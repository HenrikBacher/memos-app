package nu.bacher.memos.ui.edit

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch
import nu.bacher.memos.R
import nu.bacher.memos.ui.attachments.AttachmentList
import nu.bacher.memos.ui.reminder.ReminderPickerSheet
import nu.bacher.memos.ui.reminder.reminderLabel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoEditScreen(
    memoName: String?,
    onBack: () -> Unit,
    initialContent: String? = null,
    startInEditMode: Boolean = false,
    vm: MemoEditViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showReminderSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Read dirty off the collected state so BackHandler.enabled stays in
    // sync, and so the system handles predictive back when there's nothing
    // to confirm (a BackHandler that always intercepts blocks the gesture
    // preview animation).
    val isDirty = remember(state) { vm.isDirty() }
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardConfirm = true else onBack()
    }

    // Two pickers because Android 13's photo picker (PickVisualMedia) is
    // permission-free and gives the modern carousel UI, while GetContent
    // still wins for non-image/video files. The chip exposes both via a
    // dropdown so users pick the right one for what they're attaching.
    val handlePicked: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            scope.launch {
                val picked = readPickedFile(context, uri)
                if (picked != null) {
                    vm.addAttachment(picked.bytes, picked.filename, picked.mimeType)
                }
            }
        }
    }
    val pickVisualMedia = rememberLauncherForActivityResult(PickVisualMedia(), handlePicked)
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), handlePicked)

    LaunchedEffect(memoName, initialContent, startInEditMode) {
        vm.load(memoName, initialContent, startInEditMode)
    }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    // Only intercept when we'd otherwise lose unsaved work — lets predictive
    // back animate the screen pop normally for the read/clean case.
    BackHandler(enabled = isDirty) { showDiscardConfirm = true }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.memoName == null -> stringResource(R.string.edit_title_new)
                            state.isEditing -> stringResource(R.string.edit_title_edit)
                            else -> stringResource(R.string.edit_title_view)
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = attemptBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.edit_back),
                        )
                    }
                },
                actions = {
                    // Save is always available — for new memos and existing
                    // ones alike. The VM's save() is a no-op on an empty new
                    // memo (just finishes), so the button is safe to tap any
                    // time the screen has loaded.
                    IconButton(
                        onClick = { vm.save() },
                        enabled = !state.saving && !state.loading,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.edit_save),
                        )
                    }
                    if (state.memoName != null) {
                        IconButton(onClick = { vm.setEditing(!state.isEditing) }) {
                            if (state.isEditing) {
                                Icon(
                                    Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.edit_show_preview),
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.edit_show_editor),
                                )
                            }
                        }
                        IconButton(onClick = {
                            val url = vm.shareUrl() ?: return@IconButton
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    send,
                                    context.getString(R.string.edit_share_chooser),
                                ),
                            )
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.edit_share))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.edit_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.isEditing) {
                TextField(
                    value = state.content,
                    onValueChange = vm::setContent,
                    placeholder = { Text(stringResource(R.string.edit_content_hint)) },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                )
            } else {
                // Read-only markdown view. Links are clickable via the lib's
                // default LinkInteractionListener → LocalUriHandler → ACTION_VIEW.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (state.content.isBlank()) {
                        Text(
                            stringResource(R.string.edit_content_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Markdown(
                            content = state.content,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (state.attachments.isNotEmpty()) {
                AttachmentList(
                    attachments = state.attachments,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    onRemove = if (state.isEditing) {
                        { att -> vm.removeAttachment(att.name) }
                    } else {
                        null
                    },
                )
            }

            EditActionsRow(
                state = state,
                isEditing = state.isEditing,
                onPickPhoto = {
                    pickVisualMedia.launch(
                        PickVisualMediaRequest(PickVisualMedia.ImageAndVideo),
                    )
                },
                onPickFile = { pickFile.launch("*/*") },
                onSetVisibility = vm::setVisibility,
                onOpenReminderSheet = { showReminderSheet = true },
                onClearReminder = { vm.clearReminder() },
            )
        }

        if (showReminderSheet) {
            ReminderPickerSheet(
                onDismiss = { showReminderSheet = false },
                existingEpochMs = state.reminder?.triggerAtEpochMs,
                onPickTime = { epochMs ->
                    vm.setTimeReminder(epochMs)
                    showReminderSheet = false
                },
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                title = { Text(stringResource(R.string.edit_delete_confirm_title)) },
                text = { Text(stringResource(R.string.edit_delete_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            vm.delete()
                        },
                    ) {
                        Text(
                            stringResource(R.string.edit_delete_confirm_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.edit_delete_cancel))
                    }
                },
            )
        }

        if (showDiscardConfirm) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                title = { Text(stringResource(R.string.edit_discard_confirm_title)) },
                text = { Text(stringResource(R.string.edit_discard_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDiscardConfirm = false
                            onBack()
                        },
                    ) {
                        Text(
                            stringResource(R.string.edit_discard_confirm_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirm = false }) {
                        Text(stringResource(R.string.edit_discard_keep_editing))
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditActionsRow(
    state: MemoEditViewModel.State,
    isEditing: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSetVisibility: (String) -> Unit,
    onOpenReminderSheet: () -> Unit,
    onClearReminder: () -> Unit,
) {
    FlowRow(
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Visibility is a memo property, not a content-edit mode — always
        // editable on this screen so the user can flip a memo public without
        // first toggling into the markdown editor.
        VisibilityChip(
            visibility = state.visibility,
            onSelect = onSetVisibility,
        )

        if (isEditing) {
            AttachmentChip(
                uploading = state.uploading,
                onPickPhoto = onPickPhoto,
                onPickFile = onPickFile,
            )
        }

        val r = state.reminder
        if (r == null) {
            AssistChip(
                onClick = onOpenReminderSheet,
                leadingIcon = { Icon(Icons.Filled.Alarm, null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.edit_add_reminder)) },
            )
        } else {
            val context = LocalContext.current
            val relativeLabel = remember(r.triggerAtEpochMs) {
                reminderLabel(context, r.triggerAtEpochMs)
            }
            val label = stringResource(R.string.edit_reminder_at, relativeLabel)
            AssistChip(
                onClick = onOpenReminderSheet,
                leadingIcon = {
                    Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                },
                label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClearReminder) {
                    Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.edit_clear_reminder))
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    uploading: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = !uploading,
            leadingIcon = {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(18.dp))
                }
            },
            label = {
                Text(
                    if (uploading) stringResource(R.string.edit_uploading)
                    else stringResource(R.string.edit_add_attachment),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_attach_photo)) },
                leadingIcon = { Icon(Icons.Filled.Image, null) },
                onClick = {
                    open = false
                    onPickPhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_attach_file)) },
                leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                onClick = {
                    open = false
                    onPickFile()
                },
            )
        }
    }
}

@Composable
private fun VisibilityChip(
    visibility: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            leadingIcon = { Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp)) },
            label = { Text(visibility.toDisplayLabel()) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in MemoEditViewModel.ALL_VISIBILITIES) {
                DropdownMenuItem(
                    text = { Text(level.toDisplayLabel()) },
                    onClick = {
                        open = false
                        onSelect(level)
                    },
                )
            }
        }
    }
}

@Composable
private fun String.toDisplayLabel(): String = when (this) {
    MemoEditViewModel.VISIBILITY_PRIVATE -> stringResource(R.string.edit_visibility_private)
    MemoEditViewModel.VISIBILITY_PROTECTED -> stringResource(R.string.edit_visibility_protected)
    MemoEditViewModel.VISIBILITY_PUBLIC -> stringResource(R.string.edit_visibility_public)
    else -> this
}
