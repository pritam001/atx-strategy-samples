package org.algotradex.strategy.samples.rangesrv2;

import java.math.BigDecimal;

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
