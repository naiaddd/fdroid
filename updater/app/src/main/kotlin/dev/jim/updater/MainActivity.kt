package dev.jim.updater

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.jim.updater.databinding.ActivityMainBinding
import dev.jim.updater.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private class Row(
    val entry: AppEntry,
    val nameView: TextView,
    val versionsView: TextView,
    val progress: ProgressBar,
    val button: Button,
) {
    var pendingDownloadUrl: String? = null
    var pendingFileName: String? = null
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val inflater = LayoutInflater.from(this)
        for (entry in KNOWN_APPS) {
            val item = ItemAppBinding.inflate(inflater, binding.appListContainer, true)
            item.textAppName.text = entry.displayName
            item.textVersions.text = "Installed: ${installedVersionName(entry.packageName)}  •  Latest: —"
            item.buttonAction.text = "—"
            rows.add(Row(entry, item.textAppName, item.textVersions, item.progress, item.buttonAction))
        }

        binding.buttonCheckAll.setOnClickListener { checkAll() }
    }

    private fun checkAll() {
        binding.buttonCheckAll.isEnabled = false
        rows.forEach { it.progress.visibility = View.VISIBLE }
        lifecycleScope.launch {
            try {
                val releases = withContext(Dispatchers.IO) { GithubApi.fetchReleases() }
                rows.forEach { updateRow(it, releases) }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Check failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.buttonCheckAll.isEnabled = true
                rows.forEach { it.progress.visibility = View.GONE }
            }
        }
    }

    private fun updateRow(
        row: Row,
        releases: List<ReleaseInfo>,
    ) {
        val tagRegex = Regex("^${Regex.escape(row.entry.tagPrefix)}-v(\\d+\\.\\d+\\.\\d+)\\+(\\d+)$")
        val release = releases.firstOrNull { tagRegex.matches(it.tagName) }
        val installedName = installedVersionName(row.entry.packageName)
        val installedCode = installedVersionCode(row.entry.packageName)

        if (release == null) {
            row.versionsView.text = "Installed: $installedName  •  No release found"
            row.button.isEnabled = false
            row.button.text = "—"
            return
        }

        val match = tagRegex.matchEntire(release.tagName)!!
        val (remoteVersion, remoteCodeStr) = match.destructured
        val baseRemoteCode = remoteCodeStr.toLong()
        // Flutter's --split-per-abi build offsets the installed versionCode per ABI
        // (+2000 arm64-v8a, +1000 armeabi-v7a) while the release tag carries the bare
        // base code, so the two must be reconciled before comparing.
        val effectiveRemoteCode = if (row.entry.hasAbiSplit) baseRemoteCode + abiVersionCodeOffset() else baseRemoteCode

        row.versionsView.text = "Installed: $installedName  •  Latest: $remoteVersion+$baseRemoteCode"

        if (effectiveRemoteCode <= installedCode) {
            row.button.isEnabled = false
            row.button.text = "Up to date"
            return
        }

        val assetName =
            if (row.entry.hasAbiSplit) {
                "${row.entry.tagPrefix}-${preferredAbi()}.apk"
            } else {
                "${row.entry.tagPrefix}.apk"
            }
        val asset = release.assets.firstOrNull { it.name == assetName }
        if (asset == null) {
            row.versionsView.text = "${row.versionsView.text}  (asset $assetName missing)"
            row.button.isEnabled = false
            row.button.text = "—"
            return
        }

        row.pendingDownloadUrl = asset.downloadUrl
        row.pendingFileName = assetName
        row.button.text = if (installedCode < 0) "Install" else "Update"
        row.button.isEnabled = true
        row.button.setOnClickListener { downloadAndInstall(row) }
    }

    private fun downloadAndInstall(row: Row) {
        val url = row.pendingDownloadUrl ?: return
        val fileName = row.pendingFileName ?: return
        row.button.isEnabled = false
        row.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadFile(url, fileName) }
                installApk(file)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                row.progress.visibility = View.GONE
                row.button.isEnabled = true
            }
        }
    }

    private fun downloadFile(
        url: String,
        fileName: String,
    ): File {
        val dir = File(getExternalFilesDir(null), "apks").apply { mkdirs() }
        val dest = File(dir, fileName)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.connect()
        connection.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        connection.disconnect()
        return dest
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installing unknown apps, then tap Update again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(intent)
    }

    private fun installedVersionName(packageName: String): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "not installed"
        }

    private fun installedVersionCode(packageName: String): Long =
        try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }

    private fun preferredAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toSet()
        return if ("arm64-v8a" in supported) "arm64-v8a" else "armeabi-v7a"
    }

    private fun abiVersionCodeOffset(): Long = if (preferredAbi() == "arm64-v8a") 2000L else 1000L
}
