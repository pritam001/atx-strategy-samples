package org.algotradex.strategy.samples.doflamingov2;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader provider for the Doflamingo Ichimoku momentum 002 beta port.
 */
public final class DoflamingoIchimokuMo002BetaStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-ichimoku-mo-002-beta-v2";
    public static final String STRATEGY_VERSION = "2.0.0";
    public static final String PROVIDER_ID = "doflamingo-v2-strategy-packs";

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

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(ENTRY_MODE, StrategyParameterType.ENUM, "Entry Mode",
                    "Adaptive Doflamingo v2 entry mode.",
                    true, "HYBRID", null, null, List.of("STRICT_BETA", "EARLY_TRANSITION", "HYBRID")),
            new StrategyParameterDefinition(MIN_CONFIDENCE, StrategyParameterType.DECIMAL, "Minimum Confidence",
                    "Minimum dynamic confidence required before emitting Doflamingo Ichimoku v2 intents.",
                    true, BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.0), BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TREND_AVERAGE_LOOKBACK, StrategyParameterType.INTEGER, "Trend Average Lookback",
                    "Number of trend score bars used by the Doflamingo v2 acceleration filter.",
                    true, 10, BigDecimal.valueOf(2), BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(MAX_HOLDING_BARS, StrategyParameterType.INTEGER, "Max Holding Bars",
                    "Maximum bars the runtime may hold an accepted v2 Ichimoku position.",
                    true, 96, BigDecimal.ONE, BigDecimal.valueOf(500), List.of()),
            new StrategyParameterDefinition(RISK_FRACTION, StrategyParameterType.DECIMAL, "Risk Fraction",
                    "Portfolio risk fraction requested by v2 entry intents.",
                    true, BigDecimal.valueOf(0.01), BigDecimal.ZERO, BigDecimal.valueOf(0.02), List.of()),
            new StrategyParameterDefinition(ENABLE_PROTECTIVE_STOP, StrategyParameterType.BOOLEAN, "Enable Protective Stop",
                    "Whether v2 entry intents include an executable runtime stop policy.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Protective stop model used by v2 entry intents.",
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
                    true, 3, BigDecimal.ZERO, BigDecimal.valueOf(50), List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Ichimoku Momentum 002 Beta V2",
            "ATX-adaptive long-only Ichimoku lifecycle strategy with structured trade-intent evidence.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
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
        return StrategyParameterValidator.validate(SCHEMA, parameters);
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
                effective.integer(COOLDOWN_BARS, 3)
        );
    }
}
