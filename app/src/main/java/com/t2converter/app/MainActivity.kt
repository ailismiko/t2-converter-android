package com.t2converter.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class VideoTask(
    val uri: Uri,
    val displayName: String,
    val outputName: String,
    val status: String = "等待转换",
    val isFailed: Boolean = false,
    val progress: Float = 0f
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                T2ConverterScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun T2ConverterScreen() {
        var seriesName by remember { mutableStateOf("梦想妄想世界") }
        var startEp by remember { mutableStateOf("1") }
        var tasks by remember { mutableStateOf(listOf<VideoTask>()) }
        var isRunning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val start = startEp.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val named = uris.map { uri -> uri to resolveDisplayName(uri) }
                .sortedWith { a, b -> naturalCompare(a.second, b.second) }
            val padWidth = maxOf(2, (start + named.size - 1).toString().length)
            tasks = named.mapIndexed { idx, (uri, fileName) ->
                val ep = (start + idx).toString().padStart(padWidth, '0')
                VideoTask(
                    uri = uri,
                    displayName = fileName,
                    outputName = "${seriesName.ifBlank { "梦想妄想世界" }}_${ep}.mp4"
                )
            }
        }

        fun runTaskAt(idx: Int, onDone: () -> Unit) {
            scope.launch {
                val current = tasks.getOrNull(idx) ?: run {
                    onDone()
                    return@launch
                }
                tasks = tasks.toMutableList().also {
                    it[idx] = current.copy(status = "转换中 0%", isFailed = false, progress = 0f)
                }
                val (ok, msg) = convertSingleUri(current.uri, current.outputName) { pct ->
                    tasks = tasks.toMutableList().also { list ->
                        if (idx < list.size) {
                            list[idx] = list[idx].copy(
                                status = "转换中 ${(pct * 100).toInt()}%",
                                progress = pct
                            )
                        }
                    }
                }
                tasks = tasks.toMutableList().also { list ->
                    if (idx < list.size) {
                        list[idx] = list[idx].copy(
                            status = if (ok) "已完成 · Movies/T2Output" else "失败: $msg",
                            isFailed = !ok,
                            progress = if (ok) 1f else 0f
                        )
                    }
                }
                onDone()
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("T2 视频转换器") }) }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "固定参数: MP4 · MPEG-4 Simple Profile · 320×240 自动黑边 · 15 FPS · 200 kbps · 无音轨 · 24分钟上限",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = seriesName,
                        onValueChange = { seriesName = it },
                        label = { Text("剧集名称") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = startEp,
                        onValueChange = { startEp = it },
                        label = { Text("起始集") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("多选 MP4 / M4S") }
                    Button(
                        onClick = {
                            isRunning = true
                            scope.launch {
                                for (idx in tasks.indices) {
                                    val done = CompletableDeferred<Unit>()
                                    runTaskAt(idx) { done.complete(Unit) }
                                    done.await()
                                }
                                isRunning = false
                            }
                        },
                        enabled = tasks.isNotEmpty() && !isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isRunning) "转换中..." else "开始批量转换") }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(tasks) { idx, item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(item.outputName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "原文件: ${item.displayName}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(item.status, style = MaterialTheme.typography.bodySmall)
                                if (item.progress > 0f && item.progress < 1f) {
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (item.isFailed && !isRunning) {
                                    OutlinedButton(
                                        onClick = {
                                            isRunning = true
                                            runTaskAt(idx) { isRunning = false }
                                        }
                                    ) { Text("单独重试") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "video"
            }
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("(\\d+|\\D+)")
        val pa = re.findAll(a).map { it.value }.toList()
        val pb = re.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val cmp = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y, ignoreCase = true)
            if (cmp != 0) return cmp
        }
        return pa.size.compareTo(pb.size)
    }

    private suspend fun convertSingleUri(
        uri: Uri,
        outputName: String,
        onProgress: (Float) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val rawTmp = File(cacheDir, "in_${System.currentTimeMillis()}.tmp")
        val cleanTmp = File(cacheDir, "clean_${System.currentTimeMillis()}.mp4")
        val outTmp = File(cacheDir, outputName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rawTmp).use { output -> input.copyTo(output) }
            } ?: return@withContext false to "无法读取文件"

            // 自动剥离 B 站 .m4s 头部 000000000 混淆字节
            val head = ByteArray(64)
            val headLen = rawTmp.inputStream().use { it.read(head) }
            val ftypIdx = indexOfBytes(head, headLen, "ftyp".toByteArray())
            val skipBytes = if (ftypIdx > 4 && (0 until ftypIdx - 4).all { head[it] == 48.toByte() }) {
                (ftypIdx - 4).toLong()
            } else 0L

            rawTmp.inputStream().use { inp ->
                var skipped = 0L
                while (skipped < skipBytes) {
                    val n = inp.skip(skipBytes - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                FileOutputStream(cleanTmp).use { out -> inp.copyTo(out) }
            }

            val mediaInfo = FFprobeKit.getMediaInformation(cleanTmp.absolutePath).mediaInformation
            val srcDurationSec = mediaInfo?.duration?.toDoubleOrNull() ?: 0.0
            val targetDurationMs = (minOf(if (srcDurationSec > 0) srcDurationSec else 60.0, 1440.0) * 1000.0)

            val cmd = listOf(
                "-y", "-i", cleanTmp.absolutePath,
                "-vf", "scale=320:240:force_original_aspect_ratio=decrease,pad=320:240:(ow-iw)/2:(oh-ih)/2:black",
                "-c:v", "mpeg4", "-profile:v", "0", "-bf", "0",
                "-r", "15", "-b:v", "200k", "-pix_fmt", "yuv420p",
                "-an", "-t", "1440", "-g", "15", "-movflags", "+faststart",
                outTmp.absolutePath
            ).joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

            val finished = CompletableDeferred<Boolean>()
            var failDetail = ""
            FFmpegKit.executeAsync(
                cmd,
                { session ->
                    val ok = ReturnCode.isSuccess(session.returnCode) && outTmp.exists()
                    if (!ok) {
                        failDetail = session.failStackTrace ?: session.allLogsAsString.takeLast(200)
                    }
                    finished.complete(ok)
                },
                { },
                { stats ->
                    if (targetDurationMs > 0) {
                        val pct = (stats.time.toDouble() / targetDurationMs).toFloat().coerceIn(0f, 0.99f)
                        onProgress(pct)
                    }
                }
            )

            val ok = finished.await()
            if (!ok) {
                return@withContext false to failDetail.ifBlank { "FFmpeg 转码失败" }
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/T2Output")
            }
            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false to "写入系统 Movies 目录失败"
            contentResolver.openOutputStream(outUri)?.use { outStream ->
                outTmp.inputStream().use { it.copyTo(outStream) }
            }
            true to "OK"
        } catch (e: Throwable) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            rawTmp.delete()
            cleanTmp.delete()
            outTmp.delete()
        }
    }

    private fun indexOfBytes(haystack: ByteArray, len: Int, needle: ByteArray): Int {
        outer@ for (i in 0..(len - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
