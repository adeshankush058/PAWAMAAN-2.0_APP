package com.example.pawamaan20.ui.screens

import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.example.pawamaan20.R
import com.example.pawamaan20.viewmodel.DeviceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class IndoorData(
    val timestamp: Long,
    val aqi: Int?,
    val temp: Double?,
    val humidity: Double?
)

@Composable
fun TrendsScreen(deviceViewModel: DeviceViewModel) {

    val context = LocalContext.current
    val mainDeviceId = deviceViewModel.mainDeviceId

    var selectedTab by remember { mutableIntStateOf(1) }

    var analyzedData by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var insightText by remember { mutableStateOf("Processing data...") }
    var peakTimeText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // ================= DATA PROCESSING =================
    LaunchedEffect(mainDeviceId, selectedTab) {
        if (mainDeviceId != null) {
            isLoading = true

            withContext(Dispatchers.IO) {

                val file = File(context.filesDir, "device_${mainDeviceId}.csv")

                if (file.exists()) {

                    val allData = mutableListOf<IndoorData>()

                    try {
                        file.bufferedReader().useLines { lines ->
                            lines.drop(1).forEach { line ->
                                val parts = line.split(",")

                                if (parts.size >= 9) {

                                    val rawTs = parts[0].toLongOrNull() ?: 0L
                                    val ts = if (rawTs < 1000000000000L) rawTs * 1000 else rawTs

                                    val aqi = parts[3].toIntOrNull()
                                    val temp = parts[7].toDoubleOrNull()
                                    val hum = parts[8].toDoubleOrNull()

                                    allData.add(IndoorData(ts, aqi, temp, hum))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TRENDS", "File error: ${e.message}")
                    }

                    Log.d("TRENDS", "Device: $mainDeviceId Rows: ${allData.size}")

                    if (allData.isNotEmpty()) {

                        val calendar = Calendar.getInstance()
                        val now = System.currentTimeMillis()

                        val processed = when (selectedTab) {

                            0 -> { // TODAY
                                val startOfDay = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis

                                allData.filter { it.timestamp >= startOfDay }
                                    .groupBy {
                                        calendar.timeInMillis = it.timestamp
                                        calendar.get(Calendar.HOUR_OF_DAY)
                                    }
                                    .mapValues {
                                        val valid = it.value.mapNotNull { d -> d.aqi }.filter { a -> a > 0 }
                                        if (valid.isNotEmpty()) valid.average().toInt() else 0
                                    }
                                    .toSortedMap()
                                    .mapKeys { "${it.key}h" }
                            }

                            1 -> { // 7 DAYS
                                val limit = now - (7L * 24 * 60 * 60 * 1000)

                                allData.filter { it.timestamp >= limit }
                                    .groupBy {
                                        calendar.timeInMillis = it.timestamp
                                        SimpleDateFormat("EEE", Locale.getDefault()).format(calendar.time)
                                    }
                                    .mapValues {
                                        val valid = it.value.mapNotNull { d -> d.aqi }.filter { a -> a > 0 }
                                        if (valid.isNotEmpty()) valid.average().toInt() else 0
                                    }
                            }

                            else -> { // 30 DAYS
                                val limit = now - (30L * 24 * 60 * 60 * 1000)

                                allData.filter { it.timestamp >= limit }
                                    .groupBy {
                                        calendar.timeInMillis = it.timestamp
                                        SimpleDateFormat("dd MMM", Locale.getDefault()).format(calendar.time)
                                    }
                                    .mapValues {
                                        val valid = it.value.mapNotNull { d -> d.aqi }.filter { a -> a > 0 }
                                        if (valid.isNotEmpty()) valid.average().toInt() else 0
                                    }
                            }
                        }

                        // INSIGHT
                        val todayAvg = allData.takeLast(20).mapNotNull { it.aqi }.filter { it > 0 }.average()
                        val yesterdayAvg = allData.dropLast(20).takeLast(20).mapNotNull { it.aqi }.filter { it > 0 }.average()

                        insightText =
                            if (!yesterdayAvg.isNaN() && yesterdayAvg > 0 && !todayAvg.isNaN() && todayAvg > 0) {
                                val change = ((todayAvg - yesterdayAvg) / yesterdayAvg) * 100
                                if (change > 0)
                                    "AQI increased by ${change.toInt()}%"
                                else
                                    "AQI improved by ${abs(change.toInt())}%"
                            } else "Stable air quality"

                        val peak = if (processed.isNotEmpty()) processed.maxByOrNull { it.value } else null
                        peakTimeText = peak?.let { "Peak AQI ${it.value} at ${it.key}" } ?: ""

                        withContext(Dispatchers.Main) {
                            analyzedData = processed
                            isLoading = false
                        }

                    } else {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            insightText = "No data available"
                            analyzedData = emptyMap()
                        }
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        insightText = "CSV file not found"
                        analyzedData = emptyMap()
                    }
                }
            }
        }
    }

    // ================= UI =================

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE3F2FD), Color.White)
                )
            )
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Image(
                painter = painterResource(R.drawable.logo_of_app),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.6f),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("Air Quality Trends", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
            Text("Daily analysis & predictions", color = Color.Gray)

            Spacer(modifier = Modifier.height(24.dp))

            TimeFilterTabs(selectedTab) { selectedTab = it }

            Spacer(modifier = Modifier.height(24.dp))

            AQIChartCard(analyzedData, isLoading)

            Spacer(modifier = Modifier.height(24.dp))

            // Prediction Trend Card
            PredictionTrendCard()

            Spacer(modifier = Modifier.height(16.dp))

            InsightCard(insightText, peakTimeText)

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun TimeFilterTabs(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf("Today", "7 Days", "30 Days")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Color(0xFF0D47A1) else Color.Transparent)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun AQIChartCard(data: Map<String, Int>, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().height(280.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "AQI Analysis Overview", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0D47A1))
                }
            } else if (data.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data available for this period", color = Color.LightGray, fontSize = 14.sp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val maxAqi = data.values.maxOrNull()?.coerceAtLeast(100) ?: 100
                    data.forEach { (label, value) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(if (data.size > 10) 4.dp else 12.dp)
                                    .fillMaxHeight((value.toFloat() / maxAqi).coerceIn(0.05f, 1f))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color(0xFF1E88E5), Color(0xFFBBDEFB))
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = label, fontSize = 8.sp, color = Color.Gray, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PredictionTrendCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Model Prediction", fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                Text("Tomorrow AQI: 78 – Moderate")
                Text("Confidence: 86%", fontSize = 12.sp, color = Color.Gray)
            }

            Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = Color.Red)
        }
    }
}

@Composable
fun InsightCard(text: String, peak: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Air Quality Insight", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text, fontSize = 14.sp, color = Color.DarkGray)
            if (peak.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(peak, fontSize = 13.sp, color = Color(0xFF0D47A1), fontWeight = FontWeight.Medium)
            }
        }
    }
}
