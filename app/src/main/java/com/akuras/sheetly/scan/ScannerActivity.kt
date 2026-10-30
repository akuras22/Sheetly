package com.akuras.sheetly.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.akuras.sheetly.R
import com.akuras.sheetly.ScanMode
import com.akuras.sheetly.labelRes
import com.akuras.sheetly.ui.theme.SheetlyTheme
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScannerActivity"
private const val REVIEW_PREVIEW_LONG_SIDE_PX = 1400
private const val EDITOR_LONG_SIDE_PX = 1600
private const val LIVE_MIN_CONFIDENCE = 0.2f
private const val CORNER_TOUCH_RADIUS_DP = 44
private const val LOUPE_SIZE_DP = 120
private const val LOUPE_ZOOM = 2.5f

private val Accent = Color(0xFF3E91FF)
private val Dim = Color(0x99000000)

internal class ScannerActivity : ComponentActivity() {
    private val viewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SheetlyTheme(darkTheme = true) {
                ScannerFlow(viewModel = viewModel, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ScannerFlow(viewModel: ScannerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        when {
            reviewing && viewModel.pages.isNotEmpty() -> ReviewScreen(
                viewModel = viewModel,
                onAddPage = { reviewing = false },
                onSaved = onClose
            )
            hasCamera -> CameraScreen(
                viewModel = viewModel,
                onClose = onClose,
                onReview = { reviewing = true }
            )
            else -> PermissionScreen(
                onGrant = { permission.launch(Manifest.permission.CAMERA) },
                onClose = onClose
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.camera_permission_text), color = Color.White.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.camera_permission_grant)) }
        TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun CameraScreen(
    viewModel: ScannerViewModel,
    onClose: () -> Unit,
    onReview: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var tracked by remember { mutableStateOf<TrackedQuad?>(null) }
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var flashVisible by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(fourByThree())
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val stabilizer = remember { CornerStabilizer() }
    val autoCaptureGate = remember { AutoCaptureGate() }
    val currentTracked by rememberUpdatedState(tracked)

    fun capture() {
        if (isCapturing) return
        isCapturing = true
        flashVisible = true
        val file = viewModel.newCaptureFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    viewModel.addPhoto(file, currentTracked?.quad)
                    isCapturing = false
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    isCapturing = false
                }
            }
        )
        scope.launch {
            delay(120)
            flashVisible = false
        }
    }

    val currentCapture by rememberUpdatedState(::capture)
    val currentAutoCapture by rememberUpdatedState(autoCapture)

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.importImages(uris)
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }


    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
            }
            Spacer(modifier = Modifier.weight(1f))
            CapturePill(
                selected = autoCapture,
                label = stringResource(if (autoCapture) R.string.scan_auto else R.string.scan_manual),
                onClick = { autoCapture = !autoCapture }
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = stringResource(R.string.scan_flash),
                    tint = Color.White
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            val previewView = this
                            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder()
                                    .setResolutionSelector(fourByThree())
                                    .build()
                                    .also { it.surfaceProvider = previewView.surfaceProvider }
                                val analysis = ImageAnalysis.Builder()
                                    .setResolutionSelector(fourByThree())
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                val converter = CameraFrameConverter()
                                val detector = DocumentDetector()
                                analysis.setAnalyzer(analysisExecutor) { image ->
                                    val frame = image.use { converter.convert(it) }
                                    val detection = detector.detect(frame)
                                        ?.takeIf { it.confidence >= LIVE_MIN_CONFIDENCE }
                                        ?.let { it.copy(quad = it.quad.scaled(1f / frame.width, 1f / frame.height)) }
                                    val update = stabilizer.update(detection)
                                    val takePhoto = autoCaptureGate.shouldCapture(update) && currentAutoCapture
                                    ContextCompat.getMainExecutor(viewContext).execute {
                                        tracked = update
                                        if (takePhoto) currentCapture()
                                    }
                                }
                                runCatching {
                                    provider.unbindAll()
                                    camera = provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis,
                                        imageCapture
                                    )
                                }.onFailure { Log.e(TAG, "Failed to start camera", it) }
                            }, ContextCompat.getMainExecutor(viewContext))
                        }
                    }
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    tracked?.let { drawLiveOutline(it) }
                }
                if (flashVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
                ScanHint(
                    tracked = tracked,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RoundToolButton(
                onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(R.string.scan_gallery),
                    tint = Color.White
                )
            }
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(if (isCapturing) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !isCapturing, onClick = ::capture)
            )
            PageStackButton(
                pages = viewModel.pages,
                isBusy = viewModel.isBusy,
                onClick = onReview
            )
        }
    }
}

private fun fourByThree(): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

@Composable
private fun ScanHint(tracked: TrackedQuad?, modifier: Modifier = Modifier) {
    val text = when {
        tracked == null -> R.string.scan_hint_searching
        tracked.isStable -> R.string.scan_hint_ready
        else -> R.string.scan_hint_hold_still
    }
    Text(
        text = stringResource(text),
        modifier = modifier
            .background(Dim, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun CapturePill(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        color = Color.White,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun RoundToolButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PageStackButton(pages: List<ScanPage>, isBusy: Boolean, onClick: () -> Unit) {
    val lastFile = pages.lastOrNull()?.file
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, lastFile) {
        value = lastFile?.let { file ->
            withContext(Dispatchers.IO) {
                runCatching { ScanImaging.decodeFile(file, 200).asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        val image = thumbnail
        if (image != null && pages.isNotEmpty()) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.scan_review),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick),
                contentScale = ContentScale.Crop
            )
            Text(
                text = "${pages.size}",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Accent, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        } else if (isBusy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}

private fun DrawScope.drawLiveOutline(tracked: TrackedQuad) {
    val path = quadPath(tracked.quad, size)
    val colour = if (tracked.isStable) Color(0xFF34C759) else Accent
    drawPath(path, colour.copy(alpha = 0.2f))
    drawPath(path, colour, style = Stroke(width = 3.dp.toPx()))
}

private fun quadPath(quad: DocumentQuad, size: Size): Path = Path().apply {
    quad.corners.forEachIndexed { index, corner ->
        val x = corner.x * size.width
        val y = corner.y * size.height
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun ReviewScreen(
    viewModel: ScannerViewModel,
    onAddPage: () -> Unit,
    onSaved: () -> Unit,
) {
    val pagerState = rememberPagerState { viewModel.pages.size }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentPage = viewModel.pages.getOrNull(pagerState.currentPage.coerceAtMost(viewModel.pages.lastIndex))

    val editing = viewModel.pages.firstOrNull { it.id == editingId }
    if (editing != null) {
        CornerEditor(
            page = editing,
            onDone = { quad ->
                viewModel.updateQuad(editing.id, quad)
                editingId = null
            },
            onCancel = { editingId = null }
        )
        return
    }

    BackHandler(onBack = onAddPage)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAddPage) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${viewModel.pages.size}",
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = { viewModel.save(onSaved) }, enabled = !viewModel.isBusy) {
                Text(stringResource(R.string.scan_save_pdf))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            pageSpacing = 12.dp,
            key = { viewModel.pages.getOrNull(it)?.id ?: it }
        ) { index ->
            viewModel.pages.getOrNull(index)?.let { page -> RenderedPage(page) }
        }

        if (currentPage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                ScanMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentPage.mode == mode,
                        onClick = { viewModel.setMode(currentPage.id, mode) },
                        label = { Text(stringResource(mode.labelRes()), maxLines = 1) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ReviewAction(Icons.Default.Crop, stringResource(R.string.scan_adjust)) { editingId = currentPage.id }
                ReviewAction(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.scan_rotate)) { viewModel.rotate(currentPage.id) }
                ReviewAction(Icons.Default.AddAPhoto, stringResource(R.string.scan_add_page), onAddPage)
                ReviewAction(Icons.Default.Delete, stringResource(R.string.delete)) { viewModel.delete(currentPage.id) }
            }
        }
    }

    if (viewModel.isBusy) {
        Box(modifier = Modifier.fillMaxSize().background(Dim), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun ReviewAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RenderedPage(page: ScanPage) {
    val bitmap by produceState<Bitmap?>(initialValue = null, page) {
        value = withContext(Dispatchers.Default) {
            runCatching { ScanImaging.render(page, REVIEW_PREVIEW_LONG_SIDE_PX) }
                .onFailure { Log.e(TAG, "Failed to render page", it) }
                .getOrNull()
        }
    }
    Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Drag the corners of the outline, a loupe shows the area under the finger. */
@Composable
private fun CornerEditor(
    page: ScanPage,
    onDone: (DocumentQuad) -> Unit,
    onCancel: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, page.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { ScanImaging.decodeFile(page.file, EDITOR_LONG_SIDE_PX).asImageBitmap() }.getOrNull()
        }
    }
    var quad by remember(page.id) { mutableStateOf(page.quad) }
    var activeCorner by remember { mutableStateOf<Int?>(null) }
    val touchRadius = with(LocalDensity.current) { CORNER_TOUCH_RADIUS_DP.dp.toPx() }

    BackHandler(onBack = onCancel)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
            }
            Text(
                stringResource(R.string.scan_adjust),
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = { onDone(quad) }) { Text(stringResource(R.string.done)) }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val aspect = image.width.toFloat() / image.height
                val fitsWidth = maxWidth / maxHeight < aspect
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .then(
                            if (fitsWidth) Modifier.fillMaxWidth().aspectRatio(aspect)
                            else Modifier.fillMaxHeight().aspectRatio(aspect)
                        )
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(image) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    activeCorner = quad.corners.indices.minByOrNull { index ->
                                        val corner = quad.corners[index]
                                        hypot(corner.x * size.width - start.x, corner.y * size.height - start.y)
                                    }?.takeIf { index ->
                                        val corner = quad.corners[index]
                                        hypot(corner.x * size.width - start.x, corner.y * size.height - start.y) < touchRadius
                                    }
                                },
                                onDragEnd = { activeCorner = null },
                                onDragCancel = { activeCorner = null },
                                onDrag = { change, amount ->
                                    val index = activeCorner ?: return@detectDragGestures
                                    change.consume()
                                    val corner = quad.corners[index]
                                    quad = quad.withCorner(
                                        index,
                                        corner.x + amount.x / size.width,
                                        corner.y + amount.y / size.height
                                    )
                                }
                            )
                        }
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawEditorOverlay(quad)
                    }
                    activeCorner?.let { index ->
                        Loupe(image, quad.corners[index], canvasSize)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawEditorOverlay(quad: DocumentQuad) {
    val path = quadPath(quad, size)
    clipPath(
        Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            addPath(path)
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
    ) {
        drawRect(Dim)
    }
    drawPath(path, Accent, style = Stroke(width = 2.dp.toPx()))
    quad.corners.forEach { corner ->
        val center = Offset(corner.x * size.width, corner.y * size.height)
        drawCircle(Color.White, radius = 10.dp.toPx(), center = center)
        drawCircle(Accent, radius = 10.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
    }
}

/** Sits in the corner away from the finger, so the point being placed stays visible. */
@Composable
private fun Loupe(image: ImageBitmap, corner: Point, canvasSize: IntSize) {
    val density = LocalDensity.current
    val sizePx = with(density) { LOUPE_SIZE_DP.dp.toPx() }
    val atRight = corner.x < 0.5f
    val xOffset = if (atRight) (canvasSize.width - sizePx).toInt() else 0
    Canvas(
        modifier = Modifier
            .offset { IntOffset(xOffset, 0) }
            .size(LOUPE_SIZE_DP.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
    ) {
        val imageX = corner.x * image.width
        val imageY = corner.y * image.height
        val sourceSize = (size.width / LOUPE_ZOOM * image.width / canvasSize.width.coerceAtLeast(1)).toInt()
        drawImage(
            image = image,
            srcOffset = IntOffset(
                (imageX - sourceSize / 2).toInt().coerceIn(0, (image.width - sourceSize).coerceAtLeast(0)),
                (imageY - sourceSize / 2).toInt().coerceIn(0, (image.height - sourceSize).coerceAtLeast(0))
            ),
            srcSize = IntSize(sourceSize, sourceSize),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
        drawLine(Accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawLine(Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
}
