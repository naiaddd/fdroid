package dev.jim.updater

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val downloadUrl: String)

data class ReleaseInfo(val tagName: String, val assets: List<ReleaseAsset>, val body: String)

/**
 * Reads GitHub Releases as the update index — deploy.sh publishes one release per
 * app version (tag "<app>-v<version>+<code>") instead of an F-Droid repo index.
 */
object GithubApi {
    private const val RELEASES_URL = "https://api.github.com/repos/naiaddd/fdroid/releases?per_page=100"

    fun fetchReleases(): List<ReleaseInfo> {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releasesJson = JSONArray(body)
            return (0 until releasesJson.length()).map { i ->
                val releaseJson = releasesJson.getJSONObject(i)
                val assetsJson = releaseJson.getJSONArray("assets")
                val assets =
                    (0 until assetsJson.length()).map { j ->
                        val assetJson = assetsJson.getJSONObject(j)
                        ReleaseAsset(assetJson.getString("name"), assetJson.getString("browser_download_url"))
                    }
                val body = releaseJson.optString("body", "")
                ReleaseInfo(releaseJson.getString("tag_name"), assets, body)
            }
        } finally {
            connection.disconnect()
        }
    }
}
