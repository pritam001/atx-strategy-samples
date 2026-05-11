package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;

import java.util.Map;

final class DoflamingoChartStudies {
    private static final String FORMULA_VERSION = "atx-indicator-formula-v1";

    private DoflamingoChartStudies() {
    }

    static StrategyChartStudy ichimoku(String role, boolean required) {
        return new StrategyChartStudy(
                "ichimoku",
                "Ichimoku",
                role,
                Map.of(
                        "conversionPeriod", DoflamingoIndicatorMath.ICHIMOKU_CONVERSION_PERIOD,
                        "basePeriod", DoflamingoIndicatorMath.ICHIMOKU_BASE_PERIOD,
                        "spanBPeriod", DoflamingoIndicatorMath.ICHIMOKU_SPAN_B_PERIOD,
                        "displacement", DoflamingoIndicatorMath.ICHIMOKU_DISPLACEMENT
                ),
                FORMULA_VERSION,
                required,
                ""
        );
    }

    static StrategyChartStudy ema(int period, String role, boolean required) {
        return supported("ema", "EMA", role, Map.of("period", period), required);
    }

    static StrategyChartStudy sma(int period, String role, boolean required) {
        return supported("sma", "SMA", role, Map.of("period", period), required);
    }

    static StrategyChartStudy macd(int fastPeriod, int slowPeriod, int signalPeriod, String role, boolean required) {
        return supported("macd", "MACD", role, Map.of(
                "fastPeriod", fastPeriod,
                "slowPeriod", slowPeriod,
                "signalPeriod", signalPeriod
        ), required);
    }

    static StrategyChartStudy unsupported(String indicatorId, String displayName, String role, Map<String, Object> parameters, boolean required) {
        return new StrategyChartStudy(
                indicatorId,
                displayName,
                role,
                parameters,
                FORMULA_VERSION,
                required,
                "Used by the Doflamingo strategy but not part of the initial Web chart-study registry."
        );
    }

    private static StrategyChartStudy supported(String indicatorId, String displayName, String role, Map<String, Object> parameters, boolean required) {
        return new StrategyChartStudy(indicatorId, displayName, role, parameters, FORMULA_VERSION, required, "");
    }
}
