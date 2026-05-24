package nu.bacher.memos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import nu.bacher.memos.ui.navigation.MemosNavHost
import nu.bacher.memos.ui.theme.MemosTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialMemoName = intent?.getStringExtra(EXTRA_OPEN_MEMO_NAME)
        val openNewMemo = intent?.getBooleanExtra(EXTRA_OPEN_NEW_MEMO, false) == true
        val initialContent = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)

        setContent {
            MemosTheme {
                MemosNavHost(
                    initialMemoName = initialMemoName,
                    openNewMemoOnStart = openNewMemo,
                    initialContent = initialContent,
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_MEMO_NAME = "open_memo_name"
        const val EXTRA_OPEN_NEW_MEMO = "open_new_memo"
    }
}
