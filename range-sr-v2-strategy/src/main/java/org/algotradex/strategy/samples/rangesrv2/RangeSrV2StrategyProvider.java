package org.algotradex.strategy.samples.rangesrv2;

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

public final class RangeSrV2StrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "range-sr-v2";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    static final String MIN_TREND_ADX = "minTrendAdx";
    static final String MIN_PATTERN_CONFIDENCE = "minPatternConfidence";
    static final String MIN_CONFLUENCE = "minConfluence";
    static final String ATR_MULT_SL = "atrMultSL";
    static final String ATR_MULT_MIN_RR = "atrMultMinRR";
    static final String RISK_USD_PER_TRADE = "riskUsdPerTrade";
    static final String USE_15M_STRUCTURE = "use15mStructure";
    static final String HTF_LOOKBACK = "htfLookback";
    static final String LTF_LOOKBACK = "ltfLookback";
    static final String PIVOT_LOOKBACK = "pivotLookback";
    static final String COOLDOWN_HOURS = "cooldownHours";
    static final String LEVEL_TOLERANCE_PCT = "levelTolerancePct";
    static final String MIDLINE_TOLERANCE_PCT = "midlineTolerancePct";

    private static final String INDICATOR_FORMULA_VERSION = "atx-indicator-formula-v1";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            decimal(MIN_TREND_ADX, "Minimum H4 ADX", "Minimum ADX(14) required on H4 before trading.", "20", "1", "80"),
            decimal(MIN_PATTERN_CONFIDENCE, "Minimum Pattern Confidence", "Minimum reversal-pattern confidence; 1.0 keeps strict Tier-1 patterns.", "1.0", "0.5", "1.0"),
            integer(MIN_CONFLUENCE, "Minimum Confluence", "Minimum defended-level confluence factors required.", 2, 1, 4),
            decimal(ATR_MULT_SL, "ATR Stop Multiple", "ATR(14) multiple placed beyond the defended structure level.", "1.5", "0.1", "10.0"),
            decimal(ATR_MULT_MIN_RR, "Minimum RR Multiple", "Minimum real-structure target distance in R.", "2.0", "0.5", "10.0"),
            decimal(RISK_USD_PER_TRADE, "Risk USD Per Trade", "Dollar risk used to convert stop distance into requested units.", "1.0", "0.01", "100000.0"),
            bool(USE_15M_STRUCTURE, "Use 15m Structure", "Use M15 pivots for structure instead of the default H4 pivots.", false),
            integer(HTF_LOOKBACK, "H4 Lookback", "Maximum H4 candles used for trend and structure.", 200, 50, 1000),
            integer(LTF_LOOKBACK, "M15 Lookback", "Maximum M15 candles used for pattern and execution checks.", 200, 20, 2000),
            integer(PIVOT_LOOKBACK, "Pivot Lookback", "Confirmed fractal-pivot wing size.", 3, 1, 10),
            integer(COOLDOWN_HOURS, "Cooldown Hours", "Per-instrument setup cooldown after a signal.", 4, 0, 72),
            decimal(LEVEL_TOLERANCE_PCT, "Level Tolerance %", "Fractional proximity tolerance for levels, round numbers, and fibs.", "0.002", "0.0001", "0.05"),
            decimal(MIDLINE_TOLERANCE_PCT, "Midline Tolerance %", "Neutral band around the active range midline.", "0.02", "0.0", "0.20")
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Range S/R v2",
            "H4 structure and M15 reversal strategy with real support/resistance targets and risk-aware sizing.",
            List.of("M15"),
            List.of("EQUITY", "INDEX"),
            List.of("H4"),
            List.of(),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.RISK_AWARE_SIZING,
                    StrategyCapability.PARAMETERIZED
            ),
            SCHEMA,
            List.of(
                    study("adx", "ADX", "h4-trend-strength", Map.of("period", 14, "timeframe", "H4"), true),
                    study("ema", "EMA", "h4-ema50-trend", Map.of("period", 50, "timeframe", "H4"), true),
                    study("atr", "ATR", "m15-stop-buffer", Map.of("period", 14, "timeframe", "M15"), true),
                    study("fractal-pivots", "Fractal Pivots", "structure-levels", Map.of("lookback", 3, "timeframe", "H4"), true)
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
        if (effective.integer(LTF_LOOKBACK, 200) < 20) {
            issues.add(new StrategyValidationIssue(LTF_LOOKBACK, "M15 lookback must be at least 20"));
        }
        if (effective.integer(HTF_LOOKBACK, 200) < 50) {
            issues.add(new StrategyValidationIssue(HTF_LOOKBACK, "H4 lookback must be at least 50"));
        }
        if (effective.integer(PIVOT_LOOKBACK, 3) * 2 + 1 > effective.integer(HTF_LOOKBACK, 200)) {
            issues.add(new StrategyValidationIssue(PIVOT_LOOKBACK, "Pivot lookback is too large for H4 lookback"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                study("adx", "ADX", "h4-trend-strength", Map.of("period", 14, "timeframe", "H4"), true),
                study("ema", "EMA", "h4-ema50-trend", Map.of("period", 50, "timeframe", "H4"), true),
                study("atr", "ATR", "m15-stop-buffer", Map.of("period", 14, "timeframe", "M15"), true),
                study("fractal-pivots", "Fractal Pivots", "structure-levels",
                        Map.of("lookback", effectiveParameters.integer(PIVOT_LOOKBACK, 3), "timeframe", "H4"), true)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Range S/R v2 strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new RangeSrV2Strategy(new RangeSrV2Parameters(
                effective.decimal(MIN_TREND_ADX, BigDecimal.valueOf(20)),
                effective.decimal(MIN_PATTERN_CONFIDENCE, BigDecimal.ONE),
                effective.integer(MIN_CONFLUENCE, 2),
                effective.decimal(ATR_MULT_SL, BigDecimal.valueOf(1.5)),
                effective.decimal(ATR_MULT_MIN_RR, BigDecimal.valueOf(2.0)),
                effective.decimal(RISK_USD_PER_TRADE, BigDecimal.ONE),
                effective.bool(USE_15M_STRUCTURE, false),
                effective.integer(HTF_LOOKBACK, 200),
                effective.integer(LTF_LOOKBACK, 200),
                effective.integer(PIVOT_LOOKBACK, 3),
                effective.integer(COOLDOWN_HOURS, 4),
                effective.decimal(LEVEL_TOLERANCE_PCT, BigDecimal.valueOf(0.002)),
                effective.decimal(MIDLINE_TOLERANCE_PCT, BigDecimal.valueOf(0.02))
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

    private static StrategyChartStudy study(String indicatorId, String displayName, String role, Map<String, Object> parameters, boolean required) {
        return new StrategyChartStudy(indicatorId, displayName, role, parameters, INDICATOR_FORMULA_VERSION, required, "");
    }
}
