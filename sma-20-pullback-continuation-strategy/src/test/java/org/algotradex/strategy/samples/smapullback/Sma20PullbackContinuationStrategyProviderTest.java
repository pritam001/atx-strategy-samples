package org.algotradex.strategy.samples.smapullback;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class Sma20PullbackContinuationStrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("HDFCBANK-EQ", "HDFCBANK", "NSE", AssetClass.EQUITY, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-001"),
            new ReplayId("replay-001"),
            ReplayMode.FULL_RUN
    );

    private final Sma20PullbackContinuationStrategyProvider provider = new Sma20PullbackContinuationStrategyProvider();

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(Sma20PullbackContinuationStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.displayName()).isEqualTo("SMA 20 Pullback Continuation");
        assertThat(descriptor.supportedTimeframes()).containsExactly("M15", "H1");
        assertThat(descriptor.parameterSchema().parameters()).hasSize(11);
        assertThat(descriptor.suggestedChartStudies()).hasSize(2);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().integer("fastSmaPeriod", 0)).isEqualTo(20);
        assertThat(validation.effectiveParameters().integer("slowSmaPeriod", 0)).isEqualTo(200);
        assertThat(validation.effectiveParameters().decimal("minConfidence", BigDecimal.ZERO)).isEqualByComparingTo("0.62");
        assertThat(validation.effectiveParameters().bool("allowShorts", false)).isTrue();
    }

    @Test
    void rejectsInvalidParameters() {
        var crossFieldValidation = provider.validate(new StrategyParameters(Map.of(
                "fastSmaPeriod", 50,
                "slowSmaPeriod", 50
        )));
        var rangeValidation = provider.validate(new StrategyParameters(Map.of(
                "slopeLookbackBars", 21,
                "touchTolerancePct", "6.0",
                "minConfidence", "0.95"
        )));

        assertThat(crossFieldValidation.valid()).isFalse();
        assertThat(crossFieldValidation.issues()).extracting("field").contains("slowSmaPeriod");
        assertThat(rangeValidation.valid()).isFalse();
        assertThat(rangeValidation.issues()).extracting("field")
                .contains("slopeLookbackBars", "touchTolerancePct", "minConfidence");
    }

    @Test
    void exposesEffectiveChartStudiesFromParameters() {
        var studies = provider.effectiveChartStudies(new StrategyParameters(Map.of(
                "fastSmaPeriod", 30,
                "slowSmaPeriod", 250
        )));

        assertThat(studies).hasSize(2);
        assertThat(studies.get(0).indicatorId()).isEqualTo("sma");
        assertThat(studies.get(0).role()).isEqualTo("pullback-guide");
        assertThat(studies.get(0).parameters()).containsEntry("period", 30);
        assertThat(studies.get(1).role()).isEqualTo("support-resistance-context");
        assertThat(studies.get(1).parameters()).containsEntry("period", 250);
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(Sma20PullbackContinuationStrategyProvider.STRATEGY_ID);
    }

    @Test
    void emitsLongSignalAfterPullbackAndBreakout() {
        var strategy = compactStrategy(true, "0.62", "2.00", true);
        List<BarEvent> history = bars(
                "100.00", "101.00", "102.00", "103.00", "104.00", "105.00", "104.80", "105.80"
        );

        var signal = strategy.onBar(context(history));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.LONG);
        assertThat(signal.get().setupType()).isEqualTo(SetupType.CONTINUATION);
        assertThat(signal.get().confidence().value()).isEqualByComparingTo("0.85");
        assertThat(signal.get().tags().values()).contains(
                "strategy_family=moving_average_pullback",
                "setup=pullback_continuation",
                "fast_sma_period=3",
                "slow_sma_period=8"
        );
    }

    @Test
    void emitsShortSignalAfterRetraceAndBreakdown() {
        var strategy = compactStrategy(true, "0.62", "2.00", true);
        List<BarEvent> history = bars(
                "110.00", "109.00", "108.00", "107.00", "106.00", "105.00", "105.20", "104.20"
        );

        var signal = strategy.onBar(context(history));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.SHORT);
        assertThat(signal.get().confidence().value()).isEqualByComparingTo("0.85");
    }

    @Test
    void suppressesWarmupFlatOverextendedAndDuplicateSignals() {
        var strategy = compactStrategy(true, "0.62", "0.20", true);
        assertThat(strategy.onBar(context(bars("100.00", "101.00", "102.00")))).isEmpty();
        assertThat(strategy.onBar(context(bars("100.00", "100.01", "100.00", "100.01", "100.00", "100.01", "100.00", "100.01")))).isEmpty();
        assertThat(strategy.onBar(context(bars("100.00", "101.00", "102.00", "103.00", "104.00", "105.00", "104.80", "108.00")))).isEmpty();

        var duplicateGuard = compactStrategy(true, "0.62", "2.00", true);
        List<BarEvent> signalHistory = bars("100.00", "101.00", "102.00", "103.00", "104.00", "105.00", "104.80", "105.80");
        assertThat(duplicateGuard.onBar(context(signalHistory))).isPresent();
        assertThat(duplicateGuard.onBar(context(signalHistory))).isEmpty();
        assertThat(duplicateGuard.onBar(context(signalHistory))).isEmpty();
    }

    @Test
    void skipsWhenSma200ObstacleKeepsConfidenceBelowThreshold() {
        var strategy = compactStrategy(true, "0.75", "2.00", true);
        List<BarEvent> history = bars(
                "110.00", "110.00", "99.00", "100.00", "100.50", "101.00", "100.80", "101.40"
        );

        assertThat(strategy.onBar(context(history))).isEqualTo(Optional.empty());
    }

    private static Sma20PullbackContinuationStrategy compactStrategy(boolean allowShorts, String minConfidence, String maxExtensionPct, boolean useSma200ObstacleFilter) {
        return new Sma20PullbackContinuationStrategy(new Sma20PullbackParameters(
                3,
                8,
                3,
                new BigDecimal("0.05"),
                new BigDecimal("0.50"),
                new BigDecimal(maxExtensionPct),
                3,
                1,
                new BigDecimal(minConfidence),
                allowShorts,
                useSma200ObstacleFilter
        ));
    }

    private static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.get(history.size() - 1), history);
    }

    private static List<BarEvent> bars(String... closes) {
        return java.util.stream.IntStream.range(0, closes.length)
                .mapToObj(index -> bar(index + 1, closes[index]))
                .toList();
    }

    private static BarEvent bar(int index, String closeValue) {
        BigDecimal close = new BigDecimal(closeValue);
        BigDecimal open = index == 1 ? close : close.subtract(new BigDecimal("0.20"));
        if (index == 8 && close.compareTo(new BigDecimal("105.00")) < 0) {
            open = close.add(new BigDecimal("0.20"));
        }
        BigDecimal high = close.add(new BigDecimal("0.20"));
        BigDecimal low = close.subtract(new BigDecimal("0.30"));
        return new BarEvent(
                "1.0.0",
                new EventId("bar-%03d".formatted(index)),
                INSTRUMENT,
                Instant.parse("2026-04-11T09:15:00Z").plusSeconds((long) (index - 1) * 3600L),
                "H1",
                new OHLCV(open, high, low, close, BigDecimal.valueOf(1000L + index)),
                new SourceRef(SourceType.ADAPTER, "market-data-replay"),
                null,
                null,
                null
        );
    }
}
