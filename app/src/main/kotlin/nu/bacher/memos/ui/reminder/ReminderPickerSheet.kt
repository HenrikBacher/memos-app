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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import java.util.Calendar
import java.util.Date
import nu.bacher.memos.R

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

    val initialCal = remember(initialEpochMs) {
        Calendar.getInstance().apply { timeInMillis = initialEpochMs ?: System.currentTimeMillis() }
    }
    var date by remember(initialEpochMs) { mutableStateOf<Long?>(initialEpochMs) }
    var hour by remember(initialEpochMs) {
        mutableIntStateOf(if (initialEpochMs != null) initialCal.get(Calendar.HOUR_OF_DAY) else 9)
    }
    var minute by remember(initialEpochMs) {
        mutableIntStateOf(if (initialEpochMs != null) initialCal.get(Calendar.MINUTE) else 0)
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // Track permission state so the warning row updates after the user grants
    // or denies via the system prompt.
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var exactAlarmsGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (granted) {
            // Permission granted — finalise the reminder the user was trying to set.
            currentlySelectedInstant(date, hour, minute)?.let(onPick)
        }
    }

    val combined by remember {
        derivedStateOf { currentlySelectedInstant(date, hour, minute) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                Text(date?.let { dateLabel(context, it) } ?: stringResource(R.string.reminder_pick_date))
            }
            OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                val timeLabel = remember(hour, minute) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                    }
                    android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
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
            onClick = {
                val instant = combined ?: return@Button
                // Notification permission is the gate — without it the alarm
                // would fire but no notification would surface.
                if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@Button
                }
                onPick(instant)
            },
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
        Calendar.getInstance().apply {
            timeInMillis = it
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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
    android.text.format.DateFormat.getMediumDateFormat(context).format(Date(millis))
