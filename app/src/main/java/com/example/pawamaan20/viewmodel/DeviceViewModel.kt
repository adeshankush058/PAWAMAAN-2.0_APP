package com.example.pawamaan20.viewmodel

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────────────────────────────────────

data class DeviceItem(
    val id: String = "",
    val name: String = "",
    val type: String = "indoor"
)

data class DeviceData(
    val aqi: Int? = null,
    val pm1: Double? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val co2: Double? = null,
    val temp: Double? = null,
    val humidity: Double? = null,
    val pressure: Double? = null,
    val gas: Double? = null,
    val timestamp: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val speed_kmh: Double? = null,
    val no2: Double? = null          // NO₂ µg/m³ – optional, synthesised if absent
)

// ─────────────────────────────────────────────────────────────────────────────
// DeviceViewModel
// ─────────────────────────────────────────────────────────────────────────────

class DeviceViewModel : ViewModel() {

    private val auth  = FirebaseAuth.getInstance()
    private val dbUrl = "https://pawamaan-29da0-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private val rtdb  = FirebaseDatabase.getInstance(dbUrl)

    // ── Observable UI state ───────────────────────────────────────────────────
    var deviceList by mutableStateOf<List<String>>(emptyList())
        private set

    var mainDeviceId by mutableStateOf<String?>(null)
        private set

    var portableDeviceId by mutableStateOf<String?>(null)
        private set

    var secondaryDevices by mutableStateOf<List<DeviceItem>>(emptyList())
        private set

    var devicesData = mutableStateMapOf<String, DeviceData>()
        private set

    var rawDevicesSnapshot = mutableStateMapOf<String, Map<String, Any>>()
        private set

    var lastUpdateMap = mutableStateMapOf<String, Long>()
        private set

    var deviceNames = mutableStateMapOf<String, String>()
        private set

    // ── Route state shared with MapScreen ─────────────────────────────────────
    //
    // FIX: inner lists are now SnapshotStateList<RoutePoint> (was MutableList).
    // Compose only observes the OUTER SnapshotStateList. When we called
    // routeSegments.last().add(point) with a plain MutableList inside, Compose
    // never saw the change → MapScreen never recomposed → route never drew.
    // Now BOTH the outer list and every inner list are Compose snapshot state.
    //
    val routeSegments = mutableStateListOf<SnapshotStateList<RoutePoint>>()
    val markerPoints  = mutableStateListOf<RoutePoint>()
    private var lastMarkerLatLng: LatLng? = null

    private val lastSavedMap = mutableMapOf<String, Long>()
    private var appContext: Context? = null

    private var userDevicesListener: ValueEventListener? = null
    private val dataListeners = mutableMapOf<String, Pair<DatabaseReference, ValueEventListener>>()

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            clearState()
            if (user != null) fetchDevices(user.uid)
        }
        startOfflineCheck()

        // When a trip finishes (manual stop, data-gap, or device-offline
        // timeout) and is saved to CSV, clear the live route on MapScreen
        // so it doesn't keep showing the just-finished trip's polyline.
        TripManager.setOnTripEndedListener { clearRouteOnTripEnd() }
    }

    private fun startOfflineCheck() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(30_000)
                if (TripManager.isTripActive) {
                    val pid = portableDeviceId
                    if (pid != null) {
                        val lastUpdate = lastUpdateMap[pid] ?: 0L
                        if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate > 300_000) {
                            // 5 minutes of silence → auto-end trip
                            TripManager.endTrip("Device Offline")
                            appContext?.let { TripManager.saveTripToCsv(it) }
                        }
                    }
                }
            }
        }
    }

    fun initListeners(context: Context) {
        this.appContext = context
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State management
    // ─────────────────────────────────────────────────────────────────────────

    fun clearState() {
        userDevicesListener?.let { listener ->
            val uid = auth.currentUser?.uid
            if (uid != null) rtdb.getReference("users/$uid/devices").removeEventListener(listener)
        }
        userDevicesListener = null

        dataListeners.forEach { (_, pair) -> pair.first.removeEventListener(pair.second) }
        dataListeners.clear()

        deviceList       = emptyList()
        mainDeviceId     = null
        portableDeviceId = null
        secondaryDevices = emptyList()
        devicesData.clear()
        rawDevicesSnapshot.clear()
        lastUpdateMap.clear()
        deviceNames.clear()
        lastSavedMap.clear()
        routeSegments.clear()
        markerPoints.clear()
        lastMarkerLatLng = null
    }

    private fun resetBasicState() {
        mainDeviceId     = null
        portableDeviceId = null
        secondaryDevices = emptyList()
        devicesData.clear()
        routeSegments.clear()
        markerPoints.clear()
    }

    /**
     * Clears the live route shown on MapScreen (polyline + markers) without
     * touching device connections, names, or any other state.
     *
     * Called when a trip ends (manual stop, data-gap timeout, or device-offline
     * timeout) so the map goes back to its idle "you are here" state instead of
     * keeping the just-finished route drawn on screen.
     */
    fun clearRouteOnTripEnd() {
        routeSegments.clear()
        markerPoints.clear()
        lastMarkerLatLng = null
        Log.d("MAP", "Route cleared after trip end")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // History loader — restores today's route on app launch
    // FIX: inner segments created with mutableStateListOf (not mutableListOf)
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadTodayHistory(context: Context, deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val todayStr = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
            val file = File(context.filesDir, "device_${deviceId}_$todayStr.csv")
            if (!file.exists()) return@launch

            val loaded = mutableListOf<RoutePoint>()
            try {
                file.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.split(",")
                        if (parts.size >= 4) {
                            val ts  = parts[0].toLongOrNull() ?: 0L
                            val lat = parts[1].toDoubleOrNull()
                            val lon = parts[2].toDoubleOrNull()
                            val aqi = parts[3].toIntOrNull()
                            if (lat != null && lon != null && aqi != null) {
                                loaded.add(RoutePoint(ts, lat, lon, aqi, 0.0))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DEVICE", "History load failed: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                routeSegments.clear()
                markerPoints.clear()

                // FIX: each inner segment is a SnapshotStateList so Compose observes it
                var currentSegment = mutableStateListOf<RoutePoint>()
                var prevTime = 0L
                var lastM: LatLng? = null

                loaded.forEach { p ->
                    if (prevTime > 0 && p.timestamp - prevTime > 120_000L) {
                        if (currentSegment.isNotEmpty()) routeSegments.add(currentSegment)
                        currentSegment = mutableStateListOf<RoutePoint>()
                    }
                    currentSegment.add(p)
                    prevTime = p.timestamp

                    val pos = LatLng(p.latitude, p.longitude)
                    if (lastM == null || getDistance(lastM!!, pos) >= 50f) {
                        markerPoints.add(p)
                        lastM = pos
                    }
                }
                if (currentSegment.isNotEmpty()) routeSegments.add(currentSegment)
                lastMarkerLatLng = lastM

                Log.d("MAP", "Today's history loaded: ${loaded.size} points, ${routeSegments.size} segments")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase device fetching
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchDevices(uid: String) {
        val userEmail = auth.currentUser?.email
        if (userEmail == "adesh22@iiserb.ac.in") {
            val correctIndoor = "88_13_BF_0D_61_5C"
            rtdb.getReference("users/$uid/devices/$correctIndoor").setValue(true)
            rtdb.getReference("users/$uid/deviceNames/$correctIndoor").setValue("Main Indoor Device")
        }

        val userDevicesPath = rtdb.getReference("users/$uid/devices")
        userDevicesListener = userDevicesPath.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { it.key?.trim() }
                if (list.isEmpty()) {
                    resetBasicState()
                    return
                }
                deviceList = list

                rtdb.getReference("users/$uid/deviceNames")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(nameSnapshot: DataSnapshot) {
                            val tempSecondary = mutableListOf<DeviceItem>()
                            var tempMain: String?     = null
                            var tempPortable: String? = null
                            var resolvedCount = 0

                            list.forEach { id ->
                                val name = nameSnapshot.child(id).getValue(String::class.java) ?: "Device $id"
                                deviceNames[id] = name

                                // ── Classify device by its data shape, not by an
                                // ── info/type field that may not exist in the DB.
                                //
                                // Root cause of the original bug: devices/<id>/info/type
                                // was absent for both devices, so BOTH silently fell
                                // back to "portable", making indoor/portable assignment
                                // a race between two async Firebase calls — meaning
                                // the GPS-tracking device sometimes got wrongly
                                // classified as the indoor device, so updateRoute()
                                // was never called for it and the map never drew a route.
                                //
                                // Fix: read devices/<id>/latest once. If it has lat/lon
                                // fields, it's a portable (GPS) device; if not, it's
                                // an indoor (static) device. This matches real data
                                // instead of a metadata field that may be missing.
                                rtdb.getReference("devices/$id/latest")
                                    .addListenerForSingleValueEvent(object : ValueEventListener {
                                        override fun onDataChange(latestSnap: DataSnapshot) {
                                            val hasGps = latestSnap.child("lat").exists() &&
                                                    latestSnap.child("lon").exists()
                                            val type = if (hasGps) "portable" else "indoor"

                                            if (type == "indoor" && tempMain == null) {
                                                tempMain = id
                                            } else {
                                                tempSecondary.add(DeviceItem(id, name, type))
                                                if (type == "portable" && tempPortable == null) {
                                                    tempPortable = id
                                                }
                                            }

                                            resolvedCount++
                                            if (resolvedCount == list.size) {
                                                if (tempMain == null && list.isNotEmpty()) {
                                                    tempMain = list[0]
                                                    tempSecondary.removeAll { it.id == tempMain }
                                                }
                                                mainDeviceId     = tempMain
                                                portableDeviceId = tempPortable
                                                    ?: tempSecondary.firstOrNull { it.type == "portable" }?.id
                                                secondaryDevices = tempSecondary

                                                Log.d(
                                                    "MAP",
                                                    "Device classification resolved – main=$tempMain " +
                                                            "portable=$portableDeviceId"
                                                )

                                                appContext?.let { ctx ->
                                                    portableDeviceId?.let { pid ->
                                                        loadTodayHistory(ctx, pid)
                                                    }
                                                }
                                            }
                                        }
                                        override fun onCancelled(e: DatabaseError) {
                                            resolvedCount++
                                        }
                                    })
                                observeDeviceData(id)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase real-time listener per device
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeDeviceData(deviceId: String) {
        if (dataListeners.containsKey(deviceId)) return

        val dataRef = rtdb.getReference("devices/$deviceId/latest")
        Log.d("MAP", "Background listener active for $deviceId")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                @Suppress("UNCHECKED_CAST")
                val rawMap = snapshot.value as? Map<String, Any>
                if (rawMap != null) rawDevicesSnapshot[deviceId] = rawMap

                val data = snapshot.getValue(DeviceData::class.java) ?: return

                // Always use mobile time so status matches HomeScreen
                val mobileTime  = System.currentTimeMillis()
                lastUpdateMap[deviceId] = mobileTime

                val updatedData = data.copy(timestamp = mobileTime)
                devicesData[deviceId] = updatedData

                if (deviceId == portableDeviceId) {
                    updateRoute(updatedData)
                }

                appContext?.let { saveToCsv(deviceId, updatedData, rawMap, it) }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("MAP", "Firebase listener cancelled for $deviceId: ${error.message}")
            }
        }

        dataRef.addValueEventListener(listener)
        dataListeners[deviceId] = Pair(dataRef, listener)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route update — called on every Firebase push for the portable device
    //
    // FIX 1: zero-coordinate guard added (lat==0 && lon==0 → skip)
    // FIX 2: new segments are mutableStateListOf (Compose-observable inner list)
    // FIX 3: after adding to the inner list, reassign the outer slot so the
    //         outer SnapshotStateList emits a change notification to Compose
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateRoute(data: DeviceData) {
        val lat       = data.lat       ?: return
        val lon       = data.lon       ?: return
        val aqi       = data.aqi       ?: 0
        val speed     = data.speed_kmh ?: 0.0
        val timestamp = data.timestamp ?: System.currentTimeMillis()

        // Guard: skip zero/invalid GPS fixes
        if (lat == 0.0 && lon == 0.0) {
            Log.d("PORTABLE_DATA", "Skipped – zero coordinates")
            return
        }

        Log.d("PORTABLE_DATA", "Lat=$lat Lon=$lon AQI=$aqi Speed=$speed")

        val currentLatLng = LatLng(lat, lon)
        val lastPoint     = routeSegments.lastOrNull()?.lastOrNull()

        // Duplicate filter: ignore points closer than 3 m to the last recorded point
        val distFromLast = if (lastPoint != null)
            getDistance(LatLng(lastPoint.latitude, lastPoint.longitude), currentLatLng)
        else 10f

        if (routeSegments.isNotEmpty() && distFromLast < 3f) return

        // ── Daily reset ───────────────────────────────────────────────────────
        val todayStr   = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
        val firstPoint = routeSegments.firstOrNull()?.firstOrNull()
        if (firstPoint != null) {
            val firstDay = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
                .format(Date(firstPoint.timestamp))
            if (todayStr != firstDay) {
                routeSegments.clear()
                markerPoints.clear()
                lastMarkerLatLng = null
                Log.d("MAP", "Daily reset – new day detected")
            }
        }

        // ── Gap / reconnection detection (2-minute silence = new segment) ─────
        val lastUpdateTs = routeSegments.lastOrNull()?.lastOrNull()?.timestamp ?: 0L
        if (lastUpdateTs > 0 && timestamp - lastUpdateTs > 120_000L) {
            TripManager.endTrip("Data Gap")
            appContext?.let { TripManager.saveTripToCsv(it) }
            // FIX: new inner segment is a SnapshotStateList
            routeSegments.add(mutableStateListOf<RoutePoint>())
            Log.d("MAP", "New segment started after data gap")
        }

        // ── Trip auto-start (only if data is fresh — less than 15 s old) ──────
        if (!TripManager.isTripActive) {
            val ageDiff = System.currentTimeMillis() - timestamp
            if (ageDiff < 15_000L) {
                TripManager.startTrip()
            }
        }

        // ── Ensure at least one segment exists ────────────────────────────────
        if (routeSegments.isEmpty()) {
            // FIX: SnapshotStateList so Compose observes additions to this segment
            routeSegments.add(mutableStateListOf<RoutePoint>())
        }

        val newPoint = RoutePoint(timestamp, lat, lon, aqi, speed)

        // ── FIX: add to inner SnapshotStateList, then force outer notification ─
        // Step 1 – add the point (inner list is now Compose state → observed)
        routeSegments.last().add(newPoint)

        // Step 2 – reassign the outer slot (belt-and-suspenders: guarantees the
        // outer SnapshotStateList also emits a change so MapScreen recomposes
        // even if the inner-list notification alone isn't enough)
        val lastIndex = routeSegments.lastIndex
        routeSegments[lastIndex] = routeSegments[lastIndex]

        Log.d("MAP", "Point added – segment ${routeSegments.size}, " +
                "total points in segment: ${routeSegments.last().size}")

        // ── 50 m marker filter ────────────────────────────────────────────────
        if (lastMarkerLatLng == null || getDistance(lastMarkerLatLng!!, currentLatLng) >= 50f) {
            markerPoints.add(newPoint)
            lastMarkerLatLng = currentLatLng
        }

        // ── Forward to TripManager ────────────────────────────────────────────
        TripManager.addRoutePoint(lat, lon, aqi, speed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun getDistance(p1: LatLng, p2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0]
    }

    fun getMainDeviceName(): String = deviceNames[mainDeviceId ?: ""] ?: "Indoor Monitoring"

    // ─────────────────────────────────────────────────────────────────────────
    // On-demand Firebase fetch for the Predict Now button
    //
    // Performs a single addListenerForSingleValueEvent read of
    // devices/<id>/latest and returns the result as a DeviceData,
    // enriched with the mobile timestamp so it is consistent with
    // the data the real-time listener produces.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun fetchLatestDeviceData(deviceId: String): DeviceData? =
        suspendCancellableCoroutine { cont ->
            rtdb.getReference("devices/$deviceId/latest")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) { cont.resume(null); return }
                        val data = snapshot.getValue(DeviceData::class.java)
                        cont.resume(data?.copy(timestamp = System.currentTimeMillis()))
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.e("MAP", "fetchLatest cancelled: \${error.message}")
                        cont.resume(null)
                    }
                })
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Device management (add / delete) — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    fun addDevice(name: String, id: String, onResult: (Boolean, String) -> Unit) {
        val uid     = auth.currentUser?.uid ?: return
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            onResult(false, "Enter valid Device ID")
            return
        }

        rtdb.getReference("devices/$cleanId").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val type = snapshot.child("info/type").getValue(String::class.java) ?: "portable"
                    if (type == "indoor") {
                        onResult(false, "This device is already set as main indoor device")
                        return
                    }
                    val updates = hashMapOf<String, Any>(
                        "users/$uid/devices/$cleanId"     to true,
                        "users/$uid/deviceNames/$cleanId" to name
                    )
                    rtdb.reference.updateChildren(updates).addOnCompleteListener { task ->
                        if (task.isSuccessful) onResult(true, "Device Added Successfully")
                        else onResult(false, "Failed to save device")
                    }
                } else {
                    onResult(false, "Device not found. Please check ID.")
                }
            }
            override fun onCancelled(error: DatabaseError) { onResult(false, error.message) }
        })
    }

    fun deleteDevice(deviceId: String, onResult: (Boolean, String) -> Unit) {
        val uid = auth.currentUser?.uid ?: return
        val updates = hashMapOf<String, Any?>(
            "users/$uid/devices/$deviceId"     to null,
            "users/$uid/deviceNames/$deviceId" to null
        )
        rtdb.reference.updateChildren(updates).addOnCompleteListener { task ->
            if (task.isSuccessful) onResult(true, "Device removed successfully")
            else onResult(false, "Delete failed")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV persistence — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToCsv(
        deviceId: String,
        data: DeviceData,
        rawMap: Map<String, Any>?,
        context: Context
    ) {
        if (data.lat == null || data.lon == null) return
        val timestamp = data.timestamp ?: System.currentTimeMillis()
        if (timestamp == lastSavedMap[deviceId]) return
        lastSavedMap[deviceId] = timestamp

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dateStr  = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
                val fileName = "device_${deviceId}_$dateStr.csv"
                val file     = File(context.filesDir, fileName)
                val isNew    = !file.exists()
                val fos      = FileOutputStream(file, true)

                if (isNew) {
                    fos.write("timestamp,lat,lon,aqi,pm25,pm10,co2,temp,humidity,speed,extra\n".toByteArray())
                }

                val extra = rawMap?.entries
                    ?.filter { it.key !in listOf("timestamp","lat","lon","aqi","pm25","pm10","co2","temp","humidity","speed_kmh") }
                    ?.joinToString(";") { "${it.key}=${it.value}" } ?: ""

                val line = "$timestamp,${data.lat},${data.lon},${data.aqi ?: ""}," +
                        "${data.pm25 ?: ""},${data.pm10 ?: ""},${data.co2 ?: ""}," +
                        "${data.temp ?: ""},${data.humidity ?: ""},${data.speed_kmh ?: ""},$extra\n"
                fos.write(line.toByteArray())
                fos.close()

                Log.d("MAP", "Point saved locally for $deviceId")
            } catch (e: Exception) {
                Log.e("CSV", "Save error: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download to device Downloads folder — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    var isDownloading by mutableStateOf(false)
        private set

    fun downloadAllDeviceData(context: Context) {
        if (deviceList.isEmpty()) {
            Toast.makeText(context, "No devices linked to download data", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

            deviceList.forEach { deviceId ->
                val deviceName = deviceNames[deviceId] ?: "Device"
                val todayStr   = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
                val sourceFile = File(context.filesDir, "device_${deviceId}_$todayStr.csv")

                if (sourceFile.exists()) {
                    val exportName = "Pawamaan_Data_${deviceName.replace(" ", "_")}_$dateStr.csv"
                    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveFileToDownloadsApi29(context, sourceFile, exportName)
                    } else {
                        saveFileToDownloadsLegacy(sourceFile, exportName)
                    }
                    if (success) successCount++
                }
            }

            withContext(Dispatchers.Main) {
                isDownloading = false
                if (successCount > 0) {
                    Toast.makeText(context, "Downloaded $successCount file(s) to Downloads.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "No local data found. Ensure devices have been active.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveFileToDownloadsApi29(context: Context, sourceFile: File, fileName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri      = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                true
            } ?: false
        } catch (e: Exception) { false }
    }

    private fun saveFileToDownloadsLegacy(sourceFile: File, fileName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            sourceFile.copyTo(target, true)
            true
        } catch (e: Exception) { false }
    }
}