package com.example.pawamaan20.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// TripManager – PAWAMAAN 2.0
//
// WHY THIS FILE EXISTS:
//   The previous TripManager used plain Kotlin `var` and `MutableList`.
//   Compose has NO subscription to those — MapScreen would never recompose
//   when isTripActive flipped or a new point was added.
//
//   Fix: every field MapScreen reads is now backed by Compose snapshot state:
//     • isTripActive       → mutableStateOf<Boolean>
//     • currentTripPoints  → mutableStateListOf<RoutePoint>
//
//   MapScreen reads these the same way it always did, but now recomposition
//   fires automatically whenever they change.
// ─────────────────────────────────────────────────────────────────────────────

object TripManager {

    // ── Compose-observable state ───────────────────────────────────────────────
    var isTripActive by mutableStateOf(false)
        private set

    val currentTripPoints = mutableStateListOf<RoutePoint>()

    // ── Refresh signal for HistoryScreen ──────────────────────────────────────
    //
    // Increments every time a trip is successfully saved to CSV. HistoryScreen
    // keys its LaunchedEffect on this value, so if the user is already sitting
    // on the History tab when a trip ends elsewhere, the list still refreshes
    // immediately instead of requiring a tab switch.
    var tripSavedSignal by mutableStateOf(0)
        private set

    // ── Internal trip metadata ─────────────────────────────────────────────────
    private var tripId: String = ""
    private var tripStartTime: Long = 0L

    // ── Trip-ended hook ───────────────────────────────────────────────────────
    //
    // TripManager is a singleton object with no reference to DeviceViewModel or
    // any Compose screen. To clear the map's route (routeSegments/markerPoints,
    // which live in DeviceViewModel) and let History refresh the instant a trip
    // finishes, DeviceViewModel registers a callback here once at startup.
    //
    // This keeps TripManager decoupled — it doesn't need to know DeviceViewModel
    // exists, it just calls whatever was registered.
    //
    private var onTripEndedListener: (() -> Unit)? = null

    /** Called once (e.g. from DeviceViewModel.init) to receive trip-end notifications. */
    fun setOnTripEndedListener(listener: () -> Unit) {
        onTripEndedListener = listener
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trip lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by DeviceViewModel when a LIVE portable device starts sending data. */
    fun startTrip() {
        if (isTripActive) return   // guard against duplicate starts

        tripStartTime = System.currentTimeMillis()
        tripId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(tripStartTime))

        currentTripPoints.clear()
        isTripActive = true

        Log.d("TRIP", "Started – id=$tripId")
    }

    /**
     * Append a new location+AQI measurement to the current trip.
     * Only called while [isTripActive] is true (enforced in DeviceViewModel).
     */
    fun addRoutePoint(lat: Double, lon: Double, aqi: Int, speed: Double) {
        if (!isTripActive) return

        val point = RoutePoint(
            timestamp = System.currentTimeMillis(),
            latitude  = lat,
            longitude = lon,
            aqi       = aqi,
            speed     = speed
        )
        currentTripPoints.add(point)
        Log.d("TRIP", "Point added – total=${currentTripPoints.size}  AQI=$aqi")
    }

    /**
     * End the trip for the given [reason] (e.g. "Data Gap", "Device Offline").
     * Does NOT save to CSV — caller must follow up with [saveTripToCsv] if desired.
     */
    fun endTrip(reason: String) {
        if (!isTripActive) return
        isTripActive = false
        Log.d("TRIP", "Ended – reason=$reason  points=${currentTripPoints.size}")
    }

    /** Convenience: end + save in one call (used by manual Stop button). */
    fun stopTripManually(context: Context) {
        endTrip("Manual stop")
        saveTripToCsv(context)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV persistence  →  trip_history/YYYY-MM-DD/trip_HH-mm-ss.csv
    // ─────────────────────────────────────────────────────────────────────────

    fun saveTripToCsv(context: Context) {
        val points = currentTripPoints.toList()   // snapshot before async work
        if (points.isEmpty()) {
            Log.d("TRIP", "Save skipped – no points recorded")
            return
        }

        try {
            val startDate = Date(tripStartTime)
            val datePart  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(startDate)
            val timePart  = SimpleDateFormat("HH-mm-ss",  Locale.getDefault()).format(startDate)

            val dir = File(context.filesDir, "trip_history/$datePart")
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "trip_$timePart.csv")
            FileWriter(file, false).use { writer ->
                writer.write("timestamp,latitude,longitude,aqi,speed\n")
                points.forEach { p ->
                    writer.write("${p.timestamp},${p.latitude},${p.longitude},${p.aqi},${p.speed}\n")
                }
            }

            Log.d("TRIP", "Saved ${points.size} points → ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e("TRIP", "CSV save failed: ${e.message}", e)
        }

        // Clear points AFTER saving so History screen can load them immediately
        currentTripPoints.clear()

        // Bump the refresh signal so HistoryScreen reloads even if it's
        // already on screen when this trip finishes.
        tripSavedSignal++

        // Notify listeners (DeviceViewModel) that the trip has fully ended and
        // been saved — this clears the map's polyline/markers so the screen
        // returns to its idle state instead of showing the finished route.
        onTripEndedListener?.invoke()
        Log.d("TRIP", "Trip-ended listener notified")
    }
}