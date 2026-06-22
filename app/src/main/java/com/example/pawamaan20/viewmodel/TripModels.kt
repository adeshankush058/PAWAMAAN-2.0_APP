package com.example.pawamaan20.viewmodel

/**
 * RoutePoint represents a single AQI measurement during a trip.
 * It captures the spatial and environmental data at a specific moment.
 */
data class RoutePoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val aqi: Int,
    val speed: Double
)

/**
 * TripSummary represents one completed trip shown in HistoryScreen.
 * It contains metadata and a reference to the stored CSV data.
 */
data class TripSummary(
    val tripId: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val pointCount: Int,
    val csvPath: String
)
