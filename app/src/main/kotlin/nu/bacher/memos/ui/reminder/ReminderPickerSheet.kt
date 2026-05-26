package nu.bacher.memos.ui.reminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import nu.bacher.memos.R
import nu.bacher.memos.util.QuickReminders

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPickerSheet(
    onDismiss: () -> Unit,
    onPickTime: (Long) -> Unit,
    /** When non-null, the date/time pickers open pre-filled to this instant —
     *  used so tapping the chip on a memo that already has a reminder lets the
     *  user edit it rather than starting from scratch. */
    existingEpochMs: Long? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.reminder_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            TimeReminderForm(initialEpochMs = existingEpochMs, onPick = onPickTime)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeReminderForm(initialEpochMs: Long?, onPick: (Long) -> Unit) {
    val context = LocalContext.current

    val initialDateTime = remember(initialEpochMs) {
        initialEpochMs?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }
    var date by remember(initialEpochMs) { mutableStateOf<Long?>(initialEpochMs) }
    var hour by remember(initialEpochMs) { mutableIntStateOf(initialDateTime?.hour ?: 9) }
    var minute by remember(initialEpochMs) { mutableIntStateOf(initialDateTime?.minute ?: 0) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // Track permission state so the warning row updates after the user grants
    // or denies via the system prompt.
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var exactAlarmsGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    // Epoch the user wanted to commit when we had to detour through the
    // notification permission prompt. Stashed so the launcher callback can
    // finalise the *intended* time — a quick-pick chip's epoch, not whatever
    // the manual date/time pickers currently hold.
    var pendingEpoch by remember { mutableStateOf<Long?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (granted) {
            val target = pendingEpoch ?: currentlySelectedInstant(date, hour, minute)
            pendingEpoch = null
            target?.let(onPick)
        } else {
            pendingEpoch = null
        }
    }

    val combined by remember {
        derivedStateOf { currentlySelectedInstant(date, hour, minute) }
    }

    /**
     * Commit [epoch] as the reminder time, routing through the notification
     * permission prompt first when we don't have it yet. Used by both the
     * quick-pick chips and the manual "Set reminder" button so the gating
     * logic only lives in one place.
     */
    fun pick(epoch: Long) {
        if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingEpoch = epoch
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        onPick(epoch)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickPickRow(onPick = ::pick)

        Text(
            stringResource(R.string.reminder_or_pick_specific),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                Text(date?.let { dateLabel(context, it) } ?: stringResource(R.string.reminder_pick_date))
            }
            OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                val timeLabel = remember(hour, minute) {
                    val tz = TimeZone.currentSystemDefault()
                    val today = Clock.System.now().toLocalDateTime(tz).date
                    val millis = LocalDateTime(today, LocalTime(hour, minute))
                        .toInstant(tz)
                        .toEpochMilliseconds()
                    android.text.format.DateUtils.formatDateTime(
                        context, millis, android.text.format.DateUtils.FORMAT_SHOW_TIME,
                    )
                }
                Text(timeLabel)
            }
        }

        if (!notifGranted) {
            PermissionRow(
                message = stringResource(R.string.reminder_perm_notifications),
                action = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                },
            )
        }
        if (!exactAlarmsGranted) {
            PermissionRow(
                message = stringResource(R.string.reminder_perm_exact_alarm),
                action = {
                    openExactAlarmSettings(context)
                    // we re-check on dialog dismiss below
                },
            )
        }

        Button(
            onClick = { combined?.let(::pick) },
            enabled = combined != null && combined!! > System.currentTimeMillis(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.reminder_set)) }
    }

    if (showDate) {
        val s = rememberDatePickerState(initialSelectedDateMillis = date ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = { date = s.selectedDateMillis; showDate = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = s) }
    }
    if (showTime) {
        val s = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = {
                showTime = false
                // Returning from Settings doesn't fire any signal, so re-poll.
                exactAlarmsGranted = canScheduleExactAlarms(context)
            },
            confirmButton = {
                TextButton(onClick = { hour = s.hour; minute = s.minute; showTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = s) },
        )
    }
}

/**
 * Quick-pick row above the manual date/time pickers. All three options land
 * at 08:00 local — the [QuickReminders] helper has the day-of-week math.
 * Reads the wall clock once per recomposition (`System.currentTimeMillis()`);
 * the sheet is short-lived so we don't bother making this reactive.
 */
@Composable
private fun QuickPickRow(onPick: (Long) -> Unit) {
    val zone = remember { TimeZone.currentSystemDefault() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AssistChip(
            onClick = { onPick(QuickReminders.tomorrow(System.currentTimeMillis(), zone)) },
            label = { Text(stringResource(R.string.reminder_quick_tomorrow)) },
        )
        AssistChip(
            onClick = { onPick(QuickReminders.nextWeekend(System.currentTimeMillis(), zone)) },
            label = { Text(stringResource(R.string.reminder_quick_next_weekend)) },
        )
        AssistChip(
            onClick = { onPick(QuickReminders.nextWeek(System.currentTimeMillis(), zone)) },
            label = { Text(stringResource(R.string.reminder_quick_next_week)) },
        )
    }
}

@Composable
private fun PermissionRow(message: String, action: () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = action) { Text(stringResource(R.string.reminder_perm_grant)) }
    }
}

private fun currentlySelectedInstant(date: Long?, hour: Int, minute: Int): Long? =
    date?.let {
        val tz = TimeZone.currentSystemDefault()
        val localDate = Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date
        LocalDateTime(localDate, LocalTime(hour, minute)).toInstant(tz).toEpochMilliseconds()
    }

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService<AlarmManager>() ?: return false
    return am.canScheduleExactAlarms()
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

private fun dateLabel(context: Context, millis: Long): String =
    android.text.format.DateUtils.formatDateTime(
        context,
        millis,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_YEAR or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
    )
