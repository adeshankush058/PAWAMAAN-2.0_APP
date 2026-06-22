package com.example.pawamaan20.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pawamaan20.viewmodel.DeviceViewModel
import com.example.pawamaan20.viewmodel.RoutePoint
import com.example.pawamaan20.viewmodel.TripManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// MapScreen – PAWAMAAN 2.0  (Final, works with fixed TripManager + DeviceViewModel)
//
// This file only READS state.  All fixes for reactivity live in:
//   • TripManager.kt        – isTripActive / currentTripPoints now Compose state
//   • DeviceViewModel.kt    – inner route segments now SnapshotStateList
//
// Data flow:
//   Firebase → DeviceViewModel.updateRoute()
//            → routeSegments (outer SnapshotStateList<SnapshotStateList<RoutePoint>>)
//            → TripManager.addRoutePoint() → currentTripPoints (SnapshotStateList)
//            → MapScreen recomposes automatically ✓
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(deviceViewModel: DeviceViewModel) {

    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // ── Read shared observable state from DeviceViewModel ─────────────────────
    val portableDeviceId = deviceViewModel.portableDeviceId
    val routeSegments    = deviceViewModel.routeSegments   // SnapshotStateList — Compose observes it
    val lastUpdateMap    = deviceViewModel.lastUpdateMap

    val lastUpdateTime: Long =
        if (portableDeviceId != null) lastUpdateMap[portableDeviceId] ?: 0L else 0L

    // ── Read TripManager state — now Compose state, so recomposition fires ────
    val isTripActive     = TripManager.isTripActive         // mutableStateOf → observed ✓
    val currentTripPoints = TripManager.currentTripPoints   // mutableStateListOf → observed ✓

    // ── Local UI state ────────────────────────────────────────────────────────
    var deviceStatus    by remember { mutableStateOf("CONNECTING") }
    var showStopDialog  by remember { mutableStateOf(false) }
    var selectedPoint   by remember { mutableStateOf<RoutePoint?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Camera ────────────────────────────────────────────────────────────────
    val iiserBhopal = LatLng(23.2863, 77.2759)          // safe fallback only
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(iiserBhopal, 15f)
    }

    // ── User (phone) location ─────────────────────────────────────────────────
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun fetchUserLocation(centreIfIdle: Boolean) {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val ll = LatLng(loc.latitude, loc.longitude)
                    userLocation = ll
                    Log.d("MAP", "User location fetched: Lat=${ll.latitude} Lon=${ll.longitude}")
                    if (centreIfIdle && !TripManager.isTripActive) {
                        scope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(ll, 16f))
                            Log.d("MAP", "Camera centred on user location")
                        }
                    }
                } else {
                    Log.d("MAP", "Fallback location used – lastLocation was null")
                    scope.launch { snackbarHost.showSnackbar("Unable to get current location.") }
                }
            }.addOnFailureListener {
                Log.d("MAP", "Fallback location used – request failed")
                scope.launch { snackbarHost.showSnackbar("Unable to get current location.") }
            }
        } catch (e: SecurityException) {
            Log.e("MAP", "SecurityException: ${e.message}")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) fetchUserLocation(centreIfIdle = true)
    }

    // On first composition: get location / request permission
    LaunchedEffect(Unit) {
        val fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchUserLocation(centreIfIdle = true)
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // ── Status: mirrors HomeScreen StatusText timing ──────────────────────────
    // Re-derives on every new Firebase push (lastUpdateTime changes)
    LaunchedEffect(lastUpdateTime) {
        deviceStatus = if (lastUpdateTime <= 0L) "CONNECTING"
        else {
            val diff = System.currentTimeMillis() - lastUpdateTime
            when {
                diff < 15_000L -> "LIVE"
                diff < 60_000L -> "DELAYED"
                else           -> "OFFLINE"
            }
        }
    }

    // ── Auto-follow camera on latest point during active trip ─────────────────
    // Key on currentTripPoints.size — this is now a SnapshotStateList so Compose
    // triggers this effect every time a point is added.
    LaunchedEffect(currentTripPoints.size) {
        if (isTripActive && currentTripPoints.isNotEmpty()) {
            val last = currentTripPoints.last()
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 16f)
            )
        }
    }

    // ── Root layout ───────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier            = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings          = MapUiSettings(zoomControlsEnabled = false),
            onMapClick          = { clickLatLng ->
                val nearest = findNearestPoint(clickLatLng, currentTripPoints.toList())
                if (nearest != null) {
                    selectedPoint   = nearest
                    showBottomSheet = true
                }
            }
        ) {
            // "You are here" marker when idle
            if (!isTripActive && userLocation != null) {
                Marker(
                    state   = MarkerState(position = userLocation!!),
                    title   = "You are here",
                    snippet = "Current Location"
                )
            }

            // ── Draw route polylines ──────────────────────────────────────────
            // routeSegments is a SnapshotStateList of SnapshotStateLists.
            // Reading .size here makes Compose subscribe to both outer and inner changes.
            val segmentCount = routeSegments.size
            if (segmentCount > 0) {
                for (i in 0 until segmentCount) {
                    val segment = routeSegments[i]
                    // Reading segment.size subscribes to inner-list changes too
                    if (segment.size >= 2) {
                        Polyline(
                            points = segment.map { LatLng(it.latitude, it.longitude) },
                            color  = Color(0xFF0D47A1),
                            width  = 10f
                        )
                    }
                }

                // ── AQI chips every 500 m ─────────────────────────────────────
                if (isTripActive) {
                    val points = currentTripPoints.toList()   // snapshot for stable iteration
                    var distAcc = 0f
                    points.forEachIndexed { idx, point ->
                        if (idx == 0) {
                            AqiChipMarker(point)
                        } else {
                            val prev = points[idx - 1]
                            distAcc += distanceBetween(
                                LatLng(prev.latitude, prev.longitude),
                                LatLng(point.latitude, point.longitude)
                            )
                            if (distAcc >= 500f) {
                                AqiChipMarker(point)
                                distAcc = 0f
                                Log.d("TRIP", "AQI chip added – AQI ${point.aqi}")
                            }
                        }
                    }
                }

                // Latest position marker
                val latestPoint = routeSegments.last().lastOrNull()
                if (latestPoint != null) {
                    Marker(
                        state   = MarkerState(position = LatLng(latestPoint.latitude, latestPoint.longitude)),
                        title   = "Portable Device",
                        snippet = "AQI: ${latestPoint.aqi}",
                        alpha   = 0.9f
                    )
                }
            }
        }

        // ── Top status card ───────────────────────────────────────────────────
        Card(
            modifier  = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            shape     = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors    = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f))
        ) {
            Column(
                modifier            = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusLabel = when {
                    portableDeviceId == null -> "No Portable Device Detected"
                    else -> when (deviceStatus) {
                        "LIVE"    -> "🟢  Live Tracking"
                        "DELAYED" -> "🟡  Delayed Data"
                        "OFFLINE" -> "🔴  Offline"
                        else      -> "⌛  Connecting…"
                    }
                }
                Text(
                    text       = statusLabel,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF0D47A1)
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text      = if (!isTripActive)
                        "Start moving with your portable device to begin AQI tracking."
                    else
                        "Trip active · ${currentTripPoints.size} points recorded",
                    fontSize  = 12.sp,
                    color     = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AqiLegendChip("Good",      Color(0xFF4CAF50))
                    AqiLegendChip("Moderate",  Color(0xFFFFEB3B))
                    AqiLegendChip("Unhealthy", Color(0xFFFF9800))
                    AqiLegendChip("Hazardous", Color(0xFFF44336))
                    AqiLegendChip("Severe",    Color(0xFF9C27B0))
                }
            }
        }

        // ── FABs ──────────────────────────────────────────────────────────────
        Column(
            modifier              = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 36.dp),
            verticalArrangement   = Arrangement.spacedBy(14.dp),
            horizontalAlignment   = Alignment.End
        ) {
            // Recenter
            FloatingActionButton(
                onClick = {
                    if (isTripActive && currentTripPoints.isNotEmpty()) {
                        val last = currentTripPoints.last()
                        scope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 16f)
                            )
                        }
                    } else {
                        userLocation?.let { loc ->
                            scope.launch {
                                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(loc, 16f))
                            }
                        } ?: fetchUserLocation(centreIfIdle = true)
                    }
                },
                containerColor = Color.White,
                contentColor   = Color(0xFF0D47A1),
                shape          = RoundedCornerShape(50)
            ) { Icon(Icons.Default.MyLocation, contentDescription = "Recenter") }

            // Stop trip (only visible during active trip)
            if (isTripActive) {
                FloatingActionButton(
                    onClick        = { showStopDialog = true },
                    containerColor = Color(0xFFD32F2F),
                    contentColor   = Color.White,
                    shape          = RoundedCornerShape(50)
                ) { Icon(Icons.Default.Stop, contentDescription = "Stop Trip") }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 110.dp)
        )
    }

    // ── Stop trip dialog ──────────────────────────────────────────────────────
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("End Current Trip?") },
            text  = { Text("Do you want to stop tracking and save this trip?") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopDialog = false
                        TripManager.stopTripManually(context)
                        Log.d("TRIP", "Ended – manual stop by user")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("Stop Trip") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── AQI detail bottom sheet ───────────────────────────────────────────────
    if (showBottomSheet && selectedPoint != null) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState       = sheetState
        ) {
            val p = selectedPoint!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
            ) {
                Text("AQI Details", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(aqiColor(p.aqi), RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(16.dp))
                DetailRow("AQI",   p.aqi.toString())
                DetailRow("Time",  formatTimestamp(p.timestamp))
                DetailRow("Speed", String.format(Locale.US, "%.1f km/h", p.speed))
                DetailRow("Lat",   String.format(Locale.US, "%.5f", p.latitude))
                DetailRow("Lon",   String.format(Locale.US, "%.5f", p.longitude))
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick  = { showBottomSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
                ) { Text("Close") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
@GoogleMapComposable
fun AqiChipMarker(point: RoutePoint) {
    Marker(
        state   = MarkerState(position = LatLng(point.latitude, point.longitude)),
        icon    = buildAqiChipBitmap(point.aqi),
        anchor  = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        onClick = { true }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun AqiLegendChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(3.dp))
        Text(text = label, fontSize = 9.sp, color = Color.DarkGray)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun findNearestPoint(click: LatLng, points: List<RoutePoint>, threshold: Float = 50f): RoutePoint? {
    var nearest: RoutePoint? = null
    var minDist = threshold
    for (p in points) {
        val d = distanceBetween(click, LatLng(p.latitude, p.longitude))
        if (d < minDist) { minDist = d; nearest = p }
    }
    return nearest
}

private fun distanceBetween(a: LatLng, b: LatLng): Float {
    val r = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
    return r[0]
}

private fun aqiColor(aqi: Int): Color = when {
    aqi <= 50  -> Color(0xFF4CAF50)
    aqi <= 100 -> Color(0xFFFFEB3B)
    aqi <= 200 -> Color(0xFFFF9800)
    aqi <= 300 -> Color(0xFFF44336)
    else       -> Color(0xFF9C27B0)
}

private fun buildAqiChipBitmap(aqi: Int): BitmapDescriptor {
    val text = "AQI: $aqi"
    val textPaint = Paint().apply {
        color          = android.graphics.Color.BLACK
        textSize       = 30f
        textAlign      = Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias    = true
    }
    val bounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)
    val pad    = 14
    val width  = bounds.width()  + pad * 2
    val height = bounds.height() + pad * 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 14f, 14f, Paint().apply {
        color       = aqiColor(aqi).toArgb()
        style       = Paint.Style.FILL
        isAntiAlias = true
    })
    canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), 13f, 13f, Paint().apply {
        color       = android.graphics.Color.argb(60, 0, 0, 0)
        style       = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    })
    canvas.drawText(text, width / 2f, height / 2f - bounds.centerY(), textPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ts))