package org.algotradex.strategy.samples.rangesrv2;

import java.math.BigDecimal;

/**
 * Effective runtime configuration for {@link RangeSrV2Strategy}.
 * <p>
 * The record is immutable after construction and is populated from provider-validated
 * {@code StrategyParameters}. It deliberately stays package-private because external callers should
 * use the provider schema, not this implementation detail, to configure the strategy.
 * <p>
 * Values describe signal and intent construction assumptions: H4/M15 lookbacks, level tolerances,
 * cooldown duration, ATR stop multiples, and sample risk sizing. The record itself does not perform
 * validation or convert risk into exchange-specific lots.
 */
record RangeSrV2Parameters(
        BigDecimal minTrendAdx,
        BigDecimal minPatternConfidence,
        int minConfluence,
        BigDecimal atrMultSL,
        BigDecimal atrMultMinRR,
        BigDecimal riskUsdPerTrade,
        boolean use15mStructure,
        int htfLookback,
        int ltfLookback,
        int pivotLookback,
        int cooldownHours,
        BigDecimal levelTolerancePct,
        BigDecimal midlineTolerancePct
) {
}
