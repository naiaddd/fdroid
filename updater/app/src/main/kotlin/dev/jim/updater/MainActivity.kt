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
    val statusView: TextView,
    val downloadProgress: ProgressBar,
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

        binding.buttonCheckAll.setOnClickListener { checkAll() }
    }

    private fun checkAll() {
        binding.buttonCheckAll.isEnabled = false
        rows.forEach { it.progress.visibility = View.VISIBLE }
        lifecycleScope.launch {
            try {
                val (manifest, releases) =
                    withContext(Dispatchers.IO) {
                        val manifest = AppsManifest.fetch()
                        val releases = GithubApi.fetchReleases()
                        manifest to releases
                    }
                val resolvedApps = discoverApps(manifest, releases)
                rebuildRows(resolvedApps)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Check failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.buttonCheckAll.isEnabled = true
            }
        }
    }

    private fun rebuildRows(resolvedApps: List<ResolvedApp>) {
        rows.clear()
        binding.appListContainer.removeAllViews()
        binding.textEmptyState.visibility = if (resolvedApps.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (resolved in resolvedApps) {
            val item = ItemAppBinding.inflate(inflater, binding.appListContainer, true)
            item.textAppName.text = resolved.entry.displayName
            val row =
                Row(
                    resolved.entry,
                    item.textAppName,
                    item.textVersions,
                    item.progress,
                    item.buttonAction,
                    item.textDownloadStatus,
                    item.downloadProgress,
                )
            rows.add(row)
            updateRow(row, resolved)
        }
    }

    private fun updateRow(
        row: Row,
        resolved: ResolvedApp,
    ) {
        val installedName = installedVersionName(row.entry.packageName)
        val installedCode = installedVersionCode(row.entry.packageName)

        // Flutter's --split-per-abi build offsets the installed versionCode per ABI
        // (+2000 arm64-v8a, +1000 armeabi-v7a) while the release tag carries the bare
        // base code, so the two must be reconciled before comparing.
        val effectiveRemoteCode =
            if (row.entry.hasAbiSplit) resolved.baseRemoteCode + abiVersionCodeOffset() else resolved.baseRemoteCode

        row.versionsView.text = "Installed: $installedName  •  Latest: ${resolved.remoteVersion}+${resolved.baseRemoteCode}"

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
        val asset = resolved.release.assets.firstOrNull { it.name == assetName }
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
        row.statusView.visibility = View.VISIBLE
        row.downloadProgress.visibility = View.VISIBLE
        row.downloadProgress.isIndeterminate = true
        row.statusView.text = "Connecting…"
        lifecycleScope.launch {
            try {
                val file =
                    withContext(Dispatchers.IO) {
                        downloadFile(url, fileName) { bytesRead, totalBytes, bytesPerSecond ->
                            runOnUiThread { updateDownloadStatus(row, bytesRead, totalBytes, bytesPerSecond) }
                        }
                    }
                row.statusView.text = "Installing…"
                installApk(file)
            } catch (e: Exception) {
                row.statusView.text = "Download failed: ${e.message}"
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                row.downloadProgress.visibility = View.GONE
                row.button.isEnabled = true
            }
        }
    }

    private fun updateDownloadStatus(
        row: Row,
        bytesRead: Long,
        totalBytes: Long,
        bytesPerSecond: Double,
    ) {
        if (totalBytes > 0) {
            row.downloadProgress.isIndeterminate = false
            row.downloadProgress.progress = ((bytesRead * 100) / totalBytes).toInt()
        }
        val sizeText = if (totalBytes > 0) "${formatBytes(bytesRead)} / ${formatBytes(totalBytes)}" else formatBytes(bytesRead)
        row.statusView.text = "Downloading  $sizeText  •  ${formatSpeed(bytesPerSecond)}"
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun formatSpeed(bytesPerSecond: Double): String =
        when {
            bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000.0)
            bytesPerSecond >= 1_000 -> "%.1f KB/s".format(bytesPerSecond / 1_000.0)
            else -> "%.0f B/s".format(bytesPerSecond)
        }

    private fun downloadFile(
        url: String,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long, bytesPerSecond: Double) -> Unit,
    ): File {
        val dir = File(getExternalFilesDir(null), "apks").apply { mkdirs() }
        val dest = File(dir, fileName)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.connect()
        val totalBytes = connection.contentLengthLong
        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead = 0L
                var lastReportTime = System.currentTimeMillis()
                var lastReportBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReportTime
                    if (elapsed >= 250) {
                        val bytesPerSecond = (bytesRead - lastReportBytes) * 1000.0 / elapsed
                        onProgress(bytesRead, totalBytes, bytesPerSecond)
                        lastReportTime = now
                        lastReportBytes = bytesRead
                    }
                }
                onProgress(bytesRead, totalBytes, 0.0)
            }
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
