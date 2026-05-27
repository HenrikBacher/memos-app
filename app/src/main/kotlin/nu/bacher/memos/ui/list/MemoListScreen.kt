package nu.bacher.memos.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.ui.platform.LocalContext
import nu.bacher.memos.R
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.db.ReminderEntity
import nu.bacher.memos.data.settings.MemoLayout
import nu.bacher.memos.ui.attachments.AttachmentCardPreview
import nu.bacher.memos.ui.reminder.reminderLabel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoListScreen(
    onOpenMemo: (String) -> Unit,
    onEditMemo: (String) -> Unit,
    onCreateMemo: () -> Unit,
    onLogout: () -> Unit,
    vm: MemoListViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()
    val selectedNames by vm.selectedNames.collectAsState()
    val pagingItems = vm.memos.collectAsLazyPagingItems()
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val inSelectionMode = selectedNames.isNotEmpty()

    // Back exits selection mode before falling through to nav back.
    BackHandler(enabled = inSelectionMode) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            when {
                inSelectionMode -> SelectionTopBar(
                    count = selectedNames.size,
                    onClear = { vm.clearSelection() },
                    // Edit is only meaningful for exactly one selection; the
                    // bar hides the icon otherwise so the user isn't tempted
                    // to open a multi-edit screen we don't have.
                    onEdit = selectedNames.singleOrNull()?.let { name ->
                        {
                            onEditMemo(name)
                            vm.clearSelection()
                        }
                    },
                    onArchive = { vm.archiveSelected() },
                    onDelete = { showDeleteConfirm = true },
                )
                searchOpen -> SearchTopBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onClose = {
                        searchOpen = false
                        vm.setQuery("")
                    },
                )
                else -> TopAppBar(
                    title = { Text(stringResource(R.string.list_title)) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.list_search),
                            )
                        }
                        LayoutToggleButton(state.layout, vm::setLayout)
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.list_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.list_logout)) },
                                    onClick = {
                                        menuOpen = false
                                        vm.logout()
                                        onLogout()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateMemo,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.list_fab_new)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.tags.isNotEmpty()) {
                TagFilterRow(
                    tags = state.tags,
                    selected = state.selectedTag,
                    onSelect = vm::setSelectedTag,
                )
            }
            val refreshing = pagingItems.loadState.refresh is LoadState.Loading
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { pagingItems.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                MemoResultsBody(
                    pagingItems = pagingItems,
                    layout = state.layout,
                    query = state.query,
                    selectedTag = state.selectedTag,
                    selectedNames = selectedNames,
                    onOpenMemo = { name ->
                        // In selection mode, tap toggles selection rather
                        // than opening — keeps multi-step deletion fast and
                        // matches the standard Android contextual pattern.
                        if (inSelectionMode) vm.toggleSelection(name)
                        else onOpenMemo(name)
                    },
                    onLongPressMemo = vm::toggleSelection,
                    onClearFilter = {
                        vm.setQuery("")
                        vm.setSelectedTag(null)
                        searchOpen = false
                    },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        val count = selectedNames.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = {
                Text(
                    if (count <= 1) stringResource(R.string.edit_delete_confirm_title)
                    else stringResource(R.string.list_delete_confirm_title_count, count),
                )
            },
            text = {
                Text(
                    if (count <= 1) stringResource(R.string.edit_delete_confirm_message)
                    else stringResource(R.string.list_delete_confirm_message_count, count),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteSelected()
                    },
                ) {
                    Text(
                        stringResource(R.string.edit_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.edit_delete_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onEdit: (() -> Unit)?,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.list_selection_clear),
                )
            }
        },
        title = {
            Text(stringResource(R.string.list_selection_title_count, count))
        },
        actions = {
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.list_selection_edit),
                    )
                }
            }
            IconButton(onClick = onArchive) {
                Icon(
                    Icons.Filled.Archive,
                    contentDescription = stringResource(R.string.list_selection_archive),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.list_selection_delete),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.list_close_search),
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.list_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.list_clear_search),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutToggleButton(layout: MemoLayout, onToggle: (MemoLayout) -> Unit) {
    // Show the icon for the OTHER mode — tapping switches to it. The tooltip
    // spells out the action ("Switch to list view") so users don't have to
    // guess what the icon means.
    val label = stringResource(
        if (layout == MemoLayout.GRID) R.string.list_view_list else R.string.list_view_grid,
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = {
            onToggle(if (layout == MemoLayout.GRID) MemoLayout.LIST else MemoLayout.GRID)
        }) {
            Icon(
                imageVector = if (layout == MemoLayout.GRID) Icons.AutoMirrored.Filled.ViewList
                else Icons.Filled.GridView,
                contentDescription = label,
            )
        }
    }
}

@Composable
private fun MemoResultsBody(
    pagingItems: LazyPagingItems<MemoListViewModel.Row>,
    layout: MemoLayout,
    query: String,
    selectedTag: String?,
    selectedNames: Set<String>,
    onOpenMemo: (String) -> Unit,
    onLongPressMemo: (String) -> Unit,
    onClearFilter: () -> Unit,
) {
    val refresh = pagingItems.loadState.refresh
    val isInitialLoading = refresh is LoadState.Loading && pagingItems.itemCount == 0
    val errorState = refresh as? LoadState.Error

    if (pagingItems.itemCount == 0 && !isInitialLoading) {
        val isFiltered = query.isNotBlank() || selectedTag != null
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val msg = when {
                    errorState != null -> stringResource(friendlyErrorMessage(errorState.error))
                    isFiltered -> stringResource(R.string.list_empty_filtered)
                    else -> stringResource(R.string.list_empty)
                }
                Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    errorState != null -> Button(onClick = { pagingItems.retry() }) {
                        Text(stringResource(R.string.list_retry))
                    }
                    isFiltered -> TextButton(onClick = onClearFilter) {
                        Text(stringResource(R.string.list_clear_filter))
                    }
                }
            }
        }
        return
    }

    when (layout) {
        MemoLayout.GRID -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(8.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.memo.name },
                contentType = pagingItems.itemContentType { "memo" },
            ) { index ->
                val row = pagingItems[index] ?: return@items
                MemoCard(
                    content = row.memo.content,
                    attachments = row.memo.attachments,
                    reminder = row.reminder,
                    pendingSync = row.pendingSync,
                    selected = row.memo.name in selectedNames,
                    onClick = { onOpenMemo(row.memo.name) },
                    onLongClick = { onLongPressMemo(row.memo.name) },
                )
            }
            if (pagingItems.loadState.append is LoadState.Loading) {
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        MemoLayout.LIST -> LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.memo.name },
                contentType = pagingItems.itemContentType { "memo" },
            ) { index ->
                val row = pagingItems[index] ?: return@items
                MemoCard(
                    content = row.memo.content,
                    attachments = row.memo.attachments,
                    reminder = row.reminder,
                    pendingSync = row.pendingSync,
                    selected = row.memo.name in selectedNames,
                    onClick = { onOpenMemo(row.memo.name) },
                    onLongClick = { onLongPressMemo(row.memo.name) },
                )
            }
            if (pagingItems.loadState.append is LoadState.Loading) {
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun TagFilterRow(
    tags: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "__all__") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.list_tag_all)) },
            )
        }
        items(items = tags, key = { it }) { tag ->
            FilterChip(
                selected = selected == tag,
                onClick = { onSelect(if (selected == tag) null else tag) },
                label = { Text("#$tag") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoCard(
    content: String,
    attachments: List<AttachmentDto>,
    reminder: ReminderEntity?,
    pendingSync: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Card has no combined-click overload, so attach combinedClickable on the
    // Modifier and use the non-clickable Card constructor.
    // Cache the card background once for the fade gradient — it has to match
    // the surface behind the markdown so the fade looks like the content
    // dissolves into the card, not into a different colour stripe.
    val cardBg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else CardDefaults.cardColors().containerColor
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = cardBg)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (pendingSync) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.list_sync_pending),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // The markdown renderer lays out blocks (headers, lists, paragraphs)
            // and ignores Text's maxLines, so we cap the rendered preview height
            // and clip the rest. The substring keeps the renderer from chewing on
            // an entire long memo just to throw it away.
            var overflowing by remember(content) { mutableStateOf(false) }
            val maxHeightPx = with(LocalDensity.current) { 280.dp.toPx() }
            Box(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .clipToBounds()
                    .drawWithContent {
                        drawContent()
                        if (overflowing) {
                            // Fade the bottom ~30% so users see content is cut.
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.7f to Color.Transparent,
                                    1f to cardBg,
                                ),
                            )
                        }
                    },
            ) {
                // wrapContentSize(unbounded=true) lifts the parent's max-height
                // constraint when measuring Markdown, so onSizeChanged reports
                // the markdown's natural height — what we need to detect that
                // the 280.dp cap is actually clipping something.
                Box(
                    modifier = Modifier
                        .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                        .onSizeChanged { overflowing = it.height > maxHeightPx },
                ) {
                    Markdown(
                        content = content.take(800),
                        typography = markdownTypography(
                            text = MaterialTheme.typography.bodyMedium,
                            paragraph = MaterialTheme.typography.bodyMedium,
                            h1 = MaterialTheme.typography.titleMedium,
                            h2 = MaterialTheme.typography.titleSmall,
                            h3 = MaterialTheme.typography.titleSmall,
                            h4 = MaterialTheme.typography.bodyMedium,
                            h5 = MaterialTheme.typography.bodyMedium,
                            h6 = MaterialTheme.typography.bodyMedium,
                            ordered = MaterialTheme.typography.bodyMedium,
                            bullet = MaterialTheme.typography.bodyMedium,
                            list = MaterialTheme.typography.bodyMedium,
                            quote = MaterialTheme.typography.bodyMedium,
                        ),
                        modifier = Modifier,
                    )
                }
            }
            if (attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AttachmentCardPreview(attachments = attachments)
            }
            if (reminder != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    val context = LocalContext.current
                    val label = remember(reminder.triggerAtEpochMs) {
                        reminderLabel(context, reminder.triggerAtEpochMs)
                    }
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Maps a paging error to a user-facing string resource. We hide the raw
 * exception message (often a Ktor/JVM stack-trace flavored string) and bucket
 * by whether the failure is network, auth, or anything else.
 */
private fun friendlyErrorMessage(t: Throwable): Int {
    var current: Throwable? = t
    while (current != null) {
        val name = current::class.qualifiedName.orEmpty()
        when {
            name.endsWith("UnknownHostException") ||
                name.endsWith("UnresolvedAddressException") ||
                name.endsWith("ConnectException") ||
                name.endsWith("SocketTimeoutException") ||
                name.endsWith("HttpRequestTimeoutException") ||
                name.endsWith("IOException") -> return R.string.list_error_network
            name.endsWith("ClientRequestException") -> {
                val msg = current.message.orEmpty()
                if (msg.contains("401") || msg.contains("403")) {
                    return R.string.list_error_auth
                }
            }
        }
        current = current.cause
    }
    return R.string.list_error_generic
}
