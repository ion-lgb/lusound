/**
 * Adapted from Convx Material3SettingsGroup.kt and BottomSheetPage/Menu.kt.
 * Convx Project (C) 2026, GPL-3.0; see NOTICE for upstream attribution.
 */
package app.lusound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

/** Theme-adaptive solid grouped surface from Convx's default settings. */
@Composable
fun Modifier.softPanel(): Modifier = clip(RoundedCornerShape(22.dp))
    .background(MaterialTheme.colorScheme.surfaceContainer)

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().softPanel(), content = content)
}

@Composable
fun SettingsAction(icon: ImageVector, title: String, tag: String, action: () -> Unit) {
    ConvxSettingsAction(icon, title, tag, true, action)
}

/** A completed action may dismiss only after the sheet's native exit animation finishes. */
internal val LocalMusicSheetComplete = staticCompositionLocalOf<(() -> Unit) -> Unit> {
    error("Music sheet completion must be used inside MusicSheet")
}

/** Convx uses the native ModalBottomSheet motion for show, hide, drag and predictive back. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicSheet(canDismiss: () -> Boolean, onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    val allowDismiss by rememberUpdatedState(canDismiss)
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var completing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || completing || allowDismiss() })
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val complete: (() -> Unit) -> Unit = { afterHidden ->
        if (!completing) {
            completing = true
            focus.clearFocus()
            scope.launch {
                sheetState.hide()
                afterHidden()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (allowDismiss()) { focus.clearFocus(); onDismissRequest() } },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = canDismiss()),
        shape = BottomSheetDefaults.ExpandedShape,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = if (keyboardOpen) null else ({
            Box(Modifier.padding(vertical = 12.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
        }),
        scrimColor = BottomSheetDefaults.ScrimColor, tonalElevation = 0.dp) {
        CompositionLocalProvider(LocalMusicSheetComplete provides complete) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .padding(top = if (keyboardOpen) 12.dp else 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (keyboardOpen) 8.dp else 16.dp)) {
                ProvideTextStyle(if (keyboardOpen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, title)
                Box(Modifier.weight(1f, fill = false)) { text() }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun musicFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)
