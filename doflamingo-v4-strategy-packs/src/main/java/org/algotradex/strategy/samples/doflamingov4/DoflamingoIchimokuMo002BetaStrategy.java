package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.common.enums.PositionSide;
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
 * Short-capable lifecycle variant of Doflamingo's Ichimoku momentum setup.
 * <p>
 * The strategy evaluates closed-bar Ichimoku, EMA, trend-score, ATR, and optional market-regime
 * context. It can emit long or short entry signals and intents, plus exits, reversals, and short
 * scale-outs from the current runtime position snapshot when the configured lifecycle rules pass.
 * <p>
 * Mutable counters track cooldown and structure weakness within a single replay/instrument run. A
 * fresh instance is expected per run; the class is deterministic for the same ordered market and
 * position snapshots, but is not thread-safe.
 * <p>
 * This sample owns signal/intent evidence only. It does not execute trades, route broker orders,
 * enforce fills, reserve capital, or perform portfolio accounting.
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
    private final BigDecimal minKumoThicknessAtr;
    private final BigDecimal minFutureCloudSpreadAtr;
    private final boolean requireFutureCloudWidening;
    private final boolean requireChikouClearSpace;
    private final int tkCrossFreshBars;
    private final BigDecimal maxEntryAtrFromCloudTop;
    private final HtfCloudBiasMode htfCloudBiasMode;
    private final BigDecimal targetRMultiple;
    private final boolean sessionGating;
    private final BigDecimal volumeConfirmMultiple;
    private final BigDecimal atrExpansionMultiple;
    private final Set<PrimaryMarketRegime> skipMarketRegimes;
    private final boolean allowShorts;
    private final ShortCloudPriceMode shortCloudPriceMode;
    private final ShortEmaCloudMode shortEmaCloudMode;
    private final BigDecimal minStopPct;
    private final BigDecimal maxStopPct;
    private final boolean allowReversal;
    private final int shortStaleBars;
    private final BigDecimal shortStaleMinR;
    private final boolean allowShortScaleOut;
    private final BigDecimal shortScaleOutAtR;
    private final BigDecimal shortScaleOutFraction;
    private int structureWeakBars;
    private int shortStructureWeakBars;
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
            BigDecimal minKumoThicknessAtr,
            BigDecimal minFutureCloudSpreadAtr,
            boolean requireFutureCloudWidening,
            boolean requireChikouClearSpace,
            int tkCrossFreshBars,
            BigDecimal maxEntryAtrFromCloudTop,
            String htfCloudBiasMode,
            BigDecimal targetRMultiple,
            boolean sessionGating,
            BigDecimal volumeConfirmMultiple,
            BigDecimal atrExpansionMultiple,
            List<String> skipMarketRegimes,
            boolean allowShorts,
            String shortCloudPriceMode,
            String shortEmaCloudMode,
            BigDecimal minStopPct,
            BigDecimal maxStopPct,
            boolean allowReversal,
            int shortStaleBars,
            BigDecimal shortStaleMinR,
            boolean allowShortScaleOut,
            BigDecimal shortScaleOutAtR,
            BigDecimal shortScaleOutFraction
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
        this.minKumoThicknessAtr = requireNonNull(minKumoThicknessAtr, "minKumoThicknessAtr");
        this.minFutureCloudSpreadAtr = requireNonNull(minFutureCloudSpreadAtr, "minFutureCloudSpreadAtr");
        this.requireFutureCloudWidening = requireFutureCloudWidening;
        this.requireChikouClearSpace = requireChikouClearSpace;
        this.tkCrossFreshBars = Math.max(0, tkCrossFreshBars);
        this.maxEntryAtrFromCloudTop = requireNonNull(maxEntryAtrFromCloudTop, "maxEntryAtrFromCloudTop");
        this.htfCloudBiasMode = HtfCloudBiasMode.valueOf(requireNonNull(htfCloudBiasMode, "htfCloudBiasMode"));
        this.targetRMultiple = requireNonNull(targetRMultiple, "targetRMultiple");
        this.sessionGating = sessionGating;
        this.volumeConfirmMultiple = requireNonNull(volumeConfirmMultiple, "volumeConfirmMultiple");
        this.atrExpansionMultiple = requireNonNull(atrExpansionMultiple, "atrExpansionMultiple");
        this.skipMarketRegimes = DoflamingoMarketRegimeFilter.regimes(skipMarketRegimes);
        this.allowShorts = allowShorts;
        this.shortCloudPriceMode = ShortCloudPriceMode.valueOf(requireNonNull(shortCloudPriceMode, "shortCloudPriceMode"));
        this.shortEmaCloudMode = ShortEmaCloudMode.valueOf(requireNonNull(shortEmaCloudMode, "shortEmaCloudMode"));
        this.minStopPct = requireNonNull(minStopPct, "minStopPct");
        this.maxStopPct = requireNonNull(maxStopPct, "maxStopPct");
        this.allowReversal = allowReversal;
        this.shortStaleBars = Math.max(1, shortStaleBars);
        this.shortStaleMinR = requireNonNull(shortStaleMinR, "shortStaleMinR");
        this.allowShortScaleOut = allowShortScaleOut;
        this.shortScaleOutAtR = requireNonNull(shortScaleOutAtR, "shortScaleOutAtR");
        this.shortScaleOutFraction = requireNonNull(shortScaleOutFraction, "shortScaleOutFraction");
    }

    @Override
    public String strategyId() {
        return DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_ID;
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
            StrategyIntentResult exit = position.side() == PositionSide.SHORT
                    ? shortExitIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), maybeTrend.getAsDouble(), maybeAverage.getAsDouble())
                    : exitIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), maybeTrend.getAsDouble(), maybeAverage.getAsDouble(), maybeAtr);
            if (!exit.tradeIntents().isEmpty()) {
                cooldownRemaining = cooldownBars;
            }
            return exit;
        }

        structureWeakBars = 0;
        shortStructureWeakBars = 0;
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return StrategyIntentResult.empty();
        }
        if (!DoflamingoSessionGate.entryAllowed(context, sessionGating)) {
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
            return shortEntryIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), trend, trendAverage, maybeAtr);
        }

        double atr = maybeAtr.orElse(Math.max(0.01d, high - low));
        double cloudFloor = Math.min(ichimoku.presentSpanA(), ichimoku.presentSpanB());
        double cloudCeiling = Math.max(ichimoku.presentSpanA(), ichimoku.presentSpanB());
        double presentCloudThicknessAtr = Math.abs(ichimoku.presentSpanA() - ichimoku.presentSpanB()) / atr;
        double futureCloudSpreadAtr = Math.abs(ichimoku.futureSpanA() - ichimoku.futureSpanB()) / atr;
        boolean kumoThicknessOk = presentCloudThicknessAtr >= minKumoThicknessAtr.doubleValue();
        boolean futureCloudSpreadOk = futureCloudSpreadAtr >= minFutureCloudSpreadAtr.doubleValue();
        boolean futureCloudWideningOk = !requireFutureCloudWidening
                || Math.abs(ichimoku.futureSpanA() - ichimoku.futureSpanB()) >= Math.abs(ichimoku.presentSpanA() - ichimoku.presentSpanB());
        boolean chikouClear = !requireChikouClearSpace || DoflamingoIndicatorMath.chikouClearSpace(history, index, true);
        boolean tkFresh = tkCrossFreshBars == 0 || DoflamingoIndicatorMath.tkConfirmationFresh(history, index, tkCrossFreshBars, true);
        double entryAtrFromCloudTop = Math.max(0.0d, close - cloudCeiling) / atr;
        boolean notOverextended = entryAtrFromCloudTop <= maxEntryAtrFromCloudTop.doubleValue();
        boolean h1BiasOk = htfCloudBiasOk(context, true);
        boolean volumeOk = !earlySetup || DoflamingoIndicatorMath.volumeAtLeastAverageMultiple(history, index, 20, volumeConfirmMultiple.doubleValue());
        boolean atrExpansionOk = !earlySetup || DoflamingoIndicatorMath.atrExpansionAtLeast(history, index, atrPeriod, atrExpansionMultiple.doubleValue());
        if (!kumoThicknessOk || !futureCloudSpreadOk || !futureCloudWideningOk || !chikouClear
                || !tkFresh || !notOverextended || !h1BiasOk || !volumeOk || !atrExpansionOk) {
            return shortEntryIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), trend, trendAverage, maybeAtr);
        }

        BigDecimal confidence = confidence(strictSetup, earlySetup, close, high, low, ichimoku, trend, trendAverage, maybeAtr);
        if (confidence.compareTo(minConfidence) < 0) {
            return shortEntryIfNeeded(context, ichimoku, maybeEma9.getAsDouble(), trend, trendAverage, maybeAtr);
        }

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("ichimoku-v4.strict-low-above-span-b", "Strict candle low above present Span B", "Candle low", low, ">", "Ichimoku Span B", ichimoku.presentSpanB(), strictLowAboveSpanB),
                DoflamingoSignalSupport.condition("ichimoku-v4.strict-ema-above-span-a", "Strict EMA(9) above present Span A", "EMA(9)", maybeEma9.getAsDouble(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), strictEmaAboveSpanA),
                DoflamingoSignalSupport.condition("ichimoku-v4.strict-present-red-cloud", "Strict present cloud remains red", "Ichimoku Span B", ichimoku.presentSpanB(), ">", "Ichimoku Span A", ichimoku.presentSpanA(), strictPresentRedCloud),
                DoflamingoSignalSupport.condition("ichimoku-v4.future-green-cloud", "Future cloud is green", "Future Span A", ichimoku.futureSpanA(), ">", "Future Span B", ichimoku.futureSpanB(), futureGreenCloud),
                DoflamingoSignalSupport.condition("ichimoku-v4.conversion-above-base", "Conversion line above base line", "Conversion line", ichimoku.conversionLine(), ">", "Base line", ichimoku.baseLine(), conversionAboveBase),
                DoflamingoSignalSupport.condition("ichimoku-v4.trend-above-average", "Trend score above moving average", "Trend score", trend, ">", "Trend average", trendAverage, trendAboveAverage),
                DoflamingoSignalSupport.condition("ichimoku-v4.trend-positive", "Trend score is positive", "Trend score", trend, ">", "Zero", 0.0d, trendPositive),
                DoflamingoSignalSupport.condition("ichimoku-v4.early-close-above-span-b", "Early close reclaimed Span B", "Candle close", close, ">", "Ichimoku Span B", ichimoku.presentSpanB(), earlyCloseAboveSpanB),
                DoflamingoSignalSupport.condition("ichimoku-v4.early-prior-high-breakout", "Early transition broke prior five-bar high", "Candle close", close, ">", "Prior five-bar high", previousHigh, earlyBreakout),
                DoflamingoSignalSupport.condition("ichimoku-v4.h1-cloud-bias", "H1 cloud bias aligns with trade direction", "H1 cloud bias", h1BiasOk ? 1.0d : 0.0d, "=", "Required", 1.0d, h1BiasOk),
                DoflamingoSignalSupport.condition("ichimoku-v4.kumo-thickness", "Present cloud thickness is meaningful", "Kumo thickness ATR", presentCloudThicknessAtr, ">=", "Minimum", minKumoThicknessAtr.doubleValue(), kumoThicknessOk),
                DoflamingoSignalSupport.condition("ichimoku-v4.future-cloud-spread", "Future cloud spread is meaningful", "Future spread ATR", futureCloudSpreadAtr, ">=", "Minimum", minFutureCloudSpreadAtr.doubleValue(), futureCloudSpreadOk),
                DoflamingoSignalSupport.condition("ichimoku-v4.future-cloud-widening", "Future cloud is not collapsing against the setup", "Future widening", futureCloudWideningOk ? 1.0d : 0.0d, "=", "Required", 1.0d, futureCloudWideningOk),
                DoflamingoSignalSupport.condition("ichimoku-v4.chikou-clear-space", "Chikou lookback price space is clear", "Chikou clear", chikouClear ? 1.0d : 0.0d, "=", "Required", 1.0d, chikouClear),
                DoflamingoSignalSupport.condition("ichimoku-v4.tk-cross-fresh", "Conversion/base confirmation is fresh", "TK fresh", tkFresh ? 1.0d : 0.0d, "=", "Required", 1.0d, tkFresh),
                DoflamingoSignalSupport.condition("ichimoku-v4.entry-overextension", "Entry is not overextended from cloud top", "Entry ATR from cloud top", entryAtrFromCloudTop, "<=", "Maximum", maxEntryAtrFromCloudTop.doubleValue(), notOverextended),
                DoflamingoSignalSupport.condition("ichimoku-v4.early-volume-confirmed", "Early transition volume is confirmed", "Volume confirmed", volumeOk ? 1.0d : 0.0d, "=", "Required", 1.0d, volumeOk),
                DoflamingoSignalSupport.condition("ichimoku-v4.early-atr-expansion", "Early transition ATR expansion is confirmed", "ATR expansion", atrExpansionOk ? 1.0d : 0.0d, "=", "Required", 1.0d, atrExpansionOk),
                DoflamingoSessionGate.allowedCondition("ichimoku-v4.session-allowed", context, sessionGating),
                DoflamingoMarketRegimeFilter.allowedCondition("ichimoku-v4.market-regime-allowed", context, skipMarketRegimes),
                DoflamingoSignalSupport.condition("ichimoku-v4.confidence-threshold", "Dynamic confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
        );

        TradeSignal signal = DoflamingoSignalSupport.longSignal(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION
        );
        StrategyTradeIntent intent = DoflamingoSignalSupport.longEntryIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                riskFraction,
                stopPolicy(close, ichimoku, maybeAtr),
                maxHoldingBars,
                strictSetup ? "Doflamingo Ichimoku v4 strict cloud momentum entry" : "Doflamingo Ichimoku v4 early cloud transition entry",
                List.of(
                        "entryMode=" + entryMode,
                        "confidence=" + confidence,
                        "strictSetup=" + strictSetup,
                        "earlySetup=" + earlySetup,
                        "targetRMultiple=" + targetRMultiple,
                        "htfCloudBiasMode=" + htfCloudBiasMode,
                        DoflamingoSessionGate.sessionEvidence(context, sessionGating),
                        DoflamingoMarketRegimeFilter.marketRegimeEvidence(context),
                        DoflamingoMarketRegimeFilter.skipRegimesEvidence(skipMarketRegimes)
                ),
                List.of("doflamingo", "v4", "ichimoku", "htf", "entry", "risk", "confidence"),
                conditions
        );
        cooldownRemaining = cooldownBars;
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    private StrategyIntentResult shortEntryIfNeeded(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            double ema9,
            double trend,
            double trendAverage,
            OptionalDouble maybeAtr
    ) {
        if (!allowShorts) {
            return StrategyIntentResult.empty();
        }
        BarEvent current = context.currentBar();
        double close = current.ohlcv().close().doubleValue();
        double low = current.ohlcv().low().doubleValue();
        double high = current.ohlcv().high().doubleValue();
        double cloudFloor = Math.min(ichimoku.presentSpanA(), ichimoku.presentSpanB());
        double cloudCeiling = Math.max(ichimoku.presentSpanA(), ichimoku.presentSpanB());

        boolean priceBelowCloud = switch (shortCloudPriceMode) {
            case CLOSE_BELOW_CLOUD -> close < cloudFloor;
            case HIGH_BELOW_CLOUD -> high < cloudFloor;
        };
        boolean emaBelowCloud = switch (shortEmaCloudMode) {
            case EMA9_BELOW_SPAN_B -> ema9 < ichimoku.presentSpanB();
            case EMA9_BELOW_CLOUD -> ema9 < cloudFloor;
        };
        boolean presentCloudGreen = ichimoku.presentSpanA() > ichimoku.presentSpanB();
        boolean futureCloudRed = ichimoku.futureSpanB() > ichimoku.futureSpanA();
        boolean conversionBelowBase = ichimoku.conversionLine() < ichimoku.baseLine();
        boolean trendBelowAverage = trend < trendAverage;
        boolean trendNegative = trend < 0.0d;
        boolean accepted = priceBelowCloud
                && emaBelowCloud
                && presentCloudGreen
                && futureCloudRed
                && conversionBelowBase
                && trendBelowAverage
                && trendNegative;
        if (!accepted) {
            return StrategyIntentResult.empty();
        }
        StopSelection stop = shortStopSelection(close, ichimoku, maybeAtr);
        if (enableProtectiveStop && stop.rawStopPct().compareTo(maxStopPct) > 0) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = shortConfidence(close, high, low, ichimoku, ema9, trend, trendAverage, maybeAtr);
        if (confidence.compareTo(minConfidence) < 0) {
            return StrategyIntentResult.empty();
        }

        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                DoflamingoSignalSupport.condition("ichimoku-v4.short-price-below-cloud", "Short price below present cloud", shortCloudPriceMode == ShortCloudPriceMode.HIGH_BELOW_CLOUD ? "Candle high" : "Candle close", shortCloudPriceMode == ShortCloudPriceMode.HIGH_BELOW_CLOUD ? high : close, "<", "Cloud floor", cloudFloor, priceBelowCloud),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-ema-below-cloud", "Short EMA(9) below configured cloud boundary", "EMA(9)", ema9, "<", shortEmaCloudMode == ShortEmaCloudMode.EMA9_BELOW_SPAN_B ? "Ichimoku Span B" : "Cloud floor", shortEmaCloudMode == ShortEmaCloudMode.EMA9_BELOW_SPAN_B ? ichimoku.presentSpanB() : cloudFloor, emaBelowCloud),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-present-green-cloud", "Short present cloud remains green", "Ichimoku Span A", ichimoku.presentSpanA(), ">", "Ichimoku Span B", ichimoku.presentSpanB(), presentCloudGreen),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-future-red-cloud", "Short future cloud is red", "Future Span B", ichimoku.futureSpanB(), ">", "Future Span A", ichimoku.futureSpanA(), futureCloudRed),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-conversion-below-base", "Short conversion line below base line", "Conversion line", ichimoku.conversionLine(), "<", "Base line", ichimoku.baseLine(), conversionBelowBase),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-trend-below-average", "Short trend score below moving average", "Trend score", trend, "<", "Trend average", trendAverage, trendBelowAverage),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-trend-negative", "Short trend score is negative", "Trend score", trend, "<", "Zero", 0.0d, trendNegative),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-raw-stop-pct", "Short raw stop distance is within max stop", "Raw stop %", stop.rawStopPct(), "<=", "Max stop %", maxStopPct, stop.rawStopPct().compareTo(maxStopPct) <= 0),
                DoflamingoMarketRegimeFilter.allowedCondition("ichimoku-v4.market-regime-allowed", context, skipMarketRegimes),
                DoflamingoSignalSupport.condition("ichimoku-v4.short-confidence-threshold", "Short dynamic confidence meets threshold", "Confidence", confidence.doubleValue(), ">=", "Minimum confidence", minConfidence.doubleValue(), true)
        );

        TradeSignal signal = DoflamingoSignalSupport.shortSignal(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION
        );
        StrategyTradeIntent intent = DoflamingoSignalSupport.shortEntryIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                riskFraction,
                enableProtectiveStop && stopMode != StopMode.NONE
                        ? DoflamingoSignalSupport.percentStopWithRrTarget(stop.selectedStopPct(), targetRMultiple, "Doflamingo Ichimoku v4 short " + stopMode + " protective stop")
                        : TradeIntentExitPolicy.none(),
                maxHoldingBars,
                "Doflamingo Ichimoku v4 short cloud momentum entry",
                List.of(
                        "side=SHORT",
                        "entryMode=" + entryMode,
                        "shortCloudPriceMode=" + shortCloudPriceMode,
                        "shortEmaCloudMode=" + shortEmaCloudMode,
                        "confidence=" + confidence,
                        "cloudFloor=" + BigDecimal.valueOf(cloudFloor).setScale(4, RoundingMode.HALF_UP),
                        "cloudCeiling=" + BigDecimal.valueOf(cloudCeiling).setScale(4, RoundingMode.HALF_UP),
                        "rawStopPct=" + stop.rawStopPct(),
                        "selectedStopPct=" + stop.selectedStopPct(),
                        "stopMode=" + stopMode,
                        DoflamingoMarketRegimeFilter.marketRegimeEvidence(context),
                        DoflamingoMarketRegimeFilter.skipRegimesEvidence(skipMarketRegimes)
                ),
                List.of("doflamingo", "v4", "strict", "ichimoku", "short", "entry", "risk", "confidence"),
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
            double trendAverage,
            OptionalDouble maybeAtr
    ) {
        BarEvent current = context.currentBar();
        StrategyInstrumentPosition position = context.instrumentPosition();
        double close = current.ohlcv().close().doubleValue();
        boolean spanAOverHigh = ichimoku.presentSpanA() > current.ohlcv().high().doubleValue();
        boolean closeBelowSpanB = close < ichimoku.presentSpanB();
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
        if (allowReversal && closeBelowSpanB && conversionBelowBase) {
            StopSelection stop = shortStopSelection(close, ichimoku, maybeAtr);
            if (stop.rawStopPct().compareTo(maxStopPct) <= 0) {
                StrategyTradeIntent intent = DoflamingoSignalSupport.reverseLongToShortIntent(
                        strategyId(),
                        DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                        context,
                        confidence,
                        SetupType.CONTINUATION,
                        riskFraction,
                        enableProtectiveStop && stopMode != StopMode.NONE
                                ? DoflamingoSignalSupport.percentStopWithRrTarget(stop.selectedStopPct(), targetRMultiple, "Doflamingo Ichimoku v4 short " + stopMode + " protective stop")
                                : TradeIntentExitPolicy.none(),
                        maxHoldingBars,
                        "Doflamingo Ichimoku v4 long-to-short reversal",
                        List.of("side=SHORT", "previousSide=LONG", "allowReversal=" + allowReversal,
                                "closeBelowSpanB=" + closeBelowSpanB, "conversionBelowBase=" + conversionBelowBase,
                                "rawStopPct=" + stop.rawStopPct(), "selectedStopPct=" + stop.selectedStopPct(),
                                "positionBarsHeld=" + position.barsHeld(), "currentR=" + position.currentRMultiple(),
                                "mfeR=" + position.maxFavorablePct(), "maeR=" + position.maxAdversePct(),
                                "scaleOutCount=" + position.scaleOutCount(), "scaleInCount=" + position.scaleInCount(),
                                "confidence=" + confidence),
                        List.of("doflamingo", "v4", "strict", "ichimoku", "reversal", "short", "risk", "confidence"),
                        List.of(
                                DoflamingoSignalSupport.condition("ichimoku-v4.reverse-long-to-short-enabled", "Reversal is enabled", "Allow reversal", allowReversal ? 1.0d : 0.0d, "=", "Required", 1.0d, allowReversal),
                                DoflamingoSignalSupport.condition("ichimoku-v4.reverse-long-to-short-close-below-span-b", "Close lost present Span B", "Candle close", close, "<", "Ichimoku Span B", ichimoku.presentSpanB(), closeBelowSpanB),
                                DoflamingoSignalSupport.condition("ichimoku-v4.reverse-long-to-short-conversion", "Conversion line crossed below base", "Conversion line", ichimoku.conversionLine(), "<", "Base line", ichimoku.baseLine(), conversionBelowBase),
                                DoflamingoSignalSupport.condition("ichimoku-v4.reverse-long-to-short-raw-stop-pct", "Short raw stop distance is within max stop", "Raw stop %", stop.rawStopPct(), "<=", "Max stop %", maxStopPct, true)
                        )
                );
                return new StrategyIntentResult(List.of(), List.of(intent), List.of());
            }
        }
        StrategyTradeIntent intent = DoflamingoSignalSupport.longExitIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                "Doflamingo Ichimoku v4 structure exit",
                List.of(
                        "spanAOverHigh=" + spanAOverHigh,
                        "closeBelowSpanB=" + closeBelowSpanB,
                        "structureWeakBars=" + structureWeakBars,
                        "emaBelowSpanA=" + emaBelowSpanA
                ),
                List.of("doflamingo", "v4", "strict", "ichimoku", "exit", "risk"),
                List.of(
                        DoflamingoSignalSupport.condition("ichimoku-v4.exit-span-a-over-high", "Present Span A over candle high", "Ichimoku Span A", ichimoku.presentSpanA(), ">", "Candle high", current.ohlcv().high().doubleValue(), spanAOverHigh),
                        DoflamingoSignalSupport.condition("ichimoku-v4.exit-close-below-span-b", "Close below present Span B", "Candle close", current.ohlcv().close().doubleValue(), "<", "Ichimoku Span B", ichimoku.presentSpanB(), closeBelowSpanB),
                        DoflamingoSignalSupport.condition("ichimoku-v4.exit-conversion-confirmed", "Conversion below base for configured bars", "Structure weak bars", structureWeakBars, ">=", "Required bars", structureExitConfirmBars, structureConfirmed),
                        DoflamingoSignalSupport.condition("ichimoku-v4.exit-ema-below-span-a", "EMA(9) below present Span A", "EMA(9)", ema9, "<", "Ichimoku Span A", ichimoku.presentSpanA(), emaBelowSpanA)
                )
        );
        return new StrategyIntentResult(List.of(), List.of(intent), List.of());
    }

    private StrategyIntentResult shortExitIfNeeded(
            StrategyExecutionContext context,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            double ema9,
            double trend,
            double trendAverage
    ) {
        BarEvent current = context.currentBar();
        StrategyInstrumentPosition position = context.instrumentPosition();
        double close = current.ohlcv().close().doubleValue();
        double low = current.ohlcv().low().doubleValue();
        BigDecimal currentR = position.currentRMultiple() == null ? BigDecimal.ZERO : position.currentRMultiple();
        double cloudCeiling = Math.max(ichimoku.presentSpanA(), ichimoku.presentSpanB());
        boolean shortThesisValid = close < Math.min(ichimoku.presentSpanA(), ichimoku.presentSpanB())
                && ichimoku.conversionLine() < ichimoku.baseLine()
                && ema9 < cloudCeiling;
        boolean canScaleOut = allowShortScaleOut
                && position.scaleOutCount() == 0
                && currentR.compareTo(shortScaleOutAtR) >= 0
                && position.maxFavorablePct().signum() > 0
                && shortThesisValid;
        if (canScaleOut) {
            BigDecimal confidence = shortConfidence(close, current.ohlcv().high().doubleValue(), low, ichimoku, ema9, trend, trendAverage, OptionalDouble.empty());
            StrategyTradeIntent intent = DoflamingoSignalSupport.shortScaleOutIntent(
                    strategyId(),
                    DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.CONTINUATION,
                    shortScaleOutFraction,
                    "Doflamingo Ichimoku v4 short scale-out at configured R multiple",
                    List.of("side=SHORT", "currentR=" + currentR, "shortScaleOutAtR=" + shortScaleOutAtR,
                            "scaleOutCount=" + position.scaleOutCount(), "scaleInCount=" + position.scaleInCount(),
                            "mfeR=" + position.maxFavorablePct(), "maeR=" + position.maxAdversePct(),
                            "positionBarsHeld=" + position.barsHeld(), "confidence=" + confidence),
                    List.of("doflamingo", "v4", "strict", "ichimoku", "short", "scale-out", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("ichimoku-v4.short-scale-out-enabled", "Short scale-out is enabled", "Allow short scale-out", allowShortScaleOut ? 1.0d : 0.0d, "=", "Required", 1.0d, allowShortScaleOut),
                            DoflamingoSignalSupport.condition("ichimoku-v4.short-scale-out-r-multiple", "Current R multiple reached scale-out threshold", "Current R", currentR, ">=", "Scale-out R", shortScaleOutAtR, true),
                            DoflamingoSignalSupport.condition("ichimoku-v4.short-scale-out-thesis-valid", "Short thesis remains valid", "Short thesis valid", shortThesisValid ? 1.0d : 0.0d, "=", "Required", 1.0d, shortThesisValid)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }

        boolean spanAUnderLow = ichimoku.presentSpanA() < low;
        boolean closeAboveCloud = close > cloudCeiling;
        boolean conversionAboveBase = ichimoku.conversionLine() > ichimoku.baseLine();
        boolean emaAboveSpanA = ema9 > ichimoku.presentSpanA();
        shortStructureWeakBars = conversionAboveBase ? shortStructureWeakBars + 1 : 0;
        boolean structureConfirmed = shortStructureWeakBars >= structureExitConfirmBars;
        boolean staleExit = position.barsHeld() >= shortStaleBars && currentR.compareTo(shortStaleMinR) <= 0;
        boolean postScaleRecovery = position.scaleOutCount() > 0
                && (closeAboveCloud || conversionAboveBase || emaAboveSpanA);
        boolean exit = spanAUnderLow || closeAboveCloud || structureConfirmed || emaAboveSpanA || staleExit || postScaleRecovery;
        if (!exit) {
            return StrategyIntentResult.empty();
        }

        BigDecimal confidence = shortConfidence(close, current.ohlcv().high().doubleValue(), low, ichimoku, ema9, trend, trendAverage, OptionalDouble.empty());
        if (allowReversal && closeAboveCloud && conversionAboveBase) {
            StrategyTradeIntent intent = DoflamingoSignalSupport.reverseShortToLongIntent(
                    strategyId(),
                    DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                    context,
                    confidence,
                    SetupType.CONTINUATION,
                    riskFraction,
                    stopPolicy(close, ichimoku, OptionalDouble.empty()),
                    maxHoldingBars,
                    "Doflamingo Ichimoku v4 short-to-long reversal",
                    List.of("side=LONG", "previousSide=SHORT", "allowReversal=" + allowReversal,
                            "closeAboveCloud=" + closeAboveCloud, "conversionAboveBase=" + conversionAboveBase,
                            "positionBarsHeld=" + position.barsHeld(), "currentR=" + currentR,
                            "mfeR=" + position.maxFavorablePct(), "maeR=" + position.maxAdversePct(),
                            "scaleOutCount=" + position.scaleOutCount(), "scaleInCount=" + position.scaleInCount(),
                            "confidence=" + confidence),
                    List.of("doflamingo", "v4", "strict", "ichimoku", "reversal", "long", "risk", "confidence"),
                    List.of(
                            DoflamingoSignalSupport.condition("ichimoku-v4.reverse-short-to-long-enabled", "Reversal is enabled", "Allow reversal", allowReversal ? 1.0d : 0.0d, "=", "Required", 1.0d, allowReversal),
                            DoflamingoSignalSupport.condition("ichimoku-v4.reverse-short-to-long-close-above-cloud", "Short close reclaimed present cloud", "Candle close", close, ">", "Cloud ceiling", cloudCeiling, closeAboveCloud),
                            DoflamingoSignalSupport.condition("ichimoku-v4.reverse-short-to-long-conversion", "Conversion line crossed above base", "Conversion line", ichimoku.conversionLine(), ">", "Base line", ichimoku.baseLine(), conversionAboveBase)
                    )
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }
        StrategyTradeIntent intent = DoflamingoSignalSupport.shortExitIntent(
                strategyId(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                context,
                confidence,
                SetupType.CONTINUATION,
                "Doflamingo Ichimoku v4 short structure exit",
                List.of(
                        "side=SHORT",
                        "spanAUnderLow=" + spanAUnderLow,
                        "closeAboveCloud=" + closeAboveCloud,
                        "shortStructureWeakBars=" + shortStructureWeakBars,
                        "emaAboveSpanA=" + emaAboveSpanA,
                        "staleExit=" + staleExit,
                        "postScaleRecovery=" + postScaleRecovery,
                        "positionBarsHeld=" + position.barsHeld(),
                        "currentR=" + currentR,
                        "mfeR=" + position.maxFavorablePct(),
                        "maeR=" + position.maxAdversePct(),
                        "scaleOutCount=" + position.scaleOutCount(),
                        "scaleInCount=" + position.scaleInCount()
                ),
                List.of("doflamingo", "v4", "strict", "ichimoku", "short", "exit", "risk"),
                List.of(
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-span-a-under-low", "Present Span A under candle low", "Ichimoku Span A", ichimoku.presentSpanA(), "<", "Candle low", low, spanAUnderLow),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-close-above-cloud", "Short close reclaimed present cloud", "Candle close", close, ">", "Cloud ceiling", cloudCeiling, closeAboveCloud),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-conversion-confirmed", "Conversion above base for configured bars", "Structure weak bars", shortStructureWeakBars, ">=", "Required bars", structureExitConfirmBars, structureConfirmed),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-ema-above-span-a", "EMA(9) above present Span A", "EMA(9)", ema9, ">", "Ichimoku Span A", ichimoku.presentSpanA(), emaAboveSpanA),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-stale-bars", "Stale short bars held", "Bars held", position.barsHeld(), ">=", "Stale bars", shortStaleBars, staleExit),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-stale-r", "Stale short R multiple below threshold", "Current R", currentR, "<=", "Stale minimum R", shortStaleMinR, staleExit),
                        DoflamingoSignalSupport.condition("ichimoku-v4.short-exit-post-scale-recovery", "Post-scale short recovery", "Post-scale recovery", postScaleRecovery ? 1.0d : 0.0d, "=", "Required", 1.0d, postScaleRecovery)
                )
        );
        return new StrategyIntentResult(List.of(), List.of(intent), List.of());
    }

    private TradeIntentExitPolicy stopPolicy(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        if (!enableProtectiveStop || stopMode == StopMode.NONE) {
            return TradeIntentExitPolicy.none();
        }
        BigDecimal stopPct = stopPercent(close, ichimoku, maybeAtr);
        return DoflamingoSignalSupport.percentStopWithRrTarget(stopPct, targetRMultiple, "Doflamingo Ichimoku v4 " + stopMode + " protective stop");
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

    private TradeIntentExitPolicy shortStopPolicy(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        if (!enableProtectiveStop || stopMode == StopMode.NONE) {
            return TradeIntentExitPolicy.none();
        }
        StopSelection stop = shortStopSelection(close, ichimoku, maybeAtr);
        return DoflamingoSignalSupport.percentStopWithRrTarget(stop.selectedStopPct(), targetRMultiple, "Doflamingo Ichimoku v4 short " + stopMode + " protective stop");
    }

    private BigDecimal shortStopPercent(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        return shortStopSelection(close, ichimoku, maybeAtr).selectedStopPct();
    }

    private StopSelection shortStopSelection(double close, DoflamingoIndicatorMath.IchimokuSnapshot ichimoku, OptionalDouble maybeAtr) {
        double cloudPct = shortCloudStopPercent(close, ichimoku, cloudStopBufferPct).doubleValue();
        double atrPct = maybeAtr.isPresent()
                ? Math.max(0.1d, (maybeAtr.getAsDouble() * atrStopMultiple.doubleValue() / close) * 100.0d)
                : cloudPct;
        double selected = switch (stopMode) {
            case CLOUD -> cloudPct;
            case ATR -> atrPct;
            case CLOUD_OR_ATR -> Math.max(cloudPct, atrPct);
            case NONE -> 0.0d;
        };
        BigDecimal raw = BigDecimal.valueOf(selected).setScale(4, RoundingMode.HALF_UP);
        return new StopSelection(raw, clampMinStop(raw));
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

    static BigDecimal shortCloudStopPercent(
            double close,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            BigDecimal cloudStopBufferPct
    ) {
        double cloudResistance = Math.max(Math.max(ichimoku.presentSpanA(), ichimoku.presentSpanB()), ichimoku.baseLine());
        double cloudStopPrice = cloudResistance * (1.0d + cloudStopBufferPct.doubleValue() / 100.0d);
        double cloudPct = Math.max(0.1d, ((cloudStopPrice - close) / close) * 100.0d);
        return BigDecimal.valueOf(cloudPct).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal clampStop(BigDecimal value) {
        BigDecimal minClamped = clampMinStop(value);
        if (minClamped.compareTo(maxStopPct) > 0) {
            return maxStopPct.setScale(4, RoundingMode.HALF_UP);
        }
        return minClamped;
    }

    private BigDecimal clampMinStop(BigDecimal value) {
        if (value.compareTo(minStopPct) < 0) {
            return minStopPct.setScale(4, RoundingMode.HALF_UP);
        }
        return value;
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

    private BigDecimal shortConfidence(
            double close,
            double high,
            double low,
            DoflamingoIndicatorMath.IchimokuSnapshot ichimoku,
            double ema9,
            double trend,
            double trendAverage,
            OptionalDouble maybeAtr
    ) {
        double score = 0.45d;
        double cloudFloor = Math.min(ichimoku.presentSpanA(), ichimoku.presentSpanB());
        if (ichimoku.futureSpanB() > ichimoku.futureSpanA()) {
            score += 0.08d;
        }
        if (close < cloudFloor) {
            score += 0.08d;
        }
        if (high < cloudFloor) {
            score += 0.06d;
        }
        if (ema9 < cloudFloor) {
            score += 0.06d;
        }
        if (trend < trendAverage) {
            score += Math.min(0.12d, (trendAverage - trend) / 1000.0d + 0.04d);
        }
        double cloudRange = Math.max(1.0d, Math.abs(ichimoku.presentSpanB() - ichimoku.presentSpanA()));
        double distanceFromResistance = Math.max(0.0d, cloudFloor - close);
        if (distanceFromResistance <= cloudRange * 2.0d) {
            score += 0.06d;
        }
        if (maybeAtr.isPresent()) {
            double candleRange = Math.max(0.01d, high - low);
            if (candleRange <= maybeAtr.getAsDouble() * 1.8d) {
                score += 0.08d;
            }
        } else {
            score += 0.04d;
        }
        if (ichimoku.conversionLine() < ichimoku.baseLine()) {
            score += 0.08d;
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

    private boolean htfCloudBiasOk(StrategyExecutionContext context, boolean bullish) {
        if (htfCloudBiasMode == HtfCloudBiasMode.OFF) {
            return true;
        }
        Optional<DoflamingoIndicatorMath.IchimokuSnapshot> h1 = DoflamingoIndicatorMath.ichimoku(context.history("H1"));
        if (h1.isEmpty()) {
            return false;
        }
        DoflamingoIndicatorMath.IchimokuSnapshot snapshot = h1.get();
        return bullish
                ? snapshot.futureSpanA() > snapshot.futureSpanB() && snapshot.conversionLine() > snapshot.baseLine()
                : snapshot.futureSpanB() > snapshot.futureSpanA() && snapshot.conversionLine() < snapshot.baseLine();
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

    private enum HtfCloudBiasMode {
        OFF,
        ALIGN_WITH_TRADE
    }

    private enum ShortCloudPriceMode {
        CLOSE_BELOW_CLOUD,
        HIGH_BELOW_CLOUD
    }

    private enum ShortEmaCloudMode {
        EMA9_BELOW_SPAN_B,
        EMA9_BELOW_CLOUD
    }

    private record StopSelection(BigDecimal rawStopPct, BigDecimal selectedStopPct) {
    }
}
