package nu.bacher.memos.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nu.bacher.memos.data.api.AttachmentSource
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.FakeMemoDao
import nu.bacher.memos.data.repo.FakePendingActionDao
import nu.bacher.memos.data.repo.testHttpClient
import nu.bacher.memos.data.repo.testMemoRepository
import nu.bacher.memos.data.repo.jsonHeaders
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.ui.edit.MemoEditViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.builtins.serializer

/**
 * The edit screen gates Save and the discard-confirmation on [isDirty], and
 * maps upload failures to typed errors. Both are easy to get subtly wrong and
 * neither is visible from the repository tests.
 */
class MemoEditViewModelTest {

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun a_brand_new_memo_starts_clean() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.NotFound) })
        vm.load(memoName = null)
        vm.state.first { !it.loading }

        // Nothing typed yet — Back must not warn about discarding.
        assertFalse(vm.isDirty())
    }

    @Test
    fun shared_text_is_not_treated_as_the_users_unsaved_work() = runTest {
        val vm = vm(MockEngine { respondError(HttpStatusCode.NotFound) })
        vm.load(memoName = null, initialContent = "shared from another app")
        vm.state.first { !it.loading }

        // Pre-populated share text is the baseline, so a straight Back press
        // isn't a discard prompt for text the user never authored.
        assertFalse(vm.isDirty())

        vm.setContent("shared from another app, edited")
        assertTrue(vm.isDirty())
    }

    @Test
    fun typing_then_reverting_leaves_the_memo_clean() = runTest {
        val vm = vm(memoEngine("original"))
        vm.load("memos/x")
        vm.state.first { !it.loading }

        vm.setContent("changed")
        assertTrue(vm.isDirty())

        vm.setContent("original")
        assertFalse(vm.isDirty(), "reverting an edit should clear the dirty flag")
    }

    @Test
    fun changing_visibility_alone_counts_as_dirty() = runTest {
        val vm = vm(memoEngine("original"))
        vm.load("memos/x")
        vm.state.first { !it.loading }

        vm.setVisibility(MemoEditViewModel.VISIBILITY_PUBLIC)
        assertTrue(vm.isDirty())
    }

    @Test
    fun an_unknown_visibility_is_ignored() = runTest {
        val vm = vm(memoEngine("original"))
        vm.load("memos/x")
        vm.state.first { !it.loading }

        vm.setVisibility("NONSENSE")

        assertEquals(MemoEditViewModel.VISIBILITY_PRIVATE, vm.state.value.visibility)
        assertFalse(vm.isDirty())
    }

    @Test
    fun saving_an_empty_new_memo_just_closes_without_claiming_success() = runTest {
        var calls = 0
        val vm = vm(MockEngine { calls++; respondError(HttpStatusCode.BadRequest) })
        vm.load(memoName = null)
        vm.state.first { !it.loading }

        vm.save()
        val state = vm.state.first { it.finished }

        assertEquals(0, calls, "an empty new memo must not hit the API")
        assertFalse(state.savedSuccess, "nothing was saved, so don't show 'Saved'")
    }

    @Test
    fun an_oversized_attachment_is_rejected_before_any_upload() = runTest {
        var calls = 0
        val vm = vm(MockEngine { calls++; respondError(HttpStatusCode.BadRequest) })
        vm.load(memoName = null)
        vm.state.first { !it.loading }

        vm.addAttachment(
            AttachmentSource(
                filename = "huge.bin",
                mimeType = "application/octet-stream",
                byteCount = MemoEditViewModel.MAX_ATTACHMENT_BYTES + 1,
                openSource = { error("must not be opened") },
            ),
        )

        assertEquals(MemoEditViewModel.EditError.FILE_TOO_LARGE, vm.state.value.error)
        assertEquals(0, calls, "size is gated before the request")
    }

    @Test
    fun an_offline_upload_reports_that_attachments_cannot_be_queued() = runTest {
        // Attachments have no offline queue, unlike memo writes — the user
        // has to be told the upload simply didn't happen.
        val vm = vm(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        vm.load(memoName = null)
        vm.state.first { !it.loading }

        vm.addAttachment(
            AttachmentSource(
                filename = "a.png",
                mimeType = "image/png",
                byteCount = 3,
                openSource = { kotlinx.io.Buffer().apply { write(byteArrayOf(1, 2, 3)) } },
            ),
        )

        val state = vm.state.first { it.error != null }
        assertEquals(MemoEditViewModel.EditError.ATTACHMENT_OFFLINE, state.error)
        assertFalse(state.uploading)
    }

    private fun memoEngine(content: String) = MockEngine { _ ->
        respond(
            """{"name":"memos/x","content":${MemosJson.encodeToString(String.serializer(), content)},"visibility":"PRIVATE"}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun vm(engine: MockEngine): MemoEditViewModel {
        val client = testHttpClient(engine)
        val repo = testMemoRepository(engine, verifyClient = client)
        return MemoEditViewModel(
            memoRepo = repo,
            reminderRepo = testReminderRepository(),
            authStore = AuthStore(MapSettings(), PlaintextSecretCipher),
        )
    }
}
