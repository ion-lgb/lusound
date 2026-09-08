package app.lusound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Content panels share geometry with glass controls but retain enough opacity for readable forms. */
@Composable
fun Modifier.softPanel(): Modifier {
    val shape = MaterialTheme.shapes.large
    val colors = MaterialTheme.colorScheme
    return clip(shape).background(Brush.verticalGradient(listOf(
        colors.surfaceContainerHigh.copy(alpha = 0.65f), colors.surfaceContainer.copy(alpha = 0.55f),
    ))).border(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().softPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SettingsAction(icon: ImageVector, title: String, tag: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = action).testTag(tag).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp))
        }
        Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One bottom-sheet treatment for queue, connection forms and confirmations, including IME insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSheet(canDismiss: () -> Boolean, onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    val allowDismiss by rememberUpdatedState(canDismiss)
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    ModalBottomSheet(onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || allowDismiss() }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = canDismiss()),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = if (keyboardOpen) null else ({ BottomSheetDefaults.DragHandle() }),
        scrimColor = Color.Black.copy(alpha = 0.38f), tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = if (keyboardOpen) 12.dp else 0.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(if (keyboardOpen) 8.dp else 16.dp)) {
            ProvideTextStyle(if (keyboardOpen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, title)
            Box(Modifier.weight(1f, fill = false)) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                dismissButton()
                Spacer(Modifier.width(12.dp))
                confirmButton()
            }
        }
    }
}

@Composable
fun musicFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f),
)
