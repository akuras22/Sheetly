package com.akuras.pdfscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

suspend fun fetchAvailableUpdate(
    currentVersionCode: Int,
    currentVersionName: String,
    owner: String,
    repo: String,
): AvailableUpdate? = withContext(Dispatchers.IO) {
    val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "PDFScanner-Update-Check")
    }

    try {
        if (connection.responseCode !in 200..299) {
            return@withContext null
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val release = JSONObject(responseText)

        val tagName = release.optString("tag_name")
        val latestVersionName = tagName.ifBlank { release.optString("name", "Unknown") }
        val latestCode = parseVersionCodeFromTag(tagName)
            ?: parseVersionCodeFromName(release.optString("name"))
            ?: return@withContext null

        if (!isNewerVersionAvailable(latestVersionName, currentVersionName, latestCode, currentVersionCode)) {
            return@withContext null
        }

        val downloadUrl = release.findApkAssetUrl(owner, repo) ?: return@withContext null

        AvailableUpdate(
            versionCode = latestCode,
            versionName = latestVersionName,
            downloadUrl = downloadUrl,
        )
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

internal fun parseVersionCodeFromTag(tag: String): Int? {
    return parseVersionCode(tag)
}

internal fun parseVersionCodeFromName(name: String): Int? {
    return parseVersionCode(name)
}

internal fun parseVersionCode(input: String): Int? {
    if (input.isBlank()) return null

    // Keep parsing aligned with CI tags like v1.0 or v1.0.1 by joining numeric parts.
    val semverLike = Regex("(\\d+(?:\\.\\d+){0,2})")
        .find(input)
        ?.groupValues
        ?.getOrNull(1)

    if (!semverLike.isNullOrBlank()) {
        return semverLike.replace(".", "").toIntOrNull()
    }

    return Regex("(\\d+)").find(input)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun isNewerVersionAvailable(
    latestVersionName: String,
    currentVersionName: String,
    latestVersionCode: Int,
    currentVersionCode: Int,
): Boolean {
    val latestParts = parseVersionParts(latestVersionName)
    val currentParts = parseVersionParts(currentVersionName)

    if (latestParts != null && currentParts != null) {
        val maxSize = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val latestPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (latestPart != currentPart) {
                return latestPart > currentPart
            }
        }
        return false
    }

    return latestVersionCode > currentVersionCode
}

internal fun parseVersionParts(input: String): List<Int>? {
    if (input.isBlank()) return null
    val normalized = Regex("(\\d+(?:\\.\\d+)+|\\d+)").find(input)?.groupValues?.getOrNull(1) ?: return null
    val rawParts = normalized.split('.')
    if (rawParts.any { it.isBlank() }) return null
    val parts = rawParts.mapNotNull { it.toIntOrNull() }
    return parts.ifEmpty { null }
}

internal fun JSONObject.findApkAssetUrl(owner: String, repo: String): String? {
    val assets = optJSONArray("assets") ?: JSONArray()
    var preferredSigned: String? = null
    var preferredRegular: String? = null

    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val browserUrl = asset.optString("browser_download_url")
        if (!name.endsWith(".apk", ignoreCase = true) || browserUrl.isBlank() || !isTrustedApkUrl(browserUrl, owner, repo)) {
            continue
        }

        // CI publishes app-release-unsigned-signed.apk, so only the end of the name is meaningful.
        val lowerName = name.lowercase()
        when {
            lowerName.endsWith("unsigned.apk") -> continue
            lowerName.endsWith("signed.apk") -> if (preferredSigned == null) preferredSigned = browserUrl
            else -> if (preferredRegular == null) preferredRegular = browserUrl
        }
    }
    return preferredSigned ?: preferredRegular
}

internal fun isTrustedApkUrl(url: String, owner: String, repo: String): Boolean {
    val uri = runCatching { URL(url) }.getOrNull() ?: return false
    if (uri.protocol.lowercase(Locale.ROOT) != "https") return false
    val host = uri.host.lowercase(Locale.ROOT)
    if (host == "github.com") {
        val path = uri.path.lowercase(Locale.ROOT)
        return path.startsWith("/$owner/$repo/releases/download/".lowercase(Locale.ROOT))
    }
    return host == "objects.githubusercontent.com"
}
