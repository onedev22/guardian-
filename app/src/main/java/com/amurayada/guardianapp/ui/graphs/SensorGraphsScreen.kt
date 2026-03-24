package com.amurayada.guardianapp.ui.graphs

import androidx.compose.ui.tooling.preview.Preview
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Paint
import android.graphics.Typeface
import com.amurayada.guardianapp.bluetooth.BLEManager
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SensorGraphsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val bleManager = remember { BLEManager.getInstance(context) }
    
    val accelX = remember { mutableStateListOf<Float>() }
    val accelY = remember { mutableStateListOf<Float>() }
    val accelZ = remember { mutableStateListOf<Float>() }
    
    val gyroX = remember { mutableStateListOf<Float>() }
    val gyroY = remember { mutableStateListOf<Float>() }
    val gyroZ = remember { mutableStateListOf<Float>() }
    
    val heartRateData = remember { mutableStateListOf<Int>() }
    val currentHeartRate by bleManager.heartRate.collectAsState()
    
    var currentGForce by remember { mutableFloatStateOf(0f) }
    var maxGForce by remember { mutableFloatStateOf(0f) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var lastTimestamp by remember { mutableLongStateOf(0L) }
    
    val maxPoints = 200

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        
                        accelX.add(x)
                        accelY.add(y)
                        accelZ.add(z)
                        if (accelX.size > maxPoints) {
                            accelX.removeAt(0)
                            accelY.removeAt(0)
                            accelZ.removeAt(0)
                        }
                        
                        // Real-time G-Force Calculation
                        val magnitude = sqrt(x*x + y*y + z*z)
                        currentGForce = magnitude / 9.81f
                        if (currentGForce > maxGForce) maxGForce = currentGForce
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        
                        // Speed Estimation (Better with Linear Acceleration)
                        val magnitude = sqrt(x*x + y*y + z*z)
                        if (lastTimestamp != 0L) {
                            val dt = (event.timestamp - lastTimestamp) * 1.0e-9f
                            // Noise threshold: only integrate if movement is significant (> 0.2 m/s²)
                            if (magnitude > 0.2f) {
                                currentSpeed += magnitude * dt
                                // Speed decay/friction: very subtle when moving
                                currentSpeed *= 0.999f
                            } else {
                                // Faster decay when almost stationary to prevent drift
                                currentSpeed *= 0.95f
                                if (currentSpeed < 0.1f) currentSpeed = 0f
                            }
                        }
                        lastTimestamp = event.timestamp
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroX.add(event.values[0])
                        gyroY.add(event.values[1])
                        gyroZ.add(event.values[2])
                        if (gyroX.size > maxPoints) {
                            gyroX.removeAt(0)
                            gyroY.removeAt(0)
                            gyroZ.removeAt(0)
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        sensorManager.registerListener(listener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(listener, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(listener, sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_UI)
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    LaunchedEffect(currentHeartRate) {
        if (currentHeartRate > 0) {
            heartRateData.add(currentHeartRate)
            if (heartRateData.size > maxPoints) {
                heartRateData.removeAt(0)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gráficas", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Metrics Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem("FUERZA G", String.format("%.2f", currentGForce), "G")
                MetricItem("VELOCIDAD EST.", String.format("%.1f", currentSpeed * 3.6f), "km/h")
            }
            
            Text("ACELERÓMETRO (m/s²)", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            
            SingleAxisGraph("X", accelX, MaterialTheme.colorScheme.primary, -20f, 20f)
            SingleAxisGraph("Y", accelY, MaterialTheme.colorScheme.secondary, -20f, 20f)
            SingleAxisGraph("Z", accelZ, MaterialTheme.colorScheme.tertiary, -20f, 20f)
            
            Text("GIROSCOPIO (rad/s)", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            
            SingleAxisGraph("X", gyroX, MaterialTheme.colorScheme.primary, -15f, 15f)
            SingleAxisGraph("Y", gyroY, MaterialTheme.colorScheme.secondary, -15f, 15f)
            SingleAxisGraph("Z", gyroZ, MaterialTheme.colorScheme.tertiary, -15f, 15f)
            
            if (heartRateData.isNotEmpty()) {
                Text("PULSO CARDIACO (BPM)", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                ChartSection("GuardianBand", listOf(heartRateData.map { it.toFloat() }), listOf(MaterialTheme.colorScheme.error), listOf("BPM"), 0f, 200f)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
fun SingleAxisGraph(label: String, data: List<Float>, color: Color, minVal: Float, maxVal: Float) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        // Label overlay
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color.copy(alpha = 0.2f), shape = CircleShape)
                .align(Alignment.TopStart),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        
        // Value overlay
        Text(
            text = String.format("%.1f", data.lastOrNull() ?: 0f),
            color = onSurface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        
        Canvas(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            val width = size.width
            val height = size.height
            val range = maxVal - minVal
            
            // Horizontal Grid
            val horizontalLines = 4
            for (i in 0..horizontalLines) {
                val y = height * i / horizontalLines
                drawLine(gridColor.copy(alpha = 0.3f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(width, y))
            }
            
            if (data.size > 1) {
                val path = Path()
                val step = width / (200 - 1)
                
                data.forEachIndexed { i, value ->
                    val x = i * step
                    val normalizedValue = ((value - minVal) / range).coerceIn(0f, 1f)
                    val y = height - (normalizedValue * height)
                    
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                
                drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

@Composable
fun ChartSection(title: String, dataSets: List<List<Float>>, colors: List<Color>, labels: List<String>, minVal: Float, maxVal: Float) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            .padding(16.dp)
    ) {
        Column {
            Text(text = title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val range = maxVal - minVal
                    
                    val textPaint = Paint().apply {
                        color = android.graphics.Color.GRAY
                        alpha = 150
                        textSize = 10.dp.toPx()
                        textAlign = Paint.Align.RIGHT
                    }
                    
                    // Horizontal grid lines and labels
                    val horizontalLines = 4
                    for (i in 0..horizontalLines) {
                        val y = height * i / horizontalLines
                        drawLine(gridColor.copy(alpha = 0.3f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(width, y))
                        
                        val labelValue = maxVal - (i * range / horizontalLines)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format("%.1f", labelValue),
                            width - 2.dp.toPx(),
                            y - 2.dp.toPx(),
                            textPaint
                        )
                    }
                    
                    dataSets.forEachIndexed { index, data ->
                        if (data.size > 1) {
                            val path = Path()
                            val step = width / (200 - 1)
                            
                            data.forEachIndexed { i, value ->
                                val x = i * step
                                val normalizedValue = ((value - minVal) / range).coerceIn(0f, 1f)
                                val y = height - (normalizedValue * height)
                                
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            
                            val color = colors[index % colors.size]
                            drawPath(path = path, color = color, style = Stroke(width = 1.5.dp.toPx()))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                labels.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(colors[index % colors.size], shape = CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
