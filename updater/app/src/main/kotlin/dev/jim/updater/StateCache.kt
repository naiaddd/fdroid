package dev.jim.updater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last successful network read (the resolved app/release list) so the
 * UI can render installed-vs-latest immediately on launch instead of showing a blank
 * list until "Check" fetches from the network. Installed versions are still read live
 * from PackageManager at render time — only the expensive remote half is cached here.
 */
object StateCache {
    private const val PREFS = "updater_state"
    private const val KEY_RESOLVED = "resolved_apps"

    fun save(context: Context, apps: List<ResolvedApp>) {
        val array = JSONArray()
        for (app in apps) {
            val assets = JSONArray()
            for (asset in app.release.assets) {
                assets.put(
                    JSONObject()
                        .put("name", asset.name)
                        .put("downloadUrl", asset.downloadUrl),
                )
            }
            array.put(
                JSONObject()
                    .put("displayName", app.entry.displayName)
                    .put("packageName", app.entry.packageName)
                    .put("tagPrefix", app.entry.tagPrefix)
                    .put("hasAbiSplit", app.entry.hasAbiSplit)
                    .put("tagName", app.release.tagName)
                    .put("body", app.release.body)
                    .put("remoteVersion", app.remoteVersion)
                    .put("baseRemoteCode", app.baseRemoteCode)
                    .put("assets", assets),
            )
        }
        prefs(context).edit().putString(KEY_RESOLVED, array.toString()).apply()
    }

    fun load(context: Context): List<ResolvedApp> {
        val raw = prefs(context).getString(KEY_RESOLVED, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val assetsJson = obj.getJSONArray("assets")
                val assets =
                    (0 until assetsJson.length()).map { j ->
                        val a = assetsJson.getJSONObject(j)
                        ReleaseAsset(a.getString("name"), a.getString("downloadUrl"))
                    }
                ResolvedApp(
                    AppEntry(
                        obj.getString("displayName"),
                        obj.getString("packageName"),
                        obj.getString("tagPrefix"),
                        obj.getBoolean("hasAbiSplit"),
                    ),
                    ReleaseInfo(obj.getString("tagName"), assets, obj.getString("body")),
                    obj.getString("remoteVersion"),
                    obj.getLong("baseRemoteCode"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
