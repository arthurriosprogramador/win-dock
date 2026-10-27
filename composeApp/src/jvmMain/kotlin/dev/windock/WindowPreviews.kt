package dev.windock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.platform.win32.WinDef.RECT
import dev.windock.windows.AppWindow
import kotlin.math.roundToInt

/** Fixed geometry, shared by the Compose layout and the rectangles handed to the compositor. */
internal object PreviewLayout {
    val Padding = 10.dp
    val SlotWidth = 232.dp
    val Gap = 8.dp
    val Header = 30.dp
    val ThumbHeight = 140.dp
    private val ThumbInset = 6.dp

    fun size(count: Int) = DpSize(Padding * 2 + SlotWidth * count + Gap * (count - 1).coerceAtLeast(0), Padding * 2 + Header + ThumbHeight)

    fun slotX(index: Int): Dp = Padding + (SlotWidth + Gap) * index

    /** Where window [index]'s live image goes, in physical pixels relative to the popup. */
    fun thumbRect(index: Int, scale: Double): RECT = RECT().apply {
        fun px(dp: Dp) = (dp.value * scale).roundToInt()
        left = px(slotX(index) + ThumbInset)
        top = px(Padding + Header)
        right = px(slotX(index) + SlotWidth - ThumbInset)
        bottom = px(Padding + Header + ThumbHeight - ThumbInset)
    }
}

/**
 * The taskbar-style preview strip for one app: each window's title, a close button, and a slot where the
 * compositor draws the live thumbnail on top. Minimized windows have no live image, so the app icon shows.
 */
@Composable
internal fun WindowPreviewStrip(
    windows: List<AppWindow>,
    icon: ImageBitmap?,
    isMinimized: (AppWindow) -> Boolean,
    onHoverChange: (Boolean) -> Unit,
    onSelect: (AppWindow) -> Unit,
    onClose: (AppWindow) -> Unit,
) {
    val stripHover = remember { MutableInteractionSource() }
    val hovered by stripHover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHoverChange(hovered) }
    Box(Modifier.fillMaxSize().background(Color(0xFF1E222C)).hoverable(stripHover)) {
        windows.forEachIndexed { index, window ->
            Box(Modifier.offset(x = PreviewLayout.slotX(index), y = PreviewLayout.Padding)
                .size(PreviewLayout.SlotWidth, PreviewLayout.Header + PreviewLayout.ThumbHeight)) {
                PreviewSlot(window, icon, isMinimized(window), onSelect = { onSelect(window) }, onClose = { onClose(window) })
            }
        }
    }
}

@Composable
private fun PreviewSlot(window: AppWindow, icon: ImageBitmap?, minimized: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, tween(120), label = "preview-slot")
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .04f + .08f * highlight))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onSelect),
    ) {
        Row(Modifier.fillMaxWidth().height(PreviewLayout.Header).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Image(icon, null, Modifier.size(16.dp), filterQuality = FilterQuality.High)
            Text(window.title, color = Color(0xFFE8EAF0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            CloseButton(visible = highlight, onClick = onClose)
        }
        // The compositor paints the live image over this area; it only shows through when there's none.
        Box(Modifier.fillMaxWidth().height(PreviewLayout.ThumbHeight).padding(6.dp).clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF151820)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(48.dp).graphicsLayer { alpha = if (minimized) 1f else .35f }, filterQuality = FilterQuality.High)
            if (minimized) Text("Minimizada", color = Color(0xFF9AA1AE), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun CloseButton(visible: Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(22.dp).graphicsLayer { alpha = visible }.clip(CircleShape)
            .background(if (hovered) Color(0xFFC42B1C) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
