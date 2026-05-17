package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextFrameSnapshot;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.enums.marketcontext.MarketStructure;
import org.algotradex.platform.core.api.indicator.RollingIndicators;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.algotradex.strategy.samples.perplexity.PerplexityStrategySupport.BarMath;

public final class IndiaBollingerRsiRangeStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "india-bollinger-rsi-range-v1";
    private static final List<StrategyParameterDefinition> PARAMETERS = List.of(
            PerplexityStrategySupport.integerParam("bollingerPeriod", "Bollinger period", "Closed bars used for moving average and standard deviation.", 20, 5, 100),
            PerplexityStrategySupport.decimalParam("bollingerStdDev", "Bollinger standard deviations", "Band width multiple.", "2.0000", "0.5000", "5.0000"),
            PerplexityStrategySupport.integerParam("rsiPeriod", "RSI period", "Closed bars used for RSI.", 14, 2, 100),
            PerplexityStrategySupport.integerParam("oversoldRsi", "Oversold RSI", "Long mean-reversion RSI threshold.", 30, 1, 80),
            PerplexityStrategySupport.integerParam("overboughtRsi", "Overbought RSI", "Short mean-reversion RSI threshold.", 70, 20, 99),
            PerplexityStrategySupport.integerParam("trendLookbackBars", "Range lookback", "Closed bars used to reject strong directional drift.", 40, 5, 200),
            PerplexityStrategySupport.integerParam("rangeQualityMinAdxBelow", "Range ADX ceiling", "Maximum ADX allowed when a market-context range frame is present.", 20, 1, 100),
            PerplexityStrategySupport.decimalParam("maxRangeDriftPct", "Maximum range drift", "Maximum absolute drift over the range lookback.", "0.0600", "0.0000", "0.5000"),
            PerplexityStrategySupport.decimalParam("minBandWidthPct", "Minimum band width", "Minimum Bollinger band width as a fraction of midline.", "0.0050", "0.0000", "0.5000"),
            PerplexityStrategySupport.decimalParam("stopBandBufferPct", "Stop band buffer", "Additional stop buffer outside the touched band.", "0.0030", "0.0000", "0.1000"),
            PerplexityStrategySupport.decimalParam("targetRMultiple", "Target R multiple", "Reward-to-risk target multiple.", "1.5000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("riskFraction", "Risk fraction", "Fraction of equity requested for risk sizing.", "0.0100", "0.0000", "1.0000"),
            PerplexityStrategySupport.integerParam("maxHoldingBars", "Maximum holding bars", "Lifecycle time exit in execution bars.", 24, 1, 500),
            PerplexityStrategySupport.integerParam("cooldownBars", "Cooldown bars", "Flat bars to wait after an entry or lifecycle exit before a new entry.", 0, 0, 500),
            PerplexityStrategySupport.boolParam("skipOnExpiry", "Skip on expiry", "Skip new entries on heuristic Indian derivative expiry sessions.", false),
            PerplexityStrategySupport.boolParam("enforceSessionGate", "Enforce session gate", "Only allow new entries during the fixed India Bollinger RSI range window.", true),
            PerplexityStrategySupport.boolParam("allowShorts", "Allow shorts", "Allow overbought upper-band reversions.", true)
    );
    private static final StrategyParameterSchema SCHEMA = PerplexityStrategySupport.schema(PARAMETERS);
    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, PerplexityStrategySupport.VERSION),
            PerplexityStrategySupport.PROVIDER_ID,
            "India Bollinger RSI Range",
            "Indian-market range mean reversion with Bollinger bands, RSI extremes, regime gating, and lifecycle exits.",
            List.of("M5", "M15"),
            PerplexityStrategySupport.INDIA_ASSET_CLASSES,
            PerplexityStrategySupport.LIFECYCLE_CAPABILITIES,
            SCHEMA,
            List.of(
                    PerplexityStrategySupport.study("bollinger-bands", "Bollinger Bands", "range", Map.of("period", 20, "stdDev", "2.0"), "Closed-bar band touch and target context."),
                    PerplexityStrategySupport.study("rsi", "RSI", "mean-reversion", Map.of("period", 14), "Oversold/overbought filter."),
                    PerplexityStrategySupport.study("range-regime", "Range Regime", "filter", Map.of("lookbackBars", 40), "Directional drift gate.")
            )
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        return PerplexityStrategySupport.validate(parameters, PARAMETERS);
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyParameters effective = validate(parameters).effectiveParameters();
        return new IndiaBollingerRsiRangeStrategy(IndiaBollingerRsiRangeParameters.from(effective));
    }
}

record IndiaBollingerRsiRangeParameters(
        int bollingerPeriod,
        double bollingerStdDev,
        int rsiPeriod,
        int oversoldRsi,
        int overboughtRsi,
        int trendLookbackBars,
        int rangeQualityMinAdxBelow,
        double maxRangeDriftPct,
        double minBandWidthPct,
        double stopBandBufferPct,
        double targetRMultiple,
        BigDecimal riskFraction,
        int maxHoldingBars,
        int cooldownBars,
        boolean skipOnExpiry,
        boolean enforceSessionGate,
        boolean allowShorts
) {
    static IndiaBollingerRsiRangeParameters from(StrategyParameters parameters) {
        return new IndiaBollingerRsiRangeParameters(
                parameters.integer("bollingerPeriod", 20),
                parameters.decimal("bollingerStdDev", new BigDecimal("2.0000")).doubleValue(),
                parameters.integer("rsiPeriod", 14),
                parameters.integer("oversoldRsi", 30),
                parameters.integer("overboughtRsi", 70),
                parameters.integer("trendLookbackBars", 40),
                parameters.integer("rangeQualityMinAdxBelow", 20),
                parameters.decimal("maxRangeDriftPct", new BigDecimal("0.0600")).doubleValue(),
                parameters.decimal("minBandWidthPct", new BigDecimal("0.0050")).doubleValue(),
                parameters.decimal("stopBandBufferPct", new BigDecimal("0.0030")).doubleValue(),
                parameters.decimal("targetRMultiple", new BigDecimal("1.5000")).doubleValue(),
                parameters.decimal("riskFraction", new BigDecimal("0.0100")),
                parameters.integer("maxHoldingBars", 24),
                parameters.integer("cooldownBars", 0),
                parameters.bool("skipOnExpiry", false),
                parameters.bool("enforceSessionGate", true),
                parameters.bool("allowShorts", true)
        );
    }
}

final class IndiaBollingerRsiRangeStrategy implements TradeIntentStrategy {
    private static final int INVALIDATION_WINDOW_BARS = 2;

    private final IndiaBollingerRsiRangeParameters params;
    private final RollingIndicators.SimpleMovingAverage midlineIndicator;
    private final RollingIndicators.SimpleRsi rsiIndicator;
    private int processedBars;
    private int cooldownRemaining;
    private double currentMidline = Double.NaN;
    private double currentRsi = Double.NaN;
    private Direction activeDirection;
    private double activeLowerBand = Double.NaN;
    private double activeUpperBand = Double.NaN;

    IndiaBollingerRsiRangeStrategy(IndiaBollingerRsiRangeParameters params) {
        this.params = params;
        this.midlineIndicator = new RollingIndicators.SimpleMovingAverage(params.bollingerPeriod());
        this.rsiIndicator = new RollingIndicators.SimpleRsi(params.rsiPeriod());
    }

    @Override
    public String strategyId() {
        return IndiaBollingerRsiRangeStrategyProvider.STRATEGY_ID;
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        BarEvent current = context.currentBar();
        if (context.instrumentPosition().hasPosition()) {
            StrategyIntentResult invalidation = invalidationExit(context);
            if (!invalidation.tradeIntents().isEmpty()) {
                return invalidation;
            }
            if (context.instrumentPosition().barsHeld() >= params.maxHoldingBars()) {
                clearActiveEntry();
                armCooldown();
                return PerplexityStrategySupport.exitResult(strategyId(), current, context.instrumentPosition().side(), SetupType.MEAN_REVERSION, 0.70d,
                        List.of("strategy_family=india_bollinger_rsi_range", "exit=time"),
                        PerplexityStrategySupport.reason("Bollinger RSI lifecycle time exit", List.of("barsHeld=" + context.instrumentPosition().barsHeld()), List.of("bollinger-rsi", "exit"), List.of()));
            }
            return StrategyIntentResult.empty();
        }
        if (consumeCooldown()) {
            return StrategyIntentResult.empty();
        }
        if (params.skipOnExpiry() && PerplexityStrategySupport.isIndianExpirySession(current)) {
            return StrategyIntentResult.empty();
        }
        if (params.enforceSessionGate()
                && !PerplexityStrategySupport.isWithinIndiaWindow(current, PerplexityStrategySupport.BOLLINGER_WINDOW_START, PerplexityStrategySupport.BOLLINGER_WINDOW_END)) {
            return StrategyIntentResult.empty();
        }

        List<BarEvent> bars = context.instrumentHistory();
        int readiness = Math.max(Math.max(params.bollingerPeriod(), params.rsiPeriod() + 1), params.trendLookbackBars());
        if (bars.size() < readiness || !advanceIndicators(bars)) {
            return StrategyIntentResult.empty();
        }

        double midline = currentMidline;
        double stddev = BarMath.stddevClose(bars, params.bollingerPeriod(), midline);
        double upper = midline + (stddev * params.bollingerStdDev());
        double lower = midline - (stddev * params.bollingerStdDev());
        double close = BarMath.close(current);
        double previousClose = BarMath.close(bars.get(bars.size() - 2));
        double rsi = currentRsi;
        double drift = Math.abs(close - BarMath.close(bars.get(bars.size() - params.trendLookbackBars()))) / Math.max(close, 0.0001d);
        double bandWidthPct = (upper - lower) / Math.max(midline, 0.0001d);
        boolean rangeRegime = isRangeRegime(context, drift, bandWidthPct);
        boolean oversold = rsi <= params.oversoldRsi();
        boolean overbought = rsi >= params.overboughtRsi();
        boolean lowerBandTouch = BarMath.low(current) <= lower;
        boolean upperBandTouch = BarMath.high(current) >= upper;
        boolean longSetup = lowerBandTouch && oversold && close > previousClose;
        boolean shortSetup = params.allowShorts() && upperBandTouch && overbought && close < previousClose;
        Direction direction = longSetup ? Direction.LONG : shortSetup ? Direction.SHORT : null;
        if (direction == null || !rangeRegime || !Double.isFinite(rsi)) {
            return StrategyIntentResult.empty();
        }

        double stop = direction == Direction.LONG
                ? lower * (1.0d - params.stopBandBufferPct())
                : upper * (1.0d + params.stopBandBufferPct());
        double risk = Math.max(Math.abs(close - stop), close * 0.0025d);
        double target = direction == Direction.LONG
                ? close + (risk * params.targetRMultiple())
                : close - (risk * params.targetRMultiple());
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                PerplexityStrategySupport.condition("bb.rsi-oversold", "RSI is at reversal extreme", "rsi", rsi, direction == Direction.LONG ? "<=" : ">=", "threshold", direction == Direction.LONG ? params.oversoldRsi() : params.overboughtRsi(), direction == Direction.LONG ? oversold : overbought),
                PerplexityStrategySupport.condition("bb.lower-band-touch", "Price touched reversal band", direction == Direction.LONG ? "low" : "high", direction == Direction.LONG ? BarMath.low(current) : BarMath.high(current), direction == Direction.LONG ? "<=" : ">=", "band", direction == Direction.LONG ? lower : upper, direction == Direction.LONG ? lowerBandTouch : upperBandTouch),
                PerplexityStrategySupport.condition("bb.range-regime", "Directional drift remains range-like", "drift", drift, "<=", "maximum", params.maxRangeDriftPct(), rangeRegime)
        );
        StrategyTradeIntentReason reason = PerplexityStrategySupport.reason(
                "Bollinger band touch with RSI extreme inside a range regime",
                List.of(
                        "midline=" + PerplexityStrategySupport.price(midline),
                        "lower=" + PerplexityStrategySupport.price(lower),
                        "upper=" + PerplexityStrategySupport.price(upper),
                        "rsi=" + PerplexityStrategySupport.price(rsi),
                        "drift=" + PerplexityStrategySupport.price(drift)
                ),
                List.of("india", "bollinger", "rsi", "mean-reversion", "lifecycle"),
                conditions
        );
        double confidence = rangeReversionConfidence(rsi, bandWidthPct);
        StrategyIntentResult result = PerplexityStrategySupport.entryResult(
                strategyId(),
                direction,
                SetupType.MEAN_REVERSION,
                current,
                close,
                stop,
                target,
                confidence,
                params.maxHoldingBars(),
                IntendedHorizonLabel.INTRADAY,
                params.riskFraction(),
                List.of("strategy_family=india_bollinger_rsi_range", "setup=bollinger_rsi_mean_reversion", "market=india", "formula_version=india-bollinger-rsi-range-v1"),
                reason
        );
        trackActiveEntry(direction, lower, upper);
        armCooldown();
        return result;
    }

    private StrategyIntentResult invalidationExit(StrategyExecutionContext context) {
        if (activeDirection == null || context.instrumentPosition().barsHeld() > INVALIDATION_WINDOW_BARS) {
            return StrategyIntentResult.empty();
        }
        double close = BarMath.close(context.currentBar());
        boolean invalidated = switch (activeDirection) {
            case LONG -> close < activeLowerBand * (1.0d - params.stopBandBufferPct())
                    || (context.instrumentPosition().barsHeld() >= INVALIDATION_WINDOW_BARS && close < activeLowerBand);
            case SHORT -> close > activeUpperBand * (1.0d + params.stopBandBufferPct())
                    || (context.instrumentPosition().barsHeld() >= INVALIDATION_WINDOW_BARS && close > activeUpperBand);
            default -> false;
        };
        if (!invalidated) {
            return StrategyIntentResult.empty();
        }
        double lowerBand = activeLowerBand;
        double upperBand = activeUpperBand;
        clearActiveEntry();
        armCooldown();
        return PerplexityStrategySupport.exitResult(strategyId(), context.currentBar(), context.instrumentPosition().side(), SetupType.MEAN_REVERSION, 0.74d,
                List.of("strategy_family=india_bollinger_rsi_range", "exit=invalidation"),
                PerplexityStrategySupport.reason(
                        "Bollinger range fade invalidated by range break or failed band re-entry",
                        List.of(
                                "barsHeld=" + context.instrumentPosition().barsHeld(),
                                "close=" + PerplexityStrategySupport.price(close),
                                "lowerBand=" + PerplexityStrategySupport.price(lowerBand),
                                "upperBand=" + PerplexityStrategySupport.price(upperBand)
                        ),
                        List.of("bollinger-rsi", "exit", "exit=invalidation"),
                        List.of()
                ));
    }

    private void trackActiveEntry(Direction direction, double lowerBand, double upperBand) {
        this.activeDirection = direction;
        this.activeLowerBand = lowerBand;
        this.activeUpperBand = upperBand;
    }

    private void clearActiveEntry() {
        this.activeDirection = null;
        this.activeLowerBand = Double.NaN;
        this.activeUpperBand = Double.NaN;
    }

    private boolean advanceIndicators(List<BarEvent> bars) {
        if (bars.size() <= processedBars) {
            return Double.isFinite(currentMidline) && Double.isFinite(currentRsi);
        }
        for (int index = processedBars; index < bars.size(); index++) {
            double close = BarMath.close(bars.get(index));
            OptionalDouble midline = midlineIndicator.update(close);
            OptionalDouble rsi = rsiIndicator.update(close);
            midline.ifPresent(value -> currentMidline = value);
            rsi.ifPresent(value -> currentRsi = value);
        }
        processedBars = bars.size();
        return Double.isFinite(currentMidline) && Double.isFinite(currentRsi);
    }

    private boolean isRangeRegime(StrategyExecutionContext context, double drift, double bandWidthPct) {
        if (bandWidthPct < params.minBandWidthPct()) {
            return false;
        }
        Optional<MarketContextFrameSnapshot> frame = context.marketContext(context.currentBar().timeframe());
        if (frame.isPresent() && frame.get().marketStructure() != MarketStructure.UNKNOWN) {
            BigDecimal adx = frame.get().componentMetrics().get("adx");
            boolean adxSupportsRange = adx == null || adx.doubleValue() <= params.rangeQualityMinAdxBelow();
            return frame.get().marketStructure() == MarketStructure.RANGE_BOUND && adxSupportsRange;
        }
        return drift <= params.maxRangeDriftPct();
    }

    private boolean consumeCooldown() {
        if (cooldownRemaining <= 0) {
            return false;
        }
        cooldownRemaining--;
        return true;
    }

    private void armCooldown() {
        cooldownRemaining = Math.max(cooldownRemaining, params.cooldownBars());
    }

    private double rangeReversionConfidence(double rsi, double bandWidthPct) {
        // Combine oscillator extremity with a tradable, non-flat band width.
        return Math.min(0.88d, 0.60d + Math.min(0.16d, Math.abs(rsi - 50.0d) / 100.0d) + Math.min(0.12d, bandWidthPct));
    }
}
