package nu.bacher.memos

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.di.MemosAuthInterceptor
import nu.bacher.memos.di.androidPlatformModule
import nu.bacher.memos.di.appModule
import nu.bacher.memos.di.commonModule
import nu.bacher.memos.reminder.notify.NotificationHelper
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MemosApp : Application(), SingletonImageLoader.Factory {

    private val authStore: AuthStore by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MemosApp)
            modules(commonModule(), androidPlatformModule(), appModule())
        }
        NotificationHelper.createChannels(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(MemosAuthInterceptor(authStore))
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .build()
    }
}
