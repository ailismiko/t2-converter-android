package com.t2converter.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class SubPosition(val label: String, val alignment: Int) {
    BOTTOM("底部", 2),
    MIDDLE("居中", 5),
    TOP("顶部", 8)
}

data class VideoTask(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val episodeNumber: Int,
    val outputName: String,
    val subtitleUri: Uri? = null,
    val subtitleName: String? = null,
    val status: String = "等待转换",
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val progress: Float = 0f,
    val outputSizeBytes: Long = 0L,
    val durationText: String = ""
)

private val T2Specs = listOf(
    "封装容器" to "MP4 (-f mp4)",
    "视频编码" to "MPEG-4 Simple Profile",
    "目标分辨率" to "320 × 240",
    "画面比例" to "保持原比例",
    "黑边填充" to "自动补齐黑边",
    "视频帧率" to "15 FPS",
    "视频码率" to "200 kbps",
    "像素格式" to "YUV420P",
    "音频处理" to "彻底删除 (-an)",
    "最长时长" to "24 分钟上限",
    "关键帧间隔" to "15 帧 (-g 15)",
    "MP4 优化" to "Fast Start 前置"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FFmpegKitConfig.setFontDirectoryList(
            this,
            listOf("/system/fonts", "/product/fonts", "/system/font"),
            emptyMap()
        )
        setContent {
            val scheme = lightColorScheme(
                primary = Color(0xFF1D4ED8),
                onPrimary = Color.White,
                background = Color(0xFFF4F5F7),
                surface = Color.White,
                surfaceVariant = Color(0xFFF1F5F9),
                onSurface = Color(0xFF0F172A),
                onSurfaceVariant = Color(0xFF475569)
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    T2ConverterScreen()
                }
            }
        }
    }

    private fun recomputePendingNames(
        list: List<VideoTask>,
        rawSeries: String,
        rawStart: String
    ): List<VideoTask> {
        val cleanSeries = rawSeries.trim().ifBlank { "梦想妄想世界" }
        val start = rawStart.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val padWidth = maxOf(2, (start + list.size - 1).toString().length)
        return list.mapIndexed { idx, task ->
            if (task.isCompleted) {
                task
            } else {
                val ep = start + idx
                val epStr = ep.toString().padStart(padWidth, '0')
                task.copy(
                    episodeNumber = ep,
                    outputName = "${cleanSeries}_${epStr}.mp4"
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun T2ConverterScreen() {
        val context = LocalContext.current
        var seriesName by remember { mutableStateOf("梦想妄想世界") }
        var startEp by remember { mutableStateOf("1") }
        var tasks by remember { mutableStateOf(listOf<VideoTask>()) }
        var isRunning by remember { mutableStateOf(false) }
        var showSpecs by remember { mutableStateOf(false) }

        var subEnabled by remember { mutableStateOf(false) }
        var subFontSize by remember { mutableStateOf(16f) }
        var subPosition by remember { mutableStateOf(SubPosition.BOTTOM) }
        var subMarginV by remember { mutableStateOf(12f) }

        val scope = rememberCoroutineScope()

        val videoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val named = uris.map { uri -> uri to resolveFileMeta(uri) }
                .sortedWith { a, b -> naturalCompare(a.second.first, b.second.first) }
            val start = startEp.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val padWidth = maxOf(2, (start + named.size - 1).toString().length)
            val cleanSeries = seriesName.trim().ifBlank { "梦想妄想世界" }
            tasks = named.mapIndexed { idx, (uri, meta) ->
                val ep = start + idx
                val epStr = ep.toString().padStart(padWidth, '0')
                VideoTask(
                    uri = uri,
                    displayName = meta.first,
                    sizeBytes = meta.second,
                    episodeNumber = ep,
                    outputName = "${cleanSeries}_${epStr}.mp4"
                )
            }
        }

        val subtitlePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty() || tasks.isEmpty()) return@rememberLauncherForActivityResult
            val subs = uris.map { uri -> uri to resolveFileMeta(uri).first }
                .sortedWith { a, b -> naturalCompare(a.second, b.second) }
            tasks = tasks.mapIndexed { idx, task ->
                val videoStem = task.displayName.substringBeforeLast('.').lowercase()
                val exact = subs.firstOrNull {
                    it.second.substringBeforeLast('.').lowercase() == videoStem
                }
                val fallback = if (subs.size == 1) subs.first() else subs.getOrNull(idx)
                val chosen = exact ?: fallback
                if (chosen != null) {
                    task.copy(subtitleUri = chosen.first, subtitleName = chosen.second)
                } else task
            }
            subEnabled = true
        }

        fun runTaskAt(idx: Int, onDone: () -> Unit) {
            scope.launch {
                val current = tasks.getOrNull(idx) ?: run {
                    onDone()
                    return@launch
                }
                tasks = tasks.toMutableList().also {
                    it[idx] = current.copy(
                        status = "正在转码 0%",
                        isFailed = false,
                        isCompleted = false,
                        progress = 0f
                    )
                }
                val result = convertSingleUri(
                    uri = current.uri,
                    outputName = current.outputName,
                    subtitleUri = if (subEnabled) current.subtitleUri else null,
                    subtitleName = current.subtitleName,
                    subFontSize = subFontSize.toInt(),
                    subAlignment = subPosition.alignment,
                    subMarginV = subMarginV.toInt()
                ) { pct ->
                    tasks = tasks.toMutableList().also { list ->
                        if (idx < list.size) {
                            list[idx] = list[idx].copy(
                                status = "正在转码 ${(pct * 100).toInt()}%",
                                progress = pct
                            )
                        }
                    }
                }
                tasks = tasks.toMutableList().also { list ->
                    if (idx < list.size) {
                        list[idx] = list[idx].copy(
                            status = if (result.ok) "已完成 · Movies/T2Output" else "失败: ${result.message}",
                            isCompleted = result.ok,
                            isFailed = !result.ok,
                            progress = if (result.ok) 1f else 0f,
                            outputSizeBytes = result.outputSizeBytes,
                            durationText = result.durationText
                        )
                    }
                }
                onDone()
            }
        }

        val completedCount = tasks.count { it.isCompleted }
        val failedCount = tasks.count { it.isFailed }
        val totalProgress = if (tasks.isEmpty()) 0f else tasks.map { it.progress }.average().toFloat()

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_cover),
                                contentDescription = "T2 视频转换器封面",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Column {
                                Text(
                                    "T2 视频转换器 2.0",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    "K-Touch T2 老年机专用 · 本地离线版",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Batch Naming & File Selection Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "剧集命名与文件导入",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = seriesName,
                                    onValueChange = { next ->
                                        seriesName = next
                                        if (!isRunning) {
                                            tasks = recomputePendingNames(tasks, next, startEp)
                                        }
                                    },
                                    label = { Text("剧集名称") },
                                    singleLine = true,
                                    enabled = !isRunning,
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = startEp,
                                    onValueChange = { next ->
                                        startEp = next
                                        if (!isRunning) {
                                            tasks = recomputePendingNames(tasks, seriesName, next)
                                        }
                                    },
                                    label = { Text("起始集") },
                                    singleLine = true,
                                    enabled = !isRunning,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val previewClean = seriesName.trim().ifBlank { "梦想妄想世界" }
                            val previewStart = startEp.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val previewCount = maxOf(1, tasks.size)
                            val padW = maxOf(2, (previewStart + previewCount - 1).toString().length)
                            val firstName = "${previewClean}_${previewStart.toString().padStart(padW, '0')}.mp4"
                            val lastName = "${previewClean}_${(previewStart + previewCount - 1).toString().padStart(padW, '0')}.mp4"
                            Text(
                                if (tasks.size <= 1) "输出命名预览: $firstName"
                                else "输出命名预览: $firstName 至 $lastName",
                                fontSize = 12.sp,
                                color = Color(0xFF1D4ED8),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFEFF6FF), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { videoPicker.launch(arrayOf("*/*")) },
                                    enabled = !isRunning,
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) { Text("多选 MP4 / M4S", maxLines = 1) }
                                Button(
                                    onClick = {
                                        tasks = recomputePendingNames(tasks, seriesName, startEp)
                                        isRunning = true
                                        scope.launch {
                                            for (idx in tasks.indices) {
                                                if (tasks[idx].isCompleted) continue
                                                val done = CompletableDeferred<Unit>()
                                                runTaskAt(idx) { done.complete(Unit) }
                                                done.await()
                                            }
                                            isRunning = false
                                        }
                                    },
                                    enabled = tasks.any { !it.isCompleted } && !isRunning,
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) { Text(if (isRunning) "转换中..." else "开始批量转换", maxLines = 1) }
                            }
                        }
                    }
                }

                // 2. ASS Subtitle Burn-in & Style Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "ASS / SRT 字幕烧录",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "硬编码进 320×240 画面，支持字号与位置调整",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = subEnabled,
                                    onCheckedChange = { subEnabled = it },
                                    enabled = !isRunning
                                )
                            }

                            OutlinedButton(
                                onClick = { subtitlePicker.launch(arrayOf("*/*")) },
                                enabled = !isRunning && tasks.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                            ) {
                                val matchedSubs = tasks.count { it.subtitleUri != null }
                                Text(
                                    when {
                                        tasks.isEmpty() -> "请先选择视频后再匹配字幕"
                                        matchedSubs > 0 -> "重新选择字幕 (已匹配 $matchedSubs/${tasks.size} 集)"
                                        else -> "选择字幕文件 (.ass / .ssa / .srt)"
                                    }
                                )
                            }

                            if (subEnabled) {
                                // 320x240 Preview Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(4f / 3f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black)
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .align(Alignment.Center)
                                            .background(Color(0xFF334155)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "16:9 画面区 (上下自动补黑边)",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }

                                    val boxAlign = when (subPosition) {
                                        SubPosition.TOP -> Alignment.TopCenter
                                        SubPosition.MIDDLE -> Alignment.Center
                                        SubPosition.BOTTOM -> Alignment.BottomCenter
                                    }
                                    val padMod = when (subPosition) {
                                        SubPosition.TOP -> Modifier.padding(top = (subMarginV * 0.7f).dp)
                                        SubPosition.BOTTOM -> Modifier.padding(bottom = (subMarginV * 0.7f).dp)
                                        SubPosition.MIDDLE -> Modifier
                                    }
                                    Text(
                                        text = "${seriesName.ifBlank { "梦想妄想世界" }} · 字幕效果预览",
                                        color = Color.White,
                                        fontSize = (subFontSize * 0.85f).sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .align(boxAlign)
                                            .then(padMod)
                                            .padding(horizontal = 8.dp)
                                            .background(Color(0x88000000), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("字幕字号", fontSize = 12.sp)
                                    Text("${subFontSize.toInt()} px", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = subFontSize,
                                    onValueChange = { subFontSize = it },
                                    valueRange = 10f..28f,
                                    enabled = !isRunning
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("垂直边距 (MarginV)", fontSize = 12.sp)
                                    Text("${subMarginV.toInt()} px", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = subMarginV,
                                    onValueChange = { subMarginV = it },
                                    valueRange = 4f..48f,
                                    enabled = !isRunning && subPosition != SubPosition.MIDDLE
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SubPosition.values().forEach { pos ->
                                        FilterChip(
                                            selected = subPosition == pos,
                                            onClick = { if (!isRunning) subPosition = pos },
                                            label = { Text(pos.label) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Expandable Locked 12-Item T2 Standard Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "K-Touch T2 固定转换标准 (12项)",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "320×240 · MPEG-4 SP · 15fps · 200kbps · 无音频 · 24分钟",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { showSpecs = !showSpecs }) {
                                    Text(if (showSpecs) "收起" else "展开详情")
                                }
                            }
                            if (showSpecs) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                T2Specs.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        pair.forEach { (k, v) ->
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(v, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Task Summary Bar
                if (tasks.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "转换队列 (${tasks.size} 集)",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            "完成 $completedCount · 失败 $failedCount · 总进度 ${(totalProgress * 100).toInt()}%",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!isRunning) {
                                        TextButton(onClick = { tasks = emptyList() }) {
                                            Text("清空列表", color = Color(0xFFDC2626), fontSize = 12.sp)
                                        }
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { totalProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 5. Per-Episode Task Items
                itemsIndexed(tasks) { idx, item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    item.outputName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "第 ${item.episodeNumber.toString().padStart(2, '0')} 集",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1D4ED8)
                                )
                            }

                            val origSizeText = if (item.sizeBytes > 0) Formatter.formatShortFileSize(context, item.sizeBytes) else "未知大小"
                            Text(
                                "原文件: ${item.displayName} · $origSizeText",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (subEnabled && item.subtitleName != null) {
                                Text(
                                    "已匹配字幕: ${item.subtitleName} (${subFontSize.toInt()}px · ${subPosition.label})",
                                    fontSize = 12.sp,
                                    color = Color(0xFF1D4ED8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            val statusColor = when {
                                item.isCompleted -> Color(0xFF047857)
                                item.isFailed -> Color(0xFFDC2626)
                                item.progress > 0f -> Color(0xFF1D4ED8)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val extraOut = if (item.isCompleted && item.outputSizeBytes > 0) {
                                val outStr = Formatter.formatShortFileSize(context, item.outputSizeBytes)
                                if (item.durationText.isNotBlank()) " · $outStr · ${item.durationText}" else " · $outStr"
                            } else ""
                            Text(
                                "${item.status}$extraOut",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColor
                            )

                            if (item.progress > 0f && item.progress < 1f) {
                                LinearProgressIndicator(
                                    progress = { item.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (item.isFailed && !isRunning) {
                                OutlinedButton(
                                    onClick = {
                                        tasks = recomputePendingNames(tasks, seriesName, startEp)
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

    private fun resolveFileMeta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "video"
        var size = 0L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0 && !c.isNull(nIdx)) name = c.getString(nIdx) ?: name
                if (sIdx >= 0 && !c.isNull(sIdx)) size = c.getLong(sIdx)
            }
        }
        return name to size
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

    data class ConvertResult(
        val ok: Boolean,
        val message: String,
        val outputSizeBytes: Long = 0L,
        val durationText: String = ""
    )

    private fun escapeFilterPath(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
            .replace("[", "\\[")
            .replace("]", "\\]")
    }

    private fun prepareSubtitleFilter(
        rawSubFile: File,
        ext: String,
        fontSize: Int,
        alignment: Int,
        marginV: Int,
        workAssFile: File
    ): String {
        val lowerExt = ext.lowercase()
        if (lowerExt == "srt" || lowerExt == "vtt") {
            val scale = 288.0 / 240.0
            val sz = maxOf(8, (fontSize * scale).toInt())
            val mv = maxOf(2, (marginV * scale).toInt())
            val esc = escapeFilterPath(rawSubFile.absolutePath)
            return "subtitles='$esc':force_style='FontSize=$sz,Alignment=$alignment,MarginV=$mv,Outline=1,Shadow=0'"
        }
        var content = rawSubFile.readText(Charsets.UTF_8)
        val playResMatch = Regex("^PlayResY:\\s*(\\d+)", RegexOption.MULTILINE).find(content)
        val playResY = playResMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(120) ?: 288
        val ratio = playResY / 240.0
        val targetFont = maxOf(8, (fontSize * ratio).toInt())
        val targetMargin = maxOf(2, (marginV * ratio).toInt())

        var inStyles = false
        var cols = emptyList<String>()
        val updated = content.lines().map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inStyles = trimmed.equals("[V4+ Styles]", true) || trimmed.equals("[V4 Styles]", true)
                line
            } else if (inStyles && trimmed.lowercase().startsWith("format:")) {
                cols = trimmed.substring(7).split(",").map { it.trim().lowercase() }
                line
            } else if (inStyles && trimmed.lowercase().startsWith("style:") && cols.isNotEmpty()) {
                val colon = line.indexOf(':')
                val prefix = line.substring(0, colon + 1)
                val vals = line.substring(colon + 1).split(",").toMutableList()
                if (vals.size >= cols.size) {
                    val fi = cols.indexOf("fontsize")
                    val ai = cols.indexOf("alignment")
                    val mi = cols.indexOf("marginv")
                    if (fi >= 0) vals[fi] = targetFont.toString()
                    if (ai >= 0) vals[ai] = alignment.toString()
                    if (mi >= 0) vals[mi] = targetMargin.toString()
                    "$prefix ${vals.joinToString(",") { it.trim() }}"
                } else line
            } else line
        }.joinToString("\n")
        workAssFile.writeText(updated, Charsets.UTF_8)
        return "ass='${escapeFilterPath(workAssFile.absolutePath)}'"
    }

    private suspend fun convertSingleUri(
        uri: Uri,
        outputName: String,
        subtitleUri: Uri?,
        subtitleName: String?,
        subFontSize: Int,
        subAlignment: Int,
        subMarginV: Int,
        onProgress: (Float) -> Unit
    ): ConvertResult = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val rawTmp = File(cacheDir, "in_$ts.tmp")
        val cleanTmp = File(cacheDir, "clean_$ts.mp4")
        val rawSubTmp = File(cacheDir, "sub_raw_$ts.${subtitleName?.substringAfterLast('.', "ass") ?: "ass"}")
        val workAssTmp = File(cacheDir, "sub_work_$ts.ass")
        val outTmp = File(cacheDir, outputName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rawTmp).use { output -> input.copyTo(output) }
            } ?: return@withContext ConvertResult(false, "无法读取源文件")

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
            val clippedSec = minOf(if (srcDurationSec > 0) srcDurationSec else 60.0, 1440.0)
            val targetDurationMs = clippedSec * 1000.0

            var vf = "scale=320:240:force_original_aspect_ratio=decrease,pad=320:240:(ow-iw)/2:(oh-ih)/2:black"
            if (subtitleUri != null) {
                contentResolver.openInputStream(subtitleUri)?.use { sIn ->
                    FileOutputStream(rawSubTmp).use { sOut -> sIn.copyTo(sOut) }
                }
                if (rawSubTmp.exists() && rawSubTmp.length() > 0) {
                    val subExt = subtitleName?.substringAfterLast('.', "ass") ?: "ass"
                    val subFilter = prepareSubtitleFilter(
                        rawSubTmp, subExt, subFontSize, subAlignment, subMarginV, workAssTmp
                    )
                    vf = "$vf,$subFilter"
                }
            }

            val cmdArgs = arrayOf(
                "-y", "-i", cleanTmp.absolutePath,
                "-vf", vf,
                "-c:v", "mpeg4", "-profile:v", "0", "-bf", "0",
                "-r", "15", "-b:v", "200k", "-pix_fmt", "yuv420p",
                "-an", "-t", "1440", "-g", "15", "-movflags", "+faststart",
                outTmp.absolutePath
            )

            val finished = CompletableDeferred<Boolean>()
            var failDetail = ""
            FFmpegKit.executeWithArgumentsAsync(
                cmdArgs,
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
                return@withContext ConvertResult(false, failDetail.ifBlank { "FFmpeg 转码失败" })
            }

            val outSize = outTmp.length()
            val outInfo = FFprobeKit.getMediaInformation(outTmp.absolutePath).mediaInformation
            val outDur = outInfo?.duration?.toDoubleOrNull()?.toInt() ?: clippedSec.toInt()
            val durStr = "%02d:%02d".format(outDur / 60, outDur % 60)

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/T2Output")
            }
            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext ConvertResult(false, "写入系统 Movies 目录失败")
            contentResolver.openOutputStream(outUri)?.use { outStream ->
                outTmp.inputStream().use { it.copyTo(outStream) }
            }
            ConvertResult(true, "OK", outSize, durStr)
        } catch (e: Throwable) {
            ConvertResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            rawTmp.delete()
            cleanTmp.delete()
            rawSubTmp.delete()
            workAssTmp.delete()
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
