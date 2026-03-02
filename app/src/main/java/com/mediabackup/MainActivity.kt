package com.mediabackup

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelect: Button
    private lateinit var btnStart: Button
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var txtDestination: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtProgress: TextView
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var radioModeGroup: RadioGroup

    private lateinit var prefs: SharedPreferences
    private lateinit var powerManager: PowerManager
    private var destinationUri: Uri? = null
    private var cachedItems: List<MediaItem> = emptyList()
    private var backupJob: Job? = null
    private var totalBytes: Long = 0L
    private var activeMode: TransferMode = TransferMode.NORMAL
    private var wakeLock: PowerManager.WakeLock? = null

    private val destinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                appendLog(getString(R.string.log_persist_failed, e.message ?: ""))
            }
            destinationUri = uri
            prefs.edit().putString(KEY_DEST_URI, uri.toString()).apply()
            txtDestination.text = uri.toString()
            appendLog(getString(R.string.log_destination_set, uri.toString()))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            txtStatus.text = getString(R.string.status_permissions_denied)
            appendLog(getString(R.string.log_permissions_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelect = findViewById(R.id.btnSelect)
        btnStart = findViewById(R.id.btnStart)
        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)
        txtDestination = findViewById(R.id.txtDestination)
        txtStatus = findViewById(R.id.txtStatus)
        txtProgress = findViewById(R.id.txtProgress)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        progressBar = findViewById(R.id.progressBar)
        radioModeGroup = findViewById(R.id.radioModeGroup)

        powerManager = getSystemService(PowerManager::class.java)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        destinationUri = prefs.getString(KEY_DEST_URI, null)?.let { Uri.parse(it) }
        txtDestination.text = destinationUri?.toString() ?: getString(R.string.status_no_destination)
        txtProgress.text = "--"
        val savedMode = prefs.getString(KEY_TRANSFER_MODE, TransferMode.NORMAL.name) ?: TransferMode.NORMAL.name
        activeMode = if (savedMode == TransferMode.FAST.name) TransferMode.FAST else TransferMode.NORMAL
        radioModeGroup.check(if (activeMode == TransferMode.FAST) R.id.radioFast else R.id.radioNormal)
        radioModeGroup.setOnCheckedChangeListener { _, checkedId ->
            activeMode = if (checkedId == R.id.radioFast) TransferMode.FAST else TransferMode.NORMAL
            prefs.edit().putString(KEY_TRANSFER_MODE, activeMode.name).apply()
        }

        btnSelect.setOnClickListener { selectDestination() }
        btnScan.setOnClickListener { ensurePermissionsAndScan() }
        btnStart.setOnClickListener { startBackup() }
        btnStop.setOnClickListener { stopBackup() }
    }

    private fun selectDestination() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        destinationLauncher.launch(intent)
    }

    private fun ensurePermissionsAndScan() {
        val perms = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        permissionLauncher.launch(perms)
    }

    private fun startScan() {
        lifecycleScope.launch {
            txtStatus.text = getString(R.string.status_scanning)
            progressBar.progress = 0
            cachedItems = withContext(Dispatchers.IO) { loadMediaItems() }
            totalBytes = cachedItems.sumOf { max(0L, it.sizeBytes) }
            if (cachedItems.isEmpty()) {
                txtStatus.text = getString(R.string.status_no_media)
                appendLog(getString(R.string.log_no_media))
                return@launch
            }
            txtStatus.text = getString(R.string.status_scan_done, cachedItems.size)
            appendLog(getString(R.string.log_scan_done, cachedItems.size))
            txtProgress.text = getString(R.string.progress_scan_done, formatBytes(totalBytes))
        }
    }

    private fun startBackup() {
        if (backupJob?.isActive == true) {
            appendLog(getString(R.string.log_already_running))
            return
        }
        if (cachedItems.isEmpty()) {
            txtStatus.text = getString(R.string.status_scan_required)
            appendLog(getString(R.string.log_scan_required))
            return
        }
        val destUri = destinationUri
        if (destUri == null) {
            txtStatus.text = getString(R.string.status_select_destination_first)
            appendLog(getString(R.string.log_no_destination))
            return
        }

        backupJob = lifecycleScope.launch {
            val mode = activeMode
            enableModeRuntime(mode)
            appendLog(
                if (mode == TransferMode.FAST) getString(R.string.log_mode_fast)
                else getString(R.string.log_mode_normal)
            )

            try {
                val items = cachedItems

                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, destUri)
                if (rootDoc == null || !rootDoc.canWrite()) {
                    txtStatus.text = getString(R.string.status_destination_not_writable)
                    appendLog(getString(R.string.log_destination_not_writable))
                    return@launch
                }

                val backupRoot = rootDoc.findFile(BACKUP_DIR) ?: rootDoc.createDirectory(BACKUP_DIR)
                if (backupRoot == null) {
                    txtStatus.text = getString(R.string.status_create_backup_failed)
                    appendLog(getString(R.string.log_create_backup_failed))
                    return@launch
                }
                withContext(Dispatchers.IO) { ensureNoMediaMarker(backupRoot) }

                val imagesDir = backupRoot.findFile("Images") ?: backupRoot.createDirectory("Images")
                val videosDir = backupRoot.findFile("Videos") ?: backupRoot.createDirectory("Videos")
                if (imagesDir == null || videosDir == null) {
                    txtStatus.text = getString(R.string.status_create_subfolders_failed)
                    appendLog(getString(R.string.log_create_subfolders_failed))
                    return@launch
                }

                txtStatus.text = getString(R.string.status_copying, items.size)

                val startTime = System.currentTimeMillis()
                val stats = withContext(Dispatchers.IO) {
                    val copyStats = BackupStats()
                    ensureNoMediaMarker(imagesDir)
                    ensureNoMediaMarker(videosDir)
                    val imageIndex = buildExistingIndex(imagesDir)
                    val videoIndex = buildExistingIndex(videosDir)

                    val uiInterval = if (mode == TransferMode.FAST) FAST_UI_UPDATE_INTERVAL_MS else NORMAL_UI_UPDATE_INTERVAL_MS
                    var lastUiUpdateAt = 0L

                    for (index in items.indices) {
                        val item = items[index]
                        currentCoroutineContext().ensureActive()

                        val targetDir = if (item.isVideo) videosDir else imagesDir
                        val targetIndex = if (item.isVideo) videoIndex else imageIndex
                        val result = copyToDocumentFile(item, targetDir, targetIndex, mode) { delta ->
                            if (delta > 0L) {
                                copyStats.copiedBytes += delta
                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdateAt >= uiInterval) {
                                    lastUiUpdateAt = now
                                    val progress = if (totalBytes > 0L) {
                                        ((copyStats.copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    } else {
                                        ((index + 1) * 100) / items.size
                                    }
                                    val copiedNow = copyStats.copied
                                    val skippedNow = copyStats.skipped
                                    val copiedBytesNow = copyStats.copiedBytes
                                    runOnUiThread {
                                        progressBar.progress = progress
                                        updateProgressUI(
                                            copiedNow,
                                            skippedNow,
                                            items.size,
                                            copiedBytesNow,
                                            totalBytes,
                                            startTime
                                        )
                                    }
                                }
                            }
                        }
                        when (result) {
                            CopyResult.SUCCESS -> copyStats.copied++
                            CopyResult.SKIPPED -> {
                                copyStats.skipped++
                                copyStats.copiedBytes += max(0L, item.sizeBytes)
                            }
                            CopyResult.DESTINATION_INVALID -> {
                                copyStats.invalidStorage = true
                                break
                            }
                            CopyResult.FAILED -> Unit
                        }

                        val now = System.currentTimeMillis()
                        val shouldUpdate = now - lastUiUpdateAt >= uiInterval || index == items.lastIndex
                        if (shouldUpdate) {
                            lastUiUpdateAt = now
                            val copiedNow = copyStats.copied
                            val skippedNow = copyStats.skipped
                            val copiedBytesNow = copyStats.copiedBytes
                            val progress = if (totalBytes > 0L) {
                                ((copiedBytesNow * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                ((index + 1) * 100) / items.size
                            }
                            runOnUiThread {
                                progressBar.progress = progress
                                updateProgressUI(
                                    copiedNow,
                                    skippedNow,
                                    items.size,
                                    copiedBytesNow,
                                    totalBytes,
                                    startTime
                                )
                            }
                        }
                    }
                    copyStats
                }

                if (stats.invalidStorage) {
                    txtStatus.text = getString(R.string.status_destination_invalid)
                    appendLog(getString(R.string.log_destination_invalid))
                    return@launch
                }

                if (!this.isActive) {
                    txtStatus.text = getString(R.string.status_backup_stopped)
                    appendLog(getString(R.string.log_backup_stopped))
                    return@launch
                }

                appendLog(getString(R.string.log_done, stats.copied, stats.skipped, items.size))
                txtStatus.text = getString(R.string.status_backup_complete)
            } finally {
                disableModeRuntime()
                backupJob = null
            }
        }
    }

    override fun onDestroy() {
        disableModeRuntime()
        super.onDestroy()
    }

    private fun loadMediaItems(): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        items.addAll(queryMedia(false))
        items.addAll(queryMedia(true))
        return items
    }

    private fun queryMedia(isVideo: Boolean): List<MediaItem> {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE
        )

        val results = ArrayList<MediaItem>()
        contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: if (isVideo) "video_$id" else "image_$id"
                val mime = cursor.getString(mimeCol)
                val size = cursor.getLong(sizeCol)
                val uri = ContentUris.withAppendedId(collection, id)
                results.add(MediaItem(uri, name, mime, isVideo, size))
            }
        }
        return results
    }

    private fun buildExistingIndex(dir: DocumentFile): MutableMap<String, Long> {
        val index = LinkedHashMap<String, Long>()
        dir.listFiles().forEach { file ->
            val name = file.name
            if (!name.isNullOrEmpty()) {
                index[name] = file.length()
            }
        }
        return index
    }

    private fun ensureNoMediaMarker(dir: DocumentFile) {
        if (dir.findFile(".nomedia") == null) {
            dir.createFile("application/octet-stream", ".nomedia")
        }
    }

    private suspend fun copyToDocumentFile(
        item: MediaItem,
        targetDir: DocumentFile,
        targetIndex: MutableMap<String, Long>,
        mode: TransferMode,
        onBytesCopied: (Long) -> Unit
    ): CopyResult {
        val mime = item.mimeType ?: if (item.isVideo) "video/*" else "image/*"
        val existingSize = targetIndex[item.displayName]
        if (existingSize != null && item.sizeBytes > 0L && existingSize == item.sizeBytes) {
            return CopyResult.SKIPPED
        }

        val finalName = uniqueName(item.displayName, targetIndex)
        val useTempFile = mode != TransferMode.FAST
        val writeName = if (useTempFile) {
            ".mb_tmp_${System.currentTimeMillis()}_${item.displayName.hashCode()}"
        } else {
            finalName
        }
        val writeMime = if (useTempFile) "application/octet-stream" else mime
        val writeDoc = targetDir.createFile(writeMime, writeName)
            ?: return CopyResult.DESTINATION_INVALID

        return try {
            val sourcePfd = contentResolver.openFileDescriptor(item.uri, "r") ?: return CopyResult.FAILED
            val destPfd = contentResolver.openFileDescriptor(writeDoc.uri, "w")
                ?: return CopyResult.DESTINATION_INVALID
            sourcePfd.use { src ->
                destPfd.use { dst ->
                    copyFast(src, dst, mode, onBytesCopied)
                }
            }

            if (useTempFile && !writeDoc.renameTo(finalName)) {
                writeDoc.delete()
                return CopyResult.FAILED
            }

            targetIndex[finalName] = item.sizeBytes
            CopyResult.SUCCESS
        } catch (e: Exception) {
            writeDoc.delete()
            val msg = e.message ?: ""
            if (e is IOException || msg.contains("ENOTDIR") || msg.contains("EACCES")) {
                appendLog(getString(R.string.log_destination_invalid))
                CopyResult.DESTINATION_INVALID
            } else {
                appendLog(getString(R.string.log_copy_failed, item.displayName, msg))
                CopyResult.FAILED
            }
        }
    }

    private fun uniqueName(baseName: String, targetIndex: Map<String, Long>): String {
        var name = baseName
        var index = 1
        while (targetIndex.containsKey(name)) {
            val dot = baseName.lastIndexOf('.')
            name = if (dot > 0) {
                val prefix = baseName.substring(0, dot)
                val ext = baseName.substring(dot)
                "${prefix}_$index$ext"
            } else {
                "${baseName}_$index"
            }
            index++
        }
        return name
    }

    private suspend fun copyFast(
        sourcePfd: ParcelFileDescriptor,
        destPfd: ParcelFileDescriptor,
        mode: TransferMode,
        onBytesCopied: (Long) -> Unit
    ) {
        FileInputStream(sourcePfd.fileDescriptor).channel.use { inChannel ->
            FileOutputStream(destPfd.fileDescriptor).channel.use { outChannel ->
                val size = inChannel.size()
                var position = 0L
                val chunk = if (mode == TransferMode.FAST) 32L * 1024L * 1024L else 16L * 1024L * 1024L
                var chunks = 0
                while (position < size) {
                    currentCoroutineContext().ensureActive()
                    val transferred = inChannel.transferTo(position, chunk, outChannel)
                    if (transferred <= 0L) {
                        inChannel.position(position)
                        copyBuffered(inChannel, outChannel, mode, onBytesCopied)
                        break
                    }
                    position += transferred
                    onBytesCopied(transferred)
                    chunks++
                    if (chunks % 4 == 0) {
                        applyThermalBackoff(mode)
                    }
                }
            }
        }
    }

    private suspend fun copyBuffered(
        inChannel: java.nio.channels.FileChannel,
        outChannel: java.nio.channels.FileChannel,
        mode: TransferMode,
        onBytesCopied: (Long) -> Unit
    ) {
        val bufferSize = if (mode == TransferMode.FAST) 2 * 1024 * 1024 else 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val read = inChannel.read(buffer)
            if (read < 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                outChannel.write(buffer)
            }
            onBytesCopied(read.toLong())
            applyThermalBackoff(mode)
        }
    }

    private suspend fun applyThermalBackoff(mode: TransferMode) {
        if (mode == TransferMode.FAST) return
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_SEVERE -> delay(20)
            PowerManager.THERMAL_STATUS_CRITICAL -> delay(40)
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> delay(80)
            else -> Unit
        }
    }

    private fun enableModeRuntime(mode: TransferMode) {
        if (mode != TransferMode.FAST) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaBackup:FastTransfer").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun disableModeRuntime() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            txtLog.append(message + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updateProgressUI(
        copied: Int,
        skipped: Int,
        totalItems: Int,
        copiedBytes: Long,
        totalBytes: Long,
        startTime: Long
    ) {
        val elapsedMs = System.currentTimeMillis() - startTime
        val elapsedSec = max(1, elapsedMs / 1000)
        val speed = copiedBytes / elapsedSec.toDouble()
        val remainingBytes = max(0L, totalBytes - copiedBytes)
        val remainingSec = if (speed > 0) (remainingBytes / speed).toLong() else -1L
        val elapsedStr = formatDuration(elapsedSec)
        val remainingStr = if (remainingSec >= 0) formatDuration(remainingSec) else "--:--"
        val speedStr = "${formatBytes(speed.toLong())}/s"
        txtStatus.text = getString(R.string.status_copied_progress, copied, skipped, totalItems)
        txtProgress.text = getString(
            R.string.progress_transfer,
            elapsedStr,
            remainingStr,
            speedStr,
            formatBytes(copiedBytes),
            formatBytes(totalBytes)
        )
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun stopBackup() {
        val job = backupJob
        if (job == null || !job.isActive) {
            appendLog(getString(R.string.log_no_running_job))
            return
        }
        lifecycleScope.launch {
            job.cancelAndJoin()
            txtStatus.text = getString(R.string.status_backup_stopped)
            appendLog(getString(R.string.log_backup_stopped))
        }
    }

    data class MediaItem(
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        val isVideo: Boolean,
        val sizeBytes: Long
    )

    data class BackupStats(
        var copied: Int = 0,
        var skipped: Int = 0,
        var copiedBytes: Long = 0L,
        var invalidStorage: Boolean = false
    )

    private enum class CopyResult {
        SUCCESS,
        SKIPPED,
        FAILED,
        DESTINATION_INVALID
    }

    private enum class TransferMode {
        NORMAL,
        FAST
    }

    companion object {
        private const val PREFS = "mediabackup_prefs"
        private const val KEY_DEST_URI = "dest_uri"
        private const val KEY_TRANSFER_MODE = "transfer_mode"
        private const val BACKUP_DIR = "MediaBackup"
        private const val NORMAL_UI_UPDATE_INTERVAL_MS = 400L
        private const val FAST_UI_UPDATE_INTERVAL_MS = 1000L
    }
}
