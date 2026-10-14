package app.bolbol.tv.core.update

import app.bolbol.tv.core.model.AppUpdate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val sha = "a".repeat(64)

    private fun manifest(
        versionCode: String = "5",
        versionName: String = "\"1.4.1\"",
        apkUrl: String = "\"https://example.com/m.apk\"",
        sizeBytes: String = "12345",
        sha256: String = "\"$sha\"",
        extra: String = "",
    ) = """{"versionCode":$versionCode,"versionName":$versionName,"apkUrl":$apkUrl,"sizeBytes":$sizeBytes,
        |"sha256":$sha256,"notesFa":"چیزهای تازه"$extra}""".trimMargin()

    @Test fun parsesAFullManifest() {
        val u = UpdateManifest.parse(manifest())!!
        assertEquals(AppUpdate(5, "1.4.1", "https://example.com/m.apk", 12345L, sha, "چیزهای تازه"), u)
    }

    @Test fun numbersMayBeStrings() {
        val u = UpdateManifest.parse(manifest(versionCode = "\"7\"", sizeBytes = "\"999\""))!!
        assertEquals(7, u.versionCode)
        assertEquals(999L, u.sizeBytes)
    }

    @Test fun unknownKeysAndWhitespaceAreIgnored() {
        val u = UpdateManifest.parse(manifest(extra = ""","future":{"a":[1,2]},"channel":"beta"""", sha256 = "\"  ${sha.uppercase()} \""))!!
        assertEquals(sha, u.sha256)
    }

    @Test fun missingOptionalFieldsGetDefaults() {
        val text = """{"versionCode":9,"apkUrl":"http://10.0.2.2:8000/a.apk","sha256":"$sha"}"""
        val u = UpdateManifest.parse(text)!!
        assertEquals("9", u.versionName)
        assertEquals(0L, u.sizeBytes)
        assertEquals("", u.notesFa)
    }

    @Test fun nullNotesAreEmpty() {
        val text = """{"versionCode":9,"apkUrl":"https://e.com/a.apk","sha256":"$sha","notesFa":null,"sizeBytes":null}"""
        val u = UpdateManifest.parse(text)!!
        assertEquals("", u.notesFa)
        assertEquals(0L, u.sizeBytes)
    }

    @Test fun rejectsUnusableManifests() {
        listOf(
            "", "not json", "[]", "null", "\"x\"", "{}",
            manifest(versionCode = "0"),
            manifest(versionCode = "-3"),
            manifest(versionCode = "\"abc\""),
            manifest(apkUrl = "\"ftp://example.com/m.apk\""),
            manifest(apkUrl = "\"\""),
            manifest(sha256 = "\"deadbeef\""),
            manifest(sha256 = "\"${"g".repeat(64)}\""),
            manifest(sha256 = "null"),
        ).forEach { assertNull("should be rejected: $it", UpdateManifest.parse(it)) }
    }

    @Test fun onlyAHigherVersionCodeIsNewer() {
        val u = UpdateManifest.parse(manifest(versionCode = "5"))!!
        assertTrue(isNewer(u, 4))
        assertFalse(isNewer(u, 5))
        assertFalse(isNewer(u, 6))
    }

    @Test fun sha256KnownVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".byteInputStream()),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex("".byteInputStream()),
        )
    }

    @Test fun verifySha256ChecksFileContent() {
        val f = File.createTempFile("upd", ".apk").apply { writeText("abc"); deleteOnExit() }
        val good = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertTrue(verifySha256(f, good))
        assertTrue(verifySha256(f, good.lowercase()))
        assertFalse(verifySha256(f, "0".repeat(64)))
        f.writeText("abd")
        assertFalse(verifySha256(f, good))
        assertFalse(verifySha256(File(f.path + ".missing"), good))
        assertNotNull(f)
    }
}
