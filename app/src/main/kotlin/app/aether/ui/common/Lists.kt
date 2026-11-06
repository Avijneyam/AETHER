package app.aether.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * A page's list that keeps what's just off the screen composed and laid out, a screen and a half
 * either way: a fast fling, or a fling back, finds its rows already made instead of making them as
 * they arrive (the stutter, and the jump when one came out a different height).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPageListState(): LazyListState =
    rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 1200.dp, behind = 1200.dp))
