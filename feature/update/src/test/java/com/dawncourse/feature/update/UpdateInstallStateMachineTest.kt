/**
 * 文件说明：验证系统授权页与安装器之间的单次交接状态机。
 */
package com.dawncourse.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallStateMachineTest {

    private val updateInfo = UpdateInfo(
        versionCode = 140,
        versionName = "1.0.6.1",
        title = "测试更新",
        content = "测试",
        downloadUrl = "https://downloads.example.com/update.apk",
        releaseDate = "2026-09-02",
        sha256 = "a".repeat(64)
    )
    private val updatePackage = DownloadedUpdatePackage(
        filePath = "/cache/update_packages/update.apk",
        fileName = "update.apk",
        expectedSha256 = "a".repeat(64),
        expectedVersionCode = 140L
    )

    @Test
    fun `同一 Ready 状态只能开始一个交接 attempt`() {
        val ready = UpdateUiState.ReadyToInstall(updateInfo, updatePackage)

        val handoff = beginInstallHandoff(
            ready,
            updatePackage,
            attemptId = 7L,
            phase = InstallHandoffPhase.AWAITING_PERMISSION
        )

        requireNotNull(handoff)
        assertEquals(7L, handoff.attemptId)
        assertNull(
            beginInstallHandoff(
                handoff,
                updatePackage,
                attemptId = 8L,
                phase = InstallHandoffPhase.AWAITING_PERMISSION
            )
        )
    }

    @Test
    fun `只有当前 attempt 能推进安装器或恢复`() {
        val handoff = UpdateUiState.InstallHandoff(
            updateInfo = updateInfo,
            updatePackage = updatePackage,
            attemptId = 7L,
            phase = InstallHandoffPhase.AWAITING_PERMISSION
        )

        assertNull(markInstallerPromptLaunched(handoff, attemptId = 6L))
        assertNull(restoreAvailableUpdate(handoff, expectedAttemptId = 6L))

        val installerState = markInstallerPromptLaunched(handoff, attemptId = 7L)
        requireNotNull(installerState)
        assertEquals(InstallHandoffPhase.INSTALLER_PROMPT_LAUNCHED, installerState.phase)
        assertTrue(restoreAvailableUpdate(installerState, expectedAttemptId = 7L) is UpdateUiState.Available)
    }
}
