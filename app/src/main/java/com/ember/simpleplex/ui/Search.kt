@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Library
import com.ember.simpleplex.plex.PlexClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Title search in the libraries shown on Home (hidden ones are left out). Results update as you type,
 * grouped by library: posters for movies and shows, thumbnails for other videos.
 */
@Composable
fun SearchScreen(plex: PlexClient, nav: Nav) {
    val context = LocalContext.current
    val hidden = remember { LibraryPrefs(context).hidden }
    val libs by load(Unit) { plex.libraries().filter { it.key !in hidden } }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Result<List<Pair<Library, List<Item>>>>?>(null) }
    var menuFor by remember { mutableStateOf<Item?>(null) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }

    val libraries = libs?.getOrElse { return Message("Couldn't reach your Plex server.\n${it.message}") } ?: return

    // Waits for a pause in typing before asking Plex.
    val term = query.trim()
    LaunchedEffect(term, libraries) {
        if (term.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(350)
        results = try {
            Result.success(plex.search(libraries, term))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            withFrameNanos { }
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }

    // 12 columns: posters take 2 (six to a row), video thumbnails 3 (four to a row).
    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "field") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Palette.Tile, RoundedCornerShape(12.dp))
                    .then(if (fieldFocused) Modifier.border(3.dp, Palette.Ink, RoundedCornerShape(12.dp)) else Modifier)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, contentDescription = null, tint = Palette.Secondary, modifier = Modifier.size(20.dp))
                Box(Modifier.padding(start = 12.dp).weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search ${libraries.joinToString(", ") { it.title }}", fontSize = 16.sp, color = Palette.Secondary, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.Ink),
                        cursorBrush = SolidColor(Palette.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Search on the keyboard: close it and go down to the results.
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focus.moveFocus(FocusDirection.Down)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(field)
                            .onFocusChanged { fieldFocused = it.isFocused },
                    )
                }
            }
        }

        val found = results
        when {
            term.length < 2 -> {}
            found == null -> item(span = { GridItemSpan(maxLineSpan) }) { Hint("Searching…") }
            found.isFailure -> item(span = { GridItemSpan(maxLineSpan) }) {
                Hint("Couldn't search.\n${found.exceptionOrNull()?.message}")
            }
            found.getOrThrow().isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Hint("Nothing called “$term”.") }
            else -> for ((library, items) in found.getOrThrow()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h" + library.key) {
                    SectionTitle(library.title, Modifier.padding(top = 8.dp))
                }
                val wide = library.kind == Library.Kind.OTHER
                items(items, key = { library.key + "/" + it.ratingKey }, span = { GridItemSpan(if (wide) 3 else 2) }) { item ->
                    val open = {
                        if (item.type == "show") nav.push(Screen.Show(item)) else nav.play(listOf(item.ratingKey))
                    }
                    if (wide) {
                        WideCard(
                            imageUrl = plex.image(item.thumb, 396, 223),
                            title = item.title,
                            subtitle = null,
                            badge = if (item.duration > 0) formatDuration(item.duration) else null,
                            progress = item.progress,
                            watched = item.watched,
                            onClick = open,
                            onLongClick = { menuFor = item },
                        )
                    } else {
                        PosterCard(
                            title = item.title,
                            imageUrl = plex.image(item.thumb, 248, 372),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = open,
                            onLongClick = { menuFor = item },
                        )
                    }
                }
            }
        }
    }
    menuFor?.let { ItemMenu(plex, it, nav) { menuFor = null } }
}

@Composable
private fun Hint(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 15.sp, color = Palette.Secondary)
    }
}
