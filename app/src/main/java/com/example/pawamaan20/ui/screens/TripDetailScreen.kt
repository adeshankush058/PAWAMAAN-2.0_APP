package com.example.pawamaan20.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pawamaan20.viewmodel.RoutePoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(csvPath: String, onBack: () -> Unit) {
    var routePoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cameraPositionState = rememberCameraPositionState()

    LaunchedEffect(csvPath) {
        isLoading = true
        try {
            val points = withContext(Dispatchers.IO) {
                parseTripCsv(csvPath)
            }
            if (points.isNotEmpty()) {
                routePoints = points
                Log.d("HISTORY", "Loaded ${points.size} route points")
                
                // PART 5: Fit entire route
                val boundsBuilder = LatLngBounds.Builder()
                points.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
                val bounds = boundsBuilder.build()
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            } else {
                errorMessage = "Unable to load trip."
            }
        } catch (e: Exception) {
            Log.e("HISTORY", "Failed to load trip", e)
            errorMessage = "Unable to load trip."
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip History Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color(0xFF0D47A1)
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFF0D47A1))
            } else if (errorMessage != null) {
                Text(errorMessage!!, modifier = Modifier.align(Alignment.Center), color = Color.Red)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Map View
                    GoogleMap(
                        modifier = Modifier.weight(1.3f),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(zoomControlsEnabled = false)
                    ) {
                        if (routePoints.isNotEmpty()) {
                            // Draw route
                            Polyline(
                                points = routePoints.map { LatLng(it.latitude, it.longitude) },
                                color = Color(0xFF0D47A1),
                                width = 10f
                            )

                            // AQI CHIPS every 500 meters
                            var lastMarkerPos: LatLng? = null
                            routePoints.forEach { point ->
                                val currentPos = LatLng(point.latitude, point.longitude)
                                if (lastMarkerPos == null || getDistance(lastMarkerPos!!, currentPos) >= 500.0) {
                                    Marker(
                                        state = MarkerState(position = currentPos),
                                        title = "AQI: ${point.aqi}",
                                        snippet = "Time: ${formatTime(point.timestamp)}",
                                        icon = createAqiChipIcon(point.aqi),
                                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                                    )
                                    lastMarkerPos = currentPos
                                }
                            }
                        }
                    }

                    // Trip Summary Card
                    Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TripSummaryCard(routePoints)
                    }
                }
            }
        }
    }
}

@Composable
fun TripSummaryCard(points: List<RoutePoint>) {
    if (points.isEmpty()) return

    val startTime = points.first().timestamp
    val endTime = points.last().timestamp
    val durationMillis = endTime - startTime
    val durationMins = (durationMillis / 60000).toInt()
    
    val sdfDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    var totalDistance = 0f
    points.windowed(2).forEach { pair ->
        totalDistance += getDistance(LatLng(pair[0].latitude, pair[0].longitude), LatLng(pair[1].latitude, pair[1].longitude))
    }
    val distanceKm = totalDistance / 1000f

    val avgAqi = points.map { it.aqi }.average().roundToInt()
    val peakAqi = points.maxOf { it.aqi }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Trip Summary", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
            Spacer(modifier = Modifier.height(16.dp))
            
            SummaryDetailRow("Date", sdfDate.format(Date(startTime)))
            SummaryDetailRow("Start Time", sdfTime.format(Date(startTime)))
            SummaryDetailRow("End Time", sdfTime.format(Date(endTime)))
            SummaryDetailRow("Duration", "$durationMins min")
            SummaryDetailRow("Distance", String.format("%.2f km", distanceKm))
            SummaryDetailRow("Average AQI", "$avgAqi")
            SummaryDetailRow("Peak AQI", "$peakAqi")
            SummaryDetailRow("Points", "${points.size}")
        }
    }
}

@Composable
fun SummaryDetailRow(label: String, value: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = Color.Gray)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        HorizontalDivider(color = Color(0xFFF5F5F5))
    }
}

private fun parseTripCsv(path: String): List<RoutePoint> {
    val file = File(path)
    if (!file.exists()) return emptyList()

    val points = mutableListOf<RoutePoint>()
    try {
        file.readLines().drop(1).forEach { line ->
            try {
                val parts = line.split(",")
                if (parts.size >= 5) {
                    points.add(
                        RoutePoint(
                            timestamp = parts[0].toLong(),
                            latitude = parts[1].toDouble(),
                            longitude = parts[2].toDouble(),
                            aqi = parts[3].toInt(),
                            speed = parts[4].toDouble()
                        )
                    )
                }
            } catch (e: Exception) { /* skip invalid */ }
        }
    } catch (e: Exception) {
        Log.e("HISTORY", "Error reading CSV", e)
    }
    return points
}

private fun getDistance(p1: LatLng, p2: LatLng): Float {
    val results = FloatArray(1)
    Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
    return results[0]
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun getAqiColor(aqi: Int): Color = when {
    aqi <= 50 -> Color(0xFF4CAF50)
    aqi <= 100 -> Color(0xFFFFEB3B)
    aqi <= 200 -> Color(0xFFFF9800)
    aqi <= 300 -> Color(0xFFF44336)
    else -> Color(0xFF9C27B0)
}

private fun createAqiChipIcon(aqi: Int): BitmapDescriptor {
    val textPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val text = "AQI: $aqi"
    val textBounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, textBounds)
    
    val padding = 12
    val width = textBounds.width() + padding * 2
    val height = textBounds.height() + padding * 2
    
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    val bgPaint = Paint().apply {
        color = getAqiColor(aqi).toArgb()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawRoundRect(rectF, 12f, 12f, bgPaint)
    canvas.drawText(text, width / 2f, height / 2f - textBounds.centerY(), textPaint)
    
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
