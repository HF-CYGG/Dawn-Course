package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多课表管理由 app 负责导航，目标导入不得复用旧全库导入确认。 */
class ProfileManagementNavigationContractTest {
    private val mainActivity = File(
        "src/main/java/com/dawncourse/app/MainActivity.kt",
    ).readText()

    @Test
    fun appRegistersIndependentProfileManagerRoute() {
        assertTrue(mainActivity.contains("composable(\"profile_manager\")"))
        assertTrue(mainActivity.contains("onOpenProfileManager ="))
        assertTrue(mainActivity.contains("ProfileManagementScreen("))
    }

    @Test
    fun profileImportPassesFrozenTargetToProfileAwareImportScreen() {
        assertTrue(mainActivity.contains("profile_import?targetProfileId={targetProfileId}"))
        assertTrue(mainActivity.contains("onImport = { profileId ->"))
        assertTrue(mainActivity.contains("ImportScreen("))
        assertTrue(mainActivity.contains("targetProfileId = backStackEntry.arguments?.getLong"))
    }
}
