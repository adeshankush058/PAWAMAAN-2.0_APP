"""
Train an Android-compatible AQI prediction model for Pawamaan 2.0.

Output:
  app/src/main/assets/aqi_model.tflite

Required CSV columns:
  aqi, pm1, pm25, pm10, co2, temp, humidity, pressure, gas, target_aqi_24h

Install dependencies:
  pip install pandas numpy scikit-learn tensorflow

Run:
  python model_tools/train_app_compatible_aqi_model.py path/to/training_data.csv
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.model_selection import train_test_split


FEATURE_COLUMNS = [
    "aqi",
    "pm1",
    "pm25",
    "pm10",
    "co2",
    "temp",
    "humidity",
    "pressure",
    "gas",
]

TARGET_COLUMN = "target_aqi_24h"

ROOT_DIR = Path(__file__).resolve().parents[1]
ASSETS_DIR = ROOT_DIR / "app" / "src" / "main" / "assets"
TFLITE_OUTPUT = ASSETS_DIR / "aqi_model.tflite"
METADATA_OUTPUT = ASSETS_DIR / "aqi_model_metadata.json"


def load_training_data(csv_path: Path) -> tuple[np.ndarray, np.ndarray]:
    df = pd.read_csv(csv_path)

    required = FEATURE_COLUMNS + [TARGET_COLUMN]
    missing = [col for col in required if col not in df.columns]
    if missing:
        raise ValueError(f"Training CSV is missing required columns: {missing}")

    clean = df[required].replace([np.inf, -np.inf], np.nan).dropna()
    if len(clean) < 100:
        raise ValueError(
            "Training data has fewer than 100 valid rows after cleaning. "
            "Add more data before training."
        )

    x = clean[FEATURE_COLUMNS].astype("float32").to_numpy()
    y = clean[[TARGET_COLUMN]].astype("float32").to_numpy()

    y = np.clip(y, 0.0, 500.0)
    return x, y


def build_model(x_train: np.ndarray) -> tf.keras.Model:
    normalizer = tf.keras.layers.Normalization(axis=-1, name="sensor_normalization")
    normalizer.adapt(x_train)

    model = tf.keras.Sequential(
        [
            tf.keras.Input(shape=(len(FEATURE_COLUMNS),), name="sensor_values"),
            normalizer,
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dense(16, activation="relu"),
            tf.keras.layers.Dense(1, name="predicted_aqi_24h"),
        ],
        name="pawamaan_aqi_24h_model",
    )

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="mae",
        metrics=["mae"],
    )
    return model


def export_tflite(model: tf.keras.Model) -> None:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    TFLITE_OUTPUT.write_bytes(tflite_model)


def save_metadata(history: tf.keras.callbacks.History, test_mae: float) -> None:
    metadata = {
        "model_file": "aqi_model.tflite",
        "target": TARGET_COLUMN,
        "input_dtype": "float32",
        "input_shape": [1, len(FEATURE_COLUMNS)],
        "output_dtype": "float32",
        "output_shape": [1, 1],
        "feature_order": FEATURE_COLUMNS,
        "test_mae": round(float(test_mae), 4),
        "final_train_mae": round(float(history.history["mae"][-1]), 4),
        "final_val_mae": round(float(history.history["val_mae"][-1]), 4),
        "normalization": "baked_into_tflite_model",
    }
    METADATA_OUTPUT.write_text(json.dumps(metadata, indent=2))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(
            "Usage: python model_tools/train_app_compatible_aqi_model.py "
            "path/to/training_data.csv"
        )

    csv_path = Path(sys.argv[1]).expanduser().resolve()
    x, y = load_training_data(csv_path)

    x_train, x_test, y_train, y_test = train_test_split(
        x,
        y,
        test_size=0.2,
        random_state=42,
        shuffle=True,
    )

    model = build_model(x_train)

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_mae",
            patience=20,
            restore_best_weights=True,
        )
    ]

    history = model.fit(
        x_train,
        y_train,
        validation_split=0.2,
        epochs=200,
        batch_size=32,
        callbacks=callbacks,
        verbose=1,
    )

    test_loss, test_mae = model.evaluate(x_test, y_test, verbose=0)
    print(f"Test MAE: {test_mae:.2f}")

    export_tflite(model)
    save_metadata(history, test_mae)

    print(f"Saved TensorFlow Lite model: {TFLITE_OUTPUT}")
    print(f"Saved model metadata: {METADATA_OUTPUT}")


if __name__ == "__main__":
    main()
