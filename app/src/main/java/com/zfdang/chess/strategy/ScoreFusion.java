package com.zfdang.chess.strategy;

import com.zfdang.chess.openbook.BookCandidate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Five-layer scoring (design D). CP is used ONLY by the layer-1 hard gates and never
 * re-enters the soft score; WDL appears only in layer 4; material only in gates and
 * sacrifice classification; the attack features (layer 2) and sacrifice compensation
 * (layer 3) never count the same tactical fact twice. All weights are engineering
 * initial values awaiting match calibration.
 */
public final class ScoreFusion {

    /** A fully analyzed candidate. */
    public static final class Scored {
        public final CandidateGenerator.Candidate candidate;
        public final UciScoreNormalizer.DecisionScore score;
        public final AttackFeatureEngine.Features features;
        public final SacrificeClassifier.Result sacrifice;
        public final int confidence;
        public final double deltaCp;      // best - candidate, mover POV (cp only)
        public final Double winUtility;   // DeltaU or null when WDL unusable
        public double softScore;          // computed by evaluate()
        public String rejectReason;

        Scored(CandidateGenerator.Candidate candidate, UciScoreNormalizer.DecisionScore score,
               AttackFeatureEngine.Features features, SacrificeClassifier.Result sacrifice,
               int confidence, double deltaCp, Double winUtility) {
            this.candidate = candidate;
            this.score = score;
            this.features = features;
            this.sacrifice = sacrifice;
            this.confidence = confidence;
            this.deltaCp = deltaCp;
            this.winUtility = winUtility;
        }
    }

    /** Selection outcome: the chosen candidate, or null to keep the baseline move. */
    public static final class Selection {
        public final Scored chosen;      // null -> baseline
        public final String reason;

        Selection(Scored chosen, String reason) {
            this.chosen = chosen;
            this.reason = reason;
        }
    }

    private static final SecureRandom DIVERSITY_RANDOM = new SecureRandom();

    private ScoreFusion() {
    }

    /**
     * Layer 1 (hard gates) + layers 2..5 soft score for one candidate.
     *
     * @param profile        active profile
     * @param deltaEff       effective objective-loss envelope already modulated by
     *                       guard/phi (computed by the engine once per decision)
     * @param baselineCp     baseline cp (mover POV) for the complexity-phase gate
     */
    public static Scored evaluate(CandidateGenerator.Candidate cand,
                                  UciScoreNormalizer.DecisionScore candScore,
                                  AttackFeatureEngine.Features features,
                                  SacrificeClassifier.Result sacrifice,
                                  int confidence,
                                  RiskProfile profile,
                                  double deltaEff,
                                  double baselineCp,
                                  Double winUtility,
                                  double openingBonus) {
        Scored s = new Scored(cand, candScore, features, sacrifice, confidence,
                UciScoreNormalizer.deltaCp(
                        new UciScoreNormalizer.DecisionScore((int) Math.round(baselineCp), 0, null, true, false),
                        candScore),
                winUtility);

        // ---- layer 1: hard conditions ----
        if (confidence < Confidence.BASIC) {
            s.rejectReason = "confidence=" + Confidence.name(confidence);
            return s;
        }
        if (candScore.mateIn < 0) {
            s.rejectReason = "mated-line";
            return s;
        }
        if (candScore.mateIn > 0) {
            // Proven forced win: bypass soft scoring entirely.
            s.softScore = 1_000_000.0;
            return s;
        }
        if (!candScore.valid || candScore.inexact) {
            s.rejectReason = "invalid-or-bounded-score";
            return s;
        }
        if (s.deltaCp > deltaEff) {
            s.rejectReason = String.format("delta %.0f > deltaEff %.0f", s.deltaCp, deltaEff);
            return s;
        }
        if (s.sacrifice != null && s.sacrifice.type == SacrificeClassifier.TYPE_A_BLUNDER) {
            s.rejectReason = "sacrifice-class-A:" + s.sacrifice.note;
            return s;
        }
        if (s.sacrifice != null && s.sacrifice.netLoss <= -StrategyParams.MATERIAL_LOSS_GATE
                && features.totalAttack() < StrategyParams.MATERIAL_GATE_ATTACK_FLOOR) {
            s.rejectReason = "material-gate";
            return s;
        }
        if (profile.strengthGuard && s.sacrifice != null
                && s.sacrifice.type == SacrificeClassifier.TYPE_C_PRESSURE
                && confidence < Confidence.FULL) {
            // Guard mode: unproven sustained-pressure sacrifices must not replace the baseline.
            s.rejectReason = "guard-requires-full-confidence";
            return s;
        }

        // ---- layers 2..5: soft score (CP deliberately absent) ----
        double a = features.totalAttack();
        double c = s.sacrifice == null ? 0.0 : s.sacrifice.compensation;
        double u = winUtility == null ? 0.0 : winUtility;
        // Attack-merit gate: a candidate with no attack content, no compensation and no
        // win-utility gain must NEVER outscore the baseline through side bonuses
        // (principle: deviation requires a verified attacking opportunity).
        double merit = a + c + Math.max(0.0, u);
        double complexity = features.complexity;
        // Complexity counts only in balanced/worse positions; ahead we avoid needless risks.
        boolean balancedOrWorse = baselineCp <= 30.0;
        double cx = (balancedOrWorse && merit > 0.0) ? complexity : 0.0;

        double score = StrategyParams.SCORE_W_ATTACK * a
                * (StrategyParams.ATTACK_BASE_GAIN + StrategyParams.ATTACK_ALPHA_GAIN * profile.alpha)
                + StrategyParams.SCORE_W_COMPENSATION * c * profile.alpha
                + StrategyParams.SCORE_W_WIN_UTILITY * u
                + StrategyParams.SCORE_W_COMPLEXITY * cx
                + StrategyParams.SCORE_W_OPENING * openingBonus * profile.beta;
        s.softScore = score;
        return s;
    }

    /**
     * Final pick. Diversity (gamma) rotates only among the top near-tied candidates;
     * a weak move can never win through randomness. Null result -> keep baseline.
     */
    public static Selection select(List<Scored> evaluated, RiskProfile profile) {
        List<Scored> valid = new ArrayList<>();
        for (Scored s : evaluated) {
            if (s.rejectReason == null) valid.add(s);
        }
        if (valid.isEmpty()) {
            return new Selection(null, "no-valid-candidate");
        }
        Scored best = valid.get(0);
        for (Scored s : valid) {
            if (s.softScore > best.softScore) best = s;
        }
        if (best.softScore <= 0.0) {
            return new Selection(null, "max-score<=0-keep-baseline");
        }
        // Diversity: only among near-ties of the best score, only when gamma > 0.
        if (profile.gamma > 0.0) {
            List<Scored> ties = new ArrayList<>();
            for (Scored s : valid) {
                if (best.softScore - s.softScore <= StrategyParams.NEAR_TIE_BAND) ties.add(s);
            }
            if (ties.size() > 1) {
                int n = Math.min(ties.size(), StrategyParams.NEAR_TIE_TOP_N);
                Scored pick = ties.get(DIVERSITY_RANDOM.nextInt(n));
                if (pick != best) {
                    return new Selection(pick, "diversity-near-tie");
                }
            }
        }
        return new Selection(best, "max-score");
    }

    /** Opening bonus from a book entry (design H.5); 0 for non-book candidates. */
    public static double openingBonus(BookCandidate book) {
        if (book == null) return StrategyParams.OPENING_BONUS_UNTAGGED;
        if ("sound_attack".equals(book.klass)) return StrategyParams.OPENING_BONUS_SOUND;
        if ("trap".equals(book.klass)) return StrategyParams.OPENING_BONUS_TRAP;
        return StrategyParams.OPENING_BONUS_UNTAGGED;
    }
}
