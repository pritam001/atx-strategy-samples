package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EmaTrendStructurePullbackStrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-ema-trend-pullback-test"),
            new ReplayId("replay-ema-trend-pullback-test"),
            ReplayMode.FULL_RUN
    );

    private final EmaTrendStructurePullbackStrategyProvider provider = new EmaTrendStructurePullbackStrategyProvider();

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(EmaTrendStructurePullbackStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.displayName()).isEqualTo("EMA Trend Structure Pullback");
        assertThat(descriptor.supportedTimeframes()).containsExactly("M15", "H1");
        assertThat(descriptor.parameterSchema().parameters()).hasSize(20);
        assertThat(descriptor.suggestedChartStudies()).hasSize(4);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().integer("fastEmaPeriod", 0)).isEqualTo(20);
        assertThat(validation.effectiveParameters().integer("mediumEmaPeriod", 0)).isEqualTo(50);
        assertThat(validation.effectiveParameters().integer("slowEmaPeriod", 0)).isEqualTo(200);
        assertThat(validation.effectiveParameters().decimal("minConfidence", BigDecimal.ZERO)).isEqualByComparingTo("0.70");
        assertThat(validation.effectiveParameters().bool("allowShorts", true)).isFalse();
    }

    @Test
    void rejectsInvalidParameters() {
        var periodValidation = provider.validate(new StrategyParameters(Map.of(
                "fastEmaPeriod", 50,
                "mediumEmaPeriod", 20,
                "slowEmaPeriod", 200
        )));
        var rangeValidation = provider.validate(new StrategyParameters(Map.of(
                "pullbackMinBars", 10,
                "pullbackLookbackBars", 8,
                "compressedSeparationThresholdPct", "1.50",
                "expandingSeparationThresholdPct", "1.00",
                "idealDistanceFromFastEmaPct", "4.00",
                "maxDistanceFromFastEmaPct", "3.00"
        )));

        assertThat(periodValidation.valid()).isFalse();
        assertThat(periodValidation.issues()).extracting("field").contains("mediumEmaPeriod");
        assertThat(rangeValidation.valid()).isFalse();
        assertThat(rangeValidation.issues()).extracting("field")
                .contains("pullbackMinBars", "expandingSeparationThresholdPct", "idealDistanceFromFastEmaPct");
    }

    @Test
    void exposesEffectiveChartStudiesFromParameters() {
        var studies = provider.effectiveChartStudies(new StrategyParameters(Map.of(
                "fastEmaPeriod", 10,
                "mediumEmaPeriod", 30,
                "slowEmaPeriod", 100
        )));

        assertThat(studies).hasSize(4);
        assertThat(studies.get(0).indicatorId()).isEqualTo("ema");
        assertThat(studies.get(0).role()).isEqualTo("fast-ema");
        assertThat(studies.get(0).parameters()).containsEntry("period", 10);
        assertThat(studies.get(1).parameters()).containsEntry("period", 30);
        assertThat(studies.get(2).parameters()).containsEntry("period", 100);
        assertThat(studies.get(3).indicatorId()).isEqualTo("ema-trend-structure");
        assertThat(studies.get(3).formulaVersion()).isEqualTo("ema-trend-structure-v1");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(EmaTrendStructurePullbackStrategyProvider.STRATEGY_ID);
    }

    @Test
    void computesDeterministicEmaWithSmaSeed() {
        double[] ema = EmaTrendStructurePullbackStrategy.emaSeries(bars("10.00", "11.00", "12.00", "13.00"), 3);

        assertThat(ema[0]).isNaN();
        assertThat(ema[1]).isNaN();
        assertThat(ema[2]).isEqualTo(11.0d);
        assertThat(ema[3]).isEqualTo(12.0d);
    }

    @Test
    void emitsNoSignalBeforeDefaultReadiness() {
        var strategy = provider.create(StrategyParameters.empty(), null);

        assertThat(strategy.onBar(context(trendBars(204, 100.0d, 0.25d)))).isEmpty();
    }

    @Test
    void emitsBullishPullbackContinuationOncePerPullbackCycle() {
        var strategy = compactStrategy(false, "0.70", "3.00", 5);
        List<BarEvent> history = bullishPullbackBars();

        List<TradeSignal> signals = replay(strategy, history);
        assertThat(signals).hasSize(1);
        TradeSignal signal = signals.getFirst();
        assertThat(signal.direction()).isEqualTo(Direction.LONG);
        assertThat(signal.setupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(signal.confidence().value()).isGreaterThanOrEqualTo(new BigDecimal("0.70"));
        assertThat(signal.tags().values()).contains(
                "strategy_family=ema_trend_structure_pullback",
                "setup=bullish_pullback_continuation",
                "formula_version=ema-trend-structure-pullback-v1"
        );

        List<BarEvent> continuation = new ArrayList<>(history);
        continuation.add(upBar(continuation.size(), 113.20d));
        continuation.add(upBar(continuation.size(), 113.70d));
        continuation.add(upBar(continuation.size(), 114.10d));
        for (int index = history.size() + 1; index <= continuation.size(); index++) {
            assertThat(strategy.onBar(context(continuation.subList(0, index)))).isEmpty();
        }
    }

    @Test
    void rejectsBullishContinuationWhenEntryIsOverextended() {
        var strategy = compactStrategy(false, "0.70", "3.00", 5);
        List<BarEvent> history = bullishPullbackBars();
        history.set(history.size() - 1, upSignalBar(history.size() - 1, 118.50d));

        assertThat(replay(strategy, history)).isEmpty();
    }

    @Test
    void rejectsChoppyContinuationWhenCrossCountReachesThreshold() {
        var strategy = compactStrategy(false, "0.70", "3.00", 1);
        List<BarEvent> history = bullishPullbackBars();
        history.set(13, bar(13, 111.00d, 111.40d, 109.90d, 110.20d));

        assertThat(replay(strategy, history)).isEmpty();
    }

    @Test
    void emitsBullishTransitionBreakoutAfterCompression() {
        var strategy = transitionStrategy();
        List<BarEvent> history = bullishTransitionBars();

        List<TradeSignal> signals = replay(strategy, history);
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().direction()).isEqualTo(Direction.LONG);
        assertThat(signals.getFirst().setupType()).isEqualTo(SetupType.CONTINUATION);
        assertThat(signals.getFirst().tags().values()).contains("setup=bullish_transition_breakout");
        assertThat(signals.getFirst().confidence().value()).isGreaterThanOrEqualTo(new BigDecimal("0.70"));
    }

    @Test
    void defaultShortsDisabledAndConfigurableBearishPullbackEmitsWhenEnabled() {
        List<BarEvent> history = bearishPullbackBars();
        assertThat(replay(compactStrategy(false, "0.70", "3.00", 5), history)).isEmpty();

        List<TradeSignal> signals = replay(compactStrategy(true, "0.70", "3.00", 5), history);
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().direction()).isEqualTo(Direction.SHORT);
        assertThat(signals.getFirst().setupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(signals.getFirst().tags().values()).contains("setup=bearish_pullback_continuation");
    }

    @Test
    void rejectsFlatCompressionAndMixedStructure() {
        var strategy = compactStrategy(false, "0.70", "3.00", 5);

        assertThat(replay(strategy, flatBars(24, 100.0d))).isEmpty();
    }

    private static EmaTrendStructurePullbackStrategy compactStrategy(boolean allowShorts, String minConfidence, String maxFastDistancePct, int chopThreshold) {
        return new EmaTrendStructurePullbackStrategy(new EmaTrendStructurePullbackParameters(
                3,
                5,
                8,
                2,
                new BigDecimal("0.05"),
                new BigDecimal("0.50"),
                new BigDecimal("1.50"),
                6,
                chopThreshold,
                8,
                2,
                new BigDecimal("0.35"),
                new BigDecimal(maxFastDistancePct),
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                3,
                5,
                new BigDecimal(minConfidence),
                allowShorts,
                3
        ));
    }

    private static EmaTrendStructurePullbackStrategy transitionStrategy() {
        return new EmaTrendStructurePullbackStrategy(new EmaTrendStructurePullbackParameters(
                3,
                5,
                8,
                2,
                new BigDecimal("0.01"),
                new BigDecimal("0.20"),
                new BigDecimal("1.50"),
                8,
                5,
                8,
                2,
                new BigDecimal("0.35"),
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                3,
                5,
                new BigDecimal("0.70"),
                false,
                3
        ));
    }

    private static List<TradeSignal> replay(org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy strategy, List<BarEvent> bars) {
        List<TradeSignal> signals = new ArrayList<>();
        for (int index = 1; index <= bars.size(); index++) {
            Optional<TradeSignal> signal = strategy.onBar(context(bars.subList(0, index)));
            signal.ifPresent(signals::add);
        }
        return signals;
    }

    private static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.getLast(), history);
    }

    private static List<BarEvent> bullishPullbackBars() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            bars.add(upBar(index, 100.0d + index));
        }
        bars.add(bar(13, 111.80d, 112.00d, 110.80d, 111.20d));
        bars.add(bar(14, 111.30d, 111.90d, 111.00d, 111.50d));
        bars.add(upSignalBar(15, 112.80d));
        return bars;
    }

    private static List<BarEvent> bearishPullbackBars() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            bars.add(downBar(index, 120.0d - index));
        }
        bars.add(bar(13, 108.10d, 109.80d, 108.00d, 109.40d));
        bars.add(bar(14, 109.30d, 109.50d, 108.80d, 109.10d));
        bars.add(downSignalBar(15, 107.80d));
        return bars;
    }

    private static List<BarEvent> bullishTransitionBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 120.0d;
        for (int index = 0; index < 10; index++) {
            close -= 1.0d;
            bars.add(downBar(index, close));
        }
        for (int index = 10; index < 19; index++) {
            close += 0.10d;
            bars.add(bar(index, close - 0.03d, close + 0.10d, close - 0.10d, close));
        }
        bars.add(upSignalBar(19, close + 1.20d));
        return bars;
    }

    private static List<BarEvent> trendBars(int count, double start, double step) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(upBar(index, start + (step * index)));
        }
        return bars;
    }

    private static List<BarEvent> flatBars(int count, double close) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(bar(index, close, close + 0.10d, close - 0.10d, close));
        }
        return bars;
    }

    private static List<BarEvent> bars(String... closes) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < closes.length; index++) {
            bars.add(upBar(index, new BigDecimal(closes[index]).doubleValue()));
        }
        return bars;
    }

    private static BarEvent upBar(int index, double close) {
        return bar(index, close - 0.20d, close + 0.25d, close - 0.50d, close);
    }

    private static BarEvent downBar(int index, double close) {
        return bar(index, close + 0.20d, close + 0.50d, close - 0.25d, close);
    }

    private static BarEvent upSignalBar(int index, double close) {
        return bar(index, close - 0.70d, close + 0.25d, close - 0.75d, close);
    }

    private static BarEvent downSignalBar(int index, double close) {
        return bar(index, close + 0.70d, close + 0.75d, close - 0.25d, close);
    }

    private static BarEvent bar(int index, double open, double high, double low, double close) {
        return new BarEvent(
                "1.0.0",
                new EventId("bar-%03d".formatted(index + 1)),
                INSTRUMENT,
                Instant.parse("2026-04-11T09:15:00Z").plusSeconds((long) index * 900L),
                "M15",
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), BigDecimal.valueOf(1000L + index)),
                new SourceRef(SourceType.ADAPTER, "ema-trend-pullback-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
