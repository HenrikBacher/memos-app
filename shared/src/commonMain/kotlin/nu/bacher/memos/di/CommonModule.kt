package nu.bacher.memos.di

import io.ktor.client.engine.HttpClientEngineFactory
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.buildMemosHttpClient
import nu.bacher.memos.data.api.buildVerificationClient
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.db.MemosDatabase
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.data.settings.LayoutPreferences
import nu.bacher.memos.ui.edit.MemoEditViewModel
import nu.bacher.memos.ui.list.MemoListViewModel
import nu.bacher.memos.ui.login.LoginViewModel
import nu.bacher.memos.ui.navigation.RootViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Common Koin bindings. The platform module supplies the platform-built
 * [MemosDatabase], an [HttpClientEngineFactory], and a platform
 * [nu.bacher.memos.reminder.time.ReminderScheduler].
 */
fun commonModule(enableHttpLogging: Boolean = false) = module {
    single { get<MemosDatabase>().reminderDao() }
    single { get<MemosDatabase>().memoDao() }

    singleOf(::AuthStore)
    singleOf(::LayoutPreferences)

    single {
        buildMemosHttpClient(
            engine = get<HttpClientEngineFactory<*>>(),
            authStore = get(),
            enableLogging = enableHttpLogging,
        )
    }
    singleOf(::MemosApi)

    single {
        val engine = get<HttpClientEngineFactory<*>>()
        MemoRepository(
            api = get(),
            dao = get(),
            verifyClientFactory = { url, token -> buildVerificationClient(engine, url, token) },
        )
    }
    singleOf(::ReminderRepository)

    viewModelOf(::MemoListViewModel)
    viewModelOf(::MemoEditViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::RootViewModel)
}
