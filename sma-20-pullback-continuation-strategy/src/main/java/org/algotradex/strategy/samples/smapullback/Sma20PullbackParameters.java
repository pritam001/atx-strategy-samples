package org.algotradex.strategy.samples.smapullback;

import java.math.BigDecimal;

record Sma20PullbackParameters(
        int fastSmaPeriod,
        int slowSmaPeriod,
        int slopeLookbackBars,
        BigDecimal minSma20SlopePct,
        BigDecimal touchTolerancePct,
        BigDecimal maxEntryExtensionPct,
        int consolidationLookbackBars,
        int cooldownBars,
        BigDecimal minConfidence,
        boolean allowShorts,
        boolean useSma200ObstacleFilter
) {
}
