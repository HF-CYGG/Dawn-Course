package com.dawncourse.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多课表管理 UI 的模块边界、可访问性和导航契约。 */
class ProfileManagementSourceContractTest {
    private val screenSource = File(
        "src/main/java/com/dawncourse/feature/settings/ProfileManagementScreen.kt",
    )
    private val viewModelSource = File(
        "src/main/java/com/dawncourse/feature/settings/ProfileManagementViewModel.kt",
    )
    private val settingsSource = File(
        "src/main/java/com/dawncourse/feature/settings/SettingsScreen.kt",
    )

    @Test
    fun screenUsesLifecycleCollectionAndProfileScopedImportCallback() {
        val source = screenSource.readText()

        assertTrue(source.contains("collectAsStateWithLifecycle"))
        assertTrue(source.contains("onImport: (Long) -> Unit"))
        assertTrue(source.contains("onImport(profile.id)"))
        assertTrue(source.contains("ProfileManagementTestTags.SCREEN"))
        assertTrue(source.contains("ProfileManagementTestTags.PROFILE_LIST"))
        assertTrue(source.contains("ProfileManagementTestTags.DELETE_DIALOG"))
        assertTrue(source.contains("contentDescription = stringResource"))
        assertFalse(source.contains("NavController"))
        assertFalse(source.contains("confirmImport"))
    }

    @Test
    fun viewModelUsesImmutableStateAndOneShotEventWithoutAndroidContext() {
        val source = viewModelSource.readText()

        assertTrue(source.contains("StateFlow<ProfileManagementUiState>"))
        assertTrue(source.contains("Channel<ProfileManagementEvent>"))
        assertTrue(source.contains("receiveAsFlow()"))
        assertTrue(source.contains("TimetableProfileRepository"))
        assertTrue(source.contains("catch (cancellation: CancellationException)"))
        assertTrue(source.contains("throw cancellation"))
        assertFalse(source.contains("android.content.Context"))
        assertFalse(source.contains("Dao"))
    }

    @Test
    fun settingsEntryIsCallbackBasedAndDoesNotDependOnAppNavigation() {
        val source = settingsSource.readText()

        assertTrue(source.contains("onOpenProfileManager: () -> Unit"))
        assertTrue(source.contains("title = stringResource(R.string.profile_management_current_profile)"))
        assertFalse(source.contains("NavController"))
    }

    @Test
    fun chineseAndEnglishResourcesContainProfileManagementStrings() {
        val chinese = File("src/main/res/values/strings.xml").readText()
        val english = File("src/main/res/values-en/strings.xml").readText()

        assertTrue(chinese.contains("name=\"profile_management_title\""))
        assertTrue(chinese.contains("name=\"profile_management_delete_accounts\""))
        assertTrue(english.contains("name=\"profile_management_title\""))
        assertTrue(english.contains("name=\"profile_management_delete_accounts\""))
    }
}
