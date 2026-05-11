package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.TagSet;
import org.algotradex.platform.contracts.common.value.TimeHorizon;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * EMA20/50/200 trend-structure continuation sample strategy.
 */
public final class EmaTrendStructurePullbackStrategy implements TradeSignalStrategy {
    private static final double IDEAL_DISTANCE_FROM_FAST_EMA_MIN_PCT = 0.10d;
    private static final double TRANSITION_MAX_DISTANCE_FROM_FAST_EMA_PCT = 2.50d;

    private final EmaTrendStructurePullbackParameters params;
    private final SideRuntime longRuntime = new SideRuntime();
    private final SideRuntime shortRuntime = new SideRuntime();
    private int processedBars;

    EmaTrendStructurePullbackStrategy(EmaTrendStructurePullbackParameters params) {
        this.params = requireNonNull(params, "params");
    }

    @Override
    public String strategyId() {
        return EmaTrendStructurePullbackStrategyProvider.STRATEGY_ID;
    }

    @Override
    public Optional<TradeSignal> onBar(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        if (history.size() < processedBars) {
            resetRuntime();
        }
        if (history.size() <= processedBars) {
            return Optional.empty();
        }

        EmaSeries series = EmaSeries.from(history, params);
        Optional<SetupCandidate> latestCandidate = Optional.empty();
        int finalIndex = history.size() - 1;
        for (int index = processedBars; index <= finalIndex; index++) {
            Optional<SetupCandidate> candidate = processIndex(series, index);
            if (candidate.isPresent() && index == finalIndex) {
                latestCandidate = candidate;
            }
            processedBars = index + 1;
        }
        return latestCandidate.map(candidate -> signal(context, candidate));
    }

    static double[] emaSeries(List<BarEvent> history, int period) {
        double[] closes = new double[history.size()];
        for (int index = 0; index < history.size(); index++) {
            closes[index] = close(history.get(index));
        }
        return computeEmaSeries(closes, period);
    }

    private Optional<SetupCandidate> processIndex(EmaSeries series, int index) {
        longRuntime.onNewBar();
        shortRuntime.onNewBar();
        if (!series.readyAt(index, params)) {
            return Optional.empty();
        }

        EmaSnapshot snapshot = EmaSnapshot.from(series, index, params);
        resetInvalidatedSideState(snapshot);
        armPullbacks(series, snapshot);

        List<SetupCandidate> candidates = new ArrayList<>();
        primaryPullbackCandidate(Direction.LONG, series, snapshot).ifPresent(candidates::add);
        bullishTransitionCandidate(series, snapshot).ifPresent(candidates::add);
        if (params.allowShorts()) {
            primaryPullbackCandidate(Direction.SHORT, series, snapshot).ifPresent(candidates::add);
        }

        Optional<SetupCandidate> selected = candidates.stream()
                .filter(candidate -> candidate.confidence().compareTo(params.minConfidence()) >= 0)
                .max(Comparator.comparingInt(SetupCandidate::strengthScore)
                        .thenComparing(candidate -> candidate.kind().priority()));
        selected.ifPresent(candidate -> runtime(candidate.direction()).markEmitted(candidate.anchorIndex(), params.cooldownBars()));
        return selected;
    }

    private void resetInvalidatedSideState(EmaSnapshot snapshot) {
        if (snapshot.close() < snapshot.ema50()
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || snapshot.emaStack() == EmaStack.MIXED_STACK
                || snapshot.emaStack() == EmaStack.BEARISH_STACK
                || snapshot.emaStack() == EmaStack.BEARISH_TRANSITION) {
            longRuntime.reset();
        }
        if (snapshot.close() > snapshot.ema50()
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || snapshot.emaStack() == EmaStack.MIXED_STACK
                || snapshot.emaStack() == EmaStack.BULLISH_STACK
                || snapshot.emaStack() == EmaStack.BULLISH_TRANSITION) {
            shortRuntime.reset();
        }
    }

    private void armPullbacks(EmaSeries series, EmaSnapshot snapshot) {
        if (bullishEmaAlignment(snapshot)
                && bullishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && isPullbackBar(Direction.LONG, series, snapshot.index())) {
            longRuntime.arm(snapshot.index());
        }
        if (params.allowShorts()
                && bearishEmaAlignment(snapshot)
                && bearishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && isPullbackBar(Direction.SHORT, series, snapshot.index())) {
            shortRuntime.arm(snapshot.index());
        }
    }

    private Optional<SetupCandidate> primaryPullbackCandidate(Direction direction, EmaSeries series, EmaSnapshot snapshot) {
        SideRuntime runtime = runtime(direction);
        if (runtime.state() != SignalState.PULLBACK_ARMED || !runtime.canEmit(runtime.armedAnchorIndex())) {
            return Optional.empty();
        }
        int anchorIndex = runtime.armedAnchorIndex();
        if (anchorIndex < snapshot.index() - params.pullbackLookbackBars()
                || anchorIndex > snapshot.index() - params.pullbackMinBars()) {
            return Optional.empty();
        }
        if (direction == Direction.LONG && !currentLongContinuationConfirm(snapshot)) {
            return Optional.empty();
        }
        if (direction == Direction.SHORT && !currentShortContinuationConfirm(snapshot)) {
            return Optional.empty();
        }

        PullbackQuality pullback = pullbackQuality(direction, series, anchorIndex, snapshot.index());
        if (!pullback.realPullback() || !pullback.heldMediumEma()) {
            return Optional.empty();
        }
        if (hardReject(direction, snapshot, params.maxDistanceFromFastEmaPct().doubleValue())) {
            return Optional.empty();
        }

        SetupKind kind = direction == Direction.LONG
                ? SetupKind.BULLISH_PULLBACK_CONTINUATION
                : SetupKind.BEARISH_PULLBACK_CONTINUATION;
        int score = scorePrimary(direction, snapshot, pullback);
        return Optional.of(new SetupCandidate(direction, kind, SetupType.PULLBACK, anchorIndex, score, confidence(score), snapshot));
    }

    private Optional<SetupCandidate> bullishTransitionCandidate(EmaSeries series, EmaSnapshot snapshot) {
        int anchorIndex = snapshot.index() - 1;
        if (anchorIndex < 0 || !longRuntime.canEmit(anchorIndex)) {
            return Optional.empty();
        }
        if (snapshot.emaStack() != EmaStack.BULLISH_TRANSITION
                || snapshot.ema20SlopePct() <= 0.0d
                || snapshot.ema50SlopePct() <= 0.0d
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.previousEmaSeparationPct() > params.compressedSeparationThresholdPct().doubleValue()
                || snapshot.emaSeparationPct() <= snapshot.previousEmaSeparationPct()
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || sideDistancePct(Direction.LONG, snapshot.close(), snapshot.ema20()) > TRANSITION_MAX_DISTANCE_FROM_FAST_EMA_PCT
                || !breaksPriorHigh(series, snapshot.index(), params.transitionBreakoutLookbackBars())) {
            return Optional.empty();
        }

        int score = scoreTransition(snapshot);
        return Optional.of(new SetupCandidate(Direction.LONG, SetupKind.BULLISH_TRANSITION_BREAKOUT,
                SetupType.CONTINUATION, anchorIndex, score, confidence(score), snapshot));
    }

    private boolean currentLongContinuationConfirm(EmaSnapshot snapshot) {
        return snapshot.emaStack() == EmaStack.BULLISH_STACK
                && bullishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && snapshot.close() > snapshot.ema20()
                && snapshot.close() > snapshot.previousClose()
                && snapshot.closeLocation() >= 0.65d;
    }

    private boolean currentShortContinuationConfirm(EmaSnapshot snapshot) {
        return snapshot.emaStack() == EmaStack.BEARISH_STACK
                && bearishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && snapshot.close() < snapshot.ema20()
                && snapshot.close() < snapshot.previousClose()
                && snapshot.closeLocation() <= 0.35d;
    }

    private boolean hardReject(Direction direction, EmaSnapshot snapshot, double maxFastDistancePct) {
        if (snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.trendStructure() == TrendStructure.MA_COMPRESSION
                || snapshot.trendStructure() == TrendStructure.CHOPPY
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()) {
            return true;
        }
        if (direction == Direction.LONG && (!bullishSlope(snapshot) || snapshot.emaStack() == EmaStack.MIXED_STACK)) {
            return true;
        }
        if (direction == Direction.SHORT && (!bearishSlope(snapshot) || snapshot.emaStack() == EmaStack.MIXED_STACK)) {
            return true;
        }
        return sideDistancePct(direction, snapshot.close(), snapshot.ema20()) > maxFastDistancePct;
    }

    private boolean cleanEnoughForSetup(EmaSnapshot snapshot) {
        return snapshot.compressionState() != CompressionState.COMPRESSED
                && snapshot.recentPriceEmaCrossCount() < params.chopCrossCountThreshold();
    }

    private boolean bullishEmaAlignment(EmaSnapshot snapshot) {
        return snapshot.ema20() > snapshot.ema50() && snapshot.ema50() > snapshot.ema200();
    }

    private boolean bearishEmaAlignment(EmaSnapshot snapshot) {
        return snapshot.ema20() < snapshot.ema50() && snapshot.ema50() < snapshot.ema200();
    }

    private boolean bullishSlope(EmaSnapshot snapshot) {
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        return snapshot.ema20SlopePct() > threshold
                && snapshot.ema50SlopePct() > threshold
                && snapshot.ema200SlopePct() >= -threshold;
    }

    private boolean bearishSlope(EmaSnapshot snapshot) {
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        return snapshot.ema20SlopePct() < -threshold
                && snapshot.ema50SlopePct() < -threshold
                && snapshot.ema200SlopePct() <= threshold;
    }

    private boolean isPullbackBar(Direction direction, EmaSeries series, int index) {
        if (Double.isNaN(series.ema20()[index]) || Double.isNaN(series.ema50()[index])) {
            return false;
        }
        double tolerance = pct(params.emaTouchTolerancePct());
        double close = series.close()[index];
        if (direction == Direction.LONG) {
            boolean closedBelowFastHeldMedium = close < series.ema20()[index] && close >= series.ema50()[index];
            boolean approachedFastHeldMedium = series.low()[index] <= series.ema20()[index] * (1.0d + tolerance)
                    && close >= series.ema50()[index]
                    && sideDistancePct(Direction.LONG, close, series.ema20()[index]) <= params.maxDistanceFromFastEmaPct().doubleValue();
            return closedBelowFastHeldMedium || approachedFastHeldMedium;
        }
        boolean closedAboveFastHeldMedium = close > series.ema20()[index] && close <= series.ema50()[index];
        boolean approachedFastHeldMedium = series.high()[index] >= series.ema20()[index] * (1.0d - tolerance)
                && close <= series.ema50()[index]
                && sideDistancePct(Direction.SHORT, close, series.ema20()[index]) <= params.maxDistanceFromFastEmaPct().doubleValue();
        return closedAboveFastHeldMedium || approachedFastHeldMedium;
    }

    private PullbackQuality pullbackQuality(Direction direction, EmaSeries series, int anchorIndex, int currentIndex) {
        boolean realPullback = false;
        boolean heldMedium = true;
        boolean controlledDepth = true;
        double maxAdverseFastDistance = 0.0d;

        for (int index = anchorIndex; index < currentIndex; index++) {
            if (Double.isNaN(series.ema20()[index]) || Double.isNaN(series.ema50()[index])) {
                continue;
            }
            double close = series.close()[index];
            if (direction == Direction.LONG) {
                boolean belowFast = close < series.ema20()[index] && close >= series.ema50()[index];
                boolean approachedFast = series.low()[index] <= series.ema20()[index] * (1.0d + pct(params.emaTouchTolerancePct()))
                        && close >= series.ema50()[index];
                realPullback = realPullback || belowFast || approachedFast;
                heldMedium = heldMedium && close >= series.ema50()[index];
                maxAdverseFastDistance = Math.max(maxAdverseFastDistance, Math.max(0.0d, percentageChange(series.ema20()[index], Math.min(close, series.low()[index]))));
            } else {
                boolean aboveFast = close > series.ema20()[index] && close <= series.ema50()[index];
                boolean approachedFast = series.high()[index] >= series.ema20()[index] * (1.0d - pct(params.emaTouchTolerancePct()))
                        && close <= series.ema50()[index];
                realPullback = realPullback || aboveFast || approachedFast;
                heldMedium = heldMedium && close <= series.ema50()[index];
                maxAdverseFastDistance = Math.max(maxAdverseFastDistance, Math.max(0.0d, percentageChange(Math.max(close, series.high()[index]), series.ema20()[index])));
            }
        }
        controlledDepth = maxAdverseFastDistance <= params.maxDistanceFromFastEmaPct().doubleValue();
        return new PullbackQuality(realPullback, heldMedium, controlledDepth);
    }

    private int scorePrimary(Direction direction, EmaSnapshot snapshot, PullbackQuality pullback) {
        int score = 0;
        score += stackScore(direction, snapshot);
        score += slopeScore(direction, snapshot);
        if (pullback.realPullback()) score += 8;
        if (pullback.heldMediumEma()) score += 6;
        if ((direction == Direction.LONG && snapshot.close() > snapshot.ema20())
                || (direction == Direction.SHORT && snapshot.close() < snapshot.ema20())) {
            score += 4;
        }
        if (pullback.controlledDepth()) score += 2;
        score += momentumScore(direction, snapshot);
        score += structureCleanlinessScore(snapshot);
        score += riskLocationScore(direction, snapshot);
        return Math.min(100, score);
    }

    private int scoreTransition(EmaSnapshot snapshot) {
        int score = 0;
        score += stackScore(Direction.LONG, snapshot);
        score += slopeScore(Direction.LONG, snapshot);
        if (snapshot.previousEmaSeparationPct() <= params.compressedSeparationThresholdPct().doubleValue()) score += 8;
        if (snapshot.emaSeparationPct() > snapshot.previousEmaSeparationPct()) score += 6;
        score += 4;
        if (snapshot.ema20SlopePct() > 0.0d && snapshot.ema50SlopePct() > 0.0d) score += 2;
        score += momentumScore(Direction.LONG, snapshot);
        score += structureCleanlinessScore(snapshot);
        score += riskLocationScore(Direction.LONG, snapshot);
        return Math.min(100, score);
    }

    private int stackScore(Direction direction, EmaSnapshot snapshot) {
        if (direction == Direction.LONG) {
            if (snapshot.close() > snapshot.ema20() && snapshot.ema20() > snapshot.ema50() && snapshot.ema50() > snapshot.ema200()) {
                return 20;
            }
            if (snapshot.close() > snapshot.ema20() && snapshot.ema20() > snapshot.ema50() && snapshot.ema50() <= snapshot.ema200()) {
                return 12;
            }
            return 0;
        }
        if (snapshot.close() < snapshot.ema20() && snapshot.ema20() < snapshot.ema50() && snapshot.ema50() < snapshot.ema200()) {
            return 20;
        }
        return 0;
    }

    private int slopeScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        if (direction == Direction.LONG) {
            if (snapshot.ema20SlopePct() > threshold) score += 6;
            if (snapshot.ema50SlopePct() > threshold) score += 6;
            if (snapshot.ema200SlopePct() >= -threshold) score += 3;
        } else {
            if (snapshot.ema20SlopePct() < -threshold) score += 6;
            if (snapshot.ema50SlopePct() < -threshold) score += 6;
            if (snapshot.ema200SlopePct() <= threshold) score += 3;
        }
        return score;
    }

    private int momentumScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        if (direction == Direction.LONG) {
            if (snapshot.close() > snapshot.previousClose()) score += 5;
            if (snapshot.closeLocation() >= 0.65d) score += 5;
            if (snapshot.breaksPriorHigh() || (snapshot.threeBarReturnPct() > 0.0d
                    && sideDistancePct(direction, snapshot.close(), snapshot.ema20()) <= params.maxDistanceFromFastEmaPct().doubleValue())) {
                score += 5;
            }
        } else {
            if (snapshot.close() < snapshot.previousClose()) score += 5;
            if (snapshot.closeLocation() <= 0.35d) score += 5;
            if (snapshot.breaksPriorLow() || (snapshot.threeBarReturnPct() < 0.0d
                    && sideDistancePct(direction, snapshot.close(), snapshot.ema20()) <= params.maxDistanceFromFastEmaPct().doubleValue())) {
                score += 5;
            }
        }
        return score;
    }

    private int structureCleanlinessScore(EmaSnapshot snapshot) {
        int score = 0;
        if (snapshot.recentPriceEmaCrossCount() < 3) score += 7;
        if (snapshot.compressionState() != CompressionState.COMPRESSED) score += 5;
        if (snapshot.trendStructure() != TrendStructure.MIXED
                && snapshot.trendStructure() != TrendStructure.CHOPPY
                && snapshot.trendStructure() != TrendStructure.MA_COMPRESSION) {
            score += 3;
        }
        return score;
    }

    private int riskLocationScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        double fastDistance = sideDistancePct(direction, snapshot.close(), snapshot.ema20());
        if (fastDistance >= IDEAL_DISTANCE_FROM_FAST_EMA_MIN_PCT
                && fastDistance <= params.idealDistanceFromFastEmaPct().doubleValue()) {
            score += 10;
        }
        if (sideDistancePct(direction, snapshot.close(), snapshot.ema50()) <= params.maxDistanceFromMediumEmaPct().doubleValue()) {
            score += 5;
        }
        return score;
    }

    private BigDecimal confidence(int strengthScore) {
        double clamped = Math.max(0.50d, Math.min(0.95d, strengthScore / 100.0d));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    private TradeSignal signal(StrategyExecutionContext context, SetupCandidate candidate) {
        BarEvent bar = context.currentBar();
        EmaSnapshot snapshot = candidate.snapshot();
        return new TradeSignal(
                EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value() + '-' + candidate.kind().tagValue()),
                bar.instrument(),
                candidate.direction(),
                new ConfidenceScore(candidate.confidence()),
                candidate.setupType(),
                new TimeHorizon("short_continuation", Duration.ofHours(4)),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                null,
                new TagSet(List.of(
                        "strategy_family=ema_trend_structure_pullback",
                        "strategy_id=" + strategyId(),
                        "setup=" + candidate.kind().tagValue(),
                        "strength_score=" + candidate.strengthScore(),
                        "ema_stack=" + snapshot.emaStack().name().toLowerCase(Locale.ROOT),
                        "trend_structure=" + snapshot.trendStructure().name().toLowerCase(Locale.ROOT),
                        "recent_cross_count=" + snapshot.recentPriceEmaCrossCount(),
                        "distance_from_ema20_pct=" + formatPct(sideDistancePct(candidate.direction(), snapshot.close(), snapshot.ema20())),
                        "formula_version=ema-trend-structure-pullback-v1"
                )),
                bar.cohort(),
                bar.baseline()
        );
    }

    private SideRuntime runtime(Direction direction) {
        return direction == Direction.LONG ? longRuntime : shortRuntime;
    }

    private void resetRuntime() {
        processedBars = 0;
        longRuntime.resetAll();
        shortRuntime.resetAll();
    }

    private static boolean breaksPriorHigh(EmaSeries series, int index, int lookbackBars) {
        return series.close()[index] > priorHigh(series, index, lookbackBars);
    }

    private static boolean breaksPriorLow(EmaSeries series, int index, int lookbackBars) {
        return series.close()[index] < priorLow(series, index, lookbackBars);
    }

    private static double priorHigh(EmaSeries series, int index, int lookbackBars) {
        double high = Double.NEGATIVE_INFINITY;
        int start = Math.max(0, index - lookbackBars);
        for (int candidate = start; candidate < index; candidate++) {
            high = Math.max(high, series.high()[candidate]);
        }
        return high;
    }

    private static double priorLow(EmaSeries series, int index, int lookbackBars) {
        double low = Double.POSITIVE_INFINITY;
        int start = Math.max(0, index - lookbackBars);
        for (int candidate = start; candidate < index; candidate++) {
            low = Math.min(low, series.low()[candidate]);
        }
        return low;
    }

    private static int countRecentPriceEmaCrosses(EmaSeries series, int currentIndex, int lookbackBars) {
        int start = Math.max(1, currentIndex - lookbackBars + 1);
        int crosses = 0;
        for (int index = start; index <= currentIndex; index++) {
            if (crossed(series.close()[index - 1], series.close()[index], series.ema20()[index - 1], series.ema20()[index])) crosses++;
            if (crossed(series.close()[index - 1], series.close()[index], series.ema50()[index - 1], series.ema50()[index])) crosses++;
        }
        return crosses;
    }

    private static boolean crossed(double previousClose, double currentClose, double previousEma, double currentEma) {
        if (Double.isNaN(previousEma) || Double.isNaN(currentEma)) {
            return false;
        }
        return (previousClose - previousEma) * (currentClose - currentEma) < 0.0d;
    }

    private static double[] computeEmaSeries(double[] values, int period) {
        double[] series = new double[values.length];
        java.util.Arrays.fill(series, Double.NaN);
        if (period <= 0) {
            return series;
        }
        double seedTotal = 0.0d;
        double current = Double.NaN;
        double alpha = 2.0d / (period + 1.0d);
        for (int index = 0; index < values.length; index++) {
            double value = values[index];
            if (index < period - 1) {
                seedTotal += value;
                continue;
            }
            if (index == period - 1) {
                seedTotal += value;
                current = seedTotal / period;
            } else {
                current = (value * alpha) + (current * (1.0d - alpha));
            }
            series[index] = current;
        }
        return series;
    }

    private static double sideDistancePct(Direction direction, double close, double ema) {
        return direction == Direction.LONG ? percentageChange(close, ema) : percentageChange(ema, close);
    }

    private static double percentageChange(double value, double base) {
        if (base == 0.0d) {
            return 0.0d;
        }
        return ((value - base) / base) * 100.0d;
    }

    private static double percentageOfClose(double value, double close) {
        if (close == 0.0d) {
            return 0.0d;
        }
        return (value / Math.abs(close)) * 100.0d;
    }

    private static double pct(BigDecimal value) {
        return value.doubleValue() / 100.0d;
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double close(BarEvent bar) {
        return bar.ohlcv().close().doubleValue();
    }

    private enum SignalState {
        IDLE,
        PULLBACK_ARMED,
        SIGNAL_EMITTED
    }

    private enum EmaStack {
        BULLISH_STACK,
        BEARISH_STACK,
        BULLISH_TRANSITION,
        BEARISH_TRANSITION,
        MIXED_STACK
    }

    private enum SlopeState {
        RISING,
        FALLING,
        FLAT,
        MIXED
    }

    private enum CompressionState {
        COMPRESSED,
        EXPANDING,
        NORMAL
    }

    private enum TrendStructure {
        CLEAN_UPTREND,
        PULLBACK_IN_UPTREND,
        EARLY_UPTREND,
        MA_COMPRESSION,
        CHOPPY,
        MIXED,
        EARLY_DOWNTREND,
        PULLBACK_IN_DOWNTREND,
        CLEAN_DOWNTREND
    }

    private enum SetupKind {
        BULLISH_PULLBACK_CONTINUATION("bullish_pullback_continuation", 3),
        BEARISH_PULLBACK_CONTINUATION("bearish_pullback_continuation", 3),
        BULLISH_TRANSITION_BREAKOUT("bullish_transition_breakout", 1);

        private final String tagValue;
        private final int priority;

        SetupKind(String tagValue, int priority) {
            this.tagValue = tagValue;
            this.priority = priority;
        }

        String tagValue() {
            return tagValue;
        }

        int priority() {
            return priority;
        }
    }

    private static final class SideRuntime {
        private SignalState state = SignalState.IDLE;
        private int armedAnchorIndex = -1;
        private int lastSignalAnchorIndex = -1;
        private int cooldownRemaining;

        void onNewBar() {
            if (cooldownRemaining > 0) {
                cooldownRemaining--;
            }
        }

        SignalState state() {
            return state;
        }

        int armedAnchorIndex() {
            return armedAnchorIndex;
        }

        void arm(int anchorIndex) {
            if (cooldownRemaining > 0) {
                return;
            }
            if (state == SignalState.PULLBACK_ARMED) {
                return;
            }
            if (state == SignalState.SIGNAL_EMITTED && anchorIndex == lastSignalAnchorIndex) {
                return;
            }
            state = SignalState.PULLBACK_ARMED;
            armedAnchorIndex = anchorIndex;
        }

        boolean canEmit(int anchorIndex) {
            return anchorIndex >= 0 && cooldownRemaining <= 0 && anchorIndex != lastSignalAnchorIndex;
        }

        void markEmitted(int anchorIndex, int cooldownBars) {
            state = SignalState.SIGNAL_EMITTED;
            armedAnchorIndex = -1;
            lastSignalAnchorIndex = anchorIndex;
            cooldownRemaining = cooldownBars;
        }

        void reset() {
            if (state != SignalState.IDLE) {
                state = SignalState.IDLE;
                armedAnchorIndex = -1;
                cooldownRemaining = 0;
            }
        }

        void resetAll() {
            state = SignalState.IDLE;
            armedAnchorIndex = -1;
            lastSignalAnchorIndex = -1;
            cooldownRemaining = 0;
        }
    }

    private record EmaSeries(
            double[] open,
            double[] high,
            double[] low,
            double[] close,
            double[] ema20,
            double[] ema50,
            double[] ema200
    ) {
        private static EmaSeries from(List<BarEvent> history, EmaTrendStructurePullbackParameters params) {
            double[] open = new double[history.size()];
            double[] high = new double[history.size()];
            double[] low = new double[history.size()];
            double[] close = new double[history.size()];
            for (int index = 0; index < history.size(); index++) {
                BarEvent bar = history.get(index);
                open[index] = bar.ohlcv().open().doubleValue();
                high[index] = bar.ohlcv().high().doubleValue();
                low[index] = bar.ohlcv().low().doubleValue();
                close[index] = bar.ohlcv().close().doubleValue();
            }
            return new EmaSeries(
                    open,
                    high,
                    low,
                    close,
                    computeEmaSeries(close, params.fastEmaPeriod()),
                    computeEmaSeries(close, params.mediumEmaPeriod()),
                    computeEmaSeries(close, params.slowEmaPeriod())
            );
        }

        private boolean readyAt(int index, EmaTrendStructurePullbackParameters params) {
            int slopeBaseIndex = index - params.slopeLookbackBars();
            return index + 1 >= params.slowEmaPeriod() + params.slopeLookbackBars()
                    && slopeBaseIndex >= 0
                    && !Double.isNaN(ema20[index])
                    && !Double.isNaN(ema50[index])
                    && !Double.isNaN(ema200[index])
                    && !Double.isNaN(ema20[slopeBaseIndex])
                    && !Double.isNaN(ema50[slopeBaseIndex])
                    && !Double.isNaN(ema200[slopeBaseIndex]);
        }
    }

    private record EmaSnapshot(
            int index,
            double close,
            double previousClose,
            double ema20,
            double ema50,
            double ema200,
            double ema20SlopePct,
            double ema50SlopePct,
            double ema200SlopePct,
            double emaSeparationPct,
            double previousEmaSeparationPct,
            int recentPriceEmaCrossCount,
            double closeLocation,
            double threeBarReturnPct,
            boolean breaksPriorHigh,
            boolean breaksPriorLow,
            EmaStack emaStack,
            SlopeState slopeState,
            CompressionState compressionState,
            TrendStructure trendStructure
    ) {
        private static EmaSnapshot from(EmaSeries series, int index, EmaTrendStructurePullbackParameters params) {
            int slopeBaseIndex = index - params.slopeLookbackBars();
            double close = series.close[index];
            double previousClose = series.close[index - 1];
            double ema20 = series.ema20[index];
            double ema50 = series.ema50[index];
            double ema200 = series.ema200[index];
            double ema20SlopePct = percentageChange(ema20, series.ema20[slopeBaseIndex]);
            double ema50SlopePct = percentageChange(ema50, series.ema50[slopeBaseIndex]);
            double ema200SlopePct = percentageChange(ema200, series.ema200[slopeBaseIndex]);
            double emaSeparationPct = emaSeparationPct(close, ema20, ema50, ema200);
            double previousEmaSeparationPct = emaSeparationPct(series.close[index - 1], series.ema20[index - 1], series.ema50[index - 1], series.ema200[index - 1]);
            int recentCrossCount = countRecentPriceEmaCrosses(series, index, params.chopCrossLookbackBars());
            double closeLocation = closeLocation(series, index);
            double threeBarReturnPct = index >= 3 ? percentageChange(close, series.close[index - 3]) : 0.0d;
            boolean breaksPriorHigh = EmaTrendStructurePullbackStrategy.breaksPriorHigh(series, index, params.priorBreakoutLookbackBars());
            boolean breaksPriorLow = EmaTrendStructurePullbackStrategy.breaksPriorLow(series, index, params.priorBreakoutLookbackBars());
            EmaStack emaStack = classifyEmaStack(close, ema20, ema50, ema200);
            SlopeState slopeState = classifySlopeState(ema20SlopePct, ema50SlopePct, params.flatSlopeThresholdPct().doubleValue());
            CompressionState compressionState = classifyCompressionState(emaSeparationPct, params);
            TrendStructure trendStructure = classifyTrendStructure(close, ema20, ema50, ema200, ema20SlopePct,
                    emaStack, slopeState, compressionState, recentCrossCount, params.chopCrossCountThreshold());
            return new EmaSnapshot(index, close, previousClose, ema20, ema50, ema200, ema20SlopePct, ema50SlopePct,
                    ema200SlopePct, emaSeparationPct, previousEmaSeparationPct, recentCrossCount, closeLocation,
                    threeBarReturnPct, breaksPriorHigh, breaksPriorLow, emaStack, slopeState, compressionState, trendStructure);
        }

        private static double emaSeparationPct(double close, double ema20, double ema50, double ema200) {
            if (Double.isNaN(ema20) || Double.isNaN(ema50) || Double.isNaN(ema200)) {
                return Double.NaN;
            }
            return percentageOfClose(Math.max(ema20, Math.max(ema50, ema200)) - Math.min(ema20, Math.min(ema50, ema200)), close);
        }

        private static double closeLocation(EmaSeries series, int index) {
            double range = series.high[index] - series.low[index];
            if (range <= 0.0d) {
                return 0.50d;
            }
            return (series.close[index] - series.low[index]) / range;
        }

        private static EmaStack classifyEmaStack(double close, double ema20, double ema50, double ema200) {
            if (close >= ema20 && ema20 > ema50 && ema50 > ema200) return EmaStack.BULLISH_STACK;
            if (close <= ema20 && ema20 < ema50 && ema50 < ema200) return EmaStack.BEARISH_STACK;
            if (close > ema20 && ema20 > ema50 && ema50 <= ema200) return EmaStack.BULLISH_TRANSITION;
            if (close < ema20 && ema20 < ema50 && ema50 >= ema200) return EmaStack.BEARISH_TRANSITION;
            return EmaStack.MIXED_STACK;
        }

        private static SlopeState classifySlopeState(double ema20SlopePct, double ema50SlopePct, double threshold) {
            if (ema20SlopePct > threshold && ema50SlopePct > threshold) return SlopeState.RISING;
            if (ema20SlopePct < -threshold && ema50SlopePct < -threshold) return SlopeState.FALLING;
            if (Math.abs(ema20SlopePct) <= threshold && Math.abs(ema50SlopePct) <= threshold) return SlopeState.FLAT;
            return SlopeState.MIXED;
        }

        private static CompressionState classifyCompressionState(double emaSeparationPct, EmaTrendStructurePullbackParameters params) {
            if (emaSeparationPct <= params.compressedSeparationThresholdPct().doubleValue()) return CompressionState.COMPRESSED;
            if (emaSeparationPct >= params.expandingSeparationThresholdPct().doubleValue()) return CompressionState.EXPANDING;
            return CompressionState.NORMAL;
        }

        private static TrendStructure classifyTrendStructure(
                double close,
                double ema20,
                double ema50,
                double ema200,
                double ema20SlopePct,
                EmaStack emaStack,
                SlopeState slopeState,
                CompressionState compressionState,
                int recentCrossCount,
                int chopThreshold
        ) {
            if (emaStack == EmaStack.BULLISH_STACK && slopeState == SlopeState.RISING && compressionState != CompressionState.COMPRESSED) return TrendStructure.CLEAN_UPTREND;
            if (emaStack == EmaStack.BEARISH_STACK && slopeState == SlopeState.FALLING && compressionState != CompressionState.COMPRESSED) return TrendStructure.CLEAN_DOWNTREND;
            if (compressionState == CompressionState.COMPRESSED) return TrendStructure.MA_COMPRESSION;
            if (emaStack == EmaStack.BULLISH_TRANSITION && ema20SlopePct > 0.0d) return TrendStructure.EARLY_UPTREND;
            if (emaStack == EmaStack.BEARISH_TRANSITION && ema20SlopePct < 0.0d) return TrendStructure.EARLY_DOWNTREND;
            if (ema20 > ema50 && ema50 > ema200 && close < ema20 && close >= ema50) return TrendStructure.PULLBACK_IN_UPTREND;
            if (ema20 < ema50 && ema50 < ema200 && close > ema20 && close <= ema50) return TrendStructure.PULLBACK_IN_DOWNTREND;
            if (recentCrossCount >= chopThreshold) return TrendStructure.CHOPPY;
            return TrendStructure.MIXED;
        }
    }

    private record PullbackQuality(boolean realPullback, boolean heldMediumEma, boolean controlledDepth) {
    }

    private record SetupCandidate(
            Direction direction,
            SetupKind kind,
            SetupType setupType,
            int anchorIndex,
            int strengthScore,
            BigDecimal confidence,
            EmaSnapshot snapshot
    ) {
    }
}
