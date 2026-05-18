package org.algotradex.strategy.samples.trendpullbackv3;

import java.math.BigDecimal;

/**
 * Effective runtime configuration for {@link TrendPullbackV3Strategy}.
 *
 * <p>Trend Pullback v3 is the evolution of the original {@code range-sr-v2} sample. It keeps every
 * core mechanic (H4 trend gate, defended-level confluence, structural stop+target, fixed-USD risk
 * sizing) but introduces several knobs aimed at lifting trade count without compromising the
 * structural-pullback edge:
 *
 * <ul>
 *   <li>{@code patternTier} replaces the freeform {@code minPatternConfidence} number — clearer to
 *       reason about and aligned with the canonical tier-1 vs tier-2 split.
 *   <li>{@code volatilityAdaptiveTolerance} scales the {@code levelTolerancePct} by execution-bar
 *       ATR / price so high-vol stocks get a wider effective tolerance.
 *   <li>{@code pivotSource} explicitly chooses HTF / LTF / HYBRID. HYBRID uses H4 for the trend +
 *       zone gate but execution-timeframe pivots for level proximity + confluence, multiplying
 *       candidate-level density.
 *   <li>{@code allowMidlineWithMaxConfluence} permits MIDLINE-position entries when all four
 *       structural confluence factors line up (compensation for the relaxed zone gate).
 *   <li>{@code allowSecondTouchInCooldown} permits a second entry inside the cooldown window if
 *       the level has been retouched with strictly higher confluence than the first signal.
 *   <li>{@code emitDiagnostics} surfaces per-rejection reasons on every empty bar so the RunSet
 *       UI can show <em>why</em> an instrument produced zero trades.
 * </ul>
 *
 * <p>Values describe signal and intent construction assumptions: H4/LTF lookbacks, level tolerances,
 * cooldown duration, ATR stop multiples, and sample risk sizing. The record itself does not perform
 * validation or convert risk into exchange-specific lots.
 */
record TrendPullbackV3Parameters(
        BigDecimal minTrendAdx,
        PatternTier patternTier,
        int minConfluence,
        BigDecimal atrMultSL,
        BigDecimal atrMultMinRR,
        BigDecimal riskUsdPerTrade,
        PivotSource pivotSource,
        int htfLookback,
        int ltfLookback,
        int pivotLookback,
        int cooldownHours,
        BigDecimal levelTolerancePct,
        BigDecimal midlineTolerancePct,
        boolean volatilityAdaptiveTolerance,
        BigDecimal adaptiveToleranceMin,
        BigDecimal adaptiveToleranceMax,
        boolean allowMidlineWithMaxConfluence,
        boolean allowSecondTouchInCooldown,
        boolean tier2WithMaxConfluenceCountsAsTier1,
        boolean emitDiagnostics
) {

    /**
     * Reversal-pattern strictness tier. Each step down enables additional candle patterns:
     * <ul>
     *   <li>{@code TIER1_STRICT} — only confidence-1.0 patterns: bullish-engulfing,
     *       morning-star, three-soldiers (and bearish equivalents). Matches the original
     *       range-sr-v2 strict default.
     *   <li>{@code TIER1_OR_TIER2} — also accepts piercing/dark-cloud (0.8) and
     *       hammer/shooting-star (0.7). Default for v3.
     *   <li>{@code ALL_PATTERNS} — also accepts doji (0.5). Use with caution; doji at
     *       any level is the weakest reversal signal.
     * </ul>
     */
    enum PatternTier {
        TIER1_STRICT,
        TIER1_OR_TIER2,
        ALL_PATTERNS
    }

    /**
     * Source of pivots used for the defended-level + confluence pipeline.
     * <ul>
     *   <li>{@code HTF} — H4 pivots only (original range-sr-v2 default).
     *   <li>{@code LTF} — execution-timeframe pivots only (more candidate levels, more noise).
     *   <li>{@code HYBRID} — H4 for trend / zone determination + execution-timeframe pivots
     *       for level proximity + confluence + target. Default for v3.
     * </ul>
     */
    enum PivotSource {
        HTF,
        LTF,
        HYBRID
    }

    BigDecimal minPatternConfidence() {
        return switch (patternTier) {
            case TIER1_STRICT -> BigDecimal.ONE;
            case TIER1_OR_TIER2 -> BigDecimal.valueOf(0.7);
            case ALL_PATTERNS -> BigDecimal.valueOf(0.5);
        };
    }
}
