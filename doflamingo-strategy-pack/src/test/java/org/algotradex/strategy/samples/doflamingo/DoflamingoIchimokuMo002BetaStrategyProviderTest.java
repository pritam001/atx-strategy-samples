package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoIchimokuMo002BetaStrategyProviderTest {
    private final DoflamingoIchimokuMo002BetaStrategyProvider provider = new DoflamingoIchimokuMo002BetaStrategyProvider();

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.providerId()).isEqualTo(DoflamingoIchimokuMo002BetaStrategyProvider.PROVIDER_ID);
        assertThat(descriptor.supportedTimeframes()).containsExactly("M15", "H1");
        assertThat(descriptor.supportedAssetClasses()).containsExactly("EQUITY", "INDEX", "CRYPTO");
        assertThat(descriptor.parameterSchema().parameters()).hasSize(2);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("confidence", BigDecimal.ZERO)).isEqualByComparingTo("0.7");
        assertThat(validation.effectiveParameters().integer("trendAverageLookback", 0)).isEqualTo(10);
    }

    @Test
    void rejectsOutOfRangeParameters() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "confidence", "1.50",
                "trendAverageLookback", 1
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("confidence", "trendAverageLookback");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_ID);
    }

    @Test
    void emitsOneLongSignalWhenBetaSetupForms() {
        var strategy = provider.create(new StrategyParameters(Map.of("trendAverageLookback", 50)), null);
        List<org.algotradex.platform.contracts.market.BarEvent> bars = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        Optional<org.algotradex.platform.contracts.intelligence.TradeSignal> signal = Optional.empty();
        int signalIndex = -1;
        for (int index = 1; index <= bars.size(); index++) {
            signal = strategy.onBar(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (signal.isPresent()) {
                signalIndex = index;
                break;
            }
        }

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.LONG);
        assertThat(signal.get().setupType()).isEqualTo(SetupType.CONTINUATION);
        assertThat(signal.get().suggestedParams()).isNull();

        var repeated = strategy.onBar(DoflamingoStrategyTestSupport.context(bars.subList(0, signalIndex)));
        assertThat(repeated).isEmpty();
    }

    @Test
    void resetsLongSetupWithoutEmittingShortSignal() {
        var strategy = provider.create(new StrategyParameters(Map.of("trendAverageLookback", 50)), null);
        List<org.algotradex.platform.contracts.market.BarEvent> bars = new ArrayList<>(DoflamingoStrategyTestSupport.ichimokuBetaSetupBars());
        Optional<org.algotradex.platform.contracts.intelligence.TradeSignal> signal = Optional.empty();
        for (int index = 1; index <= bars.size(); index++) {
            signal = strategy.onBar(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (signal.isPresent()) {
                break;
            }
        }
        assertThat(signal).isPresent();

        bars.add(DoflamingoStrategyTestSupport.nextBarAfter(bars, 80.0d, 81.0d, 79.0d, 80.0d));
        var reset = strategy.onBar(DoflamingoStrategyTestSupport.context(bars));

        assertThat(reset).isEmpty();
    }
}
