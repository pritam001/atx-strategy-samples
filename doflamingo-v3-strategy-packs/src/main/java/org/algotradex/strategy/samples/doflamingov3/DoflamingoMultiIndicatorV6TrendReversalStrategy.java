package org.algotradex.strategy.samples.doflamingov3;

import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Java port of Doflamingo's MULTI_INDICATOR_V6_TREND_REVERSAL entry logic.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalStrategy implements TradeIntentStrategy {
    private static final double MIN_SHORT_PSAR_DISTANCE_PCT = 0.05d;

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
    private final Set<PrimaryMarketRegime> skipMarketRegimes;
    private final boolean allowShorts;
    private final ShortCloudMode shortCloudMode;
    private final boolean allowReversal;
    private final boolean allowShortScaleIn;
    private final BigDecimal shortScaleInAtR;
    private final int maxShortScaleIns;
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
                                                    boolean trailAfterScaleOut, BigDecimal riskFraction,
                                                    List<String> skipMarketRegimes,
                                                    boolean allowShorts,
                                                    String shortCloudMode,
                                                    boolean allowReversal,
                                                    boolean allowShortScaleIn,
                                                    BigDecimal shortScaleInAtR,
                                                    int maxShortScaleIns) {
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
        this.skipMarketRegimes = DoflamingoMarketRegimeFilter.regimes(skipMarketRegimes);
        this.allowShorts = allowShorts;
        this.shortCloudMode = ShortCloudMode.valueOf(requireNonNull(shortCloudMode, "shortCloudMode"));
        this.allowReversal = allowReversal;
        this.allowShortScaleIn = allowShortScaleIn;
        this.shortScaleInAtR = requireNonNull(shortScaleInAtR, "shortScaleInAtR");
        this.maxShortScaleIns = Math.max(0, maxShortScaleIns);
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
        boolean directionNowDown = directionDown(state, current);
        boolean directionPrevDown = state.previousPsar() > history.get(history.size() - 2).ohlcv().high().doubleValue();
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
        double cloudFloor = cloudFloor(state);
        double cloudCeiling = cloudCeiling(state);
        boolean reversalConfirmed = !directionNowUp
                && directionPrevUp
                && close < cloudFloor
                && (sellSignalStoch || sellSignalMacd);
        boolean bullishReversalConfirmed = directionNowUp
                && directionPrevDown
                && close > cloudCeiling
                && (buySignalStoch || buySignalMacd);

        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            if (position.side() == PositionSide.SHORT) {
                return shortPositionIntent(context, state, maybeEma50, bullishReversalConfirmed, buySignalStoch, buySignalMacd);
            }
            return positionIntent(context, state, maybeEma50, maybeAtr, reversalConfirmed, sellSignalStoch, sellSignalMacd);
        }
        if (DoflamingoMarketRegimeFilter.entryBlocked(context, skipMarketRegimes)) {
            return StrategyIntentResult.empty();
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
            return shortEntryIntent(context, state, maybeEma50, maybeTrend, maybeTrendAverage, maybeAtr,
                    directionNowDown, sellSignalMacd, sellSignalStoch);
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
            return shortEntryIntent(context, state, maybeEma50, maybeTrend, maybeTrendAverage, maybeAtr,
                    directionNowDown, sellSignalMacd, sellSignalStoch);
        }

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("multi-v6-v3.psar-direction-up", "PSAR confirms upward direction", "PSAR", state.psar(), "<", "Candle low", current.ohlcv().low().doubleValue(), directionNowUp),
                DoflamingoSignalSupport.condition("multi-v6-v3.buy-macd-confirmed", "MACD buy confirmation", "MACD buy pattern", buySignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalMacd),
                DoflamingoSignalSupport.condition("multi-v6-v3.buy-stoch-confirmed", "Stoch RSI buy confirmation", "Stoch buy pattern", buySignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalStoch),
                DoflamingoSignalSupport.condition("multi-v6-v3.original-momentum-confirmed", "Original reversal momentum confirmed", "Original momentum", originalMomentum ? 1.0d : 0.0d, "=", "Required", 1.0d, originalMomentum),
                DoflamingoSignalSupport.condition("multi-v6-v3.macd-histogram-rising", "MACD histogram is improving", "MACD histogram", state.macdHistogram(), ">", "Previous MACD histogram", state.previousMacdHistogram(), macdHistogramRising),
                DoflamingoSignalSupport.condition("multi-v6-v3.stoch-k-rising", "Stoch RSI K is improving", "Stoch K", state.stochK(), ">", "Previous Stoch K", state.previousStochK(), stochKRising),
                DoflamingoSignalSupport.condition("multi-v6-v3.adaptive-momentum-confirmed", "Adaptive momentum mode permits improving momentum", "Adaptive momentum", adaptiveMomentumEnabled && adaptiveMomentumConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, adaptiveMomentumEnabled && adaptiveMomentumConfirmed),
                DoflamingoSignalSupport.condition("multi-v6-v3.close-above-span-b", "Close above present Span B", "Candle close", close, ">", "Ichimoku Span B", state.presentSpanB(), close > state.presentSpanB()),
                DoflamingoSignalSupport.condition("multi-v6-v3.trend-filter", "Adaptive trend filter passed", "Trend filter", trendFilterPassed ? 1.0d : 0.0d, "=", "Required", 1.0d, trendFilterPassed),
                DoflamingoMarketRegimeFilter.allowedCondition("multi-v6-v3.market-regime-allowed", context, skipMarketRegimes),
                DoflamingoSignalSupport.condition("multi-v6-v3.confidence-threshold", "Dynamic confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
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
                "Doflamingo Multi V6 v3 PSAR, momentum, cloud, and trend-filter reversal entry",
                List.of(
                        "trendFilterMode=" + trendFilterMode,
                        "adaptiveMomentumMode=" + adaptiveMomentumMode,
                        "stopMode=" + stopMode,
                        "confidence=" + confidence,
                        DoflamingoMarketRegimeFilter.marketRegimeEvidence(context),
                        DoflamingoMarketRegimeFilter.skipRegimesEvidence(skipMarketRegimes)
                ),
                List.of("doflamingo", "v3", "adaptive", "multi-v6", "entry", "risk", "confidence"),
                conditions
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private StrategyIntentResult positionIntent(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            OptionalDouble maybeAtr,
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
                    "Doflamingo Multi V6 v3 scale-out at configured R multiple",
                    List.of("currentR=" + currentR, "scaleOutAtR=" + scaleOutAtR, "confidence=" + confidence),
                    List.of("doflamingo", "v3", "adaptive", "multi-v6", "scale-out", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v3.scale-out-r-multiple",
                                    "Current R multiple reached scale-out threshold",
                                    "Current R",
                                    currentR,
                                    ">=",
                                    "Scale-out R",
                                    scaleOutAtR,
                                    true
                            ),
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v3.scale-out-favorable-excursion",
                                    "Position has favorable excursion",
                                    "Max favorable %",
                                    position.maxFavorablePct(),
                                    ">",
                                    "Zero",
                                    BigDecimal.ZERO,
                                    position.maxFavorablePct().signum() > 0
                            ),
                            DoflamingoSignalSupport.condition(
                                    "multi-v6-v3.scale-out-macd-nonnegative",
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
        if (allowReversal && reversalConfirmed) {
            StopSelection stop = shortStopSelection(close, cloudCeiling(state), maybeAtr);
            StrategyTradeIntent reverseIntent = DoflamingoSignalSupport.reverseLongToShortIntent(
                    strategyId(),
                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.REVERSAL,
                    riskFraction,
                    DoflamingoSignalSupport.percentStop(stop.selectedStopPct(), "Doflamingo Multi V6 v3 short " + stopMode + " runtime stop"),
                    maxHoldingBars,
                    "Doflamingo Multi V6 v3 long-to-short reversal",
                    List.of("side=SHORT", "previousSide=LONG", "allowReversal=" + allowReversal,
                            "reversalConfirmed=" + reversalConfirmed, "rawStopPct=" + stop.rawStopPct(),
                            "selectedStopPct=" + stop.selectedStopPct(), "currentR=" + currentR,
                            "positionBarsHeld=" + position.barsHeld(), "confidence=" + confidence),
                    List.of("doflamingo", "v3", "adaptive", "multi-v6", "reversal", "short", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("multi-v6-v3.reverse-long-to-short-enabled", "Reversal is enabled", "Allow reversal", allowReversal ? 1.0d : 0.0d, "=", "Required", 1.0d, allowReversal),
                            DoflamingoSignalSupport.condition("multi-v6-v3.reverse-long-to-short-confirmed", "Bearish reversal confirmation", "Bearish reversal", reversalConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, reversalConfirmed)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(reverseIntent), List.of());
        }
        StrategyTradeIntent exitIntent = DoflamingoSignalSupport.longExitIntent(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL,
                "Doflamingo Multi V6 v3 lifecycle exit",
                List.of(
                        "reversalConfirmed=" + reversalConfirmed,
                        "staleExit=" + staleExit,
                        "structureBreak=" + structureBreak,
                        "postScaleWeakness=" + postScaleWeakness,
                        "trailAfterScaleOut=" + trailAfterScaleOut,
                        "confidence=" + confidence
                ),
                List.of("doflamingo", "v3", "adaptive", "multi-v6", "exit", "risk", "confidence"),
                List.of(
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-reversal-confirmed", "Reversal confirmation", "Reversal confirmed", reversalConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, reversalConfirmed),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-stale-bars", "Stale trade bars held", "Bars held", position.barsHeld(), ">=", "Stale bars", staleBars, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-stale-r", "Stale trade R multiple below threshold", "Current R", currentR, "<=", "Stale minimum R", staleMinR, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-structure-break", "Structure broke below Span B with weak PSAR", "Candle close", close, "<", "Ichimoku Span B", state.presentSpanB(), structureBreak),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-trail-after-scale-out-enabled", "Trailing after scale-out is enabled", "Trail after scale-out", trailAfterScaleOut ? 1.0d : 0.0d, "=", "Required", 1.0d, trailAfterScaleOut),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-post-scale-weakness", "Post-scale trailing weakness", "MACD histogram", state.macdHistogram(), "<", "Previous MACD histogram", state.previousMacdHistogram(), postScaleWeakness),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-sell-stoch", "Stoch RSI sell confirmation", "Stoch sell pattern", sellSignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalStoch),
                        DoflamingoSignalSupport.condition("multi-v6-v3.exit-sell-macd", "MACD sell confirmation", "MACD sell pattern", sellSignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalMacd)
                )
        );
        return new StrategyIntentResult(List.of(), List.of(exitIntent), List.of());
    }

    private StrategyIntentResult shortEntryIntent(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            OptionalDouble maybeTrend,
            OptionalDouble maybeTrendAverage,
            OptionalDouble maybeAtr,
            boolean directionNowDown,
            boolean sellSignalMacd,
            boolean sellSignalStoch
    ) {
        if (!allowShorts) {
            return StrategyIntentResult.empty();
        }
        BarEvent current = context.currentBar();
        double close = current.ohlcv().close().doubleValue();
        double high = current.ohlcv().high().doubleValue();
        double cloudFloor = cloudFloor(state);
        double cloudCeiling = cloudCeiling(state);
        boolean originalMomentum = sellSignalMacd || sellSignalStoch;
        boolean macdHistogramFalling = state.macdHistogram() < state.previousMacdHistogram();
        boolean stochKFalling = state.stochK() < state.previousStochK();
        boolean adaptiveMomentumConfirmed = macdHistogramFalling || stochKFalling;
        boolean adaptiveMomentumEnabled = adaptiveMomentumMode == AdaptiveMomentumMode.ADAPTIVE_CONFIRMATION;
        boolean momentumConfirmed = originalMomentum || (adaptiveMomentumEnabled && adaptiveMomentumConfirmed);
        boolean cloudConfirmed = shortCloudConfirmed(close, high, state);
        boolean trendFilterPassed = shortTrendFilterPassed(close, state, maybeEma50, maybeTrend, maybeTrendAverage,
                sellSignalMacd, adaptiveMomentumEnabled && adaptiveMomentumConfirmed);
        double psarDistancePct = percentDistance(state.psar(), high, close);
        double belowCloudDistancePct = percentDistance(cloudFloor, close, close);
        boolean cleanStructure = cleanShortStructure(close, high, state);
        boolean psarDistanceOk = psarDistancePct >= MIN_SHORT_PSAR_DISTANCE_PCT;
        boolean notOverextended = belowCloudDistancePct <= maxStopPct.doubleValue() * 2.0d;
        if (!directionNowDown || !momentumConfirmed || !cloudConfirmed || !trendFilterPassed
                || !cleanStructure || !psarDistanceOk || !notOverextended) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = shortConfidence(directionNowDown, sellSignalMacd, sellSignalStoch, originalMomentum,
                adaptiveMomentumConfirmed, adaptiveMomentumEnabled && adaptiveMomentumConfirmed && !originalMomentum,
                close, high, state, maybeEma50, maybeTrend, maybeTrendAverage, maybeAtr, trendFilterPassed);
        if (confidence.compareTo(minConfidence) < 0) {
            return StrategyIntentResult.empty();
        }
        StopSelection stop = shortStopSelection(close, cloudCeiling, maybeAtr);

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("multi-v6-v3.short.psar-down", "PSAR confirms downward direction", "PSAR", state.psar(), ">", "Candle high", high, directionNowDown),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.psar-distance", "Short PSAR distance avoids noisy flips", "PSAR distance %", psarDistancePct, ">=", "Minimum distance %", MIN_SHORT_PSAR_DISTANCE_PCT, psarDistanceOk),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.macd-sell", "MACD sell confirmation", "MACD sell pattern", sellSignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalMacd),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.stoch-sell", "Stoch RSI overbought sell confirmation", "Stoch sell pattern", sellSignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, sellSignalStoch),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.cloud-confirmation", "Price is below bearish cloud floor", shortCloudMode == ShortCloudMode.HIGH_BELOW_CLOUD ? "Candle high" : "Candle close", shortCloudMode == ShortCloudMode.HIGH_BELOW_CLOUD ? high : close, "<", "Cloud floor", cloudFloor, cloudConfirmed),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.clean-structure", "Short structure is clean below the cloud", "Clean structure", cleanStructure ? 1.0d : 0.0d, "=", "Required", 1.0d, cleanStructure),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.overextension", "Below-cloud distance is not overextended", "Below-cloud distance %", belowCloudDistancePct, "<=", "Max allowed %", maxStopPct.doubleValue() * 2.0d, notOverextended),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.raw-stop-pct", "Raw short stop distance before bounds", "Raw stop %", stop.rawStopPct(), "<=", "Max stop %", maxStopPct, stop.rawStopPct().compareTo(maxStopPct) <= 0),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.trend-filter", "Bearish trend filter passed", "Trend filter", trendFilterPassed ? 1.0d : 0.0d, "=", "Required", 1.0d, trendFilterPassed),
                DoflamingoMarketRegimeFilter.allowedCondition("multi-v6-v3.short.market-regime-allowed", context, skipMarketRegimes),
                DoflamingoSignalSupport.condition("multi-v6-v3.short.confidence-threshold", "Dynamic short confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
        );
        TradeSignal signal = DoflamingoSignalSupport.shortSignal(strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION, context, confidence, SetupType.REVERSAL);
        StrategyTradeIntent intent = DoflamingoSignalSupport.shortEntryIntent(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL,
                riskFraction,
                DoflamingoSignalSupport.percentStop(stop.selectedStopPct(), "Doflamingo Multi V6 v3 short " + stopMode + " runtime stop"),
                maxHoldingBars,
                "Doflamingo Multi V6 v3 bearish PSAR, momentum, cloud, and trend-filter reversal entry",
                List.of(
                        "side=SHORT",
                        "setup=multi-v6-short-reversal",
                        "psarDirection=DOWN",
                        "psarCurrent=" + state.psar(),
                        "macdHistogram=" + state.macdHistogram(),
                        "macdPreviousHistogram=" + state.previousMacdHistogram(),
                        "macdSignal=" + state.macdSignal(),
                        "stochK=" + state.stochK(),
                        "stochD=" + state.stochD(),
                        "presentSpanA=" + state.presentSpanA(),
                        "presentSpanB=" + state.presentSpanB(),
                        "cloudFloor=" + BigDecimal.valueOf(cloudFloor).setScale(4, RoundingMode.HALF_UP),
                        "cloudCeiling=" + BigDecimal.valueOf(cloudCeiling).setScale(4, RoundingMode.HALF_UP),
                        "shortCloudMode=" + shortCloudMode,
                        "rawStopPct=" + stop.rawStopPct(),
                        "selectedStopPct=" + stop.selectedStopPct(),
                        "stopMode=" + stopMode,
                        "confidenceScore=" + confidence,
                        "confidence=" + confidence,
                        DoflamingoMarketRegimeFilter.marketRegimeEvidence(context),
                        DoflamingoMarketRegimeFilter.skipRegimesEvidence(skipMarketRegimes)
                ),
                List.of("doflamingo", "v3", "adaptive", "multi-v6", "entry", "short", "risk", "confidence"),
                conditions
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private StrategyIntentResult shortPositionIntent(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            boolean bullishReversalConfirmed,
            boolean buySignalStoch,
            boolean buySignalMacd
    ) {
        StrategyInstrumentPosition position = context.instrumentPosition();
        BarEvent current = context.currentBar();
        BigDecimal currentR = position.currentRMultiple() == null ? BigDecimal.ZERO : position.currentRMultiple();
        double close = current.ohlcv().close().doubleValue();
        double high = current.ohlcv().high().doubleValue();
        double cloudFloor = cloudFloor(state);
        double cloudCeiling = cloudCeiling(state);
        boolean cleanStructure = cleanShortStructure(close, high, state);
        boolean renewedBearishMomentum = state.macdHistogram() < state.previousMacdHistogram()
                && state.macdHistogram() < 0.0d
                && state.stochK() <= state.previousStochK();
        boolean shortThesisValid = close < cloudFloor && directionDown(state, current);
        boolean canScaleIn = allowShortScaleIn
                && currentR.compareTo(shortScaleInAtR) >= 0
                && position.scaleInCount() < maxShortScaleIns
                && shortThesisValid
                && renewedBearishMomentum
                && cleanStructure;
        if (canScaleIn) {
            BigDecimal confidence = scaleOutConfidence(position, currentR, state);
            StrategyTradeIntent scaleIntent = DoflamingoSignalSupport.shortScaleInIntent(
                    strategyId(),
                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.REVERSAL,
                    scaleOutFraction,
                    maxShortScaleIns,
                    "Doflamingo Multi V6 v3 short scale-in at configured R multiple",
                    List.of("side=SHORT", "action=SCALE_IN_SHORT", "currentR=" + currentR, "shortScaleInAtR=" + shortScaleInAtR,
                            "scaleInCount=" + position.scaleInCount(), "maxShortScaleIns=" + maxShortScaleIns,
                            "renewedBearishMomentum=" + renewedBearishMomentum, "cleanStructure=" + cleanStructure,
                            "cloudFloor=" + BigDecimal.valueOf(cloudFloor).setScale(4, RoundingMode.HALF_UP),
                            "cloudCeiling=" + BigDecimal.valueOf(cloudCeiling).setScale(4, RoundingMode.HALF_UP),
                            "confidence=" + confidence),
                    List.of("doflamingo", "v3", "adaptive", "multi-v6", "scale-in", "short", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-in-enabled", "Short scale-in is enabled", "Allow short scale-in", allowShortScaleIn ? 1.0d : 0.0d, "=", "Required", 1.0d, allowShortScaleIn),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-in-r-multiple", "Current R multiple reached short scale-in threshold", "Current R", currentR, ">=", "Scale-in R", shortScaleInAtR, true),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-in-count", "Short scale-in count is below maximum", "Scale-in count", position.scaleInCount(), "<", "Max short scale-ins", maxShortScaleIns, position.scaleInCount() < maxShortScaleIns),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-in-momentum", "Renewed bearish momentum is present", "Renewed bearish momentum", renewedBearishMomentum ? 1.0d : 0.0d, "=", "Required", 1.0d, renewedBearishMomentum),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-in-clean-structure", "Short structure remains clean", "Clean structure", cleanStructure ? 1.0d : 0.0d, "=", "Required", 1.0d, cleanStructure)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(scaleIntent), List.of());
        }
        boolean canScaleOut = enableScaleOut
                && position.scaleOutCount() == 0
                && currentR.compareTo(scaleOutAtR) >= 0
                && position.maxFavorablePct().signum() > 0
                && shortThesisValid;
        if (canScaleOut) {
            BigDecimal confidence = scaleOutConfidence(position, currentR, state);
            StrategyTradeIntent scaleIntent = DoflamingoSignalSupport.shortScaleOutIntent(
                    strategyId(),
                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.REVERSAL,
                    scaleOutFraction,
                    "Doflamingo Multi V6 v3 short scale-out at configured R multiple",
                    List.of("side=SHORT", "currentR=" + currentR, "scaleOutAtR=" + scaleOutAtR,
                            "scaleOutCount=" + position.scaleOutCount(), "scaleInCount=" + position.scaleInCount(),
                            "mfeR=" + position.maxFavorablePct(), "confidence=" + confidence),
                    List.of("doflamingo", "v3", "adaptive", "multi-v6", "scale-out", "short", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-out-r-multiple", "Current R multiple reached scale-out threshold", "Current R", currentR, ">=", "Scale-out R", scaleOutAtR, true),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-out-favorable-excursion", "Short has favorable excursion", "Max favorable %", position.maxFavorablePct(), ">", "Zero", BigDecimal.ZERO, position.maxFavorablePct().signum() > 0),
                            DoflamingoSignalSupport.condition("multi-v6-v3.short.scale-out-thesis-valid", "Short thesis remains valid", "Short thesis valid", shortThesisValid ? 1.0d : 0.0d, "=", "Required", 1.0d, shortThesisValid)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(scaleIntent), List.of());
        }

        boolean closeAboveCloud = close > cloudCeiling;
        boolean staleExit = position.barsHeld() >= staleBars && currentR.compareTo(staleMinR) <= 0;
        boolean structureBreak = closeAboveCloud && !directionDown(state, current);
        boolean postScaleRecovery = trailAfterScaleOut
                && position.scaleOutCount() > 0
                && maybeEma50.isPresent()
                && close > maybeEma50.getAsDouble()
                && state.macdHistogram() > state.previousMacdHistogram();
        boolean exit = bullishReversalConfirmed || staleExit || structureBreak || postScaleRecovery;
        if (!exit) {
            return StrategyIntentResult.empty();
        }
        BigDecimal confidence = exitConfidence(position, currentR, state, bullishReversalConfirmed, staleExit, structureBreak, postScaleRecovery);
        if (allowReversal && bullishReversalConfirmed) {
            StrategyTradeIntent reverseIntent = DoflamingoSignalSupport.reverseShortToLongIntent(
                    strategyId(),
                    DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.REVERSAL,
                    riskFraction,
                    stopPolicy(close, state.presentSpanB(), OptionalDouble.empty()),
                    maxHoldingBars,
                    "Doflamingo Multi V6 v3 short-to-long reversal",
                    List.of("side=LONG", "previousSide=SHORT", "allowReversal=" + allowReversal,
                            "bullishReversalConfirmed=" + bullishReversalConfirmed, "currentR=" + currentR,
                            "positionBarsHeld=" + position.barsHeld(), "confidence=" + confidence),
                    List.of("doflamingo", "v3", "adaptive", "multi-v6", "reversal", "long", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("multi-v6-v3.reverse-short-to-long-enabled", "Reversal is enabled", "Allow reversal", allowReversal ? 1.0d : 0.0d, "=", "Required", 1.0d, allowReversal),
                            DoflamingoSignalSupport.condition("multi-v6-v3.reverse-short-to-long-confirmed", "Bullish reversal confirmation", "Bullish reversal", bullishReversalConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, bullishReversalConfirmed)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(reverseIntent), List.of());
        }
        StrategyTradeIntent exitIntent = DoflamingoSignalSupport.shortExitIntent(
                strategyId(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.REVERSAL,
                "Doflamingo Multi V6 v3 short lifecycle exit",
                List.of("side=SHORT", "bullishReversalConfirmed=" + bullishReversalConfirmed, "staleExit=" + staleExit,
                        "structureBreak=" + structureBreak, "postScaleRecovery=" + postScaleRecovery,
                        "positionBarsHeld=" + position.barsHeld(), "currentR=" + currentR,
                        "mfeR=" + position.maxFavorablePct(), "scaleOutCount=" + position.scaleOutCount(),
                        "scaleInCount=" + position.scaleInCount(), "confidence=" + confidence),
                List.of("doflamingo", "v3", "adaptive", "multi-v6", "exit", "short", "risk", "confidence"),
                List.of(
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-bullish-reversal", "Bullish reversal confirmation", "Bullish reversal", bullishReversalConfirmed ? 1.0d : 0.0d, "=", "Required", 1.0d, bullishReversalConfirmed),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-stale-bars", "Stale short bars held", "Bars held", position.barsHeld(), ">=", "Stale bars", staleBars, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-stale-r", "Stale short R multiple below threshold", "Current R", currentR, "<=", "Stale minimum R", staleMinR, staleExit),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-structure-break", "Short structure broke above cloud ceiling with weak PSAR", "Candle close", close, ">", "Cloud ceiling", cloudCeiling, structureBreak),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-post-scale-recovery", "Post-scale short trailing recovery", "MACD histogram", state.macdHistogram(), ">", "Previous MACD histogram", state.previousMacdHistogram(), postScaleRecovery),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-buy-stoch", "Stoch RSI buy confirmation", "Stoch buy pattern", buySignalStoch ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalStoch),
                        DoflamingoSignalSupport.condition("multi-v6-v3.short.exit-buy-macd", "MACD buy confirmation", "MACD buy pattern", buySignalMacd ? 1.0d : 0.0d, "=", "Required", 1.0d, buySignalMacd)
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

    private boolean shortTrendFilterPassed(
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
        double cloudFloor = cloudFloor(state);
        boolean belowEma50 = maybeEma50.isPresent() && close < maybeEma50.getAsDouble();
        boolean weakeningTrend = maybeTrend.isPresent() && maybeTrendAverage.isPresent()
                && maybeTrend.getAsDouble() < maybeTrendAverage.getAsDouble();
        boolean breakdownWithMacdConfirmation = close < cloudFloor && macdConfirmation;
        boolean adaptiveBreakdown = close < cloudFloor && adaptiveMomentumConfirmation;
        if (trendFilterMode == TrendFilterMode.STRICT) {
            return belowEma50 && weakeningTrend;
        }
        return belowEma50 || weakeningTrend || breakdownWithMacdConfirmation || adaptiveBreakdown;
    }

    private boolean shortCloudConfirmed(double close, double high, DoflamingoIndicatorMath.MultiIndicatorState state) {
        double cloudFloor = cloudFloor(state);
        return switch (shortCloudMode) {
            case CLOSE_BELOW_CLOUD -> close < cloudFloor;
            case HIGH_BELOW_CLOUD -> high < cloudFloor;
        };
    }

    private boolean cleanShortStructure(double close, double high, DoflamingoIndicatorMath.MultiIndicatorState state) {
        double cloudFloor = cloudFloor(state);
        double cloudCeiling = cloudCeiling(state);
        return close < cloudFloor
                && high < cloudCeiling
                && state.psar() > high
                && state.macdHistogram() < 0.0d;
    }

    private static double cloudFloor(DoflamingoIndicatorMath.MultiIndicatorState state) {
        return Math.min(state.presentSpanA(), state.presentSpanB());
    }

    private static double cloudCeiling(DoflamingoIndicatorMath.MultiIndicatorState state) {
        return Math.max(state.presentSpanA(), state.presentSpanB());
    }

    private static double percentDistance(double higher, double lower, double denominator) {
        if (denominator <= 0.0d) {
            return 0.0d;
        }
        return Math.max(0.0d, ((higher - lower) / denominator) * 100.0d);
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
                "Doflamingo Multi V6 v3 " + stopMode + " runtime stop"
        );
    }

    private StopSelection shortStopSelection(double close, double cloudCeiling, OptionalDouble maybeAtr) {
        double percentStop = stopLossPct.doubleValue();
        double cloudStop = Math.max(0.1d, ((cloudCeiling - close) / close) * 100.0d);
        double atrStop = maybeAtr.isPresent()
                ? Math.max(0.1d, (maybeAtr.getAsDouble() * atrStopMultiple.doubleValue() / close) * 100.0d)
                : percentStop;
        double raw = switch (stopMode) {
            case PERCENT -> percentStop;
            case ATR -> atrStop;
            case CLOUD -> cloudStop;
            case ATR_OR_PERCENT_MAX -> Math.max(percentStop, atrStop);
        };
        double selected = Math.max(minStopPct.doubleValue(), Math.min(maxStopPct.doubleValue(), raw));
        return new StopSelection(
                BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(selected).setScale(4, RoundingMode.HALF_UP)
        );
    }

    private TradeIntentExitPolicy shortStopPolicy(double close, double cloudCeiling, OptionalDouble maybeAtr) {
        StopSelection stop = shortStopSelection(close, cloudCeiling, maybeAtr);
        return DoflamingoSignalSupport.percentStop(
                stop.selectedStopPct(),
                "Doflamingo Multi V6 v3 short " + stopMode + " runtime stop"
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

    private BigDecimal shortConfidence(
            boolean directionNowDown,
            boolean sellSignalMacd,
            boolean sellSignalStoch,
            boolean originalMomentum,
            boolean adaptiveMomentumConfirmed,
            boolean adaptiveMomentumUsed,
            double close,
            double high,
            DoflamingoIndicatorMath.MultiIndicatorState state,
            OptionalDouble maybeEma50,
            OptionalDouble maybeTrend,
            OptionalDouble maybeTrendAverage,
            OptionalDouble maybeAtr,
            boolean trendFilterPassed
    ) {
        double score = 0.46d;
        if (directionNowDown) {
            score += 0.10d;
        }
        if (sellSignalMacd) {
            score += 0.10d;
        }
        if (sellSignalStoch) {
            score += 0.08d;
        }
        if (originalMomentum && adaptiveMomentumConfirmed) {
            score += 0.02d;
        } else if (adaptiveMomentumUsed) {
            score += 0.04d;
        }
        if (shortCloudConfirmed(close, high, state)) {
            score += 0.08d;
        }
        if (maybeEma50.isPresent() && close < maybeEma50.getAsDouble()) {
            score += 0.06d;
        }
        if (maybeTrend.isPresent() && maybeTrendAverage.isPresent() && maybeTrend.getAsDouble() < maybeTrendAverage.getAsDouble()) {
            score += 0.05d;
        }
        if (maybeAtr.isPresent()) {
            double distanceFromCloud = Math.max(0.0d, cloudFloor(state) - close);
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

    private static boolean directionDown(DoflamingoIndicatorMath.MultiIndicatorState state, BarEvent current) {
        return state.psar() > current.ohlcv().high().doubleValue();
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

    enum ShortCloudMode {
        CLOSE_BELOW_CLOUD,
        HIGH_BELOW_CLOUD
    }

    private record StopSelection(BigDecimal rawStopPct, BigDecimal selectedStopPct) {
    }
}
