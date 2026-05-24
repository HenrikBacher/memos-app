package nu.bacher.memos.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date
import nu.bacher.memos.R
import nu.bacher.memos.ui.reminder.ReminderPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoEditScreen(
    memoName: String?,
    onBack: () -> Unit,
    initialContent: String? = null,
    vm: MemoEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showReminderSheet by remember { mutableStateOf(false) }

    LaunchedEffect(memoName, initialContent) { vm.load(memoName, initialContent) }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    BackHandler { vm.save() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.memoName == null) stringResource(R.string.edit_title_new)
                        else stringResource(R.string.edit_title_edit),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.memoName != null) {
                        IconButton(onClick = { vm.delete() }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_delete))
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                val r = state.reminder
                if (r == null) {
                    AssistChip(
                        onClick = { showReminderSheet = true },
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
                        onClick = { showReminderSheet = true },
                        leadingIcon = {
                            Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text(label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { vm.clearReminder() }) {
                        Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.edit_clear_reminder))
                    }
                }
            }
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
    }
}
