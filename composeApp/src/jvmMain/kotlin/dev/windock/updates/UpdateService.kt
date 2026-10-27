package dev.windock.updates

import dev.windock.AppFolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import kotlin.coroutines.coroutineContext

internal object UpdateBuild {
    private val properties = Properties().apply {
        checkNotNull(UpdateBuild::class.java.getResourceAsStream("/update.properties")).use { load(it) }
    }
    val version: String = properties.getProperty("version")
    val repository: String = properties.getProperty("repository")
    val launcher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
    val canInstall: Boolean get() = System.getProperty("os.name").startsWith("Windows") &&
        System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") && launcher != null
}

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other,
        ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            if (!Regex("v?[1-9][0-9]*\\.[0-9]+\\.[0-9]+").matches(value)) return null
            val parts = value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
            if (parts[0] > 255 || parts[1] > 255 || parts[2] > 65535) return null
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class UpdateRelease(
    val version: ReleaseVersion,
    val notes: String,
    val download: URI,
    val size: Long,
    val sha256: String,
)

internal suspend fun verifyInstaller(file: File, release: UpdateRelease) {
    check(file.length() == release.size) { "O download está incompleto." }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual == release.sha256) { "A integridade do instalador não pôde ser confirmada. Tente novamente." }
}

/** Only the public repository's stable, versioned MSI asset is eligible for automatic installation. */
internal fun parseUpdate(json: String, currentVersion: String, repository: String): UpdateRelease? {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root["draft"]?.jsonPrimitive?.booleanOrNull != false ||
        root["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
    val version = ReleaseVersion.parse(tag) ?: return null
    val current = requireNotNull(ReleaseVersion.parse(currentVersion))
    if (version <= current) return null
    val asset = root["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
        it["name"]?.jsonPrimitive?.contentOrNull == "WinDock.msi" &&
            it["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
    } ?: return null
    val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
    require(url == "https://github.com/$repository/releases/download/$tag/WinDock.msi") {
        "O instalador não pertence à release esperada."
    }
    val digest = asset["digest"]?.jsonPrimitive?.contentOrNull.orEmpty()
    require(Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) { "A release ainda não possui SHA-256 válido." }
    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0
    require(size in 1..(512L * 1024 * 1024)) { "Tamanho de instalador inválido." }
    return UpdateRelease(version, root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(8000),
        URI(url), size, digest.substringAfter(':').lowercase())
}

internal class UpdateService {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    suspend fun latest(): UpdateRelease? = runInterruptible {
        val url = "https://api.github.com/repos/" + UpdateBuild.repository + "/releases/latest"
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(25))
            .header("User-Agent", "WinDock/" + UpdateBuild.version)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            404 -> null
            200 -> parseUpdate(response.body(), UpdateBuild.version, UpdateBuild.repository)
            else -> error("Não foi possível consultar as releases (HTTP " + response.statusCode() + ").")
        }
    }

    /** Stream directly to disk; report progress without retaining the MSI in the dock's small heap. */
    suspend fun download(release: UpdateRelease, progress: (Float) -> Unit): File {
        val root = File(AppFolder, "updates").apply { mkdirs() }
        val folder = Files.createTempDirectory(root.toPath(), "download-").toFile()
        val partial = File(folder, "WinDock.msi.part")
        val request = HttpRequest.newBuilder(release.download).timeout(Duration.ofMinutes(10))
            .header("User-Agent", "WinDock/" + UpdateBuild.version).build()
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
        var completed = false
        try {
            val response = withTimeout(10 * 60_000L) {
                while (!future.isDone) {
                    ensureActive()
                    check(partial.length() <= release.size) { "O download excedeu o tamanho informado." }
                    progress((partial.length().toFloat() / release.size).coerceIn(0f, 1f))
                    delay(250)
                }
                future.get()
            }
            check(response.statusCode() == 200) { "Falha ao baixar o instalador (HTTP " + response.statusCode() + ")." }
            check(response.uri().scheme == "https" && response.uri().host in setOf(
                "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
            )) { "Destino de download inesperado." }
            verifyInstaller(partial, release)
            val installer = File(folder, "WinDock.msi")
            Files.move(partial.toPath(), installer.toPath())
            progress(1f)
            completed = true
            return installer
        } finally {
            future.cancel(true)
            if (!completed) {
                runCatching { Files.deleteIfExists(partial.toPath()) }
                runCatching { Files.deleteIfExists(folder.toPath()) }
            }
        }
    }

    /** Hand off to a separate process before exiting, so Windows Installer can replace the JVM runtime. */
    suspend fun prepareInstaller(installer: File, sha256: String) {
        check(UpdateBuild.canInstall) { "A atualização automática está disponível no WinDock instalado pelo MSI." }
        val launcher = checkNotNull(UpdateBuild.launcher)
        val script = File(installer.parentFile, "install-update.ps1")
        UpdateService::class.java.getResourceAsStream("/install-update.ps1")!!.use { input ->
            script.outputStream().use { input.copyTo(it) }
        }
        val ready = File(installer.parentFile, "ready")
        val powershell = File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
        val process = ProcessBuilder(powershell.path, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", script.path, "-DockPid", ProcessHandle.current().pid().toString(),
            "-Installer", installer.path, "-Launcher", launcher.path, "-Sha256", sha256, "-ReadyFile", ready.path)
            .redirectErrorStream(true).redirectOutput(File(installer.parentFile, "helper.log")).start()
        try {
            withTimeout(10_000) {
                while (!ready.exists()) {
                    check(process.isAlive) { "Não foi possível iniciar o atualizador." }
                    delay(100)
                }
            }
        } catch (error: Throwable) {
            process.destroy()
            throw error
        }
    }
}
