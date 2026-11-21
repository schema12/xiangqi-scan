# AB-JChess 揭棋引擎集成

- 上游：https://github.com/lxsgx23/AB-JChess （GPL-3.0，基于 jqe by Devin O'Keefe, MIT）
- 集成版本：commit e5249e8（2026-10-31 发布对应的 main）
- 集成形态：JNI 共享库 `libabjchess.so`（arm64-v8a，单档 armv8 基线 + nobmi.h 回退），打包于
  `app/src/main/jniLibs/arm64-v8a/`；NNUE 网络 `abjchess-20261030.nnue` 打包于 assets/jieqi。

## 对上游的改动（补丁）

全部改动集中在两个文件，未触碰引擎核心逻辑：

1. `abjchess/src/uci.cpp`
   - `out()` 增加输出钩子 `jq::g_outHook`（JNI 模式下输出转回调，不再写 stdout）
   - `uci_loop()` 输入抽象 `jq::g_lineSource`（JNI 模式下从命令队列取行；false = 退出循环）
   - 两个钩子定义在 `namespace jq` 非匿名作用域（供 JNI 翻译单元 extern 引用）
2. `abjchess/src/jq_android.cpp`（本目录，新增文件）
   - JNI 入口 create/command/stop/release/run，契约与 Pikafish 的 NativeBridge 相同
   - 单会话；`create` 在前一会话未释放时最多等待 4 秒（Activity 重建竞态）
   - `run` 在调用线程阻塞执行 UCI 循环；stop 通过注入 quit 实现

## 构建（NDK r27c，API 29）

```bash
cd AB-JChess/abjchess
NDKB=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
CXX=$NDKB/aarch64-linux-android29-clang++
COMMON="-std=c++20 -O3 -DNDEBUG -pthread -Isrc -ffp-contract=off \
  -DABJ_TIER_NAME=\"armv8\" -DABJ_OS=\"android\" -DABJ_ARCH=\"aarch64\" \
  -march=armv8-a -include nobmi.h"
mkdir -p /tmp/jqso
for f in tables position eval nnue_small hnnue search uci selfplay bitboard position_ref jq_android; do
  $CXX $COMMON -fPIC -c src/$f.cpp -o /tmp/jqso/$f.o &
done
wait
$CXX $COMMON -fPIC -shared /tmp/jqso/*.o -static-libstdc++ -o libabjchess.so -llog
```

自建产物与上游 release 的独立可执行文件 sha256 一致（1cc6e52a…，arm64）。
基线验证：`bench` 在 Android 上输出 `Nodes searched: 43125`（与上游文档一致）。

## GPL-3.0 合规

以动态库形式分发 AB-JChess 衍生作品 + 本应用（独立程序）。随包提供 LICENSE 副本
（assets/jieqi/LICENSE-AB-JChess.txt），"关于"页注明来源与源码地址，本目录保留补丁与
构建说明即构成对应的完整对应源码说明。
