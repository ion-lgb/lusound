/**
 * Adapted from Convx Material3SettingsGroup.kt, SettingsScreen.kt and GlassSwitch.kt.
 * Convx Project (C) 2026, GPL-3.0; see NOTICE for upstream attribution.
 */
package app.lusound.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun ConvxSettingsHeader(title: String) {
    Text(title, Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ConvxSettingsBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun ConvxSettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 20.dp), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun ConvxSettingsAction(icon: ImageVector, title: String, tag: String, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).testTag(tag)
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

/** Exact 51×31 track, 27dp thumb and 200ms travel from upstream GlassSwitch. */
@Composable
internal fun ConvxSettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "glassSwitchThumb")
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.sizeIn(minWidth = 51.dp, minHeight = 48.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(51.dp, 31.dp).clip(shape)
            .background(if (checked) Color(0xFF34C759) else Color(0xFF39393D))
            .then(if (checked) Modifier.border(0.8.dp, Color.White.copy(alpha = 0.35f), shape) else Modifier),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset { IntOffset((2.dp + 20.dp * progress).roundToPx(), 0) }
                .size(27.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
    }
}
