package nu.bacher.memos.ui

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.db.PendingActionEntity
import nu.bacher.memos.data.repo.FakeMemoDao
import nu.bacher.memos.data.repo.FakePendingActionDao
import nu.bacher.memos.data.repo.memoEntity
import nu.bacher.memos.data.repo.testMemoRepository
import nu.bacher.memos.data.settings.ThemePreferences
import nu.bacher.memos.ui.settings.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logout must leave nothing of the previous account behind. */
class SettingsViewModelTest {

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun logout_clears_auth_cache_queue_and_reminders() = runTest {
        val authStore = AuthStore(MapSettings(), PlaintextSecretCipher)
            .also { it.save("https://memos.example.com", "tok") }
        val memoDao = FakeMemoDao().also { it.upsert(memoEntity("memos/a")) }
        val pendingDao = FakePendingActionDao().also {
            it.insert(
                PendingActionEntity(
                    type = "UPDATE",
                    memoName = "memos/a",
                    payloadJson = "{}",
                    createdAtEpochMs = 0,
                ),
            )
        }
        val reminderDao = FakeReminderDao()
        val scheduler = RecordingReminderScheduler()
        val reminders = testReminderRepository(reminderDao, scheduler)
        reminders.setTimeReminder("memos/a", triggerAtEpochMs = Long.MAX_VALUE)
        val reminderId = reminderDao.getAll().single().id

        val vm = SettingsViewModel(
            themePrefs = ThemePreferences(MapSettings()),
            authStore = authStore,
            memoRepo = testMemoRepository(
                MockEngine { respondError(HttpStatusCode.NotFound) },
                memoDao,
                pendingDao,
            ),
            reminderRepo = reminders,
        )

        vm.logout()

        assertNull(authStore.read())
        assertTrue(memoDao.getAll().isEmpty())
        assertTrue(pendingDao.rows.isEmpty())
        assertTrue(reminderDao.getAll().isEmpty())
        assertEquals(listOf(reminderId), scheduler.cancelled)
    }
}
