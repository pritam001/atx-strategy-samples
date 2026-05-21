package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyEntryType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyContextTimeframeRule;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyPortfolioState;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyReasoningCollectionMode;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoMomentumV5StrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-doflamingo-v5-test"),
            new ReplayId("replay-doflamingo-v5-test"),
            ReplayMode.FULL_RUN
    );

    @Test
    void intradayDescriptorMatchesV5BetaSpec() {
        DoflamingoMomentumV5IntradayStrategyProvider provider = new DoflamingoMomentumV5IntradayStrategyProvider();
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo("doflamingo-momentum-v5-beta-intraday");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("5.0.0-beta.1");
        assertThat(descriptor.displayName()).isEqualTo("Doflamingo Momentum V5 Beta Intraday");
        assertThat(descriptor.providerId()).isEqualTo("atx-strategy-samples");
        assertThat(descriptor.supportedTimeframes()).containsExactly("M1", "M5", "M15");
        assertThat(descriptor.requiredContextTimeframes()).isEmpty();
        assertThat(descriptor.optionalContextTimeframes()).isEmpty();
        assertThat(descriptor.contextTimeframeRules()).containsExactly(
                new StrategyContextTimeframeRule("M1", "M5", true),
                new StrategyContextTimeframeRule("M5", "M15", true),
                new StrategyContextTimeframeRule("M15", "H1", true)
        );
        assertThat(descriptor.capabilities()).contains(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.SCALE_IN_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
        assertThat(descriptor.reasoningModel().phases()).extracting("phaseId")
                .containsExactly("context", "structure", "momentum", "risk", "lifecycle");
        assertThat(descriptor.suggestedChartStudies()).extracting("indicatorId")
                .contains("ichimoku", "ema", "macd", "stoch-rsi", "psar", "atr", "volume-pulse");
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("riskFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.005");
        assertThat(validation.effectiveParameters().string("horizonMode", "")).isEqualTo("INTRADAY");
        assertThat(validation.effectiveParameters().bool("enableScaleIn", true)).isFalse();
        assertThat(validation.effectiveParameters().bool("emitDiagnostics", false)).isTrue();
    }

    @Test
    void swingDescriptorMatchesV5BetaSpec() {
        DoflamingoMomentumV5SwingStrategyProvider provider = new DoflamingoMomentumV5SwingStrategyProvider();
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo("doflamingo-momentum-v5-beta-swing");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("5.0.0-beta.1");
        assertThat(descriptor.displayName()).isEqualTo("Doflamingo Momentum V5 Beta Swing");
        assertThat(descriptor.supportedTimeframes()).containsExactly("M5", "M15", "H1");
        assertThat(descriptor.requiredContextTimeframes()).isEmpty();
        assertThat(descriptor.optionalContextTimeframes()).isEmpty();
        assertThat(descriptor.contextTimeframeRules()).containsExactly(
                new StrategyContextTimeframeRule("M5", "M15", true),
                new StrategyContextTimeframeRule("M15", "H1", true),
                new StrategyContextTimeframeRule("H1", "D1", true)
        );
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("riskFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.003");
        assertThat(validation.effectiveParameters().string("horizonMode", "")).isEqualTo("SWING");
        assertThat(validation.effectiveParameters().decimal("initialTargetR", BigDecimal.ZERO)).isEqualByComparingTo("3.00");
        assertThat(validation.effectiveParameters().bool("allowOvernight", false)).isTrue();
    }

    @Test
    void providersAreRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers)
                .contains(
                        DoflamingoMomentumV5IntradayStrategyProvider.STRATEGY_ID,
                        DoflamingoMomentumV5SwingStrategyProvider.STRATEGY_ID
                );
    }

    @Test
    void intradayEmitsLongEntryWithStructuredThoughtEvidence() {
        TradeIntentStrategy strategy = intradayStrategy(Map.of());
        List<BarEvent> primary = momentumBars("M15", 28, Instant.parse("2026-04-12T21:30:00Z"), true);
        List<BarEvent> h1 = momentumBars("H1", 28, Instant.parse("2026-04-10T03:45:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(reasoningContext(primary, Map.of("H1", h1)));

        assertThat(result.tradeSignals()).as("diagnostics=%s reasoning=%s", result.diagnostics(), result.reasoningEvidence()).hasSize(1);
        assertThat(result.tradeIntents()).as("diagnostics=%s reasoning=%s", result.diagnostics(), result.reasoningEvidence()).hasSize(1);
        assertThat(result.diagnostics()).contains("accept:ENTER_LONG");
        assertThat(result.reasoningEvidence()).extracting("conditionId")
                .contains("context-bias", "cloud-structure", "momentum-confirmation", "participation", "risk-location");
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.sizing().riskFraction()).isEqualByComparingTo("0.0050");
        assertThat(intent.exit().stop().type()).isEqualTo(StrategyExitRuleType.PERCENT);
        assertThat(intent.exit().target().type()).isEqualTo(StrategyExitRuleType.RR);
        assertThat(intent.reason().tags()).contains("doflamingo", "v5-beta", "intraday", "momentum", "entry", "lifecycle");
        assertThat(intent.reason().evidence()).contains(
                "eventType=ENTER_LONG",
                "horizonMode=INTRADAY",
                "primaryTimeframe=M15",
                "contextTimeframe=H1",
                "contextCloudBias=BULLISH"
        );
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "doflamingo-v5.context-bias",
                        "doflamingo-v5.cloud-structure",
                        "doflamingo-v5.ema-structure",
                        "doflamingo-v5.momentum-confirmation",
                        "doflamingo-v5.volume-pulse",
                        "doflamingo-v5.stop-quality",
                        "doflamingo-v5.confidence-threshold"
                );
        assertThat(intent.confidence().value()).isGreaterThanOrEqualTo(new BigDecimal("0.6500"));
    }

    @Test
    void intradayUsesMappedContextForSelectedPrimaryTimeframe() {
        TradeIntentStrategy strategy = intradayStrategy(Map.of());
        List<BarEvent> primary = momentumBars("M5", 28, Instant.parse("2026-04-13T03:00:00Z"), true);
        List<BarEvent> m15 = momentumBars("M15", 28, Instant.parse("2026-04-12T22:30:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(context(primary, Map.of("M15", m15)));

        assertThat(result.tradeIntents()).as("diagnostics=%s", result.diagnostics()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().reason().evidence()).contains(
                "primaryTimeframe=M5",
                "contextTimeframe=M15",
                "contextCloudBias=BULLISH"
        );
    }

    @Test
    void intradayDoesNotReturnThoughtEvidenceForOrdinaryBacktestContext() {
        TradeIntentStrategy strategy = intradayStrategy(Map.of());
        List<BarEvent> primary = momentumBars("M15", 28, Instant.parse("2026-04-12T21:30:00Z"), true);
        List<BarEvent> h1 = momentumBars("H1", 28, Instant.parse("2026-04-10T03:45:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(context(primary, Map.of("H1", h1)));

        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.reasoningEvidence()).isEmpty();
        assertThat(result.currentPhase()).isBlank();
    }

    @Test
    void intradayExplainsMissingContextInsteadOfSilentlyRejecting() {
        TradeIntentStrategy strategy = intradayStrategy(Map.of());
        List<BarEvent> primary = momentumBars("M15", 28, Instant.parse("2026-04-12T21:30:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(reasoningContext(primary, Map.of()));

        assertThat(result.tradeIntents()).isEmpty();
        assertThat(result.diagnostics()).anyMatch(value -> value.contains("reject:contextBiasFailed"));
        assertThat(result.reasoningEvidence()).filteredOn(evidence -> evidence.conditionId().equals("context-bias"))
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.passed()).isFalse());
    }

    @Test
    void lifecyclePrioritizesEodExitThenScaleOutForIntradayPositions() {
        TradeIntentStrategy eodStrategy = intradayStrategy(Map.of("forceExitTime", "09:45"));
        List<BarEvent> primary = momentumBars("M15", 28, Instant.parse("2026-04-12T21:30:00Z"), true);
        List<BarEvent> h1 = momentumBars("H1", 28, Instant.parse("2026-04-10T03:45:00Z"), true);

        StrategyIntentResult eod = eodStrategy.onBarIntent(context(primary, Map.of("H1", h1),
                position(PositionSide.LONG, 8, 1.40d, 0, 0, 3.0d, 0.2d)));

        assertThat(eod.tradeIntents()).hasSize(1);
        assertThat(eod.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(eod.tradeIntents().getFirst().reason().evidence()).contains("eventType=EXIT_LONG_EOD");

        TradeIntentStrategy scaleStrategy = intradayStrategy(Map.of("forceExitTime", "15:15", "scaleOutAtR", "1.00"));
        StrategyIntentResult scaleOut = scaleStrategy.onBarIntent(context(primary, Map.of("H1", h1),
                position(PositionSide.LONG, 8, 1.40d, 0, 0, 3.0d, 0.2d)));

        assertThat(scaleOut.tradeIntents()).hasSize(1);
        assertThat(scaleOut.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.SCALE_OUT_LONG);
        assertThat(scaleOut.tradeIntents().getFirst().sizing().type()).isEqualTo(StrategySizingType.SCALE_FRACTION);
        assertThat(scaleOut.tradeIntents().getFirst().sizing().requestedFraction()).isEqualByComparingTo("0.4000");
        assertThat(scaleOut.tradeIntents().getFirst().reason().evidence()).contains("eventType=SCALE_OUT_LONG");
    }

    @Test
    void swingEmitsLongEntryWithDailyPermissionAndSwingHorizon() {
        TradeIntentStrategy strategy = swingStrategy(Map.of(
                "maxEntryAtrFromCloudTop", "30.00",
                "maxEntryAtrFromCloudTopStrongVolume", "40.00",
                "initialAtrStopMultiple", "0.50"
        ));
        List<BarEvent> primary = momentumBars("H1", 32, Instant.parse("2026-04-10T03:45:00Z"), true);
        List<BarEvent> d1 = momentumBars("D1", 32, Instant.parse("2026-03-01T03:45:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(context(primary, Map.of("D1", d1)));

        assertThat(result.tradeSignals()).as("diagnostics=%s reasoning=%s", result.diagnostics(), result.reasoningEvidence()).hasSize(1);
        assertThat(result.tradeIntents()).as("diagnostics=%s reasoning=%s", result.diagnostics(), result.reasoningEvidence()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.sizing().riskFraction()).isEqualByComparingTo("0.0030");
        assertThat(intent.horizon().intendedHorizonLabel()).isEqualTo(org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel.SWING);
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(64);
        assertThat(intent.reason().tags()).contains("doflamingo", "v5-beta", "swing", "momentum", "entry", "lifecycle");
        assertThat(intent.reason().evidence()).contains(
                "eventType=ENTER_LONG",
                "horizonMode=SWING",
                "primaryTimeframe=H1",
                "contextTimeframe=D1",
                "contextCloudBias=BULLISH",
                "overnightRiskAccepted=true"
        );
        assertThat(((StrategyReasoningEvaluator) strategy).currentPhase(context(primary, Map.of("D1", d1)))).isEqualTo("risk");
    }

    @Test
    void swingUsesMappedContextForSelectedPrimaryTimeframe() {
        TradeIntentStrategy strategy = swingStrategy(Map.of(
                "maxEntryAtrFromCloudTop", "30.00",
                "maxEntryAtrFromCloudTopStrongVolume", "40.00",
                "initialAtrStopMultiple", "0.50"
        ));
        List<BarEvent> primary = momentumBars("M15", 32, Instant.parse("2026-04-10T03:45:00Z"), true);
        List<BarEvent> h1 = momentumBars("H1", 32, Instant.parse("2026-04-08T03:45:00Z"), true);

        StrategyIntentResult result = strategy.onBarIntent(context(primary, Map.of("H1", h1)));

        assertThat(result.tradeIntents()).as("diagnostics=%s", result.diagnostics()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().reason().evidence()).contains(
                "primaryTimeframe=M15",
                "contextTimeframe=H1",
                "contextCloudBias=BULLISH"
        );
    }

    private static TradeIntentStrategy intradayStrategy(Map<String, Object> overrides) {
        return (TradeIntentStrategy) new DoflamingoMomentumV5IntradayStrategyProvider()
                .create(new StrategyParameters(compactParams(overrides)), null);
    }

    private static TradeIntentStrategy swingStrategy(Map<String, Object> overrides) {
        return (TradeIntentStrategy) new DoflamingoMomentumV5SwingStrategyProvider()
                .create(new StrategyParameters(compactParams(overrides)), null);
    }

    private static Map<String, Object> compactParams(Map<String, Object> overrides) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ichimokuConversionPeriod", 3);
        params.put("ichimokuBasePeriod", 5);
        params.put("ichimokuSpanBPeriod", 8);
        params.put("ichimokuDisplacement", 1);
        params.put("emaFastPeriod", 3);
        params.put("emaMidPeriod", 5);
        params.put("emaAnchorPeriod", 8);
        params.put("smaSlowPeriod", 12);
        params.put("macdFastPeriod", 3);
        params.put("macdSlowPeriod", 6);
        params.put("macdSignalPeriod", 3);
        params.put("stochRsiPeriod", 5);
        params.put("stochRsiK", 2);
        params.put("stochRsiD", 2);
        params.put("atrPeriod", 3);
        params.put("volumeLookbackBars", 3);
        params.put("primaryWarmupBars", 12);
        params.put("contextWarmupBars", 12);
        params.put("volumePulseMultiple", "1.05");
        params.put("strongVolumePulseMultiple", "1.20");
        params.put("atrExpansionMultiple", "1.00");
        params.put("minCloudThicknessAtr", "0.01");
        params.put("minFutureSpreadAtr", "0.01");
        params.put("minStopPct", "0.05");
        params.put("maxStopPct", "10.00");
        params.put("minConfidence", "0.60");
        params.put("maxEntryAtrFromCloudTop", "8.00");
        params.put("maxEntryAtrFromCloudTopStrongVolume", "12.00");
        params.put("emitDiagnostics", true);
        params.putAll(overrides);
        return params;
    }

    private static StrategyExecutionContext context(List<BarEvent> primary, Map<String, List<BarEvent>> contextBars) {
        return context(primary, contextBars, StrategyInstrumentPosition.flat());
    }

    private static StrategyExecutionContext reasoningContext(List<BarEvent> primary, Map<String, List<BarEvent>> contextBars) {
        return context(primary, contextBars, StrategyInstrumentPosition.flat(), StrategyReasoningCollectionMode.ENABLED);
    }

    private static StrategyExecutionContext context(
            List<BarEvent> primary,
            Map<String, List<BarEvent>> contextBars,
            StrategyInstrumentPosition position
    ) {
        return context(primary, contextBars, position, StrategyReasoningCollectionMode.DISABLED);
    }

    private static StrategyExecutionContext context(
            List<BarEvent> primary,
            Map<String, List<BarEvent>> contextBars,
            StrategyInstrumentPosition position,
            StrategyReasoningCollectionMode reasoningCollectionMode
    ) {
        return new StrategyExecutionContext(
                METADATA,
                primary.getLast(),
                primary,
                MarketDataVisibilitySnapshot.trusted(primary.getLast().occurredAt(), primary.getLast().timeframe(), contextBars, List.of()),
                MarketContextSnapshot.empty(),
                position,
                StrategyPortfolioState.empty(),
                reasoningCollectionMode
        );
    }

    private static StrategyInstrumentPosition position(
            PositionSide side,
            int barsHeld,
            double currentR,
            int scaleInCount,
            int scaleOutCount,
            double maxFavorablePct,
            double maxAdversePct
    ) {
        return new StrategyInstrumentPosition(
                true,
                side,
                decimal(1.0d),
                decimal(100.0d),
                Instant.parse("2026-04-11T09:15:00Z"),
                barsHeld,
                decimal(currentR),
                decimal(currentR),
                decimal(currentR),
                decimal(2.0d),
                scaleInCount,
                scaleOutCount,
                "",
                decimal(101.0d),
                decimal(maxFavorablePct),
                decimal(maxAdversePct)
        );
    }

    private static List<BarEvent> momentumBars(String timeframe, int count, Instant start, boolean bullish) {
        List<BarEvent> bars = new ArrayList<>();
        double close = bullish ? 100.0d : 140.0d;
        for (int index = 0; index < count; index++) {
            double acceleration = Math.max(0, index - 8) * Math.max(0, index - 8) * 0.045d;
            double step = 0.45d + acceleration;
            close = bullish ? close + step : close - step;
            double open = bullish ? close - 0.35d : close + 0.35d;
            double high = Math.max(open, close) + 0.45d + (index > count - 4 ? 0.30d : 0.0d);
            double low = Math.min(open, close) - 0.45d - (index > count - 4 ? 0.15d : 0.0d);
            BigDecimal volume = BigDecimal.valueOf(index == count - 1 ? 2500L : 1000L + index * 10L);
            bars.add(bar(index, timeframe, start, open, high, low, close, volume));
        }
        return bars;
    }

    private static BarEvent bar(
            int index,
            String timeframe,
            Instant start,
            double open,
            double high,
            double low,
            double close,
            BigDecimal volume
    ) {
        long seconds = switch (timeframe) {
            case "M1" -> 60L;
            case "M5" -> 300L;
            case "M15" -> 900L;
            case "H1" -> 3600L;
            case "H4" -> 14400L;
            case "D1" -> 86400L;
            default -> 900L;
        };
        return new BarEvent(
                "1.0.0",
                new EventId("bar-%03d".formatted(index + 1)),
                INSTRUMENT,
                start.plusSeconds(seconds * index),
                timeframe,
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), volume),
                new SourceRef(SourceType.ADAPTER, "doflamingo-v5-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
