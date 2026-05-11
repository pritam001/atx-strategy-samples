package org.algotradex.strategy.samples.smapullback;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

/**
 * Closed-bar SMA20 pullback continuation sample strategy.
 */
public final class Sma20PullbackContinuationStrategy implements TradeSignalStrategy {
    private static final double CONFIDENCE_FLOOR = 0.45d;
    private static final double CONFIDENCE_CEILING = 0.85d;

    private final Sma20PullbackParameters params;
    private SignalState state = SignalState.NO_TREND;
    private int cooldownRemaining;
    private int lastLongSetupTouchIndex = -1;
    private int lastShortSetupTouchIndex = -1;

    Sma20PullbackContinuationStrategy(Sma20PullbackParameters params) {
        this.params = requireNonNull(params, "params");
        if (params.fastSmaPeriod() < 1) {
            throw new IllegalArgumentException("fastSmaPeriod must be >= 1");
        }
        if (params.slowSmaPeriod() <= params.fastSmaPeriod()) {
            throw new IllegalArgumentException("slowSmaPeriod must be greater than fastSmaPeriod");
        }
        if (params.cooldownBars() < 0) {
            throw new IllegalArgumentException("cooldownBars must be >= 0");
        }
    }

    @Override
    public String strategyId() {
        return Sma20PullbackContinuationStrategyProvider.STRATEGY_ID;
    }

    @Override
    public Optional<TradeSignal> onBar(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.size() < params.slowSmaPeriod()) {
            state = SignalState.WARMUP;
            return Optional.empty();
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            state = SignalState.COOLDOWN;
            return Optional.empty();
        }

        Optional<SmaSnapshot> snapshot = SmaSnapshot.from(history, params);
        if (snapshot.isEmpty()) {
            state = SignalState.WARMUP;
            return Optional.empty();
        }

        TrendState trend = classifyTrend(snapshot.get());
        BarEvent current = context.currentBar();
        resetResolvedSetups(current, snapshot.get(), trend);
        if (trend == TrendState.FLAT) {
            state = SignalState.NO_TREND;
            return Optional.empty();
        }

        if (trend == TrendState.UP) {
            Optional<SetupCandidate> setup = longSetup(history, snapshot.get());
            if (setup.isPresent()) {
                state = SignalState.LONG_ARMED;
                BigDecimal confidence = score(Direction.LONG, snapshot.get(), setup.get());
                if (setup.get().touchIndex() != lastLongSetupTouchIndex && confidence.compareTo(params.minConfidence()) >= 0) {
                    lastLongSetupTouchIndex = setup.get().touchIndex();
                    cooldownRemaining = params.cooldownBars();
                    state = SignalState.COOLDOWN;
                    return Optional.of(signal(context, Direction.LONG, confidence));
                }
            } else {
                state = SignalState.TREND_UP;
            }
            return Optional.empty();
        }

        if (params.allowShorts() && trend == TrendState.DOWN) {
            Optional<SetupCandidate> setup = shortSetup(history, snapshot.get());
            if (setup.isPresent()) {
                state = SignalState.SHORT_ARMED;
                BigDecimal confidence = score(Direction.SHORT, snapshot.get(), setup.get());
                if (setup.get().touchIndex() != lastShortSetupTouchIndex && confidence.compareTo(params.minConfidence()) >= 0) {
                    lastShortSetupTouchIndex = setup.get().touchIndex();
                    cooldownRemaining = params.cooldownBars();
                    state = SignalState.COOLDOWN;
                    return Optional.of(signal(context, Direction.SHORT, confidence));
                }
            } else {
                state = SignalState.TREND_DOWN;
            }
        }
        return Optional.empty();
    }

    private TrendState classifyTrend(SmaSnapshot snapshot) {
        double minimumSlope = params.minSma20SlopePct().doubleValue();
        if (snapshot.fastSlopePct() >= minimumSlope) {
            return TrendState.UP;
        }
        if (snapshot.fastSlopePct() <= -minimumSlope) {
            return TrendState.DOWN;
        }
        return TrendState.FLAT;
    }

    private Optional<SetupCandidate> longSetup(List<BarEvent> history, SmaSnapshot snapshot) {
        BarEvent current = last(history);
        double close = close(current);
        if (close <= snapshot.fastSma()) {
            return Optional.empty();
        }
        double extensionPct = distancePct(close, snapshot.fastSma());
        if (extensionPct > params.maxEntryExtensionPct().doubleValue()) {
            return Optional.empty();
        }

        PullbackWindow window = pullbackWindow(history, Direction.LONG);
        if (window.touchIndex() < 0 || close <= window.triggerLevel()) {
            return Optional.empty();
        }
        return Optional.of(new SetupCandidate(window.touchIndex(), extensionPct, window.cleanTrigger(), sma200Obstacle(Direction.LONG, close, snapshot), sma200Supportive(Direction.LONG, close, snapshot)));
    }

    private Optional<SetupCandidate> shortSetup(List<BarEvent> history, SmaSnapshot snapshot) {
        BarEvent current = last(history);
        double close = close(current);
        if (close >= snapshot.fastSma()) {
            return Optional.empty();
        }
        double extensionPct = distancePct(close, snapshot.fastSma());
        if (extensionPct > params.maxEntryExtensionPct().doubleValue()) {
            return Optional.empty();
        }

        PullbackWindow window = pullbackWindow(history, Direction.SHORT);
        if (window.touchIndex() < 0 || close >= window.triggerLevel()) {
            return Optional.empty();
        }
        return Optional.of(new SetupCandidate(window.touchIndex(), extensionPct, window.cleanTrigger(), sma200Obstacle(Direction.SHORT, close, snapshot), sma200Supportive(Direction.SHORT, close, snapshot)));
    }

    private PullbackWindow pullbackWindow(List<BarEvent> history, Direction direction) {
        int currentIndex = history.size() - 1;
        int start = Math.max(0, currentIndex - params.consolidationLookbackBars());
        int touchIndex = -1;
        double triggerLevel = direction == Direction.LONG ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        boolean cleanTrigger = false;
        for (int index = start; index < currentIndex; index++) {
            BarEvent candidate = history.get(index);
            OptionalDouble candidateSma = sma(history, index + 1, params.fastSmaPeriod());
            if (candidateSma.isEmpty()) {
                continue;
            }
            double fastSma = candidateSma.getAsDouble();
            if (direction == Direction.LONG) {
                triggerLevel = Math.max(triggerLevel, high(candidate));
                if (low(candidate) <= fastSma * (1.0d + pct(params.touchTolerancePct()))
                        && close(candidate) >= fastSma * (1.0d - pct(params.touchTolerancePct()))) {
                    touchIndex = index;
                }
            } else {
                triggerLevel = Math.min(triggerLevel, low(candidate));
                if (high(candidate) >= fastSma * (1.0d - pct(params.touchTolerancePct()))
                        && close(candidate) <= fastSma * (1.0d + pct(params.touchTolerancePct()))) {
                    touchIndex = index;
                }
            }
        }
        BarEvent current = last(history);
        if (direction == Direction.LONG) {
            cleanTrigger = close(current) > open(current) && close(current) > triggerLevel;
        } else {
            cleanTrigger = close(current) < open(current) && close(current) < triggerLevel;
        }
        return new PullbackWindow(touchIndex, triggerLevel, cleanTrigger);
    }

    private BigDecimal score(Direction direction, SmaSnapshot snapshot, SetupCandidate setup) {
        double score = 0.55d;
        if (Math.abs(snapshot.fastSlopePct()) >= params.minSma20SlopePct().doubleValue() * 2.0d) {
            score += 0.10d;
        }
        score += 0.10d;
        if (setup.cleanTrigger()) {
            score += 0.05d;
        }
        if (setup.sma200Supportive()) {
            score += 0.05d;
        }
        if (setup.sma200Obstacle()) {
            score -= 0.10d;
        }
        if (setup.extensionPct() > params.maxEntryExtensionPct().doubleValue() / 2.0d) {
            score -= 0.10d;
        }

        double clamped = Math.max(CONFIDENCE_FLOOR, Math.min(CONFIDENCE_CEILING, score));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean sma200Obstacle(Direction direction, double close, SmaSnapshot snapshot) {
        if (!params.useSma200ObstacleFilter()) {
            return false;
        }
        if (direction == Direction.LONG && close < snapshot.slowSma()) {
            return distancePct(close, snapshot.slowSma()) <= params.maxEntryExtensionPct().doubleValue();
        }
        if (direction == Direction.SHORT && close > snapshot.slowSma()) {
            return distancePct(close, snapshot.slowSma()) <= params.maxEntryExtensionPct().doubleValue();
        }
        return false;
    }

    private boolean sma200Supportive(Direction direction, double close, SmaSnapshot snapshot) {
        return direction == Direction.LONG ? close > snapshot.slowSma() : close < snapshot.slowSma();
    }

    private void resetResolvedSetups(BarEvent current, SmaSnapshot snapshot, TrendState trend) {
        if (trend != TrendState.UP || close(current) < snapshot.fastSma()) {
            lastLongSetupTouchIndex = -1;
        }
        if (trend != TrendState.DOWN || close(current) > snapshot.fastSma()) {
            lastShortSetupTouchIndex = -1;
        }
    }

    private TradeSignal signal(StrategyExecutionContext context, Direction direction, BigDecimal confidence) {
        BarEvent bar = context.currentBar();
        return new TradeSignal(
                Sma20PullbackContinuationStrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value() + '-' + direction.name().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                new ConfidenceScore(confidence),
                SetupType.CONTINUATION,
                new TimeHorizon("intraday", Duration.ofHours(4)),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                null,
                new TagSet(List.of(
                        "strategy_family=moving_average_pullback",
                        "fast_sma_period=" + params.fastSmaPeriod(),
                        "slow_sma_period=" + params.slowSmaPeriod(),
                        "setup=pullback_continuation",
                        "trigger=consolidation_breakout_or_breakdown",
                        "trend_filter=sma20_slope",
                        "strategy_note=20_SMA_trend_pullback_strategy_200_SMA_context"
                )),
                bar.cohort(),
                bar.baseline()
        );
    }

    private static OptionalDouble sma(List<BarEvent> history, int endExclusive, int period) {
        if (endExclusive < period || endExclusive > history.size()) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int index = endExclusive - period; index < endExclusive; index++) {
            total += close(history.get(index));
        }
        return OptionalDouble.of(total / period);
    }

    private static double distancePct(double left, double right) {
        return Math.abs(left - right) / Math.max(Math.abs(right), 1.0d) * 100.0d;
    }

    private static double pct(BigDecimal value) {
        return value.doubleValue() / 100.0d;
    }

    private static BarEvent last(List<BarEvent> history) {
        return history.get(history.size() - 1);
    }

    private static double open(BarEvent bar) {
        return bar.ohlcv().open().doubleValue();
    }

    private static double high(BarEvent bar) {
        return bar.ohlcv().high().doubleValue();
    }

    private static double low(BarEvent bar) {
        return bar.ohlcv().low().doubleValue();
    }

    private static double close(BarEvent bar) {
        return bar.ohlcv().close().doubleValue();
    }

    private enum SignalState {
        WARMUP,
        NO_TREND,
        TREND_UP,
        LONG_ARMED,
        TREND_DOWN,
        SHORT_ARMED,
        COOLDOWN
    }

    private enum TrendState {
        UP,
        DOWN,
        FLAT
    }

    private record SmaSnapshot(double fastSma, double slowSma, double fastSlopePct) {
        private static Optional<SmaSnapshot> from(List<BarEvent> history, Sma20PullbackParameters params) {
            OptionalDouble currentFast = sma(history, history.size(), params.fastSmaPeriod());
            OptionalDouble currentSlow = sma(history, history.size(), params.slowSmaPeriod());
            OptionalDouble previousFast = sma(history, history.size() - params.slopeLookbackBars(), params.fastSmaPeriod());
            if (currentFast.isEmpty() || currentSlow.isEmpty() || previousFast.isEmpty()) {
                return Optional.empty();
            }
            double slopePct = (currentFast.getAsDouble() - previousFast.getAsDouble()) / Math.max(Math.abs(previousFast.getAsDouble()), 1.0d) * 100.0d;
            return Optional.of(new SmaSnapshot(currentFast.getAsDouble(), currentSlow.getAsDouble(), slopePct));
        }
    }

    private record PullbackWindow(int touchIndex, double triggerLevel, boolean cleanTrigger) {
    }

    private record SetupCandidate(int touchIndex, double extensionPct, boolean cleanTrigger, boolean sma200Obstacle, boolean sma200Supportive) {
    }
}
