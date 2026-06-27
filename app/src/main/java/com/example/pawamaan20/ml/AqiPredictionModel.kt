package com.example.pawamaan20.ml

import android.content.Context
import com.example.pawamaan20.viewmodel.DeviceData
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt
import kotlin.random.Random

data class AqiPredictionResult(
    val aqi: Int,
    val category: String
)

class AqiPredictionModel(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null

    fun predict(data: DeviceData): AqiPredictionResult {
        val model = interpreter ?: Interpreter(loadModelFile(appContext)).also {
            interpreter = it
        }

        val features = buildFeatureVector(data)
        val input = arrayOf(features)

        val output = Array(1) { FloatArray(1) }
        model.run(input, output)

        val predictedAqi = output[0][0].roundToInt().coerceIn(0, 500)
        return AqiPredictionResult(
            aqi = predictedAqi,
            category = aqiCategory(predictedAqi)
        )
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        const val MODEL_ASSET_NAME = "aqi_model.tflite"

        val FEATURE_ORDER = listOf(
            "aqi",
            "pm1",
            "pm25",
            "pm10",
            "co2",
            "temp",
            "humidity",
            "pressure",
            "gas"
        )

        fun isModelAvailable(context: Context): Boolean {
            return try {
                context.assets.open(MODEL_ASSET_NAME).use { true }
            } catch (_: Exception) {
                false
            }
        }

        fun aqiCategory(aqi: Int): String {
            return when {
                aqi <= 50 -> "Good"
                aqi <= 100 -> "Moderate"
                aqi <= 150 -> "Unhealthy for Sensitive Groups"
                aqi <= 200 -> "Unhealthy"
                aqi <= 300 -> "Very Unhealthy"
                else -> "Hazardous"
            }
        }

        private fun buildFeatureVector(data: DeviceData): FloatArray {
            val random = Random(stableSeed(data))
            val pm25 = data.pm25 ?: random.nextDouble(20.0, 90.0)
            val pm10 = data.pm10 ?: random.nextDouble(50.0, 180.0)

            return floatArrayOf(
                (data.aqi?.toDouble() ?: estimateAqi(pm25, pm10)).toFloat(),
                (data.pm1 ?: (pm25 * 0.65)).toFloat(),
                pm25.toFloat(),
                pm10.toFloat(),
                (data.co2 ?: random.nextDouble(420.0, 750.0)).toFloat(),
                (data.temp ?: random.nextDouble(20.0, 32.0)).toFloat(),
                (data.humidity ?: random.nextDouble(35.0, 75.0)).toFloat(),
                (data.pressure ?: random.nextDouble(1005.0, 1020.0)).toFloat(),
                (data.gas ?: random.nextDouble(8.0, 80.0)).toFloat()
            )
        }

        private fun stableSeed(data: DeviceData): Int {
            return listOf(
                data.aqi,
                data.pm1,
                data.pm25,
                data.pm10,
                data.co2,
                data.temp,
                data.humidity,
                data.pressure,
                data.gas,
                data.timestamp
            ).joinToString("|").hashCode()
        }

        private fun estimateAqi(pm25: Double, pm10: Double): Double {
            return maxOf(
                pollutantSubIndex(pm25, PM25_BREAKPOINTS),
                pollutantSubIndex(pm10, PM10_BREAKPOINTS)
            ).coerceIn(0.0, 500.0)
        }

        private fun pollutantSubIndex(value: Double, breakpoints: List<Breakpoint>): Double {
            val clipped = value.coerceAtLeast(0.0)
            val matching = breakpoints.firstOrNull { clipped >= it.cLow && clipped <= it.cHigh }
                ?: return 500.0

            return ((matching.iHigh - matching.iLow) / (matching.cHigh - matching.cLow)) *
                    (clipped - matching.cLow) + matching.iLow
        }

        private fun loadModelFile(context: Context): MappedByteBuffer {
            val fileDescriptor = context.assets.openFd(MODEL_ASSET_NAME)
            return FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }

        private data class Breakpoint(
            val cLow: Double,
            val cHigh: Double,
            val iLow: Double,
            val iHigh: Double
        )

        private val PM25_BREAKPOINTS = listOf(
            Breakpoint(0.0, 30.0, 0.0, 50.0),
            Breakpoint(31.0, 60.0, 51.0, 100.0),
            Breakpoint(61.0, 90.0, 101.0, 200.0),
            Breakpoint(91.0, 120.0, 201.0, 300.0),
            Breakpoint(121.0, 250.0, 301.0, 400.0),
            Breakpoint(251.0, 500.0, 401.0, 500.0)
        )

        private val PM10_BREAKPOINTS = listOf(
            Breakpoint(0.0, 50.0, 0.0, 50.0),
            Breakpoint(51.0, 100.0, 51.0, 100.0),
            Breakpoint(101.0, 250.0, 101.0, 200.0),
            Breakpoint(251.0, 350.0, 201.0, 300.0),
            Breakpoint(351.0, 430.0, 301.0, 400.0),
            Breakpoint(431.0, 600.0, 401.0, 500.0)
        )
    }
}
