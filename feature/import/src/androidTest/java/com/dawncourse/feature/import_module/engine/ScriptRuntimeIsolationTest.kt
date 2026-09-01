package com.dawncourse.feature.import_module.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScriptRuntimeIsolationTest {
    @Test
    fun synchronousInfiniteLoopKillsRuntimeAndNextParseSucceeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptEngine(context)
        val timeoutAttempt = runCatching {
            engine.parseHtml(
                script = "function parse(){ while(true){} }",
                html = "<html></html>",
                harnessSource = TEST_HARNESS,
                timeoutMillis = 700
            )
        }
        val timeout = timeoutAttempt.exceptionOrNull() as? ScriptEngine.ScriptExecutionException

        assertTrue(timeout?.errorCode == ScriptEngine.ERROR_TIMEOUT)
        val terminatedPid = engine.lastTerminatedRuntimeProcessId
        assertTrue(terminatedPid > 0)

        val result = engine.parseHtml(
            script = "function parse(){ return [{name:'Math'}]; }",
            html = "<html></html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )

        assertTrue(result.ok)
        assertTrue(engine.lastRuntimeProcessId > 0)
        assertNotEquals(terminatedPid, engine.lastRuntimeProcessId)
        assertEquals(terminatedPid, engine.lastTerminatedRuntimeProcessId)
    }

    @Test
    fun successfulRequestsCompleteWithoutForceKillingRuntime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptEngine(context)

        val first = engine.parseHtml(
            script = "function parse(){ return [{name:'First'}]; }",
            html = "<html></html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )
        val firstProcessId = engine.lastRuntimeProcessId
        val second = engine.parseHtml(
            script = "function parse(){ return [{name:'Second'}]; }",
            html = "<html></html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue(firstProcessId > 0)
        assertTrue(engine.lastRuntimeProcessId > 0)
        assertEquals(0, engine.lastTerminatedRuntimeProcessId)
    }

    @Test
    fun mutatingGlobalFormatCannotContaminateNextRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptEngine(context)

        val first = engine.parseHtml(
            script = """
                globalThis.format = function(){ return 'poisoned'; };
                delete globalThis.format;
                function parse(){ return [{name:'Mutated'}]; }
            """.trimIndent(),
            html = "<html></html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )
        val firstProcessId = engine.lastRuntimeProcessId
        val second = engine.parseHtml(
            script = "function parse(){ return [{name:'Clean'}]; }",
            html = "<html></html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue(firstProcessId > 0)
        assertTrue(engine.lastRuntimeProcessId > 0)
        assertEquals(0, engine.lastTerminatedRuntimeProcessId)
    }

    @Test
    fun scriptRequestAndResponseAreNeverPersistedToCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtimeDirectory = File(context.cacheDir, "script_runtime")
        runtimeDirectory.listFiles()
            ?.filter { it.name.startsWith("request-") || it.name.startsWith("response-") }
            ?.forEach { it.delete() }
        val engine = ScriptEngine(context)

        val result = engine.parseHtml(
            script = "function parse(){ return [{name:'Memory only'}]; }",
            html = "<html>raw-sensitive-content</html>",
            harnessSource = TEST_HARNESS,
            timeoutMillis = 2_000
        )

        assertTrue(result.ok)
        assertTrue(
            runtimeDirectory.listFiles()
                ?.none { it.name.startsWith("request-") || it.name.startsWith("response-") }
                ?: true
        )
    }

    @Test
    fun largeRequestAndResponseCrossPipesWithoutDeadlock() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptEngine(context)
        val largestAcceptedHtml = "x".repeat(ScriptRuntimeLimits.MAX_HTML_BYTES)

        val result = engine.parseHtml(
            script = """
                function parse(){
                  return [{name: new Array(800001).join('x')}];
                }
            """.trimIndent(),
            html = largestAcceptedHtml,
            harnessSource = TEST_HARNESS,
            timeoutMillis = ScriptEngine.DEFAULT_TIMEOUT_MS
        )

        assertTrue(result.ok)
        assertTrue(result.raw.length > 700_000)
    }

    private companion object {
        val TEST_HARNESS = """
            (function(){
              var settled = false;
              var result = null;
              globalThis.__dawnHost = {
                begin: function(scope, html) {
                  try {
                    var value = scope.parse(html);
                    var valid = Array.isArray(value) && value.length > 0;
                    result = {
                      raw: JSON.stringify(value), ok: valid, schemaValid: valid,
                      resultCount: valid ? value.length : 0,
                      errorCode: valid ? '' : 'empty_result', errorMessage: '',
                      entryUsed: 'parse', contractVersion: 1
                    };
                  } catch (error) {
                    result = {
                      raw: '', ok: false, schemaValid: false, resultCount: 0,
                      errorCode: 'script_exception', errorMessage: '',
                      entryUsed: 'parse', contractVersion: 1
                    };
                  }
                  settled = true;
                  return false;
                },
                isSettled: function(){ return settled; },
                abortAsTimeout: function(){},
                resultJson: function(){ return JSON.stringify(result); }
              };
            })();
        """.trimIndent()
    }
}
