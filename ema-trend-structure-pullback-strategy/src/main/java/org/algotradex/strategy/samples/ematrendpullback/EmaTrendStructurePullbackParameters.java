package org.algotradex.strategy.samples.ematrendpullback;

import java.math.BigDecimal;

/**
 * Effective runtime configuration for {@link EmaTrendStructurePullbackStrategy}.
 * <p>
 * The record is immutable and package-private. It is produced only after provider validation so the
 * strategy can read cohesive EMA, pullback, confidence, stop, and lifecycle settings without
 * depending on the generic parameter map during bar processing.
 * <p>
 * It carries intent metadata assumptions such as risk fraction, max holding bars, and scale
 * fractions; it does not validate parameter relationships, reserve capital, or translate fractions
 * into broker orders.
 */
record EmaTrendStructurePullbackParameters(
        int fastEmaPeriod,
        int mediumEmaPeriod,
        int slowEmaPeriod,
        int slopeLookbackBars,
        BigDecimal flatSlopeThresholdPct,
        BigDecimal compressedSeparationThresholdPct,
        BigDecimal expandingSeparationThresholdPct,
        int chopCrossLookbackBars,
        int chopCrossCountThreshold,
        int pullbackLookbackBars,
        int pullbackMinBars,
        BigDecimal emaTouchTolerancePct,
        BigDecimal maxDistanceFromFastEmaPct,
        BigDecimal idealDistanceFromFastEmaPct,
        BigDecimal maxDistanceFromMediumEmaPct,
        int priorBreakoutLookbackBars,
        int transitionBreakoutLookbackBars,
        BigDecimal minConfidence,
        boolean allowShorts,
        int cooldownBars,
        BigDecimal riskFraction,
        int maxHoldingBars,
        int staleBars,
        BigDecimal staleMinR,
        StopMode stopMode,
        int atrPeriod,
        BigDecimal atrStopMultiple,
        BigDecimal minStopPct,
        BigDecimal maxStopPct,
        boolean enableScaleOut,
        BigDecimal scaleOutAtR,
        BigDecimal scaleOutFraction,
        boolean trailAfterScaleOut,
        boolean enableScaleIn,
        BigDecimal scaleInAtR,
        int maxScaleIns,
        BigDecimal scaleInFraction,
        boolean breakEvenAfterScaleOut,
        boolean exitOnCompression,
        boolean exitOnChop
) {
    /**
     * Stop model understood by the sample strategy.
     * <p>
     * The current implementation converts EMA50/ATR-derived structure stops into the platform's
     * percent stop policy before emitting an entry intent.
     */
    enum StopMode {
        EMA50_OR_ATR
    }
}
