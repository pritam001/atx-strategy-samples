package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader provider for the EMA trend-structure pullback continuation strategy sample.
 */
public final class EmaTrendStructurePullbackStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "ema-trend-structure-pullback-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    static final String FAST_EMA_PERIOD = "fastEmaPeriod";
    static final String MEDIUM_EMA_PERIOD = "mediumEmaPeriod";
    static final String SLOW_EMA_PERIOD = "slowEmaPeriod";
    static final String SLOPE_LOOKBACK_BARS = "slopeLookbackBars";
    static final String FLAT_SLOPE_THRESHOLD_PCT = "flatSlopeThresholdPct";
    static final String COMPRESSED_SEPARATION_THRESHOLD_PCT = "compressedSeparationThresholdPct";
    static final String EXPANDING_SEPARATION_THRESHOLD_PCT = "expandingSeparationThresholdPct";
    static final String CHOP_CROSS_LOOKBACK_BARS = "chopCrossLookbackBars";
    static final String CHOP_CROSS_COUNT_THRESHOLD = "chopCrossCountThreshold";
    static final String PULLBACK_LOOKBACK_BARS = "pullbackLookbackBars";
    static final String PULLBACK_MIN_BARS = "pullbackMinBars";
    static final String EMA_TOUCH_TOLERANCE_PCT = "emaTouchTolerancePct";
    static final String MAX_DISTANCE_FROM_FAST_EMA_PCT = "maxDistanceFromFastEmaPct";
    static final String IDEAL_DISTANCE_FROM_FAST_EMA_PCT = "idealDistanceFromFastEmaPct";
    static final String MAX_DISTANCE_FROM_MEDIUM_EMA_PCT = "maxDistanceFromMediumEmaPct";
    static final String PRIOR_BREAKOUT_LOOKBACK_BARS = "priorBreakoutLookbackBars";
    static final String TRANSITION_BREAKOUT_LOOKBACK_BARS = "transitionBreakoutLookbackBars";
    static final String MIN_CONFIDENCE = "minConfidence";
    static final String ALLOW_SHORTS = "allowShorts";
    static final String COOLDOWN_BARS = "cooldownBars";

    private static final String INDICATOR_FORMULA_VERSION = "atx-indicator-formula-v1";
    private static final String EMA_TREND_STRUCTURE_FORMULA_VERSION = "ema-trend-structure-v1";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            integer(FAST_EMA_PERIOD, "Fast EMA Period", "Closed-bar period count for EMA20 structure.", 20, 2, 100),
            integer(MEDIUM_EMA_PERIOD, "Medium EMA Period", "Closed-bar period count for EMA50 structure.", 50, 5, 250),
            integer(SLOW_EMA_PERIOD, "Slow EMA Period", "Closed-bar period count for EMA200 structure.", 200, 50, 500),
            integer(SLOPE_LOOKBACK_BARS, "Slope Lookback Bars", "Closed-bar lookback used for EMA slope percentages.", 5, 1, 50),
            decimal(FLAT_SLOPE_THRESHOLD_PCT, "Flat Slope Threshold %", "Slope threshold that separates rising/falling EMA states from flat.", "0.05", "0.00", "5.00"),
            decimal(COMPRESSED_SEPARATION_THRESHOLD_PCT, "Compressed Separation Threshold %", "Maximum EMA-stack separation treated as compression.", "0.50", "0.00", "10.00"),
            decimal(EXPANDING_SEPARATION_THRESHOLD_PCT, "Expanding Separation Threshold %", "EMA-stack separation treated as expanding structure.", "1.50", "0.01", "20.00"),
            integer(CHOP_CROSS_LOOKBACK_BARS, "Chop Cross Lookback Bars", "Recent bar-to-bar transitions used to count EMA20/EMA50 price crosses.", 20, 2, 100),
            integer(CHOP_CROSS_COUNT_THRESHOLD, "Chop Cross Count Threshold", "Reject setups when recent EMA20/EMA50 price crosses reach this count.", 5, 1, 50),
            integer(PULLBACK_LOOKBACK_BARS, "Pullback Lookback Bars", "Maximum age of a pullback touch before the current resumption bar.", 8, 3, 50),
            integer(PULLBACK_MIN_BARS, "Pullback Minimum Bars", "Minimum age of the pullback touch before the current resumption bar.", 2, 1, 20),
            decimal(EMA_TOUCH_TOLERANCE_PCT, "EMA Touch Tolerance %", "Price is treated as approaching EMA20 when the wick reaches within this percentage.", "0.20", "0.00", "5.00"),
            decimal(MAX_DISTANCE_FROM_FAST_EMA_PCT, "Maximum Distance From Fast EMA %", "Hard rejection threshold for current close distance from EMA20.", "3.00", "0.10", "20.00"),
            decimal(IDEAL_DISTANCE_FROM_FAST_EMA_PCT, "Ideal Distance From Fast EMA %", "Upper edge of the ideal EMA20 distance band; lower edge is fixed at 0.10%.", "2.00", "0.10", "10.00"),
            decimal(MAX_DISTANCE_FROM_MEDIUM_EMA_PCT, "Maximum Distance From Medium EMA %", "Risk-location scoring band for current close distance from EMA50.", "6.00", "0.10", "25.00"),
            integer(PRIOR_BREAKOUT_LOOKBACK_BARS, "Prior Breakout Lookback Bars", "Prior high/low lookback used by continuation momentum scoring.", 3, 2, 20),
            integer(TRANSITION_BREAKOUT_LOOKBACK_BARS, "Transition Breakout Lookback Bars", "Prior high lookback required by bullish transition breakout.", 5, 3, 30),
            decimal(MIN_CONFIDENCE, "Minimum Confidence", "Minimum computed setup confidence required to emit a TradeSignal.", "0.70", "0.50", "0.95"),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Emit mirrored bearish pullback continuation signals.", true, false, null, null, List.of()),
            integer(COOLDOWN_BARS, "Cooldown Bars", "Number of closed bars to suppress after an emitted setup event.", 10, 0, 100)
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "EMA Trend Structure Pullback",
            "External EMA20/50/200 trend-structure strategy that emits pullback continuation setup events after confirmation.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.SHORT_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA,
            List.of(
                    emaStudy(20, "fast-ema", true),
                    emaStudy(50, "medium-ema", true),
                    emaStudy(200, "slow-ema", true),
                    new StrategyChartStudy("ema-trend-structure", "EMA Trend Structure", "structure-pane", Map.of(),
                            EMA_TREND_STRUCTURE_FORMULA_VERSION, false,
                            "Visualization only; strategy recomputes EMA trend structure from closed replay bars.")
            )
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        StrategyValidationResult result = StrategyParameterValidator.validate(SCHEMA, parameters);
        if (!result.valid()) {
            return result;
        }

        StrategyParameters effective = result.effectiveParameters();
        List<StrategyValidationIssue> issues = new ArrayList<>();
        int fast = effective.integer(FAST_EMA_PERIOD, 20);
        int medium = effective.integer(MEDIUM_EMA_PERIOD, 50);
        int slow = effective.integer(SLOW_EMA_PERIOD, 200);
        if (!(fast < medium && medium < slow)) {
            issues.add(new StrategyValidationIssue(MEDIUM_EMA_PERIOD, "EMA periods must satisfy fast < medium < slow"));
        }
        if (effective.integer(SLOPE_LOOKBACK_BARS, 5) >= slow) {
            issues.add(new StrategyValidationIssue(SLOPE_LOOKBACK_BARS, "Slope lookback must be smaller than slow EMA period"));
        }
        if (effective.integer(PULLBACK_MIN_BARS, 2) >= effective.integer(PULLBACK_LOOKBACK_BARS, 8)) {
            issues.add(new StrategyValidationIssue(PULLBACK_MIN_BARS, "Pullback minimum bars must be smaller than pullback lookback bars"));
        }
        if (effective.decimal(COMPRESSED_SEPARATION_THRESHOLD_PCT, BigDecimal.valueOf(0.50))
                .compareTo(effective.decimal(EXPANDING_SEPARATION_THRESHOLD_PCT, BigDecimal.valueOf(1.50))) >= 0) {
            issues.add(new StrategyValidationIssue(EXPANDING_SEPARATION_THRESHOLD_PCT, "Expanding separation threshold must be greater than compressed threshold"));
        }
        if (effective.decimal(IDEAL_DISTANCE_FROM_FAST_EMA_PCT, BigDecimal.valueOf(2.00))
                .compareTo(effective.decimal(MAX_DISTANCE_FROM_FAST_EMA_PCT, BigDecimal.valueOf(3.00))) > 0) {
            issues.add(new StrategyValidationIssue(IDEAL_DISTANCE_FROM_FAST_EMA_PCT, "Ideal EMA20 distance must be <= maximum EMA20 distance"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                emaStudy(effectiveParameters.integer(FAST_EMA_PERIOD, 20), "fast-ema", true),
                emaStudy(effectiveParameters.integer(MEDIUM_EMA_PERIOD, 50), "medium-ema", true),
                emaStudy(effectiveParameters.integer(SLOW_EMA_PERIOD, 200), "slow-ema", true),
                new StrategyChartStudy("ema-trend-structure", "EMA Trend Structure", "structure-pane", Map.of(),
                        EMA_TREND_STRUCTURE_FORMULA_VERSION, false,
                        "Visualization only; strategy recomputes EMA trend structure from closed replay bars.")
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid EMA trend structure pullback strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new EmaTrendStructurePullbackStrategy(new EmaTrendStructurePullbackParameters(
                effective.integer(FAST_EMA_PERIOD, 20),
                effective.integer(MEDIUM_EMA_PERIOD, 50),
                effective.integer(SLOW_EMA_PERIOD, 200),
                effective.integer(SLOPE_LOOKBACK_BARS, 5),
                effective.decimal(FLAT_SLOPE_THRESHOLD_PCT, BigDecimal.valueOf(0.05)),
                effective.decimal(COMPRESSED_SEPARATION_THRESHOLD_PCT, BigDecimal.valueOf(0.50)),
                effective.decimal(EXPANDING_SEPARATION_THRESHOLD_PCT, BigDecimal.valueOf(1.50)),
                effective.integer(CHOP_CROSS_LOOKBACK_BARS, 20),
                effective.integer(CHOP_CROSS_COUNT_THRESHOLD, 5),
                effective.integer(PULLBACK_LOOKBACK_BARS, 8),
                effective.integer(PULLBACK_MIN_BARS, 2),
                effective.decimal(EMA_TOUCH_TOLERANCE_PCT, BigDecimal.valueOf(0.20)),
                effective.decimal(MAX_DISTANCE_FROM_FAST_EMA_PCT, BigDecimal.valueOf(3.00)),
                effective.decimal(IDEAL_DISTANCE_FROM_FAST_EMA_PCT, BigDecimal.valueOf(2.00)),
                effective.decimal(MAX_DISTANCE_FROM_MEDIUM_EMA_PCT, BigDecimal.valueOf(6.00)),
                effective.integer(PRIOR_BREAKOUT_LOOKBACK_BARS, 3),
                effective.integer(TRANSITION_BREAKOUT_LOOKBACK_BARS, 5),
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.70)),
                effective.bool(ALLOW_SHORTS, false),
                effective.integer(COOLDOWN_BARS, 10)
        ));
    }

    private static StrategyParameterDefinition integer(String key, String label, String description, int defaultValue, int min, int max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.INTEGER, label, description, true,
                defaultValue, BigDecimal.valueOf(min), BigDecimal.valueOf(max), List.of());
    }

    private static StrategyParameterDefinition decimal(String key, String label, String description, String defaultValue, String min, String max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.DECIMAL, label, description, true,
                new BigDecimal(defaultValue), new BigDecimal(min), new BigDecimal(max), List.of());
    }

    private static StrategyChartStudy emaStudy(int period, String role, boolean required) {
        return new StrategyChartStudy("ema", "EMA", role, Map.of("period", period), INDICATOR_FORMULA_VERSION, required, "");
    }
}
