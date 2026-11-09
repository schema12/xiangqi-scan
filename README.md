# 象棋摆谱助手（XiangqiScan）

给 cnvcs《中国象棋》App 补一个"拍照/截图 → 自动识别 → 摆好棋盘"的独立辅助 App。
识别结果生成 PGN（标准 UCCI FEN），root 直写进《中国象棋》的棋谱目录，
在它的"打谱/载入对局"里直接打开。无 root 时回退导出到 Download。

> 背景与全部逆向结论见 `NOTES.md`。

## 结构

```
assistant/           Android App（Kotlin + CameraX + TFLite + libsu）
  app/src/main/java/com/xiangqiscan/app/
    core/            Position 棋盘模型、Fen(FEN/PGN 编解码)
    cv/              BoardDetector(Hough 找边框四角)、Homography、网格采样
    ml/              TflitePieceClassifier / HeuristicClassifier(无模型回退)
    scan/            扫描页（四角拖拽校准）
    editor/          编辑确认页（自绘棋盘、点选改子、合法性校验）
    io/              Delivery(root 写入 + Download 回退)、Prefs
    debug/           DebugActivity（adb 无头测试入口，仅 debug 构建导出）
trainer/             合成数据生成 + 小 CNN 训练 → int8 TFLite（约 60KB）
.github/workflows/   android-build.yml（云编译 APK）、train-model.yml（云训练）
apk/ samples/        逆向材料：原版 APK、实测样本棋谱
```

## 构建

本地需 JDK17 + Android SDK；无本地环境时推到 GitHub 用 Actions 云编译
（工作流会产出 `xiangqiscan-debug` / `xiangqiscan-release-unsigned` 两个 artifact）。
release 需自签名：

```
apksigner sign --ks <你的keystore> --out xiangqiscan.apk app-release-unsigned.apk
```

## 训练识别模型

```
cd trainer
pip install -r requirements.txt
python -I gen_data.py --per-class 12000   # Linux 会自动下载开源中文字体
python -I train.py --epochs 4
# 产物 trainer/app_model/pieces_int8.tflite
```

把 `pieces_int8.tflite` 放进 `assistant/app/src/main/assets/model/` 即可被 App 加载；
没有模型文件时 App 自动退化为启发式（只判空/红黑，字形人工指定），依然可用。

## 尚未完成 / 待验证

- [ ] 编译（待远程桌面或 Actions 首跑）
- [ ] 真实截图/照片上的识别率调优（需要样本）
- [ ] 《中国象棋》Wi-Fi 导入 POST 直连（备选交付路径）
