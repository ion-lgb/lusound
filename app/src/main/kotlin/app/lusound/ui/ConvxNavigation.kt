/** Convx FloatingNavBar adapter; actual FloatingTabBar/GooeyTransition sources are vendored. */
package app.lusound.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.floatingtabbar.*

@Composable
fun ConvxNavigation(settings: Boolean, navigate: (Boolean) -> Unit, search: () -> Unit,
    backdrop: Backdrop, scroll: FloatingTabBarScrollConnection, accessory: (@Composable (Boolean) -> Unit)?) {
    FloatingTabBar(selectedTabKey = if (settings) "settings" else "library", scrollConnection = scroll,
        modifier = Modifier.fillMaxWidth().testTag("floating_navigation"),
        tabBarContentModifier = Modifier.glass(backdrop), backdrop = backdrop,
        colors = FloatingTabBarDefaults.colors(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f), MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f)),
        inlineAccessory = accessory?.let { content -> { modifier, visibility -> Box(modifier.widthIn(min = 120.dp, max = 320.dp)) { content(visibility.transition.targetState == androidx.compose.animation.EnterExitState.Visible) } } },
        expandedAccessory = accessory?.let { content -> { modifier, visibility -> Box(modifier.fillMaxWidth()) { content(visibility.transition.targetState == androidx.compose.animation.EnterExitState.Visible) } } },
        accentColor = MaterialTheme.colorScheme.primary) {
        tab("library", { Text("资料库") }, {
            Icon(Icons.Rounded.LibraryMusic, "资料库", Modifier)
        }, { navigate(false) })
        tab("settings", { Text("设置") }, {
            Icon(Icons.Rounded.Settings, "设置", Modifier)
        }, { navigate(true) })
        standaloneTab("search", { Icon(Icons.Rounded.Search, "搜索音乐", Modifier) }, search)
    }
}
