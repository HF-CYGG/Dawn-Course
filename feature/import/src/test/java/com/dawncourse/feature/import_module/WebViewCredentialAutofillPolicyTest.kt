package com.dawncourse.feature.import_module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewCredentialAutofillPolicyTest {

    @Test
    fun `allows autofill only on the saved HTTPS origin with the effective HTTPS port`() {
        assertTrue(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "https://jw.example.edu.cn:443/login?service=portal",
            ),
        )
    }

    @Test
    fun `rejects a different HTTPS host`() {
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "https://sso.example.edu.cn/login",
            ),
        )
    }

    @Test
    fun `rejects a different effective port`() {
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn:8443/jwglxt",
                currentWebViewUrl = "https://jw.example.edu.cn/login",
            ),
        )
    }

    @Test
    fun `rejects HTTP and scheme downgrade`() {
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "http://jw.example.edu.cn/login",
            ),
        )
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "http://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "https://jw.example.edu.cn/login",
            ),
        )
    }

    @Test
    fun `allows an explicitly saved HTTP endpoint only on the same effective origin`() {
        assertTrue(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "http://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "http://jw.example.edu.cn:80/login",
            ),
        )
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "http://jw.example.edu.cn:8080/jwglxt",
                currentWebViewUrl = "http://jw.example.edu.cn/login",
            ),
        )
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "http://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "http://sso.example.edu.cn/login",
            ),
        )
    }

    @Test
    fun `rejects malformed or non web URLs`() {
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "https://jw.example.edu.cn:bad-port/login",
            ),
        )
        assertFalse(
            WebViewCredentialAutofillPolicy.canAutoFill(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "javascript:alert(1)",
            ),
        )
    }

    @Test
    fun `does not allow an automatic endpoint update to expand the saved origin`() {
        assertFalse(
            WebViewCredentialAutofillPolicy.canUpdateSavedEndpoint(
                savedEndpointUrl = "https://jw.example.edu.cn/jwglxt",
                currentWebViewUrl = "https://sso.example.edu.cn/callback",
            ),
        )
        assertFalse(
            WebViewCredentialAutofillPolicy.canUpdateSavedEndpoint(
                savedEndpointUrl = null,
                currentWebViewUrl = "https://jw.example.edu.cn/jwglxt",
            ),
        )
    }

    @Test
    fun `refuses to build an autofill wrapper for a non web saved origin`() {
        assertNull(
            WebViewCredentialAutofillPolicy.wrapCredentialScriptForVerifiedOrigin(
                savedEndpointUrl = "javascript:alert(1)",
                credentialScript = "credentialFill()",
            ),
        )
    }

    @Test
    fun `HTTP wrapper uses the saved scheme and default port`() {
        val wrapped = WebViewCredentialAutofillPolicy.wrapCredentialScriptForVerifiedOrigin(
            savedEndpointUrl = "http://jw.example.edu.cn/jwglxt",
            credentialScript = "credentialFill()",
        )

        requireNotNull(wrapped)
        assertTrue(wrapped.contains("const expectedProtocol = \"http:\";"))
        assertTrue(wrapped.contains("const expectedPort = \"80\";"))
    }

    @Test
    fun `wraps credentials behind a synchronous document location origin check`() {
        val credentialScript = "setTimeout(function(){ credentialFill('secret'); }, 0);"

        val wrapped = WebViewCredentialAutofillPolicy.wrapCredentialScriptForVerifiedOrigin(
            savedEndpointUrl = "https://JW.Example.edu.cn:8443/jwglxt",
            credentialScript = credentialScript,
        )

        requireNotNull(wrapped)
        assertTrue(wrapped.contains("document.location"))
        assertTrue(wrapped.contains("const expectedProtocol = \"https:\";"))
        assertTrue(wrapped.contains("const expectedHost = \"jw.example.edu.cn\";"))
        assertTrue(wrapped.contains("const expectedPort = \"8443\";"))
        assertTrue(wrapped.contains("actualProtocol !== expectedProtocol"))
        assertTrue(wrapped.contains("actualHost !== expectedHost"))
        assertTrue(wrapped.contains("actualPort !== expectedPort"))
        assertEquals(
            "origin mismatch branch must run before the credential-containing script",
            true,
            wrapped.indexOf("return \"autofill_origin_mismatch\";") < wrapped.indexOf("secret"),
        )
        assertEquals(
            "origin mismatch branch must run before any asynchronous credential task can be scheduled",
            true,
            wrapped.indexOf("return \"autofill_origin_mismatch\";") < wrapped.indexOf("setTimeout"),
        )
    }
}
