package com.example.pawamaan20.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pawamaan20.viewmodel.DeviceViewModel
import com.example.pawamaan20.viewmodel.TripManager
import com.example.pawamaan20.viewmodel.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(deviceViewModel: DeviceViewModel, onTripClick: (String) -> Unit) {
    val context = LocalContext.current
    var groupedTrips by remember { mutableStateOf<Map<String, List<TripSummary>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    // Reload whenever the screen is entered OR a trip is saved while this
    // screen is already on-screen (TripManager.tripSavedSignal increments
    // on every successful save — see TripManager.saveTripToCsv).
    val tripSavedSignal = TripManager.tripSavedSignal
    LaunchedEffect(tripSavedSignal) {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            loadTripsFromStorage(context)
        }
        groupedTrips = loaded
        isLoading = false
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE3F2FD), Color.White)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "Trip History",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D47A1)
            )
            Text(
                text = "View your previous air quality tracking sessions",
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0D47A1))
                }
            } else if (groupedTrips.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No trip history available",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.DarkGray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Use your portable device to start tracking.",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    groupedTrips.forEach { (date, trips) ->
                        // Date Header (e.g., 12 Aug 2026)
                        item {
                            Text(
                                text = formatDateHeader(date),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0),
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(trips) { trip ->
                            TripCard(trip, onClick = {
                                Log.d("HISTORY", "Opening trip: ${trip.csvPath}")
                                onTripClick(trip.csvPath)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TripCard(trip: TripSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Activity Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsRun,
                    contentDescription = null,
                    tint = Color(0xFF0D47A1)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Trip Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${trip.startTime} - ${trip.endTime}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = "${trip.pointCount} points collected",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }

            // Info Icon
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "View Details",
                tint = Color(0xFFBBDEFB),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Scans the local trip_history directory and extracts summaries from CSV files.
 */
private fun loadTripsFromStorage(context: Context): Map<String, List<TripSummary>> {
    val baseDir = File(context.filesDir, "trip_history")
    if (!baseDir.exists()) return emptyMap()

    val summaries = mutableListOf<TripSummary>()
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 1. Scan date folders (YYYY-MM-DD)
    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dateFolder ->
        val folderDate = dateFolder.name

        // 2. Scan CSV files in each folder
        dateFolder.listFiles()?.filter { it.extension == "csv" }?.forEach { csvFile ->
            try {
                val lines = csvFile.readLines()
                if (lines.size > 1) { // Header + at least one data row
                    val dataRows = lines.drop(1)
                    val firstRow = dataRows.first().split(",")
                    val lastRow = dataRows.last().split(",")

                    // Extract mobile timestamps (index 0)
                    val startTs = firstRow[0].toLongOrNull() ?: 0L
                    val endTs = lastRow[0].toLongOrNull() ?: 0L

                    summaries.add(
                        TripSummary(
                            tripId = csvFile.name,
                            date = folderDate,
                            startTime = timeFormat.format(Date(startTs)),
                            endTime = timeFormat.format(Date(endTs)),
                            pointCount = dataRows.size,
                            csvPath = csvFile.absolutePath
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("HISTORY", "Failed to parse trip file: ${csvFile.name}", e)
            }
        }
    }

    Log.d("HISTORY", "Loaded ${summaries.size} trips")

    // 3. Sort by date descending and then by start time descending
    // 4. Group by date to create headers
    return summaries
        .sortedWith(compareByDescending<TripSummary> { it.date }.thenByDescending { it.startTime })
        .groupBy { it.date }
}

/**
 * Formats a YYYY-MM-DD string into a user-friendly header (e.g., 12 Aug 2026).
 */
private fun formatDateHeader(dateStr: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val date = inputFormat.parse(dateStr)
        outputFormat.format(date!!)
    } catch (e: Exception) {
        dateStr
    }
}