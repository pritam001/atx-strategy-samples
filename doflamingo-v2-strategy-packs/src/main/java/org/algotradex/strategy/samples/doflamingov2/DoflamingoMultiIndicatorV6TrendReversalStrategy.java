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
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

/**
 * Java port of Doflamingo's MULTI_INDICATOR_V6_TREND_REVERSAL entry logic.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalStrategy implements TradeIntentStrategy {
    private final BigDecimal minConfidence;
    private final double stochOverbought;
    private final double stochOversold;
    private final TrendFilterMode trendFilterMode;
    private final AdaptiveMomentumMode adaptiveMomentumMode;
    private final StopMode stopMode;
    private final BigDecimal stopLossPct;
    private final BigDecimal minStopPct;
    private final BigDecimal maxStopPct;
    private final int atrPeriod;
    private final BigDecimal atrStopMultiple;
    private final int maxHoldingBars;
    private final int staleBars;
    private final BigDecimal staleMinR;
    private final boolean enableScaleOut;
    private final BigDecimal scaleOutAtR;
    private final BigDecimal scaleOutFraction;
    private final boolean trailAfterScaleOut;
    private final BigDecimal riskFraction;
    private final DoflamingoIndicatorMath.MultiIndicatorTracker indicatorTracker;
    private int processedBars;

    DoflamingoMultiIndicatorV6TrendReversalStrategy(BigDecimal minConfidence, int macdFastPeriod, int macdSlowPeriod,
                                                    int macdSignalPeriod, BigDecimal stochOverbought,
                                                    BigDecimal stochOversold, String trendFilterMode,
                                                    String adaptiveMomentumMode, String stopMode,
                                                    BigDecimal stopLossPct, BigDecimal minStopPct, BigDecimal maxStopPct,
                                                    int atrPeriod, BigDecimal atrStopMultiple, int maxHoldingBars,
                                                    int staleBars, BigDecimal staleMinR, boolean enableScaleOut,
                                                    BigDecimal scaleOutAtR, BigDecimal scaleOutFraction,
                                                    boolean trailAfterScaleOut, BigDecimal riskFraction) {
        this.minConfidence = requireNonNull(minConfidence, "minConfidence");
        this.stochOverbought = requireNonNull(stochOverbought, "stochOverbought").doubleValue();
        this.stochOversold = requireNonNull(stochOversold, "stochOversold").doubleValue();
        this.trendFilterMode = TrendFilterMode.valueOf(requireNonNull(trendFilterMode, "trendFilterMode"));
        this.adaptiveMomentumMode = AdaptiveMomentumMode.valueOf(requireNonNull(adaptiveMomentumMode, "adaptiveMomentumMode"));
        this.stopMode = StopMode.valueOf(requireNonNull(stopMode, "stopMode"));
        this.stopLossPct = requireNonNull(stopLossPct, "stopLossPct");
        this.minStopPct = requireNonNull(minStopPct, "minStopPct");
        this.maxStopPct = requireNonNull(maxStopPct, "maxStopPct");
        this.atrPeriod = atrPeriod;
        this.atrStopMultiple = requireNonNull(atrStopMultiple, "atrStopMultiple");
        this.maxHoldingBars = maxHoldingBars;
        this.staleBars = staleBars;
        this.staleMinR = requireNonNull(staleMinR, "staleMinR");
        this.enableScaleOut = enableScaleOut;
        this.scaleOutAtR = requireNonNull(scaleOutAtR, "scaleOutAtR");
        this.scaleOutFraction = requireNonNull(scaleOutFraction, "scaleOutFraction");
        this.trailAfterScaleOut = trailAfterScaleOut;
        this.riskFraction = requireNonNull(riskFraction, "riskFraction");
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
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
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
        int index = history.size() - 1;
        OptionalDouble maybeEma50 = DoflamingoIndicatorMath.closedBarEma(history, index, 50);
        OptionalDouble maybeTrend = DoflamingoIndicatorMath.closedTrendScore(history, index);
        OptionalDouble maybeTrendAverage = DoflamingoIndicatorMath.closedTrendAverage(history, index, 10);
        OptionalDouble maybeAtr = DoflamingoIndicatorMath.atr(history, index, atrPeriod);
        double close = current.ohlcv().close().doubleValue();
        boolean directionNowUp = state.psar() < current.ohlcv().low().doubleValue();
        boolean directionPrevUp = state.previousPsar() < history.get(history.size() - 2).ohlcv().low().doubleValue();
        boolean buySignalStoch = state.previousStochK() <= state.previousStochD()
                && state.stochK() > state.stochD()
                && Math.min(state.previousStochK(), state.stochK()) < stochOversold + 10.0d;
        boolean sellSignalStoch = state.previousStochK() >= state.previousStochD()
                && state.stochK() < state.stochD()
                && Math.max(state.previousStochK(), state.stochK()) > stochOverbought - 10.0d;
        boolean originalBuySignalMacd = state.macdHistogram() > 0.0d
                && state.previousMacdHistogram() > 0.0d
                && state.secondPreviousMacdHistogram() < 0.0d
                && state.macdSignal() < 0.0d;
        boolean buySignalMacd = originalBuySignalMacd
                || (state.macdHistogram() > 0.0d && state.previousMacdHistogram() <= 0.0d);
        boolean sellSignalMacd = state.macdHistogram() < 0.0d
                && state.previousMacdHistogram() < 0.0d
                && state.secondPreviousMacdHistogram() > 0.0d
                && state.macdSignal() > 0.0d;
        boolean reversalConfirmed = !directionNowUp
                && directionPrevUp
                && close < state.presentSpanB()
                && (sellSignalStoch || sellSignalMacd);

        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            return positionIntent(context, state, maybeEma50, reversalConfirmed, sellSignalStoch, sellSignalMacd);
        }

        boolean originalMomentum = buySignalMacd || buySignalStoch;
        boolean macdHistogramRising = state.macdHistogram() > state.previousMacdHistogram();
        boolean stochKRising = state.stochK() > state.previousStochK();
        boolean adaptiveMomentumConfirmed = macdHistogramRising || stochKRising;
        boolean adaptiveMomentumEnabled = adaptiveMomentumMode == AdaptiveMomentumMode.ADAPTIVE_CONFIRMATION;
        boolean momentumConfirmed = originalMomentum || (adaptiveMomentumEnabled && adaptiveMomentumConfirmed);
        boolean entry = directionNowUp
                && momentumConfirmed
                && close > state.presentSpanB();
        boolean trendFilterPassed = trendFilterPassed(
                close,
                state,
                maybeEma50,
                maybeTrend,
                maybeTrendAverage,
                buySignalMacd,
                adaptiveMomentumEnabled && adaptiveMomentumConfirmed
        );
        if (!entry || !trendFilterPassed) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = confidence(
                directionNowUp,
                buySignalMacd,
                buySignalStoch,
                originalMomentum,
                adaptiveMomentumConfirmed,
                adaptiveMomentumEnabled && adaptiveMomentumConfirmed && !originalMomentum,
                close,
                state,
                maybeEma50,
                maybeTrend,
                maybeTrendAverage,
                maybeAtr,
                trendFilterPassed
        );
        if (confidence.compareTo(minConfidence) < 0) {
            return StrategyIntentResult.empty();
        }

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("multi-v6-v2.psar-direction-up", "PSAR confirms upward direction", "PSAR", state.psar(), "<", "Candle low", current.ohlcv().low().doubleValue(), directionNowUp),
                DoflamingoSignalSupport.condition("multi-v6-v2.buy-macd-confirmed", "MACD buy confirmation", "MACD buy pattern", buySignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalMacd),
                DoflamingoSignalSupport.condition("multi-v6-v2.buy-stoch-confirmed", "Stoch RSI buy confirmation", "Stoch buy pattern", buySignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalStoch),
                DoflamingoSignalSupport.condition("multi-v6-v2.original-momentum-confirmed", "Original reversal momentum confirmed", "Original momentum", originalMomentum ? 1.0d : 0.0d, "=", "Required", 1.0d, originalMomentum),
                DoflamingoSignalSupport.condition("multi-v6-v2.macd-histogram-rising", "MACD histogram is improving", "MACD histogram", state.macdHistogram(), ">", "Previous MACD histogram", state.previousMacdHistogram(), macdHistogramRising),
                DoflamingoSignalSupport.condition("multi-v6-v2.stoch-k-rising", "Stoch RSI K is improving", "Stoch K", state.stochK(), ">", "Previous Stoch K", state.previousStochK(), stochKRising),
                DoflamingoSignalSupport.condition("multi-v6-v2.adaptive-momentum-confirmed", "Adaptive momentum mode permits improving momentum", "Adaptive momentum", adaptiveMomentumEnabled && adaptiveMomentumConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, adaptiveMomentumEnabled && adaptiveMomentumConfirmed),
                DoflamingoSignalSupport.condition("multi-v6-v2.close-above-span-b", "Close above present Span B", "Candle close", close, ">", "Ichimoku Span B", state.presentSpanB(), close > state.presentSpanB()),
                DoflamingoSignalSupport.condition("multi-v6-v2.trend-filter", "Adaptive trend filter passed", "Trend filter", trendFilterPassed ? 1.0d : 0.0d, "=", "Required", 1.0d, trendFilterPassed),
                DoflamingoSignalSupport.condition("multi-v6-v2.confidence-threshold", "Dynamic confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
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
                riskFraction,
                stopPolicy(close, state.presentSpanB(), maybeAtr),
                maxHoldingBars,
                "Doflamingo Multi V6 v2 PSAR, momentum, cloud, and trend-filter reversal entry",
                List.of(
                        "trendFilterMode=" + trendFilterMode,
                        "adaptiveMomentumMode=" + adaptiveMomentumMode,
                        "stopMode=" + stopMode,
                        "confidence=" + confidence
                ),
                List.of("doflamingo", "v2", "adaptive", "multi-v6", "entry", "risk", "confidence"),
                conditions
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private StrategyIntentResult positionIntent(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            boolean reversalConfirmed,
            boolean sellSignalStoch,
            boolean sellSignalMacd
    ) {
        StrategyInstrumentPosition position = context.instrumentPosition();
        BarEvent current = context.currentBar();
        BigDecimal currentR = position.currentRMultiple() == null ? BigDecimal.ZERO : position.currentRMultiple();
        boolean canScaleOut = enableScaleOut
                && position.scaleOutCount() == 0
                && currentR.compareTo(scaleOutAtR) >= 0;
        if (canScaleOut) {
            BigDecimal confidence = scaleOutConfidence(position, currentR, state);
            StrategyTradeIntent scaleIntent = DoflamingoSignalSupport.scaleOutIntent(
                    strategyId(),
                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.REVERSAL,
                    scaleOutFraction,
                    "Doflamingo Multi V6 v2 scale-out at configured R multiple",
                    List.of("currentR=" + currentR, "scaleOutAtR=" + scaleOutAtR, "confidence=" + confidence),
                    List.of("doflamingo", "v2", "adaptive", "multi-v6", "scale-out", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v2.scale-out-r-multiple",
                                    "Current R multiple reached scale-out threshold",
                                    "Current R",
                                    currentR,
                                    ">=",
                                    "Scale-out R",
                                    scaleOutAtR,
                                    true
                            ),
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v2.scale-out-favorable-excursion",
                                    "Position has favorable excursion",
                                    "Max favorable %",
                                    position.maxFavorablePct(),
                                    ">",
                                    "Zero",
                                    BigDecimal.ZERO,
                                    position.maxFavorablePct().signum() > 0
                            ),
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v2.scale-out-macd-nonnegative",
                                    "MACD histogram remains non-negative",
                                    "MACD histogram",
                                    state.macdHistogram(),
                                    ">=",
                                    "Zero",
                                    0.0d,
                                    state.macdHistogram() >= 0.0d
                            )
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(scaleIntent), List.of());
        }

        double close = current.ohlcv().close().doubleValue();
        boolean closeBelowSpanB = close < state.presentSpanB();
        boolean staleExit = position.barsHeld() >= staleBars && currentR.compareTo(staleMinR) <= 0;
        boolean structureBreak = closeBelowSpanB && !directionUp(state, current);
        boolean postScaleWeakness = trailAfterScaleOut
                && position.scaleOutCount() > 0
                && maybeEma50.isPresent()
                && close < maybeEma50.getAsDouble()
                && state.macdHistogram() < state.previousMacdHistogram();
        boolean exit = reversalConfirmed || staleExit || structureBreak || postScaleWeakness;
        if (!exit) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = exitConfidence(position, currentR, state, reversalConfirmed, staleExit, structureBreak, postScaleWeakness);
        StrategyTradeIntent exitIntent = DoflamingoSignalSupport.longExitIntent(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL,
                "Doflamingo Multi V6 v2 lifecycle exit",
                List.of(
                        "reversalConfirmed=" + reversalConfirmed,
                        "staleExit=" + staleExit,
                        "structureBreak=" + structureBreak,
                        "postScaleWeakness=" + postScaleWeakness,
                        "trailAfterScaleOut=" + trailAfterScaleOut,
                        "confidence=" + confidence
                ),
                List.of("doflamingo", "v2", "adaptive", "multi-v6", "exit", "risk", "confidence"),
                List.of(
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-reversal-confirmed", "Reversal confirmation", "Reversal confirmed", reversalConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, reversalConfirmed),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-stale-bars", "Stale trade bars held", "Bars held", position.barsHeld(), ">=", "Stale bars", staleBars, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-stale-r", "Stale trade R multiple below threshold", "Current R", currentR, "<=", "Stale minimum R", staleMinR, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-structure-break", "Structure broke below Span B with weak PSAR", "Candle close", close, "<", "Ichimoku Span B", state.presentSpanB(), structureBreak),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-trail-after-scale-out-enabled", "Trailing after scale-out is enabled", "Trail after scale-out", trailAfterScaleOut ? 1.0d : 0.0d, "=", "Required", 1.0d, trailAfterScaleOut),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-post-scale-weakness", "Post-scale trailing weakness", "MACD histogram", state.macdHistogram(), "<", "Previous MACD histogram", state.previousMacdHistogram(), postScaleWeakness),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-sell-stoch", "Stoch RSI sell confirmation", "Stoch sell pattern", sellSignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalStoch),
                        DoflamingoSignalSupport.condition("multi-v6-v2.exit-sell-macd", "MACD sell confirmation", "MACD sell pattern", sellSignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalMacd)
                )
        );
        return new StrategyIntentResult(List.of(), List.of(exitIntent), List.of());
    }

    private boolean trendFilterPassed(
            double close,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            OptionalDouble maybeTrend,
            OptionalDouble maybeTrendAverage,
            boolean macdConfirmation,
            boolean adaptiveMomentumConfirmation
    ) {
        if (trendFilterMode == TrendFilterMode.NONE) {
            return true;
        }
        boolean aboveEma50 = maybeEma50.isPresent() && close > maybeEma50.getAsDouble();
        boolean improvingTrend = maybeTrend.isPresent() && maybeTrendAverage.isPresent()
                && maybeTrend.getAsDouble() > maybeTrendAverage.getAsDouble();
        boolean reclaimWithMacdConfirmation = close > state.presentSpanB() && macdConfirmation;
        boolean adaptiveReclaim = close > state.presentSpanB() && adaptiveMomentumConfirmation;
        if (trendFilterMode == TrendFilterMode.STRICT) {
            return aboveEma50 && improvingTrend;
        }
        return aboveEma50 || improvingTrend || reclaimWithMacdConfirmation || adaptiveReclaim;
    }

    private TradeIntentExitPolicy stopPolicy(double close, double presentSpanB, OptionalDouble maybeAtr) {
        double percentStop = stopLossPct.doubleValue();
        double cloudStop = Math.max(0.1d, ((close - presentSpanB) / close) * 100.0d);
        double atrStop = maybeAtr.isPresent()
                ? Math.max(0.1d, (maybeAtr.getAsDouble() * atrStopMultiple.doubleValue() / close) * 100.0d)
                : percentStop;
        double selected = switch (stopMode) {
            case PERCENT -> percentStop;
            case ATR -> atrStop;
            case CLOUD -> cloudStop;
            case ATR_OR_PERCENT_MAX -> Math.max(percentStop, atrStop);
        };
        selected = Math.max(minStopPct.doubleValue(), Math.min(maxStopPct.doubleValue(), selected));
        return DoflamingoSignalSupport.percentStop(
                BigDecimal.valueOf(selected).setScale(4, RoundingMode.HALF_UP),
                "Doflamingo Multi V6 v2 " + stopMode + " runtime stop"
        );
    }

    private BigDecimal confidence(
            boolean directionNowUp,
            boolean buySignalMacd,
            boolean buySignalStoch,
            boolean originalMomentum,
            boolean adaptiveMomentumConfirmed,
            boolean adaptiveMomentumUsed,
            double close,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            OptionalDouble maybeTrend,
            OptionalDouble maybeTrendAverage,
            OptionalDouble maybeAtr,
            boolean trendFilterPassed
    ) {
        double score = 0.46d;
        if (directionNowUp) {
            score += 0.10d;
        }
        if (buySignalMacd) {
            score += 0.10d;
        }
        if (buySignalStoch) {
            score += 0.08d;
        }
        if (originalMomentum && adaptiveMomentumConfirmed) {
            score += 0.02d;
        } else if (adaptiveMomentumUsed) {
            score += 0.04d;
        }
        if (close > state.presentSpanB()) {
            score += 0.08d;
        }
        if (maybeEma50.isPresent() && close > maybeEma50.getAsDouble()) {
            score += 0.06d;
        }
        if (maybeTrend.isPresent() && maybeTrendAverage.isPresent() && maybeTrend.getAsDouble() > maybeTrendAverage.getAsDouble()) {
            score += 0.05d;
        }
        if (maybeAtr.isPresent()) {
            double distanceFromCloud = Math.max(0.0d, close - state.presentSpanB());
            if (distanceFromCloud <= maybeAtr.getAsDouble() * 3.0d) {
                score += 0.04d;
            }
        }
        if (trendFilterPassed) {
            score += 0.04d;
        }
        return boundedConfidence(score);
    }

    private BigDecimal scaleOutConfidence(
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            DoflamingoIndicatorMath.MultiIndicatorState state
    ) {
        double score = 0.54d;
        double rMargin = currentR.subtract(scaleOutAtR).doubleValue();
        if (rMargin >= 0.0d) {
            score += 0.12d + Math.min(0.10d, rMargin * 0.05d);
        }
        if (position.maxFavorablePct().signum() > 0) {
            score += 0.07d;
        }
        if (state.macdHistogram() >= 0.0d) {
            score += 0.05d;
        }
        return boundedConfidence(score);
    }

    private BigDecimal exitConfidence(
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            boolean reversalConfirmed,
            boolean staleExit,
            boolean structureBreak,
            boolean postScaleWeakness
    ) {
        double score = 0.54d;
        if (reversalConfirmed) {
            score += 0.13d;
        }
        if (structureBreak) {
            score += 0.11d;
        }
        if (staleExit) {
            score += 0.09d;
        }
        if (postScaleWeakness) {
            score += 0.10d;
        }
        if (currentR.compareTo(staleMinR) <= 0) {
            score += 0.06d;
        }
        if (position.maxAdversePct().signum() > 0) {
            score += 0.04d;
        }
        if (state.macdHistogram() < state.previousMacdHistogram()) {
            score += 0.03d;
        }
        return boundedConfidence(score);
    }

    private static BigDecimal boundedConfidence(double score) {
        return BigDecimal.valueOf(Math.max(0.50d, Math.min(0.95d, score))).setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean directionUp(DoflamingoIndicatorMath.MultiIndicatorState state, BarEvent current) {
        return state.psar() < current.ohlcv().low().doubleValue();
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

    private enum TrendFilterMode {
        NONE,
        SOFT,
        STRICT
    }

    private enum AdaptiveMomentumMode {
        STRICT_REVERSAL,
        ADAPTIVE_CONFIRMATION
    }

    private enum StopMode {
        PERCENT,
        ATR,
        CLOUD,
        ATR_OR_PERCENT_MAX
    }
}
