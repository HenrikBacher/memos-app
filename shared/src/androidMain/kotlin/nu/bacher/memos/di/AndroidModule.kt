package nu.bacher.memos.di

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import nu.bacher.memos.data.db.createMemosDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Platform bindings supplied by the :shared module on Android. The :app
 * module supplies its own additional module to bind ReminderScheduler
 * (which needs to reference AlarmReceiver, an :app-only class).
 */
@OptIn(ExperimentalSettingsApi::class)
fun androidPlatformModule() = module {
    single<HttpClientEngineFactory<*>> { OkHttp }

    single { createMemosDatabase(androidContext()) }

    single<ObservableSettings> {
        val prefs = androidContext().getSharedPreferences(
            "memos_auth_prefs",
            android.content.Context.MODE_PRIVATE,
        )
        SharedPreferencesSettings(prefs)
    }
}
