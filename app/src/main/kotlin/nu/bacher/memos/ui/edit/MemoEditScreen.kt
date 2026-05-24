package nu.bacher.memos.ui.edit

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import nu.bacher.memos.R
import nu.bacher.memos.ui.attachments.AttachmentList
import nu.bacher.memos.ui.reminder.ReminderPickerSheet
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoEditScreen(
    memoName: String?,
    onBack: () -> Unit,
    initialContent: String? = null,
    vm: MemoEditViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showReminderSheet by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val picked = readPickedFile(context, uri)
                if (picked != null) {
                    vm.addAttachment(picked.bytes, picked.filename, picked.mimeType)
                }
            }
        }
    }

    LaunchedEffect(memoName, initialContent) { vm.load(memoName, initialContent) }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    BackHandler { vm.save() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.memoName == null) stringResource(R.string.edit_title_new)
                        else stringResource(R.string.edit_title_edit),
                    )
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
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
                        Box {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.edit_more_actions),
                                )
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_delete)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        moreOpen = false
                                        showDeleteConfirm = true
                                    },
                                )
                            }
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
                onPickAttachment = { pickFile.launch("*/*") },
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditActionsRow(
    state: MemoEditViewModel.State,
    isEditing: Boolean,
    onPickAttachment: () -> Unit,
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
            AssistChip(
                onClick = onPickAttachment,
                enabled = !state.uploading,
                leadingIcon = {
                    if (state.uploading) {
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
                        if (state.uploading) stringResource(R.string.edit_uploading)
                        else stringResource(R.string.edit_add_attachment),
                    )
                },
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
            val label = stringResource(
                R.string.edit_reminder_at,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(r.triggerAtEpochMs)),
            )
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
