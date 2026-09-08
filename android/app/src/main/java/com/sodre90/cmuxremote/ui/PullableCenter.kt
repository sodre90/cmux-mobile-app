package com.sodre90.cmuxremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centres [content] in a container a `PullToRefreshBox` can still see a pull in.
 *
 * A plain Box never dispatches nested-scroll deltas, so wrapping one in
 * PullToRefreshBox leaves the gesture dead -- which is why pull-to-refresh used
 * to work over a populated list and nowhere else. The empty and failed states
 * are exactly where a user reaches for it. A single-item LazyColumn is
 * scrollable (so the gesture registers) and `fillParentMaxSize` gives the item
 * the viewport's height, so the content still centres rather than sitting at
 * the top the way a wrap-height scrolling Column would.
 */
@Composable
fun PullableCenter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}
