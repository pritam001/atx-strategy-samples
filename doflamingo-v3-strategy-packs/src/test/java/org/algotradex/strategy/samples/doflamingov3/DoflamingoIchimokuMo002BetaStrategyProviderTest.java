package org.algotradex.strategy.samples.doflamingov3;

import org.algotradex.platform.contracts.common.enums.StrategyEntryType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoIchimokuMo002BetaStrategyProviderTest {
    private final DoflamingoIchimokuMo002BetaStrategyProvider provider = new DoflamingoIchimokuMo002BetaStrategyProvider();

    @Test
    void exposesDescriptorCapabilitiesAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.identity().strategyVersion()).isEqualTo(DoflamingoIchimokuMo002BetaStrategyProvider.STRATEGY_VERSION);
        assertThat(descriptor.providerId()).isEqualTo(DoflamingoIchimokuMo002BetaStrategyProvider.PROVIDER_ID);
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(descriptor.parameterSchema().parameters()).hasSize(18);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().string("entryMode", "")).isEqualTo("HYBRID");
        assertThat(validation.effectiveParameters().decimal("minConfidence", BigDecimal.ZERO)).isEqualByComparingTo("0.60");
        assertThat(validation.effectiveParameters().integer("maxHoldingBars", 0)).isEqualTo(96);
        assertThat(validation.effectiveParameters().string("stopMode", "")).isEqualTo("CLOUD_OR_ATR");
        assertThat(validation.effectiveParameters().stringList("skipMarketRegimes", List.of("fallback"))).isEmpty();
        assertThat(validation.effectiveParameters().bool("allowShorts", false)).isTrue();
        assertThat(validation.effectiveParameters().string("shortCloudPriceMode", "")).isEqualTo("HIGH_BELOW_CLOUD");
        assertThat(validation.effectiveParameters().string("shortEmaCloudMode", "")).isEqualTo("EMA9_BELOW_SPAN_B");
    }

    @Test
    void rejectsInvalidEnumsAndOutOfRangeParameters() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "entryMode", "FAST",
                "minConfidence", "1.50",
                "trendAverageLookback", 1,
                "riskFraction", "0.05",
                "stopMode", "TRAILING",
                "shortCloudPriceMode", "LOW_BELOW_CLOUD",
                "shortEmaCloudMode", "EMA9_ABOVE_CLOUD",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY", "UNKNOWN_REGIME")
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field")
                .contains("entryMode", "minConfidence", "trendAverageLookback", "riskFraction", "stopMode",
                        "shortCloudPriceMode", "shortEmaCloudMode", "skipMarketRegimes");
    }

    @Test
    void rejectsInvalidShortStopRelationship() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "minStopPct", "3.0",
                "maxStopPct", "2.0"
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("minStopPct");
    }

    @Test
    void acceptsMarketRegimeSkipListParameter() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "skipMarketRegimes",
                List.of("RANGING_LOW_VOLATILITY", "STRONG_TREND_MEDIUM_VOLATILITY")
        )));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().stringList("skipMarketRegimes", List.of()))
                .containsExactly("RANGING_LOW_VOLATILITY", "STRONG_TREND_MEDIUM_VOLATILITY");
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
    void entryIntentCarriesExecutionSizingHorizonAndConditionEvidence() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        var result = firstIntent(strategy, bars);

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(96);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.sizing().riskFraction()).isEqualByComparingTo("0.0100");
        assertThat(intent.sourceBarId()).isNotBlank();
        assertThat(intent.reason().tags()).contains("doflamingo", "v3", "adaptive", "ichimoku", "entry", "confidence");
        assertThat(intent.reason().evidence()).contains("marketRegime=INSUFFICIENT_DATA", "skipMarketRegimes=[]");
        assertThat(intent.reason().conditions()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("ichimoku-v3.market-regime-allowed");
        assertThat(intent.reason().conditions())
                .allSatisfy(condition -> {
                    assertThat(condition.conditionId()).isNotBlank();
                    assertThat(condition.label()).isNotBlank();
                    assertThat(condition.operator()).isNotBlank();
                    assertThat(condition.message()).isNotBlank();
                });
        assertThat(intent.confidence().value()).isGreaterThanOrEqualTo(new BigDecimal("0.50"));
    }

    @Test
    void hybridCanEnterNoLaterThanStrictBetaOnSameFixture() {
        TradeIntentStrategy strict = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "entryMode", "STRICT_BETA",
                "trendAverageLookback", 50,
                "minConfidence", "0.50"
        )), null);
        TradeIntentStrategy hybrid = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "entryMode", "HYBRID",
                "trendAverageLookback", 50,
                "minConfidence", "0.50"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        int strictIndex = firstIntentIndex(strict, bars);
        int hybridIndex = firstIntentIndex(hybrid, bars);

        assertThat(strictIndex).isGreaterThan(0);
        assertThat(hybridIndex).isGreaterThan(0);
        assertThat(hybridIndex).isLessThanOrEqualTo(strictIndex);
    }

    @Test
    void configuredMarketRegimeSuppressesFlatEntryOnly() {
        TradeIntentStrategy blocked = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        TradeIntentStrategy allowed = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        assertThat(firstIntent(blocked, bars, PrimaryMarketRegime.RANGING_LOW_VOLATILITY)).isNull();
        assertThat(firstIntent(allowed, bars, PrimaryMarketRegime.STRONG_TREND_MEDIUM_VOLATILITY)).isNotNull();
    }

    @Test
    void bearishSetupEmitsShortEntryIntent() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.ichimokuBetaShortSetupBars();

        var result = firstIntent(strategy, bars);

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_SHORT);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(96);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.reason().tags()).contains("doflamingo", "v3", "adaptive", "ichimoku", "short", "entry");
        assertThat(intent.reason().evidence()).contains("side=SHORT");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "ichimoku-v3.short-price-below-cloud",
                        "ichimoku-v3.short-future-red-cloud",
                        "ichimoku-v3.short-trend-negative",
                        "ichimoku-v3.short-confidence-threshold"
                );
    }

    @Test
    void allowShortsFalseSuppressesBearishEntry() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "allowShorts", false
        )), null);

        assertThat(firstIntent(strategy, DoflamingoStrategyTestSupport.ichimokuBetaShortSetupBars())).isNull();
    }

    @Test
    void shortStructureExitRequiresShortPositionAndUsesFullCloseSizing() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);

        var positionedResult = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                DoflamingoStrategyTestSupport.ichimokuBetaSetupBars(),
                DoflamingoStrategyTestSupport.shortPosition(4, 0.4d, 0),
                PrimaryMarketRegime.RANGING_LOW_VOLATILITY
        ));

        assertThat(positionedResult.tradeIntents()).hasSize(1);
        var intent = positionedResult.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.EXIT_SHORT);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.CLOSE_FRACTION);
        assertThat(intent.sizing().requestedFraction()).isEqualByComparingTo("1.0000");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("ichimoku-v3.short-exit-close-above-cloud");
    }

    @Test
    void structureExitRequiresActualLongPositionAndUsesFullCloseSizing() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        List<BarEvent> bars = new ArrayList<>(DoflamingoStrategyTestSupport.ichimokuBetaSetupBars());
        bars.add(DoflamingoStrategyTestSupport.nextBarAfter(bars, 80.0d, 81.0d, 79.0d, 80.0d));

        var flatResult = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars));
        assertThat(flatResult.tradeIntents()).isEmpty();

        var positionedResult = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(4, 0.4d, 0),
                PrimaryMarketRegime.RANGING_LOW_VOLATILITY
        ));

        assertThat(positionedResult.tradeIntents()).hasSize(1);
        var intent = positionedResult.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.CLOSE_FRACTION);
        assertThat(intent.sizing().requestedFraction()).isEqualByComparingTo("1.0000");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("ichimoku-v3.exit-span-a-over-high");
    }

    @Test
    void cloudStopUsesLowerOfPresentSpanBAndBaseLine() {
        var snapshot = new DoflamingoIndicatorMath.IchimokuSnapshot(
                101.0d,
                95.0d,
                100.5d,
                100.0d,
                102.0d,
                99.0d
        );

        BigDecimal stopPct = DoflamingoIchimokuMo002BetaStrategy.cloudStopPercent(
                110.0d,
                snapshot,
                BigDecimal.valueOf(0.25)
        );

        assertThat(stopPct).isEqualByComparingTo("13.8523");
    }

    @Test
    void shortCloudStopUsesUpperCloudResistanceAndBaseLine() {
        var snapshot = new DoflamingoIndicatorMath.IchimokuSnapshot(
                101.0d,
                105.0d,
                100.5d,
                100.0d,
                99.0d,
                102.0d
        );

        BigDecimal stopPct = DoflamingoIchimokuMo002BetaStrategy.shortCloudStopPercent(
                95.0d,
                snapshot,
                BigDecimal.valueOf(0.25)
        );

        assertThat(stopPct).isEqualByComparingTo("10.8026");
    }

    private static org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult firstIntent(
            TradeIntentStrategy strategy,
            List<BarEvent> bars
    ) {
        for (int index = 1; index <= bars.size(); index++) {
            var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (!result.tradeIntents().isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult firstIntent(
            TradeIntentStrategy strategy,
            List<BarEvent> bars,
            PrimaryMarketRegime primaryRegime
    ) {
        for (int index = 1; index <= bars.size(); index++) {
            var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars.subList(0, index), primaryRegime));
            if (!result.tradeIntents().isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static int firstIntentIndex(TradeIntentStrategy strategy, List<BarEvent> bars) {
        for (int index = 1; index <= bars.size(); index++) {
            var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (!result.tradeIntents().isEmpty()) {
                return index;
            }
        }
        return -1;
    }
}
