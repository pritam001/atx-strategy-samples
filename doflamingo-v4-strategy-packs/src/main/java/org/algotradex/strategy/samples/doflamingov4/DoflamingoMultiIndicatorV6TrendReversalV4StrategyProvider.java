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
 * ServiceLoader entrypoint for {@code doflamingo-multi-indicator-v6-trend-reversal-v4}.
 * <p>
 * The provider exposes the v4 descriptor, chart-study metadata, validation, and fresh strategy
 * construction for a short-capable multi-indicator lifecycle sample. Parameters include long/short
 * momentum filters, stop bounds, stale-exit rules, scale-out and short scale-in controls, reversal
 * enablement, and market-regime entry skips.
 * <p>
 * Validation protects the configuration contract before strategy creation. Runtime order
 * acceptance, broker routing, fill simulation, exchange-specific constraints, and portfolio
 * accounting are not owned by this plugin.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-multi-indicator-v6-trend-reversal-v4";
    public static final String STRATEGY_VERSION = "4.0.0";
    public static final String PROVIDER_ID = "doflamingo-v4-strategy-packs";
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
    static final String STOP_MODE = "stopMode";
    static final String STOP_LOSS_PCT = "stopLossPct";
    static final String MIN_STOP_PCT = "minStopPct";
    static final String MAX_STOP_PCT = "maxStopPct";
    static final String ATR_PERIOD = "atrPeriod";
    static final String ATR_STOP_MULTIPLE = "atrStopMultiple";
    static final String MAX_HOLDING_BARS = "maxHoldingBars";
    static final String STALE_BARS = "staleBars";
    static final String STALE_MIN_R = "staleMinR";
    static final String COOLDOWN_BARS = "cooldownBars";
    static final String STRUCTURE_EXIT_CONFIRM_BARS = "structureExitConfirmBars";
    static final String VOLUME_CONFIRM_MULTIPLE = "volumeConfirmMultiple";
    static final String PSAR_MIN_DISTANCE_LONG_PCT = "psarMinDistanceLongPct";
    static final String REQUIRE_RSI_EXTREME_WITHIN_BARS = "requireRsiExtremeWithinBars";
    static final String TARGET_R_MULTIPLE = "targetRMultiple";
    static final String SESSION_GATING = "sessionGating";
    static final String MAX_PORTFOLIO_DRAWDOWN_PCT = "maxPortfolioDrawdownPct";
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
                    "Minimum dynamic confidence required before emitting Doflamingo Multi V6 v4 intents.",
                    true, BigDecimal.valueOf(0.62), BigDecimal.ZERO, BigDecimal.ONE, List.of()),
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
                    true, "STRICT", null, null, List.of("NONE", "SOFT", "STRICT")),
            new StrategyParameterDefinition(STOP_MODE, StrategyParameterType.ENUM, "Stop Mode",
                    "Runtime stop model emitted on v4 entry intents.",
                    true, "ATR", null, null, List.of("PERCENT", "ATR", "CLOUD", "ATR_OR_PERCENT_MAX")),
            new StrategyParameterDefinition(STOP_LOSS_PCT, StrategyParameterType.DECIMAL, "Stop Loss Percent",
                    "Percent stop used by fixed or bounded adaptive stop policies.",
                    true, BigDecimal.valueOf(1.50), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MIN_STOP_PCT, StrategyParameterType.DECIMAL, "Minimum Stop Percent",
                    "Lower bound for adaptive stop distance.",
                    true, BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MAX_STOP_PCT, StrategyParameterType.DECIMAL, "Maximum Stop Percent",
                    "Upper bound for adaptive stop distance.",
                    true, BigDecimal.valueOf(2.0), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(ATR_PERIOD, StrategyParameterType.INTEGER, "ATR Period",
                    "ATR period used by adaptive stop scoring.",
                    true, 14, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(ATR_STOP_MULTIPLE, StrategyParameterType.DECIMAL, "ATR Stop Multiple",
                    "ATR multiple used by adaptive stop scoring.",
                    true, BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.1), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(MAX_HOLDING_BARS, StrategyParameterType.INTEGER, "Max Holding Bars",
                    "Maximum bars the runtime may hold an accepted v4 Multi V6 position.",
                    true, 32, BigDecimal.ONE, BigDecimal.valueOf(500), List.of()),
            new StrategyParameterDefinition(STALE_BARS, StrategyParameterType.INTEGER, "Stale Bars",
                    "Bars held before a weak trade is eligible for stale exit.",
                    true, 12, BigDecimal.ONE, BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(STALE_MIN_R, StrategyParameterType.DECIMAL, "Stale Minimum R",
                    "Maximum R multiple below which an old trade is considered stale.",
                    true, BigDecimal.valueOf(0.40), BigDecimal.valueOf(-5), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(COOLDOWN_BARS, StrategyParameterType.INTEGER, "Cooldown Bars",
                    "Bars to wait after an emitted entry or exit before a fresh flat entry.",
                    true, 4, BigDecimal.ZERO, BigDecimal.valueOf(50), List.of()),
            new StrategyParameterDefinition(STRUCTURE_EXIT_CONFIRM_BARS, StrategyParameterType.INTEGER, "Structure Exit Confirm Bars",
                    "Consecutive structure-weak bars required before an explicit structure exit.",
                    true, 2, BigDecimal.ONE, BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(VOLUME_CONFIRM_MULTIPLE, StrategyParameterType.DECIMAL, "Volume Confirm Multiple",
                    "Current volume must be at least this multiple of its 20-bar average; 0 disables the gate.",
                    true, BigDecimal.valueOf(1.10), BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(PSAR_MIN_DISTANCE_LONG_PCT, StrategyParameterType.DECIMAL, "Long PSAR Minimum Distance Percent",
                    "Minimum percent distance between candle low and PSAR for long entries.",
                    true, BigDecimal.valueOf(0.05), BigDecimal.ZERO, BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(REQUIRE_RSI_EXTREME_WITHIN_BARS, StrategyParameterType.INTEGER, "RSI Extreme Lookback Bars",
                    "Require a recent oversold/overbought RSI extreme within this many bars; 0 disables the gate.",
                    true, 8, BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(TARGET_R_MULTIPLE, StrategyParameterType.DECIMAL, "Target R Multiple",
                    "Runtime target expressed as reward-to-risk multiple.",
                    true, BigDecimal.valueOf(2.50), BigDecimal.valueOf(0.10), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(SESSION_GATING, StrategyParameterType.BOOLEAN, "Session Gating",
                    "Whether to block fresh entries outside deterministic NSE regular-session times.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(MAX_PORTFOLIO_DRAWDOWN_PCT, StrategyParameterType.DECIMAL, "Max Portfolio Drawdown Percent",
                    "Block fresh entries when context portfolio drawdown is at or beyond this threshold; 0 disables the gate.",
                    true, BigDecimal.valueOf(8.0), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(ENABLE_SCALE_OUT, StrategyParameterType.BOOLEAN, "Enable Scale Out",
                    "Whether v4 emits one scale-out intent after the configured R multiple.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SCALE_OUT_AT_R, StrategyParameterType.DECIMAL, "Scale Out At R",
                    "Current R multiple required for scale-out.",
                    true, BigDecimal.valueOf(1.25), BigDecimal.valueOf(0.1), BigDecimal.valueOf(10), List.of()),
            new StrategyParameterDefinition(SCALE_OUT_FRACTION, StrategyParameterType.DECIMAL, "Scale Out Fraction",
                    "Open-position fraction requested by scale-out intents.",
                    true, BigDecimal.valueOf(0.40), BigDecimal.valueOf(0.01), BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TRAIL_AFTER_SCALE_OUT, StrategyParameterType.BOOLEAN, "Trail After Scale Out",
                    "Whether post-scale trailing weakness may close the remaining position.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(RISK_FRACTION, StrategyParameterType.DECIMAL, "Risk Fraction",
                    "Portfolio risk fraction requested by v4 entry intents.",
                    true, BigDecimal.valueOf(0.0075), BigDecimal.ZERO, BigDecimal.valueOf(0.02), List.of()),
            new StrategyParameterDefinition(SKIP_MARKET_REGIMES, StrategyParameterType.MULTI_ENUM, "Skip Market Regimes",
                    "Market-context regimes where new Doflamingo Multi V6 v4 entries are skipped.",
                    false, List.of("STRONG_TREND_HIGH_VOLATILITY", "RANGING_HIGH_VOLATILITY"), null, null, DoflamingoMarketRegimeFilter.ALLOWED_REGIME_NAMES),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Whether V4 may emit new short entry intents when bearish lifecycle conditions pass.",
                    true, true, null, null, List.of()),
            new StrategyParameterDefinition(SHORT_CLOUD_MODE, StrategyParameterType.ENUM, "Short Cloud Mode",
                    "Bearish cloud confirmation required for V4 short entries.",
                    true, "CLOSE_BELOW_CLOUD", null, null, List.of("CLOSE_BELOW_CLOUD", "HIGH_BELOW_CLOUD")),
            new StrategyParameterDefinition(ALLOW_REVERSAL, StrategyParameterType.BOOLEAN, "Allow Reversal",
                    "Whether V4 may emit executable long/short reversal intents from an existing position.",
                    true, false, null, null, List.of()),
            new StrategyParameterDefinition(ALLOW_SHORT_SCALE_IN, StrategyParameterType.BOOLEAN, "Allow Short Scale In",
                    "Whether V4 may add once to a winning short when renewed bearish structure appears.",
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
            "Doflamingo Multi Indicator V6 Trend Reversal V4",
            "Short-capable multi-indicator trend reversal lifecycle strategy with structured trade-intent evidence.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
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
                    StrategyCapability.PORTFOLIO_AWARE,
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
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.62)),
                effective.integer(MACD_FAST_PERIOD, DEFAULT_MACD_FAST_PERIOD),
                effective.integer(MACD_SLOW_PERIOD, DEFAULT_MACD_SLOW_PERIOD),
                effective.integer(MACD_SIGNAL_PERIOD, DEFAULT_MACD_SIGNAL_PERIOD),
                effective.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80)),
                effective.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20)),
                effective.string(TREND_FILTER_MODE, "STRICT"),
                effective.string(STOP_MODE, "ATR"),
                effective.decimal(STOP_LOSS_PCT, BigDecimal.valueOf(1.50)),
                effective.decimal(MIN_STOP_PCT, BigDecimal.valueOf(0.60)),
                effective.decimal(MAX_STOP_PCT, BigDecimal.valueOf(2.0)),
                effective.integer(ATR_PERIOD, 14),
                effective.decimal(ATR_STOP_MULTIPLE, BigDecimal.valueOf(1.5)),
                effective.integer(MAX_HOLDING_BARS, 32),
                effective.integer(STALE_BARS, 12),
                effective.decimal(STALE_MIN_R, BigDecimal.valueOf(0.40)),
                effective.integer(COOLDOWN_BARS, 4),
                effective.integer(STRUCTURE_EXIT_CONFIRM_BARS, 2),
                effective.decimal(VOLUME_CONFIRM_MULTIPLE, BigDecimal.valueOf(1.10)),
                effective.decimal(PSAR_MIN_DISTANCE_LONG_PCT, BigDecimal.valueOf(0.05)),
                effective.integer(REQUIRE_RSI_EXTREME_WITHIN_BARS, 8),
                effective.decimal(TARGET_R_MULTIPLE, BigDecimal.valueOf(2.50)),
                effective.bool(SESSION_GATING, true),
                effective.decimal(MAX_PORTFOLIO_DRAWDOWN_PCT, BigDecimal.valueOf(8.0)),
                effective.bool(ENABLE_SCALE_OUT, true),
                effective.decimal(SCALE_OUT_AT_R, BigDecimal.valueOf(1.25)),
                effective.decimal(SCALE_OUT_FRACTION, BigDecimal.valueOf(0.40)),
                effective.bool(TRAIL_AFTER_SCALE_OUT, true),
                effective.decimal(RISK_FRACTION, BigDecimal.valueOf(0.0075)),
                effective.stringList(SKIP_MARKET_REGIMES, List.of("STRONG_TREND_HIGH_VOLATILITY", "RANGING_HIGH_VOLATILITY")),
                effective.bool(ALLOW_SHORTS, true),
                effective.string(SHORT_CLOUD_MODE, "CLOSE_BELOW_CLOUD"),
                effective.bool(ALLOW_REVERSAL, false),
                effective.bool(ALLOW_SHORT_SCALE_IN, false),
                effective.decimal(SHORT_SCALE_IN_AT_R, BigDecimal.valueOf(0.50)),
                effective.integer(MAX_SHORT_SCALE_INS, 1)
        );
    }
}
