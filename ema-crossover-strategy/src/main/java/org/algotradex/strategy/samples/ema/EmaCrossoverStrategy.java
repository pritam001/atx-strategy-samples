package org.algotradex.strategy.samples.ema;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.TimeHorizon;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.indicator.RollingIndicators;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Sample {@link TradeSignalStrategy} that emits a signal when a fast EMA crosses a slow EMA on
 * closed bars.
 * <p>
 * The strategy keeps run-local rolling EMA state and assumes the platform creates a fresh instance
 * for one replay/instrument execution. It is deterministic for the same ordered bar history and
 * effective parameters, but it is not thread-safe and must not be shared across concurrent runs.
 * <p>
 * This class owns only signal detection and signal metadata. It does not place orders, route broker
 * requests, manage positions, or perform portfolio accounting; those concerns remain platform
 * runtime responsibilities.
 */
public final class EmaCrossoverStrategy implements TradeSignalStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private final BigDecimal confidence;
    private final boolean allowShorts;
    private final int fastPeriod;
    private final int slowPeriod;
    private final RollingIndicators.EmaPairTracker emaPairTracker;
    private int processedBars;
    private String lastProcessedEventId = "";

    EmaCrossoverStrategy(int fastPeriod, int slowPeriod, BigDecimal confidence, boolean allowShorts) {
        if (fastPeriod < 2) {
            throw new IllegalArgumentException("fastPeriod must be >= 2");
        }
        if (slowPeriod <= fastPeriod) {
            throw new IllegalArgumentException("slowPeriod must be greater than fastPeriod");
        }
        this.confidence = requireNonNull(confidence, "confidence");
        this.allowShorts = allowShorts;
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.emaPairTracker = new RollingIndicators.EmaPairTracker(fastPeriod, slowPeriod);
    }

    @Override
    public String strategyId() {
        return EmaCrossoverStrategyProvider.STRATEGY_ID;
    }

    @Override
    public String stateSchemaVersion() {
        return "ema-crossover-v1-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "processedBars", processedBars,
                "lastProcessedEventId", lastProcessedEventId,
                "emaPairTracker", emaPairTracker.snapshotState()
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        processedBars = asInt(state == null ? null : state.get("processedBars"), 0);
        lastProcessedEventId = asString(state == null ? null : state.get("lastProcessedEventId"));
        Object trackerState = state == null ? null : state.get("emaPairTracker");
        if (trackerState != null) {
            emaPairTracker.restoreState(ResumableStrategy.STATE_MAPPER.convertValue(
                    trackerState,
                    RollingIndicators.EmaPairTrackerState.class
            ));
        }
    }

    @Override
    public void restoreStateForReWarm(Map<String, Object> checkpointState) {
        processedBars = 0;
        lastProcessedEventId = "";
        emaPairTracker.restoreState(new RollingIndicators.EmaPairTracker(fastPeriod, slowPeriod).snapshotState());
    }

    @Override
    public Optional<TradeSignal> onBar(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        Optional<RollingIndicators.EmaPairCrossState> state = advanceIndicators(history);
        if (state.isEmpty()) {
            return Optional.empty();
        }

        RollingIndicators.EmaPair previous = state.get().previous();
        RollingIndicators.EmaPair current = state.get().current();
        if (previous.fast() <= previous.slow() && current.fast() > current.slow()) {
            return Optional.of(signal(context, Direction.LONG));
        }
        if (allowShorts && previous.fast() >= previous.slow() && current.fast() < current.slow()) {
            return Optional.of(signal(context, Direction.SHORT));
        }
        return Optional.empty();
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        Optional<RollingIndicators.EmaPairCrossState> maybeState = evaluateCrossState(context.instrumentHistory());
        if (maybeState.isEmpty()) {
            return List.of(new ThoughtConditionEvidence(
                    "ema-crossover.warmup",
                    "EMA crossover warmup complete",
                    ConditionRole.ENTRY_FILTER,
                    false,
                    "Waiting for enough closed bars to seed both EMA series"
            ));
        }
        RollingIndicators.EmaPair previous = maybeState.get().previous();
        RollingIndicators.EmaPair current = maybeState.get().current();
        boolean crossedLong = previous.fast() <= previous.slow() && current.fast() > current.slow();
        boolean crossedShort = allowShorts && previous.fast() >= previous.slow() && current.fast() < current.slow();
        return List.of(
                new ThoughtConditionEvidence(
                        "ema-crossover.long-cross",
                        "Fast EMA crossed above slow EMA",
                        ConditionRole.ENTRY_TRIGGER,
                        crossedLong,
                        crossedLong ? "Long crossover fired on this closed bar" : "Fast EMA has not crossed above the slow EMA"
                ),
                new ThoughtConditionEvidence(
                        "ema-crossover.short-cross",
                        "Fast EMA crossed below slow EMA",
                        ConditionRole.ENTRY_TRIGGER,
                        crossedShort,
                        crossedShort ? "Short crossover fired on this closed bar" : "Fast EMA has not crossed below the slow EMA"
                )
        );
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        Optional<RollingIndicators.EmaPairCrossState> maybeState = evaluateCrossState(context.instrumentHistory());
        if (maybeState.isEmpty()) {
            return "warmup";
        }
        RollingIndicators.EmaPair previous = maybeState.get().previous();
        RollingIndicators.EmaPair current = maybeState.get().current();
        boolean signal = previous.fast() <= previous.slow() && current.fast() > current.slow()
                || allowShorts && previous.fast() >= previous.slow() && current.fast() < current.slow();
        return signal ? "signal" : "scanning";
    }

    private Optional<RollingIndicators.EmaPairCrossState> advanceIndicators(List<BarEvent> history) {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        int startIndex = nextUnprocessedIndex(history);
        if (startIndex >= history.size()) {
            return Optional.empty();
        }
        Optional<RollingIndicators.EmaPairCrossState> state = Optional.empty();
        for (int index = startIndex; index < history.size(); index++) {
            BarEvent bar = history.get(index);
            state = emaPairTracker.update(RollingIndicators.close(bar));
            lastProcessedEventId = bar.eventId().value();
        }
        processedBars += history.size() - startIndex;
        return state;
    }

    private int nextUnprocessedIndex(List<BarEvent> history) {
        if (!lastProcessedEventId.isBlank()) {
            for (int index = history.size() - 1; index >= 0; index--) {
                if (lastProcessedEventId.equals(history.get(index).eventId().value())) {
                    return index + 1;
                }
            }
            if (processedBars > history.size()) {
                // Retained Simulation Lab windows may omit the checkpoint anchor. In that case,
                // the only bar guaranteed to be post-checkpoint is the current tail bar.
                return history.size() - 1;
            }
        }
        if (processedBars <= 0) {
            return 0;
        }
        return processedBars <= history.size() ? processedBars : history.size() - 1;
    }

    private Optional<RollingIndicators.EmaPairCrossState> evaluateCrossState(List<BarEvent> history) {
        int checkpointProcessedBars = processedBars;
        String checkpointLastProcessedEventId = lastProcessedEventId;
        RollingIndicators.EmaPairTrackerState checkpointTracker = emaPairTracker.snapshotState();
        Optional<RollingIndicators.EmaPairCrossState> state = advanceIndicators(history);
        emaPairTracker.restoreState(checkpointTracker);
        processedBars = checkpointProcessedBars;
        lastProcessedEventId = checkpointLastProcessedEventId;
        return state;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : "";
    }

    private TradeSignal signal(StrategyExecutionContext context, Direction direction) {
        BarEvent bar = context.currentBar();
        return new TradeSignal(
                EmaCrossoverStrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value()),
                bar.instrument(),
                direction,
                new ConfidenceScore(confidence),
                SetupType.CONTINUATION,
                new TimeHorizon("intraday", Duration.ofHours(4)),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                null,
                null,
                bar.cohort(),
                bar.baseline()
        );
    }
}
