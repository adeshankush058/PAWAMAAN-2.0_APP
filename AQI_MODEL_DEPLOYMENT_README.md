# AQI Model Deployment Changes

This app is now prepared for an on-device TensorFlow Lite AQI prediction model.
You can create the model later and place it in the Android assets folder.

For the exact app-compatible model contract, read:

```text
APP_COMPATIBLE_MODEL_SPEC.md
```

To train/export a compatible TensorFlow Lite model, use:

```text
model_tools/train_app_compatible_aqi_model.py
```

## Required Model Format

Use this format:

```text
aqi_model.tflite
```

Place it here:

```text
app/src/main/assets/aqi_model.tflite
```

The app currently handles the missing model safely. Until the file is added, the
home screen prediction card shows that prediction is unavailable instead of
crashing.

## Files Changed

### 1. Gradle Dependency

File:

```text
app/build.gradle.kts
```

Position:

```text
android { ... }
dependencies { ... }
```

Before:

```kotlin
dependencies {
    implementation("com.google.firebase:firebase-database-ktx")
}
```

After:

```kotlin
android {
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("com.google.firebase:firebase-database-ktx")

    // On-device ML inference for the AQI prediction model in app/src/main/assets.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
```

Purpose:

- Adds TensorFlow Lite support.
- Makes Android package `.tflite` files without compression, which allows fast
  memory-mapped loading.

### 2. New Model Inference Wrapper

File:

```text
app/src/main/java/com/example/pawamaan20/ml/AqiPredictionModel.kt
```

Position:

```text
New package: com.example.pawamaan20.ml
```

Before:

```text
No model-loading or prediction code existed.
```

After:

```kotlin
class AqiPredictionModel(context: Context) : Closeable {
    fun predict(data: DeviceData): AqiPredictionResult
}
```

Purpose:

- Loads `aqi_model.tflite` from `app/src/main/assets`.
- Sends live sensor values into the model.
- Reads one numeric output as predicted AQI.
- Converts predicted AQI into a category like `Good`, `Moderate`, or `Unhealthy`.
- Closes the TensorFlow Lite interpreter when the UI is disposed.

Current input feature order:

```text
1. aqi
2. pm1
3. pm25
4. pm10
5. co2
6. temp
7. humidity
8. pressure
9. gas
```

Expected model input shape:

```text
[1, 9]
```

Expected model output shape:

```text
[1, 1]
```

That output value should be the predicted AQI for tomorrow.

The current `data_aqi/output/models_v2/*.pkl` models are not directly compatible
with the Android app because they are Python LightGBM models, predict PM2.5, and
expect many engineered station/weather features that the app does not currently
receive.

### 3. Home Screen Prediction Slot

File:

```text
app/src/main/java/com/example/pawamaan20/ui/screens/HomeScreen.kt
```

Position:

```text
DashboardContent(...)
```

Before:

```kotlin
Text(text = "AQI: 78 - Moderate", fontSize = 18.sp, color = Color(0xFF0277BD))
```

The prediction card always showed a fixed dummy value.

After:

```kotlin
TomorrowPredictionCard(
    predictionText = predictionText,
    predictionDetail = predictionDetail
)
```

The card now:

- Waits for live indoor sensor data from `DeviceViewModel`.
- Checks whether `app/src/main/assets/aqi_model.tflite` exists.
- Runs the TensorFlow Lite model when the file is available.
- Shows the predicted AQI and category.
- Shows a safe unavailable state if the model is missing or the input shape does
  not match.

### 4. Assets Folder

File:

```text
app/src/main/assets/.gitkeep
```

Position:

```text
app/src/main/assets/
```

Before:

```text
No assets folder existed for the model file.
```

After:

```text
app/src/main/assets/
```

Purpose:

- Creates the folder where `aqi_model.tflite` should be placed later.

## How To Add Your Model Later

1. Train/export your model as TensorFlow Lite.
2. Rename the model file exactly:

```text
aqi_model.tflite
```

3. Put it here:

```text
app/src/main/assets/aqi_model.tflite
```

4. Make sure the model uses this input:

```text
[aqi, pm1, pm25, pm10, co2, temp, humidity, pressure, gas]
```

5. Make sure the model returns one number:

```text
predicted_aqi
```

## If Your Model Uses Different Inputs

Update this file:

```text
app/src/main/java/com/example/pawamaan20/ml/AqiPredictionModel.kt
```

Change this section:

```kotlin
val input = arrayOf(floatArrayOf(
    (data.aqi ?: 0).toFloat(),
    (data.pm1 ?: 0.0).toFloat(),
    (data.pm25 ?: 0.0).toFloat(),
    (data.pm10 ?: 0.0).toFloat(),
    (data.co2 ?: 0.0).toFloat(),
    (data.temp ?: 0.0).toFloat(),
    (data.humidity ?: 0.0).toFloat(),
    (data.pressure ?: 0.0).toFloat(),
    (data.gas ?: 0.0).toFloat()
))
```

The order and preprocessing must match the exact training order. If you used a
scaler during training, the same scaling values must be added here before
running the model.

## Current Behavior

Before adding the model:

```text
Tomorrow Prediction
Prediction unavailable
Model file missing
```

After adding a compatible model:

```text
Tomorrow Prediction
AQI: 78 - Moderate
```

The AQI value will come from your `.tflite` model, not from hardcoded text.
