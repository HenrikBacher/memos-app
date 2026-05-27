package nu.bacher.memos.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import nu.bacher.memos.MainActivity
import nu.bacher.memos.R
import nu.bacher.memos.data.db.MemoDao
import org.koin.java.KoinJavaComponent.get

/**
 * Home-screen widget showing the user's most recent memos. Tapping a row
 * deep-links into [MainActivity] with [MainActivity.EXTRA_OPEN_MEMO_NAME]; the
 * "+" in the header opens a fresh New Memo screen.
 *
 * Reads from the local Room cache via [MemoDao.getAll] — the widget never hits
 * the network. [MemosApp] observes the cache and calls [updateAll] when it
 * changes so the widget stays in sync with the in-app list.
 */
class MemosWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val memoDao = get<MemoDao>(MemoDao::class.java)
        val memos = memoDao.getAll()
            .asSequence()
            // Mirror MemoListViewModel: archived stays out of the active view.
            .filter { it.state != STATE_ARCHIVED }
            .sortedBy { it.orderInList }
            .take(MAX_ROWS)
            .map { WidgetMemo(it.name, it.content.preview()) }
            .toList()

        provideContent {
            GlanceTheme { Content(memos) }
        }
    }

    @Composable
    private fun Content(memos: List<WidgetMemo>) {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(8.dp),
        ) {
            Header(context = context)
            Spacer(GlanceModifier.height(4.dp))
            if (memos.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        context.getString(R.string.widget_empty),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(items = memos, itemId = { it.name.hashCode().toLong() }) { memo ->
                        MemoRow(context, memo)
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(context: Context) {
        val openNew = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_NEW_MEMO, true)
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(
                text = context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight(),
            )
            Box(
                modifier = GlanceModifier
                    .size(28.dp)
                    .cornerRadius(14.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .clickable(openNew),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_notification),
                    contentDescription = context.getString(R.string.widget_new_memo),
                    modifier = GlanceModifier.size(16.dp),
                )
            }
        }
    }

    @Composable
    private fun MemoRow(context: Context, memo: WidgetMemo) {
        val openMemo = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_MEMO_NAME, memo.name)
            },
        )
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .clickable(openMemo),
        ) {
            Text(
                text = memo.preview,
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurface),
            )
        }
    }

    private data class WidgetMemo(val name: String, val preview: String)

    private companion object {
        const val MAX_ROWS = 8
        const val PREVIEW_CHARS = 90
        const val STATE_ARCHIVED = "ARCHIVED"
    }

    private fun String.preview(): String {
        // Collapse newlines + trim leading markdown markers so each row stays a
        // single, readable snippet rather than a jagged fragment.
        val cleaned = lineSequence()
            .map { line -> line.trimStart { it == '#' || it == '-' || it == '*' || it == ' ' || it == '\t' } }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return if (cleaned.length <= PREVIEW_CHARS) cleaned
        else cleaned.take(PREVIEW_CHARS).trimEnd() + "…"
    }
}

class MemosWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemosWidget()
}
