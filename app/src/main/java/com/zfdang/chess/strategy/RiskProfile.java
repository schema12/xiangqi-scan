package com.zfdang.chess.strategy;

import com.zfdang.chess.Settings;

/**
 * Continuous risk profile derived from risk_level 0..100 (design B).
 *
 * Three independent dimensions (alpha/beta/gamma) are continuous functions of the
 * slider; the five UI presets are only shortcut values, never internal branches.
 * strength_guard is strictly orthogonal: it clamps the objective-loss envelope
 * (delta) but never rescales alpha/beta/gamma, so different risk levels still show
 * different aggression while the guard ceiling holds.
 */
public final class RiskProfile {

    public final int riskLevel;
    public final double alpha;       // attack risk appetite, 0..1
    public final double beta;        // offbeat-opening appetite, 0..1
    public final double gamma;       // move diversity, 0..1
    public final boolean strengthGuard;
    public final boolean enhancedThinking; // true = mode B (2x budget, opt-in)
    public final boolean wdlEnabled;
    // ---- user-facing feature switches (design: 增量开发任务 §三) ----
    public final boolean candidateSearch;    // 额外候选搜索
    public final boolean attackFeatures;     // 攻击特征评分
    public final boolean sacrificeAnalysis;  // 弃子补偿分析
    public final boolean aggressiveBook;     // 激进开局库
    public final boolean openingStyle;       // 开局风格偏好
    public final boolean diversityEnabled;   // 多样性评分
    public final boolean strategyEnabled;    // 总开关

    private RiskProfile(int riskLevel, double alpha, double beta, double gamma,
                        boolean strengthGuard, boolean enhancedThinking, boolean wdlEnabled,
                        boolean candidateSearch, boolean attackFeatures, boolean sacrificeAnalysis,
                        boolean aggressiveBook, boolean openingStyle, boolean diversityEnabled,
                        boolean strategyEnabled) {
        this.riskLevel = riskLevel;
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.strengthGuard = strengthGuard;
        this.enhancedThinking = enhancedThinking;
        this.wdlEnabled = wdlEnabled;
        this.candidateSearch = candidateSearch;
        this.attackFeatures = attackFeatures;
        this.sacrificeAnalysis = sacrificeAnalysis;
        this.aggressiveBook = aggressiveBook;
        this.openingStyle = openingStyle;
        this.diversityEnabled = diversityEnabled;
        this.strategyEnabled = strategyEnabled;
    }

    public static RiskProfile fromSettings(Settings s) {
        int risk = clamp(s.getRiskLevel(), 0, 100);
        double r = risk / 100.0;

        double alpha = alphaFromRisk(r);
        double beta = betaFromRisk(r);
        double gamma = gammaFromRisk(r);

        // Advanced overrides take precedence when enabled (0..100 slider -> 0..1).
        int aO = s.getAlphaOverride();
        int bO = s.getBetaOverride();
        int gO = s.getGammaOverride();
        if (aO >= 0) alpha = aO / 100.0;
        if (bO >= 0) beta = bO / 100.0;
        if (gO >= 0) gamma = gO / 100.0;

        return new RiskProfile(risk, alpha, beta, gamma,
                s.getStrengthGuard(),
                "B".equals(s.getThinkingMode()),
                s.getShowWdl(),
                s.getCandidateSearchEnabled(),
                s.getAttackFeaturesEnabled(),
                s.getSacrificeAnalysisEnabled(),
                s.getAggressiveBookEnabled(),
                s.getOpeningStyleEnabled(),
                s.getDiversityEnabled(),
                s.getStrategyEnabled());
    }

    /** Test/analysis factory with explicit values; every module switch defaults to on. */
    public static RiskProfile of(int riskLevel, double alpha, double beta, double gamma,
                                 boolean strengthGuard) {
        return new RiskProfile(riskLevel, alpha, beta, gamma, strengthGuard, false, true,
                true, true, true, true, true, true, true);
    }

    /** Test/analysis factory: derive from an existing profile with explicit module switches. */
    public static RiskProfile withSwitches(RiskProfile base, boolean candidateSearch, boolean wdlEnabled,
                                           boolean attackFeatures, boolean sacrificeAnalysis,
                                           boolean aggressiveBook, boolean openingStyle,
                                           boolean diversityEnabled) {
        return new RiskProfile(base.riskLevel, base.alpha, base.beta, base.gamma,
                base.strengthGuard, base.enhancedThinking, wdlEnabled,
                candidateSearch, attackFeatures, sacrificeAnalysis,
                aggressiveBook, openingStyle, diversityEnabled, base.strategyEnabled);
    }

    /** alpha = ((r - 0.20)/0.80)^1.2 for r > 0.20, else 0 (design B.1). */
    public static double alphaFromRisk(double r) {
        if (r <= StrategyParams.ALPHA_RISK_FLOOR) return 0.0;
        double t = (r - StrategyParams.ALPHA_RISK_FLOOR) / (1.0 - StrategyParams.ALPHA_RISK_FLOOR);
        return Math.pow(t, StrategyParams.ALPHA_CURVE_EXP);
    }

    /** beta = (r - 0.15)/0.85 for r > 0.15, else 0. */
    public static double betaFromRisk(double r) {
        if (r <= StrategyParams.BETA_RISK_FLOOR) return 0.0;
        return (r - StrategyParams.BETA_RISK_FLOOR) / (1.0 - StrategyParams.BETA_RISK_FLOOR);
    }

    /** gamma = 0.7 * smoothstep(r), smoothstep(t) = t*t*(3-2t). */
    public static double gammaFromRisk(double r) {
        double t = clamp(r, 0.0, 1.0);
        return StrategyParams.GAMMA_MAX * t * t * (3.0 - 2.0 * t);
    }

    /** Candidate count for restricted searches, 0 when alpha == 0, else 1..5. */
    public int candidateK() {
        if (alpha <= 0.0) return 0;
        return 1 + (int) Math.floor(4.0 * alpha);
    }

    /** Objective-loss ceiling without guard, cp: 200 * alpha (never above the hard cap). */
    public double deltaMaxCp() {
        return Math.min(StrategyParams.DELTA_MAX_HARD_CAP, StrategyParams.DELTA_MAX_HARD_CAP * alpha);
    }

    /** Objective-loss ceiling with guard, cp: 20 + 30*alpha (design B.3). */
    public double deltaGuardCp() {
        return StrategyParams.DELTA_GUARD_BASE + StrategyParams.DELTA_GUARD_SPAN * alpha;
    }

    /** Draw weight in the win-seeking utility U = w + q(alpha)*d (design C). */
    public double qDraw() {
        return 0.5 * (1.0 - alpha);
    }

    /** Traps are allowed only at high risk with the guard off (design F). */
    public boolean allowsTraps() {
        return !strengthGuard && alpha >= StrategyParams.TRAP_MIN_ALPHA;
    }

    /** True when the aggressive layer must run at all (alpha > 0). */
    public boolean hasAttackCandidates() {
        return alpha > 0.0;
    }

    public static String presetName(int risk) {
        if (risk <= 20) return "稳健";
        if (risk <= 40) return "均衡";
        if (risk <= 60) return "主动进攻";
        if (risk <= 85) return "激进争胜";
        return "极端冒险";
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
