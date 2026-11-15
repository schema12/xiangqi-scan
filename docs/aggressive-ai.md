# 激进策略层(Aggressive AI Strategy Layer)

实现依据:`xiangqi-risk-design-final-v2.md`(HEAD `ef8cbe10` 基线审计 + 多轮纠错定稿)。
本文件是开发与标定速查,不是设计文档本体。

## 模块地图(全部在 Java 应用层,Pikafish/JNI/Service 零改动)

```
com/zfdang/chess/strategy/
  StrategyParams        全部工程初值集中地(标定只改这一个类)
  RiskProfile           risk_level 0..100 → α/β/γ 连续映射 + guard + 模式A/B
  UciScoreNormalizer    score cp / score mate / wdl 严格分离,轮走方视角
  WdlUtility            Uα = w + q(α)·d,q(α)=0.5(1-α);ΔU 相对基线,方向正确
  Confidence            候选结果五级可信度(只有 ≥BASIC 进入排序)
  MaterialBalance       真实子力核算(与 CP 完全分离,仅作辅助闸门)
  AttackFeatureEngine   9 项规则攻击特征 + 复杂度启发式
  CandidateGenerator    Rule.PossibleToPositions 全合法候选 + PV 仅首着 + 开局合并
  SacrificeClassifier   弃子 A/B/C/D 分类与补偿验证
  ScoreFusion           五层评分 + 硬闸门 + 近并列多样性
  OpeningSelector       开局候选过滤(refuted/offbeat_loss/trap 门槛)
  RiskDecisionEngine    状态机:基线锁定 → 受限搜索串行 → 融合 → 单次提交
  PlayLogger            JSONL 决策日志(filesDir/strategy_logs/decisions.jsonl)
```

接线点:
- `ComputerPlayer`:修复 `score mate N` 混入 CP、补 `wdl`/bound 解析、支持
  `SearchRequest.goOverride`(候选专用 `go movetime X searchmoves <m>`)、
  新增 `SearchOutcome`/`SearchOutcomeListener`(bestmove 时发射一次)。
- `GameController`:`WAITING_FOR_DECISION` 状态;`startStrategyForward → beginDecision`;
  决策提交走 `commitMove`(UI 线程单次 `computerMovePiece`);
  `stopSearchNow`/`reportEngineError`/`startNewGame`/`startFENGame`/`applyPlayerConfig`
  均有取消与回退路径;risk=0 完全等价原版行为(含旧 random_move 语义)。
- `BHOpenBook/BHDatabase`:资产缺失 → 显式"无库模式"(不再静默失败);
  `queryDetailed` 富查询(tag/obj_eval/refuted/class,旧库自动降级到传统列)。
- 设置 UI:风险滑块(0-100 连续)+ 五档预设名 + 棋力保护开关 + 高级三维覆盖;
  旧"让电脑走法更有变化"更名为"开局库随机选着(与激进策略无关)"。

## 关键约定(TD-1,与设计的偏差记录)

1. **预算**:基线永远使用用户原有 go 命令(不降预算)。候选池 = 模式 A
   `0.4×T_user_est`(夹在 400–2000ms)、模式 B `1×T_user_est`(1000–4000ms),
   `T_user_est` = movetime 或基线实测耗时。因此 movetime 用户的总思考时间
   最多超出用户设定约 2 秒;严格等时 A/B 对测请使用 PlayLogger 的时间字段做
   配对统计,或双方都用模式 B(对照组给同样额外预算)。
2. **PV 约定**:引擎 PV 首着 = 候选着法本身;特征/弃子重放使用去掉首着的续走。
3. **视角**:一次决策内基线与所有候选共用同一根局面(轮走方=引擎方),
   CP/Δ 全部为轮走方视角,红黑无需翻转;`toRedPov` 仅用于日志与开局库比较。
4. **开局库**:命中不再是"直接走棋"。候选进 `CandidateGenerator` 合并后,
   与普通着法一样经过受限搜索复验;稳健档(risk≤20)保持旧路径但无随机比较器。

## 待标定工程初值(StrategyParams)

α 曲线指数、Δmax=200·α、Δ_guard=20+30α、Δfree、九项特征权重、五层分数权重
(100/80/60/20/15/10)、近并列带宽 15、深度门槛 8/12、预算比例 0.4、
弃子分类阈值(-100/-450、恢复 0.6×)等 —— **全部未经对局验证**。
标定流程:红黑互换 A/B(对照组 = risk 0 原版行为),统计见设计文档 J 节。

## 日志 schema

```json
{"ts":..,"risk":..,"alpha":..,"guard":..,"mode":"A","mover_red":..,
 "baseline":"b2e2","final":"h2e2","reason":"max-score","committed":true,
 "cancelled":false,"duration_ms":..,"baseline_cp":..,"baseline_mate":..,
 "delta_eff":..,"candidates":[{"m":"h2e2","cp":-30,"mate":0,"conf":5,
 "delta":30,"A":0.42,"C":0.5,"sac":"C","U":0.03,"score":33.1,"reject":""}]}
```

## 数据授权说明

- 仓库内 27,542 个 XQF 棋谱(去重后 26,795 唯一)是历史汇编出版物,**根 MIT
  只覆盖软件代码,不自动覆盖棋谱内容**;在版权确认之前,不把任何棋谱转换产物
  打包进 APK。`XQFParser/`(walker8088/cchess)为 GPL-3.0+,仅作离线构建工具。
- 开局库 schema 已就绪(`bhobk` 增列 + `opening_line` 表,见设计文档 H.3);
  未来的库构建流程:去重 → 回放 → Zobrist → Pikafish eval 打 obj_eval →
  人工标注 class(sound_attack/trap/refuted/offbeat_loss)→ 打包
  `assets/databases/<自有库名>.obk`。
