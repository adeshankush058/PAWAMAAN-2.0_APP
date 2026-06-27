# App-Compatible AQI Model Specification

This is the exact model version that should be made for the current Android app.

## Final Model File

The final model must be:

```text
app/src/main/assets/aqi_model.tflite
```

## Model Type

Use a TensorFlow Lite regression model.

Do not use these formats directly in the Android app:

```text
.pkl
.joblib
.h5
.keras
.pt
.pth
.onnx
```

The app currently loads only:

```text
.tflite
```

## Input Contract

Input tensor:

```text
name: any name is fine
dtype: float32
shape: [1, 9]
```

Feature order must be exactly:

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

This is the order used by:

```text
app/src/main/java/com/example/pawamaan20/ml/AqiPredictionModel.kt
```

## Output Contract

Output tensor:

```text
dtype: float32
shape: [1, 1]
```

The output value must be:

```text
predicted_aqi_24h
```

That means the model should directly predict tomorrow's AQI.

## Training Dataset Shape

Your training CSV should contain at least these columns:

```text
aqi
pm1
pm25
pm10
co2
temp
humidity
pressure
gas
target_aqi_24h
```

Where:

```text
target_aqi_24h = AQI value 24 hours after the current row
```

If your data is not exactly hourly, create the target using the nearest future
reading around 24 hours later.

## Why This Version Is Correct For The App

The app does not currently have reliable access to the full feature set used by
the `data_aqi` LightGBM models.

Those models expect features like:

```text
wind_speed
wind_sin
no2_roll24h_mean
pm25_city_mean_lag1h
station_tt_nagar
pm25_lag48h
```

The live Android app currently has direct access to the indoor device fields:

```text
aqi
pm1
pm25
pm10
co2
temp
humidity
pressure
gas
```

So the compatible model must be trained on exactly those available fields.

## Recommended Model Architecture

A small dense neural network is enough for this app-side version:

```text
Input [9]
Dense 32, ReLU
Dense 16, ReLU
Dense 1
```

Use regression loss:

```text
Mean Absolute Error
```

Clamp the predicted value between 0 and 500 in the Android app. This is already
done in `AqiPredictionModel.kt`.

## Normalization

Recommended:

1. Calculate mean and standard deviation for the 9 input columns during training.
2. Normalize training data before model fitting.
3. Either bake normalization into the TensorFlow model or apply the same
   normalization in Android.

The easiest route is to bake normalization into the model using a Keras
`Normalization` layer. Then Android can keep sending raw sensor values.

## Export Script

Use:

```text
model_tools/train_app_compatible_aqi_model.py
```

That script trains a Keras model and exports:

```text
app/src/main/assets/aqi_model.tflite
```

## Before Vs After

Before:

```text
data_aqi/output/models_v2/model_pm25_24h.pkl
```

Problem:

```text
Python LightGBM model, predicts PM2.5, expects around 50 engineered features.
Not directly usable in the Android app.
```

After:

```text
app/src/main/assets/aqi_model.tflite
```

Correct behavior:

```text
Android sends 9 live sensor values.
Model returns 1 predicted AQI value.
Home screen shows "AQI: value - category".
```
