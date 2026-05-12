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
 * ServiceLoader provider for the EMA trend-structure pullback lifecycle strategy sample.
 */
public final class EmaTrendStructurePullbackStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "ema-trend-structure-pullback-v2";
    public static final String STRATEGY_VERSION = "2.0.0";
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
    static final String RISK_FRACTION = "riskFraction";
    static final String MAX_HOLDING_BARS = "maxHoldingBars";
    static final String STALE_BARS = "staleBars";
    static final String STALE_MIN_R = "staleMinR";
    static final String STOP_MODE = "stopMode";
    static final String ATR_PERIOD = "atrPeriod";
    static final String ATR_STOP_MULTIPLE = "atrStopMultiple";
    static final String MIN_STOP_PCT = "minStopPct";
    static final String MAX_STOP_PCT = "maxStopPct";
    static final String ENABLE_SCALE_OUT = "enableScaleOut";
    static final String SCALE_OUT_AT_R = "scaleOutAtR";
    static final String SCALE_OUT_FRACTION = "scaleOutFraction";
    static final String TRAIL_AFTER_SCALE_OUT = "trailAfterScaleOut";
    static final String ENABLE_SCALE_IN = "enableScaleIn";
    static final String SCALE_IN_AT_R = "scaleInAtR";
    static final String MAX_SCALE_INS = "maxScaleIns";
    static final String SCALE_IN_FRACTION = "scaleInFraction";
    static final String BREAK_EVEN_AFTER_SCALE_OUT = "breakEvenAfterScaleOut";
    static final String EXIT_ON_COMPRESSION = "exitOnCompression";
    static final String EXIT_ON_CHOP = "exitOnChop";

    private static final String INDICATOR_FORMULA_VERSION = "atx-indicator-formula-v1";
    private static final String EMA_TREND_STRUCTURE_FORMULA_VERSION = "ema-trend-structure-v1";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            integer(FAST_EMA_PERIOD, "Fast EMA Period", "Closed-bar period count for EMA20 structure.", 20, 2, 100),
            integer(MEDIUM_EMA_PERIOD, "Medium EMA Period", "Closed-bar period count for EMA50 structure.", 50, 5, 250),
            integer(SLOW_EMA_PERIOD, "Slow EMA Period", "Closed-bar period count for EMA200 structure.", 200, 5, 500),
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
            decimal(MIN_CONFIDENCE, "Minimum Confidence", "Minimum computed setup confidence required to emit a TradeSignal and entry intent.", "0.70", "0.50", "0.95"),
            bool(ALLOW_SHORTS, "Allow Shorts", "Emit mirrored bearish pullback continuation signals.", false),
            integer(COOLDOWN_BARS, "Cooldown Bars", "Number of closed bars to suppress after an emitted setup event.", 10, 0, 100),
            decimal(RISK_FRACTION, "Risk Fraction", "Portfolio risk fraction requested by v2 entry intents.", "0.01", "0.00", "0.02"),
            integer(MAX_HOLDING_BARS, "Max Holding Bars", "Maximum bars the runtime may hold an accepted v2 position.", 48, 1, 500),
            integer(STALE_BARS, "Stale Bars", "Bars held before a weak trade is eligible for stale exit.", 16, 1, 499),
            decimal(STALE_MIN_R, "Stale Minimum R", "Maximum R multiple below which an old trade is considered stale.", "0.25", "-5.00", "10.00"),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Runtime stop model emitted on entry intents.", true, "EMA50_OR_ATR", null, null, List.of("EMA50_OR_ATR")),
            integer(ATR_PERIOD, "ATR Period", "ATR period used by EMA50/ATR structure stop conversion.", 14, 2, 100),
            decimal(ATR_STOP_MULTIPLE, "ATR Stop Multiple", "ATR multiple used around EMA50, pullback, and current-close stop anchors.", "1.50", "0.10", "10.00"),
            decimal(MIN_STOP_PCT, "Minimum Stop Percent", "Lower bound for adaptive stop distance.", "0.50", "0.01", "20.00"),
            decimal(MAX_STOP_PCT, "Maximum Stop Percent", "Upper bound for adaptive stop distance.", "3.00", "0.01", "20.00"),
            bool(ENABLE_SCALE_OUT, "Enable Scale Out", "Whether v2 emits one scale-out intent after the configured R multiple.", true),
            decimal(SCALE_OUT_AT_R, "Scale Out At R", "Current R multiple required for scale-out.", "1.00", "0.01", "10.00"),
            decimal(SCALE_OUT_FRACTION, "Scale Out Fraction", "Open-position fraction requested by scale-out intents.", "0.50", "0.01", "1.00"),
            bool(TRAIL_AFTER_SCALE_OUT, "Trail After Scale Out", "Whether post-scale trailing weakness may close the remaining position.", true),
            bool(ENABLE_SCALE_IN, "Enable Scale In", "Whether v2 may pyramid into renewed pullbacks.", false),
            decimal(SCALE_IN_AT_R, "Scale In At R", "Current R multiple required before scale-in is eligible.", "0.50", "0.01", "10.00"),
            integer(MAX_SCALE_INS, "Maximum Scale Ins", "Maximum number of scale-in intents allowed per position.", 1, 0, 10),
            decimal(SCALE_IN_FRACTION, "Scale In Fraction", "Open-position fraction requested by scale-in intents.", "0.25", "0.01", "1.00"),
            bool(BREAK_EVEN_AFTER_SCALE_OUT, "Break Even After Scale Out", "Exit remainder if a scaled-out trade gives back to breakeven.", true),
            bool(EXIT_ON_COMPRESSION, "Exit On Compression", "Exit losing trades when EMA compression appears.", true),
            bool(EXIT_ON_CHOP, "Exit On Chop", "Exit losing trades when recent price/EMA crosses indicate chop.", true)
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "EMA Trend Structure Pullback Lifecycle",
            "External EMA20/50/200 trend-structure strategy that emits lifecycle trade intents for pullback continuation setups.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.EXIT_INTENT,
                    StrategyCapability.SCALE_OUT_INTENT,
                    StrategyCapability.SCALE_IN_INTENT,
                    StrategyCapability.RISK_AWARE_SIZING,
                    StrategyCapability.PARAMETERIZED
            ),
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
        if (effective.integer(STALE_BARS, 16) >= effective.integer(MAX_HOLDING_BARS, 48)) {
            issues.add(new StrategyValidationIssue(STALE_BARS, "Stale bars must be smaller than max holding bars"));
        }
        if (effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.50))
                .compareTo(effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(3.00))) > 0) {
            issues.add(new StrategyValidationIssue(MIN_STOP_PCT, "Minimum stop percent must be <= maximum stop percent"));
        }
        if (effective.decimal(SCALE_OUT_AT_R, BigDecimal.ONE).signum() <= 0) {
            issues.add(new StrategyValidationIssue(SCALE_OUT_AT_R, "Scale-out R threshold must be positive"));
        }
        if (effective.decimal(SCALE_IN_AT_R, BigDecimal.valueOf(0.50)).signum() <= 0) {
            issues.add(new StrategyValidationIssue(SCALE_IN_AT_R, "Scale-in R threshold must be positive"));
        }
        if (effective.integer(MAX_SCALE_INS, 1) < 0) {
            issues.add(new StrategyValidationIssue(MAX_SCALE_INS, "Maximum scale-ins must be >= 0"));
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
                effective.integer(COOLDOWN_BARS, 10),
                effective.decimal(RISK_FRACTION, BigDecimal.valueOf(0.01)),
                effective.integer(MAX_HOLDING_BARS, 48),
                effective.integer(STALE_BARS, 16),
                effective.decimal(STALE_MIN_R, BigDecimal.valueOf(0.25)),
                EmaTrendStructurePullbackParameters.StopMode.valueOf(effective.string(STOP_MODE, "EMA50_OR_ATR")),
                effective.integer(ATR_PERIOD, 14),
                effective.decimal(ATR_STOP_MULTIPLE, BigDecimal.valueOf(1.50)),
                effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.50)),
                effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(3.00)),
                effective.bool(ENABLE_SCALE_OUT, true),
                effective.decimal(SCALE_OUT_AT_R, BigDecimal.ONE),
                effective.decimal(SCALE_OUT_FRACTION, BigDecimal.valueOf(0.50)),
                effective.bool(TRAIL_AFTER_SCALE_OUT, true),
                effective.bool(ENABLE_SCALE_IN, false),
                effective.decimal(SCALE_IN_AT_R, BigDecimal.valueOf(0.50)),
                effective.integer(MAX_SCALE_INS, 1),
                effective.decimal(SCALE_IN_FRACTION, BigDecimal.valueOf(0.25)),
                effective.bool(BREAK_EVEN_AFTER_SCALE_OUT, true),
                effective.bool(EXIT_ON_COMPRESSION, true),
                effective.bool(EXIT_ON_CHOP, true)
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

    private static StrategyParameterDefinition bool(String key, String label, String description, boolean defaultValue) {
        return new StrategyParameterDefinition(key, StrategyParameterType.BOOLEAN, label, description, true,
                defaultValue, null, null, List.of());
    }

    private static StrategyChartStudy emaStudy(int period, String role, boolean required) {
        return new StrategyChartStudy("ema", "EMA", role, Map.of("period", period), INDICATOR_FORMULA_VERSION, required, "");
    }
}
