# 揭棋模式（AB-JChess）技术审计与实施笔记

基准：origin/main @ ef8cbe10（box ~/work/repo，工作区干净）。PC 工作目录在激进模式分支
feature/strategy-settings-book，本任务不触碰。

## 一、AB-JChess 引擎协议（源码实测，非猜测）

来源：lxsgx23/AB-JChess @ e5249e8（C++20，GPL-3.0，基于 jqe by Devin O'Keefe MIT）。

### FEN（Pikafish 揭棋方言，5 或 6 字段）
```
xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX w R2A2C2P5N2B2r2a2c2p5n2b2 0 1
```
- 字段1 棋盘：rank9→rank0；**X=红暗子，x=黑暗子**；暗子只允许出现在其初始位置（darkRole 表，
  set_fen 校验 "face-down piece off its start point"）；每方恰一将/帅
- 字段2 行棋方 w/b（w=红）
- 字段3 身份池：`R2A2C2P5N2B2`（红）+ 小写（黑），字母序任意（R 车 N 马 B 相 A 仕 C 炮 P 兵），
  数量 ≤ 初始（R/N/B/A/C 各2、P5）；**池=该方尚未揭晓的身份余量**；池总数必须 ≥ 暗子数；
  池字段 "-" 仅当无暗子
- 字段4/5 半动钟/回合数；6 字段 FEN 的第 4 字段被引擎忽略（兼容）

### 走法 token（position ... moves 附加）
- 4~6 字符：`h2e2`（明子移动）
- 暗子移动：+1 字符揭晓身份（**行棋方颜色大小写**：红暗子翻出车='R'）→ `h2e2R`
- 吃黑暗子且身份已知：再 +1 字符（** victim 颜色大小写**）→ `h2e2Pr`
- **被吃暗子身份未知：省略该字符** → 引擎原生保留概率分布（urn 不减）✓ 实测 T-A 通过
- 暗子无揭晓后缀 → 报错 "identity suffix does not match"（实测 T1）✓

### 规则模型（决定 App 端走法生成）
- **暗子的走法按其所在格的初始角色生成**（"movement role is fixed by the square it stands on"），
  gen 与 least_attacker 皆如此；暗子移动必须同时揭晓（reveal ∈ 该方池即可，不校验走法与身份一致）
- 明子按标准象棋规则
- 重复局面/长将长捉：ChaseRule 选项（默认 skyrule）
- 将/帅永远不是暗子

### UCI
- 标准 uci/isready/position/go/stop；position 支持 `position startpos|fen ... [moves t1 t2...]`
- go: depth/nodes/movetime/wtime/btime/infinite/searchmoves/perft/mate/ponder
- 输出：info depth ... score cp|mate ... pv（标准格式）；bestmove <4字符坐标>（只有 base，无揭晓）
- 诊断命令 `moves`：输出当前局面全部合法走法（app 可用于交叉校验）
- setoption：Hash(1-16384MB,默认64)、Threads(默认1)、EvalFile、Clear Hash、ChaseRule(none/default/skyrule)
- NNUE：默认 abjchess-20261030.nnue（可执行目录/cwd 查找）；**Android 用 setoption EvalFile 绝对路径**；
  无网时 go 返回 bestmove (none)；isready 前有 info string ISA tier / net type
- 启动 banner：id name AB JChess 1 RB → uciok；无网时 go refuse；readyok 前可能有多条 info string

### 构建与产物（实测）
- NDK r27c、API 29；`src/isa/build_universal.sh android-arm64 OUT`；JOBS=4
- **自建产物 sha256 与官方 release 完全一致**（1cc6e52a…）→ 可复现
- android-arm64 universal 含 armv8+dotprod 两档（恰好覆盖项目现有两个 flavor 的 ABI）
- NNUE 仅 580KB；arm64 二进制 1.8MB → APK 增量 ~2.4MB
- box 冒烟测试（linux-x86-64 二进制）：startpos 搜索、暗子 reveal token、未知吃子、已知吃子、
  池推导全部通过

## 二、集成方案决策

**接入方式：独立可执行子进程 + Binder Service（选定）**
- 备选 A：JNI .so —— AB-JChess 是 stdio UCI 可执行（自带 main/uci_loop），改造成 JNI 需要动上游源码
  且多档位调度（dispatch/ABJ_TIER）依赖独立进程模型；维护成本高
- 备选 B：可执行子进程 —— release 二进制可直接使用；与 :pikafish 相同的进程隔离思想；
  Android 提取 assets → filesDir + setExecutable → ProcessBuilder
- **选 B**：零上游修改、天然进程隔离、更新引擎=换二进制
- 与 Pikafish 完全隔离：独立 Service（android:process=":abjchess"）、独立 AIDL
  （IJieqiService/IJieqiCallback）、独立客户端类；不触碰 ComputerPlayer/UCIEngineBase/Pikafish*

## 三、模块清单

直接复用：GameSetupDialog（先手方/双方配置）、EngineAssets 模式、BoardEditView 的绘制几何/素材/
悬浮窗交互模式、识别管线（明子）、GcLog、Settings、ui_* 主题色。

新增：
1. JieqiGameState（Kotlin）：grid(10×9 Char, X/x/明子)、stm、pools[2][6]、真实暗子分配（仅 App 持有，
   绝不下发引擎）、token 历史、全量快照（悔棋/保存恢复）、FEN/token 序列化
2. JieqiRules：与引擎一致的单子走法生成 + 合法性（明子将军/对将校验；暗子按格角色）；
   引擎 apply 报错时兜底回滚
3. JieqiAssets：引擎二进制+NNUE 提取与校验（sha256）
4. IJieqiService/IJieqiCallback AIDL + JieqiService（:abjchess 进程，ProcessBuilder 子进程，
   stdin/stdout 泵）+ JieqiEngineClient（UCI 会话：初始化握手/position+go/stop/bestmove/info 解析）
5. JieqiBoardView：棋盘渲染（明子/暗子瓦片/行棋方/最近一步/将军提示）+ 交互
6. JieqiActivity：对弈界面（复用 GameSetupDialog/工具栏模式）+ 身份录入对话框（翻子/吃子）
   + 身份池校对面板 + 历史列表 + 保存恢复
7. ScanActivity 揭棋模式：识别明子 + 编辑器新增红/黑暗子棋子类型 + 行棋方设置 + 池推导与校对
8. MainActivity 揭棋入口卡片
9. InformationActivity 致谢补 AB-JChess（GPL-3.0，仓库内附 LICENSE 拷贝）
10. 单元测试：JieqiRules/GameState/token 往返/池推导/快照回退

## 四、扫描模式视角映射（强制，开发中不得反转）

软件 AI = 原棋局中的我；软件玩家 = 原棋局中的对家。
- AI 走 AI 的暗子 → 用户在真实棋盘走并在 App 录入揭晓身份（token+reveal）
- 用户走用户的暗子 → 同上录入揭晓身份
- AI 吃用户的暗子 → 用户在真实棋盘看到被吃身份，录入 victim 身份
- 用户吃 AI 的暗子 → 用户看不到身份 → token 省略 victim（引擎原生未知吃子）
- 未知身份永不猜测、不从池乱扣；未知被吃暗子计人「身份不明被吃数」（阶段6 校对面板展示）

## 五、风险与对策
- 引擎崩溃：onFinished → 客户端自动重启一次并重发当前 position（同现有 reportEngineError 模式）
- 非法局面导入：开局前校验（双将/暗子位置/池≥暗子数/行棋方）+ 可视化提示与编辑入口
- 池矛盾（中途导入）：池推导基于盘面明子 + 校对面板手动修正；引擎约束（池≥暗子、≤初始）由
  App 预检，不满足时引导用户修正而不是造数据
- 长任务状态错乱：客户端命令队列串行 + position 总是全量重发（引擎无 undo，悔棋=重发 fen+历史）
- 内存：子进程独立堆；App 端快照轻量（90 字节棋盘+池+分配）
