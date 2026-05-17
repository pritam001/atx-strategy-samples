package org.algotradex.strategy.samples.doflamingov4;

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
 * ServiceLoader entrypoint for {@code doflamingo-ichimoku-mo-002-beta-v4}.
 * <p>
 * The provider publishes the short-capable lifecycle descriptor, parameter schema, chart studies,
 * validation, and fresh strategy construction for the v4 Ichimoku sample. Parameters control entry
 * mode, protective stop metadata, market-regime skips, short cloud rules, reversal behavior, stale
 * short exits, and short scale-out sizing.
 * <p>
 * This provider is the plugin metadata/factory boundary. It does not own replay scheduling,
 * position mutation, execution acceptance, broker routing, exchange constraints, or portfolio
 * accounting.
 */
public final class DoflamingoIchimokuMo002BetaV4StrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-ichimoku-mo-002-beta-v4";
    public static final String STRATEGY_VERSION = "4.2.0";
    public static final String PROVIDER_ID = "doflamingo-v4-strategy-packs";

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
    static final String ALLOW_REVERSAL = "allowReversal";
    static final String SHORT_STALE_BARS = "shortStaleBars";
    static final String SHORT_STALE_MIN_R = "shortStaleMinR";
    static final String ALLOW_SHORT_SCALE_OUT = "allowShortScaleOut";
    static final String SHORT_SCALE_OUT_AT_R = "shortScaleOutAtR";
    static final String SHORT_SCALE_OUT_FRACTION = "shortScaleOutFraction";
    static final String MIN_KUMO_THICKNESS_ATR = "minKumoThicknessAtr";
    static final String MIN_FUTURE_CLOUD_SPREAD_ATR = "minFutureCloudSpreadAtr";
    static final String REQUIRE_FUTURE_CLOUD_WIDENING = "requireFutureCloudWidening";
    static final String REQUIRE_CHIKOU_CLEAR_SPACE = "requireChikouClearSpace";
    static final String TK_CROSS_FRESH_BARS = "tkCrossFreshBars";
    static final String MAX_ENTRY_ATR_FROM_CLOUD_TOP = "maxEntryAtrFromCloudTop";
    static final String HTF_CLOUD_BIAS_MODE = "htfCloudBiasMode";
    static final String TARGET_R_MULTIPLE = "targetRMultiple";
    static final String SESSION_GATING = "sessionGating";
    static final String VOLUME_CONFIRM_MULTIPLE = "volumeConfirmMultiple";
    static final String ATR_EXPANSION_MULTIPLE = "atrExpansionMultiple";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(ENTRY_MODE, StrategyParameterType.ENUM, "Entry Mode",
                    "Adaptive Doflamingo v4 entry mode.",
                    true, "HYBRID", null, null, List.of("STRICT_BETA", "EARLY_TRANSITION", "HYBRID")),
            new StrategyParameterDefinition(MIN_CONFIDENCE, StrategyParameterType.DECIMAL, "Minimum Confidence",
                    "Minimum dynamic confidence required before emitting Doflamingo Ichimoku v4 intents.",
                    true, BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.0), BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TREND_AVERAGE_LOOKBACK, StrategyParameterType.INTEGER, "Trend Average Lookback",
                    "Number of trend score bars used by the Doflamingo v4 acceleration filter.",
                    true, 10, BigDecimal.valueOf(2), BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(MAX_HOLDING_BARS, StrategyParameterType.INTEGER, "Max Holding Bars",
                    "Maximum bars the runtime may hold an accepted v4 Ichimoku position.",
                    true, 96, BigDecimal.ONE, BigDecimal.valueOf(500), List.of()),
            new StrategyParameterDefinition(RISK_FRACTION, StrategyParameterType.DECIMAL, "Risk Fraction",
                    "Portfolio risk fraction requested by v4 entry intents.",
                    true, BigDecimal.valueOf(0.01), BigDecimal.ZERO, BigDecimal.valueOf(0.02), List.of()),
            new StrategyParameterDefinition(ENABLE_PROTECTIVE_STOP, StrategyParameterType.BOOLEAN, "Enable Protective Stop",
                    "Whether v4 entry intents include an executable runtime stop policy.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Protective stop model used by v4 entry intents.",
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
            new StrategyParameterDefinition(MIN_KUMO_THICKNESS_ATR, StrategyParameterType.DECIMAL, "Minimum Kumo Thickness ATR",
                    "Minimum present-cloud thickness expressed as ATR multiple.",
                    true, BigDecimal.valueOf(0.10), BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(MIN_FUTURE_CLOUD_SPREAD_ATR, StrategyParameterType.DECIMAL, "Minimum Future Cloud Spread ATR",
                    "Minimum future-cloud spread expressed as ATR multiple.",
                    true, BigDecimal.valueOf(0.05), BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(REQUIRE_FUTURE_CLOUD_WIDENING, StrategyParameterType.BOOLEAN, "Require Future Cloud Widening",
                    "Require the future cloud spread to be at least as wide as the present cloud.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(REQUIRE_CHIKOU_CLEAR_SPACE, StrategyParameterType.BOOLEAN, "Require Chikou Clear Space",
                    "Require current close to clear the price space from the Chikou lookback window.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(TK_CROSS_FRESH_BARS, StrategyParameterType.INTEGER, "TK Cross Fresh Bars",
                    "Maximum bars since conversion/base confirmation; 0 disables freshness.",
                    true, 12, BigDecimal.ZERO, BigDecimal.valueOf(50), List.of()),
            new StrategyParameterDefinition(MAX_ENTRY_ATR_FROM_CLOUD_TOP, StrategyParameterType.DECIMAL, "Max Entry ATR From Cloud Top",
                    "Maximum long entry distance above the cloud ceiling expressed as ATR multiple; 0 disables the gate.",
                    true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(50), List.of()),
            new StrategyParameterDefinition(HTF_CLOUD_BIAS_MODE, StrategyParameterType.ENUM, "H1 Cloud Bias Mode",
                    "Higher-timeframe cloud-bias requirement for flat entries.",
                    true, "OFF", null, null, List.of("OFF", "ALIGN_WITH_TRADE")),
            new StrategyParameterDefinition(TARGET_R_MULTIPLE, StrategyParameterType.DECIMAL, "Target R Multiple",
                    "Runtime target expressed as reward-to-risk multiple.",
                    true, BigDecimal.valueOf(2.50), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(SESSION_GATING, StrategyParameterType.BOOLEAN, "Session Gating",
                    "Whether to block fresh entries outside deterministic NSE regular-session times.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(VOLUME_CONFIRM_MULTIPLE, StrategyParameterType.DECIMAL, "Volume Confirm Multiple",
                    "Current volume must be at least this multiple of its 20-bar average for early transitions; 0 disables the gate.",
                    true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(ATR_EXPANSION_MULTIPLE, StrategyParameterType.DECIMAL, "ATR Expansion Multiple",
                    "Current ATR must be at least this multiple of prior ATR for early transitions; 0 disables the gate.",
                    true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(SKIP_MARKET_REGIMES, StrategyParameterType.MULTI_ENUM, "Skip Market Regimes",
                    "Market-context regimes where new Doflamingo Ichimoku v4 entries are skipped.",
                    false, List.of(), null, null, DoflamingoMarketRegimeFilter.ALLOWED_REGIME_NAMES),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Whether V4 may emit new short entry intents when bearish Ichimoku conditions pass.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_CLOUD_PRICE_MODE, StrategyParameterType.ENUM, "Short Cloud Price Mode",
                    "Bearish price/cloud confirmation required before V4 short entries.",
                    true, "HIGH_BELOW_CLOUD", null, null, List.of("HIGH_BELOW_CLOUD", "CLOSE_BELOW_CLOUD")),
            new StrategyParameterDefinition(SHORT_EMA_CLOUD_MODE, StrategyParameterType.ENUM, "Short EMA Cloud Mode",
                    "Bearish EMA/cloud confirmation required before V4 short entries.",
                    true, "EMA9_BELOW_SPAN_B", null, null, List.of("EMA9_BELOW_SPAN_B", "EMA9_BELOW_CLOUD")),
            new StrategyParameterDefinition(MIN_STOP_PCT, StrategyParameterType.DECIMAL, "Minimum Stop Percent",
                    "Lower bound for V4 short adaptive stop distance.",
                    true, BigDecimal.valueOf(0.10), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MAX_STOP_PCT, StrategyParameterType.DECIMAL, "Maximum Stop Percent",
                    "Upper bound for V4 short adaptive stop distance.",
                    true, BigDecimal.valueOf(2.50), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(ALLOW_REVERSAL, StrategyParameterType.BOOLEAN, "Allow Reversal",
                    "Whether V4 may emit executable long/short reversal intents from an existing position.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_STALE_BARS, StrategyParameterType.INTEGER, "Short Stale Bars",
                    "Bars held before a weak short is eligible for stale exit.",
                    true, 16, BigDecimal.ONE, BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(SHORT_STALE_MIN_R, StrategyParameterType.DECIMAL, "Short Stale Minimum R",
                    "Maximum R multiple below which an old short is considered stale.",
                    true, BigDecimal.valueOf(0.25), BigDecimal.valueOf(-5), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(ALLOW_SHORT_SCALE_OUT, StrategyParameterType.BOOLEAN, "Allow Short Scale Out",
                    "Whether V4 may reduce a winning short at the configured R multiple.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_SCALE_OUT_AT_R, StrategyParameterType.DECIMAL, "Short Scale Out At R",
                    "Current R multiple required before short scale-out is eligible.",
                    true, BigDecimal.valueOf(1.00), BigDecimal.valueOf(0.10), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(SHORT_SCALE_OUT_FRACTION, StrategyParameterType.DECIMAL, "Short Scale Out Fraction",
                    "Open-position fraction requested by short scale-out intents.",
                    true, BigDecimal.valueOf(0.50), BigDecimal.valueOf(0.01), BigDecimal.ONE, List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Ichimoku Momentum 002 Beta V4",
            "Short-capable Ichimoku lifecycle strategy with structured trade-intent evidence.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
            List.of("H1"),
            List.of(),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.EXIT_INTENT,
                    StrategyCapability.SCALE_OUT_INTENT,
                    StrategyCapability.REVERSAL_INTENT,
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
                effective.decimal(MIN_KUMO_THICKNESS_ATR, BigDecimal.valueOf(0.10)),
                effective.decimal(MIN_FUTURE_CLOUD_SPREAD_ATR, BigDecimal.valueOf(0.05)),
                effective.bool(REQUIRE_FUTURE_CLOUD_WIDENING, false),
                effective.bool(REQUIRE_CHIKOU_CLEAR_SPACE, false),
                effective.integer(TK_CROSS_FRESH_BARS, 12),
                effective.decimal(MAX_ENTRY_ATR_FROM_CLOUD_TOP, BigDecimal.ZERO),
                effective.string(HTF_CLOUD_BIAS_MODE, "OFF"),
                effective.decimal(TARGET_R_MULTIPLE, BigDecimal.valueOf(2.50)),
                effective.bool(SESSION_GATING, false),
                effective.decimal(VOLUME_CONFIRM_MULTIPLE, BigDecimal.ZERO),
                effective.decimal(ATR_EXPANSION_MULTIPLE, BigDecimal.ZERO),
                effective.stringList(SKIP_MARKET_REGIMES, List.of()),
                effective.bool(ALLOW_SHORTS, true),
                effective.string(SHORT_CLOUD_PRICE_MODE, "HIGH_BELOW_CLOUD"),
                effective.string(SHORT_EMA_CLOUD_MODE, "EMA9_BELOW_SPAN_B"),
                effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.10)),
                effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.50)),
                effective.bool(ALLOW_REVERSAL, false),
                effective.integer(SHORT_STALE_BARS, 16),
                effective.decimal(SHORT_STALE_MIN_R, BigDecimal.valueOf(0.25)),
                effective.bool(ALLOW_SHORT_SCALE_OUT, true),
                effective.decimal(SHORT_SCALE_OUT_AT_R, BigDecimal.valueOf(1.00)),
                effective.decimal(SHORT_SCALE_OUT_FRACTION, BigDecimal.valueOf(0.50))
        );
    }
}
