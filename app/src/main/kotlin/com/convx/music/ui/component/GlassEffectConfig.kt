/** Convx default glass configuration, adapted from GlassEffect.kt (GPL-3.0). */
package com.convx.music.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class GlassEffectConfig(val blurRadius: Float, val puckColor: Color, val puckOpacity: Float)
val LocalGlassEffectConfig = staticCompositionLocalOf { GlassEffectConfig(2f, Color.Unspecified, 0.8f) }
fun glassResolutionScale(blurRadiusDp: Float): Float = 1f - (blurRadiusDp / 8f).coerceIn(0f, 1f) * 0.7f
