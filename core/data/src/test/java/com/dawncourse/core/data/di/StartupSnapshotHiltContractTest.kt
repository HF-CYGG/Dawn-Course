package com.dawncourse.core.data.di

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hilt 链必须把快照 Runtime 限制在 Context + DataStore + 快照文件，不提前解析 Room。 */
class StartupSnapshotHiltContractTest {
    @Test
    fun bindsSnapshotRepositoryAndProvidesRuntimeWithoutDatabaseArguments() {
        val source = File("src/main/java/com/dawncourse/core/data/di/DataModule.kt").readText()

        assertTrue(source.contains("StartupSnapshotRepositoryImpl"))
        assertTrue(source.contains("StartupSnapshotRepository"))
        assertTrue(source.contains("provideStartupSnapshotRuntime"))
        val provider = source.substringAfter("fun provideStartupSnapshotRuntime").substringBefore("}")
        assertTrue(provider.contains("StartupSnapshotRuntime"))
        assertTrue(provider.contains("ActiveProfileSelectionStore"))
        assertFalse(provider.contains("AppDatabase"))
        assertFalse(provider.contains("Dao"))
    }
}
