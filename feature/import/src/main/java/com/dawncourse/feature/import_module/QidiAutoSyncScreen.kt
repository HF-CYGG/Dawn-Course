@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    var targetYear by remember { mutableStateOf("") }
    var targetTerm by remember { mutableStateOf("") }
    var pendingLoadUrl by remember { mutableStateOf<String?>(null) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("等待开始") }
    val logItems = remember { mutableStateListOf<String>() }
    val logScrollState = rememberScrollState()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    var captchaUrl by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }

    fun addLog(message: String) {
        logItems.add("${LocalTime.now().format(timeFormatter)} $message")
    }

    LaunchedEffect(provider) {
        addLog("初始化同步页面：$providerName")
        val creds = viewModel.loadQidiCredentials()
        val needProvider = provider
        if (creds == null || creds.provider != needProvider || creds.type != SyncCredentialType.PASSWORD) {
            subTitle = "未绑定${providerName}凭据，请在设置中绑定"
            addLog("未找到可用凭据或提供者不一致")
            return@LaunchedEffect
        }
        credsForAutoFill = creds
        val endpoint = creds.endpointUrl
        if (!endpoint.isNullOrBlank()) {
            val normalized = normalizeEndpointForLoad(endpoint)
            if (normalized.isNullOrBlank()) {
                subTitle = "入口地址无效，请在设置中重新绑定"
                addLog("入口地址无效：$endpoint")
                return@LaunchedEffect
            }
            addressBar = normalized
            pendingLoadUrl = normalized
            if (webView != null) {
                loading = true
                webView?.loadUrl(normalized)
                addLog("自动加载入口：$normalized")
            } else {
                subTitle = "正在打开${providerName}教务系统..."
                addLog("等待 WebView 初始化")
            }
        } else {
            subTitle = "请在上方地址栏手动输入或通过门户进入${providerName}教务系统，系统将自动记录入口"
            addLog("入口地址为空，等待手动输入")
        }
    }

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
        if (showProgressDialog) {
            Dialog(onDismissRequest = { if (!loading && !needManualLogin) showProgressDialog = false }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = currentStep, style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(logScrollState)
                        ) {
                            logItems.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (needManualLogin && captchaUrl.isNotBlank()) {
                            Text("验证码", style = MaterialTheme.typography.titleSmall)
                            CaptchaImage(url = captchaUrl)
                            OutlinedTextField(
                                value = captchaCode,
                                onValueChange = { captchaCode = it },
                                label = { Text("请输入验证码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val wv = webView ?: return@OutlinedButton
                                        scope.launch {
                                            wv.evaluateJavascript(
                                                "var i=document.querySelector('#yzmPic'); if(i){ i.click(); } void 0;"
                                            , null)
                                            val res = suspendEvaluateJs(
                                                wv,
                                                "(function(){var i=document.querySelector('#yzmPic'); if(!i) return ''; var s=i.getAttribute('src')||''; try{ return new URL(s, location.href).href }catch(e){ return s; }})()"
                                            )
                                            val src = parseJsReturn(res).replace("\\/", "/")
                                            if (src.isNotBlank()) {
                                                captchaUrl = src
                                                addLog("已刷新验证码")
                                            }
                                        }
                                    }
                                ) { Text("刷新验证码") }
                                Button(
                                    onClick = {
                                        val code = captchaCode.trim()
                                        if (code.isBlank()) {
                                            addLog("验证码为空")
                                            return@Button
                                        }
                                        val wv = webView ?: return@Button
                                        val safe = escapeJs(code)
                                        wv.evaluateJavascript(
                                            "(function(){var input=document.querySelector('#yzm')||document.querySelector('input[name=\"yzm\"]')||document.querySelector('input[id*=\"yzm\"]'); if(input){input.value='$safe';} var b=document.querySelector('#dl'); if(b){b.click();}})();"
                                        , null)
                                        addLog("已提交验证码并触发登录")
                                    }
                                ) { Text("继续登录") }
                            }
                        }
                        if (!loading && !needManualLogin) {
                            OutlinedButton(
                                onClick = { showProgressDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("关闭") }
                        }
                    }
                }
            }
        }
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
                    val url = normalizeEndpointForLoad(addressBar)
                    if (url.isNullOrBlank()) {
                        subTitle = "请输入有效网址"
                        addLog("输入的地址无效：$addressBar")
                        return@OutlinedButton
                    }
                    addressBar = url
                    wv.loadUrl(url)
                    addLog("手动前往地址：$url")
                }) { Text("前往") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = targetYear,
                    onValueChange = { targetYear = it },
                    label = { Text("学年(如 2025)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = targetTerm,
                    onValueChange = { targetTerm = it },
                    label = { Text("学期(1/2/3)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            if (loading) {
                LinearProgressIndicator()
            }
            WebViewBox(
                provider = provider,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onWebViewReady = { wv ->
                    webView = wv
                    addLog("WebView 初始化完成")
                    val url = pendingLoadUrl
                    if (!url.isNullOrBlank()) {
                        loading = true
                        wv.loadUrl(url)
                        addLog("触发预加载：$url")
                    }
                },
                onPageStarted = { url ->
                    loading = true
                    if (showProgressDialog) {
                        currentStep = "页面加载中"
                        addLog("开始加载：$url")
                    }
                    if (url.isNotBlank()) {
                        addressBar = url
                    }
                },
                onPageTitle = { t -> if (t.isNotBlank()) title = t },
                onPageFinished = { wv, _ ->
                    loading = false
                    if (showProgressDialog) {
                        currentStep = "页面加载完成"
                        addLog("页面加载完成")
                    }
                    val creds = credsForAutoFill
                    if (creds != null && creds.type == SyncCredentialType.PASSWORD) {
                        val js = buildAutoFillScript(
                            username = creds.username ?: "",
                            password = creds.secret
                        )
                        wv.evaluateJavascript(js, null)
                        if (showProgressDialog) {
                            addLog("已执行自动填充脚本")
                        }
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
                                        var yzmSrc = '';
                                        var yzmImg = document.querySelector('#yzmPic');
                                        if(yzmImg){
                                          var s = yzmImg.getAttribute('src') || '';
                                          try{ yzmSrc = new URL(s, location.href).href; }catch(e){ yzmSrc = s; }
                                        }
                                        var isLogin = !!document.querySelector('#yhm') || !!document.querySelector('#dl');
                                        var isKebiao = !!document.querySelector('#ylkbTable') || !!document.querySelector('#ajaxForm');
                                        var href = location.href;
                                        return JSON.stringify({wrong:!!hasWrong, yzm:!!yzmVis, yzmSrc:yzmSrc, isLogin:isLogin, isKebiao:isKebiao, href:href});
                                      }catch(e){ return JSON.stringify({wrong:false,yzm:false,yzmSrc:'',isLogin:false,isKebiao:false,href:''}); }
                                    })();
                                    """.trimIndent()
                                )
                                val txt = parseJsReturn(res)
                                val wrong = txt.contains("\"wrong\":true")
                                val yzm = txt.contains("\"yzm\":true")
                                needManualLogin = wrong || yzm
                                val yzmSrcMatch = Regex("\"yzmSrc\":\"(.*?)\"").find(txt)
                                val yzmSrc = yzmSrcMatch?.groups?.get(1)?.value?.replace("\\/", "/").orEmpty()
                                captchaUrl = if (yzm && yzmSrc.isNotBlank()) yzmSrc else ""
                                if (needManualLogin) {
                                    subTitle = "检测到登录失败或需要验证码，请在页面中手动输入后点击“继续登录/提取”。"
                                    if (showProgressDialog) {
                                        currentStep = "需要验证码或手动登录"
                                        addLog("检测到验证码或登录失败")
                                        if (yzm) addLog("验证码已可见")
                                        if (wrong) addLog("检测到账号或密码错误提示")
                                        if (captchaUrl.isNotBlank()) addLog("验证码地址已获取")
                                    }
                                }
                                val hrefMatch = Regex("\"href\":\"(.*?)\"").find(txt)
                                val href = hrefMatch?.groups?.get(1)?.value ?: ""
                                if (href.isNotBlank()) {
                                    val updated = viewModel.updateEndpointIfNeeded(href, provider)
                                    if (updated) {
                                        subTitle = "已自动记录登录入口，后续可直接“开始同步（$providerName）”。"
                                        if (showProgressDialog) {
                                            addLog("已更新入口地址")
                                        }
                                    }
                                    if (showProgressDialog) {
                                        addLog("当前地址：$href")
                                    }
                                }
                            } catch (_: Throwable) {
                                // ignore
                            }
                        }
                    }
                },
                onPageError = { desc ->
                    loading = false
                    if (desc.isNotBlank()) {
                        subTitle = "页面加载失败：$desc"
                        if (showProgressDialog) {
                            currentStep = "页面加载失败"
                            addLog("页面加载失败：$desc")
                        }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        webView?.reload()
                        addLog("手动刷新页面")
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" 重新加载")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            showProgressDialog = true
                            currentStep = "读取绑定凭据"
                            addLog("开始读取绑定凭据")
                            val creds = viewModel.loadQidiCredentials()
                            val needProvider = provider
                            if (creds == null || creds.provider != needProvider || creds.type != SyncCredentialType.PASSWORD) {
                                subTitle = "未绑定${providerName}凭据，请在设置中绑定"
                                currentStep = "未绑定账号"
                                addLog("未绑定${providerName}凭据")
                                return@launch
                            }
                            val endpoint = creds.endpointUrl
                            credsForAutoFill = creds
                            addLog("已读取绑定凭据")
                            loading = true
                            subTitle = if (endpoint.isNullOrBlank()) {
                                "请在上方地址栏手动输入或通过门户进入${providerName}教务系统，系统将自动记录入口"
                            } else {
                                "正在打开${providerName}教务系统..."
                            }
                            currentStep = "打开教务系统"
                            addLog("准备加载入口地址")
                            val wv = webView ?: return@launch
                            if (!endpoint.isNullOrBlank()) {
                                val normalized = normalizeEndpointForLoad(endpoint)
                                if (normalized.isNullOrBlank()) {
                                    loading = false
                                    subTitle = "入口地址无效，请在设置中重新绑定"
                                    currentStep = "入口地址无效"
                                    addLog("入口地址无效")
                                    return@launch
                                }
                                addressBar = normalized
                                wv.loadUrl(normalized)
                                addLog("开始加载入口：$normalized")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" 开始同步（$providerName）")
                }
            }
            Row {
                OutlinedButton(
                    onClick = {
                        // 注入脚本并提取，随后写入数据库
                        scope.launch {
                            showProgressDialog = true
                            currentStep = "注入脚本并提取课程"
                            addLog("开始提取课程")
                            loading = true
                            subTitle = "正在提取课程..."
                            val wv = webView ?: run {
                                loading = false
                                subTitle = "WebView 未初始化"
                                currentStep = "WebView 未初始化"
                                addLog("WebView 未初始化")
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
                                addLog("已加载解析脚本")
                                val js = buildString {
                                    append("(function(){try{")
                                    append("window.__dawnResult=null;window.__dawnReady=false;")
                                    append(outputConsole).append(";")
                                    append(courseUtils).append(";")
                                    if (qidiProvider != null) append(qidiProvider).append(";")
                                    if (provider == SyncProviderType.ZF) {
                                        val y = targetYear.trim()
                                        val q = targetTerm.trim()
                                        val termCode = when (q) {
                                            "1" -> "3"
                                            "2" -> "12"
                                            "3" -> "16"
                                            "12", "16" -> q
                                            else -> ""
                                        }
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
                                              var fixedYear='${y}';
                                              var fixedTerm='${termCode}';
                                              if(xnm){
                                                if(fixedYear && fixedYear.length===4){ xnm.value=fixedYear; }
                                                if(!xnm.value){ for(var i=xnm.options.length-1;i>=0;i--){ if(xnm.options[i].value){ xnm.value=xnm.options[i].value; break; } } }
                                                xnm.dispatchEvent(new Event('change',{bubbles:true}));
                                              }
                                              if(xqm){
                                                if(fixedTerm){ xqm.value=fixedTerm; }
                                                if(!xqm.value){ for(var j=xqm.options.length-1;j>=0;j--){ if(xqm.options[j].value){ xqm.value=xqm.options[j].value; break; } } }
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
                                addLog("已注入提取脚本")
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
                                    if (attempts % 10 == 0) {
                                        addLog("等待页面返回数据：${attempts}/40")
                                    }
                                    attempts++
                                    kotlinx.coroutines.delay(300)
                                }
                                if (raw.isBlank()) {
                                    subTitle = "未能提取到有效内容，请确认已登录并处于课表页"
                                    loading = false
                                    currentStep = "未提取到有效内容"
                                    addLog("未提取到有效内容")
                                    return@launch
                                }
                                addLog("页面数据已获取")
                                importViewModel.parseResultFromWebView(raw)
                                addLog("开始解析课程数据")
                                // 等待解析进入 Review 状态并拿到课程
                                var wait = 0
                                var parsed: List<ParsedCourse> = emptyList()
                                while (wait < 40) {
                                    val ui = importViewModel.uiState.value
                                    parsed = ui.parsedCourses
                                    if (parsed.isNotEmpty()) break
                                    if (wait % 10 == 0) {
                                        addLog("等待解析结果：${wait}/40")
                                    }
                                    wait++
                                    kotlinx.coroutines.delay(200)
                                }
                                if (parsed.isEmpty()) {
                                    subTitle = "解析失败或未发现课程"
                                    loading = false
                                    currentStep = "解析失败"
                                    addLog("解析失败或未发现课程")
                                    return@launch
                                }
                                currentStep = "写入课程数据"
                                addLog("解析完成，课程数：${parsed.size}")
                                val count = viewModel.applyToCurrentSemester(parsed)
                                subTitle = "同步成功：更新 ${count} 门课程"
                                loading = false
                                currentStep = "同步完成"
                                addLog("同步成功：更新 ${count} 门课程")
                                showProgressDialog = false
                                onFinish()
                            } catch (_: Throwable) {
                                loading = false
                                subTitle = "提取失败：请重试或更换入口"
                                currentStep = "提取失败"
                                addLog("提取失败")
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

@Composable
private fun CaptchaImage(url: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 160.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.Transparent.toArgb())
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
        },
        update = { view ->
            if (url.isNotBlank()) {
                view.loadUrl(url)
            }
        }
    )
}

private fun escapeJs(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private fun normalizeEndpointForLoad(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    if (URLUtil.isNetworkUrl(withScheme)) {
        return withScheme
    }
    val guessed = URLUtil.guessUrl(withScheme)
    return if (URLUtil.isNetworkUrl(guessed)) guessed else null
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
    provider: SyncProviderType,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView) -> Unit,
    onPageStarted: (String) -> Unit = {},
    onPageTitle: (String) -> Unit,
    onPageFinished: (WebView, String) -> Unit = {_, _ -> },
    onPageError: (String) -> Unit = {}
) {
    AndroidView(
        modifier = modifier.padding(top = 8.dp),
        factory = { context ->
            val wv = WebView(context)
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            if (provider == SyncProviderType.ZF) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = settings.userAgentString.replace("wv", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }
            wv.webChromeClient = WebChromeClient()
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onPageStarted(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    onPageTitle(view.title ?: "")
                    onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        onPageError(error.description?.toString().orEmpty())
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame) {
                        onPageError("HTTP ${errorResponse.statusCode}")
                    }
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
