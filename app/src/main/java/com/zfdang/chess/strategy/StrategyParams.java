package com.zfdang.chess.strategy;

/**
 * Central home of every tunable of the aggressive-strategy layer.
 *
 * IMPORTANT: every numeric value in this file is an ENGINEERING INITIAL VALUE
 * (工程初始值) chosen from the design document xiangqi-risk-design-final-v2.md.
 * None of them has been validated by rated games yet; calibration happens via the
 * A/B match harness (see docs/aggressive-ai.md). Keep every threshold here so
 * future calibration only touches this class.
 */
public final class StrategyParams {

    // ---- continuous risk mapping (design B.1) ----
    public static final double ALPHA_CURVE_EXP = 1.2;      // alpha = ((r-0.20)/0.80)^1.2
    public static final double ALPHA_RISK_FLOOR = 0.20;    // risk below 20% -> alpha 0
    public static final double BETA_RISK_FLOOR = 0.15;
    public static final double GAMMA_MAX = 0.70;           // gamma = 0.7 * smoothstep(r)

    // ---- objective-loss envelope (design B.3) ----
    public static final int DELTA_MAX_HARD_CAP = 200;      // cp, absolute ceiling for any risk
    public static final int DELTA_GUARD_BASE = 20;         // guard-mode floor, cp
    public static final int DELTA_GUARD_SPAN = 30;         // guard-mode span with alpha, cp

    // ---- material gate (design D layer-1; auxiliary signal only) ----
    public static final int MATERIAL_PAWN = 100;
    public static final int MATERIAL_ADVISOR = 120;
    public static final int MATERIAL_ELEPHANT = 120;
    public static final int MATERIAL_KNIGHT = 450;
    public static final int MATERIAL_CANNON = 450;
    public static final int MATERIAL_ROOK = 900;
    public static final int MATERIAL_LOSS_GATE = 450;      // net loss <= -450 && attack<0.35 -> reject
    public static final int SACRIFICE_LOSS_MIN = 100;      // net loss <= -100 counts as a sacrifice
    public static final double MATERIAL_GATE_ATTACK_FLOOR = 0.35;

    // ---- five-layer score weights (design D; heuristic initial values) ----
    public static final int SCORE_W_ATTACK = 100;
    public static final int SCORE_W_COMPENSATION = 80;
    public static final int SCORE_W_WIN_UTILITY = 60;
    public static final int SCORE_W_COMPLEXITY = 20;
    public static final int SCORE_W_OPENING = 15;
    public static final int SCORE_W_DIVERSITY = 10;
    public static final double ATTACK_BASE_GAIN = 0.4;     // 100*A*(0.4+0.6*alpha)
    public static final double ATTACK_ALPHA_GAIN = 0.6;
    public static final double NEAR_TIE_BAND = 15.0;       // diversity only inside this band
    public static final int NEAR_TIE_TOP_N = 3;            // rotate routes among top-3 only

    // ---- attack feature weights (design D layer-2; sum = 1.0) ----
    public static final double W_CHECK_CHAIN = 0.18;
    public static final double W_MATE_THREAT = 0.20;
    public static final double W_KING_ZONE = 0.14;
    public static final double W_DOUBLE_ATTACK = 0.12;
    public static final double W_PIN_SKEWER = 0.08;
    public static final double W_DISCOVERED = 0.06;
    public static final double W_OPEN_FILE = 0.06;
    public static final double W_TEMPO = 0.08;
    public static final double W_DEFENDER_PULL = 0.08;

    // ---- candidate search confidence (design G.2) ----
    public static final int CONF_DEPTH_MIN = 8;            // level >=4 needs this depth
    public static final int CONF_DEPTH_FULL = 12;          // level 5 needs this depth
    public static final int CONF_INTERRUPTED_MIN_DEPTH = 4;

    // ---- wall-clock budgets (design G.1; ms) ----
    public static final double MODE_A_CAND_RATIO = 0.4;    // candidates get 0.4x T_user_est
    public static final int MODE_A_CAND_BUDGET_MIN = 400;
    public static final int MODE_A_CAND_BUDGET_MAX = 2000;
    public static final int MODE_B_CAND_RATIO_NUM = 1;     // mode B: candidates get 1x T_user_est
    public static final int MODE_B_CAND_BUDGET_MIN = 1000;
    public static final int MODE_B_CAND_BUDGET_MAX = 4000;
    public static final int DEFAULT_T_USER_EST = 2500;     // depth-mode estimate before measuring
    public static final int FALLBACK_BUFFER_MS = 500;

    // ---- opening book classes (design H.4) ----
    public static final double OPENING_BONUS_SOUND = 0.6;
    public static final double OPENING_BONUS_TRAP = 0.4;
    public static final double OPENING_BONUS_UNTAGGED = 0.0;
    public static final int OBJ_EVAL_SOUND_FLOOR = -50;    // cp; below this not "sound"
    public static final int OBJ_EVAL_OFFBEAT_LOSS = -150;  // cp; plain offbeat losing line

    // ---- trap usage (design F) ----
    public static final double TRAP_MIN_ALPHA = 0.6;       // traps only above this alpha
    public static final double SAC_COMPENSATION_MIN = 0.4; // B-class PV recovery fraction

    // ---- misc ----
    public static final int BOOK_QUERY_LIMIT = 8;
    public static final int LOG_MAX_BYTES = 2 * 1024 * 1024;

    private StrategyParams() {
    }

    /** Piece material value for the auxiliary material gate only (never mixed with cp). */
    public static int materialValue(int pieceType) {
        switch (pieceType) {
            case 5: case 12: return MATERIAL_ROOK;      // WJU / BJU
            case 4: case 11: return MATERIAL_KNIGHT;    // WMA / BMA
            case 6: case 13: return MATERIAL_CANNON;    // WPAO / BPAO
            case 2: case 9: return MATERIAL_ADVISOR;    // WSHI / BSHI
            case 3: case 10: return MATERIAL_ELEPHANT;  // WXIANG / BXIANG
            case 7: case 14: return MATERIAL_PAWN;      // WBING / BZU
            default: return 0;                          // kings/empty never "captured"
        }
    }
}
