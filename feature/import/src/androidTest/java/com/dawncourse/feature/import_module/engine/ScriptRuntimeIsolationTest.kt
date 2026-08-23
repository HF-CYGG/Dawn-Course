package com.dawncourse.feature.import_module.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptRuntimeIsolationTest {
    @Test
    fun synchronousInfiniteLoopKillsRuntimeAndNextParseSucceeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptEngine(context)
        val timeout = runCatching {
            engine.parseHtml(
                script = "function parse(){ while(true){} }",
                html = "<html></html>",
                harnessSource = TEST_HARNESS,
                timeoutMillis = 700
            )
        }.exceptionOrNull() as? ScriptEngine.ScriptExecutionException

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
