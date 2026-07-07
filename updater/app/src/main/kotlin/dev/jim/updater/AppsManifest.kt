package dev.jim.updater

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ManifestEntry(val packageName: String, val displayName: String)

/**
 * apps.json lives at the root of the fdroid repo and maps GitHub release tag
 * prefixes to Android package names — the one piece of info that can't be
 * derived from the releases themselves. deploy.sh keeps it in sync on every
 * release, so new apps show up here without ever touching Updater's source.
 */
object AppsManifest {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/naiaddd/fdroid/main/apps.json"

    fun fetch(): Map<String, ManifestEntry> {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("apps.json fetch returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val result = mutableMapOf<String, ManifestEntry>()
            json.keys().forEach { key ->
                val entryJson = json.getJSONObject(key)
                result[key] = ManifestEntry(entryJson.getString("packageName"), entryJson.getString("displayName"))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }
}

data class ResolvedApp(
    val entry: AppEntry,
    val release: ReleaseInfo,
    val remoteVersion: String,
    val baseRemoteCode: Long,
)

/**
 * Combines the manifest with the raw release list to find, per app, the release
 * with the highest version code — releases group by tag prefix ("<prefix>-v<version>+<code>").
 */
fun discoverApps(
    manifest: Map<String, ManifestEntry>,
    releases: List<ReleaseInfo>,
): List<ResolvedApp> {
    val tagRegex = Regex("^(.+)-v(\\d+\\.\\d+\\.\\d+)\\+(\\d+)$")
    val parsed =
        releases.mapNotNull { release ->
            tagRegex.matchEntire(release.tagName)?.let { match ->
                val (prefix, version, codeStr) = match.destructured
                Triple(prefix, version to codeStr.toLong(), release)
            }
        }

    return parsed
        .groupBy { it.first }
        .mapNotNull { (prefix, candidates) ->
            val manifestEntry = manifest[prefix] ?: return@mapNotNull null
            val (_, versionAndCode, release) = candidates.maxByOrNull { it.second.second } ?: return@mapNotNull null
            val (version, code) = versionAndCode
            val hasAbiSplit =
                release.assets.any {
                    it.name == "$prefix-arm64-v8a.apk" || it.name == "$prefix-armeabi-v7a.apk"
                }
            ResolvedApp(
                AppEntry(manifestEntry.displayName, manifestEntry.packageName, prefix, hasAbiSplit),
                release,
                version,
                code,
            )
        }
        .sortedBy { it.entry.displayName }
}
