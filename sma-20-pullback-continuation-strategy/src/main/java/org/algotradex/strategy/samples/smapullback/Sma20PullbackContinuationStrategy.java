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
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

/**
 * Sample {@link TradeSignalStrategy} for SMA pullback continuation setups on closed bars.
 * <p>
 * The strategy classifies a fast-SMA trend, looks for a recent pullback/touch followed by a
 * continuation trigger, and uses the slow SMA as support/resistance context. It emits at most one
 * {@link TradeSignal} per invocation and suppresses repeated setup touches through run-local
 * cooldown and touch tracking.
 * <p>
 * Instances are mutable and run-scoped, not thread-safe. The strategy owns setup detection and
 * signal scoring only; order execution, broker routing, accepted-position lifecycle, and portfolio
 * accounting are platform-owned.
 */
public final class Sma20PullbackContinuationStrategy implements TradeSignalStrategy, ResumableStrategy, StrategyReasoningEvaluator {
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
    public String stateSchemaVersion() {
        return "sma-20-pullback-continuation-v1-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "state", state.name(),
                "cooldownRemaining", cooldownRemaining,
                "lastLongSetupTouchIndex", lastLongSetupTouchIndex,
                "lastShortSetupTouchIndex", lastShortSetupTouchIndex
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        this.state = SignalState.valueOf(String.valueOf(state == null ? SignalState.NO_TREND.name() : state.getOrDefault("state", SignalState.NO_TREND.name())));
        cooldownRemaining = asInt(state == null ? null : state.get("cooldownRemaining"), 0);
        lastLongSetupTouchIndex = asInt(state == null ? null : state.get("lastLongSetupTouchIndex"), -1);
        lastShortSetupTouchIndex = asInt(state == null ? null : state.get("lastShortSetupTouchIndex"), -1);
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

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.size() < params.slowSmaPeriod()) {
            return List.of(new ThoughtConditionEvidence(
                    "sma-pullback.warmup",
                    "SMA pullback warmup complete",
                    ConditionRole.ENTRY_FILTER,
                    false,
                    "Waiting for enough bars to compute both SMA guides"
            ));
        }
        if (cooldownRemaining > 0) {
            return List.of(new ThoughtConditionEvidence(
                    "sma-pullback.cooldown",
                    "Cooldown is clear",
                    ConditionRole.RISK_GUARD,
                    false,
                    "Cooldown is still suppressing duplicate pullback signals"
            ));
        }
        Optional<SmaSnapshot> snapshot = SmaSnapshot.from(history, params);
        if (snapshot.isEmpty()) {
            return List.of(new ThoughtConditionEvidence(
                    "sma-pullback.warmup",
                    "SMA pullback warmup complete",
                    ConditionRole.ENTRY_FILTER,
                    false,
                    "Waiting for slope lookback and SMA values"
            ));
        }
        TrendState trend = classifyTrend(snapshot.get());
        Optional<SetupCandidate> longSetup = trend == TrendState.UP ? longSetup(history, snapshot.get()) : Optional.empty();
        Optional<SetupCandidate> shortSetup = params.allowShorts() && trend == TrendState.DOWN ? shortSetup(history, snapshot.get()) : Optional.empty();
        boolean trendReady = trend != TrendState.FLAT;
        boolean pullbackReady = longSetup.isPresent() || shortSetup.isPresent();
        boolean confidenceReady = longSetup.map(setup -> score(Direction.LONG, snapshot.get(), setup).compareTo(params.minConfidence()) >= 0)
                .orElseGet(() -> shortSetup.map(setup -> score(Direction.SHORT, snapshot.get(), setup).compareTo(params.minConfidence()) >= 0).orElse(false));
        return List.of(
                new ThoughtConditionEvidence("sma-pullback.trend", "Fast SMA slope defines a trend", ConditionRole.ENTRY_FILTER, trendReady, trendReady ? "Fast SMA slope has directional bias" : "Fast SMA slope is flat"),
                new ThoughtConditionEvidence("sma-pullback.pullback", "Pullback touch and trigger are present", ConditionRole.ENTRY_TRIGGER, pullbackReady, pullbackReady ? "A pullback touch and trigger are present" : "No qualifying pullback trigger yet"),
                new ThoughtConditionEvidence("sma-pullback.confidence", "Setup confidence clears threshold", ConditionRole.RISK_GUARD, confidenceReady, confidenceReady ? "Computed setup confidence clears the threshold" : "Setup confidence does not clear the threshold")
        );
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        if (context.instrumentHistory().size() < params.slowSmaPeriod()) {
            return "warmup";
        }
        if (cooldownRemaining > 0 || state == SignalState.COOLDOWN) {
            return "cooldown";
        }
        return evaluateReasoning(context).stream().anyMatch(evidence -> evidence.conditionId().equals("sma-pullback.pullback") && evidence.passed())
                ? "signal"
                : "scanning";
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

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
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
