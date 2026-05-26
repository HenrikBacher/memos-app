package nu.bacher.memos

import android.app.Application
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.engine.HttpClientEngineFactory
import nu.bacher.memos.data.api.buildImageHttpClient
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.di.androidPlatformModule
import nu.bacher.memos.di.appModule
import nu.bacher.memos.di.commonModule
import nu.bacher.memos.reminder.notify.NotificationHelper
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MemosApp : Application(), SingletonImageLoader.Factory {

    private val authStore: AuthStore by inject()
    private val httpEngine: HttpClientEngineFactory<*> by inject()

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
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = buildImageHttpClient(httpEngine, authStore)
        return ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = client)) }
            .build()
    }
}
