package org.algotradex.strategy.samples.ema;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EmaCrossoverStrategyProviderTest {
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
}
