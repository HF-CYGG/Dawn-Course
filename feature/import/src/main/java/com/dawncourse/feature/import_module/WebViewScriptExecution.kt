package com.dawncourse.feature.import_module

import android.os.Build
import android.webkit.WebView
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

private const val WEBVIEW_SCRIPT_TIMEOUT_MS = 5_000L

internal suspend fun evaluateWebViewScript(
    webView: WebView,
    script: String,
    timeoutMillis: Long = WEBVIEW_SCRIPT_TIMEOUT_MS
): String = try {
    withTimeout(timeoutMillis.coerceAtMost(WEBVIEW_SCRIPT_TIMEOUT_MS)) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value ?: "null")
            }
        }
    }
} catch (_: TimeoutCancellationException) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        webView.webViewRenderProcess?.terminate()
    }
    resetWebViewAfterRendererLoss(webView)
    ""
}

internal fun resetWebViewAfterRendererLoss(webView: WebView): Boolean {
    @Suppress("UNCHECKED_CAST")
    val recreate = webView.tag as? (() -> Unit)
    webView.tag = null
    webView.destroy()
    recreate?.invoke()
    return true
}
