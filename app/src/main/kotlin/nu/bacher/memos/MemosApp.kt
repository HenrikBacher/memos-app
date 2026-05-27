package nu.bacher.memos

import android.app.Application
import android.os.StrictMode
import androidx.glance.appwidget.updateAll
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import nu.bacher.memos.data.api.buildImageHttpClient
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.db.MemoDao
import nu.bacher.memos.di.androidPlatformModule
import nu.bacher.memos.di.appModule
import nu.bacher.memos.di.commonModule
import nu.bacher.memos.reminder.notify.NotificationHelper
import nu.bacher.memos.widget.MemosWidget
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MemosApp : Application(), SingletonImageLoader.Factory {

    private val authStore: AuthStore by inject()
    private val httpEngine: HttpClientEngineFactory<*> by inject()
    private val memoDao: MemoDao by inject()

    // Long-lived scope for app-process background work (currently just widget
    // sync). SupervisorJob keeps a single failure from killing the whole scope.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build(),
            )
        }
        startKoin {
            androidContext(this@MemosApp)
            modules(
                commonModule(enableHttpLogging = BuildConfig.DEBUG),
                androidPlatformModule(),
                appModule(),
            )
        }
        NotificationHelper.createChannels(this)
        observeMemosForWidget()
    }

    /**
     * Push a widget refresh whenever the memo cache changes so the home-screen
     * widget stays in sync with the in-app list. We project to just the fields
     * the widget renders before distinctUntilChanged so identical previews
     * don't churn the widget (Room rewrites cachedAtEpochMs on every refresh).
     * The initial emission is dropped — Glance renders on attach already.
     */
    private fun observeMemosForWidget() {
        appScope.launch {
            memoDao.observeAll()
                .map { rows -> rows.map { it.name to it.content } }
                .distinctUntilChanged()
                .drop(1)
                .onEach { MemosWidget().updateAll(this@MemosApp) }
                .collect {}
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = buildImageHttpClient(httpEngine, authStore)
        return ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = client)) }
            .build()
    }
}
