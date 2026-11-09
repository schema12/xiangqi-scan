# 项目交接笔记 — 中国象棋 app「拍照识别摆谱」功能

> 本文件供新会话继续任务用。让新会话读本文件即可接续上下文。

## 需求
用户手机上的《中国象棋》app（cnvcs.com 出品）有摆谱功能但必须手动摆子。
希望增加：**拍照 / 相册导入截图 → 自动识别棋子 → 自动摆好棋盘**。

## 已确认的事实
- **App**：《中国象棋》v1.87，开发者 cnvcs.com（cnvcs@hotmail.com），**闭源**。
  - 官网：https://cnvcs.com/xiangqi.html
  - APK 直链：https://cnvcs.com/apk/xiangqi187.apk （已下载到 `apk/xiangqi187.apk`）
  - 功能：打谱/摆谱/练习/对弈；支持 CBL、CBR、PGN、XQF、MXQ、CHE 棋谱格式；
    1.76 起练习模式可摆局，1.76+ 打谱模式也有摆设棋局；**1.86 起支持 Wi-Fi 导入/导出棋谱文件**。
- **本地环境（Windows）**：Python 3.10 ✅ / adb ✅ / git ✅ / Java 8（JVM 配置异常）⚠️ / **无 Android SDK** ❌
  → 本地不适合编译；按用户要求：优先用远程 Linux 桌面 MCP 编译，不行则 GitHub Actions 云编译。
- **MCP**：ZCode 里已配置并连接 `simular-box`（https://mcp.box.simular.ai/mcp），
  这是用户的**远程 Linux 桌面 MCP 服务**。注意：MCP 工具只在**会话启动时**加载，
  老会话里看不到工具，必须新开会话。
- 用户手机可通过 USB + adb 连接（用户同意 adb 提取，官方下载亦可，已用官方 APK）。

## 重大进展（已验证）
- **APK 结构**：Java 层是空壳（classes.dex 463KB 几乎全是 androidx，无 app 字符串）；
  UI/逻辑全在 `lib/arm64-v8a/libxiangqi.so`（2.4MB，C++，Magisk root 用户手机实测）。
  → **放弃注入 app 的路线**，做独立辅助工具 + 对接它的载入功能。
- **内置棋谱库**：assets/data0-12.pak 是 SPAK 容器（魔数 `SPAK\x01`），内容**加密**（熵 8.0），无法提取。
- **关键发现：app 的棋谱就在手机上**：`/sdcard/Android/data/com.cnvcs.xiangqi/files/棋谱/` 下，
  含赛事/古谱/残局/开局等 .cbl 文件。已拉样本到 `samples/`：
  - `1109test.pgn`（用户自己存的对局，**PGN 模板**）：标签 `[Game "Chinese Chess"] [Date] [Time] [Red] [Black] [Result]`，
    着法为中文纵线记法（红用汉字数字"炮二进七"，黑用全角数字"车９平８"），无 FEN。
  - 三个 CBL：`CCBridge` 魔数 + 20 字节头（偏移16 处 u32=解压后大小）+ zlib 流，
    内容为 GBK 编码二进制结构（u32 长度+定长字段的标题/作者/日期，`|` 分隔的简介）。
    着法树形如 `01 00 77 57 51 23`（序号+坐标对）。**局面编码未完全逆向**（无 FEN 字符串，是二进制局面）。
- **FEN 测试（决定性，已完结）**：构造 UCCI 标准 FEN 的 PGN push 到手机
  `棋谱/其它/fentest.pgn`（root 用 `suu`，不要用 `su`；属主 u0_a201:sdcard_rw，chmod 660），
  **app 能正确载入**。显示"红上黑下"只是 app 的视角（自带翻转功能，无关紧要）。
- **FEN 方言（app 自导出验证）**：`samples/outtest.pgn` —— **标准 UCCI**：
  黑方底线在前（row0=黑方）、大写红/小写黑（KABNRC P / kabnrcp）、`w`/`b` 轮走方、
  尾部计数可省略（app 只写 `[FEN "... b"]`，不带 ` - - 0 1`，但带了也能解析）。
  PGN 标签模板照 `1109test.pgn`/`outtest.pgn`：Game/Date/Time/Red/Black/Result(+FEN)。
- **app 只能导出 pgn/xq/xqf**（.xq=私有 XQEX 加密格式，放弃；.xqf=公开标准格式，备用）。
  载入支持 cbl/cbr/pgn/xqf/mxq/che。**主路线输出 PGN+FEN。**
- 用户手机已 root（Magisk，`suu` 提权），adb 调试可用，设备号 IFY5T4ON59SKWKSW。
- **adb 注意**：Git Bash 需 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"` 防路径转换；
  中文文件名 adb pull 会乱码，用 `adb shell cat` 流式拉取。

## 代码已写完（本会话），工程结构见 README.md
- `assistant/` 完整 Android 工程：Kotlin+CameraX+TFLite+libsu，viewBinding，minSdk 26 / target 35，
  AGP 8.7.3 / Gradle 8.10.2 / Kotlin 2.0.21 / JDK 17（无 wrapper jar，云构建用 setup-gradle 指定版本）。
- `trainer/` 合成数据生成器（已本地冒烟通过，样本预览效果佳）+ Keras CNN → int8 TFLite（~60KB）。
- `.github/workflows/`：android-build.yml（assembleDebug+assembleRelease，artifact 输出）、
  train-model.yml（手动触发，云生成数据+训练，产出 tflite artifact）。
- FEN 编解码算法已用 Python 镜像对 `outtest.pgn` 的 FEN 做往返验证，逐字节一致。
- 调试通道：debug 构建的 `DebugActivity`（exported）支持 adb 无头驱动：
  `am start -n com.xiangqiscan.app/.debug.DebugActivity --es in <图> --es out <目录> [--es corners ...] [--es fen ...]`，
  输出 overlay.jpg / cells.txt / fen.txt 到 app 外部私有目录（root 可 pull）。

## 下次会话（编译阶段）步骤
1. 首选：远程桌面 MCP 装 JDK17+Android SDK cmdline-tools（用户已授权在远程机装编译环境），
   `gradle :app:assembleDebug` 出 APK → adb 装 → 推测试图（网上搜天天象棋等截图）→
   `am start ...DebugActivity` → pull 结果调 BoardDetector/HeuristicClassifier。
2. 或：推 GitHub（用户有账号）→ Actions 云编译 → 下载 artifact → adb 装。
3. 模型：触发 train-model.yml 或远程机跑 trainer，产物放 `assistant/app/src/main/assets/model/pieces_int8.tflite`。
4. 真机验收：识别 → 编辑器改错 → 写入 → 象棋 App 载入全流程。

## 待办 / 下一步
1. **编译**（代码已全部写完，见上节"下次会话步骤"）：远程桌面 MCP 优先，GitHub Actions 备选（用户有账号）。
2. **模型**：云训练或远程机训练，产物入 assets；无模型时 App 仍可用（启发式+人工指定字形）。
3. **样本**：网上搜各象棋 App（尤其天天象棋）对局截图 + 棋书图谱照片做识别调优；搜不到再找用户要。
4. **验收闭环**：真机 识别→编辑→写入→《中国象棋》载入 全流程 + 识别率调优。

## 用户约束
- 不要在本地下载一堆编译工具/SDK。
- 远程桌面慢或问题多就直接换 GitHub 云编译。
