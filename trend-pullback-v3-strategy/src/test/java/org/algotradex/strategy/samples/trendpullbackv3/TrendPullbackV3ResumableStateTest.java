package org.algotradex.strategy.samples.trendpullbackv3;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyStateEnvelope;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.strategy.simulation.SimulationStepper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrendPullbackV3ResumableStateTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-trend-pullback-v3-resume-test"),
            new ReplayId("replay-trend-pullback-v3-resume-test"),
            ReplayMode.FULL_RUN
    );

    private final TrendPullbackV3StrategyProvider provider = new TrendPullbackV3StrategyProvider();

    @Test
    void resumableStateMatchesFreshReplayAcrossCheckpoint() {
        StrategyParameters parameters = new StrategyParameters(Map.of("cooldownHours", 4, "emitDiagnostics", true));
        TradeIntentStrategy original = strategy(parameters);
        StrategyExecutionContext first = context(bullishM15Setup(), h4TrendStructure(130.0d));
        StrategyExecutionContext second = context(shift(bullishM15Setup(), 1), h4TrendStructure(130.0d));
        original.onBarIntent(first);
        ResumableStrategy originalState = (ResumableStrategy) original;
        StrategyStateEnvelope checkpoint = originalState.initialState(
                TrendPullbackV3StrategyProvider.STRATEGY_VERSION,
                "variant-a",
                parameters
        );

        TradeIntentStrategy restored = strategy(parameters);
        ((ResumableStrategy) restored).resumeFromState(originalState.serialise(checkpoint));
        StrategyIntentResult restoredSecond = restored.onBarIntent(second);

        TradeIntentStrategy fresh = strategy(parameters);
        fresh.onBarIntent(first);
        StrategyIntentResult freshSecond = fresh.onBarIntent(second);

        assertThat(restoredSecond).usingRecursiveComparison().isEqualTo(freshSecond);
        assertThat(((ResumableStrategy) restored).snapshotState()).isEqualTo(((ResumableStrategy) fresh).snapshotState());
    }

    @Test
    void simulationStepperStateMatchesFreshReplayAcrossSerializedCheckpoint() {
        StrategyParameters parameters = new StrategyParameters(Map.of("cooldownHours", 4, "emitDiagnostics", true));
        List<BarEvent> h4 = h4TrendStructure(130.0d);
        List<BarEvent> warmup = bullishM15Setup();
        BarEvent next = shift(bullishM15Setup(), 1).getLast();

        SimulationStepper checkpointed = stepper(parameters);
        for (BarEvent bar : warmup) {
            checkpointed.step(bar, visibility(bar, h4), MarketContextSnapshot.empty());
        }
        StrategyStateEnvelope checkpoint = checkpointed.checkpoint("variant-a");

        SimulationStepper restored = stepper(parameters);
        restored.resumeFromSerialised(checkpointed.serialise(checkpoint), parameters);
        var restoredNext = restored.step(next, visibility(next, h4), MarketContextSnapshot.empty());

        SimulationStepper fresh = stepper(parameters);
        for (BarEvent bar : warmup) {
            fresh.step(bar, visibility(bar, h4), MarketContextSnapshot.empty());
        }
        var freshNext = fresh.step(next, visibility(next, h4), MarketContextSnapshot.empty());

        assertThat(restoredNext.result()).usingRecursiveComparison().isEqualTo(freshNext.result());
        assertThat(restored.currentState().strategyState()).isEqualTo(fresh.currentState().strategyState());
        assertThat(restored.currentState().resolvedParamsHash()).isEqualTo(fresh.currentState().resolvedParamsHash());
        assertThat(restored.currentState().currentPhase()).isNotBlank().isIn(declaredPhaseIds());
    }

    private TradeIntentStrategy strategy(StrategyParameters parameters) {
        return (TradeIntentStrategy) provider.create(parameters, null);
    }

    private SimulationStepper stepper(StrategyParameters parameters) {
        return new SimulationStepper(
                List.of((TradeSignalStrategy) provider.create(parameters, null)),
                METADATA,
                256,
                java.time.Clock.systemUTC(),
                TrendPullbackV3StrategyProvider.STRATEGY_VERSION,
                parameters
        );
    }

    private List<String> declaredPhaseIds() {
        return provider.descriptor().reasoningModel().phases().stream()
                .map(org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor::phaseId)
                .toList();
    }

    private static MarketDataVisibilitySnapshot visibility(BarEvent current, List<BarEvent> h4) {
        return new MarketDataVisibilitySnapshot(current.occurredAt(), current.timeframe(), Map.of("H4", h4), List.of());
    }

    private static StrategyExecutionContext context(List<BarEvent> executionBars, List<BarEvent> h4) {
        BarEvent current = executionBars.getLast();
        return new StrategyExecutionContext(
                METADATA,
                current,
                executionBars,
                new MarketDataVisibilitySnapshot(current.occurredAt(), current.timeframe(), Map.of("H4", h4), List.of()),
                null
        );
    }

    private static List<BarEvent> bullishM15Setup() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            bars.add(m15(index, 106.0d, 107.0d, 104.5d, 105.0d));
        }
        bars.add(m15(18, 105.0d, 105.5d, 100.5d, 101.0d));
        bars.add(m15(19, 100.5d, 107.0d, 99.5d, 106.0d));
        return bars;
    }

    private static List<BarEvent> h4TrendStructure(double targetHigh) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            double close = 80.0d + (index * 0.55d);
            bars.add(h4(index, close - 0.3d, close + 1.0d, close - 1.0d, close));
        }
        bars.add(h4(50, 109.0d, targetHigh, 106.0d, 110.0d));
        bars.add(h4(51, 108.0d, 116.0d, 104.0d, 109.0d));
        bars.add(h4(52, 107.0d, 113.0d, 103.0d, 108.0d));
        bars.add(h4(53, 106.0d, 111.0d, 102.0d, 107.0d));
        bars.add(h4(54, 105.0d, 110.0d, 100.0d, 106.0d));
        bars.add(h4(55, 106.0d, 112.0d, 102.0d, 108.0d));
        bars.add(h4(56, 108.0d, 113.0d, 103.0d, 109.0d));
        bars.add(h4(57, 109.0d, 114.0d, 104.0d, 110.0d));
        bars.add(h4(58, 110.0d, 115.0d, 105.0d, 111.0d));
        bars.add(h4(59, 111.0d, 116.0d, 106.0d, 112.0d));
        return bars;
    }

    private static List<BarEvent> shift(List<BarEvent> source, int minutes) {
        List<BarEvent> shifted = new ArrayList<>();
        for (BarEvent bar : source) {
            shifted.add(new BarEvent(
                    bar.schemaVersion(),
                    new EventId(bar.eventId().value() + "-s" + minutes),
                    bar.instrument(),
                    bar.occurredAt().plusSeconds(minutes * 60L),
                    bar.timeframe(),
                    bar.ohlcv(),
                    bar.sourceRef(),
                    bar.cohort(),
                    bar.baseline(),
                    bar.tags()
            ));
        }
        return shifted;
    }

    private static BarEvent m15(int index, double open, double high, double low, double close) {
        return bar("m15", "M15", index, 900L, open, high, low, close);
    }

    private static BarEvent h4(int index, double open, double high, double low, double close) {
        return bar("h4", "H4", index, 14_400L, open, high, low, close);
    }

    private static BarEvent bar(String prefix, String timeframe, int index, long seconds, double open, double high, double low, double close) {
        return new BarEvent(
                "1.0.0",
                new EventId(prefix + "-bar-%03d".formatted(index + 1)),
                INSTRUMENT,
                Instant.parse("2026-04-11T09:15:00Z").plusSeconds(index * seconds),
                timeframe,
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), BigDecimal.valueOf(1000L + index)),
                new SourceRef(SourceType.ADAPTER, "trend-pullback-v3-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
