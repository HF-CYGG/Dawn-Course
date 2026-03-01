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
    provider: SyncProviderType = SyncProviderType.QIDI,
    viewModel: QidiSyncViewModel = hiltViewModel(),
    importViewModel: ImportViewModel = hiltViewModel()
){
    val providerName = when (provider) {
        SyncProviderType.QIDI -> "起迪"
        SyncProviderType.ZF -> "正方"
        else -> "教务"
    }
    var title by remember { mutableStateOf("$providerName 一键更新（实验）") }
    var subTitle by remember { mutableStateOf("请确认已在设置中绑定${providerName}账号") }
    var loading by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    var credsForAutoFill by remember { mutableStateOf<com.dawncourse.core.domain.model.SyncCredentials?>(null) }
    var needManualLogin by remember { mutableStateOf(false) }
    var addressBar by remember { mutableStateOf("") }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = addressBar,
                    onValueChange = { addressBar = it },
                    label = { Text("网址") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    val wv = webView ?: return@OutlinedButton
                    var url = addressBar.trim()
                    if (url.isNotEmpty() && !(url.startsWith("http://") || url.startsWith("https://"))) {
                        url = "https://$url"
                    }
                    if (url.isNotEmpty()) {
                        wv.loadUrl(url)
                    }
                }) { Text("前往") }
            }
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
                        val js = buildAutoFillScript(
                            username = creds.username ?: "",
                            password = creds.secret
                        )
                        wv.evaluateJavascript(js, null)
                    }
                    // 检测正方登录页的错误与验证码可见性，提示用户手动输入；并在首次进入登录页/课表页时自动记录入口
                    if (provider == SyncProviderType.ZF) {
                        scope.launch {
                            try {
                                val res = suspendEvaluateJs(
                                    wv,
                                    """
                                    (function(){
                                      try{
                                        var tips=document.querySelector('#tips');
                                        var tipTxt=tips? (tips.innerText||'') : '';
                                        var hasWrong = tipTxt.indexOf('用户名或密码不正确')>=0;
                                        var yzmDiv=document.querySelector('#yzmDiv');
                                        var yzmVis=false;
                                        if(yzmDiv){
                                          var cs=getComputedStyle(yzmDiv);
                                          yzmVis = !(cs.display==='none' || cs.visibility==='hidden');
                                        }
                                        var isLogin = !!document.querySelector('#yhm') || !!document.querySelector('#dl');
                                        var isKebiao = !!document.querySelector('#ylkbTable') || !!document.querySelector('#ajaxForm');
                                        var href = location.href;
                                        return JSON.stringify({wrong:!!hasWrong, yzm:!!yzmVis, isLogin:isLogin, isKebiao:isKebiao, href:href});
                                      }catch(e){ return JSON.stringify({wrong:false,yzm:false,isLogin:false,isKebiao:false,href:''}); }
                                    })();
                                    """.trimIndent()
                                )
                                val txt = parseJsReturn(res)
                                val wrong = txt.contains("\"wrong\":true")
                                val yzm = txt.contains("\"yzm\":true")
                                needManualLogin = wrong || yzm
                                if (needManualLogin) {
                                    subTitle = "检测到登录失败或需要验证码，请在页面中手动输入后点击“继续登录/提取”。"
                                }
                                val hrefMatch = Regex("\"href\":\"(.*?)\"").find(txt)
                                val href = hrefMatch?.groups?.get(1)?.value ?: ""
                                if (href.isNotBlank()) {
                                    val updated = viewModel.updateEndpointIfNeeded(href, provider)
                                    if (updated) {
                                        subTitle = "已自动记录登录入口，后续可直接“开始同步（$providerName）”。"
                                    }
                                }
                            } catch (_: Throwable) {
                                // ignore
                            }
                        }
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
                            val needProvider = provider
                            if (creds == null || creds.provider != needProvider || creds.type != SyncCredentialType.PASSWORD) {
                                subTitle = "未绑定${providerName}凭据，请在设置中绑定"
                                return@launch
                            }
                            val endpoint = creds.endpointUrl
                            credsForAutoFill = creds
                            loading = true
                            subTitle = if (endpoint.isNullOrBlank()) {
                                "请在上方地址栏手动输入或通过门户进入${providerName}教务系统，系统将自动记录入口"
                            } else {
                                "正在打开${providerName}教务系统..."
                            }
                            val wv = webView ?: return@launch
                            if (!endpoint.isNullOrBlank()) {
                                wv.loadUrl(endpoint)
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" 开始同步（$providerName）")
                }
            }
            if (provider == SyncProviderType.ZF && needManualLogin) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            webView?.evaluateJavascript(
                                "var b=document.querySelector('#dl'); if(b){b.click();} void 0;"
                            , null)
                        }
                    ) { Text("继续登录") }
                    OutlinedButton(
                        onClick = {
                            webView?.evaluateJavascript(
                                "var i=document.querySelector('#yzmPic'); if(i){ i.click(); } void 0;"
                            , null)
                        }
                    ) { Text("刷新验证码") }
                    OutlinedButton(
                        onClick = {
                            val wv = webView ?: return@OutlinedButton
                            scope.launch {
                                val href = parseJsReturn(suspendEvaluateJs(wv, "location.href"))
                                val updated = viewModel.updateEndpointIfNeeded(href, provider)
                                if (updated) {
                                    subTitle = "已记录当前为登录入口"
                                } else {
                                    subTitle = "入口已存在或记录失败"
                                }
                            }
                        }
                    ) { Text("记录当前为入口") }
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
                                val qidiProvider = if (provider == SyncProviderType.QIDI) {
                                    // 起迪：尝试使用 provider 脚本
                                    runCatching { assets.open("js/qidi_provider.js").bufferedReader().use { it.readText() } }.getOrNull()
                                } else null
                                val js = buildString {
                                    append("(function(){try{")
                                    append("window.__dawnResult=null;window.__dawnReady=false;")
                                    append(outputConsole).append(";")
                                    append(courseUtils).append(";")
                                    if (qidiProvider != null) append(qidiProvider).append(";")
                                    if (provider == SyncProviderType.ZF) {
                                        append("""
                                          async function waitForSelector(sel, timeout){
                                            return new Promise((resolve,reject)=>{
                                              var t=0; var it=setInterval(function(){
                                                var el=document.querySelector(sel);
                                                if(el){ clearInterval(it); resolve(el); }
                                                t+=200; if(t>=timeout){ clearInterval(it); resolve(null); }
                                              },200);
                                            });
                                          }
                                          async function openZfTimetableAndQuery(){
                                            // 1) 若当前已在课表页，直接进行查询
                                            if(document.querySelector('#ylkbTable') || document.querySelector('#ajaxForm')){
                                              // 继续走查询按钮，提高成功率
                                            }else{
                                              // 2) 导航到“信息查询 -> 个人课表查询”
                                              var link = Array.from(document.querySelectorAll('a')).find(a=>{
                                                var href=(a.getAttribute('href')||'').toLowerCase();
                                                var txt=(a.textContent||'').trim();
                                                return href.indexOf('xskbcx')>=0 || txt.indexOf('个人课表查询')>=0;
                                              });
                                              if(link){ link.click(); }
                                              await waitForSelector('#ajaxForm, #ylkbTable', 8000);
                                            }
                                            
                                            // 3) 设置学年/学期: 若 select 存在，优先选择已选；否则保持默认
                                            try{
                                              var xnm=document.querySelector('#xnm');
                                              var xqm=document.querySelector('#xqm');
                                              if(xnm){
                                                // 保持当前选中项；若为空则选择最后一个非空项
                                                if(!xnm.value){
                                                  for(var i=xnm.options.length-1;i>=0;i--){
                                                    if(xnm.options[i].value){ xnm.value=xnm.options[i].value; break; }
                                                  }
                                                }
                                                xnm.dispatchEvent(new Event('change',{bubbles:true}));
                                              }
                                              if(xqm){
                                                if(!xqm.value){
                                                  for(var j=xqm.options.length-1;j>=0;j--){
                                                    if(xqm.options[j].value){ xqm.value=xqm.options[j].value; break; }
                                                  }
                                                }
                                                xqm.dispatchEvent(new Event('change',{bubbles:true}));
                                              }
                                            }catch(e){}
                                            
                                            // 4) 点击查询按钮
                                            var btn=document.querySelector('#search_go') || Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').indexOf('查询')>=0);
                                            if(btn){ btn.click(); }
                                            
                                            // 5) 等待表格渲染
                                            await waitForSelector('#ylkbTable, #kblist_table, #kbgrid_table_0', 8000);
                                          }
                                        """.trimIndent())
                                    }
                                    append("""
                                        (async function(){
                                          try{
                                            if(typeof scheduleHtmlProvider==='function' && ${if (provider == SyncProviderType.ZF) "false" else "true"}){
                                              var result=await scheduleHtmlProvider();
                                              if(result!=="do not continue"){
                                                window.__dawnResult=result;window.__dawnReady=true;return;
                                              }
                                            }
                                            ${if (provider == SyncProviderType.ZF) "await openZfTimetableAndQuery();" else ""}
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
 * 生成更健壮的自动填充脚本：
 * - 针对常见字段名：yhm/xh/username/account 等，密码：mm/pwd/password
 * - 触发 input/change/blur 事件
 * - 尝试在同域 iframe 中执行相同逻辑
 */
private fun buildAutoFillScript(username: String, password: String): String {
    val userEsc = username.replace("'", "\\'")
    val passEsc = password.replace("'", "\\'")
    return """
      (function(){
        function tryFill(doc){
          try{
            var U='$userEsc', P='$passEsc';
            var userSel = 'input[name="yhm"],input[name*="user"],input[name*="account"],input[id*="user"],input[id*="account"],input[name="xh"],input[id*="xh"],input[type="text"]';
            var passSel = 'input[name="mm"],input[name*="pwd"],input[id*="pwd"],input[type="password"]';
            var userEl = doc.querySelector(userSel);
            var passEl = doc.querySelector(passSel);
            if(userEl){ userEl.value = U; userEl.dispatchEvent(new Event('input',{bubbles:true})); userEl.dispatchEvent(new Event('change',{bubbles:true})); userEl.dispatchEvent(new Event('blur',{bubbles:true})); }
            if(passEl){ passEl.value = P; passEl.dispatchEvent(new Event('input',{bubbles:true})); passEl.dispatchEvent(new Event('change',{bubbles:true})); passEl.dispatchEvent(new Event('blur',{bubbles:true})); }
            var form = (passEl && passEl.form) || (userEl && userEl.form) || doc.querySelector('form');
            if(form){
              // 若存在验证码输入区域，则暂停自动提交，等待用户手动输入
              var yzmEl = doc.querySelector('#yzm') || doc.querySelector('input[name="yzm"]');
              var yzmDiv = doc.querySelector('#yzmDiv');
              var needCaptcha = false;
              try{
                if(yzmEl){ needCaptcha = true; }
                if(yzmDiv){
                  var cs = getComputedStyle(yzmDiv);
                  if(!(cs.display==='none' || cs.visibility==='hidden')) needCaptcha = true;
                }
              }catch(e){}
              if(needCaptcha){ return; }
              var btn = form.querySelector('button[type="submit"],input[type="submit"],button[id*="login"],button[name*="login"],button[class*="login"]');
              if(btn){ btn.click(); } else { try{ form.submit(); }catch(e){} }
            }
          }catch(e){}
        }
        tryFill(document);
        var iframes = document.querySelectorAll('iframe');
        for(var i=0;i<iframes.length;i++){
          try{ var idoc = iframes[i].contentDocument; if(idoc) tryFill(idoc); }catch(e){}
        }
      })();
    """.trimIndent()
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
     * 若当前绑定与 provider 一致，且尚未记录入口或入口不同，则更新入口。
     *
     * @return 是否发生更新
     */
    suspend fun updateEndpointIfNeeded(href: String, provider: SyncProviderType): Boolean {
        val creds = credentialsRepository.getCredentials() ?: return false
        if (creds.provider != provider) return false
        val normalized = normalizeEndpoint(href)
        if (normalized.isBlank()) return false
        if (creds.endpointUrl == normalized) return false
        credentialsRepository.saveCredentials(
            creds.copy(endpointUrl = normalized)
        )
        return true
    }

    /**
     * 规范化入口地址：
     * - 优先截断至 /jwglxt 根（若存在）
     * - 否则保留 scheme://host[:port]
     */
    private fun normalizeEndpoint(href: String): String {
        return try {
            val uri = java.net.URI(href)
            val base = "${uri.scheme}://${uri.host}" + (if (uri.port in 1..65535) ":${uri.port}" else "")
            val path = uri.path ?: ""
            if (path.contains("/jwglxt")) {
                val idx = path.indexOf("/jwglxt")
                base + path.substring(0, idx + "/jwglxt".length)
            } else {
                base
            }
        } catch (_: Exception) {
            ""
        }
    }

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
