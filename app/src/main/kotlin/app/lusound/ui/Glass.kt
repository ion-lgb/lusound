package app.lusound.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.backdrop.BackdropEffectScope
import com.convx.music.ui.component.backdrop.drawBackdrop
import com.convx.music.ui.component.backdrop.effects.blur
import com.convx.music.ui.component.backdrop.effects.lens

/** Uses Convx's source-included backdrop renderer. API26–30 translucent material is an explicit product choice. */
@Composable
fun Modifier.glass(backdrop: Backdrop): Modifier {
    val shape = RoundedCornerShape(28.dp)
    val tint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isSystemInDarkTheme()) 0.28f else 0.58f)
    if (Build.VERSION.SDK_INT < 31) return clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
    val effects: BackdropEffectScope.() -> Unit = remember {
        {
            blur(14.dp.toPx())
            if (Build.VERSION.SDK_INT >= 33) lens(18.dp.toPx(), 24.dp.toPx())
        }
    }
    return drawBackdrop(backdrop = backdrop, shape = { shape }, effects = effects,
        onDrawSurface = { drawRect(tint) })
}
