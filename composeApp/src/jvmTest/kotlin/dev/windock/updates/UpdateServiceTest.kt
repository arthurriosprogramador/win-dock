package dev.windock.updates

import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class UpdateServiceTest {
    private val repository = "arthurriosprogramador/win-dock"
    private val digest = "a".repeat(64)
    private fun release(tag: String = "v1.1.0", sha: String = "sha256:$digest",
        url: String = "https://github.com/$repository/releases/download/$tag/WinDock.msi") = """
        {
          "tag_name":"$tag", "draft":false, "prerelease":false,
          "body":"Correções\nNovidades", "assets":[
            {"name":"WinDock.msi", "state":"uploaded", "size":70000000,
             "digest":"$sha", "browser_download_url":"$url"}
          ]
        }
    """.trimIndent()

    @Test fun versionsCompareNumerically() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        assertEquals(ReleaseVersion.parse("v1.1.0"), ReleaseVersion.parse("1.1.0"))
    }

    @Test fun unstableAndInvalidVersionsAreRejected() {
        listOf("1.1.0-beta", "v2.0.0+build", "1.2", "0.1.0", "999999999999.1.0", "256.0.0", "1.0.65536")
            .forEach { assertNull(ReleaseVersion.parse(it), it) }
    }

    @Test fun recognizesStableMsiAndNotes() {
        val update = assertNotNull(parseUpdate(release(), "1.0.0", repository))
        assertEquals(ReleaseVersion(1, 1, 0), update.version)
        assertEquals("Correções\nNovidades", update.notes)
        assertEquals(digest, update.sha256)
    }

    @Test fun doesNotDowngradeOrReinstallSameVersion() {
        assertNull(parseUpdate(release(), "1.1.0", repository))
        assertNull(parseUpdate(release(), "1.2.0", repository))
    }

    @Test fun ignoresDraftAndPrerelease() {
        assertNull(parseUpdate(release().replace("\"draft\":false", "\"draft\":true"), "1.0.0", repository))
        assertNull(parseUpdate(release().replace("\"prerelease\":false", "\"prerelease\":true"), "1.0.0", repository))
    }

    @Test fun rejectsUntrustedDownloadAndMissingChecksum() {
        assertFailsWith<IllegalArgumentException> {
            parseUpdate(release(url = "https://example.com/WinDock.msi"), "1.0.0", repository)
        }
        assertFailsWith<IllegalArgumentException> {
            parseUpdate(release(sha = ""), "1.0.0", repository)
        }
    }

    @Test fun ignoresWrongAssetAndRejectsOversizedDownload() {
        assertNull(parseUpdate(release().replace("\"name\":\"WinDock.msi\"", "\"name\":\"WinDock-arm64.msi\""), "1.0.0", repository))
        assertFailsWith<IllegalArgumentException> {
            parseUpdate(release().replace("70000000", "9999999999"), "1.0.0", repository)
        }
    }

    @Test fun verifiesBytesAndRejectsTruncationOrTampering() = runBlocking {
        val file = Files.createTempFile("windock-integrity", ".msi").toFile()
        try {
            val original = "installer fixture".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            val release = UpdateRelease(ReleaseVersion(1, 1, 0), "", URI("https://github.com"),
                original.size.toLong(), hash)
            file.writeBytes(original)
            verifyInstaller(file, release)
            file.writeBytes(original.dropLast(1).toByteArray())
            assertFailsWith<IllegalStateException> { verifyInstaller(file, release) }
            file.writeBytes(original.copyOf().apply { this[0] = 0 })
            assertFailsWith<IllegalStateException> { verifyInstaller(file, release) }
        } finally {
            file.delete()
        }
        Unit
    }

    @Test fun packagedMetadataIsAvailable() {
        assertNotNull(ReleaseVersion.parse(UpdateBuild.version))
        assertEquals(repository, UpdateBuild.repository)
        assertNotNull(UpdateBuild::class.java.getResource("/install-update.ps1"))
    }
}
