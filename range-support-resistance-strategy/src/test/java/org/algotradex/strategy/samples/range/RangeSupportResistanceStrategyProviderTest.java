package org.algotradex.strategy.samples.range;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.OrderType;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RangeSupportResistanceStrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("ASIANPAINT-EQ", "ASIANPAINT", "NSE", AssetClass.EQUITY, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-001"),
            new ReplayId("replay-001"),
            ReplayMode.FULL_RUN
    );

    private final RangeSupportResistanceStrategyProvider provider = new RangeSupportResistanceStrategyProvider();

    private static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.get(history.size() - 1), history);
    }

    private static BarEvent bar(String eventId, String occurredAt, String open, String high, String low, String close) {
        return new BarEvent(
                "1.0.0",
                new EventId(eventId),
                INSTRUMENT,
                Instant.parse(occurredAt),
                "H1",
                new OHLCV(
                        new BigDecimal(open),
                        new BigDecimal(high),
                        new BigDecimal(low),
                        new BigDecimal(close),
                        BigDecimal.valueOf(1000)
                ),
                new SourceRef(SourceType.ADAPTER, "market-data-replay"),
                null,
                null,
                null
        );
    }

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(RangeSupportResistanceStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.supportedTimeframes()).containsExactly("M15", "H1", "D1");
        assertThat(descriptor.parameterSchema().parameters()).hasSize(4);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().integer("lookback", 0)).isEqualTo(30);
        assertThat(validation.effectiveParameters().decimal("tolerance", BigDecimal.ZERO)).isEqualByComparingTo("0.002");
        assertThat(validation.effectiveParameters().decimal("riskReward", BigDecimal.ZERO)).isEqualByComparingTo("2.0");
        assertThat(validation.effectiveParameters().decimal("confidence", BigDecimal.ZERO)).isEqualByComparingTo("0.7");
    }

    @Test
    void rejectsOutOfRangeParameters() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "lookback", 1,
                "tolerance", "0.10",
                "riskReward", "0.01",
                "confidence", "1.10"
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field")
                .contains("lookback", "tolerance", "riskReward", "confidence");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(RangeSupportResistanceStrategyProvider.STRATEGY_ID);
    }

    @Test
    void emitsLongSignalNearSupportAfterBullishConfirmation() {
        var strategy = provider.create(new StrategyParameters(Map.of(
                "lookback", 3,
                "tolerance", "0.01",
                "riskReward", "2.0",
                "confidence", "0.72"
        )), null);
        List<BarEvent> history = List.of(
                bar("bar-001", "2026-04-11T09:15:00Z", "100.00", "101.00", "99.50", "100.30"),
                bar("bar-002", "2026-04-11T10:15:00Z", "100.20", "100.90", "100.00", "100.40"),
                bar("bar-003", "2026-04-11T11:15:00Z", "100.40", "100.80", "100.20", "100.30"),
                bar("bar-004", "2026-04-11T12:15:00Z", "100.10", "100.40", "100.10", "100.20"),
                bar("bar-005", "2026-04-11T13:15:00Z", "100.00", "101.00", "99.80", "100.50")
        );

        var signal = strategy.onBar(context(history));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.LONG);
        assertThat(signal.get().setupType()).isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(signal.get().occurredAt()).isEqualTo(Instant.parse("2026-04-11T13:15:00Z"));
        assertThat(signal.get().confidence().value()).isEqualByComparingTo("0.72");
        assertThat(signal.get().suggestedParams().entry()).isEqualByComparingTo("100.50");
        assertThat(signal.get().suggestedParams().stop()).isEqualByComparingTo("100.00");
        assertThat(signal.get().suggestedParams().target()).isEqualByComparingTo("101.50");
        assertThat(signal.get().suggestedParams().orderType()).isEqualTo(OrderType.MARKET);
    }

    @Test
    void emitsShortSignalNearResistanceAfterBearishConfirmation() {
        var strategy = provider.create(new StrategyParameters(Map.of(
                "lookback", 3,
                "tolerance", "0.01",
                "riskReward", "2.0"
        )), null);
        List<BarEvent> history = List.of(
                bar("bar-001", "2026-04-11T09:15:00Z", "110.00", "111.00", "109.00", "110.40"),
                bar("bar-002", "2026-04-11T10:15:00Z", "110.40", "110.50", "109.80", "110.30"),
                bar("bar-003", "2026-04-11T11:15:00Z", "110.30", "110.40", "109.70", "110.10"),
                bar("bar-004", "2026-04-11T12:15:00Z", "110.10", "110.30", "110.10", "110.20"),
                bar("bar-005", "2026-04-11T13:15:00Z", "110.80", "111.00", "110.00", "110.00")
        );

        var signal = strategy.onBar(context(history));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.SHORT);
        assertThat(signal.get().suggestedParams().entry()).isEqualByComparingTo("110.00");
        assertThat(signal.get().suggestedParams().stop()).isEqualByComparingTo("110.50");
        assertThat(signal.get().suggestedParams().target()).isEqualByComparingTo("109.00");
    }

    @Test
    void staysFlatBeforeEnoughHistoryOrWithoutConfirmation() {
        var strategy = provider.create(new StrategyParameters(Map.of(
                "lookback", 3,
                "tolerance", "0.01"
        )), null);
        List<BarEvent> shortHistory = List.of(
                bar("bar-001", "2026-04-11T09:15:00Z", "100", "106", "99", "104"),
                bar("bar-002", "2026-04-11T10:15:00Z", "104", "105", "100", "103"),
                bar("bar-003", "2026-04-11T11:15:00Z", "103", "104", "101", "102"),
                bar("bar-004", "2026-04-11T12:15:00Z", "101", "106", "99.50", "105")
        );
        List<BarEvent> noConfirmation = new ArrayList<>(shortHistory);
        noConfirmation.add(bar("bar-005", "2026-04-11T13:15:00Z", "105", "106", "99.80", "100"));

        assertThat(strategy.onBar(context(shortHistory))).isEmpty();
        assertThat(strategy.onBar(context(noConfirmation))).isEqualTo(Optional.empty());
    }
}
