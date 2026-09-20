package nu.bacher.memos.data.db

/** Projection of the only two columns the home-screen widget renders. */
data class WidgetMemoRow(
    val name: String,
    val content: String,
)
