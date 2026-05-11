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
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Java port of Doflamingo's MULTI_INDICATOR_V6_TREND_REVERSAL entry logic.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalStrategy implements TradeIntentStrategy {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final BigDecimal confidence;
    private final double stochOverbought;
    private final double stochOversold;
    private final BigDecimal stopLossPct;
    private final DoflamingoIndicatorMath.MultiIndicatorTracker indicatorTracker;
    private BigDecimal activeStopLoss;
    private int processedBars;

    DoflamingoMultiIndicatorV6TrendReversalStrategy(BigDecimal confidence, int macdFastPeriod, int macdSlowPeriod,
                                                    int macdSignalPeriod, BigDecimal stochOverbought,
                                                    BigDecimal stochOversold, BigDecimal stopLossPct) {
        this.confidence = requireNonNull(confidence, "confidence");
        this.stochOverbought = requireNonNull(stochOverbought, "stochOverbought").doubleValue();
        this.stochOversold = requireNonNull(stochOversold, "stochOversold").doubleValue();
        this.stopLossPct = requireNonNull(stopLossPct, "stopLossPct");
        this.indicatorTracker = DoflamingoIndicatorMath.multiIndicatorTracker(macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
    }

    @Override
    public String strategyId() {
        return DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_ID;
    }

    @Override
    public List<StrategyCapability> lifecycleCapabilities() {
        return List.of(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING
        );
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        Optional<DoflamingoIndicatorMath.MultiIndicatorState> maybeState = advanceIndicators(history);
        if (maybeState.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        DoflamingoIndicatorMath.MultiIndicatorState state = maybeState.get();
        BarEvent current = context.currentBar();
        boolean directionNowUp = state.psar() < current.ohlcv().low().doubleValue();
        boolean directionPrevUp = state.previousPsar() < history.get(history.size() - 2).ohlcv().low().doubleValue();
        boolean buySignalStoch = state.previousStochK() <= state.previousStochD()
                && state.stochK() > state.stochD()
                && state.stochK() < stochOversold;
        boolean sellSignalStoch = state.previousStochK() >= state.previousStochD()
                && state.stochK() < state.stochD()
                && state.stochK() > stochOverbought;
        boolean buySignalMacd = state.macdHistogram() > 0.0d
                && state.previousMacdHistogram() > 0.0d
                && state.secondPreviousMacdHistogram() < 0.0d
                && state.macdSignal() < 0.0d;
        boolean sellSignalMacd = state.macdHistogram() < 0.0d
                && state.previousMacdHistogram() < 0.0d
                && state.secondPreviousMacdHistogram() > 0.0d
                && state.macdSignal() > 0.0d;
        boolean reversalConfirmed = !directionNowUp
                && directionPrevUp
                && current.ohlcv().close().doubleValue() < state.presentSpanB()
                && (sellSignalStoch || sellSignalMacd);

        if (activeStopLoss != null) {
            if (current.ohlcv().low().compareTo(activeStopLoss) <= 0 || reversalConfirmed) {
                BigDecimal stopAtExit = activeStopLoss;
                activeStopLoss = null;
                if (context.instrumentPosition().hasPosition()) {
                    return new StrategyIntentResult(
                            List.of(),
                            List.of(DoflamingoSignalSupport.longExitIntent(
                                    strategyId(),
                                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                                    context,
                                    confidence,
                                    SetupType.REVERSAL,
                                    reversalConfirmed ? "Doflamingo Multi V6 reversal confirmation exit" : "Doflamingo Multi V6 fixed stop exit",
                                    List.of(
                                            DoflamingoSignalSupport.condition("fixed-stop-hit", "Candle low hit fixed stop", "Candle low", current.ohlcv().low().doubleValue(), "<=", "Active stop", stopAtExit.doubleValue(), current.ohlcv().low().compareTo(stopAtExit) <= 0),
                                            DoflamingoSignalSupport.condition("psar-reversal-down", "PSAR flipped below long direction", "PSAR", state.psar(), ">=", "Candle low", current.ohlcv().low().doubleValue(), !directionNowUp),
                                            DoflamingoSignalSupport.condition("close-below-span-b", "Close below present Span B", "Candle close", current.ohlcv().close().doubleValue(), "<", "Ichimoku Span B", state.presentSpanB(), current.ohlcv().close().doubleValue() < state.presentSpanB()),
                                            DoflamingoSignalSupport.condition("sell-stoch-confirmed", "Stoch RSI sell confirmation", "Stoch K", state.stochK(), "<", "Stoch D", state.stochD(), sellSignalStoch),
                                            DoflamingoSignalSupport.condition("sell-macd-confirmed", "MACD sell confirmation", "MACD histogram", state.macdHistogram(), "<", "Zero", 0.0d, sellSignalMacd)
                                    )
                            )),
                            List.of()
                    );
                }
            }
            return StrategyIntentResult.empty();
        }

        boolean entry = directionNowUp
                && (buySignalMacd || buySignalStoch)
                && current.ohlcv().close().doubleValue() > state.presentSpanB();
        if (!entry || context.instrumentPosition().hasPosition()) {
            return StrategyIntentResult.empty();
        }

        activeStopLoss = current.ohlcv().close().multiply(
                BigDecimal.ONE.subtract(stopLossPct.divide(BigDecimal.valueOf(100), MATH_CONTEXT)),
                MATH_CONTEXT
        );
        TradeSignal signal = DoflamingoSignalSupport.longSignal(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL
        );
        StrategyTradeIntent intent = DoflamingoSignalSupport.longEntryIntent(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL,
                stopLossPct,
                "Doflamingo Multi V6 PSAR, MACD/StochRSI, and cloud reversal entry",
                List.of(
                        DoflamingoSignalSupport.condition("psar-direction-up", "PSAR confirms upward direction", "PSAR", state.psar(), "<", "Candle low", current.ohlcv().low().doubleValue(), true),
                        DoflamingoSignalSupport.condition("buy-macd-confirmed", "MACD buy confirmation", "MACD histogram", state.macdHistogram(), ">", "Zero", 0.0d, buySignalMacd),
                        DoflamingoSignalSupport.condition("buy-stoch-confirmed", "Stoch RSI buy confirmation", "Stoch K", state.stochK(), ">", "Stoch D", state.stochD(), buySignalStoch),
                        DoflamingoSignalSupport.condition("close-above-span-b", "Close above present Span B", "Candle close", current.ohlcv().close().doubleValue(), ">", "Ichimoku Span B", state.presentSpanB(), true),
                        DoflamingoSignalSupport.condition("fixed-stop-distance", "Fixed stop percent configured", "Stop loss percent", stopLossPct.doubleValue(), "=", "Configured stop loss percent", stopLossPct.doubleValue(), true)
                )
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private Optional<DoflamingoIndicatorMath.MultiIndicatorState> advanceIndicators(List<BarEvent> history) {
        if (history.size() <= processedBars) {
            return Optional.empty();
        }
        Optional<DoflamingoIndicatorMath.MultiIndicatorState> state = Optional.empty();
        for (int index = processedBars; index < history.size(); index++) {
            state = indicatorTracker.update(history.get(index));
        }
        processedBars = history.size();
        return state;
    }
}
