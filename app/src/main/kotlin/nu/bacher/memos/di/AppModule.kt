package nu.bacher.memos.di

import nu.bacher.memos.reminder.time.AlarmScheduler
import nu.bacher.memos.reminder.time.ReminderScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * :app-only bindings. AlarmScheduler lives here (not in :shared) because it
 * references AlarmReceiver, which is an Android Manifest receiver and must
 * be declared in the application module.
 */
fun appModule() = module {
    single<ReminderScheduler> { AlarmScheduler(androidContext()) }
}
