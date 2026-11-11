# 象棋鱼 · 识别摆谱版

基于 **zfdang/chinese-chess-fish-android**（象棋鱼，项目1），
缝合 **Shirakawa-Kotone/chinese-chess-helper**（项目2）的
**拍照识别局面摆谱** 能力，并补充 helper 缺失的 **相册 / 截图导入** 入口。

## 识别摆谱功能

对弈界面底栏新增 **识别摆谱** 按钮：

1. **拍照识别**——相机对准棋盘（3:4 引导框），拍下即识别
2. **相册 / 截图选图**——从相册选择对局截图或照片
3. 识别结果进校对界面：棋盘渲染识别网格、低置信格红框提示、
   点击任意交叉点可改/加/删子、FEN 实时显示、可复制
4. 点 **从此局面开始对弈** → 进入象棋鱼原有对弈流程（Pikafish 分析、
   悔棋、历史记录等一切功能照常可用）

## 技术说明

- 识别管线移植自 helper（OnnxRecognizer）：RTMPose 4 角点 → 透视矫正 →
  Swin 90 格分类，模型同 helper 内置版本（`models/4_v6-0301.onnx` +
  `nano_v3-0319.onnx`，来源 TheOne1006/chinese-chess-recognition）。
- 稳健化：双 pose 缩放假设 + 旋转重试共 4 候选按置信择优；EXIF 方向矫正；
  可疑识别触发底边角点精修；无 OpenCV 依赖（android.graphics 等价实现）。
- 识别输出为标准 FEN（大写=红），直接走象棋鱼既有的
  `GameActivity(FENString)` → `startFENGame` 链路。

## 构建

`./gradlew assembleArmv8-Release assembleArmv8-dotprod-Release`
（JDK 17；推送 main 分支后 GitHub Actions 自动构建并在 Artifacts 产出 `ChessFish-apks`）

## 致谢

- [zfdang/chinese-chess-fish-android](https://github.com/zfdang/chinese-chess-fish-android)（象棋鱼，基座）
- [Shirakawa-Kotone/chinese-chess-helper](https://github.com/Shirakawa-Kotone/chinese-chess-helper)（识别管线来源）
- [TheOne1006/chinese-chess-recognition](https://github.com/TheOne1006/chinese-chess-recognition)（识别模型）
- [Pikafish](https://github.com/official-pikafish/Pikafish)（引擎）
