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
        int cooldownBars
) {
}
