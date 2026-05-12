package org.algotradex.strategy.samples.smapullback;

import java.math.BigDecimal;

/**
 * Effective runtime configuration for {@link Sma20PullbackContinuationStrategy}.
 * <p>
 * Instances are immutable value carriers produced after provider validation. The record does not
 * validate cross-field invariants itself and is package-private so external callers configure the
 * strategy through the provider schema rather than this implementation detail.
 */
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
