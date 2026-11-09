"""
训练小 CNN 并导出 int8 TFLite。

用法:
    python train.py [--dataset dataset] [--out app_model] [--epochs 4]
"""
import argparse
import json
import os
import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf  # noqa: E402

BASE = os.path.dirname(os.path.abspath(__file__))
CLASSES = ["empty", "king", "advisor", "elephant", "horse", "rook", "cannon", "pawn"]


def build_model(n_classes=8):
    m = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(48, 48, 3)),
        tf.keras.layers.Conv2D(16, 3, padding="same", activation="relu"),
        tf.keras.layers.MaxPooling2D(),
        tf.keras.layers.Conv2D(32, 3, padding="same", activation="relu"),
        tf.keras.layers.MaxPooling2D(),
        tf.keras.layers.Conv2D(64, 3, padding="same", activation="relu"),
        tf.keras.layers.GlobalAveragePooling2D(),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dense(n_classes, activation="softmax"),
    ])
    m.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return m


def representative_dataset(x):
    for i in range(500):
        yield [x[i:i + 1].astype(np.float32)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=os.path.join(BASE, "dataset"))
    ap.add_argument("--out", default=os.path.join(BASE, "app_model"))
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--batch", type=int, default=256)
    args = ap.parse_args()

    x = np.load(os.path.join(args.dataset, "x.npy")).astype(np.float32) / 255.0
    y = np.load(os.path.join(args.dataset, "y.npy"))
    n = len(x)
    cut = int(n * 0.95)
    xtr, ytr, xev, yev = x[:cut], y[:cut], x[cut:], y[cut:]
    print(f"train={len(xtr)} eval={len(xev)}")

    model = build_model()
    model.fit(xtr, ytr, epochs=args.epochs, batch_size=args.batch,
              validation_data=(xev, yev), verbose=2)

    loss, acc = model.evaluate(xev, yev, verbose=0)
    print(f"eval acc={acc:.4f}")

    # 逐类统计
    pred = np.argmax(model.predict(xev, verbose=0), axis=1)
    per_class = {}
    for ci, cname in enumerate(CLASSES):
        mask = yev == ci
        per_class[cname] = round(float((pred[mask] == ci).mean()), 4) if mask.any() else None
    print("per-class:", per_class)

    # int8 全量化
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = lambda: representative_dataset(xtr)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type = tf.uint8
    conv.inference_output_type = tf.float32
    tfl = conv.convert()

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "pieces_int8.tflite")
    with open(out_path, "wb") as f:
        f.write(tfl)
    with open(os.path.join(args.out, "labels.json"), "w", encoding="utf-8") as f:
        json.dump({"classes": CLASSES, "size": 48}, f, ensure_ascii=False)
    with open(os.path.join(args.out, "metrics.txt"), "w") as f:
        f.write(f"acc={acc:.4f}\nper_class={per_class}\n")

    # 用 TFLite 解释器做最终冒烟测试
    it = tf.lite.Interpreter(model_path=out_path)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    print("tflite input:", inp["shape"], inp["dtype"], "output:", out["shape"], out["dtype"])
    ok = 0
    tot = min(500, len(xev))
    for i in range(tot):
        q = (xev[i:i + 1] * 255).astype(np.uint8)
        it.set_tensor(inp["index"], q)
        it.invoke()
        p = np.argmax(it.get_tensor(out["index"])[0])
        ok += int(p == yev[i])
    print(f"tflite smoke acc={ok / tot:.4f} ({ok}/{tot})")
    print("模型输出:", out_path)


if __name__ == "__main__":
    main()
