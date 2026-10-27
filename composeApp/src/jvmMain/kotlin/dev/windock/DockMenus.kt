package dev.windock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windock.mvi.DockUiState
import dev.windock.mvi.ShellAction
import dev.windock.mvi.UiEvent
import dev.windock.windows.JumpItem
import dev.windock.windows.JumpLists
import dev.windock.windows.TaskbarApps
import dev.windock.windows.resourceIcon
import dev.windock.windows.shellOpen
import dev.windock.windows.systemIcon
import java.awt.GraphicsEnvironment
import java.io.File

/** One line of the dock's menu: an icon (a bitmap or a Segoe Fluent glyph), a label and what it does. */
internal class MenuRow(
    val label: String,
    val icon: ImageBitmap? = null,
    val glyph: Char? = null,
    val bold: Boolean = false,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

internal class MenuSection(val title: String?, val rows: List<MenuRow>)

/** A right-click menu laid out like a Windows 11 jump list: titled sections, then the app's own actions. */
internal class DockMenu(val sections: List<MenuSection>, val actions: List<MenuRow>)

private object Glyphs {
    const val PIN = '\uE718'
    const val UNPIN = '\uE77A'
    const val CLOSE = '\uE8BB'
    const val OPEN = '\uE838'
    const val EMPTY = '\uE74D'
    const val TASK_MANAGER = '\uE9D9'
    const val SETTINGS = '\uE713'
    const val DESKTOP = '\uE7F4'
    const val PERSONALIZE = '\uE771'
    const val RESTORE = '\uE7A7'
}

/**
 * Builds the right-click menu for [itemId]: the Start button opens the native Win+X menu instead (and this
 * returns null); apps get their real jump list plus launch/pin/close; the bar gets taskbar-wide actions.
 * Reads files and extracts icons, so call it off the UI thread.
 */
internal fun buildContextMenu(itemId: String, state: DockUiState, apps: TaskbarApps, dispatch: (UiEvent) -> Unit): DockMenu? = when (itemId) {
    ShellAction.START -> {
        apps.openPowerUserMenu()
        null
    }
    ShellAction.RECYCLE_BIN -> DockMenu(emptyList(), listOf(
        MenuRow("Abrir", glyph = Glyphs.OPEN, bold = true) { apps.runShellAction(ShellAction.RECYCLE_BIN) },
        MenuRow("Esvaziar Lixeira", glyph = Glyphs.EMPTY, enabled = state.system.recycleBinFull) { apps.emptyRecycleBin() },
    ))
    ShellAction.DOWNLOADS -> DockMenu(emptyList(), listOf(
        MenuRow("Abrir", glyph = Glyphs.OPEN, bold = true) { apps.runShellAction(ShellAction.DOWNLOADS) },
    ))
    ShellAction.BAR -> DockMenu(emptyList(), buildList {
        add(MenuRow("Gerenciador de Tarefas", glyph = Glyphs.TASK_MANAGER) { shellOpen("taskmgr.exe") })
        add(MenuRow("Configurações da barra de tarefas", glyph = Glyphs.SETTINGS) { shellOpen("ms-settings:taskbar") })
        add(MenuRow("Mostrar área de trabalho", glyph = Glyphs.DESKTOP) { apps.runShellAction(ShellAction.SHOW_DESKTOP) })
        add(MenuRow("Personalizar dock", glyph = Glyphs.PERSONALIZE) { dispatch(UiEvent.ToggleSettings) })
        if (state.dockActive) add(MenuRow("Restaurar barra do Windows", glyph = Glyphs.RESTORE) { dispatch(UiEvent.RestoreNativeTaskbar) })
    })
    else -> appMenu(itemId, state, apps, dispatch)
}

private fun appMenu(itemId: String, state: DockUiState, apps: TaskbarApps, dispatch: (UiEvent) -> Unit): DockMenu? {
    val app = state.items.firstOrNull { it.id == itemId } ?: return null
    val pin = apps.pinFor(itemId)
    val windows = apps.windowCount(itemId)
    val appIcon = apps.icon(itemId)
    val sections = runCatching { apps.jumpList(itemId) }.getOrDefault(emptyList()).map { category ->
        MenuSection(category.title, category.items.map { item ->
            MenuRow(item.title, icon = jumpIcon(item) ?: appIcon) { JumpLists.open(item) }
        })
    }
    val actions = buildList {
        // Like the taskbar: the app's name starts another instance.
        add(MenuRow(app.label, icon = appIcon, enabled = pin != null) { apps.launchNew(itemId) })
        if (app.isPinned) {
            add(MenuRow("Desafixar do dock", glyph = Glyphs.UNPIN) { dispatch(UiEvent.UnpinApp(itemId)) })
        } else if (pin != null) {
            add(MenuRow("Fixar no dock", glyph = Glyphs.PIN) { dispatch(UiEvent.PinApp(pin)) })
        }
        if (windows > 0) {
            add(MenuRow(if (windows == 1) "Fechar janela" else "Fechar todas as janelas", glyph = Glyphs.CLOSE) { apps.closeWindows(itemId) })
        }
    }
    return DockMenu(sections, actions)
}

private val iconCache = object : LinkedHashMap<String, ImageBitmap?>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 96
}

/** The entry's own icon, else the shell's icon for the file or folder it opens. */
private fun jumpIcon(item: JumpItem): ImageBitmap? {
    val key = item.iconSpec ?: item.path ?: return null
    synchronized(iconCache) { if (iconCache.containsKey(key)) return iconCache[key] }
    val icon = item.iconSpec?.let { spec ->
        // Specs like "explorer.exe,0" are relative to the Windows folder.
        val file = spec.substringBeforeLast(',')
        val absolute = if (File(file).isAbsolute) spec else File(System.getenv("WINDIR") ?: "C:\\Windows", file).path + "," + spec.substringAfterLast(',')
        resourceIcon(absolute)
    } ?: item.path?.let { path -> File(path).takeIf { it.exists() }?.let(::systemIcon) }
    synchronized(iconCache) { iconCache[key] = icon }
    return icon
}

// ---------------------------------------------------------------- Popup

internal object MenuLayout {
    val Width = 300.dp
    val Padding = 4.dp
    val HeaderHeight = 30.dp
    val RowHeight = 36.dp
    val DividerHeight = 9.dp

    fun height(menu: DockMenu): Dp {
        var h = Padding * 2 + RowHeight * menu.actions.size
        menu.sections.forEach { h += HeaderHeight + RowHeight * it.rows.size }
        if (menu.sections.isNotEmpty() && menu.actions.isNotEmpty()) h += DividerHeight
        return h
    }
}

private val MenuBackground = Color(0xFF2C2C2C)
private val MenuBorder = Color(0x33FFFFFF)
private val MenuText = Color(0xFFF3F3F3)
private val MenuDisabled = Color(0x66FFFFFF)

/** Windows 11 ships "Segoe Fluent Icons"; Windows 10 has the same glyphs in "Segoe MDL2 Assets". */
@OptIn(ExperimentalTextApi::class)
private val GlyphFont: FontFamily by lazy {
    val names = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
    FontFamily(SystemFont(if ("Segoe Fluent Icons" in names) "Segoe Fluent Icons" else "Segoe MDL2 Assets"))
}

@Composable
internal fun DockMenuPopup(menu: DockMenu, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(MenuBackground).border(1.dp, MenuBorder, shape).padding(MenuLayout.Padding),
    ) {
        menu.sections.forEach { section ->
            Box(Modifier.fillMaxWidth().height(MenuLayout.HeaderHeight).padding(start = 12.dp, top = 8.dp)) {
                Text(section.title.orEmpty(), color = MenuText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            section.rows.forEach { MenuRowView(it, onDismiss) }
        }
        if (menu.sections.isNotEmpty() && menu.actions.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(MenuLayout.DividerHeight), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
            }
        }
        menu.actions.forEach { MenuRowView(it, onDismiss) }
    }
}

@Composable
private fun MenuRowView(row: MenuRow, onDismiss: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = if (row.enabled) MenuText else MenuDisabled
    Row(
        Modifier.fillMaxWidth().height(MenuLayout.RowHeight).clip(RoundedCornerShape(4.dp))
            .background(if (hovered && row.enabled) Color(0x14FFFFFF) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = row.enabled) {
                onDismiss()
                // Launching can block briefly (ShellExecute); keep it off the UI thread.
                Thread({ runCatching(row.run) }, "dock-menu-action").apply { isDaemon = true }.start()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                row.icon != null -> Image(row.icon, null, Modifier.size(16.dp))
                row.glyph != null -> Text(row.glyph.toString(), color = color, fontSize = 14.sp, fontFamily = GlyphFont)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            row.label, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (row.bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
