package com.t2converter.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import com.arthenica.ffmpegkit.ReturnCode
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
            val start = startEp.toIntOrNull()?.coerceAtLeast(1) ?: 1
            tasks = uris.mapIndexed { idx, uri ->
                val ep = (start + idx).toString().padStart(2, '0')
                VideoTask(
                    uri = uri,
                    displayName = uri.lastPathSegment ?: "video_$ep",
                    outputName = "${seriesName.ifBlank { "梦想妄想世界" }}_${ep}.mp4"
                )
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
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = startEp,
                        onValueChange = { startEp = it },
                        label = { Text("起始集") },
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
                                tasks.forEachIndexed { idx, task ->
                                    tasks = tasks.toMutableList().also {
                                        it[idx] = task.copy(status = "转换中...")
                                    }
                                    val result = convertSingleUri(task.uri, task.outputName)
                                    tasks = tasks.toMutableList().also {
                                        it[idx] = task.copy(
                                            status = if (result) "已保存至 Movies/T2Output" else "转换失败",
                                            progress = if (result) 1f else 0f
                                        )
                                    }
                                }
                                isRunning = false
                            }
                        },
                        enabled = tasks.isNotEmpty() && !isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isRunning) "转换中..." else "开始批量转换") }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(tasks) { _, item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(item.outputName, style = MaterialTheme.typography.titleSmall)
                                Text(item.status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun convertSingleUri(uri: Uri, outputName: String): Boolean = withContext(Dispatchers.IO) {
        val rawTmp = File(cacheDir, "in_${System.currentTimeMillis()}.tmp")
        val cleanTmp = File(cacheDir, "clean_${System.currentTimeMillis()}.mp4")
        val outTmp = File(cacheDir, outputName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rawTmp).use { output -> input.copyTo(output) }
            } ?: return@withContext false

            // 自动剥离 B 站 .m4s 头部 000000000 混淆字节
            val head = ByteArray(64)
            val headLen = rawTmp.inputStream().use { it.read(head) }
            val ftypIdx = indexOfBytes(head, headLen, "ftyp".toByteArray())
            val skipBytes = if (ftypIdx > 4 && (0 until ftypIdx - 4).all { head[it] == 48.toByte() }) {
                (ftypIdx - 4).toLong()
            } else 0L

            rawTmp.inputStream().use { inp ->
                inp.skip(skipBytes)
                FileOutputStream(cleanTmp).use { out -> inp.copyTo(out) }
            }

            val cmd = listOf(
                "-y", "-i", cleanTmp.absolutePath,
                "-vf", "scale=320:240:force_original_aspect_ratio=decrease,pad=320:240:(ow-iw)/2:(oh-ih)/2:black",
                "-c:v", "mpeg4", "-profile:v", "0", "-bf", "0",
                "-r", "15", "-b:v", "200k", "-pix_fmt", "yuv420p",
                "-an", "-t", "1440", "-g", "15", "-movflags", "+faststart",
                outTmp.absolutePath
            ).joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode) || !outTmp.exists()) {
                return@withContext false
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/T2Output")
            }
            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            contentResolver.openOutputStream(outUri)?.use { outStream ->
                outTmp.inputStream().use { it.copyTo(outStream) }
            }
            true
        } catch (_: Exception) {
            false
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
