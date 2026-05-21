package org.algotradex.strategy.samples.ema;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyStateEnvelope;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.strategy.simulation.SimulationStepper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EmaCrossoverStrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("RELIANCE-EQ", "RELIANCE", "NSE", AssetClass.EQUITY, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-ema-crossover-sim"),
            new ReplayId("replay-ema-crossover-sim"),
            ReplayMode.FULL_RUN
    );
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC);

    private final EmaCrossoverStrategyProvider provider = new EmaCrossoverStrategyProvider();

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(EmaCrossoverStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.parameterSchema().parameters()).hasSize(4);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().integer("fastEmaPeriod", 0)).isEqualTo(9);
        assertThat(validation.effectiveParameters().integer("slowEmaPeriod", 0)).isEqualTo(21);
        assertThat(validation.effectiveParameters().bool("allowShorts", true)).isFalse();
    }

    @Test
    void rejectsFastPeriodGreaterThanOrEqualToSlowPeriod() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "fastEmaPeriod", 21,
                "slowEmaPeriod", 21
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("slowEmaPeriod");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(EmaCrossoverStrategyProvider.STRATEGY_ID);
    }

    @Test
    void simulationLabCompatibilitySurvivesBoundedRetainedHistoryResume() {
        StrategyParameters parameters = new StrategyParameters(Map.of(
                "fastEmaPeriod", 3,
                "slowEmaPeriod", 5,
                "allowShorts", true
        ));
        List<BarEvent> bars = bars("100", "99", "98", "97", "96", "95", "99", "103", "100", "97");

        SimulationStepper checkpointed = stepper(parameters);
        int checkpointIndex = 6;
        for (int index = 0; index < checkpointIndex; index++) {
            checkpointed.step(bars.get(index));
        }
        byte[] serialised = checkpointed.serialise(checkpointed.checkpoint("variant-a"));

        SimulationStepper restored = stepper(parameters);
        restored.resumeFromSerialised(serialised, parameters);
        List<String> restoredSignals = replayStepperSignals(restored, bars.subList(checkpointIndex, bars.size()));

        SimulationStepper fresh = stepper(parameters);
        List<String> freshSignals = replayStepperSignals(fresh, bars);

        assertThat(restoredSignals).containsExactly("LONG:2026-01-02T16:15:00Z", "SHORT:2026-01-02T18:15:00Z");
        assertThat(restoredSignals).isEqualTo(freshSignals);
        assertThat(restored.currentState().strategyState()).isEqualTo(fresh.currentState().strategyState());
        assertThat(restored.currentState().currentPhase()).isNotBlank();

        TradeSignalStrategy strategy = provider.create(parameters, null);
        assertThat(strategy).isInstanceOf(ResumableStrategy.class).isInstanceOf(StrategyReasoningEvaluator.class);
        assertThat(provider.descriptor().reasoningModel().phases()).extracting("phaseId")
                .contains("warmup", "scanning", "signal");
        assertThat(provider.descriptor().parameterSchema().parameters())
                .allSatisfy(parameter -> assertThat(parameter.resumePolicy()).as(parameter.key()).isNotNull());
    }

    @Test
    void reasoningObserverDoesNotAdvanceCursorBeforeDecision() {
        StrategyParameters parameters = new StrategyParameters(Map.of(
                "fastEmaPeriod", 3,
                "slowEmaPeriod", 5,
                "allowShorts", true
        ));
        List<BarEvent> bars = bars("100", "99", "98", "97", "96", "95", "99", "103");
        TradeSignalStrategy strategy = provider.create(parameters, null);
        for (int endExclusive = 1; endExclusive < bars.size(); endExclusive++) {
            strategy.onBar(context(bars.subList(0, endExclusive)));
        }
        ResumableStrategy resumable = (ResumableStrategy) strategy;
        StrategyReasoningEvaluator reasoning = (StrategyReasoningEvaluator) strategy;
        Map<String, Object> stateBeforeReasoning = resumable.snapshotState();

        reasoning.evaluateReasoning(context(bars));
        reasoning.currentPhase(context(bars));

        assertThat(resumable.snapshotState()).isEqualTo(stateBeforeReasoning);
        Optional<TradeSignal> signal = strategy.onBar(context(bars));
        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.LONG);
        assertThat(signal.get().occurredAt()).isEqualTo(Instant.parse("2026-01-02T16:15:00Z"));
    }

    @Test
    void simulationStepperSignalsMatchDirectReplayGoldenSequence() {
        StrategyParameters parameters = new StrategyParameters(Map.of(
                "fastEmaPeriod", 3,
                "slowEmaPeriod", 5,
                "allowShorts", true
        ));
        List<BarEvent> bars = bars("100", "99", "98", "97", "96", "95", "99", "103", "100", "97");

        List<String> directSignals = replayDirectSignals(provider.create(parameters, null), bars);
        List<String> stepperSignals = replayStepperSignals(stepper(parameters), bars);

        assertThat(directSignals).containsExactly("LONG:2026-01-02T16:15:00Z", "SHORT:2026-01-02T18:15:00Z");
        assertThat(stepperSignals).isEqualTo(directSignals);
    }

    @Test
    void resumedStrategyWithOnlyPostCheckpointBarsMatchesGoldenSequence() {
        StrategyParameters parameters = new StrategyParameters(Map.of(
                "fastEmaPeriod", 3,
                "slowEmaPeriod", 5,
                "allowShorts", true
        ));
        List<BarEvent> bars = bars("100", "99", "98", "97", "96", "95", "99", "103", "100", "97");
        int checkpointIndex = 6;
        TradeSignalStrategy original = provider.create(parameters, null);
        for (int index = 0; index < checkpointIndex; index++) {
            original.onBar(context(bars.subList(0, index + 1)));
        }
        ResumableStrategy originalState = (ResumableStrategy) original;
        StrategyStateEnvelope checkpoint = originalState.initialState(
                EmaCrossoverStrategyProvider.STRATEGY_VERSION,
                "variant-a",
                parameters
        );
        TradeSignalStrategy restored = provider.create(parameters, null);
        ((ResumableStrategy) restored).resumeFromState(originalState.serialise(checkpoint));

        List<String> resumedSignals = new ArrayList<>();
        for (int index = checkpointIndex; index < bars.size(); index++) {
            restored.onBar(context(List.of(bars.get(index))))
                    .map(signal -> signal.direction().name() + ':' + signal.occurredAt())
                    .ifPresent(resumedSignals::add);
        }

        assertThat(resumedSignals).containsExactly("LONG:2026-01-02T16:15:00Z", "SHORT:2026-01-02T18:15:00Z");
    }

    private SimulationStepper stepper(StrategyParameters parameters) {
        return new SimulationStepper(
                List.of(provider.create(parameters, null)),
                METADATA,
                256,
                CLOCK,
                EmaCrossoverStrategyProvider.STRATEGY_VERSION,
                parameters,
                3
        );
    }

    private static List<String> replayDirectSignals(TradeSignalStrategy strategy, List<BarEvent> bars) {
        List<String> signals = new ArrayList<>();
        for (int endExclusive = 1; endExclusive <= bars.size(); endExclusive++) {
            strategy.onBar(context(bars.subList(0, endExclusive)))
                    .map(signal -> signal.direction().name() + ':' + signal.occurredAt())
                    .ifPresent(signals::add);
        }
        return signals;
    }

    private static List<String> replayStepperSignals(SimulationStepper stepper, List<BarEvent> bars) {
        List<String> signals = new ArrayList<>();
        for (BarEvent bar : bars) {
            stepper.step(bar).result().emittedTradeSignals().stream()
                    .map(signal -> signal.direction().name() + ':' + signal.occurredAt())
                    .forEach(signals::add);
        }
        return signals;
    }

    private static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.getLast(), history);
    }

    private static List<BarEvent> bars(String... closes) {
        List<BarEvent> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-01-02T09:15:00Z");
        for (int index = 0; index < closes.length; index++) {
            BigDecimal close = new BigDecimal(closes[index]);
            bars.add(new BarEvent(
                    "1.0.0",
                    new EventId("bar-%03d".formatted(index + 1)),
                    INSTRUMENT,
                    start.plusSeconds(index * 3600L),
                    "H1",
                    new OHLCV(close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close, BigDecimal.valueOf(1000L + index)),
                    new SourceRef(SourceType.ADAPTER, "market-data-replay"),
                    null,
                    null,
                    null
            ));
        }
        return bars;
    }
}
