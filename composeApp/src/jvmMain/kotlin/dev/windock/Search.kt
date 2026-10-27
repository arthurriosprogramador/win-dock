package dev.windock

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windock.windows.AppIndex
import java.net.URLEncoder
import java.text.Normalizer

internal enum class ResultKind { APP, SETTING, FILES, WEB }

/** One row of the dock's search: what to show and the URI/command that opens it. */
internal data class SearchResult(val kind: ResultKind, val title: String, val subtitle: String, val target: String, val appId: String? = null)

private class SettingPage(val title: String, val uri: String, val keywords: String)

/** Common Windows Settings pages, so typing "wifi" or "papel de parede" goes straight to the right page. */
private val SettingPages = listOf(
    SettingPage("Wi-Fi", "ms-settings:network-wifi", "rede internet wireless sem fio"),
    SettingPage("Bluetooth e dispositivos", "ms-settings:bluetooth", "fone mouse teclado parear"),
    SettingPage("Rede e Internet", "ms-settings:network", "ethernet conexão"),
    SettingPage("VPN", "ms-settings:network-vpn", "rede privada"),
    SettingPage("Modo avião", "ms-settings:network-airplanemode", "aviao"),
    SettingPage("Proxy", "ms-settings:network-proxy", "rede"),
    SettingPage("Tela", "ms-settings:display", "monitor resolução brilho escala hdr"),
    SettingPage("Som", "ms-settings:sound", "volume áudio alto-falante microfone fone"),
    SettingPage("Notificações", "ms-settings:notifications", "alertas não perturbe"),
    SettingPage("Energia e bateria", "ms-settings:powersleep", "suspensão economia de energia desligar tela"),
    SettingPage("Armazenamento", "ms-settings:storagesense", "disco espaço limpeza"),
    SettingPage("Área de transferência", "ms-settings:clipboard", "copiar colar histórico"),
    SettingPage("Personalização", "ms-settings:personalization", "aparência"),
    SettingPage("Plano de fundo", "ms-settings:personalization-background", "papel de parede wallpaper imagem"),
    SettingPage("Cores", "ms-settings:colors", "modo escuro claro tema cor de destaque"),
    SettingPage("Temas", "ms-settings:themes", "aparência"),
    SettingPage("Tela de bloqueio", "ms-settings:lockscreen", "bloqueio"),
    SettingPage("Barra de tarefas", "ms-settings:taskbar", "taskbar"),
    SettingPage("Aplicativos instalados", "ms-settings:appsfeatures", "desinstalar programas apps"),
    SettingPage("Aplicativos padrão", "ms-settings:defaultapps", "navegador padrão abrir com"),
    SettingPage("Aplicativos de inicialização", "ms-settings:startupapps", "iniciar com o windows startup"),
    SettingPage("Contas", "ms-settings:yourinfo", "usuário perfil microsoft"),
    SettingPage("Opções de entrada", "ms-settings:signinoptions", "senha pin windows hello digital"),
    SettingPage("Data e hora", "ms-settings:dateandtime", "relógio fuso horário"),
    SettingPage("Idioma e região", "ms-settings:regionlanguage", "idioma língua formato"),
    SettingPage("Teclado", "ms-settings:keyboard", "layout digitação"),
    SettingPage("Mouse", "ms-settings:mousetouchpad", "ponteiro cursor rolagem"),
    SettingPage("Impressoras e scanners", "ms-settings:printers", "imprimir impressora"),
    SettingPage("Privacidade e segurança", "ms-settings:privacy", "permissões"),
    SettingPage("Localização", "ms-settings:privacy-location", "gps local"),
    SettingPage("Câmera", "ms-settings:privacy-webcam", "webcam"),
    SettingPage("Microfone", "ms-settings:privacy-microphone", "mic"),
    SettingPage("Windows Update", "ms-settings:windowsupdate", "atualizações atualizar"),
    SettingPage("Segurança do Windows", "windowsdefender:", "antivírus defender firewall vírus"),
    SettingPage("Sobre", "ms-settings:about", "especificações nome do computador versão sistema"),
    SettingPage("Modo de jogo", "ms-settings:gaming-gamemode", "jogos"),
    SettingPage("Acessibilidade", "ms-settings:easeofaccess", "lupa narrador contraste"),
    SettingPage("Painel de Controle", "control", "painel"),
)

private val Accents = Regex("\\p{Mn}+")

private fun normalize(text: String) =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Accents, "")

/** Lower case, no accents, no spaces or punctuation: "Wi-Fi" and "wifi" compare equal. */
private fun compact(text: String) = normalize(text).filter { it.isLetterOrDigit() }

/** A name prepared once for matching: searching then only compares strings on each keystroke. */
private class Searchable(name: String, keywords: String = "") {
    val normalized = normalize(name)
    val compact = compact(name)
    val words = normalized.split(' ', '-', '.', '(')
    val keywords = normalize(keywords).split(' ')
}

private val searchables = java.util.concurrent.ConcurrentHashMap<String, Searchable>()

private fun searchable(name: String, keywords: String = "") = searchables.getOrPut(name + "\u0000" + keywords) { Searchable(name, keywords) }

/** Higher is better; 0 means no match. Whole-name prefix beats word prefix beats substring. */
private fun score(query: String, compactQuery: String, item: Searchable): Int = when {
    compactQuery.isEmpty() -> 0
    item.normalized == query || item.compact == compactQuery -> 100
    item.normalized.startsWith(query) || item.compact.startsWith(compactQuery) -> 80
    item.words.any { it.startsWith(query) } -> 60
    query in item.normalized || compactQuery in item.compact -> 40
    item.keywords.any { it.startsWith(query) } -> 30
    else -> 0
}

/** Apps and Settings pages for [query], best first, followed by "search files" and "search the web". */
internal fun searchFor(query: String, index: AppIndex): List<SearchResult> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return emptyList()
    val cq = compact(q)
    val apps = index.apps.map { it to score(q, cq, searchable(it.name)) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<*, Int>> { it.second }.thenBy { (it.first as dev.windock.windows.StartApp).name.length })
        .take(6).map { (app, _) -> SearchResult(ResultKind.APP, app.name, "Aplicativo", "shell:AppsFolder\\${app.appId}", app.appId) }
    val settings = SettingPages.map { it to score(q, cq, searchable(it.title, it.keywords)) }.filter { it.second > 0 }
        .sortedByDescending { it.second }.take(3)
        .map { (page, _) -> SearchResult(ResultKind.SETTING, page.title, "Configurações", page.uri) }
    val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8).replace("+", "%20")
    val home = System.getProperty("user.home")
    return apps + settings + listOf(
        SearchResult(ResultKind.FILES, "Pesquisar arquivos por “${query.trim()}”", "Abre no Explorador de Arquivos",
            "search-ms:query=$encoded&crumb=location:${URLEncoder.encode(home, Charsets.UTF_8).replace("+", "%20")}"),
        SearchResult(ResultKind.WEB, "Pesquisar “${query.trim()}” na web", "Abre no navegador", "https://www.google.com/search?q=$encoded"),
    )
}

/** Shared between the search field (in the bar window) and its results panel (a window of its own). */
@Stable
internal class SearchSession {
    var query by mutableStateOf("")
    var results by mutableStateOf(emptyList<SearchResult>())
    var selected by mutableIntStateOf(0)
    /** The search field's left edge, in bar-window pixels. */
    var anchorX by mutableStateOf(0f)
    /** Opens a result; set by the field, so a click in the panel behaves like Enter. */
    var open: (SearchResult) -> Unit = {}
}

internal val LocalAppIndex = staticCompositionLocalOf<AppIndex?> { null }

internal object SearchLayout {
    val Width = 440.dp
    val RowHeight = 46.dp
    val Padding = 8.dp
    fun height(rows: Int): Dp = Padding * 2 + RowHeight * rows
}

/** The results panel shown above the dock while typing in the search field. */
@Composable
internal fun SearchPanel(session: SearchSession, index: AppIndex) {
    Column(Modifier.fillMaxSize().background(Color(0xFF1E222C)).padding(SearchLayout.Padding)) {
        session.results.forEachIndexed { i, result ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            LaunchedEffect(hovered) { if (hovered) session.selected = i }
            val selected = i == session.selected
            Row(
                Modifier.fillMaxWidth().height(SearchLayout.RowHeight).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Color(0xFF2E3546) else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null) { session.open(result) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    val icon = result.appId?.let { index.icons[it] ?: index.icon(it) }
                    if (icon != null) Image(icon, null, Modifier.size(28.dp), filterQuality = FilterQuality.High)
                    else ResultGlyph(result.kind)
                }
                Column(Modifier.weight(1f)) {
                    Text(result.title, color = Color(0xFFF1F3F7), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(result.subtitle, color = Color(0xFF9AA1AE), fontSize = 11.sp, maxLines = 1)
                }
                if (selected) Text("Enter ↵", color = Color(0xFF7C8494), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ResultGlyph(kind: ResultKind) {
    Canvas(Modifier.size(22.dp)) {
        val u = 1.dp.toPx()
        val w = size.width
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
        when (kind) {
            ResultKind.SETTING -> {
                val c = Offset(w / 2, w / 2)
                drawCircle(Color(0xFFB8C2D6), w * .32f, c, style = Stroke(3.2f * u))
                drawCircle(Color(0xFFB8C2D6), w * .1f, c)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                    drawLine(Color(0xFFB8C2D6), c + dir * (w * .34f), c + dir * (w * .48f), 3 * u, StrokeCap.Round)
                }
            }
            ResultKind.FILES -> {
                drawRoundRect(Color(0xFFFFC83D), Offset(w * .05f, w * .18f), Size(w * .42f, w * .22f), androidx.compose.ui.geometry.CornerRadius(2 * u))
                drawRoundRect(Color(0xFFFFD75E), Offset(w * .05f, w * .3f), Size(w * .9f, w * .55f), androidx.compose.ui.geometry.CornerRadius(3 * u))
            }
            ResultKind.WEB -> {
                val c = Offset(w / 2, w / 2)
                drawCircle(Color(0xFF6CB6FF), w * .42f, c, style = stroke)
                drawOval(Color(0xFF6CB6FF), Offset(w * .3f, w * .08f), Size(w * .4f, w * .84f), style = stroke)
                drawLine(Color(0xFF6CB6FF), Offset(w * .08f, w / 2), Offset(w * .92f, w / 2), 1.8f * u)
            }
            ResultKind.APP -> drawRoundRect(Color(0xFF3A4152), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6 * u))
        }
    }
}
