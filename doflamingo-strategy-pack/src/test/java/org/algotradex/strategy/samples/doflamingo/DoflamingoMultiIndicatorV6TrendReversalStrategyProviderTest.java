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

class DoflamingoMultiIndicatorV6TrendReversalStrategyProviderTest {
    private final DoflamingoMultiIndicatorV6TrendReversalStrategyProvider provider =
            new DoflamingoMultiIndicatorV6TrendReversalStrategyProvider();

    @Test
    void exposesDescriptorAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.providerId()).isEqualTo(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.PROVIDER_ID);
        assertThat(descriptor.parameterSchema().parameters()).hasSize(7);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().integer("macdFastPeriod", 0)).isEqualTo(12);
        assertThat(validation.effectiveParameters().integer("macdSlowPeriod", 0)).isEqualTo(26);
        assertThat(validation.effectiveParameters().integer("macdSignalPeriod", 0)).isEqualTo(9);
        assertThat(validation.effectiveParameters().decimal("stopLossPct", BigDecimal.ZERO)).isEqualByComparingTo("2.0");
    }

    @Test
    void rejectsInvalidMacdAndStochRelationships() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "macdFastPeriod", 12,
                "macdSlowPeriod", 12,
                "stochOversold", 85,
                "stochOverbought", 80
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("macdSlowPeriod", "stochOversold");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_ID);
    }

    @Test
    void emitsOneLongSignalWhenV6EntryConditionForms() {
        var strategy = provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "confidence", "0.73"
        )), null);
        List<org.algotradex.platform.contracts.market.BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

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
        assertThat(signal.get().setupType()).isEqualTo(SetupType.REVERSAL);
        assertThat(signal.get().confidence().value()).isEqualByComparingTo("0.73");
        assertThat(signal.get().suggestedParams()).isNull();

        var repeated = strategy.onBar(DoflamingoStrategyTestSupport.context(bars.subList(0, signalIndex)));
        assertThat(repeated).isEmpty();
    }

    @Test
    void stopLossResetDoesNotEmitShortSignal() {
        var strategy = provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "stopLossPct", "2.0"
        )), null);
        List<org.algotradex.platform.contracts.market.BarEvent> bars = new ArrayList<>(DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars());
        Optional<org.algotradex.platform.contracts.intelligence.TradeSignal> signal = Optional.empty();
        for (int index = 1; index <= bars.size(); index++) {
            signal = strategy.onBar(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (signal.isPresent()) {
                break;
            }
        }
        assertThat(signal).isPresent();

        double close = 100.0d;
        bars.add(DoflamingoStrategyTestSupport.nextBarAfter(bars, close, close + 1.0d, 10.0d, close));
        var reset = strategy.onBar(DoflamingoStrategyTestSupport.context(bars));

        assertThat(reset).isEmpty();
    }
}
