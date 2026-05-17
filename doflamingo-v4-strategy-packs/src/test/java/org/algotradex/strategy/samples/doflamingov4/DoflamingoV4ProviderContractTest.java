package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoV4ProviderContractTest {
    private final DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider trendProvider =
            new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
    private final DoflamingoIchimokuMo002BetaV4StrategyProvider ichimokuProvider =
            new DoflamingoIchimokuMo002BetaV4StrategyProvider();

    @Test
    void trendV4DescriptorUsesNewIdentityDefaultsAndNoAdaptiveEscapeHatch() {
        var descriptor = trendProvider.descriptor();
        var validation = trendProvider.validate(StrategyParameters.empty());
        var effective = validation.effectiveParameters();

        assertThat(descriptor.identity().strategyId()).isEqualTo("doflamingo-multi-indicator-v6-trend-reversal-v4");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("4.0.0");
        assertThat(descriptor.providerId()).isEqualTo("doflamingo-v4-strategy-packs");
        assertThat(descriptor.displayName()).contains("V4");
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PORTFOLIO_AWARE,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(parameterNames(descriptor.parameterSchema().parameters()))
                .contains(
                        "cooldownBars",
                        "structureExitConfirmBars",
                        "volumeConfirmMultiple",
                        "psarMinDistanceLongPct",
                        "requireRsiExtremeWithinBars",
                        "targetRMultiple",
                        "sessionGating",
                        "maxPortfolioDrawdownPct"
                )
                .doesNotContain("adaptiveMomentumMode", "earningsCalendarRef", "maxConsecutiveLosses");

        assertThat(validation.valid()).isTrue();
        assertThat(effective.decimal("minConfidence", BigDecimal.ZERO)).isEqualByComparingTo("0.62");
        assertThat(effective.string("trendFilterMode", "")).isEqualTo("STRICT");
        assertThat(effective.string("stopMode", "")).isEqualTo("ATR");
        assertThat(effective.decimal("stopLossPct", BigDecimal.ZERO)).isEqualByComparingTo("1.50");
        assertThat(effective.decimal("minStopPct", BigDecimal.ZERO)).isEqualByComparingTo("0.60");
        assertThat(effective.decimal("maxStopPct", BigDecimal.ZERO)).isEqualByComparingTo("2.00");
        assertThat(effective.integer("maxHoldingBars", 0)).isEqualTo(32);
        assertThat(effective.integer("staleBars", 0)).isEqualTo(12);
        assertThat(effective.decimal("staleMinR", BigDecimal.ZERO)).isEqualByComparingTo("0.40");
        assertThat(effective.integer("cooldownBars", -1)).isEqualTo(4);
        assertThat(effective.decimal("scaleOutAtR", BigDecimal.ZERO)).isEqualByComparingTo("1.25");
        assertThat(effective.decimal("scaleOutFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.40");
        assertThat(effective.decimal("riskFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.0075");
        assertThat(effective.decimal("targetRMultiple", BigDecimal.ZERO)).isEqualByComparingTo("2.50");
        assertThat(effective.bool("sessionGating", false)).isTrue();
        assertThat(effective.stringList("skipMarketRegimes", List.of()))
                .containsExactly("STRONG_TREND_HIGH_VOLATILITY", "RANGING_HIGH_VOLATILITY");
    }

    @Test
    void ichimokuV4DescriptorDeclaresH1ContextAndCloudQualityGates() {
        var descriptor = ichimokuProvider.descriptor();
        var validation = ichimokuProvider.validate(StrategyParameters.empty());
        var effective = validation.effectiveParameters();

        assertThat(descriptor.identity().strategyId()).isEqualTo("doflamingo-ichimoku-mo-002-beta-v4");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("4.0.0");
        assertThat(descriptor.providerId()).isEqualTo("doflamingo-v4-strategy-packs");
        assertThat(descriptor.requiredContextTimeframes()).containsExactly("H1");
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(parameterNames(descriptor.parameterSchema().parameters()))
                .contains(
                        "minKumoThicknessAtr",
                        "minFutureCloudSpreadAtr",
                        "requireFutureCloudWidening",
                        "requireChikouClearSpace",
                        "tkCrossFreshBars",
                        "maxEntryAtrFromCloudTop",
                        "htfCloudBiasMode",
                        "targetRMultiple",
                        "sessionGating"
                )
                .doesNotContain("earningsCalendarRef");

        assertThat(validation.valid()).isTrue();
        assertThat(effective.string("htfCloudBiasMode", "")).isEqualTo("ALIGN_WITH_TRADE");
        assertThat(effective.decimal("minKumoThicknessAtr", BigDecimal.ZERO)).isEqualByComparingTo("0.25");
        assertThat(effective.decimal("maxEntryAtrFromCloudTop", BigDecimal.ZERO)).isEqualByComparingTo("2.50");
        assertThat(effective.decimal("targetRMultiple", BigDecimal.ZERO)).isEqualByComparingTo("2.50");
        assertThat(effective.bool("sessionGating", false)).isTrue();
        assertThat(effective.stringList("skipMarketRegimes", List.of()))
                .containsExactly("STRONG_TREND_HIGH_VOLATILITY", "RANGING_HIGH_VOLATILITY");
    }

    @Test
    void v4ProvidersAreRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers)
                .contains(
                        DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider.STRATEGY_ID,
                        DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_ID
                );
    }

    private static List<String> parameterNames(List<StrategyParameterDefinition> definitions) {
        return definitions.stream().map(StrategyParameterDefinition::key).toList();
    }
}
