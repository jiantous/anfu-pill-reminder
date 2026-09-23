package com.jian.pillreminder.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.jian.pillreminder.domain.LeafletParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PillScan"

private enum class ScanStage { CAMERA, RECOGNIZING, RESULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLeafletScreen(
    onUseResult: (LeafletParser.Result) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(ScanStage.CAMERA) }
    var result by remember { mutableStateOf<LeafletParser.Result?>(null) }
    var rawText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // 拍照后的识别中：不离开相机画面（渐隐取景框 + 扫描光带），
    // 识别完才切 RESULT。相册选图那条路没有相机画面可停留，走全屏加载页。
    var photoRecognizing by remember { mutableStateOf(false) }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionAsked = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    /** 从相册选一张已有的照片来识别（有些人已经拍好了说明书）。 */
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        stage = ScanStage.RECOGNIZING
        errorMsg = null
        scope.launch {
            runCatching {
                val image = InputImage.fromFilePath(context, uri)
                recognizeText(image)
            }.onSuccess { lines ->
                rawText = lines.joinToString("\n")
                result = LeafletParser.parse(lines)
                stage = ScanStage.RESULT
            }.onFailure { e ->
                Log.e(TAG, "相册图片识别失败", e)
                errorMsg = "这张图片没能识别出文字，换一张更清晰的试试"
                stage = ScanStage.CAMERA
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍说明书识别") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !hasPermission -> PermissionNeeded(
                    asked = permissionAsked,
                    onRequest = { permLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    },
                    onPickImage = { pickLauncher.launch("image/*") }
                )

                stage == ScanStage.RECOGNIZING -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text("正在识别文字…", style = MaterialTheme.typography.bodyLarge)
                }

                stage == ScanStage.RESULT && result != null -> ResultPane(
                    result = result!!,
                    rawText = rawText,
                    onRetake = { stage = ScanStage.CAMERA; result = null },
                    onUse = { onUseResult(result!!) }
                )

                else -> CameraPane(
                    imageCapture = imageCapture,
                    recognizing = photoRecognizing,
                    errorMsg = errorMsg,
                    onPickImage = { pickLauncher.launch("image/*") },
                    onShutter = {
                        photoRecognizing = true
                        errorMsg = null
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                // proxy.image 是 CameraX 实验性 API，这里显式 OptIn
                                @androidx.camera.core.ExperimentalGetImage
                                override fun onCaptureSuccess(proxy: ImageProxy) {
                                    scope.launch {
                                        runCatching {
                                            val media = proxy.image
                                                ?: error("拍到的图像为空")
                                            val image = InputImage.fromMediaImage(
                                                media, proxy.imageInfo.rotationDegrees
                                            )
                                            recognizeText(image)
                                        }.also { proxy.close() }
                                            .onSuccess { lines ->
                                                rawText = lines.joinToString("\n")
                                                result = LeafletParser.parse(lines)
                                                photoRecognizing = false
                                                stage = ScanStage.RESULT
                                            }
                                            .onFailure { e ->
                                                Log.e(TAG, "识别失败", e)
                                                errorMsg = "没识别出文字，把说明书放平、光线亮一点再拍"
                                                photoRecognizing = false
                                            }
                                    }
                                }

                                override fun onError(exc: ImageCaptureException) {
                                    Log.e(TAG, "拍照失败", exc)
                                    errorMsg = "拍照失败：${exc.message ?: "未知错误"}"
                                    photoRecognizing = false
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

/** ML Kit 中文识别封装成挂起函数，返回按行拆好的文字。 */
private suspend fun recognizeText(image: InputImage): List<String> =
    suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        // 用完必须 close：中文识别模型占几十 MB 内存，每次拍照都 new 一个不关，
        // 反复扫描会持续累积甚至 OOM。成功、失败、协程取消三条路都要释放。
        cont.invokeOnCancellation { runCatching { recognizer.close() } }
        fun releaseAnd(action: () -> Unit) {
            runCatching { recognizer.close() }
            runCatching(action)
        }
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks
                    .flatMap { it.lines }
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    releaseAnd { cont.resumeWithException(IllegalStateException("画面中没有识别到文字")) }
                } else {
                    releaseAnd { cont.resume(lines) }
                }
            }
            .addOnFailureListener { e -> releaseAnd { cont.resumeWithException(e) } }
    }

@Composable
private fun CameraPane(
    imageCapture: ImageCapture,
    recognizing: Boolean,
    errorMsg: String?,
    onShutter: () -> Unit,
    onPickImage: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var bindError by remember { mutableStateOf<String?>(null) }

    // 取景框边框与内容的透明度：识别中整体渐隐，把注意力让给扫描光带
    val frameAlpha by animateFloatAsState(
        targetValue = if (recognizing) 0.25f else 1f,
        animationSpec = tween(350),
        label = "vfAlpha"
    )

    // 竖向分区：提示条 / 取景框（占满剩余）/ 操作区。
    // 早先版本把快门用 align(BottomCenter) 浮在整块画面上，而取景框靠
    // padding(vertical = 96.dp) 撑满，两者必然交叠——框像被按钮切了一刀。
    // 各占各的地盘之后就再无交集。
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    bindCamera(ctx, view, lifecycleOwner, imageCapture) { e ->
                        bindError = e
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(Modifier.fillMaxSize()) {
            // ---- 顶部提示 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Text(
                        if (recognizing) "正在识别，保持稳定" else "把「用法用量」对准框内，尽量放平、光线充足",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                (errorMsg ?: bindError)?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ---- 取景引导框：吃掉提示条与操作区之间的全部空间 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    .border(
                        2.dp,
                        Color.White.copy(alpha = 0.75f * frameAlpha),
                        MaterialTheme.shapes.large
                    )
            ) {
                if (recognizing) {
                    ScanBeam(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .alpha(frameAlpha)
                    )
                }
            }

            // ---- 操作区 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 26.dp),
                contentAlignment = Alignment.Center
            ) {
                // 快门放在 Box 里独立居中，不受左右元素宽度影响。
                // 早先靠"相册按钮 + 固定宽 Spacer"凑对称，两者宽度并不相等，快门其实是偏的。
                // 识别中快门缩小变淡且不可点：正在识别时再拍一张只会搅乱流程。
                ShutterButton(
                    onClick = onShutter,
                    dimmed = recognizing,
                    enabled = !recognizing
                )

                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 32.dp)
                ) {
                    GalleryButton(onClick = onPickImage)
                }
            }
        }
    }
}

/**
 * 识别中的扫描光带：一条主色亮线带渐隐拖尾，从上到下扫过取景框。
 * 位移在 drawWithContent 里按父容器实际高度换算（graphicsLayer 的
 * size 是本元素自己的，拿不到父高度）。
 */
@Composable
private fun ScanBeam(modifier: Modifier = Modifier) {
    // draw lambda 不是 Composable 上下文，颜色先取出来捕获
    val beamColor = MaterialTheme.colorScheme.primary
    val beam = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            beam.animateTo(1f, animationSpec = tween(1300, easing = LinearEasing))
            beam.snapTo(0f)
        }
    }
    Box(
        modifier
            .drawWithContent {
                // 亮线的扫动范围：从顶部到贴住框底
                val travel = size.height - 2.dp.toPx()
                val top = travel * beam.value
                // 背景整体铺一层极淡的渐变，让"扫过区域"有被光带照过的余韵
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            beamColor.copy(alpha = 0.30f),
                            beamColor.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                // 亮线本体
                drawRect(
                    color = beamColor.copy(alpha = 0.85f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, 2.dp.toPx())
                )
                // 光带上方的渐隐拖尾跟着一起走
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            beamColor.copy(alpha = 0.22f)
                        ),
                        startY = (top - 40.dp.toPx()).coerceAtLeast(0f),
                        endY = top
                    ),
                    topLeft = Offset(0f, (top - 40.dp.toPx()).coerceAtLeast(0f)),
                    size = Size(size.width, 40.dp.toPx())
                )
            }
    )
}

/** 快门：白色圆底 + 主色相机图标。识别中缩小变淡且不可点。 */
@Composable
private fun ShutterButton(onClick: () -> Unit, dimmed: Boolean = false, enabled: Boolean = true) {
    // 识别中缩到 88% 并压透明度——和 CameraPane 顶部的 recognizing 状态联动
    val sizeScale by animateFloatAsState(
        targetValue = if (dimmed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "shutterDim"
    )
    Surface(
        shape = CircleShape,
        color = Color.White,
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = sizeScale
                scaleY = sizeScale
                alpha = if (dimmed) 0.75f else 1f
            }
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = "拍照识别",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/** 从相册选图。先定形再套点击，圆的直径才受控。 */
@Composable
private fun GalleryButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = Modifier.size(48.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = "从相册选择",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun bindCamera(
    context: Context,
    view: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: ImageCapture,
    onError: (String) -> Unit
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        runCatching {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }.onFailure { e ->
            Log.e(TAG, "相机启动失败", e)
            onError("相机启动失败：${e.message ?: "未知原因"}")
        }
    }, ContextCompat.getMainExecutor(context))
}

@Composable
private fun ResultPane(
    result: LeafletParser.Result,
    rawText: String,
    onRetake: () -> Unit,
    onUse: () -> Unit
) {
    var showRaw by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (!result.hasAnything) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("没能提取出用药信息", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "识别到了文字，但里面没有「每次几片、每日几次」这类用法用量描述。" +
                            "试着把说明书的「用法用量」段落单独拍一张，或者直接手动填。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Text(
                "识别结果",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "请核对下面的信息，确认后会填进添加药品的表单，你还能再修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    FieldRow("药品名称", result.name, result.evidence["name"])
                    FieldRow(
                        "单次剂量",
                        result.dosage?.let {
                            com.jian.pillreminder.notify.Reminders.formatDosage(it) + (result.unit ?: "")
                        },
                        result.evidence["dosage"]
                    )
                    FieldRow(
                        "每日次数",
                        result.timesPerDay?.let { "$it 次" },
                        result.evidence["timesPerDay"]
                    )
                    FieldRow(
                        "服药时间",
                        result.suggestedTimes.takeIf { it.isNotEmpty() }
                            ?.joinToString("、") { it.format() },
                        if (result.suggestedTimes.isNotEmpty()) "按每日次数推荐，可自行调整" else null
                    )
                    FieldRow(
                        "用药频率",
                        result.schedule?.let { s ->
                            when (s) {
                                is com.jian.pillreminder.data.Schedule.Daily -> "每天"
                                is com.jian.pillreminder.data.Schedule.EveryNDays -> "每 ${s.intervalDays} 天一次"
                                is com.jian.pillreminder.data.Schedule.WeekDays -> "每周固定几天"
                                is com.jian.pillreminder.data.Schedule.CycleOnOff -> "吃 ${s.onDays} 天停 ${s.offDays} 天"
                            }
                        },
                        result.evidence["schedule"]
                    )
                    FieldRow("进餐关系", result.mealRelation?.label, result.evidence["meal"])
                }
            }

            // 形近字纠正记录：必须让用户看到"我把什么改成了什么"，不能静默改药名
            if (result.nameFixes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("已自动纠正易认错的字", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        result.nameFixes.forEach { fix ->
                            Text(
                                "「${fix.from}」→「${fix.to}」",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "如果纠正错了，填入表单后可以直接改回来。",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "机器识别可能出错，请务必以医生嘱咐和实际说明书为准。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "收起识别到的原文" else "查看识别到的原文")
        }
        if (showRaw) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    rawText.ifBlank { "（无）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重拍")
            }
            Button(
                onClick = onUse,
                shape = ButtonDefaults.shape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (result.hasAnything) "填入表单" else "手动填写")
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FieldRow(label: String, value: String?, evidence: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(84.dp)
            )
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已识别",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    "未识别，需你手填",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (value != null && evidence != null) {
            Text(
                evidence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 84.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun PermissionNeeded(
    asked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickImage: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.NoPhotography,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Text("需要相机权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "用来拍摄药品说明书。识别全程在手机本地完成，照片不会保存也不会上传。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = if (asked) onOpenSettings else onRequest) {
            Text(if (asked) "去设置里开启" else "允许使用相机")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPickImage) {
            Text("或者从相册选一张照片")
        }
    }
}
