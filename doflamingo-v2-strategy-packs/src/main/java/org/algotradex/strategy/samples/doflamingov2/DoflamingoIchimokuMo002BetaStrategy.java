package org.algotradex.strategy.samples.doflamingov2;

import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Java port of Doflamingo's ICHIMOKU_MOMENTUM_MO_002_BETA entry logic.
 */
public final class DoflamingoIchimokuMo002BetaStrategy implements TradeIntentStrategy {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final EntryMode entryMode;
    private final BigDecimal minConfidence;
    private final int trendAverageLookback;
    private final int maxHoldingBars;
    private final BigDecimal riskFraction;
    private final boolean enableProtectiveStop;
    private final StopMode stopMode;
    private final int atrPeriod;
    private final BigDecimal atrStopMultiple;
    private final BigDecimal cloudStopBufferPct;
    private final int structureExitConfirmBars;
    private final int cooldownBars;
    private final Set<PrimaryMarketRegime> skipMarketRegimes;
    private int structureWeakBars;
    private int cooldownRemaining;

    DoflamingoIchimokuMo002BetaStrategy(
            String entryMode,
            BigDecimal minConfidence,
            int trendAverageLookback,
            int maxHoldingBars,
            BigDecimal riskFraction,
            boolean enableProtectiveStop,
            String stopMode,
            int atrPeriod,
            BigDecimal atrStopMultiple,
            BigDecimal cloudStopBufferPct,
            int structureExitConfirmBars,
            int cooldownBars,
            List<String> skipMarketRegimes
    ) {
        this.entryMode = EntryMode.valueOf(requireNonNull(entryMode, "entryMode"));
        this.minConfidence = requireNonNull(minConfidence, "minConfidence");
        if (trendAverageLookback < 2) {
            throw new IllegalArgumentException("trendAverageLookback must be >= 2");
        }
        this.trendAverageLookback = trendAverageLookback;
        this.maxHoldingBars = maxHoldingBars;
        this.riskFraction = requireNonNull(riskFraction, "riskFraction");
        this.enableProtectiveStop = enableProtectiveStop;
        this.stopMode = StopMode.valueOf(requireNonNull(stopMode, "stopMode"));
        this.atrPeriod = atrPeriod;
        this.atrStopMultiple = requireNonNull(atrStopMultiple, "atrStopMultiple");
        this.cloudStopBufferPct = requireNonNull(cloudStopBufferPct, "cloudStopBufferPct");
        this.structureExitConfirmBars = Math.max(1, structureExitConfirmBars);
        this.cooldownBars = Math.max(0, cooldownBars);
        this.skipMarketRegimes = DoflamingoMarketRegimeFilter.regimes(skipMarketRegimes);
    }

    @Override
    public String strategyId() {
        return DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_ID;
    }

    @Override
    public List<StrategyCapability> lifecycleCapabilities() {
        return List.of(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        int index = history.size() - 1;
        if (index < DoflamingoIndicatorMath.TREND_MINIMUM_INDEX) {
            return StrategyIntentResult.empty();
        }
        Optional<DoflamingoIndicatorMath.IchimokuSnapshot> maybeIchimoku = DoflamingoIndicatorMath.ichimoku(history);
        OptionalDouble maybeEma9 = DoflamingoIndicatorMath.closedBarEma(history, index, 9);
        OptionalDouble maybeTrend = DoflamingoIndicatorMath.closedTrendScore(history, index);
        OptionalDouble maybeAverage = DoflamingoIndicatorMath.closedTrendAverage(history, index, trendAverageLookback);
        OptionalDouble maybeAtr = DoflamingoIndicatorMath.atr(history, index, atrPeriod);
        if (maybeIchimoku.isEmpty() || maybeEma9.isEmpty() || maybeTrend.isEmpty() || maybeAverage.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        DoflamingoIndicatorMath.IchimokuSnapshot ichimoku = maybeIchimoku.get();
        BarEvent current = context.currentBar();
        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            StrategyIntentResult exit = exitIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), maybeTrend.getAsDouble(), maybeAverage.getAsDouble());
            if (!exit.tradeIntents().isEmpty()) {
                cooldownRemaining = cooldownBars;
            }
            return exit;
        }

        structureWeakBars = 0;
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return StrategyIntentResult.empty();
        }
        if (DoflamingoMarketRegimeFilter.entryBlocked(context, skipMarketRegimes)) {
            return StrategyIntentResult.empty();
        }

        double close = current.ohlcv().close().doubleValue();
        double low = current.ohlcv().low().doubleValue();
        double high = current.ohlcv().high().doubleValue();
        double trend = maybeTrend.getAsDouble();
        double trendAverage = maybeAverage.getAsDouble();
        double previousHigh = previousHigh(history, index, 5);

        boolean strictLowAboveSpanB = low > ichimoku.presentSpanB();
        boolean strictEmaAboveSpanA = maybeEma9.getAsDouble() > ichimoku.presentSpanA();
        boolean strictPresentRedCloud = ichimoku.presentSpanB() > ichimoku.presentSpanA();
        boolean futureGreenCloud = ichimoku.futureSpanA() > ichimoku.futureSpanB();
        boolean conversionAboveBase = ichimoku.conversionLine() > ichimoku.baseLine();
        boolean trendAboveAverage = trend > trendAverage;
        boolean trendPositive = trend > 0.0d;
        boolean strictSetup = strictLowAboveSpanB
                && strictEmaAboveSpanA
                && strictPresentRedCloud
                && futureGreenCloud
                && conversionAboveBase
                && trendAboveAverage
                && trendPositive;

        boolean earlyCloseAboveSpanB = close > ichimoku.presentSpanB();
        boolean earlyBreakout = close > previousHigh;
        boolean earlySetup = earlyCloseAboveSpanB
                && futureGreenCloud
                && conversionAboveBase
                && trendAboveAverage
                && earlyBreakout;

        boolean accepted = switch (entryMode) {
            case STRICT_BETA -> strictSetup;
            case EARLY_TRANSITION -> earlySetup;
            case HYBRID -> strictSetup || earlySetup;
        };
        if (!accepted) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = confidence(strictSetup, earlySetup, close, high, low, ichimoku, trend, trendAverage, maybeAtr);
        if (confidence.compareTo(minConfidence) < 0) {
            return StrategyIntentResult.empty();
        }

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("ichimoku-v2.strict-low-above-span-b", "Strict candle low above present Span B", "Candle low", low, ">", "Ichimoku Span B", ichimoku.presentSpanB(), strictLowAboveSpanB),
                DoflamingoSignalSupport.condition("ichimoku-v2.strict-ema-above-span-a", "Strict EMA(9) above present Span A", "EMA(9)", maybeEma9.getAsDouble(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), strictEmaAboveSpanA),
                DoflamingoSignalSupport.condition("ichimoku-v2.strict-present-red-cloud", "Strict present cloud remains red", "Ichimoku Span B", ichimoku.presentSpanB(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), strictPresentRedCloud),
                DoflamingoSignalSupport.condition("ichimoku-v2.future-green-cloud", "Future cloud is green", "Future Span A", ichimoku.futureSpanA(), ">", "Future Span B", ichimoku.futureSpanB(), futureGreenCloud),
                DoflamingoSignalSupport.condition("ichimoku-v2.conversion-above-base", "Conversion line above base line", "Conversion line", ichimoku.conversionLine(), ">", "Base line", ichimoku.baseLine(), conversionAboveBase),
                DoflamingoSignalSupport.condition("ichimoku-v2.trend-above-average", "Trend score above moving average", "Trend score", trend, ">", "Trend average", trendAverage, trendAboveAverage),
                DoflamingoSignalSupport.condition("ichimoku-v2.trend-positive", "Trend score is positive", "Trend score", trend, ">", "Zero", 0.0d, trendPositive),
                DoflamingoSignalSupport.condition("ichimoku-v2.early-close-above-span-b", "Early close reclaimed Span B", "Candle close", close, ">", "Ichimoku Span B", ichimoku.presentSpanB(), earlyCloseAboveSpanB),
                DoflamingoSignalSupport.condition("ichimoku-v2.early-prior-high-breakout", "Early transition broke prior five-bar high", "Candle close", close, ">", "Prior five-bar high", previousHigh, earlyBreakout),
                DoflamingoMarketRegimeFilter.allowedCondition("ichimoku-v2.market-regime-allowed", context, skipMarketRegimes),
                DoflamingoSignalSupport.condition("ichimoku-v2.confidence-threshold", "Dynamic confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
        );

        TradeSignal signal = DoflamingoSignalSupport.longSignal(
                strategyId(),
                DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION
        );
        StrategyTradeIntent intent = DoflamingoSignalSupport.longEntryIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                riskFraction,
                stopPolicy(close, ichimoku, maybeAtr),
                maxHoldingBars,
                strictSetup ? "Doflamingo Ichimoku v2 strict cloud momentum entry" : "Doflamingo Ichimoku v2 early cloud transition entry",
                List.of(
                        "entryMode=" + entryMode,
                        "confidence=" + confidence,
                        "strictSetup=" + strictSetup,
                        "earlySetup=" + earlySetup,
                        DoflamingoMarketRegimeFilter.marketRegimeEvidence(context),
                        DoflamingoMarketRegimeFilter.skipRegimesEvidence(skipMarketRegimes)
                ),
                List.of("doflamingo", "v2", "adaptive", "ichimoku", "entry", "risk", "confidence"),
                conditions
        );
        cooldownRemaining = cooldownBars;
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private StrategyIntentResult exitIfNeeded(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            double ema9,
            double trend,
            double trendAverage
    ) {
        BarEvent current = context.currentBar();
        boolean spanAOverHigh = ichimoku.presentSpanA() > current.ohlcv().high().doubleValue();
        boolean closeBelowSpanB = current.ohlcv().close().doubleValue() < ichimoku.presentSpanB();
        boolean conversionBelowBase = ichimoku.conversionLine() < ichimoku.baseLine();
        boolean emaBelowSpanA = ema9 < ichimoku.presentSpanA();
        structureWeakBars = conversionBelowBase ? structureWeakBars + 1 : 0;
        boolean structureConfirmed = structureWeakBars >= structureExitConfirmBars;
        boolean exit = spanAOverHigh || closeBelowSpanB || structureConfirmed || emaBelowSpanA;
        if (!exit) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = confidence(true, false, current.ohlcv().close().doubleValue(), current.ohlcv().high().doubleValue(),
                current.ohlcv().low().doubleValue(), ichimoku, trend, trendAverage, OptionalDouble.empty());
        StrategyTradeIntent intent = DoflamingoSignalSupport.longExitIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                "Doflamingo Ichimoku v2 structure exit",
                List.of(
                        "spanAOverHigh=" + spanAOverHigh,
                        "closeBelowSpanB=" + closeBelowSpanB,
                        "structureWeakBars=" + structureWeakBars,
                        "emaBelowSpanA=" + emaBelowSpanA
                ),
                List.of("doflamingo", "v2", "adaptive", "ichimoku", "exit", "risk"),
                List.of(
                        DoflamingoSignalSupport.condition("ichimoku-v2.exit-span-a-over-high", "Present Span A over candle high", "Ichimoku Span A", ichimoku.presentSpanA(), ">", "Candle high", current.ohlcv().high().doubleValue(), spanAOverHigh),
                        DoflamingoSignalSupport.condition("ichimoku-v2.exit-close-below-span-b", "Close below present Span B", "Candle close", current.ohlcv().close().doubleValue(), "<", "Ichimoku Span B", ichimoku.presentSpanB(), closeBelowSpanB),
                        DoflamingoSignalSupport.condition("ichimoku-v2.exit-conversion-confirmed", "Conversion below base for configured bars", "Structure weak bars", structureWeakBars, ">=", "Required bars", structureExitConfirmBars, structureConfirmed),
                        DoflamingoSignalSupport.condition("ichimoku-v2.exit-ema-below-span-a", "EMA(9) below present Span A", "EMA(9)", ema9, "<", "Ichimoku Span A", ichimoku.presentSpanA(), emaBelowSpanA)
                )
        );
        return new StrategyIntentResult(List.of(), List.of(intent), List.of());
    }

    private TradeIntentExitPolicy stopPolicy(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        if (!enableProtectiveStop || stopMode == StopMode.NONE) {
            return TradeIntentExitPolicy.none();
        }
        BigDecimal stopPct = stopPercent(close, ichimoku, maybeAtr);
        return DoflamingoSignalSupport.percentStop(stopPct, "Doflamingo Ichimoku v2 " + stopMode + " protective stop");
    }

    private BigDecimal stopPercent(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        double cloudPct = cloudStopPercent(close, ichimoku, cloudStopBufferPct).doubleValue();
        double atrPct = maybeAtr.isPresent()
                ? Math.max(0.1d, (maybeAtr.getAsDouble() * atrStopMultiple.doubleValue() / close) * 100.0d)
                : cloudPct;
        double selected = switch (stopMode) {
            case CLOUD -> cloudPct;
            case ATR -> atrPct;
            case CLOUD_OR_ATR -> Math.max(cloudPct, atrPct);
            case NONE -> 0.0d;
        };
        return BigDecimal.valueOf(selected).setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal cloudStopPercent(
            double close,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            BigDecimal cloudStopBufferPct
    ) {
        double cloudSupport = Math.min(ichimoku.presentSpanB(), ichimoku.baseLine());
        double cloudStopPrice = cloudSupport * (1.0d - cloudStopBufferPct.doubleValue() / 100.0d);
        double cloudPct = Math.max(0.1d, ((close - cloudStopPrice) / close) * 100.0d);
        return BigDecimal.valueOf(cloudPct).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal confidence(
            boolean strictSetup,
            boolean earlySetup,
            double close,
            double high,
            double low,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            double trend,
            double trendAverage,
            OptionalDouble maybeAtr
    ) {
        double score = 0.45d;
        if (ichimoku.futureSpanA() > ichimoku.futureSpanB()) {
            score += 0.08d;
        }
        if (close > ichimoku.presentSpanB()) {
            score += 0.08d;
        }
        if (low > ichimoku.presentSpanB()) {
            score += 0.04d;
        }
        if (trend > trendAverage) {
            score += Math.min(0.12d, (trend - trendAverage) / 1000.0d + 0.04d);
        }
        double cloudRange = Math.max(1.0d, Math.abs(ichimoku.presentSpanB() - ichimoku.presentSpanA()));
        double distanceFromSupport = Math.max(0.0d, close - ichimoku.presentSpanB());
        if (distanceFromSupport <= cloudRange * 2.0d) {
            score += 0.08d;
        }
        if (maybeAtr.isPresent()) {
            double candleRange = Math.max(0.01d, high - low);
            if (candleRange <= maybeAtr.getAsDouble() * 1.8d) {
                score += 0.08d;
            }
        } else {
            score += 0.04d;
        }
        if (strictSetup) {
            score += 0.10d;
        } else if (earlySetup) {
            score -= 0.04d;
        }
        return BigDecimal.valueOf(Math.max(0.50d, Math.min(0.95d, score))).setScale(4, RoundingMode.HALF_UP);
    }

    private static double previousHigh(List<BarEvent> history, int index, int lookback) {
        double high = Double.NEGATIVE_INFINITY;
        int start = Math.max(0, index - lookback);
        for (int candidate = start; candidate < index; candidate++) {
            high = Math.max(high, history.get(candidate).ohlcv().high().doubleValue());
        }
        return high;
    }

    private enum EntryMode {
        STRICT_BETA,
        EARLY_TRANSITION,
        HYBRID
    }

    private enum StopMode {
        NONE,
        CLOUD,
        ATR,
        CLOUD_OR_ATR
    }
}
