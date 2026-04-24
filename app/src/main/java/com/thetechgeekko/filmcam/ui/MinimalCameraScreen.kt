package com.thetechgeekko.filmcam.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thetechgeekko.filmcam.model.*

/**
 * Minimalist camera screen implementing mood.camera gesture controls
 * Zero overlays by default, edge swipes for parameter adjustment
 */
@Composable
fun MinimalCameraScreen(
    filmSettings: FilmSettings,
    onSettingsChange: (FilmSettings) -> Unit,
    onCapture: () -> Unit,
    onDRSToggle: () -> Unit,
    availableEmulations: List<FilmEmulation>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    
    var showGrid by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(false) }
    var showPresetEditor by remember { mutableStateOf(false) }
    var developingOverlayVisible by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(EmulationCategory.FILM) }
    
    // Capture trigger with developing animation
    fun triggerCapture() {
        onCapture()
        developingOverlayVisible = true
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Camera preview placeholder (actual preview handled by CameraX/Camera2 view)
        CameraPreviewPlaceholder(
            showGrid = showGrid,
            showLevel = showLevel,
            aspectRatio = filmSettings.aspectRatio,
            modifier = Modifier.fillMaxSize()
        )
        
        // Edge swipe gesture handlers
        EdgeGestureHandler(
            onTopSwipe = { delta ->
                // ISO adjustment (swipe up/down on top edge)
                val isoSteps = listOf(100, 200, 400, 800, 1600, 3200)
                val currentIndex = isoSteps.indexOf(filmSettings.isoSim)
                if (delta < -50 && currentIndex < isoSteps.size - 1) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSettingsChange(filmSettings.copy(isoSim = isoSteps[currentIndex + 1]))
                } else if (delta > 50 && currentIndex > 0) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSettingsChange(filmSettings.copy(isoSim = isoSteps[currentIndex - 1]))
                }
            },
            onRightSwipe = { delta ->
                // Exposure compensation (swipe up/down on right edge)
                val evStep = 0.33f
                val newEv = (filmSettings.exposureComp - delta / 100f).coerceIn(-3f, 3f)
                if (kotlin.math.abs(delta) > 30) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSettingsChange(filmSettings.copy(exposureComp = newEv))
                }
            },
            onLeftSwipe = { delta ->
                // Dynamic range (swipe up/down on left edge)
                val drValues = DynamicRange.values()
                val currentIndex = drValues.indexOf(filmSettings.dynamicRange)
                if (delta < -50 && currentIndex < drValues.size - 1) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSettingsChange(filmSettings.copy(dynamicRange = drValues[currentIndex + 1]))
                } else if (delta > 50 && currentIndex > 0) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSettingsChange(filmSettings.copy(dynamicRange = drValues[currentIndex - 1]))
                }
            },
            onBottomSwipe = { delta ->
                // Film stock scroller (swipe left/right on bottom edge)
                if (kotlin.math.abs(delta) > 50) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    // Handled by LazyRow below
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Bottom-left typography (ISO, EV, DR info)
        InfoOverlay(
            iso = filmSettings.isoSim,
            exposureComp = filmSettings.exposureComp,
            dynamicRange = filmSettings.dynamicRange,
            emulation = filmSettings.emulation.displayName,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        )
        
        // Bottom-right aspect ratio indicator
        AspectRatioIndicator(
            aspectRatio = filmSettings.aspectRatio,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
        
        // Bottom film stock scroller
        FilmStockScroller(
            emulations = availableEmulations,
            selectedCategory = selectedCategory,
            onCategoryChange = { selectedCategory = it },
            onEmulationSelected = { emulation ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSettingsChange(filmSettings.copy(emulation = emulation))
            },
            currentEmulation = filmSettings.emulation,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        
        // Center capture button
        CaptureButton(
            onClick = { triggerCapture() },
            onLongPress = {
                onDRSToggle()
                // Triple haptic = DRS ON, single long = OFF
                if (!filmSettings.hdrEnabled) {
                    repeat(3) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        Thread.sleep(100)
                    }
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            },
            hdrEnabled = filmSettings.hdrEnabled,
            modifier = Modifier.align(Alignment.Center)
        )
        
        // Developing overlay animation
        AnimatedVisibility(
            visible = developingOverlayVisible,
            enter = fadeIn(animationSpec = tween(1200)),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Developing...",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        
        // Hidden menu trigger (long-press empty area)
        HiddenMenuTrigger(
            onShowGrid = { showGrid = !showGrid },
            onShowLevel = { showLevel = !showLevel },
            onOpenPresetEditor = { showPresetEditor = true },
            currentSettings = filmSettings,
            modifier = Modifier.fillMaxSize()
        )
        
        // Advanced Preset Editor modal
        if (showPresetEditor) {
            AdvancedPresetEditorDialog(
                settings = filmSettings,
                onDismiss = { showPresetEditor = false },
                onSave = { updatedSettings ->
                    onSettingsChange(updatedSettings)
                    showPresetEditor = false
                }
            )
        }
    }
}

/**
 * Camera preview placeholder with optional grid/level overlays
 */
@Composable
private fun CameraPreviewPlaceholder(
    showGrid: Boolean,
    showLevel: Boolean,
    aspectRatio: AspectRatio,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Actual camera preview would be here (TextureView/SurfaceView)
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
        )
        
        // Rule-of-thirds grid overlay
        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = Offset(width / 3, 0f),
                    end = Offset(width / 3, height),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = Offset(2 * width / 3, 0f),
                    end = Offset(2 * width / 3, height),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = Offset(0f, height / 3),
                    end = Offset(width, height / 3),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = Offset(0f, 2 * height / 3),
                    end = Offset(width, 2 * height / 3),
                    strokeWidth = 2f
                )
            }
        }
        
        // Level indicator (gyroscopic horizon)
        if (showLevel) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = Color.Yellow.copy(alpha = 0.6f),
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 3f
                )
            }
        }
    }
}

/**
 * Info overlay showing current settings (bottom-left)
 */
@Composable
private fun InfoOverlay(
    iso: Int,
    exposureComp: Float,
    dynamicRange: DynamicRange,
    emulation: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "ISO $iso",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "${if (exposureComp >= 0) "+" else ""}${String.format("%.1f", exposureComp)}EV",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "DR: ${dynamicRange.name}",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = emulation,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Aspect ratio indicator (bottom-right)
 */
@Composable
private fun AspectRatioIndicator(
    aspectRatio: AspectRatio,
    modifier: Modifier = Modifier
) {
    Text(
        text = aspectRatio.displayName,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
    )
}

/**
 * Film stock scroller with category tabs
 */
@Composable
private fun FilmStockScroller(
    emulations: List<FilmEmulation>,
    selectedCategory: EmulationCategory,
    onCategoryChange: (EmulationCategory) -> Unit,
    onEmulationSelected: (FilmEmulation) -> Unit,
    currentEmulation: FilmEmulation,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(bottom = 80.dp)) {
        // Category tabs
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            EmulationCategory.values().forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategoryChange(category) },
                    label = { Text(category.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Emulation thumbnails
        val filteredEmulations = emulations.filter { it.category == selectedCategory }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filteredEmulations) { emulation ->
                EmulationThumbnail(
                    emulation = emulation,
                    isSelected = emulation.id == currentEmulation.id,
                    onClick = { onEmulationSelected(emulation) }
                )
            }
        }
    }
}

/**
 * Individual emulation thumbnail
 */
@Composable
private fun EmulationThumbnail(
    emulation: FilmEmulation,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .then(if (isSelected) Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape) else Modifier)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, _ ->
                    onClick()
                }
            }
    ) {
        // Placeholder for actual CLUT preview thumbnail
        Text(
            text = emulation.displayName.take(3).uppercase(),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Center capture button with long-press DRS toggle
 */
@Composable
private fun CaptureButton(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    hdrEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .background(Color.White.copy(alpha = 0.85f), CircleShape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onClick() },
                    onDragCancel = {},
                    onHorizontalDrag = { _, _ -> }
                )
            }
    ) {
        if (hdrEnabled) {
            Text(
                text = "HDR",
                color = Color.Black,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Long-press hidden menu trigger
 */
@Composable
private fun HiddenMenuTrigger(
    onShowGrid: () -> Unit,
    onShowLevel: () -> Unit,
    onOpenPresetEditor: () -> Unit,
    currentSettings: FilmSettings,
    modifier: Modifier = Modifier
) {
    var menuVisible by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { /* Ignore */ },
                    onDragCancel = { /* Ignore */ },
                    onHorizontalDrag = { _, _ -> }
                )
            }
    ) {
        if (menuVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(200.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Grid Overlay", modifier = Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { onShowGrid() },
                            onHorizontalDrag = { _, _ -> }
                        )
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Level Indicator", modifier = Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { onShowLevel() },
                            onHorizontalDrag = { _, _ -> }
                        )
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Advanced Editor", modifier = Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { onOpenPresetEditor() },
                            onHorizontalDrag = { _, _ -> }
                        )
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Save RAW: ${if (currentSettings.saveRaw) "ON" else "OFF"}")
                }
            }
        }
    }
}

/**
 * Advanced Preset Editor dialog matching mood.camera layout
 */
@Composable
private fun AdvancedPresetEditorDialog(
    settings: FilmSettings,
    onDismiss: () -> Unit,
    onSave: (FilmSettings) -> Unit
) {
    var editedSettings by remember { mutableStateOf(settings) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Advanced Preset Editor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Emulation section
                Text("Emulation", style = MaterialTheme.typography.titleMedium)
                SliderWithLabel(
                    label = "Strength",
                    value = editedSettings.emulationStrength,
                    onValueChange = { editedSettings = editedSettings.copy(emulationStrength = it) },
                    valueRange = 0f..1f
                )
                
                // Color Science section
                Text("Color Science", style = MaterialTheme.typography.titleMedium)
                SliderWithLabel(
                    label = "Saturation",
                    value = editedSettings.saturation,
                    onValueChange = { editedSettings = editedSettings.copy(saturation = it) },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Contrast",
                    value = editedSettings.contrast,
                    onValueChange = { editedSettings = editedSettings.copy(contrast = it) },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Temperature",
                    value = editedSettings.temperature,
                    onValueChange = { editedSettings = editedSettings.copy(temperature = it) },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Tint",
                    value = editedSettings.tint,
                    onValueChange = { editedSettings = editedSettings.copy(tint = it) },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Fade",
                    value = editedSettings.fade,
                    onValueChange = { editedSettings = editedSettings.copy(fade = it) },
                    valueRange = 0f..1f
                )
                SliderWithLabel(
                    label = "Mute",
                    value = editedSettings.mute,
                    onValueChange = { editedSettings = editedSettings.copy(mute = it) },
                    valueRange = 0f..1f
                )
                
                // Physical Textures section
                Text("Physical Textures", style = MaterialTheme.typography.titleMedium)
                SliderWithLabel(
                    label = "Grain Level",
                    value = editedSettings.grainLevel,
                    onValueChange = { editedSettings = editedSettings.copy(grainLevel = it) },
                    valueRange = 0f..1f
                )
                SliderWithLabel(
                    label = "Grain Size",
                    value = editedSettings.grainSize,
                    onValueChange = { editedSettings = editedSettings.copy(grainSize = it) },
                    valueRange = 0.5f..2f
                )
                SliderWithLabel(
                    label = "Halation",
                    value = editedSettings.halation,
                    onValueChange = { editedSettings = editedSettings.copy(halation = it) },
                    valueRange = 0f..1f
                )
                SliderWithLabel(
                    label = "Bloom",
                    value = editedSettings.bloom,
                    onValueChange = { editedSettings = editedSettings.copy(bloom = it) },
                    valueRange = 0f..1f
                )
                SliderWithLabel(
                    label = "Aberration",
                    value = editedSettings.aberration,
                    onValueChange = { editedSettings = editedSettings.copy(aberration = it) },
                    valueRange = 0f..1f
                )
                
                // Tonal Manipulation section
                Text("Tonal Manipulation", style = MaterialTheme.typography.titleMedium)
                SliderWithLabel(
                    label = "Highlights",
                    value = editedSettings.toneCurve.highlights,
                    onValueChange = { 
                        editedSettings = editedSettings.copy(
                            toneCurve = editedSettings.toneCurve.copy(highlights = it)
                        )
                    },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Midtones",
                    value = editedSettings.toneCurve.midtones,
                    onValueChange = {
                        editedSettings = editedSettings.copy(
                            toneCurve = editedSettings.toneCurve.copy(midtones = it)
                        )
                    },
                    valueRange = -1f..1f
                )
                SliderWithLabel(
                    label = "Shadows",
                    value = editedSettings.toneCurve.shadows,
                    onValueChange = {
                        editedSettings = editedSettings.copy(
                            toneCurve = editedSettings.toneCurve.copy(shadows = it)
                        )
                    },
                    valueRange = -1f..1f
                )
                
                // Dynamic Range toggle
                Text("Dynamic Range", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DynamicRange.values().forEach { dr ->
                        FilterChip(
                            selected = editedSettings.dynamicRange == dr,
                            onClick = { editedSettings = editedSettings.copy(dynamicRange = dr) },
                            label = { Text(dr.name) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Save button
                Button(
                    onClick = { onSave(editedSettings) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Preset")
                }
            }
        }
    }
}

/**
 * Reusable slider with label component
 */
@Composable
private fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column {
        Text(
            text = "$label: ${String.format("%.2f", value)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * Edge gesture handler for parameter adjustments
 */
@Composable
private fun EdgeGestureHandler(
    onTopSwipe: (Float) -> Unit,
    onRightSwipe: (Float) -> Unit,
    onLeftSwipe: (Float) -> Unit,
    onBottomSwipe: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val edgeThreshold = 50.dp
    
    Box(modifier = modifier) {
        // Top edge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .height(edgeThreshold)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> onTopSwipe(dragAmount) }
                    )
                }
        )
        
        // Right edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(edgeThreshold)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> onRightSwipe(dragAmount) }
                    )
                }
        )
        
        // Left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(edgeThreshold)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> onLeftSwipe(dragAmount) }
                    )
                }
        )
        
        // Bottom edge
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(edgeThreshold)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount -> onBottomSwipe(dragAmount) }
                    )
                }
        )
    }
}
