package app.thingsfinder.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.data.db.SearchRow
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.savedStateFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ContentWidth
import app.thingsfinder.ui.components.EmptyState
import app.thingsfinder.ui.components.ErrorState
import app.thingsfinder.ui.components.LoadingState
import app.thingsfinder.ui.components.QtyBadge
import app.thingsfinder.ui.components.ScreenPreviews
import app.thingsfinder.ui.components.StatePreviews
import app.thingsfinder.ui.components.pluralText
import app.thingsfinder.ui.theme.ThingsFinderTheme

@Composable
fun SearchScreen(
    onOpenPlace: (Long) -> Unit,
    onOpenBox: (Long) -> Unit,
    vm: SearchViewModel = viewModel(factory = savedStateFactory { c, h -> SearchViewModel(c.inventory, h) }),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    SearchContent(query, results, vm::onQueryChange, onOpenPlace, onOpenBox)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    query: String,
    results: UiState<SearchResults>,
    onQueryChange: (String) -> Unit,
    onOpenPlace: (Long) -> Unit,
    onOpenBox: (Long) -> Unit,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (autoFocus && query.isEmpty()) focus.requestFocus() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.search_title)) }) }) { padding ->
        ContentWidth(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focus),
                )
                when (results) {
                    UiState.Loading -> LoadingState()
                    is UiState.Error -> ErrorState(results.message.text())
                    UiState.NotFound -> Unit
                    is UiState.Content -> ResultsList(results.data, onOpenPlace, onOpenBox)
                }
            }
        }
    }
}

@Composable
private fun ResultsList(results: SearchResults, onOpenPlace: (Long) -> Unit, onOpenBox: (Long) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            results.query.isEmpty() -> item {
                EmptyState(Icons.Outlined.Search, stringResource(R.string.search_empty_query))
            }
            results.rows.isEmpty() -> item {
                EmptyState(Icons.Outlined.Inbox, stringResource(R.string.search_no_results, results.query))
            }
            else -> {
                item {
                    Text(
                        pluralText(R.plurals.search_result_count, results.rows.size) + " · “${results.query}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(results.rows, key = { it.itemId }) { row ->
                    ResultCard(row, onClick = { if (row.boxId != null) onOpenBox(row.boxId) else onOpenPlace(row.placeId) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(row: SearchRow, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.itemName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                QtyBadge(row.quantity)
            }
            val where = if (row.boxName != null) "${row.placeName} › ${row.boxName}" else row.placeName
            Text(
                stringResource(R.string.search_in, where),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

// ---- previews ------------------------------------------------------------

@ScreenPreviews
@Composable
private fun SearchResultsPreview() = ThingsFinderTheme {
    SearchContent(
        query = "glue",
        results = UiState.Content(
            SearchResults(
                "glue",
                listOf(
                    SearchRow(1, "Hot glue gun", 1, 3, "Tools bin 2", 1, "Garage"),
                    SearchRow(2, "Glue sticks", 30, null, null, 2, "Kitchen"),
                ),
            ),
        ),
        onQueryChange = {}, onOpenPlace = {}, onOpenBox = {}, autoFocus = false,
    )
}

@StatePreviews
@Composable
private fun SearchNoResultsPreview() = ThingsFinderTheme {
    SearchContent("unicorn", UiState.Content(SearchResults("unicorn", emptyList())), {}, {}, {}, autoFocus = false)
}

@StatePreviews
@Composable
private fun SearchIdlePreview() = ThingsFinderTheme {
    SearchContent("", UiState.Content(SearchResults("", emptyList())), {}, {}, {}, autoFocus = false)
}
