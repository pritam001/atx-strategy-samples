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
 * ServiceLoader entrypoint for {@code doflamingo-multi-indicator-v6-trend-reversal-v3}.
 * <p>
 * The provider exposes the v3 descriptor, chart-study metadata, validation, and fresh strategy
 * construction for a short-capable multi-indicator lifecycle sample. Parameters include long/short
 * momentum filters, stop bounds, stale-exit rules, scale-out and short scale-in controls, reversal
 * enablement, and market-regime entry skips.
 * <p>
 * Validation protects the configuration contract before strategy creation. Runtime order
 * acceptance, broker routing, fill simulation, exchange-specific constraints, and portfolio
 * accounting are not owned by this plugin.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-multi-indicator-v6-trend-reversal-v3";
    public static final String STRATEGY_VERSION = "3.0.0";
    public static final String PROVIDER_ID = "doflamingo-v3-strategy-packs";
    private static final int DEFAULT_MACD_FAST_PERIOD = 16;
    private static final int DEFAULT_MACD_SLOW_PERIOD = 36;
    private static final int DEFAULT_MACD_SIGNAL_PERIOD = 9;

    static final String MIN_CONFIDENCE = "minConfidence";
    static final String MACD_FAST_PERIOD = "macdFastPeriod";
    static final String MACD_SLOW_PERIOD = "macdSlowPeriod";
    static final String MACD_SIGNAL_PERIOD = "macdSignalPeriod";
    static final String STOCH_OVERBOUGHT = "stochOverbought";
    static final String STOCH_OVERSOLD = "stochOversold";
    static final String TREND_FILTER_MODE = "trendFilterMode";
    static final String ADAPTIVE_MOMENTUM_MODE = "adaptiveMomentumMode";
    static final String STOP_MODE = "stopMode";
    static final String STOP_LOSS_PCT = "stopLossPct";
    static final String MIN_STOP_PCT = "minStopPct";
    static final String MAX_STOP_PCT = "maxStopPct";
    static final String ATR_PERIOD = "atrPeriod";
    static final String ATR_STOP_MULTIPLE = "atrStopMultiple";
    static final String MAX_HOLDING_BARS = "maxHoldingBars";
    static final String STALE_BARS = "staleBars";
    static final String STALE_MIN_R = "staleMinR";
    static final String ENABLE_SCALE_OUT = "enableScaleOut";
    static final String SCALE_OUT_AT_R = "scaleOutAtR";
    static final String SCALE_OUT_FRACTION = "scaleOutFraction";
    static final String TRAIL_AFTER_SCALE_OUT = "trailAfterScaleOut";
    static final String RISK_FRACTION = "riskFraction";
    static final String SKIP_MARKET_REGIMES = DoflamingoMarketRegimeFilter.SKIP_MARKET_REGIMES;
    static final String ALLOW_SHORTS = "allowShorts";
    static final String SHORT_CLOUD_MODE = "shortCloudMode";
    static final String ALLOW_REVERSAL = "allowReversal";
    static final String ALLOW_SHORT_SCALE_IN = "allowShortScaleIn";
    static final String SHORT_SCALE_IN_AT_R = "shortScaleInAtR";
    static final String MAX_SHORT_SCALE_INS = "maxShortScaleIns";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(MIN_CONFIDENCE, StrategyParameterType.DECIMAL, "Minimum Confidence",
                    "Minimum dynamic confidence required before emitting Doflamingo Multi V6 v3 intents.",
                    true, BigDecimal.valueOf(0.60), BigDecimal.ZERO, BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(MACD_FAST_PERIOD, StrategyParameterType.INTEGER, "MACD Fast Period",
                    "Fast EMA period used by the Doflamingo v6 MACD pattern.",
                    true, DEFAULT_MACD_FAST_PERIOD, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(MACD_SLOW_PERIOD, StrategyParameterType.INTEGER, "MACD Slow Period",
                    "Slow EMA period used by the Doflamingo v6 MACD pattern.",
                    true, DEFAULT_MACD_SLOW_PERIOD, BigDecimal.valueOf(3), BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(MACD_SIGNAL_PERIOD, StrategyParameterType.INTEGER, "MACD Signal Period",
                    "Signal EMA period used by the Doflamingo v6 MACD pattern.",
                    true, DEFAULT_MACD_SIGNAL_PERIOD, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(STOCH_OVERBOUGHT, StrategyParameterType.DECIMAL, "Stoch Overbought",
                    "Stoch RSI overbought threshold used by the reset rule.",
                    true, BigDecimal.valueOf(80), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(STOCH_OVERSOLD, StrategyParameterType.DECIMAL, "Stoch Oversold",
                    "Stoch RSI oversold threshold used by the entry rule.",
                    true, BigDecimal.valueOf(20), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(TREND_FILTER_MODE, StrategyParameterType.ENUM, "Trend Filter Mode",
                    "Adaptive trend filter strictness for reversal entries.",
                    true, "SOFT", null, null, List.of("NONE", "SOFT", "STRICT")),
            new StrategyParameterDefinition(ADAPTIVE_MOMENTUM_MODE, StrategyParameterType.ENUM, "Adaptive Momentum Mode",
                    "Whether improving MACD histogram or Stoch RSI K may explicitly stand in for the original reversal pattern.",
                    true, "STRICT_REVERSAL", null, null, List.of("STRICT_REVERSAL", "ADAPTIVE_CONFIRMATION")),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Runtime stop model emitted on v3 entry intents.",
                    true, "ATR_OR_PERCENT_MAX", null, null, List.of("PERCENT", "ATR", "CLOUD", "ATR_OR_PERCENT_MAX")),
            new StrategyParameterDefinition(STOP_LOSS_PCT, StrategyParameterType.DECIMAL, "Stop Loss Percent",
                    "Percent stop used by fixed or bounded adaptive stop policies.",
                    true, BigDecimal.valueOf(2.0), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MIN_STOP_PCT, StrategyParameterType.DECIMAL, "Minimum Stop Percent",
                    "Lower bound for adaptive stop distance.",
                    true, BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MAX_STOP_PCT, StrategyParameterType.DECIMAL, "Maximum Stop Percent",
                    "Upper bound for adaptive stop distance.",
                    true, BigDecimal.valueOf(2.5), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(ATR_PERIOD, StrategyParameterType.INTEGER, "ATR Period",
                    "ATR period used by adaptive stop scoring.",
                    true, 14, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(ATR_STOP_MULTIPLE, StrategyParameterType.DECIMAL, "ATR Stop Multiple",
                    "ATR multiple used by adaptive stop scoring.",
                    true, BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.1), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(MAX_HOLDING_BARS, StrategyParameterType.INTEGER, "Max Holding Bars",
                    "Maximum bars the runtime may hold an accepted v3 Multi V6 position.",
                    true, 64, BigDecimal.ONE, BigDecimal.valueOf(500), List.of()),
            new StrategyParameterDefinition(STALE_BARS, StrategyParameterType.INTEGER, "Stale Bars",
                    "Bars held before a weak trade is eligible for stale exit.",
                    true, 16, BigDecimal.ONE, BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(STALE_MIN_R, StrategyParameterType.DECIMAL, "Stale Minimum R",
                    "Maximum R multiple below which an old trade is considered stale.",
                    true, BigDecimal.valueOf(0.25), BigDecimal.valueOf(-5), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(ENABLE_SCALE_OUT, StrategyParameterType.BOOLEAN, "Enable Scale Out",
                    "Whether v3 emits one scale-out intent after the configured R multiple.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SCALE_OUT_AT_R, StrategyParameterType.DECIMAL, "Scale Out At R",
                    "Current R multiple required for scale-out.",
                    true, BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.1), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(SCALE_OUT_FRACTION, StrategyParameterType.DECIMAL, "Scale Out Fraction",
                    "Open-position fraction requested by scale-out intents.",
                    true, BigDecimal.valueOf(0.50), BigDecimal.valueOf(0.01), BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TRAIL_AFTER_SCALE_OUT, StrategyParameterType.BOOLEAN, "Trail After Scale Out",
                    "Whether post-scale trailing weakness may close the remaining position.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(RISK_FRACTION, StrategyParameterType.DECIMAL, "Risk Fraction",
                    "Portfolio risk fraction requested by v3 entry intents.",
                    true, BigDecimal.valueOf(0.01), BigDecimal.ZERO, BigDecimal.valueOf(0.02), List.of()),
            new StrategyParameterDefinition(SKIP_MARKET_REGIMES, StrategyParameterType.MULTI_ENUM, "Skip Market Regimes",
                    "Market-context regimes where new Doflamingo Multi V6 v3 entries are skipped.",
                    false, List.of(), null, null, DoflamingoMarketRegimeFilter.ALLOWED_REGIME_NAMES),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Whether V3 may emit new short entry intents when bearish lifecycle conditions pass.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_CLOUD_MODE, StrategyParameterType.ENUM, "Short Cloud Mode",
                    "Bearish cloud confirmation required for V3 short entries.",
                    true, "CLOSE_BELOW_CLOUD", null, null, List.of("CLOSE_BELOW_CLOUD", "HIGH_BELOW_CLOUD")),
            new StrategyParameterDefinition(ALLOW_REVERSAL, StrategyParameterType.BOOLEAN, "Allow Reversal",
                    "Whether V3 may emit executable long/short reversal intents from an existing position.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(ALLOW_SHORT_SCALE_IN, StrategyParameterType.BOOLEAN, "Allow Short Scale In",
                    "Whether V3 may add once to a winning short when renewed bearish structure appears.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_SCALE_IN_AT_R, StrategyParameterType.DECIMAL, "Short Scale In At R",
                    "Current R multiple required before short scale-in is eligible.",
                    true, BigDecimal.valueOf(0.50), BigDecimal.valueOf(0.10), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(MAX_SHORT_SCALE_INS, StrategyParameterType.INTEGER, "Max Short Scale Ins",
                    "Maximum accepted short scale-in intents per position.",
                    true, 1, BigDecimal.ZERO, BigDecimal.valueOf(10), List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Multi Indicator V6 Trend Reversal V3",
            "Short-capable multi-indicator trend reversal lifecycle strategy with structured trade-intent evidence.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.EXIT_INTENT,
                    StrategyCapability.SCALE_IN_INTENT,
                    StrategyCapability.SCALE_OUT_INTENT,
                    StrategyCapability.REVERSAL_INTENT,
                    StrategyCapability.RISK_AWARE_SIZING,
                    StrategyCapability.PARAMETERIZED
            ),
            SCHEMA,
            List.of(
                    DoflamingoChartStudies.ichimoku("trend-reversal-context", true),
                    DoflamingoChartStudies.macd(
                            DEFAULT_MACD_FAST_PERIOD,
                            DEFAULT_MACD_SLOW_PERIOD,
                            DEFAULT_MACD_SIGNAL_PERIOD,
                            "momentum-pattern",
                            true),
                    DoflamingoChartStudies.unsupported("stoch-rsi", "Stoch RSI", "reset-and-entry-filter", Map.of("rsiPeriod", 14, "stochasticPeriod", 14, "kPeriod", 3, "dPeriod", 3), true),
                    DoflamingoChartStudies.unsupported("psar", "PSAR", "trend-reversal-trigger", Map.of(), true),
                    DoflamingoChartStudies.ema(50, "adaptive-trend-filter", false),
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
        if (effective.integer(MACD_SLOW_PERIOD, DEFAULT_MACD_SLOW_PERIOD) <= effective.integer(MACD_FAST_PERIOD, DEFAULT_MACD_FAST_PERIOD)) {
            issues.add(new StrategyValidationIssue(MACD_SLOW_PERIOD, "MACD slow period must be greater than fast period"));
        }
        if (effective.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20))
                .compareTo(effective.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80))) >= 0) {
            issues.add(new StrategyValidationIssue(STOCH_OVERSOLD, "Stoch oversold threshold must be below overbought threshold"));
        }
        if (effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(1.0))
                .compareTo(effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.5))) > 0) {
            issues.add(new StrategyValidationIssue(MIN_STOP_PCT, "Minimum stop percent must be <= maximum stop percent"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                DoflamingoChartStudies.ichimoku("trend-reversal-context", true),
                DoflamingoChartStudies.macd(
                        effectiveParameters.integer(MACD_FAST_PERIOD, DEFAULT_MACD_FAST_PERIOD),
                        effectiveParameters.integer(MACD_SLOW_PERIOD, DEFAULT_MACD_SLOW_PERIOD),
                        effectiveParameters.integer(MACD_SIGNAL_PERIOD, DEFAULT_MACD_SIGNAL_PERIOD),
                        "momentum-pattern",
                        true),
                DoflamingoChartStudies.unsupported("stoch-rsi", "Stoch RSI", "reset-and-entry-filter", Map.of(
                        "rsiPeriod", 14,
                        "stochasticPeriod", 14,
                        "kPeriod", 3,
                        "dPeriod", 3,
                        "oversold", effectiveParameters.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20)),
                        "overbought", effectiveParameters.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80))
                ), true),
                DoflamingoChartStudies.unsupported("psar", "PSAR", "trend-reversal-trigger", Map.of(), true),
                DoflamingoChartStudies.ema(50, "adaptive-trend-filter", false),
                DoflamingoChartStudies.unsupported("atr", "ATR", "protective-stop-context",
                        Map.of("period", effectiveParameters.integer(ATR_PERIOD, 14)), false)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo v6 trend reversal parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new DoflamingoMultiIndicatorV6TrendReversalStrategy(
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.60)),
                effective.integer(MACD_FAST_PERIOD, DEFAULT_MACD_FAST_PERIOD),
                effective.integer(MACD_SLOW_PERIOD, DEFAULT_MACD_SLOW_PERIOD),
                effective.integer(MACD_SIGNAL_PERIOD, DEFAULT_MACD_SIGNAL_PERIOD),
                effective.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80)),
                effective.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20)),
                effective.string(TREND_FILTER_MODE, "SOFT"),
                effective.string(ADAPTIVE_MOMENTUM_MODE, "STRICT_REVERSAL"),
                effective.string(STOP_MODE, "ATR_OR_PERCENT_MAX"),
                effective.decimal(STOP_LOSS_PCT, BigDecimal.valueOf(2.0)),
                effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(1.0)),
                effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.5)),
                effective.integer(ATR_PERIOD, 14),
                effective.decimal(ATR_STOP_MULTIPLE, BigDecimal.valueOf(1.5)),
                effective.integer(MAX_HOLDING_BARS, 64),
                effective.integer(STALE_BARS, 16),
                effective.decimal(STALE_MIN_R, BigDecimal.valueOf(0.25)),
                effective.bool(ENABLE_SCALE_OUT, true),
                effective.decimal(SCALE_OUT_AT_R, BigDecimal.valueOf(1.0)),
                effective.decimal(SCALE_OUT_FRACTION, BigDecimal.valueOf(0.50)),
                effective.bool(TRAIL_AFTER_SCALE_OUT, true),
                effective.decimal(RISK_FRACTION, BigDecimal.valueOf(0.01)),
                effective.stringList(SKIP_MARKET_REGIMES, List.of()),
                effective.bool(ALLOW_SHORTS, true),
                effective.string(SHORT_CLOUD_MODE, "CLOSE_BELOW_CLOUD"),
                effective.bool(ALLOW_REVERSAL, false),
                effective.bool(ALLOW_SHORT_SCALE_IN, false),
                effective.decimal(SHORT_SCALE_IN_AT_R, BigDecimal.valueOf(0.50)),
                effective.integer(MAX_SHORT_SCALE_INS, 1)
        );
    }
}
