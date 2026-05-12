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
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.indicator.RollingIndicators;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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
public final class EmaCrossoverStrategy implements TradeSignalStrategy {
    private final BigDecimal confidence;
    private final boolean allowShorts;
    private final RollingIndicators.EmaPairTracker emaPairTracker;
    private int processedBars;

    EmaCrossoverStrategy(int fastPeriod, int slowPeriod, BigDecimal confidence, boolean allowShorts) {
        if (fastPeriod < 2) {
            throw new IllegalArgumentException("fastPeriod must be >= 2");
        }
        if (slowPeriod <= fastPeriod) {
            throw new IllegalArgumentException("slowPeriod must be greater than fastPeriod");
        }
        this.confidence = requireNonNull(confidence, "confidence");
        this.allowShorts = allowShorts;
        this.emaPairTracker = new RollingIndicators.EmaPairTracker(fastPeriod, slowPeriod);
    }

    @Override
    public String strategyId() {
        return EmaCrossoverStrategyProvider.STRATEGY_ID;
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

    private Optional<RollingIndicators.EmaPairCrossState> advanceIndicators(List<BarEvent> history) {
        if (history.size() <= processedBars) {
            return Optional.empty();
        }
        Optional<RollingIndicators.EmaPairCrossState> state = Optional.empty();
        for (int index = processedBars; index < history.size(); index++) {
            state = emaPairTracker.update(RollingIndicators.close(history.get(index)));
        }
        processedBars = history.size();
        return state;
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
