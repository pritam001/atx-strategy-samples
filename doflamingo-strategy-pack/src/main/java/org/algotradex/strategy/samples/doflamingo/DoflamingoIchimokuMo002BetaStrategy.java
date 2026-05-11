package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

/**
 * Java port of Doflamingo's ICHIMOKU_MOMENTUM_MO_002_BETA entry logic.
 */
public final class DoflamingoIchimokuMo002BetaStrategy implements TradeIntentStrategy {
    private final BigDecimal confidence;
    private final int trendAverageLookback;
    private boolean activeLongSetup;

    DoflamingoIchimokuMo002BetaStrategy(BigDecimal confidence, int trendAverageLookback) {
        this.confidence = requireNonNull(confidence, "confidence");
        if (trendAverageLookback < 2) {
            throw new IllegalArgumentException("trendAverageLookback must be >= 2");
        }
        this.trendAverageLookback = trendAverageLookback;
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
                StrategyCapability.EXIT_INTENT
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
        OptionalDouble maybeEma9 = DoflamingoIndicatorMath.laggedEma(history, index, 9);
        OptionalDouble maybeTrend = DoflamingoIndicatorMath.trendScore(history, index);
        OptionalDouble maybeAverage = DoflamingoIndicatorMath.trendAverage(history, index, trendAverageLookback);
        if (maybeIchimoku.isEmpty() || maybeEma9.isEmpty() || maybeTrend.isEmpty() || maybeAverage.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        DoflamingoIndicatorMath.IchimokuSnapshot ichimoku = maybeIchimoku.get();
        BarEvent current = context.currentBar();
        if (activeLongSetup) {
            if (ichimoku.presentSpanA() > current.ohlcv().high().doubleValue()) {
                activeLongSetup = false;
                if (context.instrumentPosition().hasPosition()) {
                    return new StrategyIntentResult(
                            List.of(),
                            List.of(DoflamingoSignalSupport.longExitIntent(
                                    strategyId(),
                                    DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_VERSION,
                                    context,
                                    confidence,
                                    SetupType.CONTINUATION,
                                    "Doflamingo Ichimoku span-A over candle-high exit",
                                    List.of(DoflamingoSignalSupport.condition(
                                            "span-a-over-high",
                                            "Present Span A over candle high",
                                            "Ichimoku Span A",
                                            ichimoku.presentSpanA(),
                                            ">",
                                            "Candle high",
                                            current.ohlcv().high().doubleValue(),
                                            true
                                    ))
                            )),
                            List.of()
                    );
                }
            }
            return StrategyIntentResult.empty();
        }

        boolean entry = current.ohlcv().low().doubleValue() > ichimoku.presentSpanB()
                && maybeEma9.getAsDouble() > ichimoku.presentSpanA()
                && ichimoku.presentSpanB() > ichimoku.presentSpanA()
                && ichimoku.futureSpanA() > ichimoku.futureSpanB()
                && ichimoku.conversionLine() > ichimoku.baseLine()
                && maybeTrend.getAsDouble() > maybeAverage.getAsDouble()
                && maybeTrend.getAsDouble() > 0.0d;
        if (!entry || context.instrumentPosition().hasPosition()) {
            return StrategyIntentResult.empty();
        }

        activeLongSetup = true;
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
                null,
                "Doflamingo Ichimoku cloud momentum entry",
                List.of(
                        DoflamingoSignalSupport.condition("low-above-span-b", "Candle low above present Span B", "Candle low", current.ohlcv().low().doubleValue(), ">", "Ichimoku Span B", ichimoku.presentSpanB(), true),
                        DoflamingoSignalSupport.condition("ema-above-span-a", "EMA(9) above present Span A", "EMA(9)", maybeEma9.getAsDouble(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), true),
                        DoflamingoSignalSupport.condition("span-b-above-span-a", "Present Span B above present Span A", "Ichimoku Span B", ichimoku.presentSpanB(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), true),
                        DoflamingoSignalSupport.condition("future-span-a-above-b", "Future Span A above Future Span B", "Future Span A", ichimoku.futureSpanA(), ">", "Future Span B", ichimoku.futureSpanB(), true),
                        DoflamingoSignalSupport.condition("conversion-above-base", "Conversion line above base line", "Conversion line", ichimoku.conversionLine(), ">", "Base line", ichimoku.baseLine(), true),
                        DoflamingoSignalSupport.condition("trend-above-average", "Trend score above moving average", "Trend score", maybeTrend.getAsDouble(), ">", "Trend average", maybeAverage.getAsDouble(), true),
                        DoflamingoSignalSupport.condition("trend-positive", "Trend score positive", "Trend score", maybeTrend.getAsDouble(), ">", "Zero", 0.0d, true)
                )
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }
}
