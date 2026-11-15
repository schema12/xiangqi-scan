package com.zfdang.chess.strategy;

/**
 * Win-seeking utility over Pikafish's UCI_ShowWDL output (design C).
 *
 *   U_alpha(m) = w_m + q(alpha) * d_m        (loss probability never adds utility)
 *   q(alpha)   = 0.5 * (1 - alpha)           q(0)=0.5, q(1)=0
 *   DeltaU(m)  = U_alpha(m) - U_alpha(best)  (negative when the candidate is worse)
 *
 * Loss risk is controlled independently by the layer-1 hard gates (delta envelope,
 * mate filter, sacrifice classification), NOT by this utility. W values are Pikafish's
 * engine self-play model (fishtest LTC fit) and must never be presented as true
 * human win rates.
 */
public final class WdlUtility {

    private WdlUtility() {
    }

    /**
     * A wdl triple is usable only when it is well-formed. Out-of-fit material phases
     * (Pikafish model fitted for material 17..110) are rejected by the caller via
     * {@link #usableForDecision} depth/confidence gating plus this sanity check.
     */
    public static boolean wellFormed(int[] wdl) {
        if (wdl == null || wdl.length != 3) return false;
        int sum = 0;
        for (int v : wdl) {
            if (v < 0 || v > 1000) return false;
            sum += v;
        }
        return sum >= 950 && sum <= 1050;
    }

    /** U_alpha for a wdl triple; input assumed well-formed. */
    public static double utility(int[] wdl, double qDraw) {
        double w = wdl[0] / 1000.0;
        double d = wdl[1] / 1000.0;
        return w + qDraw * d;
    }

    /**
     * Direction-correct utility delta (candidate minus baseline). Returns 0 (with
     * {@code available=false} in callers) when either side lacks a usable wdl.
     */
    public static Double deltaU(int[] baselineWdl, int[] candidateWdl, double qDraw) {
        if (!wellFormed(baselineWdl) || !wellFormed(candidateWdl)) return null;
        return utility(candidateWdl, qDraw) - utility(baselineWdl, qDraw);
    }
}
