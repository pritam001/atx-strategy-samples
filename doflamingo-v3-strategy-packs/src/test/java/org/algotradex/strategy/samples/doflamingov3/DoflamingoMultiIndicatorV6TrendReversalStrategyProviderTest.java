package org.algotradex.strategy.samples.doflamingov3;

import org.algotradex.platform.contracts.common.enums.StrategyEntryType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.strategy.simulation.SimulationStepper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoMultiIndicatorV6TrendReversalStrategyProviderTest {
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-doflamingo-v3-stepper-parity-test"),
            new ReplayId("replay-doflamingo-v3-stepper-parity-test"),
            ReplayMode.FULL_RUN
    );

    private final DoflamingoMultiIndicatorV6TrendReversalStrategyProvider provider =
            new DoflamingoMultiIndicatorV6TrendReversalStrategyProvider();

    @Test
    void exposesDescriptorCapabilitiesAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_ID);
        assertThat(descriptor.identity().strategyVersion()).isEqualTo(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION);
        assertThat(descriptor.providerId()).isEqualTo(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.PROVIDER_ID);
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_IN_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.REVERSAL_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(descriptor.parameterSchema().parameters()).hasSize(29);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("minConfidence", BigDecimal.ZERO)).isEqualByComparingTo("0.60");
        assertThat(validation.effectiveParameters().integer("macdFastPeriod", 0)).isEqualTo(16);
        assertThat(validation.effectiveParameters().integer("macdSlowPeriod", 0)).isEqualTo(36);
        assertThat(validation.effectiveParameters().string("trendFilterMode", "")).isEqualTo("SOFT");
        assertThat(validation.effectiveParameters().string("adaptiveMomentumMode", "")).isEqualTo("STRICT_REVERSAL");
        assertThat(validation.effectiveParameters().string("stopMode", "")).isEqualTo("ATR_OR_PERCENT_MAX");
        assertThat(validation.effectiveParameters().decimal("scaleOutFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.50");
        assertThat(validation.effectiveParameters().bool("trailAfterScaleOut", false)).isTrue();
        assertThat(validation.effectiveParameters().stringList("skipMarketRegimes", List.of("fallback"))).isEmpty();
        assertThat(validation.effectiveParameters().bool("allowShorts", false)).isTrue();
        assertThat(validation.effectiveParameters().string("shortCloudMode", "")).isEqualTo("CLOSE_BELOW_CLOUD");
        assertThat(validation.effectiveParameters().bool("allowReversal", true)).isFalse();
        assertThat(validation.effectiveParameters().bool("allowShortScaleIn", true)).isFalse();
        assertThat(validation.effectiveParameters().decimal("shortScaleInAtR", BigDecimal.ZERO)).isEqualByComparingTo("0.50");
        assertThat(validation.effectiveParameters().integer("maxShortScaleIns", 0)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidMacdStochAndStopRelationships() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "macdFastPeriod", 12,
                "macdSlowPeriod", 12,
                "stochOversold", 85,
                "stochOverbought", 80,
                "minStopPct", "3.0",
                "maxStopPct", "2.0"
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field")
                .contains("macdSlowPeriod", "stochOversold", "minStopPct");
    }

    @Test
    void rejectsInvalidEnumParameters() {
        var validation = provider.validate(new StrategyParameters(Map.of(
                "trendFilterMode", "HARD",
                "adaptiveMomentumMode", "ALWAYS",
                "stopMode", "MANUAL",
                "skipMarketRegimes", List.of("UNKNOWN_REGIME")
        )));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("trendFilterMode", "adaptiveMomentumMode", "stopMode", "skipMarketRegimes");
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

        assertThat(providers).contains(DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_ID);
    }

    @Test
    void softTrendFilterAllowsConfirmedReclaimEntryIntent() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "SOFT",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        var result = firstIntent(strategy, bars);

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.sizing().riskFraction()).isEqualByComparingTo("0.0100");
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(64);
        assertThat(intent.horizon().intendedHorizonLabel()).isEqualTo(IntendedHorizonLabel.SWING);
        assertThat(intent.exit().stop().type()).isEqualTo(StrategyExitRuleType.PERCENT);
        assertThat(intent.reason().tags()).contains("doflamingo", "v3", "multi-v6", "entry", "confidence");
        assertThat(intent.reason().evidence()).contains("marketRegime=INSUFFICIENT_DATA", "skipMarketRegimes=[]");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "multi-v6-v3.psar-direction-up",
                        "multi-v6-v3.adaptive-momentum-confirmed",
                        "multi-v6-v3.trend-filter",
                        "multi-v6-v3.market-regime-allowed",
                        "multi-v6-v3.confidence-threshold"
                );
        assertThat(intent.reason().evidence()).contains("adaptiveMomentumMode=ADAPTIVE_CONFIRMATION");
    }

    @Test
    void simulationStepperEmissionsMatchDirectReplayGoldenActions() {
        StrategyParameters parameters = new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "SOFT",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION"
        ));
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        List<String> direct = replayDirectActions(parameters, bars);
        List<String> stepped = replayStepperActions(parameters, bars);

        assertThat(direct).startsWith("ENTER_LONG");
        assertThat(stepped).containsExactly("ENTER_LONG", "SCALE_OUT_LONG");
    }

    @Test
    void strictTrendFilterSuppressesWeakShortHistoryReversal() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "STRICT"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        assertThat(firstIntent(strategy, bars)).isNull();
    }

    @Test
    void strictReversalModeSuppressesAdaptiveOnlyMomentumEntry() {
        TradeIntentStrategy strictReversal = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "NONE"
        )), null);
        TradeIntentStrategy adaptive = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "maxStopPct", "20.0",
                "trendFilterMode", "NONE",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        assertThat(firstIntent(strictReversal, bars)).isNull();
        var adaptiveResult = firstIntent(adaptive, bars);

        assertThat(adaptiveResult).isNotNull();
        var adaptiveIntent = adaptiveResult.tradeIntents().getFirst();
        assertThat(adaptiveIntent.reason().conditions())
                .filteredOn(condition -> condition.conditionId().equals("multi-v6-v3.adaptive-momentum-confirmed"))
                .singleElement()
                .satisfies(condition -> assertThat(condition.passed()).isTrue());
    }

    @Test
    void configuredMarketRegimeSuppressesFlatEntryOnly() {
        TradeIntentStrategy blocked = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "SOFT",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        TradeIntentStrategy allowed = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "SOFT",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        assertThat(firstIntent(blocked, bars, PrimaryMarketRegime.RANGING_LOW_VOLATILITY)).isNull();
        assertThat(firstIntent(allowed, bars, PrimaryMarketRegime.STRONG_TREND_MEDIUM_VOLATILITY)).isNotNull();
    }

    @Test
    void bearishSetupEmitsShortEntryIntent() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "NONE",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6ShortSetupBars().subList(0, 82);

        var result = firstIntent(strategy, bars);

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_SHORT);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.reason().tags()).contains("doflamingo", "v3", "multi-v6", "entry", "short", "confidence");
        assertThat(intent.reason().evidence()).contains("side=SHORT", "setup=multi-v6-short-reversal");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "multi-v6-v3.short.psar-down",
                        "multi-v6-v3.short.psar-distance",
                        "multi-v6-v3.short.cloud-confirmation",
                        "multi-v6-v3.short.clean-structure",
                        "multi-v6-v3.short.overextension",
                        "multi-v6-v3.short.raw-stop-pct",
                        "multi-v6-v3.short.trend-filter",
                        "multi-v6-v3.short.confidence-threshold"
                );
        assertThat(intent.reason().evidence()).anySatisfy(value -> assertThat(value).startsWith("presentSpanA="));
        assertThat(intent.reason().evidence()).anySatisfy(value -> assertThat(value).startsWith("cloudFloor="));
        assertThat(intent.reason().evidence()).anySatisfy(value -> assertThat(value).startsWith("rawStopPct="));
        assertThat(intent.reason().evidence()).anySatisfy(value -> assertThat(value).startsWith("selectedStopPct="));
    }

    @Test
    void shortScaleInRequiresExplicitEnablementAndRespectsMaxCount() {
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6ShortSetupBars().subList(0, 82);
        TradeIntentStrategy disabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false
        )), null);
        var disabledResult = disabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.shortPosition(6, 0.80d, 0)
        ));

        assertThat(disabledResult.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.SCALE_IN_SHORT);

        TradeIntentStrategy enabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "allowShortScaleIn", true,
                "shortScaleInAtR", "0.50",
                "maxShortScaleIns", 1
        )), null);
        var scaleIn = enabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.shortPosition(6, 0.80d, 0)
        ));
        var maxReached = enabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.shortPosition(6, 0.80d, 1, 0, 3.0d, 1.0d)
        ));

        assertThat(scaleIn.tradeIntents()).hasSize(1);
        assertThat(scaleIn.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.SCALE_IN_SHORT);
        assertThat(scaleIn.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("multi-v6-v3.short.scale-in-enabled", "multi-v6-v3.short.scale-in-clean-structure");
        assertThat(maxReached.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.SCALE_IN_SHORT);
    }

    @Test
    void reversalIntentsRequireExplicitEnablement() {
        List<BarEvent> bars = new java.util.ArrayList<>(DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars());
        bars.add(DoflamingoStrategyTestSupport.nextBarAfter(bars, 155.0d, 160.0d, 145.0d, 150.0d));
        TradeIntentStrategy disabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false
        )), null);
        TradeIntentStrategy enabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "allowReversal", true,
                "maxStopPct", "20.0"
        )), null);

        var disabledResult = disabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.20d, 0)
        ));
        var enabledResult = enabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.20d, 0)
        ));

        assertThat(disabledResult.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.REVERSE_LONG_TO_SHORT);
        assertThat(enabledResult.tradeIntents()).hasSize(1);
        assertThat(enabledResult.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.REVERSE_LONG_TO_SHORT);
    }

    @Test
    void allowShortsFalseSuppressesBearishEntry() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "trendFilterMode", "NONE",
                "adaptiveMomentumMode", "ADAPTIVE_CONFIRMATION",
                "allowShorts", false
        )), null);

        assertThat(firstIntent(strategy, DoflamingoStrategyTestSupport.multiIndicatorV6ShortSetupBars())).isNull();
    }

    @Test
    void shortPositionUsesShortScaleOutAndExitActions() {
        TradeIntentStrategy scaleOutStrategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "scaleOutAtR", "1.0",
                "scaleOutFraction", "0.50"
        )), null);
        var scaleOut = scaleOutStrategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                DoflamingoStrategyTestSupport.multiIndicatorV6ShortSetupBars(),
                DoflamingoStrategyTestSupport.shortPosition(6, 1.2d, 0)
        ));

        assertThat(scaleOut.tradeIntents()).hasSize(1);
        assertThat(scaleOut.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.SCALE_OUT_SHORT);
        assertThat(scaleOut.tradeIntents().getFirst().sizing().type()).isEqualTo(StrategySizingType.SCALE_FRACTION);

        TradeIntentStrategy exitStrategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false
        )), null);
        var exit = exitStrategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars(),
                DoflamingoStrategyTestSupport.shortPosition(6, 0.2d, 0)
        ));

        assertThat(exit.tradeIntents()).hasSize(1);
        assertThat(exit.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_SHORT);
        assertThat(exit.tradeIntents().getFirst().sizing().type()).isEqualTo(StrategySizingType.CLOSE_FRACTION);
    }

    @Test
    void scaleOutIntentUsesScaleFractionAndPositionProjectionPreventsRepeat() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "scaleOutAtR", "1.0",
                "scaleOutFraction", "0.50",
                "skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        var scale = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 1.2d, 0),
                PrimaryMarketRegime.RANGING_LOW_VOLATILITY
        ));

        assertThat(scale.tradeIntents()).hasSize(1);
        var intent = scale.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.SCALE_OUT_LONG);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.SCALE_FRACTION);
        assertThat(intent.sizing().requestedFraction()).isEqualByComparingTo("0.5000");
        assertThat(intent.confidence().value()).isNotEqualByComparingTo("0.7200");

        TradeIntentStrategy strongerScale = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "scaleOutAtR", "1.0",
                "scaleOutFraction", "0.50"
        )), null);
        var stronger = strongerScale.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 3.2d, 0)
        ));
        assertThat(stronger.tradeIntents().getFirst().confidence().value()).isGreaterThan(intent.confidence().value());

        TradeIntentStrategy alreadyScaled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "scaleOutAtR", "1.0"
        )), null);
        var repeat = alreadyScaled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 1.2d, 1)
        ));
        assertThat(repeat.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.SCALE_OUT_LONG);
    }

    @Test
    void staleExitUsesFullCloseSizing() {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "staleBars", 4,
                "staleMinR", "0.25"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.10d, 0)
        ));

        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.CLOSE_FRACTION);
        assertThat(intent.sizing().requestedFraction()).isEqualByComparingTo("1.0000");
        assertThat(intent.confidence().value()).isNotEqualByComparingTo("0.7400");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("multi-v6-v3.exit-stale-bars", "multi-v6-v3.exit-stale-r");
    }

    @Test
    void exitConfidenceReflectsAdverseEvidence() {
        TradeIntentStrategy mild = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "staleBars", 4,
                "staleMinR", "0.25"
        )), null);
        TradeIntentStrategy adverse = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "staleBars", 4,
                "staleMinR", "0.25"
        )), null);
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        var mildExit = mild.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.10d, 0, 0.0d, 0.0d)
        ));
        var adverseExit = adverse.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.10d, 0, 0.0d, 3.0d)
        ));

        assertThat(adverseExit.tradeIntents().getFirst().confidence().value())
                .isGreaterThan(mildExit.tradeIntents().getFirst().confidence().value());
    }

    @Test
    void trailAfterScaleOutCanDisablePostScaleWeaknessExit() {
        List<BarEvent> bars = new java.util.ArrayList<>(DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars());
        bars.add(DoflamingoStrategyTestSupport.nextBarAfter(bars, 370.0d, 376.0d, 191.0d, 192.0d));

        TradeIntentStrategy enabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "trailAfterScaleOut", true
        )), null);
        TradeIntentStrategy disabled = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "trailAfterScaleOut", false
        )), null);

        var enabledResult = enabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.70d, 1)
        ));
        var disabledResult = disabled.onBarIntent(DoflamingoStrategyTestSupport.context(
                bars,
                DoflamingoStrategyTestSupport.longPosition(6, 0.70d, 1)
        ));

        assertThat(enabledResult.tradeIntents()).extracting("action").contains(StrategyTradeAction.EXIT_LONG);
        assertThat(enabledResult.tradeIntents().getFirst().reason().conditions())
                .filteredOn(condition -> condition.conditionId().equals("multi-v6-v3.exit-post-scale-weakness"))
                .singleElement()
                .satisfies(condition -> assertThat(condition.passed()).isTrue());
        assertThat(disabledResult.tradeIntents()).isEmpty();
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

    private List<String> replayDirectActions(StrategyParameters parameters, List<BarEvent> bars) {
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(parameters, null);
        List<String> actions = new ArrayList<>();
        for (int index = 1; index <= bars.size(); index++) {
            strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars.subList(0, index))).tradeIntents().stream()
                    .map(intent -> intent.action().name())
                    .forEach(actions::add);
        }
        return actions;
    }

    private List<String> replayStepperActions(StrategyParameters parameters, List<BarEvent> bars) {
        SimulationStepper stepper = new SimulationStepper(
                List.of((TradeSignalStrategy) provider.create(parameters, null)),
                METADATA,
                256,
                Clock.systemUTC(),
                DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.STRATEGY_VERSION,
                parameters
        );
        List<String> actions = new ArrayList<>();
        for (BarEvent bar : bars) {
            stepper.step(bar, MarketDataVisibilitySnapshot.empty(), MarketContextSnapshot.empty()).result().emittedTradeIntents().stream()
                    .map(intent -> intent.action().name())
                    .forEach(actions::add);
        }
        return actions;
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
}
