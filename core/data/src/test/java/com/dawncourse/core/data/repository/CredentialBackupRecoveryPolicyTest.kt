package com.dawncourse.core.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 凭据原子覆盖崩溃后的备份保留策略。 */
class CredentialBackupRecoveryPolicyTest {

    @Test
    fun `主文件验证成功后才删除备份`() {
        val events = mutableListOf<String>()

        recoverCredentialBackupIfNeeded(
            mainExists = { events += "main-exists"; true },
            backupExists = { events += "backup-exists"; true },
            mainIsValid = { events += "validate-main"; true },
            restoreBackup = { events += "restore" },
            discardBackup = { events += "discard" },
        )

        assertEquals(
            listOf("backup-exists", "main-exists", "validate-main", "discard"),
            events,
        )
    }

    @Test
    fun `主文件存在但验证失败时恢复备份而不删除它`() {
        val events = mutableListOf<String>()

        recoverCredentialBackupIfNeeded(
            mainExists = { events += "main-exists"; true },
            backupExists = { events += "backup-exists"; true },
            mainIsValid = { events += "validate-main"; false },
            restoreBackup = { events += "restore" },
            discardBackup = { events += "discard" },
        )

        assertEquals(
            listOf("backup-exists", "main-exists", "validate-main", "restore"),
            events,
        )
    }

    @Test
    fun `主文件缺失时直接恢复备份且不尝试验证不存在的主文件`() {
        val events = mutableListOf<String>()

        recoverCredentialBackupIfNeeded(
            mainExists = { events += "main-exists"; false },
            backupExists = { events += "backup-exists"; true },
            mainIsValid = { events += "validate-main"; false },
            restoreBackup = { events += "restore" },
            discardBackup = { events += "discard" },
        )

        assertEquals(listOf("backup-exists", "main-exists", "restore"), events)
    }

    @Test
    fun `清除凭据先收敛恢复状态再同时删除主文件与备份`() = runBlocking {
        val events = mutableListOf<String>()

        clearCredentialFiles(
            recoverBackups = { events += "recover" },
            deleteRestoreStaging = { events += "delete-restore" },
            deleteMain = { events += "delete-main" },
            deleteBackup = { events += "delete-backup" },
        )

        assertEquals(
            listOf("recover", "delete-restore", "delete-backup", "delete-main"),
            events,
        )
    }

    @Test
    fun `备份删除失败时不删主文件以避免下次启动复活`() = runBlocking {
        val events = mutableListOf<String>()

        runCatching {
            clearCredentialFiles(
                recoverBackups = { events += "recover" },
                deleteRestoreStaging = { events += "delete-restore" },
                deleteMain = { events += "delete-main" },
                deleteBackup = {
                    events += "delete-backup"
                    error("无法删除备份")
                },
            )
        }

        assertEquals(listOf("recover", "delete-restore", "delete-backup"), events)
    }

    @Test
    fun `主凭据备份和恢复临时文件映射到同一受管文件`() {
        val main = ProfileCredentialFileNamer.fileName("123e4567-e89b-12d3-a456-426614174000")

        assertEquals(main, credentialOwnerFileName(main))
        assertEquals(main, credentialOwnerFileName("$main.bak"))
        assertEquals(main, credentialOwnerFileName("$main.restore"))
        assertEquals(null, credentialOwnerFileName("unmanaged.restore"))
    }

    @Test
    fun `备份恢复成功前始终保留原备份`() {
        val events = mutableListOf<String>()

        restoreCredentialBackupSafely(
            stageBackupCopy = { events += "stage-copy" },
            replaceMainAtomically = { events += "replace-main" },
            mainIsValid = { events += "validate-main"; true },
            discardBackup = { events += "discard-backup" },
        )

        assertEquals(
            listOf("stage-copy", "replace-main", "validate-main", "discard-backup"),
            events,
        )
    }

    @Test
    fun `备份恢复验证失败时不删除原备份`() {
        val events = mutableListOf<String>()

        val failure = runCatching {
            restoreCredentialBackupSafely(
                stageBackupCopy = { events += "stage-copy" },
                replaceMainAtomically = { events += "replace-main" },
                mainIsValid = { events += "validate-main"; false },
                discardBackup = { events += "discard-backup" },
            )
        }.exceptionOrNull()

        assertEquals(
            listOf("stage-copy", "replace-main", "validate-main"),
            events,
        )
        assertEquals("恢复后的凭据备份未通过回读验证", failure?.message)
    }
}
