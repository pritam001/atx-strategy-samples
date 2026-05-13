package org.algotradex.strategy.samples.rangesrv2;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class RangeSrV2StrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final InstrumentRef CRYPTO_INSTRUMENT = new InstrumentRef("CRYPTO-BTC-INR", "BTC/INR", "COINSWITCHX", AssetClass.CRYPTO, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-range-sr-v2-test"),
            new ReplayId("replay-range-sr-v2-test"),
            ReplayMode.FULL_RUN
    );

    private final RangeSrV2StrategyProvider provider = new RangeSrV2StrategyProvider();

    @Test
    void exposesDescriptorCapabilitiesAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo("range-sr-v2");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.providerId()).isEqualTo("atx-strategy-samples");
        assertThat(descriptor.supportedTimeframes()).containsExactly("M1", "M5", "M15");
        assertThat(descriptor.requiredContextTimeframes()).containsExactly("H4");
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(descriptor.parameterSchema().parameters()).hasSize(13);
        assertThat(descriptor.suggestedChartStudies()).hasSize(4);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("minPatternConfidence", BigDecimal.ZERO)).isEqualByComparingTo("1.0");
        assertThat(validation.effectiveParameters().bool("use15mStructure", true)).isFalse();
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(RangeSrV2StrategyProvider.STRATEGY_ID);
    }

    @Test
    void emitsBullishSignalAndEntryIntentAtH4DiscountSupport() {
        TradeIntentStrategy strategy = strategy(Map.of());
        StrategyIntentResult result = strategy.onBarIntent(context(bullishM15Setup(), h4TrendStructure(130.0d)));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);

        TradeSignal signal = result.tradeSignals().getFirst();
        assertThat(signal.direction()).isEqualTo(Direction.LONG);
        assertThat(signal.suggestedParams().entry()).isEqualByComparingTo("106.0000");
        assertThat(signal.suggestedParams().target()).isEqualByComparingTo("130.0000");
        assertThat(signal.suggestedParams().size()).isPositive();
        assertThat(signal.tags().values()).contains("pattern=bullish-engulfing", "confluence=2");

        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.direction()).isEqualTo(PositionSide.LONG);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.NORMALIZED_UNITS);
        assertThat(intent.sizing().requestedUnits()).isEqualByComparingTo(signal.suggestedParams().size());
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("h4-trend", "zone-match", "confluence", "pattern", "rr");
    }

    @Test
    void supportsM1AndM5ExecutionTimeframes() {
        for (String timeframe : List.of("M1", "M5")) {
            TradeIntentStrategy strategy = strategy(Map.of());
            List<BarEvent> executionBars = retime(bullishM15Setup(), timeframe, timeframe.equals("M1") ? 60L : 300L);

            StrategyIntentResult result = strategy.onBarIntent(context(executionBars, h4TrendStructure(130.0d)));

            assertThat(result.tradeSignals()).as(timeframe).hasSize(1);
            assertThat(result.tradeIntents()).as(timeframe).hasSize(1);
            assertThat(result.tradeIntents().getFirst().reason().conditions())
                    .as(timeframe)
                    .anySatisfy(condition -> assertThat(condition.label()).contains(timeframe));
        }
    }

    @Test
    void preservesPositiveNormalizedUnitsForHighPricedCryptoSetups() {
        TradeIntentStrategy strategy = strategy(Map.of("riskUsdPerTrade", "1"));
        StrategyIntentResult result = strategy.onBarIntent(context(
                scalePrices(bullishM15Setup(), 10_000.0d, CRYPTO_INSTRUMENT),
                scalePrices(h4TrendStructure(130.0d), 10_000.0d, CRYPTO_INSTRUMENT)
        ));

        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().suggestedParams().size()).isEqualByComparingTo("0.0001");
        assertThat(result.tradeIntents().getFirst().sizing().requestedUnits()).isEqualByComparingTo("0.0001");
    }

    @Test
    void enforcesPerInstrumentCooldown() {
        TradeIntentStrategy strategy = strategy(Map.of("cooldownHours", 4));
        StrategyExecutionContext first = context(bullishM15Setup(), h4TrendStructure(130.0d));
        StrategyExecutionContext second = context(shift(bullishM15Setup(), 1), h4TrendStructure(130.0d));

        assertThat(strategy.onBarIntent(first).tradeSignals()).hasSize(1);
        assertThat(strategy.onBarIntent(second).tradeSignals()).isEmpty();
    }

    @Test
    void skipsFlatH4Trend() {
        TradeIntentStrategy strategy = strategy(Map.of());

        assertThat(strategy.onBarIntent(context(bullishM15Setup(), flatH4())).tradeSignals()).isEmpty();
    }

    @Test
    void skipsWhenNoRealTwoRTargetExists() {
        TradeIntentStrategy strategy = strategy(Map.of());

        assertThat(strategy.onBarIntent(context(bullishM15Setup(), h4TrendStructure(120.0d))).tradeSignals()).isEmpty();
    }

    @Test
    void strictTierOneDefaultSkipsHammerPattern() {
        TradeIntentStrategy strategy = strategy(Map.of());

        assertThat(strategy.onBarIntent(context(hammerM15Setup(), h4TrendStructure(130.0d))).tradeSignals()).isEmpty();
    }

    @Test
    void lowerPatternThresholdAllowsTierTwoHammer() {
        TradeIntentStrategy strategy = strategy(Map.of("minPatternConfidence", "0.7"));

        assertThat(strategy.onBarIntent(context(hammerM15Setup(), h4TrendStructure(130.0d))).tradeSignals()).hasSize(1);
    }

    @Test
    void skipsStopHuntThatClosesThroughSupport() {
        TradeIntentStrategy strategy = strategy(Map.of());

        assertThat(strategy.onBarIntent(context(failedStopHuntM15Setup(), h4TrendStructure(130.0d))).tradeSignals()).isEmpty();
    }

    private TradeIntentStrategy strategy(Map<String, Object> overrides) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.putAll(overrides);
        return (TradeIntentStrategy) provider.create(new StrategyParameters(params), null);
    }

    private static StrategyExecutionContext context(List<BarEvent> executionBars, List<BarEvent> h4) {
        BarEvent current = executionBars.getLast();
        return new StrategyExecutionContext(
                METADATA,
                current,
                executionBars,
                new MarketDataVisibilitySnapshot(current.occurredAt(), current.timeframe(), Map.of("H4", h4), List.of()),
                null
        );
    }

    private static List<BarEvent> bullishM15Setup() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            bars.add(m15(index, 106.0d, 107.0d, 104.5d, 105.0d));
        }
        bars.add(m15(18, 105.0d, 105.5d, 100.5d, 101.0d));
        bars.add(m15(19, 100.5d, 107.0d, 99.5d, 106.0d));
        return bars;
    }

    private static List<BarEvent> hammerM15Setup() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            bars.add(m15(index, 106.0d, 107.0d, 104.5d, 105.0d));
        }
        bars.add(m15(19, 103.0d, 104.0d, 99.5d, 103.6d));
        return bars;
    }

    private static List<BarEvent> failedStopHuntM15Setup() {
        List<BarEvent> bars = bullishM15Setup();
        bars.set(19, m15(19, 100.5d, 103.0d, 99.5d, 99.8d));
        return bars;
    }

    private static List<BarEvent> h4TrendStructure(double targetHigh) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            double close = 80.0d + (index * 0.55d);
            bars.add(h4(index, close - 0.3d, close + 1.0d, close - 1.0d, close));
        }
        bars.add(h4(50, 109.0d, targetHigh, 106.0d, 110.0d));
        bars.add(h4(51, 108.0d, 116.0d, 104.0d, 109.0d));
        bars.add(h4(52, 107.0d, 113.0d, 103.0d, 108.0d));
        bars.add(h4(53, 106.0d, 111.0d, 102.0d, 107.0d));
        bars.add(h4(54, 105.0d, 110.0d, 100.0d, 106.0d));
        bars.add(h4(55, 106.0d, 112.0d, 102.0d, 108.0d));
        bars.add(h4(56, 108.0d, 113.0d, 103.0d, 109.0d));
        bars.add(h4(57, 109.0d, 114.0d, 104.0d, 110.0d));
        bars.add(h4(58, 110.0d, 115.0d, 105.0d, 111.0d));
        bars.add(h4(59, 111.0d, 116.0d, 106.0d, 112.0d));
        return bars;
    }

    private static List<BarEvent> flatH4() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            bars.add(h4(index, 105.0d, 106.0d, 104.0d, 105.0d));
        }
        return bars;
    }

    private static List<BarEvent> shift(List<BarEvent> source, int minutes) {
        List<BarEvent> shifted = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            BarEvent bar = source.get(index);
            shifted.add(new BarEvent(
                    bar.schemaVersion(),
                    new EventId(bar.eventId().value() + "-s" + minutes),
                    bar.instrument(),
                    bar.occurredAt().plusSeconds(minutes * 60L),
                    bar.timeframe(),
                    bar.ohlcv(),
                    bar.sourceRef(),
                    bar.cohort(),
                    bar.baseline(),
                    bar.tags()
            ));
        }
        return shifted;
    }

    private static List<BarEvent> scalePrices(List<BarEvent> source, double multiplier, InstrumentRef instrument) {
        List<BarEvent> scaled = new ArrayList<>();
        BigDecimal factor = BigDecimal.valueOf(multiplier);
        for (BarEvent bar : source) {
            OHLCV ohlcv = bar.ohlcv();
            scaled.add(new BarEvent(
                    bar.schemaVersion(),
                    new EventId(bar.eventId().value() + "-scaled"),
                    instrument,
                    bar.occurredAt(),
                    bar.timeframe(),
                    new OHLCV(
                            ohlcv.open().multiply(factor).setScale(4, RoundingMode.HALF_UP),
                            ohlcv.high().multiply(factor).setScale(4, RoundingMode.HALF_UP),
                            ohlcv.low().multiply(factor).setScale(4, RoundingMode.HALF_UP),
                            ohlcv.close().multiply(factor).setScale(4, RoundingMode.HALF_UP),
                            ohlcv.volume()
                    ),
                    bar.sourceRef(),
                    bar.cohort(),
                    bar.baseline(),
                    bar.tags()
            ));
        }
        return scaled;
    }

    private static List<BarEvent> retime(List<BarEvent> source, String timeframe, long seconds) {
        List<BarEvent> retimed = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            BarEvent bar = source.get(index);
            retimed.add(new BarEvent(
                    bar.schemaVersion(),
                    new EventId(timeframe.toLowerCase(Locale.ROOT) + "-bar-%03d".formatted(index + 1)),
                    bar.instrument(),
                    Instant.parse("2026-04-11T09:15:00Z").plusSeconds(index * seconds),
                    timeframe,
                    bar.ohlcv(),
                    bar.sourceRef(),
                    bar.cohort(),
                    bar.baseline(),
                    bar.tags()
            ));
        }
        return retimed;
    }

    private static BarEvent m15(int index, double open, double high, double low, double close) {
        return bar("m15", "M15", index, 900L, open, high, low, close);
    }

    private static BarEvent h4(int index, double open, double high, double low, double close) {
        return bar("h4", "H4", index, 14_400L, open, high, low, close);
    }

    private static BarEvent bar(String prefix, String timeframe, int index, long seconds, double open, double high, double low, double close) {
        return new BarEvent(
                "1.0.0",
                new EventId(prefix + "-bar-%03d".formatted(index + 1)),
                INSTRUMENT,
                Instant.parse("2026-04-11T09:15:00Z").plusSeconds(index * seconds),
                timeframe,
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), BigDecimal.valueOf(1000L + index)),
                new SourceRef(SourceType.ADAPTER, "range-sr-v2-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
