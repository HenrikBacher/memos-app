package nu.bacher.memos

import android.app.Application
import nu.bacher.memos.di.androidPlatformModule
import nu.bacher.memos.di.appModule
import nu.bacher.memos.di.commonModule
import nu.bacher.memos.reminder.notify.NotificationHelper
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MemosApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MemosApp)
            modules(commonModule(), androidPlatformModule(), appModule())
        }
        NotificationHelper.createChannels(this)
    }
}
