/**
 * Convx Project (C) 2026, GPL-3.0; see NOTICE.
 * Default Thumbnail.kt carousel adapted to Media3's immutable queue snapshots.
 */
package app.lusound.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.collect

/** A transition owns its complete text/image snapshot, including its outgoing content. */
@Immutable
data class PlayerPresentation(val mediaId: String?, val title: String, val artist: String, val artwork: String?)

private data class ArtworkQueue(val entries: List<PlayerPresentation>, val index: Int)

private fun artworkQueue(controller: MediaController): ArtworkQueue = ArtworkQueue(
    entries = (0 until controller.mediaItemCount).map { index ->
        val item = controller.getMediaItemAt(index)
        val metadata = if (index == controller.currentMediaItemIndex) controller.mediaMetadata else item.mediaMetadata
        PlayerPresentation(item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(), metadata.artworkUri?.toString())
    },
    index = controller.currentMediaItemIndex,
)

/** Real adjacent queue entries move together; only a completed user swipe changes playback. */
@Composable
fun PlayerArtworkCarousel(controller: MediaController, presentation: PlayerPresentation, coverSize: Dp, motion: PlayerMotion, modifier: Modifier, ownsMorph: Boolean) {
    var queue by remember(controller) { mutableStateOf(artworkQueue(controller)) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { queue = artworkQueue(controller) }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    if (queue.entries.isEmpty()) return
    val pager = rememberPagerState(initialPage = queue.index.coerceIn(queue.entries.indices), pageCount = { queue.entries.size })
    var userScrollStarted by remember { mutableStateOf(false) }
    var synchronizing by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrollStarted = true
            if (interaction is DragInteraction.Cancel) userScrollStarted = false
        }
    }
    LaunchedEffect(queue.index, queue.entries.map { it.mediaId }) {
        userScrollStarted = false
        synchronizing = true
        try {
            pager.animateScrollToPage(queue.index.coerceIn(queue.entries.indices))
        } finally { synchronizing = false }
    }
    LaunchedEffect(pager, controller) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.collect { (scrolling, index) ->
            if (!scrolling && userScrollStarted && !synchronizing) {
                userScrollStarted = false
                if (index != controller.currentMediaItemIndex) controller.seekToDefaultPosition(index)
            }
        }
    }
    Box(modifier.playerSheetDrag(motion), contentAlignment = Alignment.Center) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("player_carousel"), beyondViewportPageCount = 1) { index ->
            val entry = if (index == queue.index) presentation else queue.entries[index]
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PlayerCover(entry.artwork, Modifier.size(coverSize).graphicsLayer {
                    alpha = if (ownsMorph && motion.progress > 0f && motion.progress < 1f) 0f else 1f
                }.semantics { contentDescription = "封面：${entry.title}" }, 12.dp)
            }
        }
        // The resting cover slot stays measured while adjacent pages travel through it.
        // A page temporarily clipped by the viewport must never shrink the morph's endpoint.
        if (ownsMorph) Box(Modifier.size(coverSize).testTag("player_artwork").fullPlayerArtwork(motion))
    }
}
