package com.dawncourse.feature.import_module.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 验证 maintained wrapper 的 native Promise job drain 语义，没有公开 pending-job API 依赖。 */
@RunWith(AndroidJUnit4::class)
class QuickJsRuntimeAdapterInstrumentedTest {
    @Test
    fun evaluateReturnsAfterNativePromiseJobsAreDrained() {
        val runtime = ThreadConfinedQuickJsRuntimeAdapter(HarlonQuickJsRuntimeFactory.create())

        try {
            runtime.evaluate(
                """
                globalThis.__dawnPromiseValue = "pending";
                Promise.resolve("settled").then(function(value) {
                  globalThis.__dawnPromiseValue = value;
                });
                """.trimIndent()
            )

            assertEquals(
                QuickJsEvaluationValue.TextValue("settled"),
                runtime.evaluate("globalThis.__dawnPromiseValue")
            )
        } finally {
            runtime.close()
            runtime.close()
        }
    }
}
