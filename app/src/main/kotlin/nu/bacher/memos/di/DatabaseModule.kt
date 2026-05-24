package nu.bacher.memos.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import nu.bacher.memos.data.db.MemosDatabase
import nu.bacher.memos.data.db.ReminderDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MemosDatabase =
        Room.databaseBuilder(context, MemosDatabase::class.java, "memos.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideReminderDao(db: MemosDatabase): ReminderDao = db.reminderDao()
}
