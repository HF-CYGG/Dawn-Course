@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.feature.import_module.model.toDomainCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 起迪教务“一键更新（实验）”页面
 *
 * 设计目标：
 * - 自动使用已绑定的“入口地址 + 用户名 + 密码”进行登录
 * - 注入现有适配脚本 (qidi_provider.js) 提取课程
 * - 直接覆盖当前学期的课程数据
 *
 * 安全原则：
 * - 不打印用户名/密码/页面敏感内容
 * - 仅在用户主动点击时进行登录与提取
 */
@Composable
fun QidiAutoSyncScreen(
    onBackClick: () -> Unit,
    onFinish: () -> Unit,
    viewModel: QidiSyncViewModel = hiltViewModel(),
    importViewModel: ImportViewModel = hiltViewModel()
){
    var title by remember { mutableStateOf("起迪一键更新（实验）") }
    var subTitle by remember { mutableStateOf("请确认已在设置中绑定起迪账号") }
    var loading by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    var credsForAutoFill by remember { mutableStateOf<com.dawncourse.core.domain.model.SyncCredentials?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = subTitle, style = MaterialTheme.typography.bodyMedium)
            if (loading) {
                LinearProgressIndicator()
            }
            WebViewBox(
                onWebViewReady = { wv ->
                    webView = wv
                },
                onPageTitle = { t -> if (t.isNotBlank()) title = t },
                onPageFinished = { wv, url ->
                    val creds = credsForAutoFill
                    if (creds != null && creds.type == SyncCredentialType.PASSWORD) {
                        // 基础自动填充与提交：尽量不依赖特定 DOM 结构
                        val userEsc = (creds.username ?: "").replace("'", "\\'")
                        val passEsc = creds.secret.replace("'", "\\'")
                        val js = """
                            (function(){
                              try{
                                var U='$userEsc'; var P='$passEsc';
                                var userEl = document.querySelector('input[type="text"], input[name*="user"], input[id*="user"], input[name*="account"], input[id*="account"]');
                                var passEl = document.querySelector('input[type="password"]');
                                if(userEl){ userEl.value = U; userEl.dispatchEvent(new Event('input', {bubbles:true})); }
                                if(passEl){ passEl.value = P; passEl.dispatchEvent(new Event('input', {bubbles:true})); }
                                var form = (passEl && passEl.form) || (userEl && userEl.form) || document.querySelector('form');
                                if(form){
                                  var btn = form.querySelector('button[type="submit"], input[type="submit"]');
                                  if(btn){ btn.click(); } else { form.submit(); }
                                }
                              }catch(e){}
                            })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { webView?.reload() }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" 重新加载")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val creds = viewModel.loadQidiCredentials()
                            if (creds == null || creds.provider != SyncProviderType.QIDI || creds.type != SyncCredentialType.PASSWORD) {
                                subTitle = "未绑定起迪凭据，请在设置中绑定"
                                return@launch
                            }
                            val endpoint = creds.endpointUrl ?: run {
                                subTitle = "未设置入口地址"
                                return@launch
                            }
                            credsForAutoFill = creds
                            loading = true
                            subTitle = "正在打开教务系统..."
                            val wv = webView ?: return@launch
                            wv.loadUrl(endpoint)
                        }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" 开始同步")
                }
            }
            Row {
                OutlinedButton(
                    onClick = {
                        // 注入脚本并提取，随后写入数据库
                        scope.launch {
                            loading = true
                            subTitle = "正在提取课程..."
                            val wv = webView ?: run {
                                loading = false
                                subTitle = "WebView 未初始化"
                                return@launch
                            }
                            try {
                                val context = wv.context
                                val assets = context.assets
                                val outputConsole = assets.open("js/output_console.js").bufferedReader().use { it.readText() }
                                val courseUtils = assets.open("js/course_utils.js").bufferedReader().use { it.readText() }
                                val qidiProvider = assets.open("js/qidi_provider.js").bufferedReader().use { it.readText() }
                                val js = buildString {
                                    append("(function(){try{")
                                    append("window.__dawnResult=null;window.__dawnReady=false;")
                                    append(outputConsole).append(";")
                                    append(courseUtils).append(";")
                                    append(qidiProvider).append(";")
                                    append("""
                                        (async function(){
                                          try{
                                            if(typeof scheduleHtmlProvider==='function'){
                                              var result=await scheduleHtmlProvider();
                                              if(result!=="do not continue"){
                                                window.__dawnResult=result;window.__dawnReady=true;return;
                                              }
                                            }
                                          }catch(e){}
                                          window.__dawnResult=document.documentElement.outerHTML;window.__dawnReady=true;
                                        })();
                                    """.trimIndent())
                                    append("}catch(e){window.__dawnReady=true;}})();")
                                }
                                wv.evaluateJavascript(js, null)
                                // 轮询读取结果
                                var attempts = 0
                                var raw = ""
                                while (attempts < 40) {
                                    val r = suspendEvaluateJs(wv, "window.__dawnReady?window.__dawnResult:null")
                                    val parsed = parseJsReturn(r)
                                    if (parsed.isNotBlank()) {
                                        raw = parsed
                                        break
                                    }
                                    attempts++
                                    kotlinx.coroutines.delay(300)
                                }
                                if (raw.isBlank()) {
                                    subTitle = "未能提取到有效内容，请确认已登录并处于课表页"
                                    loading = false
                                    return@launch
                                }
                                importViewModel.parseResultFromWebView(raw)
                                // 等待解析进入 Review 状态并拿到课程
                                var wait = 0
                                var parsed: List<ParsedCourse> = emptyList()
                                while (wait < 40) {
                                    val ui = importViewModel.uiState.value
                                    parsed = ui.parsedCourses
                                    if (parsed.isNotEmpty()) break
                                    wait++
                                    kotlinx.coroutines.delay(200)
                                }
                                if (parsed.isEmpty()) {
                                    subTitle = "解析失败或未发现课程"
                                    loading = false
                                    return@launch
                                }
                                val count = viewModel.applyToCurrentSemester(parsed)
                                subTitle = "同步成功：更新 ${count} 门课程"
                                loading = false
                                onFinish()
                            } catch (_: Throwable) {
                                loading = false
                                subTitle = "提取失败：请重试或更换入口"
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" 提取并覆盖当前学期")
                }
            }
        }
    }
}

/**
 * WebView 容器
 *
 * 开启 JS、允许存储、启用 Cookie，注入基础登录辅助逻辑：
 * - 当检测到常见登录表单时，尝试自动填充用户名与密码并提交
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBox(
    onWebViewReady: (WebView) -> Unit,
    onPageTitle: (String) -> Unit,
    onPageFinished: (WebView, String) -> Unit = {_, _ -> }
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        factory = { context ->
            val wv = WebView(context)
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    onPageTitle(view.title ?: "")
                    onPageFinished(view, url)
                }
            }
            onWebViewReady(wv)
            wv
        }
    )
}

/**
 * 挂起执行 JS 并返回字符串
 */
private suspend fun suspendEvaluateJs(webView: WebView, script: String): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { value ->
            if (cont.isActive) cont.resume(value) {}
        }
    }

/**
 * 解析 evaluateJavascript 返回的字符串（去除引号与转义）
 */
private fun parseJsReturn(value: String?): String {
    if (value == null) return ""
    var v = value
    if (v.startsWith("\"") && v.endsWith("\"")) {
        v = v.substring(1, v.length - 1)
        v = v.replace("\\n", "\n").replace("\\\"", "\"")
    }
    return v
}

/**
 * 起迪同步 ViewModel
 *
 * 负责：
 * - 读取已绑定的起迪凭据
 * - 将解析结果覆盖写入当前学期
 */
@HiltViewModel
class QidiSyncViewModel @Inject constructor(
    private val credentialsRepository: CredentialsRepository,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository
) : androidx.lifecycle.ViewModel() {

    /**
     * 读取起迪凭据
     */
    suspend fun loadQidiCredentials() = credentialsRepository.getCredentials()

    /**
     * 覆盖写入当前学期课程
     *
     * @return 写入课程数量
     */
    suspend fun applyToCurrentSemester(parsed: List<ParsedCourse>): Int {
        val current = semesterRepository.getCurrentSemester().first() ?: return 0
        courseRepository.deleteCoursesBySemester(current.id)
        val domainCourses = parsed.map { it.toDomainCourse().copy(semesterId = current.id) }
        courseRepository.insertCourses(domainCourses)
        return domainCourses.size
    }
}
