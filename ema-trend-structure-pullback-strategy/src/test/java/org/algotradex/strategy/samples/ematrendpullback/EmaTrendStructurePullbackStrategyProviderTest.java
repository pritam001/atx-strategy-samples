package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.Direction;
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
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyPortfolioState;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyStateEnvelope;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.strategy.simulation.SimulationStepper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EmaTrendStructurePullbackStrategyProviderTest {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-ema-trend-pullback-test"),
            new ReplayId("replay-ema-trend-pullback-test"),
            ReplayMode.FULL_RUN
    );

    private final EmaTrendStructurePullbackStrategyProvider provider = new EmaTrendStructurePullbackStrategyProvider();

    @Test
    void exposesDescriptorCapabilitiesAndDefaultParameters() {
        var descriptor = provider.descriptor();
        var validation = provider.validate(StrategyParameters.empty());

        assertThat(descriptor.identity().strategyId()).isEqualTo("ema-trend-structure-pullback-v2");
        assertThat(descriptor.identity().strategyVersion()).isEqualTo("2.0.0");
        assertThat(descriptor.displayName()).isEqualTo("EMA Trend Structure Pullback Lifecycle");
        assertThat(descriptor.providerId()).isEqualTo("atx-strategy-samples");
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
        assertThat(descriptor.parameterSchema().parameters()).hasSize(40);
        assertThat(descriptor.suggestedChartStudies()).hasSize(4);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveParameters().decimal("riskFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.01");
        assertThat(validation.effectiveParameters().integer("maxHoldingBars", 0)).isEqualTo(48);
        assertThat(validation.effectiveParameters().integer("staleBars", 0)).isEqualTo(16);
        assertThat(validation.effectiveParameters().string("stopMode", "")).isEqualTo("EMA50_OR_ATR");
        assertThat(validation.effectiveParameters().decimal("scaleOutFraction", BigDecimal.ZERO)).isEqualByComparingTo("0.50");
        assertThat(validation.effectiveParameters().bool("enableScaleIn", true)).isFalse();
        assertThat(validation.effectiveParameters().bool("breakEvenAfterScaleOut", false)).isTrue();
    }

    @Test
    void rejectsInvalidParameters() {
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("fastEmaPeriod", 50);
        supplied.put("mediumEmaPeriod", 20);
        supplied.put("slowEmaPeriod", 200);
        supplied.put("pullbackMinBars", 10);
        supplied.put("pullbackLookbackBars", 8);
        supplied.put("compressedSeparationThresholdPct", "1.50");
        supplied.put("expandingSeparationThresholdPct", "1.00");
        supplied.put("idealDistanceFromFastEmaPct", "4.00");
        supplied.put("maxDistanceFromFastEmaPct", "3.00");
        supplied.put("staleBars", 48);
        supplied.put("maxHoldingBars", 48);
        supplied.put("minStopPct", "3.00");
        supplied.put("maxStopPct", "2.00");
        var validation = provider.validate(new StrategyParameters(supplied));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field")
                .contains("mediumEmaPeriod", "pullbackMinBars", "expandingSeparationThresholdPct",
                        "idealDistanceFromFastEmaPct", "staleBars", "minStopPct");
    }

    @Test
    void rejectsInvalidEnumParameters() {
        var validation = provider.validate(new StrategyParameters(Map.of("stopMode", "MANUAL")));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting("field").contains("stopMode");
    }

    @Test
    void rejectsInvalidLifecycleParametersAndAllowsCompactReplayPeriods() {
        var invalid = provider.validate(new StrategyParameters(Map.of(
                "scaleOutFraction", "0.00",
                "scaleInFraction", "1.25",
                "scaleOutAtR", "0.00",
                "scaleInAtR", "-0.10",
                "maxScaleIns", -1
        )));

        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.issues()).extracting("field")
                .contains("scaleOutFraction", "scaleInFraction", "scaleOutAtR", "scaleInAtR", "maxScaleIns");

        var compact = provider.validate(new StrategyParameters(compactParameters(Map.of())));

        assertThat(compact.valid()).isTrue();
        assertThat(compact.effectiveParameters().integer("fastEmaPeriod", 0)).isEqualTo(3);
        assertThat(compact.effectiveParameters().integer("mediumEmaPeriod", 0)).isEqualTo(5);
        assertThat(compact.effectiveParameters().integer("slowEmaPeriod", 0)).isEqualTo(8);
    }

    @Test
    void exposesEffectiveChartStudiesFromParameters() {
        var studies = provider.effectiveChartStudies(new StrategyParameters(Map.of(
                "fastEmaPeriod", 10,
                "mediumEmaPeriod", 30,
                "slowEmaPeriod", 100
        )));

        assertThat(studies).hasSize(4);
        assertThat(studies.get(0).indicatorId()).isEqualTo("ema");
        assertThat(studies.get(0).role()).isEqualTo("fast-ema");
        assertThat(studies.get(0).parameters()).containsEntry("period", 10);
        assertThat(studies.get(1).parameters()).containsEntry("period", 30);
        assertThat(studies.get(2).parameters()).containsEntry("period", 100);
        assertThat(studies.get(3).indicatorId()).isEqualTo("ema-trend-structure");
    }

    @Test
    void isRegisteredForServiceLoaderDiscovery() {
        var providers = ServiceLoader.load(StrategyProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(StrategyProvider::descriptor)
                .map(descriptor -> descriptor.identity().strategyId())
                .toList();

        assertThat(providers).contains(EmaTrendStructurePullbackStrategyProvider.STRATEGY_ID);
    }

    @Test
    void computesDeterministicEmaWithSmaSeed() {
        double[] ema = EmaTrendStructurePullbackStrategy.emaSeries(bars("10.00", "11.00", "12.00", "13.00"), 3);

        assertThat(ema[0]).isNaN();
        assertThat(ema[1]).isNaN();
        assertThat(ema[2]).isEqualTo(11.0d);
        assertThat(ema[3]).isEqualTo(12.0d);
    }

    @Test
    void emitsNoSignalBeforeDefaultReadiness() {
        var strategy = provider.create(StrategyParameters.empty(), null);

        assertThat(strategy.onBar(context(trendBars(204, 100.0d, 0.25d)))).isEmpty();
    }

    @Test
    void emitsBullishPullbackSignalAndEntryIntent() {
        TradeIntentStrategy strategy = compactStrategy(Map.of());
        StrategyIntentResult result = firstIntent(strategy, bullishPullbackBars());

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeIntents()).hasSize(1);
        TradeSignal signal = result.tradeSignals().getFirst();
        var intent = result.tradeIntents().getFirst();
        assertThat(signal.direction()).isEqualTo(Direction.LONG);
        assertThat(signal.setupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(signal.tags().values()).contains(
                "strategy_family=ema_trend_structure_pullback",
                "setup=bullish_pullback_continuation",
                "formula_version=ema-trend-structure-pullback-v2"
        );
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.entry().type()).isEqualTo(StrategyEntryType.MARKET_NEXT_OPEN);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.RISK_FRACTION);
        assertThat(intent.sizing().riskFraction()).isEqualByComparingTo("0.0100");
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(48);
        assertThat(intent.exit().stop().type()).isEqualTo(StrategyExitRuleType.PERCENT);
        assertThat(intent.sourceBarId()).isEqualTo("bar-016");
        assertThat(intent.reason().tags()).contains("ema-trend-structure", "v2", "lifecycle", "entry", "pullback", "risk", "confidence");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "ema-v2.bullish-stack",
                        "ema-v2.pullback-real",
                        "ema-v2.pullback-held-ema50",
                        "ema-v2.stop-distance-acceptable",
                        "ema-v2.confidence-threshold"
                );
        assertThat(intent.confidence().value()).isGreaterThanOrEqualTo(new BigDecimal("0.7000"));
    }

    @Test
    void resumableStateMatchesFreshReplayAcrossCheckpoint() {
        List<BarEvent> bars = bullishPullbackBars();
        int checkpointIndex = Math.max(1, bars.size() / 2);
        TradeIntentStrategy original = compactStrategy(Map.of());
        replayUntil(original, bars, checkpointIndex);
        ResumableStrategy originalState = (ResumableStrategy) original;
        StrategyStateEnvelope checkpoint = originalState.initialState(
                EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                "variant-a",
                new StrategyParameters(compactParameters(Map.of()))
        );

        TradeIntentStrategy restored = compactStrategy(Map.of());
        ((ResumableStrategy) restored).resumeFromState(originalState.serialise(checkpoint));
        StrategyIntentResult restoredFinal = replayRange(restored, bars, checkpointIndex, bars.size());

        TradeIntentStrategy fresh = compactStrategy(Map.of());
        StrategyIntentResult freshFinal = replayRange(fresh, bars, 0, bars.size());

        assertThat(restoredFinal).usingRecursiveComparison().isEqualTo(freshFinal);
        assertThat(((ResumableStrategy) restored).snapshotState()).isEqualTo(((ResumableStrategy) fresh).snapshotState());
    }

    @Test
    void simulationStepperStateMatchesFreshReplayAcrossSerializedCheckpoint() {
        List<BarEvent> bars = bullishPullbackBars();
        int checkpointIndex = Math.max(1, bars.size() / 2);
        StrategyParameters parameters = new StrategyParameters(compactParameters(Map.of()));

        SimulationStepper checkpointed = stepper(parameters);
        for (int index = 0; index < checkpointIndex; index++) {
            checkpointed.step(bars.get(index), MarketDataVisibilitySnapshot.empty(), MarketContextSnapshot.empty());
        }
        StrategyStateEnvelope checkpoint = checkpointed.checkpoint("variant-a");

        SimulationStepper restored = stepper(parameters);
        restored.resumeFromSerialised(checkpointed.serialise(checkpoint), parameters);
        var restoredFinal = stepRange(restored, bars, checkpointIndex, bars.size());

        SimulationStepper fresh = stepper(parameters);
        var freshFinal = stepRange(fresh, bars, 0, bars.size());

        assertThat(restoredFinal.result()).usingRecursiveComparison().isEqualTo(freshFinal.result());
        assertThat(restored.currentState().strategyState()).isEqualTo(fresh.currentState().strategyState());
        assertThat(restored.currentState().resolvedParamsHash()).isEqualTo(fresh.currentState().resolvedParamsHash());
    }

    @Test
    void entryConfidenceChangesWithStopEvidence() {
        var clamped = firstIntent(compactStrategy(Map.of("maxStopPct", "3.00")), bullishPullbackBars())
                .tradeIntents()
                .getFirst();
        var unclamped = firstIntent(compactStrategy(Map.of("maxStopPct", "5.00")), bullishPullbackBars())
                .tradeIntents()
                .getFirst();

        assertThat(clamped.confidence().value()).isLessThan(unclamped.confidence().value());
        assertThat(clamped.reason().evidence()).contains("stopPct=3.0000");
        assertThat(unclamped.reason().evidence())
                .anyMatch(value -> value.startsWith("stopPct=3.") && !value.equals("stopPct=3.0000"));
        assertThat(unclamped.reason().evidence()).contains("stopMode=EMA50_OR_ATR");
    }

    @Test
    void emitsBullishTransitionBreakoutEntryIntent() {
        TradeIntentStrategy strategy = compactStrategy(Map.of(
                "flatSlopeThresholdPct", "0.01",
                "compressedSeparationThresholdPct", "0.20"
        ));

        StrategyIntentResult result = firstIntent(strategy, bullishTransitionBars());

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals().getFirst().setupType()).isEqualTo(SetupType.CONTINUATION);
        assertThat(result.tradeSignals().getFirst().tags().values()).contains("setup=bullish_transition_breakout");
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
    }

    @Test
    void defaultShortsDisabledAndConfigurableBearishPullbackEmitsEntryIntent() {
        List<BarEvent> history = bearishPullbackBars();
        assertThat(firstIntent(compactStrategy(Map.of("allowShorts", false)), history)).isNull();

        StrategyIntentResult result = firstIntent(compactStrategy(Map.of("allowShorts", true)), history);

        assertThat(result).isNotNull();
        assertThat(result.tradeSignals()).hasSize(1);
        assertThat(result.tradeSignals().getFirst().direction()).isEqualTo(Direction.SHORT);
        assertThat(result.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.ENTER_SHORT);
        assertThat(result.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("ema-v2.bearish-stack");
    }

    @Test
    void suppressesEntryWhilePlatformPositionExistsAndDuringCooldown() {
        TradeIntentStrategy positioned = compactStrategy(Map.of("enableScaleOut", false));
        var positionedResult = positioned.onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 2, 0.40d, 0, 0, 1.0d, 0.0d)));
        assertThat(positionedResult.tradeSignals()).isEmpty();
        assertThat(positionedResult.tradeIntents()).isEmpty();

        TradeIntentStrategy cooldown = compactStrategy(Map.of());
        List<BarEvent> continuation = new ArrayList<>(bullishPullbackBars());
        continuation.add(upBar(continuation.size(), 113.20d));
        continuation.add(upBar(continuation.size(), 113.70d));
        continuation.add(upBar(continuation.size(), 114.10d));

        assertThat(replaySignals(cooldown, continuation)).hasSize(1);
    }

    @Test
    void lifecycleScaleOutUsesConfiguredScaleFractionAndDoesNotRepeat() {
        TradeIntentStrategy strategy = compactStrategy(Map.of(
                "scaleOutAtR", "1.0",
                "scaleOutFraction", "0.50"
        ));
        List<BarEvent> bars = bullishPullbackBars();

        var result = strategy.onBarIntent(context(bars, position(PositionSide.LONG, 6, 1.20d, 0, 0, 2.0d, 0.2d)));

        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.SCALE_OUT_LONG);
        assertThat(intent.sizing().type()).isEqualTo(StrategySizingType.SCALE_FRACTION);
        assertThat(intent.sizing().requestedFraction()).isEqualByComparingTo("0.5000");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("ema-v2.scale-out-r-multiple", "ema-v2.scale-out-favorable-excursion", "ema-v2.scale-out-trend-valid");
        assertThat(intent.reason().tags()).contains("pullback");
        assertLifecycleEvidence(intent.reason().evidence());

        TradeIntentStrategy alreadyScaled = compactStrategy(Map.of("scaleOutAtR", "1.0"));
        var repeat = alreadyScaled.onBarIntent(context(bars, position(PositionSide.LONG, 6, 1.20d, 0, 1, 2.0d, 0.2d)));
        assertThat(repeat.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.SCALE_OUT_LONG);
    }

    @Test
    void lifecycleHardAndTimedExitsUseFullCloseSizing() {
        TradeIntentStrategy hardExitStrategy = compactStrategy(Map.of("enableScaleOut", false));
        List<BarEvent> broken = new ArrayList<>(bullishPullbackBars());
        broken.add(bar(broken.size(), 112.20d, 112.40d, 103.80d, 104.20d));

        var hardExit = hardExitStrategy.onBarIntent(context(broken, position(PositionSide.LONG, 5, -0.20d, 0, 0, 0.4d, 2.0d)));

        assertThat(hardExit.tradeIntents()).hasSize(1);
        var hardIntent = hardExit.tradeIntents().getFirst();
        assertThat(hardIntent.action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(hardIntent.sizing().type()).isEqualTo(StrategySizingType.CLOSE_FRACTION);
        assertThat(hardIntent.sizing().requestedFraction()).isEqualByComparingTo("1.0000");
        assertThat(hardIntent.reason().conditions()).extracting("conditionId").contains("ema-v2.exit-structure-break");
        assertThat(hardIntent.reason().tags()).contains("pullback");
        assertLifecycleEvidence(hardIntent.reason().evidence());

        TradeIntentStrategy staleStrategy = compactStrategy(Map.of("enableScaleOut", false, "staleBars", 4, "staleMinR", "0.25"));
        var staleExit = staleStrategy.onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 6, 0.10d, 0, 0, 0.5d, 0.0d)));
        assertThat(staleExit.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(staleExit.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("ema-v2.exit-stale-bars", "ema-v2.exit-stale-r");

        TradeIntentStrategy maxStrategy = compactStrategy(Map.of("enableScaleOut", false, "maxHoldingBars", 8, "staleBars", 4));
        var maxExit = maxStrategy.onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 8, 2.00d, 0, 0, 4.0d, 0.0d)));
        assertThat(maxExit.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("ema-v2.exit-max-holding");
    }

    @Test
    void compressionChopPostScaleAndBreakEvenExitsEmitLifecycleConditions() {
        TradeIntentStrategy compression = compactStrategy(Map.of("enableScaleOut", false));
        var compressionExit = compression.onBarIntent(context(flatBars(24, 100.0d), position(PositionSide.LONG, 5, -0.10d, 0, 0, 0.0d, 1.0d)));
        assertThat(compressionExit.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("ema-v2.exit-compression", "ema-v2.exit-chop");

        TradeIntentStrategy postScale = compactStrategy(Map.of("enableScaleOut", false, "trailAfterScaleOut", true));
        List<BarEvent> weak = postScaleTrailOnlyBars();
        var postScaleExit = postScale.onBarIntent(context(weak, position(PositionSide.LONG, 7, 0.20d, 0, 1, 2.0d, 0.5d)));
        assertThat(postScaleExit.tradeIntents().getFirst().reason().conditions())
                .filteredOn(condition -> condition.conditionId().equals("ema-v2.exit-post-scale-trail"))
                .singleElement()
                .satisfies(condition -> assertThat(condition.passed()).isTrue());
        assertThat(postScaleExit.tradeIntents().getFirst().reason().summary()).contains("post-scale trailing weakness");
        assertThat(postScaleExit.tradeIntents().getFirst().reason().evidence())
                .contains("emaStack=MIXED_STACK", "structureBreak=false", "mixedStack=true", "mixedStackLosing=false",
                        "postScaleWeakness=true", "breakEvenFailure=false");

        TradeIntentStrategy breakEven = compactStrategy(Map.of("enableScaleOut", false, "breakEvenAfterScaleOut", true));
        var breakEvenExit = breakEven.onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 7, -0.01d, 0, 1, 2.0d, 0.5d)));
        assertThat(breakEvenExit.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(breakEvenExit.tradeIntents().getFirst().reason().summary()).contains("breakeven failure");
        assertThat(breakEvenExit.tradeIntents().getFirst().reason().evidence())
                .contains("postScaleWeakness=false", "breakEvenFailure=true");
    }

    @Test
    void lifecycleExitTogglesSuppressTheirSpecificExitPaths() {
        var compressedBars = trendBars(16, 100.0d, 0.05d);
        Map<String, Object> compressedOnly = Map.of(
                "enableScaleOut", false,
                "compressedSeparationThresholdPct", "5.00",
                "expandingSeparationThresholdPct", "6.00",
                "exitOnChop", false
        );
        var compressionExit = compactStrategy(compressedOnly)
                .onBarIntent(context(compressedBars, position(PositionSide.LONG, 5, -0.10d, 0, 0, 0.2d, 0.4d)));
        assertThat(compressionExit.tradeIntents()).hasSize(1);
        assertThat(compressionExit.tradeIntents().getFirst().reason().evidence()).contains("compressionExit=true");

        var compressionSuppressed = compactStrategy(withOverride(compressedOnly, "exitOnCompression", false))
                .onBarIntent(context(compressedBars, position(PositionSide.LONG, 5, -0.10d, 0, 0, 0.2d, 0.4d)));
        assertThat(compressionSuppressed.tradeIntents()).isEmpty();

        Map<String, Object> chopOnly = Map.of(
                "enableScaleOut", false,
                "exitOnCompression", false,
                "chopCrossCountThreshold", 1
        );
        var chopExit = compactStrategy(chopOnly)
                .onBarIntent(context(bullishPullbackWithCrossBars(), position(PositionSide.LONG, 5, -0.10d, 0, 0, 0.2d, 0.4d)));
        assertThat(chopExit.tradeIntents()).hasSize(1);
        assertThat(chopExit.tradeIntents().getFirst().reason().evidence()).contains("chopExit=true");

        var chopSuppressed = compactStrategy(withOverride(chopOnly, "exitOnChop", false))
                .onBarIntent(context(bullishPullbackWithCrossBars(), position(PositionSide.LONG, 5, -0.10d, 0, 0, 0.2d, 0.4d)));
        assertThat(chopSuppressed.tradeIntents()).isEmpty();

        List<BarEvent> weak = postScaleTrailOnlyBars();
        var trailSuppressed = compactStrategy(Map.of(
                        "enableScaleOut", false,
                        "trailAfterScaleOut", false
                ))
                .onBarIntent(context(weak, position(PositionSide.LONG, 7, 0.20d, 0, 1, 2.0d, 0.5d)));
        assertThat(trailSuppressed.tradeIntents()).isEmpty();

        TradeIntentStrategy breakEvenOff = compactStrategy(Map.of(
                "enableScaleOut", false,
                "breakEvenAfterScaleOut", false
        ));
        var breakEvenSuppressed = breakEvenOff.onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 7, -0.01d, 0, 1, 2.0d, 0.5d)));
        assertThat(breakEvenSuppressed.tradeIntents()).isEmpty();
    }

    @Test
    void lifecycleCoversMixedOppositeAndShortSideExitsAndScaleOuts() {
        var mixedWhileLosing = compactStrategy(Map.of(
                        "enableScaleOut", false,
                        "exitOnCompression", false,
                        "exitOnChop", false
                ))
                .onBarIntent(context(mixedStackBars(), position(PositionSide.LONG, 5, -0.20d, 0, 0, 0.2d, 1.2d)));
        assertThat(mixedWhileLosing.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(mixedWhileLosing.tradeIntents().getFirst().reason().evidence())
                .contains(
                        "emaStack=MIXED_STACK",
                        "structureBreak=true",
                        "closeBeyondMedium=false",
                        "mixedStack=true",
                        "mixedStackLosing=true",
                        "oppositeStack=false"
                );

        var longOnOppositeStack = compactStrategy(Map.of("enableScaleOut", false))
                .onBarIntent(context(bearishPullbackBars(), position(PositionSide.LONG, 5, -0.30d, 0, 0, 0.2d, 1.2d)));
        assertThat(longOnOppositeStack.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(longOnOppositeStack.tradeIntents().getFirst().reason().evidence()).contains("structureBreak=true");

        var shortExit = compactStrategy(Map.of("allowShorts", true, "enableScaleOut", false))
                .onBarIntent(context(bullishPullbackBars(), position(PositionSide.SHORT, 5, -0.30d, 0, 0, 0.2d, 1.2d)));
        assertThat(shortExit.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_SHORT);

        var shortScaleOut = compactStrategy(Map.of("allowShorts", true, "scaleOutAtR", "1.0"))
                .onBarIntent(context(bearishPullbackBars(), position(PositionSide.SHORT, 6, 1.20d, 0, 0, 2.0d, 0.2d)));
        assertThat(shortScaleOut.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.SCALE_OUT_SHORT);
    }

    @Test
    void scaleOutRequiresPositiveMfeAndUnbrokenTrend() {
        var noMfe = compactStrategy(Map.of("scaleOutAtR", "1.0"))
                .onBarIntent(context(bullishPullbackBars(), position(PositionSide.LONG, 6, 1.20d, 0, 0, 0.0d, 0.2d)));
        assertThat(noMfe.tradeIntents()).isEmpty();

        List<BarEvent> broken = new ArrayList<>(bullishPullbackBars());
        broken.add(bar(broken.size(), 112.20d, 112.40d, 103.80d, 104.20d));
        var brokenTrend = compactStrategy(Map.of("scaleOutAtR", "1.0"))
                .onBarIntent(context(broken, position(PositionSide.LONG, 6, 1.20d, 0, 0, 2.0d, 0.2d)));
        assertThat(brokenTrend.tradeIntents()).extracting("action").doesNotContain(StrategyTradeAction.SCALE_OUT_LONG);
        assertThat(brokenTrend.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
    }

    @Test
    void scaleInIsDisabledByDefaultAndRequiresProfitableRenewedSetup() {
        List<BarEvent> bars = bullishPullbackBars();
        var disabled = compactStrategy(Map.of("enableScaleOut", false))
                .onBarIntent(context(bars, position(PositionSide.LONG, 6, 0.80d, 0, 0, 2.0d, 0.2d)));
        assertThat(disabled.tradeIntents()).isEmpty();

        var enabled = compactStrategy(Map.of("enableScaleOut", false, "enableScaleIn", true, "scaleInAtR", "0.50"))
                .onBarIntent(context(bars, position(PositionSide.LONG, 6, 0.80d, 0, 0, 2.0d, 0.2d)));
        assertThat(enabled.tradeIntents()).hasSize(1);
        assertThat(enabled.tradeIntents().getFirst().action()).isEqualTo(StrategyTradeAction.SCALE_IN_LONG);
        assertThat(enabled.tradeIntents().getFirst().sizing().type()).isEqualTo(StrategySizingType.SCALE_FRACTION);
        assertThat(enabled.tradeIntents().getFirst().reason().conditions()).extracting("conditionId")
                .contains("ema-v2.scale-in-r-positive", "ema-v2.scale-in-count-available",
                        "ema-v2.scale-in-renewed-pullback", "ema-v2.scale-in-confidence-threshold");

        var losing = compactStrategy(Map.of("enableScaleOut", false, "enableScaleIn", true))
                .onBarIntent(context(bars, position(PositionSide.LONG, 6, -0.10d, 0, 0, 0.2d, 1.0d)));
        assertThat(losing.tradeIntents()).isEmpty();

        var maxed = compactStrategy(Map.of("enableScaleOut", false, "enableScaleIn", true, "maxScaleIns", 1))
                .onBarIntent(context(bars, position(PositionSide.LONG, 6, 0.80d, 1, 0, 2.0d, 0.2d)));
        assertThat(maxed.tradeIntents()).isEmpty();
    }

    @Test
    void scaleInIsBlockedByExplicitChopAndCompressionChecks() {
        var choppy = compactStrategy(Map.of(
                        "enableScaleOut", false,
                        "enableScaleIn", true,
                        "scaleInAtR", "0.50",
                        "chopCrossCountThreshold", 1
                ))
                .onBarIntent(context(bullishPullbackWithCrossBars(), position(PositionSide.LONG, 6, 0.80d, 0, 0, 2.0d, 0.2d)));
        assertThat(choppy.tradeIntents()).isEmpty();

        var compressed = compactStrategy(Map.of(
                        "enableScaleOut", false,
                        "enableScaleIn", true,
                        "scaleInAtR", "0.50",
                        "compressedSeparationThresholdPct", "5.00",
                        "expandingSeparationThresholdPct", "6.00"
                ))
                .onBarIntent(context(trendBars(16, 100.0d, 0.05d), position(PositionSide.LONG, 6, 0.80d, 0, 0, 2.0d, 0.2d)));
        assertThat(compressed.tradeIntents()).isEmpty();
    }

    private TradeIntentStrategy compactStrategy(Map<String, Object> overrides) {
        return (TradeIntentStrategy) provider.create(new StrategyParameters(compactParameters(overrides)), null);
    }

    private SimulationStepper stepper(StrategyParameters parameters) {
        return new SimulationStepper(
                List.of((TradeSignalStrategy) provider.create(parameters, null)),
                METADATA,
                256,
                java.time.Clock.systemUTC(),
                EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                parameters
        );
    }

    private static org.algotradex.platform.core.strategy.simulation.SimulationStepResult stepRange(
            SimulationStepper stepper,
            List<BarEvent> bars,
            int start,
            int endExclusive
    ) {
        org.algotradex.platform.core.strategy.simulation.SimulationStepResult result = null;
        for (int index = start; index < endExclusive; index++) {
            result = stepper.step(bars.get(index), MarketDataVisibilitySnapshot.empty(), MarketContextSnapshot.empty());
        }
        return result;
    }

    private static Map<String, Object> compactParameters(Map<String, Object> overrides) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fastEmaPeriod", 3);
        params.put("mediumEmaPeriod", 5);
        params.put("slowEmaPeriod", 8);
        params.put("slopeLookbackBars", 2);
        params.put("flatSlopeThresholdPct", "0.05");
        params.put("compressedSeparationThresholdPct", "0.50");
        params.put("expandingSeparationThresholdPct", "1.50");
        params.put("chopCrossLookbackBars", 6);
        params.put("chopCrossCountThreshold", 5);
        params.put("pullbackLookbackBars", 8);
        params.put("pullbackMinBars", 2);
        params.put("emaTouchTolerancePct", "0.35");
        params.put("maxDistanceFromFastEmaPct", "3.00");
        params.put("idealDistanceFromFastEmaPct", "2.00");
        params.put("maxDistanceFromMediumEmaPct", "8.00");
        params.put("priorBreakoutLookbackBars", 3);
        params.put("transitionBreakoutLookbackBars", 5);
        params.put("minConfidence", "0.70");
        params.put("allowShorts", false);
        params.put("cooldownBars", 3);
        params.put("riskFraction", "0.01");
        params.put("maxHoldingBars", 48);
        params.put("staleBars", 16);
        params.put("staleMinR", "0.25");
        params.put("atrPeriod", 3);
        params.put("atrStopMultiple", "1.50");
        params.put("minStopPct", "0.50");
        params.put("maxStopPct", "3.00");
        params.putAll(overrides);
        return params;
    }

    private static Map<String, Object> withOverride(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static void assertLifecycleEvidence(List<String> evidence) {
        assertThat(evidence).anyMatch(value -> value.startsWith("stopMode="));
        assertThat(evidence).anyMatch(value -> value.startsWith("currentStopPct="));
        assertThat(evidence).anyMatch(value -> value.startsWith("riskFraction="));
        assertThat(evidence).anyMatch(value -> value.startsWith("maxHoldingBars="));
        assertThat(evidence).anyMatch(value -> value.startsWith("positionBarsHeld="));
        assertThat(evidence).anyMatch(value -> value.startsWith("currentR="));
        assertThat(evidence).anyMatch(value -> value.startsWith("mfeR="));
        assertThat(evidence).anyMatch(value -> value.startsWith("scaleOutCount="));
        assertThat(evidence).anyMatch(value -> value.startsWith("scaleInCount="));
    }

    private static StrategyIntentResult firstIntent(TradeIntentStrategy strategy, List<BarEvent> bars) {
        for (int index = 1; index <= bars.size(); index++) {
            var result = strategy.onBarIntent(context(bars.subList(0, index)));
            if (!result.tradeIntents().isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static void replayUntil(TradeIntentStrategy strategy, List<BarEvent> bars, int endExclusive) {
        replayRange(strategy, bars, 0, endExclusive);
    }

    private static StrategyIntentResult replayRange(TradeIntentStrategy strategy, List<BarEvent> bars, int start, int endExclusive) {
        StrategyIntentResult result = StrategyIntentResult.empty();
        for (int index = start + 1; index <= endExclusive; index++) {
            result = strategy.onBarIntent(context(bars.subList(0, index)));
        }
        return result;
    }

    private static List<TradeSignal> replaySignals(TradeIntentStrategy strategy, List<BarEvent> bars) {
        List<TradeSignal> signals = new ArrayList<>();
        for (int index = 1; index <= bars.size(); index++) {
            Optional<TradeSignal> signal = strategy.onBar(context(bars.subList(0, index)));
            signal.ifPresent(signals::add);
        }
        return signals;
    }

    private static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.getLast(), history);
    }

    private static StrategyExecutionContext context(List<BarEvent> history, StrategyInstrumentPosition position) {
        return new StrategyExecutionContext(
                METADATA,
                history.getLast(),
                history,
                null,
                null,
                position,
                StrategyPortfolioState.empty()
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

    private static List<BarEvent> bullishPullbackBars() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            bars.add(upBar(index, 100.0d + index));
        }
        bars.add(bar(13, 111.80d, 112.00d, 110.80d, 111.20d));
        bars.add(bar(14, 111.30d, 111.90d, 111.00d, 111.50d));
        bars.add(upSignalBar(15, 112.80d));
        return bars;
    }

    private static List<BarEvent> bullishPullbackWithCrossBars() {
        List<BarEvent> bars = bullishPullbackBars();
        bars.set(13, bar(13, 111.00d, 111.40d, 109.90d, 110.20d));
        return bars;
    }

    private static List<BarEvent> postScaleTrailOnlyBars() {
        List<BarEvent> bars = new ArrayList<>(bullishPullbackBars());
        bars.add(bar(bars.size(), 111.70d, 111.90d, 111.20d, 111.40d));
        bars.add(bar(bars.size(), 111.80d, 112.00d, 111.30d, 111.60d));
        return bars;
    }

    private static List<BarEvent> mixedStackBars() {
        List<BarEvent> bars = new ArrayList<>(bullishPullbackBars());
        bars.add(bar(bars.size(), 112.60d, 112.80d, 112.20d, 112.40d));
        bars.add(bar(bars.size(), 112.00d, 112.20d, 111.60d, 111.80d));
        return bars;
    }

    private static List<BarEvent> bearishPullbackBars() {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            bars.add(downBar(index, 120.0d - index));
        }
        bars.add(bar(13, 108.10d, 109.80d, 108.00d, 109.40d));
        bars.add(bar(14, 109.30d, 109.50d, 108.80d, 109.10d));
        bars.add(downSignalBar(15, 107.80d));
        return bars;
    }

    private static List<BarEvent> bullishTransitionBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 120.0d;
        for (int index = 0; index < 10; index++) {
            close -= 1.0d;
            bars.add(downBar(index, close));
        }
        for (int index = 10; index < 19; index++) {
            close += 0.10d;
            bars.add(bar(index, close - 0.03d, close + 0.10d, close - 0.10d, close));
        }
        bars.add(upSignalBar(19, close + 1.20d));
        return bars;
    }

    private static List<BarEvent> trendBars(int count, double start, double step) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(upBar(index, start + (step * index)));
        }
        return bars;
    }

    private static List<BarEvent> flatBars(int count, double close) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(bar(index, close, close + 0.10d, close - 0.10d, close));
        }
        return bars;
    }

    private static List<BarEvent> bars(String... closes) {
        List<BarEvent> bars = new ArrayList<>();
        for (int index = 0; index < closes.length; index++) {
            bars.add(upBar(index, new BigDecimal(closes[index]).doubleValue()));
        }
        return bars;
    }

    private static BarEvent upBar(int index, double close) {
        return bar(index, close - 0.20d, close + 0.25d, close - 0.50d, close);
    }

    private static BarEvent downBar(int index, double close) {
        return bar(index, close + 0.20d, close + 0.50d, close - 0.25d, close);
    }

    private static BarEvent upSignalBar(int index, double close) {
        return bar(index, close - 0.70d, close + 0.25d, close - 0.75d, close);
    }

    private static BarEvent downSignalBar(int index, double close) {
        return bar(index, close + 0.70d, close + 0.75d, close - 0.25d, close);
    }

    private static BarEvent bar(int index, double open, double high, double low, double close) {
        return new BarEvent(
                "1.0.0",
                new EventId("bar-%03d".formatted(index + 1)),
                INSTRUMENT,
                Instant.parse("2026-04-11T09:15:00Z").plusSeconds((long) index * 900L),
                "M15",
                new OHLCV(decimal(open), decimal(high), decimal(low), decimal(close), BigDecimal.valueOf(1000L + index)),
                new SourceRef(SourceType.ADAPTER, "ema-trend-pullback-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
