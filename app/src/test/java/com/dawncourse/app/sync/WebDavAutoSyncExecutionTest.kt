package com.dawncourse.app.sync

import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** WebDAV Worker 最外层策略的纯 JVM 行为测试。 */
class WebDavAutoSyncExecutionTest {

    @Test
    fun `准备态异常被转换为 retry`() = runBlocking {
        val outcome = WebDavAutoSyncExecution.execute(
            readiness = { error("database unavailable") },
            isEnabled = { error("settings must not run") },
            upload = { error("upload must not run") },
        )

        assertEquals(WebDavAutoSyncOutcome.RETRY, outcome)
    }

    @Test
    fun `设置读取异常被转换为 retry`() = runBlocking {
        val outcome = WebDavAutoSyncExecution.execute(
            readiness = { OperationalDataReadiness.READY },
            isEnabled = { error("settings unavailable") },
            upload = { error("upload must not run") },
        )

        assertEquals(WebDavAutoSyncOutcome.RETRY, outcome)
    }

    @Test
    fun `上传临时异常被转换为 retry`() = runBlocking {
        val outcome = WebDavAutoSyncExecution.execute(
            readiness = { OperationalDataReadiness.READY },
            isEnabled = { true },
            upload = { error("temporary network failure") },
        )

        assertEquals(WebDavAutoSyncOutcome.RETRY, outcome)
    }

    @Test
    fun `任一外层阶段取消时继续传播取消`() {
        listOf<suspend () -> WebDavAutoSyncOutcome>(
            {
                WebDavAutoSyncExecution.execute(
                    readiness = { throw CancellationException("readiness cancelled") },
                    isEnabled = { true },
                    upload = { successfulUpload() },
                )
            },
            {
                WebDavAutoSyncExecution.execute(
                    readiness = { OperationalDataReadiness.READY },
                    isEnabled = { throw CancellationException("settings cancelled") },
                    upload = { successfulUpload() },
                )
            },
            {
                WebDavAutoSyncExecution.execute(
                    readiness = { OperationalDataReadiness.READY },
                    isEnabled = { true },
                    upload = { throw CancellationException("upload cancelled") },
                )
            },
        ).forEach { execution ->
            assertThrows(CancellationException::class.java) {
                runBlocking { execution() }
            }
        }
    }

    @Test
    fun `缺少凭据与认证失败均不重试`() = runBlocking {
        listOf(SyncErrorCode.NO_CREDENTIALS, SyncErrorCode.AUTH_FAILED).forEach { code ->
            val outcome = WebDavAutoSyncExecution.execute(
                readiness = { OperationalDataReadiness.READY },
                isEnabled = { true },
                upload = { failedUpload(code) },
            )

            assertEquals(WebDavAutoSyncOutcome.SUCCESS, outcome)
        }
    }

    @Test
    fun `启动中重试而恢复态和关闭开关安全结束`() = runBlocking {
        assertEquals(
            WebDavAutoSyncOutcome.RETRY,
            executeWith(OperationalDataReadiness.STARTING, enabled = true),
        )
        assertEquals(
            WebDavAutoSyncOutcome.SUCCESS,
            executeWith(OperationalDataReadiness.RECOVERY_REQUIRED, enabled = true),
        )
        assertEquals(
            WebDavAutoSyncOutcome.SUCCESS,
            executeWith(OperationalDataReadiness.READY, enabled = false),
        )
    }

    @Test
    fun `成功上传为 success 而非认证失败结果为 retry`() = runBlocking {
        assertEquals(
            WebDavAutoSyncOutcome.SUCCESS,
            WebDavAutoSyncExecution.execute(
                readiness = { OperationalDataReadiness.READY },
                isEnabled = { true },
                upload = { successfulUpload() },
            ),
        )
        assertEquals(
            WebDavAutoSyncOutcome.RETRY,
            WebDavAutoSyncExecution.execute(
                readiness = { OperationalDataReadiness.READY },
                isEnabled = { true },
                upload = { failedUpload(SyncErrorCode.NETWORK_ERROR) },
            ),
        )
    }

    /** 执行无需上传的 readiness/开关组合。 */
    private suspend fun executeWith(
        readiness: OperationalDataReadiness,
        enabled: Boolean,
    ): WebDavAutoSyncOutcome = WebDavAutoSyncExecution.execute(
        readiness = { readiness },
        isEnabled = { enabled },
        upload = { error("upload must not run") },
    )

    /** 构造成功上传结果。 */
    private fun successfulUpload(): WebDavSyncResult = WebDavSyncResult(
        success = true,
        message = "ok",
    )

    /** 构造失败上传结果。 */
    private fun failedUpload(code: SyncErrorCode): WebDavSyncResult = WebDavSyncResult(
        success = false,
        message = "failed",
        code = code,
    )
}
