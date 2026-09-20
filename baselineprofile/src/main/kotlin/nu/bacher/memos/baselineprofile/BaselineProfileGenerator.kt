package nu.bacher.memos.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Walks the paths a user hits first, so they get compiled ahead of time.
 *
 * Deliberately limited to what works without credentials: the generator runs
 * against a freshly installed app, so it sees the login screen rather than the
 * memo list. Startup plus first composition is where the bulk of the win is
 * anyway — the Compose runtime, Koin graph, Room and Ktor initialisation all
 * happen on that path regardless of which screen renders.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        // Wait for first content rather than a fixed sleep, so the profile
        // reflects a real first frame on slow emulators too.
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "nu.bacher.memos"
        const val TIMEOUT_MS = 10_000L
    }
}
