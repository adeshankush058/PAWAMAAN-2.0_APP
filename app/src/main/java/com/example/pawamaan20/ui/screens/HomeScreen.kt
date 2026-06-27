package com.example.pawamaan20.ui.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pawamaan20.R
import com.example.pawamaan20.ml.AqiPredictionModel
import com.example.pawamaan20.ml.AqiPredictionResult
import com.example.pawamaan20.viewmodel.DeviceViewModel
import com.example.pawamaan20.viewmodel.DeviceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    deviceViewModel: DeviceViewModel,
    onLogout: () -> Unit,
    onNavigateToAddDevice: () -> Unit,
    onNavigateToTripDetail: (String) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE3F2FD), Color.White)
    )

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                shadowElevation = 8.dp
            ) {
                NavigationBar(
                    containerColor = Color.White,
                    contentColor = Color(0xFF0D47A1)
                ) {
                    NavigationBarItem(
                        selected = selectedIndex == 0,
                        onClick = { selectedIndex = 0 },
                        label = { Text("Dashboard") },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0D47A1),
                            selectedTextColor = Color(0xFF0D47A1),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFFE3F2FD)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedIndex == 1,
                        onClick = { selectedIndex = 1 },
                        label = { Text("History") },
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0D47A1),
                            selectedTextColor = Color(0xFF0D47A1),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFFE3F2FD)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedIndex == 2,
                        onClick = { selectedIndex = 2 },
                        label = { Text("Map") },
                        icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0D47A1),
                            selectedTextColor = Color(0xFF0D47A1),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFFE3F2FD)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedIndex == 3,
                        onClick = { selectedIndex = 3 },
                        label = { Text("Profile") },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0D47A1),
                            selectedTextColor = Color(0xFF0D47A1),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFFE3F2FD)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
                .padding(paddingValues)
        ) {
            when (selectedIndex) {
                0 -> DashboardContent(deviceViewModel, onNavigateToAddDevice)
                1 -> HistoryScreen(deviceViewModel, onNavigateToTripDetail)
                2 -> MapScreen(deviceViewModel)
                3 -> ProfileScreen(deviceViewModel = deviceViewModel, onLogout = onLogout)
            }
        }
    }
}

@Composable
fun DashboardContent(deviceViewModel: DeviceViewModel, onNavigateToAddDevice: () -> Unit) {
    val context          = LocalContext.current
    val mainDeviceId     = deviceViewModel.mainDeviceId
    val secondaryDevices = deviceViewModel.secondaryDevices
    val devicesData      = deviceViewModel.devicesData

    // ── Prediction model (closed when composable leaves composition) ──────────
    val predictionModel = remember(context) { AqiPredictionModel(context) }
    DisposableEffect(predictionModel) {
        onDispose { predictionModel.close() }
    }

    // ── Auto-prediction (runs immediately, then every 30 minutes) ─────────────
    var autoPrediction by remember { mutableStateOf<AqiPredictionResult?>(null) }
    var autoUpdatedAt  by remember { mutableStateOf("") }

    // ── Live / on-demand prediction ───────────────────────────────────────────
    var livePrediction by remember { mutableStateOf<AqiPredictionResult?>(null) }
    var isRunningLive  by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 30-minute loop: takes the MOST RECENT sensor snapshot at each tick
    LaunchedEffect(mainDeviceId) {
        while (true) {
            val data = devicesData[mainDeviceId ?: ""]
            if (data != null) {
                val result = withContext(Dispatchers.Default) {
                    runCatching { predictionModel.predict(data) }.getOrNull()
                }
                if (result != null) {
                    autoPrediction = result
                    autoUpdatedAt  = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(Date())
                }
            }
            delay(30 * 60 * 1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.logo_of_app),
                contentDescription = "Pawamaan Logo",
                modifier = Modifier.fillMaxWidth(0.7f).padding(top = 16.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (mainDeviceId == null) {
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(24.dp),
                colors    = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "No devices connected.", color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNavigateToAddDevice) { Text("Connect Device") }
                }
            }
        } else {
            IndoorDeviceSection(deviceViewModel.getMainDeviceName(), mainDeviceId, devicesData[mainDeviceId])

            Spacer(modifier = Modifier.height(20.dp))

            // Prediction card with 30-min auto refresh + manual predict-now button
            TomorrowPredictionCard(
                autoPrediction = autoPrediction,
                autoUpdatedAt  = autoUpdatedAt,
                livePrediction = livePrediction,
                isRunningLive  = isRunningLive,
                onRequestLive  = {
                    isRunningLive  = true
                    livePrediction = null
                    scope.launch {
                        // Fetch the most recent reading directly from Firebase
                        val freshData = withContext(Dispatchers.IO) {
                            deviceViewModel.fetchLatestDeviceData(mainDeviceId)
                        } ?: devicesData[mainDeviceId]

                        val result = if (freshData != null) {
                            withContext(Dispatchers.Default) {
                                runCatching { predictionModel.predict(freshData) }.getOrNull()
                            }
                        } else null

                        livePrediction = result
                        isRunningLive  = false
                    }
                }
            )

            if (secondaryDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text       = "Other Devices",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF0D47A1),
                    modifier   = Modifier.align(Alignment.Start)
                )
                secondaryDevices.forEach { device ->
                    Spacer(modifier = Modifier.height(16.dp))
                    PortableDeviceCard(device.name, devicesData[device.id])
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
fun TomorrowPredictionCard(
    autoPrediction : AqiPredictionResult?,
    autoUpdatedAt  : String,
    livePrediction : AqiPredictionResult?,
    isRunningLive  : Boolean,
    onRequestLive  : () -> Unit
) {
    // Live prediction takes priority when available
    val display = livePrediction ?: autoPrediction

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFE1F5FE))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            // Header row: title + Predict Now button
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text       = "Tomorrow's AQI Forecast",
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF01579B)
                )
                OutlinedButton(
                    onClick        = onRequestLive,
                    enabled        = !isRunningLive,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    border         = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0277BD))
                ) {
                    if (isRunningLive) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = Color(0xFF0277BD)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Predicting…", fontSize = 11.sp, color = Color(0xFF0277BD))
                    } else {
                        Text("Predict Now", fontSize = 11.sp, color = Color(0xFF0277BD))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (display == null) {
                // Still in the first 30-minute window before first auto-run
                Text(
                    text     = "Calculating… (auto-updates every 30 min)",
                    fontSize = 14.sp,
                    color    = Color(0xFF0277BD).copy(alpha = 0.7f)
                )
            } else {
                // AQI number + category
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text       = "AQI ${display.aqi}",
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color(0xFF0277BD)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text     = "– ${display.category}",
                        fontSize = 15.sp,
                        color    = Color(0xFF0277BD),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Accuracy chip + source label
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (display.usedFallback) Color(0xFFFFF3E0) else Color(0xFFE8F5E9)
                    ) {
                        Text(
                            text     = "Accuracy ${display.accuracyPct}%",
                            fontSize = 11.sp,
                            color    = if (display.usedFallback) Color(0xFFE65100) else Color(0xFF2E7D32),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    if (livePrediction != null) {
                        Text("Live result", fontSize = 11.sp, color = Color(0xFF4CAF50))
                    } else if (autoUpdatedAt.isNotBlank()) {
                        Text("Updated $autoUpdatedAt", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun IndoorDeviceSection(name: String, id: String, data: DeviceData?) {
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.Start) {
                Text(text = name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                Text(text = "Device ID: $id", fontSize = 14.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "AQI", fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f))
                Text(text = data?.aqi?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
                StatusText(data)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard(title = "PM2.5", value = "${data?.pm25 ?: "--"} µg/m³", modifier = Modifier.weight(1f))
            SensorCard(title = "CO₂", value = "${data?.co2?.toInt() ?: "--"} ppm", modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tempF = String.format(Locale.getDefault(), "%.1f", data?.temp ?: 0.0)
            SensorCard(title = "Temp", value = "${tempF}°C", modifier = Modifier.weight(1f))
            SensorCard(title = "Humidity", value = "${data?.humidity?.toInt() ?: "--"}%", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun PortableDeviceCard(name: String, data: DeviceData?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(text = name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "Secondary Device", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (data == null) {
                Text(text = "Waiting for data...", color = Color.White.copy(alpha = 0.6f))
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "AQI", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                        Text(text = data.aqi?.toString() ?: "--", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    StatusText(data, Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensorSmallInfo("PM2.5", data.pm25?.toString() ?: "--", Modifier.weight(1f))
                    SensorSmallInfo("PM10", data.pm10?.toString() ?: "--", Modifier.weight(1f))
                    SensorSmallInfo("Speed", "${data.speed_kmh ?: "--"} km/h", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun StatusText(
    data: DeviceData?,
    textColor: Color = Color.White
) {
    if (data == null) return

    val currentKey = "${data.aqi}_${data.pm25}_${data.pm10}_${data.co2}_${data.temp}_${data.humidity}_${data.lat}_${data.lon}_${data.speed_kmh}"

    var lastKey by rememberSaveable { mutableStateOf("") }
    var lastUpdateTime by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(currentKey) {
        if (currentKey != lastKey && !currentKey.contains("null")) {
            lastKey = currentKey
            lastUpdateTime = System.currentTimeMillis()
        }
    }

    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            currentTime = System.currentTimeMillis()
        }
    }

    val (statusColor, statusText) = if (lastUpdateTime == 0L) {
        Color.Gray to "Connecting..."
    } else {
        val diff = currentTime - lastUpdateTime
        when {
            diff < 45_000 -> Color(0xFF4CAF50) to "Live"
            diff < 120_000 -> Color(0xFFFFC107) to "Delayed"
            else -> Color(0xFFF44336) to "Offline"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = statusText, color = textColor, fontSize = 12.sp)
    }
}

@Composable
fun StatusTextFromRawData(
    rawData: Map<String, Any>?,
    textColor: Color = Color.Black
) {
    if (rawData == null) return

    val currentKey = rawData.filterKeys { it != "timestamp" }.toSortedMap().toString()

    var lastKey by rememberSaveable { mutableStateOf("") }
    var lastUpdateTime by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(currentKey) {
        if (currentKey != lastKey) {
            lastKey = currentKey
            lastUpdateTime = System.currentTimeMillis()
        }
    }

    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            currentTime = System.currentTimeMillis()
        }
    }

    val (statusColor, statusText) = if (lastUpdateTime == 0L) {
        Color.Gray to "Connecting..."
    } else {
        val diff = currentTime - lastUpdateTime
        when {
            diff < 45_000 -> Color(0xFF4CAF50) to "Live"
            diff < 120_000 -> Color(0xFFFFC107) to "Delayed"
            else -> Color(0xFFF44336) to "Offline"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = statusText, color = textColor, fontSize = 12.sp)
    }
}

@Composable
fun SensorSmallInfo(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun SensorCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}
