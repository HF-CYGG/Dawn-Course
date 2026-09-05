package com.dawncourse.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 独立多课表管理页面。
 *
 * 导航由 app 注入回调，feature 不持有具体导航控制器；导入回调始终携带目标 Profile ID。
 */
@Composable
fun ProfileManagementScreen(
    onBackClick: () -> Unit,
    onImport: (Long) -> Unit,
    viewModel: ProfileManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            val message = when (event) {
                ProfileManagementEvent.LoadFailed -> resources.getString(R.string.profile_management_load_failed)
                is ProfileManagementEvent.FormRejected -> resources.getString(event.error.stringResourceId())
                is ProfileManagementEvent.MutationInconsistent ->
                    resources.getString(R.string.profile_management_inconsistent_state)
                is ProfileManagementEvent.MutationRejected -> resources.getString(event.operation.failureStringResourceId())
                is ProfileManagementEvent.MutationSucceeded -> resources.getString(
                    event.operation.successStringResourceId(),
                    event.label.orEmpty(),
                )
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    ProfileManagementContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onImport = onImport,
        onCreateProfile = viewModel::openCreateDialog,
        onSwitchProfile = viewModel::switchProfile,
        onOpenRename = viewModel::openRenameDialog,
        onOpenSemesterPicker = viewModel::openSemesterPicker,
        onOpenCreateSemester = viewModel::openCreateSemesterDialog,
        onRequestDeletion = viewModel::requestDeletion,
        onDismissDialog = viewModel::dismissDialog,
        onUpdateCreateDraft = viewModel::updateCreateDraft,
        onConfirmCreate = viewModel::createProfile,
        onConfirmRename = viewModel::renameProfile,
        onSetActiveSemester = viewModel::setActiveSemester,
        onCreateSemester = viewModel::createSemester,
        onConfirmDeletion = viewModel::confirmDeletion,
    )
}

/** 无 ViewModel 的可预览/可测试页面内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileManagementContent(
    uiState: ProfileManagementUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onImport: (Long) -> Unit,
    onCreateProfile: () -> Unit,
    onSwitchProfile: (Long) -> Unit,
    onOpenRename: (Long, String) -> Unit,
    onOpenSemesterPicker: (Long) -> Unit,
    onOpenCreateSemester: (Long) -> Unit,
    onRequestDeletion: (Long) -> Unit,
    onDismissDialog: () -> Unit,
    onUpdateCreateDraft: (ProfileCreationDraft) -> Unit,
    onConfirmCreate: () -> Unit,
    onConfirmRename: (Long, String) -> Unit,
    onSetActiveSemester: (Long, Long) -> Unit,
    onCreateSemester: (Long, String, String, String) -> Unit,
    onConfirmDeletion: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(ProfileManagementTestTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.profile_management_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.canMutate) {
                FloatingActionButton(
                    onClick = onCreateProfile,
                    modifier = Modifier.testTag(ProfileManagementTestTags.CREATE_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.profile_management_create),
                    )
                }
            }
        },
    ) { paddingValues ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.profile_management_loading))
                }
            }

            uiState.hasLoadError -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.profile_management_load_failed))
            }

            uiState.profiles.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.profile_management_empty))
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .testTag(ProfileManagementTestTags.PROFILE_LIST),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.profile_management_switch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(uiState.profiles, key = ProfileRowUiModel::id) { profile ->
                    ProfileCard(
                        profile = profile,
                        canDelete = uiState.profiles.size > 1,
                        isMutating = uiState.isMutating,
                        onSwitch = { onSwitchProfile(profile.id) },
                        onRename = { onOpenRename(profile.id, profile.name) },
                        onSelectSemester = { onOpenSemesterPicker(profile.id) },
                        onCreateSemester = { onOpenCreateSemester(profile.id) },
                        onImport = { onImport(profile.id) },
                        onDelete = { onRequestDeletion(profile.id) },
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    ProfileManagementDialogs(
        uiState = uiState,
        onDismiss = onDismissDialog,
        onUpdateCreateDraft = onUpdateCreateDraft,
        onConfirmCreate = onConfirmCreate,
        onConfirmRename = onConfirmRename,
        onOpenCreateSemester = onOpenCreateSemester,
        onSetActiveSemester = onSetActiveSemester,
        onCreateSemester = onCreateSemester,
        onConfirmDeletion = onConfirmDeletion,
    )
}

/** 单套课表卡片及其作用域内操作。 */
@Composable
private fun ProfileCard(
    profile: ProfileRowUiModel,
    canDelete: Boolean,
    isMutating: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onSelectSemester: () -> Unit,
    onCreateSemester: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onSwitch,
        enabled = !isMutating,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProfileManagementTestTags.profile(profile.id)),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (profile.isActive) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.profile_management_current),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Text(
                        text = profile.activeSemesterName
                            ?: stringResource(R.string.profile_management_no_semester),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    profile.courseCount?.let { courseCount ->
                        Text(
                            text = stringResource(R.string.profile_management_course_count, courseCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(
                                R.string.profile_management_more_actions,
                                profile.name,
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_management_rename)) },
                            onClick = { menuExpanded = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_management_select_semester)) },
                            onClick = { menuExpanded = false; onSelectSemester() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_management_create_semester)) },
                            onClick = { menuExpanded = false; onCreateSemester() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_management_delete)) },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                            enabled = canDelete,
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }

            if (profile.isEmptyProfile) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = stringResource(R.string.profile_management_semester_empty),
                    modifier = Modifier.testTag(ProfileManagementTestTags.EMPTY_SEMESTER),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCreateSemester,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.profile_management_create_semester)) }
                    Button(
                        onClick = onImport,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(ProfileManagementTestTags.import(profile.id)),
                    ) { Text(stringResource(R.string.profile_management_import)) }
                }
            }
        }
    }
}

/** 根据 ViewModel 对话框状态展示创建、重命名、学期选择和删除确认。 */
@Composable
private fun ProfileManagementDialogs(
    uiState: ProfileManagementUiState,
    onDismiss: () -> Unit,
    onUpdateCreateDraft: (ProfileCreationDraft) -> Unit,
    onConfirmCreate: () -> Unit,
    onConfirmRename: (Long, String) -> Unit,
    onOpenCreateSemester: (Long) -> Unit,
    onSetActiveSemester: (Long, Long) -> Unit,
    onCreateSemester: (Long, String, String, String) -> Unit,
    onConfirmDeletion: () -> Unit,
) {
    when (val dialog = uiState.dialog) {
        ProfileManagementDialog.None -> Unit
        is ProfileManagementDialog.Create -> CreateProfileDialog(
            draft = dialog.draft,
            isMutating = uiState.isMutating,
            onDismiss = onDismiss,
            onDraftChange = onUpdateCreateDraft,
            onConfirm = onConfirmCreate,
        )
        is ProfileManagementDialog.Rename -> RenameProfileDialog(
            dialog = dialog,
            isMutating = uiState.isMutating,
            onDismiss = onDismiss,
            onConfirm = onConfirmRename,
        )
        is ProfileManagementDialog.SemesterPicker -> SemesterPickerDialog(
            profileId = dialog.profileId,
            semesters = uiState.semestersByProfile[dialog.profileId].orEmpty(),
            onDismiss = onDismiss,
            onCreateSemester = onOpenCreateSemester,
            onSelect = onSetActiveSemester,
        )
        is ProfileManagementDialog.CreateSemester -> CreateSemesterDialog(
            profileId = dialog.profileId,
            isMutating = uiState.isMutating,
            onDismiss = onDismiss,
            onConfirm = onCreateSemester,
        )
        is ProfileManagementDialog.DeletePreviewLoading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_management_delete_title)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_cancel)) }
            },
        )
        is ProfileManagementDialog.DeleteConfirmation -> DeleteConfirmationDialog(
            impact = dialog.impact,
            enabled = uiState.canConfirmDeletion && !uiState.isMutating,
            onDismiss = onDismiss,
            onConfirm = onConfirmDeletion,
        )
    }
}

/** 三模式新建课表表单。 */
@Composable
private fun CreateProfileDialog(
    draft: ProfileCreationDraft,
    isMutating: Boolean,
    onDismiss: () -> Unit,
    onDraftChange: (ProfileCreationDraft) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(ProfileManagementTestTags.CREATE_DIALOG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_management_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileCreationMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = draft.mode == mode,
                            onClick = { onDraftChange(draft.copy(mode = mode)) },
                        )
                        Text(stringResource(mode.stringResourceId()))
                    }
                }
                OutlinedTextField(
                    value = draft.profileName,
                    onValueChange = { onDraftChange(draft.copy(profileName = it)) },
                    label = { Text(stringResource(R.string.profile_management_profile_name)) },
                    singleLine = true,
                )
                if (draft.mode == ProfileCreationMode.WITH_SEMESTER) {
                    SemesterFields(
                        name = draft.semesterName,
                        date = draft.startDate,
                        weekCount = draft.weekCount,
                        onNameChange = { onDraftChange(draft.copy(semesterName = it)) },
                        onDateChange = { onDraftChange(draft.copy(startDate = it)) },
                        onWeekCountChange = { onDraftChange(draft.copy(weekCount = it)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isMutating) {
                Text(stringResource(R.string.profile_management_confirm_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_cancel)) }
        },
    )
}

/** Profile 重命名弹窗。 */
@Composable
private fun RenameProfileDialog(
    dialog: ProfileManagementDialog.Rename,
    isMutating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    var name by remember(dialog.profileId) { mutableStateOf(dialog.currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_management_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profile_management_profile_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(dialog.profileId, name) },
                enabled = !isMutating && name.isNotBlank(),
            ) { Text(stringResource(R.string.profile_management_confirm_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_cancel)) }
        },
    )
}

/** 指定 Profile 的活动学期选择。 */
@Composable
private fun SemesterPickerDialog(
    profileId: Long,
    semesters: List<ProfileSemesterUiModel>,
    onDismiss: () -> Unit,
    onCreateSemester: (Long) -> Unit,
    onSelect: (Long, Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_management_select_semester)) },
        text = {
            Column {
                if (semesters.isEmpty()) {
                    Text(stringResource(R.string.profile_management_semester_empty))
                } else {
                    semesters.forEach { semester ->
                        TextButton(
                            onClick = { onSelect(profileId, semester.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(semester.name, modifier = Modifier.weight(1f))
                            if (semester.isActive) {
                                Text(stringResource(R.string.profile_management_current))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreateSemester(profileId) }) {
                Text(stringResource(R.string.profile_management_create_semester))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_close)) }
        },
    )
}

/** 在指定 Profile 内创建并激活一个新学期。 */
@Composable
private fun CreateSemesterDialog(
    profileId: Long,
    isMutating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, String, String) -> Unit,
) {
    var name by remember(profileId) { mutableStateOf("") }
    var date by remember(profileId) { mutableStateOf("") }
    var weekCount by remember(profileId) { mutableStateOf("20") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_management_create_semester)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SemesterFields(
                    name = name,
                    date = date,
                    weekCount = weekCount,
                    onNameChange = { name = it },
                    onDateChange = { date = it },
                    onWeekCountChange = { weekCount = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(profileId, name, date, weekCount) },
                enabled = !isMutating,
            ) { Text(stringResource(R.string.profile_management_confirm_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_cancel)) }
        },
    )
}

/** 复用的新学期基础字段。 */
@Composable
private fun SemesterFields(
    name: String,
    date: String,
    weekCount: String,
    onNameChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onWeekCountChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.profile_management_semester_name)) },
        singleLine = true,
    )
    OutlinedTextField(
        value = date,
        onValueChange = onDateChange,
        label = { Text(stringResource(R.string.profile_management_start_date)) },
        singleLine = true,
    )
    OutlinedTextField(
        value = weekCount,
        onValueChange = onWeekCountChange,
        label = { Text(stringResource(R.string.profile_management_week_count)) },
        singleLine = true,
    )
}

/** 永久删除二次确认，完整展示真实影响计数。 */
@Composable
private fun DeleteConfirmationDialog(
    impact: ProfileDeletionImpactUiModel,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(ProfileManagementTestTags.DELETE_DIALOG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_management_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.profile_management_delete_warning))
                Text(stringResource(R.string.profile_management_delete_profile, impact.profileName))
                Text(stringResource(R.string.profile_management_delete_semesters, impact.semesterCount))
                Text(stringResource(R.string.profile_management_delete_courses, impact.courseCount))
                Text(stringResource(R.string.profile_management_delete_bindings, impact.sourceBindingCount))
                Text(stringResource(R.string.profile_management_delete_accounts, impact.credentialCount))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    text = stringResource(R.string.profile_management_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_management_cancel)) }
        },
    )
}

/** 创建模式对应的本地化名称。 */
@StringRes
private fun ProfileCreationMode.stringResourceId(): Int = when (this) {
    ProfileCreationMode.EMPTY -> R.string.profile_management_create_mode_empty
    ProfileCreationMode.WITH_SEMESTER -> R.string.profile_management_create_mode_semester
    ProfileCreationMode.CLONE_CURRENT -> R.string.profile_management_create_mode_clone
}

/** 表单错误对应的本地化文案。 */
@StringRes
private fun ProfileFormError.stringResourceId(): Int = when (this) {
    ProfileFormError.EMPTY_PROFILE_NAME -> R.string.profile_management_error_empty_profile_name
    ProfileFormError.EMPTY_SEMESTER_NAME -> R.string.profile_management_error_empty_semester_name
    ProfileFormError.INVALID_DATE -> R.string.profile_management_error_invalid_date
    ProfileFormError.INVALID_WEEK_COUNT -> R.string.profile_management_error_invalid_week_count
    ProfileFormError.NO_CLONE_SOURCE -> R.string.profile_management_error_no_clone_source
}

/** 成功事件对应的本地化 Snackbar。 */
@StringRes
private fun ProfileMutationOperation.successStringResourceId(): Int = when (this) {
    ProfileMutationOperation.CREATE_PROFILE -> R.string.profile_management_success_create
    ProfileMutationOperation.SWITCH_PROFILE -> R.string.profile_management_success_switch
    ProfileMutationOperation.RENAME_PROFILE -> R.string.profile_management_success_rename
    ProfileMutationOperation.SET_ACTIVE_SEMESTER -> R.string.profile_management_success_set_semester
    ProfileMutationOperation.CREATE_SEMESTER -> R.string.profile_management_success_create_semester
    ProfileMutationOperation.DELETE_PROFILE -> R.string.profile_management_success_delete
    ProfileMutationOperation.PREVIEW_DELETION -> R.string.profile_management_success_preview
}

/** 失败事件对应的本地化 Snackbar。 */
@StringRes
private fun ProfileMutationOperation.failureStringResourceId(): Int = when (this) {
    ProfileMutationOperation.CREATE_PROFILE -> R.string.profile_management_failure_create
    ProfileMutationOperation.SWITCH_PROFILE -> R.string.profile_management_failure_switch
    ProfileMutationOperation.RENAME_PROFILE -> R.string.profile_management_failure_rename
    ProfileMutationOperation.SET_ACTIVE_SEMESTER -> R.string.profile_management_failure_set_semester
    ProfileMutationOperation.CREATE_SEMESTER -> R.string.profile_management_failure_create_semester
    ProfileMutationOperation.DELETE_PROFILE -> R.string.profile_management_failure_delete
    ProfileMutationOperation.PREVIEW_DELETION -> R.string.profile_management_failure_preview
}
