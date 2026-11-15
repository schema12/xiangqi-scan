package com.zfdang.chess.strategy;

import com.zfdang.chess.gamelogic.Board;
import com.zfdang.chess.gamelogic.Move;
import com.zfdang.chess.openbook.BookCandidate;
import com.zfdang.chess.strategy.CandidateGenerator.Candidate;
import com.zfdang.chess.strategy.ScoreFusion.Scored;
import com.zfdang.chess.strategy.ScoreFusion.Selection;

import org.petero.droidfish.player.SearchOutcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision state machine (design G.3):
 *
 *   IDLE -> BASELINE_RUNNING -> BASE_DONE(lock bestmove)
 *        -> CANDIDATE_RUNNING[m] -> CANDIDATE_DONE[m] -> ... -> FUSION -> COMMIT
 *   any: cancel() / timeout / engineError -> FALLBACK(locked best) -> TERMINAL
 *
 * Guarantees:
 *  - the baseline bestmove is locked the moment it arrives and is ALWAYS available
 *    as the final answer;
 *  - only one engine search is in flight at any time (the host queues the next
 *    candidate strictly after the previous bestmove);
 *  - one decision commits at most one move (decisionToken), stale/old-position
 *    callbacks are ignored via searchId matching;
 *  - unfinished candidates never enter the ranking (Confidence gate);
 *  - a decision never touches Game state itself: the host performs the single
 *    committed move on the UI thread.
 */
public final class RiskDecisionEngine {

    /** Bridge to the GameController; every method must be safe to call from the
     *  engine monitor thread. */
    public interface Host {
        /** Start the baseline search (user's own go budget, MultiPV=1); returns searchId. */
        int startBaselineSearch();

        /** Start one restricted root search "go movetime X searchmoves <ucci>"; returns searchId. */
        int startRestrictedSearch(String ucci, int movetimeMs);

        /** Send a UCI command while the engine is idle (option toggles only). */
        void sendRawCommand(String uciCommand);

        /** Stop the currently running search (timeout / user cancel). */
        void stopEngineSearch();

        /** Commit the final move exactly once, on the UI thread. */
        void commitMove(String ucci, String reason);

        /** User-facing status text. */
        void status(String message);

        /** Log sink (may be null in tests). */
        PlayLogger logger();
    }

    private enum State {
        IDLE, BASELINE_RUNNING, BASE_DONE, CANDIDATE_RUNNING, FUSION, TERMINAL
    }

    private final Host host;
    private final RiskProfile profile;
    private final Board rootBoard;              // mover to move; owned copy, never mutated
    private final List<BookCandidate> bookCandidates;
    private final long startedAt;

    private State state = State.IDLE;
    private int expectedSearchId = -1;
    private boolean cancelled = false;
    private boolean committed = false;
    private boolean wdlRequested = false;

    private String lockedBest = null;           // permanent fallback, set when baseline arrives
    private UciScoreNormalizer.DecisionScore baselineScore = null;
    private int baselineConfidence = Confidence.NONE;
    private double deltaEff = 0;
    private int perCandidateBudget = 0;
    private long candidateDeadline = 0;

    private List<Candidate> candidates = new ArrayList<>();
    private int candidateIndex = 0;
    private List<Scored> evaluated = new ArrayList<>();

    public RiskDecisionEngine(Host host, RiskProfile profile, Board rootBoard,
                              List<BookCandidate> bookCandidates) {
        this.host = host;
        this.profile = profile;
        this.rootBoard = new Board(rootBoard);
        this.bookCandidates = bookCandidates == null ? new ArrayList<>() : bookCandidates;
        this.startedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Kick off the baseline search. Engine must be idle. */
    public synchronized void begin() {
        if (state != State.IDLE) return;
        state = State.BASELINE_RUNNING;
        host.status("策略分析中(风险 " + profile.riskLevel + ")...");
        // WDL data is only consumed by candidate scoring; skip the option entirely
        // when the candidate pool is disabled (dependency rule, design §三.3).
        wdlRequested = profile.wdlEnabled && profile.candidateSearch;
        if (wdlRequested) {
            host.sendRawCommand("setoption name UCI_ShowWDL value true");
        }
        expectedSearchId = host.startBaselineSearch();
    }

    /** User pressed force-move / stop: abort the chain, keep the locked baseline. */
    public synchronized void cancel() {
        cancelled = true;
        if (state == State.BASELINE_RUNNING || state == State.CANDIDATE_RUNNING
                || state == State.BASE_DONE) {
            host.stopEngineSearch(); // outcome (afterStop) will arrive and finalize
        } else if (state == State.FUSION) {
            // fusion is instant; nothing to stop
        }
    }

    /** Hard terminate without committing (new game / activity teardown). */
    public synchronized void terminate() {
        state = State.TERMINAL;
    }

    public synchronized boolean isActive() {
        return state != State.IDLE && state != State.TERMINAL;
    }

    public synchronized boolean isCommitted() {
        return committed;
    }

    /** Engine died mid-decision. Returns true when this decision handled it. */
    public synchronized boolean onEngineError() {
        if (state == State.TERMINAL || state == State.IDLE) return false;
        if (committed) return true;
        if (lockedBest != null) {
            commit(lockedBest, "engine-error-fallback");
            return true;
        }
        // No baseline yet: let the controller's legacy restart logic take over.
        state = State.TERMINAL;
        return false;
    }

    // ------------------------------------------------------------------ outcome flow

    /** Called on the monitor thread for every completed search. */
    public synchronized void onOutcome(SearchOutcome o) {
        if (state == State.IDLE || state == State.TERMINAL) return;
        if (o == null || o.searchId != expectedSearchId) return; // stale / other-position callback
        if (committed) return;

        if (cancelled) {
            if (o.hasBestMove() && lockedBest == null && state == State.BASELINE_RUNNING) {
                lockedBest = o.bestMove; // interrupted baseline still yields a move
            }
            finishWithLocked("cancelled");
            return;
        }

        switch (state) {
            case BASELINE_RUNNING:
                onBaseline(o);
                break;
            case CANDIDATE_RUNNING:
                onCandidate(o);
                break;
            default:
                break;
        }
    }

    private void onBaseline(SearchOutcome o) {
        if (!o.hasBestMove()) {
            state = State.TERMINAL; // controller legacy error path takes over
            return;
        }
        lockedBest = o.bestMove;
        baselineScore = UciScoreNormalizer.from(o);
        baselineConfidence = Confidence.classify(o);
        state = State.BASE_DONE;

        // Forced lines: nothing to decide.
        if (baselineScore.mateIn > 0) {
            commit(lockedBest, "baseline-mate-in-" + baselineScore.mateIn);
            return;
        }
        if (baselineScore.mateIn < 0) {
            commit(lockedBest, "baseline-getting-mated");
            return;
        }
        if (!profile.hasAttackCandidates() || baselineConfidence < Confidence.BASIC) {
            commit(lockedBest, baselineConfidence < Confidence.BASIC ? "weak-baseline" : "alpha-zero");
            return;
        }
        // 用户开关:额外候选搜索关闭 → 只走基线(原版行为,无额外 searchmoves)。
        if (!profile.candidateSearch) {
            commit(lockedBest, "candidate-search-disabled");
            return;
        }
        // 依赖自动处理:三个评分来源(攻击特征/弃子补偿/WDL)全部关闭时,
        // 候选搜索没有任何可选收益 → 自动跳过,避免无意义耗时。
        if (!profile.attackFeatures && !profile.sacrificeAnalysis && !profile.wdlEnabled) {
            commit(lockedBest, "all-scoring-disabled");
            return;
        }

        // Budgets (design G.1). Baseline keeps the user's own budget; candidates get
        // a capped extra pool. Mode B doubles the candidate pool and must only be
        // compared against a control group given the same extra time.
        long now = System.currentTimeMillis();
        long baselineElapsed = Math.max(1, now - startedAt);
        long tUserEst = profile.enhancedThinking ? baselineElapsed : estimateTUser(baselineElapsed);
        double ratio = profile.enhancedThinking
                ? StrategyParams.MODE_B_CAND_RATIO_NUM
                : StrategyParams.MODE_A_CAND_RATIO;
        int lo = profile.enhancedThinking ? StrategyParams.MODE_B_CAND_BUDGET_MIN
                : StrategyParams.MODE_A_CAND_BUDGET_MIN;
        int hi = profile.enhancedThinking ? StrategyParams.MODE_B_CAND_BUDGET_MAX
                : StrategyParams.MODE_A_CAND_BUDGET_MAX;
        int candPool = (int) Math.max(lo, Math.min(hi, ratio * tUserEst));
        int k = profile.candidateK();
        perCandidateBudget = Math.max(200, candPool / Math.max(1, k));
        candidateDeadline = now + candPool + StrategyParams.FALLBACK_BUFFER_MS;

        // Objective-loss envelope (design B.3): guard clamps, phi modulates.
        deltaEff = computeDeltaEff(baselineScore.cp);

        // Unified opening flow: book candidates are merged, never played directly.
        List<BookCandidate> book = OpeningSelector.filterForMover(
                bookCandidates, profile, deltaEff, rootBoard.bRedGo);

        candidates = CandidateGenerator.generate(rootBoard, lockedBest, book, profile);
        if (candidates.isEmpty()) {
            commit(lockedBest, "no-candidates");
            return;
        }

        state = State.CANDIDATE_RUNNING;
        candidateIndex = 0;
        launchNextCandidate();
    }

    private void onCandidate(SearchOutcome o) {
        Candidate cand = candidates.get(candidateIndex);
        analyzeAndStore(cand, o);
        candidateIndex++;
        // Proven forced win: no later candidate can beat it, stop the pool early.
        if (o.mateIn > 0) {
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        if (candidateIndex < candidates.size() && now + perCandidateBudget <= candidateDeadline) {
            launchNextCandidate();
        } else {
            finish();
        }
    }

    private void launchNextCandidate() {
        long now = System.currentTimeMillis();
        if (now + perCandidateBudget > candidateDeadline) {
            finish();
            return;
        }
        Candidate cand = candidates.get(candidateIndex);
        expectedSearchId = host.startRestrictedSearch(cand.ucci, perCandidateBudget);
    }

    private void analyzeAndStore(Candidate cand, SearchOutcome o) {
        int conf = Confidence.classify(o);
        UciScoreNormalizer.DecisionScore ds = UciScoreNormalizer.from(o);
        // Engine PV starts with the candidate move itself; the feature/sacrifice
        // replay continues AFTER it, so strip ply 0 to avoid applying it twice.
        List<String> continuation = o.pvUcci;
        if (!continuation.isEmpty() && cand.ucci.equals(continuation.get(0))) {
            continuation = continuation.subList(1, continuation.size());
        }
        AttackFeatureEngine.Features feats;
        if (profile.attackFeatures) {
            feats = AttackFeatureEngine.compute(rootBoard, cand.ucci, continuation);
            if (o.mateIn > 0) feats.mateThreat = 1.0;
        } else {
            feats = new AttackFeatureEngine.Features(); // 攻击特征评分关闭:A=0、复杂度=0
        }
        Move mv = new Move(rootBoard);
        mv.fromUCCIString(cand.ucci);
        SacrificeClassifier.Result sac;
        if (profile.sacrificeAnalysis) {
            sac = SacrificeClassifier.classify(
                    rootBoard, mv, continuation, feats.totalAttack(), cand.book, profile);
        } else {
            // 弃子补偿分析关闭:不做弃子分类(C=0)。材料硬闸门仍在第一层独立生效。
            sac = new SacrificeClassifier.Result();
            sac.netLoss = MaterialBalance.worstOpponentCaptureNet(
                    rootBoardAfterMove(mv), rootBoard.bRedGo);
            sac.note = "sacrifice-analysis-disabled";
        }

        Double winUtility = null;
        if (profile.wdlEnabled && baselineScore != null && baselineScore.wdl != null
                && conf >= Confidence.FULL) {
            winUtility = WdlUtility.deltaU(baselineScore.wdl, o.wdl, profile.qDraw());
        }

        Scored s = ScoreFusion.evaluate(cand, ds, feats, sac, conf, profile,
                deltaEff, baselineScore.cp, winUtility,
                profile.openingStyle ? ScoreFusion.openingBonus(cand.book) : 0.0);
        evaluated.add(s);
    }

    private Board rootBoardAfterMove(Move mv) {
        Board after = new Board(rootBoard);
        after.doMove(mv);
        return after;
    }

    private void finish() {
        state = State.FUSION;
        Selection sel = ScoreFusion.select(evaluated, profile);
        if (sel.chosen == null) {
            finishWithLocked(sel.reason);
        } else {
            commit(sel.chosen.candidate.ucci, sel.reason);
        }
    }

    private void finishWithLocked(String reason) {
        if (lockedBest != null) {
            commit(lockedBest, "fallback:" + reason);
        } else {
            state = State.TERMINAL;
        }
    }

    private void commit(String ucci, String reason) {
        if (committed) return;
        committed = true;
        state = State.TERMINAL;
        if (wdlRequested) {
            host.sendRawCommand("setoption name UCI_ShowWDL value false");
        }
        logDecision(ucci, reason);
        host.commitMove(ucci, reason);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * deltaEff = guard ? deltaGuard(r) : deltaMax(r) * phi(cp) with a complexity
     * bonus when behind (design B.3). All cp thresholds are engineering initial values.
     */
    private double computeDeltaEff(double baselineCp) {
        double base;
        if (profile.strengthGuard) {
            base = profile.deltaGuardCp();
        } else {
            double phi;
            if (baselineCp > 150) phi = 0.35;
            else if (baselineCp > 30) phi = 0.75;
            else if (baselineCp >= -30) phi = 1.0;
            else if (baselineCp >= -150) phi = 1.0 + 0.2 * profile.gamma;
            else phi = 0.5;
            base = profile.deltaMaxCp() * phi;
        }
        return Math.min(base, StrategyParams.DELTA_MAX_HARD_CAP);
    }

    /** T_user estimate: the user's movetime when set, else the measured baseline cost. */
    private long estimateTUser(long baselineElapsed) {
        return Math.max(500, Math.min(baselineElapsed, 20000));
    }

    private void logDecision(String finalMove, String reason) {
        PlayLogger logger = host.logger();
        if (logger == null) return;
        StringBuilder sb = new StringBuilder(512);
        long now = System.currentTimeMillis();
        sb.append("{\"ts\":").append(now)
                .append(",\"risk\":").append(profile.riskLevel)
                .append(",\"alpha\":").append(String.format(java.util.Locale.ROOT, "%.3f", profile.alpha))
                .append(",\"guard\":").append(profile.strengthGuard)
                .append(",\"mode\":\"").append(profile.enhancedThinking ? "B" : "A").append('"')
                .append(",\"mover_red\":").append(rootBoard.bRedGo)
                .append(",\"baseline\":\"").append(PlayLogger.escape(lockedBest)).append('"')
                .append(",\"final\":\"").append(PlayLogger.escape(finalMove)).append('"')
                .append(",\"reason\":\"").append(PlayLogger.escape(reason)).append('"')
                .append(",\"committed\":").append(committed)
                .append(",\"cancelled\":").append(cancelled)
                .append(",\"duration_ms\":").append(now - startedAt)
                .append(",\"baseline_cp\":").append(baselineScore == null ? 0 : baselineScore.cp)
                .append(",\"baseline_mate\":").append(baselineScore == null ? 0 : baselineScore.mateIn)
                .append(",\"delta_eff\":").append(String.format(java.util.Locale.ROOT, "%.0f", deltaEff))
                .append(",\"candidates\":[");
        for (int i = 0; i < evaluated.size(); i++) {
            Scored s = evaluated.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"m\":\"").append(PlayLogger.escape(s.candidate.ucci)).append('"')
                    .append(",\"cp\":").append(s.score.cp)
                    .append(",\"mate\":").append(s.score.mateIn)
                    .append(",\"conf\":").append(s.confidence)
                    .append(",\"delta\":").append(s.deltaCp == Integer.MAX_VALUE ? "\"INF\"" : String.format(java.util.Locale.ROOT, "%.0f", s.deltaCp))
                    .append(",\"A\":").append(String.format(java.util.Locale.ROOT, "%.3f", s.features.totalAttack()))
                    .append(",\"C\":").append(String.format(java.util.Locale.ROOT, "%.3f", s.sacrifice == null ? 0 : s.sacrifice.compensation))
                    .append(",\"sac\":\"").append(s.sacrifice == null ? "none" : sacType(s.sacrifice.type)).append('"')
                    .append(",\"U\":").append(s.winUtility == null ? "null" : String.format(java.util.Locale.ROOT, "%.3f", s.winUtility))
                    .append(",\"score\":").append(String.format(java.util.Locale.ROOT, "%.1f", s.softScore))
                    .append(",\"reject\":\"").append(PlayLogger.escape(s.rejectReason == null ? "" : s.rejectReason)).append('"')
                    .append('}');
        }
        sb.append("]}");
        logger.log(sb.toString());
    }

    private static String sacType(int t) {
        switch (t) {
            case SacrificeClassifier.TYPE_A_BLUNDER: return "A";
            case SacrificeClassifier.TYPE_B_TACTICAL: return "B";
            case SacrificeClassifier.TYPE_C_PRESSURE: return "C";
            case SacrificeClassifier.TYPE_D_TRAP: return "D";
            default: return "none";
        }
    }
}
