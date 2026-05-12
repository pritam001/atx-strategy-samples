package org.algotradex.strategy.samples.doflamingov3;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
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
 * ServiceLoader provider for the Doflamingo Ichimoku momentum 002 beta port.
 */
public final class DoflamingoIchimokuMo002BetaStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-ichimoku-mo-002-beta-v3";
    public static final String STRATEGY_VERSION = "3.0.0";
    public static final String PROVIDER_ID = "doflamingo-v3-strategy-packs";

    static final String ENTRY_MODE = "entryMode";
    static final String MIN_CONFIDENCE = "minConfidence";
    static final String TREND_AVERAGE_LOOKBACK = "trendAverageLookback";
    static final String MAX_HOLDING_BARS = "maxHoldingBars";
    static final String RISK_FRACTION = "riskFraction";
    static final String ENABLE_PROTECTIVE_STOP = "enableProtectiveStop";
    static final String STOP_MODE = "stopMode";
    static final String ATR_PERIOD = "atrPeriod";
    static final String ATR_STOP_MULTIPLE = "atrStopMultiple";
    static final String CLOUD_STOP_BUFFER_PCT = "cloudStopBufferPct";
    static final String STRUCTURE_EXIT_CONFIRM_BARS = "structureExitConfirmBars";
    static final String COOLDOWN_BARS = "cooldownBars";
    static final String SKIP_MARKET_REGIMES = DoflamingoMarketRegimeFilter.SKIP_MARKET_REGIMES;
    static final String ALLOW_SHORTS = "allowShorts";
    static final String SHORT_CLOUD_PRICE_MODE = "shortCloudPriceMode";
    static final String SHORT_EMA_CLOUD_MODE = "shortEmaCloudMode";
    static final String MIN_STOP_PCT = "minStopPct";
    static final String MAX_STOP_PCT = "maxStopPct";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(ENTRY_MODE, StrategyParameterType.ENUM, "Entry Mode",
                    "Adaptive Doflamingo v3 entry mode.",
                    true, "HYBRID", null, null, List.of("STRICT_BETA", "EARLY_TRANSITION", "HYBRID")),
            new StrategyParameterDefinition(MIN_CONFIDENCE, StrategyParameterType.DECIMAL, "Minimum Confidence",
                    "Minimum dynamic confidence required before emitting Doflamingo Ichimoku v3 intents.",
                    true, BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.0), BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TREND_AVERAGE_LOOKBACK, StrategyParameterType.INTEGER, "Trend Average Lookback",
                    "Number of trend score bars used by the Doflamingo v3 acceleration filter.",
                    true, 10, BigDecimal.valueOf(2), BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(MAX_HOLDING_BARS, StrategyParameterType.INTEGER, "Max Holding Bars",
                    "Maximum bars the runtime may hold an accepted v3 Ichimoku position.",
                    true, 96, BigDecimal.ONE, BigDecimal.valueOf(500), List.of()),
            new StrategyParameterDefinition(RISK_FRACTION, StrategyParameterType.DECIMAL, "Risk Fraction",
                    "Portfolio risk fraction requested by v3 entry intents.",
                    true, BigDecimal.valueOf(0.01), BigDecimal.ZERO, BigDecimal.valueOf(0.02), List.of()),
            new StrategyParameterDefinition(ENABLE_PROTECTIVE_STOP, StrategyParameterType.BOOLEAN, "Enable Protective Stop",
                    "Whether v3 entry intents include an executable runtime stop policy.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Protective stop model used by v3 entry intents.",
                    true, "CLOUD_OR_ATR", null, null, List.of("NONE", "CLOUD", "ATR", "CLOUD_OR_ATR")),
            new StrategyParameterDefinition(ATR_PERIOD, StrategyParameterType.INTEGER, "ATR Period",
                    "ATR period used by adaptive protective stop scoring.",
                    true, 14, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(ATR_STOP_MULTIPLE, StrategyParameterType.DECIMAL, "ATR Stop Multiple",
                    "ATR multiple used by adaptive protective stop scoring.",
                    true, BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.1), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(CLOUD_STOP_BUFFER_PCT, StrategyParameterType.DECIMAL, "Cloud Stop Buffer Percent",
                    "Percent buffer below cloud support for cloud-based stops.",
                    true, BigDecimal.valueOf(0.25), BigDecimal.ZERO, BigDecimal.valueOf(5), List.of()),
            new StrategyParameterDefinition(STRUCTURE_EXIT_CONFIRM_BARS, StrategyParameterType.INTEGER, "Structure Exit Confirm Bars",
                    "Consecutive bars required for conversion/base structure exits.",
                    true, 2, BigDecimal.ONE, BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(COOLDOWN_BARS, StrategyParameterType.INTEGER, "Cooldown Bars",
                    "Bars to wait after an emitted entry or exit before a fresh entry.",
                    true, 3, BigDecimal.ZERO, BigDecimal.valueOf(50), List.of()),
            new StrategyParameterDefinition(SKIP_MARKET_REGIMES, StrategyParameterType.MULTI_ENUM, "Skip Market Regimes",
                    "Market-context regimes where new Doflamingo Ichimoku v3 entries are skipped.",
                    false, List.of(), null, null, DoflamingoMarketRegimeFilter.ALLOWED_REGIME_NAMES),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Whether V3 may emit new short entry intents when bearish Ichimoku conditions pass.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_CLOUD_PRICE_MODE, StrategyParameterType.ENUM, "Short Cloud Price Mode",
                    "Bearish price/cloud confirmation required before V3 short entries.",
                    true, "HIGH_BELOW_CLOUD", null, null, List.of("HIGH_BELOW_CLOUD", "CLOSE_BELOW_CLOUD")),
            new StrategyParameterDefinition(SHORT_EMA_CLOUD_MODE, StrategyParameterType.ENUM, "Short EMA Cloud Mode",
                    "Bearish EMA/cloud confirmation required before V3 short entries.",
                    true, "EMA9_BELOW_SPAN_B", null, null, List.of("EMA9_BELOW_SPAN_B", "EMA9_BELOW_CLOUD")),
            new StrategyParameterDefinition(MIN_STOP_PCT, StrategyParameterType.DECIMAL, "Minimum Stop Percent",
                    "Lower bound for V3 short adaptive stop distance.",
                    true, BigDecimal.valueOf(0.10), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MAX_STOP_PCT, StrategyParameterType.DECIMAL, "Maximum Stop Percent",
                    "Upper bound for V3 short adaptive stop distance.",
                    true, BigDecimal.valueOf(2.50), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Ichimoku Momentum 002 Beta V3",
            "Short-capable Ichimoku lifecycle strategy with structured trade-intent evidence.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.EXIT_INTENT,
                    StrategyCapability.RISK_AWARE_SIZING,
                    StrategyCapability.PARAMETERIZED
            ),
            SCHEMA,
            List.of(
                    DoflamingoChartStudies.ichimoku("primary-signal", true),
                    DoflamingoChartStudies.ema(9, "price-momentum-filter", false),
                    DoflamingoChartStudies.sma(20, "trend-score-component", false),
                    DoflamingoChartStudies.sma(50, "trend-score-component", false),
                    DoflamingoChartStudies.sma(200, "trend-score-component", false),
                    DoflamingoChartStudies.unsupported("trend-score", "Trend Score", "trend-acceleration-filter", Map.of("lookback", 10), false),
                    DoflamingoChartStudies.unsupported("atr", "ATR", "protective-stop-context", Map.of("period", 14), false)
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
        if (effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.10))
                .compareTo(effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.50))) > 0) {
            issues.add(new StrategyValidationIssue(MIN_STOP_PCT, "Minimum stop percent must be <= maximum stop percent"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                DoflamingoChartStudies.ichimoku("primary-signal", true),
                DoflamingoChartStudies.ema(9, "price-momentum-filter", false),
                DoflamingoChartStudies.sma(20, "trend-score-component", false),
                DoflamingoChartStudies.sma(50, "trend-score-component", false),
                DoflamingoChartStudies.sma(200, "trend-score-component", false),
                DoflamingoChartStudies.unsupported("trend-score", "Trend Score", "trend-acceleration-filter",
                        Map.of("lookback", effectiveParameters.integer(TREND_AVERAGE_LOOKBACK, 10)), false),
                DoflamingoChartStudies.unsupported("atr", "ATR", "protective-stop-context",
                        Map.of("period", effectiveParameters.integer(ATR_PERIOD, 14)), false)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo Ichimoku beta parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new DoflamingoIchimokuMo002BetaStrategy(
                effective.string(ENTRY_MODE, "HYBRID"),
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.60)),
                effective.integer(TREND_AVERAGE_LOOKBACK, 10),
                effective.integer(MAX_HOLDING_BARS, 96),
                effective.decimal(RISK_FRACTION, BigDecimal.valueOf(0.01)),
                effective.bool(ENABLE_PROTECTIVE_STOP, true),
                effective.string(STOP_MODE, "CLOUD_OR_ATR"),
                effective.integer(ATR_PERIOD, 14),
                effective.decimal(ATR_STOP_MULTIPLE, BigDecimal.valueOf(1.5)),
                effective.decimal(CLOUD_STOP_BUFFER_PCT, BigDecimal.valueOf(0.25)),
                effective.integer(STRUCTURE_EXIT_CONFIRM_BARS, 2),
                effective.integer(COOLDOWN_BARS, 3),
                effective.stringList(SKIP_MARKET_REGIMES, List.of()),
                effective.bool(ALLOW_SHORTS, true),
                effective.string(SHORT_CLOUD_PRICE_MODE, "HIGH_BELOW_CLOUD"),
                effective.string(SHORT_EMA_CLOUD_MODE, "EMA9_BELOW_SPAN_B"),
                effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.10)),
                effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.50))
        );
    }
}
