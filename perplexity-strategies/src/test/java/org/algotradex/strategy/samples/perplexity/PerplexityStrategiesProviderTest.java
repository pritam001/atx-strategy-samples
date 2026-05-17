package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextFrameSnapshot;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSourceWindow;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyPortfolioState;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyOrigin;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityStrategiesProviderTest {
    private static final String ORB = "india-orb-breakout-v1";
    private static final String BOLLINGER_RSI = "india-bollinger-rsi-range-v1";
    private static final String VWAP_REVERSION = "india-vwap-reversion-v1";
    private static final String RSI_SWING = "india-rsi-swing-daily-v1";

    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-perplexity-india-test"),
            new ReplayId("replay-perplexity-india-test"),
            ReplayMode.FULL_RUN
    );
    private static final Instant SESSION_START = Instant.parse("2026-01-02T03:45:00Z");
    private static final Instant VWAP_SESSION_START = Instant.parse("2026-01-02T04:25:00Z");

    @Test
    void serviceLoaderDiscoversAllPerplexityStrategies() {
        assertThat(providerDescriptors().keySet()).contains(ORB, BOLLINGER_RSI, VWAP_REVERSION, RSI_SWING);
    }

    @Test
    void descriptorsExposeIndiaLifecycleContracts() {
        Map<String, StrategyDescriptor> descriptors = providerDescriptors();

        assertThat(descriptors.get(ORB).supportedTimeframes()).containsExactly("M5", "M15");
        assertThat(descriptors.get(BOLLINGER_RSI).supportedTimeframes()).containsExactly("M5", "M15");
        assertThat(descriptors.get(VWAP_REVERSION).supportedTimeframes()).containsExactly("M5", "M15");
        assertThat(descriptors.get(RSI_SWING).supportedTimeframes()).containsExactly("H1", "D1");

        for (String strategyId : List.of(ORB, BOLLINGER_RSI, VWAP_REVERSION, RSI_SWING)) {
            StrategyDescriptor descriptor = descriptors.get(strategyId);
            StrategyProvider provider = provider(strategyId);

            assertThat(descriptor.providerId()).isEqualTo("atx-strategy-samples-perplexity");
            assertThat(descriptor.supportedAssetClasses()).contains("EQUITY", "INDEX", "FUTURE", "ETF");
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
            assertThat(descriptor.parameterSchema().parameters()).isNotEmpty();
            assertThat(descriptor.suggestedChartStudies()).isNotEmpty();
            assertThat(provider.validate(StrategyParameters.empty()).valid()).isTrue();
        }
    }

    @Test
    void descriptorsExposeP0P1ParametersAndCorrectDefaults() {
        assertDefault(ORB, "cooldownBars", 0);
        assertDefault(ORB, "skipOnExpiry", true);
        assertDefault(ORB, "enforceSessionGate", true);

        assertDefault(BOLLINGER_RSI, "cooldownBars", 0);
        assertDefault(BOLLINGER_RSI, "skipOnExpiry", false);
        assertDefault(BOLLINGER_RSI, "enforceSessionGate", true);
        assertDefault(BOLLINGER_RSI, "rangeQualityMinAdxBelow", 20);
        assertDefault(BOLLINGER_RSI, "oversoldRsi", 30);
        assertDefault(BOLLINGER_RSI, "overboughtRsi", 70);

        assertDefault(VWAP_REVERSION, "cooldownBars", 0);
        assertDefault(VWAP_REVERSION, "skipOnExpiry", true);
        assertDefault(VWAP_REVERSION, "enforceSessionGate", true);

        assertDefault(RSI_SWING, "cooldownBars", 0);
        assertDefault(RSI_SWING, "skipOnExpiry", true);
    }

    @Test
    void orbBreakoutEmitsLongIntentAfterOpeningRangeBreakWithVolume() {
        TradeIntentStrategy strategy = strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "riskFraction", "0.01",
                "maxHoldingBars", 18
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(orbBreakoutBars()));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().direction()).isEqualTo(Direction.LONG);
        assertThat(result.tradeSignals().getFirst().setupType()).isEqualTo(SetupType.BREAKOUT);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(result.tradeIntents().getFirst().direction()).isEqualTo(PositionSide.LONG);
        assertThat(result.tradeIntents().getFirst().horizon().maxHoldingBars()).isEqualTo(18);
        assertThat(result.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("orb.range-ready", "orb.breakout", "orb.volume", "orb.atr-ready");
    }

    @Test
    void orbDefaultOpeningRangeBarsAreTimeframeAware() {
        TradeIntentStrategy m5Strategy = strategy(ORB, Map.of(
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "riskFraction", "0.01",
                "maxHoldingBars", 18
        ));
        TradeIntentStrategy m15Strategy = strategy(ORB, Map.of(
                "atrPeriod", 3,
                "volumeLookbackBars", 3,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "riskFraction", "0.01",
                "maxHoldingBars", 18
        ));

        StrategyIntentResult m5Result = m5Strategy.onBarIntent(context(orbBreakoutBars()));
        StrategyIntentResult m15Result = m15Strategy.onBarIntent(context(
                retime(orbBreakoutFiveBars(), "M15", SESSION_START, Duration.ofMinutes(15))
        ));

        assertThat(m5Result.tradeIntents()).hasSize(1);
        assertThat(m15Result.tradeIntents()).hasSize(1);
    }

    @Test
    void percentStopValueIsEncodedAsPercentagePoints() {
        TradeIntentStrategy strategy = strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "riskFraction", "0.01",
                "maxHoldingBars", 18
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(orbBreakoutBars()));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().exit().stop().value())
                .isGreaterThan(new BigDecimal("0.5000"))
                .isLessThan(new BigDecimal("5.0000"));
    }

    @Test
    void m15MaxHoldingDurationUsesFifteenMinuteBars() {
        TradeIntentStrategy strategy = strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "riskFraction", "0.01",
                "maxHoldingBars", 4
        ));

        List<BarEvent> bars = retime(orbBreakoutBars(), "M15", Instant.parse("2026-01-02T02:15:00Z"), Duration.ofMinutes(15));
        StrategyIntentResult result = strategy.onBarIntent(context(bars));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().horizon().maxHoldingDuration()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void bollingerRsiRangeEmitsMeanReversionLongAtLowerBand() {
        TradeIntentStrategy strategy = strategy(BOLLINGER_RSI, Map.of(
                "bollingerPeriod", 10,
                "bollingerStdDev", "2.0",
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18,
                "riskFraction", "0.01",
                "maxHoldingBars", 24
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(bollingerRsiRangeBars()));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().direction()).isEqualTo(Direction.LONG);
        assertThat(result.tradeSignals().getFirst().setupType()).isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(result.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("bb.rsi-oversold", "bb.lower-band-touch", "bb.range-regime");
    }

    @Test
    void bollingerUsesMarketContextWhenPresentAndFallsBackToDriftWhenAbsent() {
        Map<String, Object> parameters = Map.of(
                "bollingerPeriod", 10,
                "bollingerStdDev", "2.0",
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18,
                "riskFraction", "0.01",
                "maxHoldingBars", 24
        );
        List<BarEvent> bars = bollingerRsiRangeBars();

        assertThat(strategy(BOLLINGER_RSI, parameters).onBarIntent(context(bars)).tradeIntents()).hasSize(1);
        assertThat(strategy(BOLLINGER_RSI, parameters).onBarIntent(context(bars, flatPosition(), marketContext(bars, "RANGE_BOUND", "10.0000"))).tradeIntents()).hasSize(1);
        assertThat(strategy(BOLLINGER_RSI, parameters).onBarIntent(context(bars, flatPosition(), marketContext(bars, "BREAKOUT_STRUCTURE", "10.0000"))).tradeIntents()).isEmpty();
    }

    @Test
    void vwapReversionEmitsLongWhenPriceReclaimsLowerVwapBand() {
        TradeIntentStrategy strategy = strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "minRelativeVolume", "1.00",
                "riskFraction", "0.01",
                "maxHoldingBars", 18
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(vwapReversionBars()));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().direction()).isEqualTo(Direction.LONG);
        assertThat(result.tradeSignals().getFirst().setupType()).isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(result.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("vwap.session-ready", "vwap.lower-band-reclaim", "vwap.volume");
    }

    @Test
    void rsiSwingDailyEmitsLongOnTrendPullbackResumption() {
        TradeIntentStrategy strategy = strategy(RSI_SWING, Map.of(
                "fastEmaPeriod", 5,
                "slowEmaPeriod", 13,
                "rsiPeriod", 5,
                "pullbackRsiMin", 35,
                "pullbackRsiMax", 65,
                "volumeLookbackBars", 8,
                "minRelativeVolume", "1.00",
                "riskFraction", "0.01",
                "maxHoldingBars", 12
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(rsiSwingDailyBars()));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().direction()).isEqualTo(Direction.LONG);
        assertThat(result.tradeSignals().getFirst().setupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(result.tradeIntents().getFirst().horizon().maxHoldingBars()).isEqualTo(12);
        assertThat(result.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("rsi-swing.trend", "rsi-swing.pullback-zone", "rsi-swing.resumption", "rsi-swing.volume");
    }

    @Test
    void lifecycleStrategiesDoNotEmitDuplicateEntriesWhilePositionIsOpen() {
        assertThat(strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "maxHoldingBars", 18
        )).onBarIntent(context(orbBreakoutBars(), longPosition(2), MarketContextSnapshot.empty())).tradeIntents()).isEmpty();

        assertThat(strategy(BOLLINGER_RSI, Map.of(
                "bollingerPeriod", 10,
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18,
                "maxHoldingBars", 24
        )).onBarIntent(context(bollingerRsiRangeBars(), longPosition(2), MarketContextSnapshot.empty())).tradeIntents()).isEmpty();

        assertThat(strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "minRelativeVolume", "1.00",
                "maxHoldingBars", 18
        )).onBarIntent(context(vwapReversionBars(), longPosition(2), MarketContextSnapshot.empty())).tradeIntents()).isEmpty();

        assertThat(strategy(RSI_SWING, Map.of(
                "fastEmaPeriod", 5,
                "slowEmaPeriod", 13,
                "rsiPeriod", 5,
                "pullbackRsiMin", 35,
                "pullbackRsiMax", 65,
                "volumeLookbackBars", 8,
                "minRelativeVolume", "1.00",
                "maxHoldingBars", 12
        )).onBarIntent(context(rsiSwingDailyBars(), longPosition(2), MarketContextSnapshot.empty())).tradeIntents()).isEmpty();
    }

    @Test
    void orbBreakoutInvalidationExitsWhenPriceFallsBackIntoOpeningRange() {
        TradeIntentStrategy strategy = strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "maxHoldingBars", 18,
                "cooldownBars", 2
        ));
        List<BarEvent> entryBars = orbBreakoutBars();
        assertThat(strategy.onBarIntent(context(entryBars)).tradeIntents()).hasSize(1);

        List<BarEvent> invalidationBars = new ArrayList<>(entryBars);
        invalidationBars.add(m5(10, 102.20, 102.30, 101.55, 101.90, 1_400));
        StrategyIntentResult result = strategy.onBarIntent(context(invalidationBars, longPosition(1), MarketContextSnapshot.empty()));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(result.tradeIntents().getFirst().setupType()).isEqualTo(SetupType.BREAKOUT);
        assertThat(result.tradeIntents().getFirst().reason().tags()).contains("exit=invalidation");
    }

    @Test
    void bollingerInvalidationExitsWhenRangeBreaksBeyondTouchedBand() {
        TradeIntentStrategy strategy = strategy(BOLLINGER_RSI, Map.of(
                "bollingerPeriod", 10,
                "bollingerStdDev", "2.0",
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18,
                "maxHoldingBars", 24,
                "cooldownBars", 2
        ));
        List<BarEvent> entryBars = bollingerRsiRangeBars();
        assertThat(strategy.onBarIntent(context(entryBars)).tradeIntents()).hasSize(1);

        List<BarEvent> invalidationBars = new ArrayList<>(entryBars);
        invalidationBars.add(m5(25, 95.20, 95.30, 92.10, 92.40, 1_300));
        StrategyIntentResult result = strategy.onBarIntent(context(invalidationBars, longPosition(1), MarketContextSnapshot.empty()));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(result.tradeIntents().getFirst().setupType()).isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(result.tradeIntents().getFirst().reason().tags()).contains("exit=invalidation");
    }

    @Test
    void vwapInvalidationExitsWhenPriceDoesNotReclaimEntryVwapByThirdBar() {
        TradeIntentStrategy strategy = strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "minRelativeVolume", "1.00",
                "maxHoldingBars", 18,
                "cooldownBars", 2
        ));
        List<BarEvent> entryBars = vwapReversionBars();
        assertThat(strategy.onBarIntent(context(entryBars)).tradeIntents()).hasSize(1);

        List<BarEvent> invalidationBars = new ArrayList<>(entryBars);
        invalidationBars.add(vwapM5(20, 99.00, 99.25, 98.70, 99.10, 1_250));
        invalidationBars.add(vwapM5(21, 99.10, 99.30, 98.80, 99.05, 1_260));
        invalidationBars.add(vwapM5(22, 99.05, 99.20, 98.60, 99.00, 1_270));
        StrategyIntentResult result = strategy.onBarIntent(context(invalidationBars, longPosition(3), MarketContextSnapshot.empty()));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(result.tradeIntents().getFirst().setupType()).isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(result.tradeIntents().getFirst().reason().tags()).contains("exit=invalidation");
    }

    @Test
    void rsiSwingUsesMarketContextTrendWhenAvailableAndFallsBackToEmaWhenAbsent() {
        Map<String, Object> parameters = Map.of(
                "fastEmaPeriod", 5,
                "slowEmaPeriod", 13,
                "rsiPeriod", 5,
                "pullbackRsiMin", 35,
                "pullbackRsiMax", 65,
                "volumeLookbackBars", 8,
                "minRelativeVolume", "1.00",
                "maxHoldingBars", 12
        );
        List<BarEvent> bars = rsiSwingDailyBars();

        assertThat(strategy(RSI_SWING, parameters).onBarIntent(context(bars)).tradeIntents()).hasSize(1);
        assertThat(strategy(RSI_SWING, parameters).onBarIntent(context(bars, flatPosition(), marketContextWithTrend(bars, "TRENDING_UP"))).tradeIntents()).hasSize(1);
        assertThat(strategy(RSI_SWING, parameters).onBarIntent(context(bars, flatPosition(), marketContextWithTrend(bars, "TRENDING_DOWN"))).tradeIntents()).isEmpty();
    }

    @Test
    void lifecycleTimeExitsKeepTheEntrySetupFamily() {
        assertThat(strategy(ORB, Map.of()).onBarIntent(context(orbBreakoutBars(), longPosition(36), MarketContextSnapshot.empty())).tradeIntents().getFirst().setupType())
                .isEqualTo(SetupType.BREAKOUT);
        assertThat(strategy(BOLLINGER_RSI, Map.of()).onBarIntent(context(bollingerRsiRangeBars(), longPosition(24), MarketContextSnapshot.empty())).tradeIntents().getFirst().setupType())
                .isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(strategy(VWAP_REVERSION, Map.of()).onBarIntent(context(vwapReversionBars(), longPosition(24), MarketContextSnapshot.empty())).tradeIntents().getFirst().setupType())
                .isEqualTo(SetupType.MEAN_REVERSION);
        assertThat(strategy(RSI_SWING, Map.of()).onBarIntent(context(rsiSwingDailyBars(), longPosition(20), MarketContextSnapshot.empty())).tradeIntents().getFirst().setupType())
                .isEqualTo(SetupType.PULLBACK);
    }

    @Test
    void orbAndVwapCanUsePriorSessionHistoryForAtrAndVolumeBaselines() {
        TradeIntentStrategy orb = strategy(ORB, Map.of(
                "openingRangeBars", 3,
                "atrPeriod", 8,
                "volumeLookbackBars", 8,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "maxHoldingBars", 18
        ));
        TradeIntentStrategy vwap = strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 8,
                "volumeLookbackBars", 8,
                "minRelativeVolume", "1.00",
                "maxHoldingBars", 18
        ));

        assertThat(orb.onBarIntent(context(orbBreakoutWithPriorSessionHistory())).tradeIntents()).hasSize(1);
        assertThat(vwap.onBarIntent(context(vwapReversionWithPriorSessionHistory())).tradeIntents()).hasSize(1);
    }

    @Test
    void cooldownSuppressesRepeatedEntriesAfterAnEntry() {
        TradeIntentStrategy strategy = strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20",
                "maxHoldingBars", 18,
                "cooldownBars", 2
        ));

        assertThat(strategy.onBarIntent(context(orbBreakoutBars())).tradeIntents()).hasSize(1);
        assertThat(strategy.onBarIntent(context(retime(orbBreakoutBars(), "M5", SESSION_START.plus(Duration.ofDays(1)), Duration.ofMinutes(5)))).tradeIntents()).isEmpty();
    }

    @Test
    void sessionGatesRejectIntradayEntriesOutsideFixedIndiaWindows() {
        assertThat(strategy(ORB, Map.of(
                "openingRangeBars", 3,
                "atrPeriod", 3,
                "volumeLookbackBars", 3,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20"
        )).onBarIntent(context(retime(orbBreakoutFiveBars(), "M5", Instant.parse("2026-01-02T03:35:00Z"), Duration.ofMinutes(5)))).tradeIntents()).isEmpty();

        assertThat(strategy(BOLLINGER_RSI, Map.of(
                "bollingerPeriod", 10,
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18
        )).onBarIntent(context(retime(bollingerRsiRangeBars(), "M5", Instant.parse("2026-01-02T06:45:00Z"), Duration.ofMinutes(5)))).tradeIntents()).isEmpty();

        assertThat(strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 3,
                "volumeLookbackBars", 3,
                "minRelativeVolume", "1.00"
        )).onBarIntent(context(retime(vwapReversionFiveBars(), "M5", Instant.parse("2026-01-02T04:30:00Z"), Duration.ofMinutes(5)))).tradeIntents()).isEmpty();
    }

    @Test
    void expirySkipSuppressesOrbVwapAndRsiSwingByDefault() {
        Instant expiryM5Start = Instant.parse("2026-01-08T03:45:00Z");
        Instant rsiExpiryClose = Instant.parse("2026-01-08T10:00:00Z");

        assertThat(strategy(ORB, Map.of(
                "openingRangeBars", 6,
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "breakoutBufferPct", "0.001",
                "minRelativeVolume", "1.20"
        )).onBarIntent(context(retime(orbBreakoutBars(), "M5", expiryM5Start, Duration.ofMinutes(5)))).tradeIntents()).isEmpty();

        assertThat(strategy(VWAP_REVERSION, Map.of(
                "vwapBandPct", "0.006",
                "atrPeriod", 5,
                "volumeLookbackBars", 5,
                "minRelativeVolume", "1.00"
        )).onBarIntent(context(retime(vwapReversionBars(), "M5", Instant.parse("2026-01-08T04:25:00Z"), Duration.ofMinutes(5)))).tradeIntents()).isEmpty();

        assertThat(strategy(RSI_SWING, Map.of(
                "fastEmaPeriod", 5,
                "slowEmaPeriod", 13,
                "rsiPeriod", 5,
                "pullbackRsiMin", 35,
                "pullbackRsiMax", 65,
                "volumeLookbackBars", 8,
                "minRelativeVolume", "1.00"
        )).onBarIntent(context(retimeToEnd(rsiSwingDailyBars(), "D1", rsiExpiryClose, Duration.ofDays(1)))).tradeIntents()).isEmpty();
    }

    @Test
    void expirySkipDoesNotSuppressBollingerByDefault() {
        TradeIntentStrategy strategy = strategy(BOLLINGER_RSI, Map.of(
                "bollingerPeriod", 10,
                "bollingerStdDev", "2.0",
                "rsiPeriod", 5,
                "oversoldRsi", 45,
                "trendLookbackBars", 18
        ));

        StrategyIntentResult result = strategy.onBarIntent(context(retime(bollingerRsiRangeBars(), "M5", Instant.parse("2026-01-08T03:45:00Z"), Duration.ofMinutes(5))));

        assertThat(result.tradeIntents()).hasSize(1);
    }

    private static Map<String, StrategyDescriptor> providerDescriptors() {
        return ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .collect(Collectors.toMap(descriptor -> descriptor.identity().strategyId(), Function.identity(), (left, right) -> left));
    }

    private static StrategyProvider provider(String strategyId) {
        var provider = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.descriptor().identity().strategyId().equals(strategyId))
                .findFirst();

        assertThat(provider).as("provider for %s", strategyId).isPresent();
        return provider.orElseThrow();
    }

    private static TradeIntentStrategy strategy(String strategyId, Map<String, Object> parameters) {
        StrategyProvider provider = provider(strategyId);
        StrategyParameters supplied = new StrategyParameters(parameters);
        var validation = provider.validate(supplied);
        assertThat(validation.valid()).as("valid parameters for %s: %s", strategyId, validation.issues()).isTrue();
        var context = new StrategyInstantiationContext(provider.descriptor().identity(), StrategyOrigin.CLASSPATH_PLUGIN, provider.descriptor().providerId());
        assertThat(provider.create(validation.effectiveParameters(), context)).isInstanceOf(TradeIntentStrategy.class);
        return (TradeIntentStrategy) provider.create(validation.effectiveParameters(), context);
    }

    private static void assertDefault(String strategyId, String key, Object expected) {
        Map<String, StrategyParameterDefinition> parameters = provider(strategyId).descriptor().parameterSchema().parameters().stream()
                .collect(Collectors.toMap(StrategyParameterDefinition::key, Function.identity()));

        assertThat(parameters).containsKey(key);
        assertThat(parameters.get(key).defaultValue()).isEqualTo(expected);
    }

    private static StrategyExecutionContext context(List<BarEvent> bars) {
        return context(bars, flatPosition(), MarketContextSnapshot.empty());
    }

    private static StrategyExecutionContext context(List<BarEvent> bars, StrategyInstrumentPosition position, MarketContextSnapshot marketContext) {
        return new StrategyExecutionContext(
                METADATA,
                bars.getLast(),
                bars,
                MarketDataVisibilitySnapshot.empty(),
                marketContext,
                position,
                StrategyPortfolioState.empty()
        );
    }

    private static StrategyInstrumentPosition flatPosition() {
        return StrategyInstrumentPosition.flat();
    }

    private static StrategyInstrumentPosition longPosition(int barsHeld) {
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        return new StrategyInstrumentPosition(
                true,
                PositionSide.LONG,
                BigDecimal.ONE.setScale(4),
                decimal(100),
                SESSION_START,
                barsHeld,
                zero,
                zero,
                null,
                null,
                0,
                0,
                StrategyTradeAction.ENTER_LONG.name(),
                decimal(100),
                zero,
                zero
        );
    }

    private static MarketContextSnapshot marketContext(List<BarEvent> bars, String structure, String adx) {
        BarEvent current = bars.getLast();
        MarketContextFrameSnapshot frame = new MarketContextFrameSnapshot(
                current.instrument().instrumentId(),
                current.timeframe(),
                current.occurredAt(),
                current.occurredAt(),
                MarketContextSourceWindow.empty(current.occurredAt()),
                bars.size(),
                "SIDEWAYS",
                "NORMAL_VOLATILITY",
                structure,
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                structure,
                List.of(structure),
                Map.of("adx", new BigDecimal(adx)),
                new BigDecimal("0.8000"),
                List.of("test market context")
        );
        return new MarketContextSnapshot(
                current.instrument().instrumentId(),
                current.occurredAt(),
                current.timeframe(),
                Map.of(current.timeframe(), frame),
                structure,
                List.of()
        );
    }

    private static MarketContextSnapshot marketContextWithTrend(List<BarEvent> bars, String trend) {
        BarEvent current = bars.getLast();
        MarketContextFrameSnapshot frame = new MarketContextFrameSnapshot(
                current.instrument().instrumentId(),
                current.timeframe(),
                current.occurredAt(),
                current.occurredAt(),
                MarketContextSourceWindow.empty(current.occurredAt()),
                bars.size(),
                trend,
                "NORMAL_VOLATILITY",
                "PULLBACK_STRUCTURE",
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                trend,
                List.of(trend),
                Map.of(),
                new BigDecimal("0.8000"),
                List.of("test market context")
        );
        return new MarketContextSnapshot(
                current.instrument().instrumentId(),
                current.occurredAt(),
                current.timeframe(),
                Map.of(current.timeframe(), frame),
                trend,
                List.of()
        );
    }

    private static List<BarEvent> orbBreakoutBars() {
        List<BarEvent> bars = new ArrayList<>();
        bars.add(m5(0, 100.00, 101.60, 99.80, 101.00, 1_000));
        bars.add(m5(1, 101.00, 102.00, 100.50, 101.70, 1_050));
        bars.add(m5(2, 101.70, 101.90, 100.40, 100.90, 980));
        bars.add(m5(3, 100.90, 101.50, 100.20, 100.80, 1_020));
        bars.add(m5(4, 100.80, 101.70, 100.30, 101.40, 1_010));
        bars.add(m5(5, 101.40, 101.80, 100.60, 101.20, 990));
        bars.add(m5(6, 101.20, 101.90, 100.90, 101.50, 1_100));
        bars.add(m5(7, 101.50, 101.95, 101.10, 101.70, 1_080));
        bars.add(m5(8, 101.70, 102.05, 101.20, 101.80, 1_120));
        bars.add(m5(9, 101.80, 102.60, 101.70, 102.35, 1_950));
        return bars;
    }

    private static List<BarEvent> orbBreakoutFiveBars() {
        List<BarEvent> bars = new ArrayList<>();
        bars.add(m5(0, 100.00, 101.00, 99.80, 100.50, 1_000));
        bars.add(m5(1, 100.50, 101.20, 100.00, 100.70, 1_020));
        bars.add(m5(2, 100.70, 101.00, 100.10, 100.40, 1_010));
        bars.add(m5(3, 100.40, 101.10, 100.20, 100.80, 1_030));
        bars.add(m5(4, 100.80, 102.50, 100.70, 102.10, 2_000));
        return bars;
    }

    private static List<BarEvent> orbBreakoutWithPriorSessionHistory() {
        List<BarEvent> bars = new ArrayList<>();
        bars.addAll(priorSessionBars(Instant.parse("2026-01-01T03:45:00Z")));
        bars.addAll(orbBreakoutFiveBars());
        return bars;
    }

    private static List<BarEvent> bollingerRsiRangeBars() {
        List<BarEvent> bars = new ArrayList<>();
        double[] closes = {100, 100.4, 99.8, 100.2, 99.7, 100.1, 100.3, 99.9, 100.2, 99.8,
                100.1, 100.0, 99.7, 100.4, 100.1, 99.8, 100.2, 99.9, 100.0, 99.7,
                97.8, 96.8, 95.6, 94.8, 95.4};
        for (int index = 0; index < closes.length; index++) {
            double close = closes[index];
            double low = index == closes.length - 1 ? close - 2.0 : index >= 20 ? close - 1.2 : close - 0.5;
            double high = close + 0.6;
            bars.add(m5(index, close + 0.2, high, low, close, 1_000 + (index * 8)));
        }
        return bars;
    }

    private static List<BarEvent> vwapReversionBars() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            double close = 100.00 + ((index % 3) * 0.10);
            bars.add(vwapM5(index, close - 0.10, close + 0.35, close - 0.35, close, 1_000 + (index * 4)));
        }
        bars.add(vwapM5(18, 99.90, 100.10, 96.80, 97.20, 1_650));
        bars.add(vwapM5(19, 97.20, 99.40, 96.90, 99.05, 1_900));
        return bars;
    }

    private static List<BarEvent> vwapReversionFiveBars() {
        List<BarEvent> bars = new ArrayList<>();
        bars.add(vwapM5(0, 100.00, 100.35, 99.65, 100.00, 1_000));
        bars.add(vwapM5(1, 100.05, 100.40, 99.70, 100.10, 1_010));
        bars.add(vwapM5(2, 100.10, 100.45, 99.75, 100.20, 1_020));
        bars.add(vwapM5(3, 100.00, 100.30, 99.60, 100.00, 1_030));
        bars.add(vwapM5(4, 97.20, 99.40, 96.90, 99.05, 1_900));
        return bars;
    }

    private static List<BarEvent> vwapReversionWithPriorSessionHistory() {
        List<BarEvent> bars = new ArrayList<>();
        bars.addAll(priorSessionBars(Instant.parse("2026-01-01T05:40:00Z")));
        bars.addAll(retime(vwapReversionFiveBars(), "M5", Instant.parse("2026-01-02T05:40:00Z"), Duration.ofMinutes(5)));
        return bars;
    }

    private static List<BarEvent> rsiSwingDailyBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 100.0;
        for (int index = 0; index < 34; index++) {
            close += 0.55;
            bars.add(d1(index, close - 0.30, close + 0.80, close - 0.90, close, 900 + (index * 4)));
        }
        double[] pullbackAndResume = {118.20, 117.00, 115.80, 114.90, 115.60, 116.80};
        for (int index = 0; index < pullbackAndResume.length; index++) {
            double value = pullbackAndResume[index];
            double volume = index == pullbackAndResume.length - 1 ? 1_450 : 1_000 + (index * 30);
            bars.add(d1(34 + index, value - 0.40, value + 0.90, value - 1.10, value, volume));
        }
        return bars;
    }

    private static List<BarEvent> priorSessionBars(Instant start) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            double close = 99.60 + ((index % 4) * 0.20);
            bars.add(bar("M5", index, start.plus(Duration.ofMinutes(index * 5L)), close - 0.10, close + 0.55, close - 0.65, close, 950 + (index * 10)));
        }
        return bars;
    }

    private static BarEvent m5(int index, double open, double high, double low, double close, double volume) {
        return bar("M5", index, SESSION_START.plus(Duration.ofMinutes(index * 5L)), open, high, low, close, volume);
    }

    private static BarEvent vwapM5(int index, double open, double high, double low, double close, double volume) {
        return bar("M5", index, VWAP_SESSION_START.plus(Duration.ofMinutes(index * 5L)), open, high, low, close, volume);
    }

    private static BarEvent d1(int index, double open, double high, double low, double close, double volume) {
        return bar("D1", index, SESSION_START.plus(Duration.ofDays(index)), open, high, low, close, volume);
    }

    private static List<BarEvent> retimeToEnd(List<BarEvent> bars, String timeframe, Instant lastBarTime, Duration step) {
        Instant first = lastBarTime.minus(step.multipliedBy(bars.size() - 1L));
        return retime(bars, timeframe, first, step);
    }

    private static List<BarEvent> retime(List<BarEvent> bars, String timeframe, Instant firstBarTime, Duration step) {
        List<BarEvent> retimed = new ArrayList<>(bars.size());
        for (int index = 0; index < bars.size(); index++) {
            BarEvent source = bars.get(index);
            retimed.add(bar(
                    timeframe,
                    index,
                    firstBarTime.plus(step.multipliedBy(index)),
                    source.ohlcv().open().doubleValue(),
                    source.ohlcv().high().doubleValue(),
                    source.ohlcv().low().doubleValue(),
                    source.ohlcv().close().doubleValue(),
                    source.ohlcv().volume().doubleValue()
            ));
        }
        return retimed;
    }

    private static BarEvent bar(String timeframe, int index, Instant occurredAt, double open, double high, double low, double close, double volume) {
        return new BarEvent(
                "1.0.0",
                new EventId("bar-" + timeframe.toLowerCase() + '-' + occurredAt.toString().replace(':', '-') + '-' + String.format("%03d", index)),
                INSTRUMENT,
                occurredAt,
                timeframe,
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), decimal(volume)),
                new SourceRef(SourceType.ADAPTER, "test-market-data"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
