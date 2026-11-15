package com.zfdang.chess.openbook;

/**
 * One opening-book candidate enriched with quality metadata (design H).
 * tag/klass/objEval/refuted may be null/absent when the shipped database predates
 * the enriched schema; callers must treat absent metadata as "unverified" — such
 * entries still go through the same restricted-search verification as ordinary
 * candidates and are never auto-labeled as sound flying-knife openings.
 */
public final class BookCandidate {
    /** UCCI move for the position that was queried. */
    public final String ucci;
    /** Legacy book score (statistics-derived; never a quality verdict by itself). */
    public final int score;
    public final double winRate;
    /** Content tag from the design: normal|aggressive|gambit|trap|sharp, or null. */
    public final String tag;
    /** Pikafish objective evaluation (cp, red POV) of the resulting position, or null. */
    public final Integer objEval;
    /** True when the line is known to be refuted by correct play; never recommended. */
    public final boolean refuted;
    /** Quality class: sound_attack|trap|refuted|offbeat_loss, or null when unclassified. */
    public final String klass;
    /** Human-readable source string. */
    public final String source;

    public BookCandidate(String ucci, int score, double winRate, String tag,
                         Integer objEval, boolean refuted, String klass, String source) {
        this.ucci = ucci;
        this.score = score;
        this.winRate = winRate;
        this.tag = tag;
        this.objEval = objEval;
        this.refuted = refuted;
        this.klass = klass;
        this.source = source;
    }

    public boolean isRefuted() {
        return refuted || "refuted".equals(klass);
    }
}
