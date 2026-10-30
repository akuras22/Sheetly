package com.akuras.pdfscanner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesVersionCodeFromTag() {
        assertEquals(105, parseVersionCodeFromTag("v1.0.5"))
        assertEquals(2, parseVersionCodeFromTag("release-2"))
    }

    @Test
    fun detectsNewerSemverVersion() {
        assertTrue(isNewerVersionAvailable("v1.4.0", "v1.3.9", 140, 139))
        assertFalse(isNewerVersionAvailable("v1.3.9", "v1.3.9", 139, 139))
    }

    @Test
    fun acceptsOnlyTrustedGithubApkUrls() {
        assertTrue(isTrustedApkUrl("https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release.apk", "akuras22", "PDFScanner"))
        assertTrue(isTrustedApkUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/abc.apk", "akuras22", "PDFScanner"))
        assertFalse(isTrustedApkUrl("http://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release.apk", "akuras22", "PDFScanner"))
        assertFalse(isTrustedApkUrl("https://evil.example.com/app-release.apk", "akuras22", "PDFScanner"))
        assertFalse(isTrustedApkUrl("https://github.com/evil/x/akuras22/PDFScanner/releases/download/v1.0.0/a.apk", "akuras22", "PDFScanner"))
    }

    @Test
    fun prefersSignedApkAsset() {
        val json = JSONObject(
            """
            {
              "assets": [
                {"name":"app-release-unsigned.apk","browser_download_url":"https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release-unsigned.apk"},
                {"name":"app-release-signed.apk","browser_download_url":"https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release-signed.apk"}
              ]
            }
            """.trimIndent()
        )
        val url = json.findApkAssetUrl("akuras22", "PDFScanner")
        assertEquals(
            "https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release-signed.apk",
            url
        )
    }

    @Test
    fun acceptsSignedCiAssetName() {
        val json = JSONObject(
            """
            {
              "assets": [
                {"name":"app-release-unsigned-signed.apk","browser_download_url":"https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release-unsigned-signed.apk"}
              ]
            }
            """.trimIndent()
        )
        assertNotNull(json.findApkAssetUrl("akuras22", "PDFScanner"))
    }

    @Test
    fun skipsUnsignedApkAsset() {
        val json = JSONObject(
            """
            {
              "assets": [
                {"name":"app-release-unsigned.apk","browser_download_url":"https://github.com/akuras22/PDFScanner/releases/download/v1.0.0/app-release-unsigned.apk"}
              ]
            }
            """.trimIndent()
        )
        assertNull(json.findApkAssetUrl("akuras22", "PDFScanner"))
    }
}
