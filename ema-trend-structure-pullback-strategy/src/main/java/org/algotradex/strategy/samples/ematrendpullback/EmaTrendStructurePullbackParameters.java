package org.algotradex.strategy.samples.ematrendpullback;

import java.math.BigDecimal;

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
    enum StopMode {
        EMA50_OR_ATR
    }
}
