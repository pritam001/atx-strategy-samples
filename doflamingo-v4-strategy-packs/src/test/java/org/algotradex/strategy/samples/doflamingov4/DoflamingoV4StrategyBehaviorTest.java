package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.replay.MarketDataVisibilitySnapshot;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyStateEnvelope;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.strategy.simulation.SimulationStepper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoV4StrategyBehaviorTest {
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-doflamingo-v4-stepper-resume-test"),
            new ReplayId("replay-doflamingo-v4-stepper-resume-test"),
            ReplayMode.FULL_RUN
    );

    @Test
    void trendV4SuppressesV3AdaptiveOnlyReversalFixtureByDefault() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "sessionGating", false
        )), null);

        StrategyIntentResult result = firstIntent(strategy, DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars());

        assertThat(result).isNull();
    }

    @Test
    void trendV4UsesExplicitTimeStopInsteadOfPlatformStopMutation() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "enableScaleOut", false,
                "maxHoldingBars", 32,
                "sessionGating", false
        )), null);

        var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(
                DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars(),
                DoflamingoStrategyTestSupport.longPosition(32, 0.80d, 0)
        ));

        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.EXIT_LONG);
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains("multi-v6-v4.exit-time-stop");
        assertThat(intent.reason().evidence()).contains("timeStop=true");
    }

    @Test
    void v4StopPolicyCarriesRuntimeRrTargetWithoutTrailingMutation() {
        var policy = DoflamingoSignalSupport.percentStopWithRrTarget(
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(2.5),
                "v4 bounded stop"
        );

        assertThat(policy.stop().type()).isEqualTo(StrategyExitRuleType.PERCENT);
        assertThat(policy.stop().value()).isEqualByComparingTo("1.5000");
        assertThat(policy.target().type()).isEqualTo(StrategyExitRuleType.RR);
        assertThat(policy.target().value()).isEqualByComparingTo("2.5000");
        assertThat(policy.trailing().type().name()).isEqualTo("NONE");
    }

    @Test
    void trendV4ImplementsSimulationLabSpiAndExplainsNoActionBars() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(multiV4ResumeParameters(), null);

        assertThat(strategy).isInstanceOf(ResumableStrategy.class);
        assertThat(strategy).isInstanceOf(StrategyReasoningEvaluator.class);

        StrategyReasoningEvaluator reasoning = (StrategyReasoningEvaluator) strategy;
        var context = DoflamingoStrategyTestSupport.context(
                DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars().subList(0, 10)
        );

        assertThat(reasoning.evaluateReasoning(context)).isNotEmpty();
        assertThat(reasoning.currentPhase(context)).isNotBlank().isIn(trendV4PhaseIds());
    }

    @Test
    void trendV4ResumableStateMatchesFreshReplayAcrossCheckpoint() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        StrategyParameters parameters = multiV4ResumeParameters();
        List<BarEvent> primary = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();
        int checkpointIndex = Math.max(1, primary.size() / 2);

        TradeIntentStrategy original = (TradeIntentStrategy) provider.create(parameters, null);
        replayRange(original, primary, 0, checkpointIndex);
        ResumableStrategy originalState = (ResumableStrategy) original;
        StrategyStateEnvelope checkpoint = originalState.initialState(
                DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider.STRATEGY_VERSION,
                "variant-a",
                parameters
        );

        TradeIntentStrategy restored = (TradeIntentStrategy) provider.create(parameters, null);
        ((ResumableStrategy) restored).resumeFromState(originalState.serialise(checkpoint));
        StrategyIntentResult restoredFinal = replayRange(restored, primary, checkpointIndex, primary.size());

        TradeIntentStrategy fresh = (TradeIntentStrategy) provider.create(parameters, null);
        StrategyIntentResult freshFinal = replayRange(fresh, primary, 0, primary.size());

        assertThat(restoredFinal).usingRecursiveComparison().isEqualTo(freshFinal);
        assertThat(((ResumableStrategy) restored).snapshotState()).isEqualTo(((ResumableStrategy) fresh).snapshotState());
    }

    @Test
    void trendV4SimulationStepperStateMatchesFreshReplayAcrossSerializedCheckpoint() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        StrategyParameters parameters = multiV4ResumeParameters();
        List<BarEvent> primary = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();
        int checkpointIndex = Math.max(1, primary.size() / 2);

        SimulationStepper checkpointed = multiStepper(provider, parameters);
        for (int index = 0; index < checkpointIndex; index++) {
            checkpointed.step(primary.get(index), MarketDataVisibilitySnapshot.empty(), MarketContextSnapshot.empty());
        }
        StrategyStateEnvelope checkpoint = checkpointed.checkpoint("variant-a");

        SimulationStepper restored = multiStepper(provider, parameters);
        restored.resumeFromSerialised(checkpointed.serialise(checkpoint), parameters);
        var restoredFinal = stepRange(restored, primary, List.of(), checkpointIndex, primary.size());

        SimulationStepper fresh = multiStepper(provider, parameters);
        var freshFinal = stepRange(fresh, primary, List.of(), 0, primary.size());

        assertThat(restoredFinal.result()).usingRecursiveComparison().isEqualTo(freshFinal.result());
        assertThat(restored.currentState().strategyState()).isEqualTo(fresh.currentState().strategyState());
        assertThat(restored.currentState().resolvedParamsHash()).isEqualTo(fresh.currentState().resolvedParamsHash());
        assertThat(restored.currentState().currentPhase()).isNotBlank().isIn(trendV4PhaseIds());
    }

    @Test
    void trendV4SimulationStepperEmissionsMatchDirectReplayGoldenActions() {
        var provider = new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider();
        StrategyParameters parameters = multiV4ResumeParameters();
        List<BarEvent> primary = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();

        List<String> direct = replayDirectActions((TradeIntentStrategy) provider.create(parameters, null), primary);
        List<String> stepped = replayStepperActions(multiStepper(provider, parameters), primary, List.of());

        assertThat(direct).isEmpty();
        assertThat(stepped).isEqualTo(direct);
    }

    @Test
    void ichimokuV4CanStillRequireVisibleH1BiasWhenConfigured() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.ofEntries(
                Map.entry("trendAverageLookback", 50),
                Map.entry("minConfidence", "0.50"),
                Map.entry("htfCloudBiasMode", "ALIGN_WITH_TRADE"),
                Map.entry("sessionGating", false),
                Map.entry("minKumoThicknessAtr", "0.0"),
                Map.entry("minFutureCloudSpreadAtr", "0.0"),
                Map.entry("maxEntryAtrFromCloudTop", "50.0"),
                Map.entry("requireChikouClearSpace", false),
                Map.entry("requireFutureCloudWidening", false),
                Map.entry("volumeConfirmMultiple", "0.0"),
                Map.entry("atrExpansionMultiple", "0.0")
        )), null);

        StrategyIntentResult result = firstIntent(strategy, DoflamingoStrategyTestSupport.ichimokuBetaSetupBars());

        assertThat(result).isNull();
    }

    @Test
    void ichimokuV4EntryUsesH1BiasCloudQualityAndRrTargetEvidence() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.ofEntries(
                Map.entry("trendAverageLookback", 50),
                Map.entry("minConfidence", "0.50"),
                Map.entry("htfCloudBiasMode", "ALIGN_WITH_TRADE"),
                Map.entry("sessionGating", false),
                Map.entry("minKumoThicknessAtr", "0.0"),
                Map.entry("minFutureCloudSpreadAtr", "0.0"),
                Map.entry("maxEntryAtrFromCloudTop", "50.0"),
                Map.entry("requireChikouClearSpace", false),
                Map.entry("requireFutureCloudWidening", false),
                Map.entry("volumeConfirmMultiple", "0.0"),
                Map.entry("atrExpansionMultiple", "0.0"),
                Map.entry("targetRMultiple", "2.50")
        )), null);
        List<BarEvent> primary = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        List<BarEvent> h1 = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        StrategyIntentResult result = firstIntentWithH1(strategy, primary, h1);

        assertThat(result).isNotNull();
        assertThat(result.tradeIntents()).hasSize(1);
        var intent = result.tradeIntents().getFirst();
        assertThat(intent.action()).isEqualTo(StrategyTradeAction.ENTER_LONG);
        assertThat(intent.exit().target().type()).isEqualTo(StrategyExitRuleType.RR);
        assertThat(intent.exit().target().value()).isEqualByComparingTo("2.5000");
        assertThat(intent.reason().tags()).contains("v4", "ichimoku", "htf");
        assertThat(intent.reason().conditions()).extracting("conditionId")
                .contains(
                        "ichimoku-v4.h1-cloud-bias",
                        "ichimoku-v4.kumo-thickness",
                        "ichimoku-v4.future-cloud-spread",
                        "ichimoku-v4.entry-overextension"
                );
    }

    @Test
    void ichimokuV4ZeroMaxEntryDistanceDisablesCloudDistanceCap() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.ofEntries(
                Map.entry("trendAverageLookback", 50),
                Map.entry("minConfidence", "0.50"),
                Map.entry("htfCloudBiasMode", "ALIGN_WITH_TRADE"),
                Map.entry("sessionGating", false),
                Map.entry("minKumoThicknessAtr", "0.0"),
                Map.entry("minFutureCloudSpreadAtr", "0.0"),
                Map.entry("maxEntryAtrFromCloudTop", "0.0"),
                Map.entry("requireChikouClearSpace", false),
                Map.entry("requireFutureCloudWidening", false),
                Map.entry("volumeConfirmMultiple", "0.0"),
                Map.entry("atrExpansionMultiple", "0.0")
        )), null);
        List<BarEvent> primary = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        List<BarEvent> h1 = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        StrategyIntentResult result = firstIntentWithH1(strategy, primary, h1);

        assertThat(result).isNotNull();
        assertThat(result.tradeIntents()).hasSize(1);
        assertThat(result.tradeIntents().getFirst().reason().conditions())
                .filteredOn(condition -> condition.conditionId().equals("ichimoku-v4.entry-overextension"))
                .singleElement()
                .extracting("passed")
                .isEqualTo(true);
    }

    @Test
    void ichimokuV4SimulationStepperEmissionsMatchDirectReplayGoldenActions() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        StrategyParameters parameters = ichimokuResumeParameters();
        List<BarEvent> primary = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        List<BarEvent> h1 = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();

        List<String> direct = replayDirectActionsWithH1((TradeIntentStrategy) provider.create(parameters, null), primary, h1);
        List<String> stepped = replayStepperActions(stepper(provider, parameters), primary, h1);

        assertThat(direct).startsWith("ENTER_LONG");
        assertThat(stepped).containsExactly("ENTER_LONG");
    }

    @Test
    void ichimokuV4ResumableStateMatchesFreshReplayAcrossCheckpoint() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        StrategyParameters parameters = new StrategyParameters(Map.ofEntries(
                Map.entry("trendAverageLookback", 50),
                Map.entry("minConfidence", "0.50"),
                Map.entry("htfCloudBiasMode", "ALIGN_WITH_TRADE"),
                Map.entry("sessionGating", false),
                Map.entry("minKumoThicknessAtr", "0.0"),
                Map.entry("minFutureCloudSpreadAtr", "0.0"),
                Map.entry("maxEntryAtrFromCloudTop", "0.0"),
                Map.entry("requireChikouClearSpace", false),
                Map.entry("requireFutureCloudWidening", false),
                Map.entry("volumeConfirmMultiple", "0.0"),
                Map.entry("atrExpansionMultiple", "0.0")
        ));
        List<BarEvent> primary = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        List<BarEvent> h1 = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        int checkpointIndex = Math.max(1, primary.size() / 2);
        TradeIntentStrategy original = (TradeIntentStrategy) provider.create(parameters, null);
        replayRangeWithH1(original, primary, h1, 0, checkpointIndex);
        ResumableStrategy originalState = (ResumableStrategy) original;
        StrategyStateEnvelope checkpoint = originalState.initialState(
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                "variant-a",
                parameters
        );

        TradeIntentStrategy restored = (TradeIntentStrategy) provider.create(parameters, null);
        ((ResumableStrategy) restored).resumeFromState(originalState.serialise(checkpoint));
        StrategyIntentResult restoredFinal = replayRangeWithH1(restored, primary, h1, checkpointIndex, primary.size());

        TradeIntentStrategy fresh = (TradeIntentStrategy) provider.create(parameters, null);
        StrategyIntentResult freshFinal = replayRangeWithH1(fresh, primary, h1, 0, primary.size());

        assertThat(restoredFinal).usingRecursiveComparison().isEqualTo(freshFinal);
        assertThat(((ResumableStrategy) restored).snapshotState()).isEqualTo(((ResumableStrategy) fresh).snapshotState());
    }

    @Test
    void ichimokuV4SimulationStepperStateMatchesFreshReplayAcrossSerializedCheckpoint() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        StrategyParameters parameters = ichimokuResumeParameters();
        List<BarEvent> primary = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        List<BarEvent> h1 = DoflamingoStrategyTestSupport.ichimokuBetaSetupBars();
        int checkpointIndex = Math.max(1, primary.size() / 2);

        SimulationStepper checkpointed = stepper(provider, parameters);
        for (int index = 0; index < checkpointIndex; index++) {
            checkpointed.step(primary.get(index), visibility(primary.get(index), h1, index + 1), MarketContextSnapshot.empty());
        }
        StrategyStateEnvelope checkpoint = checkpointed.checkpoint("variant-a");

        SimulationStepper restored = stepper(provider, parameters);
        restored.resumeFromSerialised(checkpointed.serialise(checkpoint), parameters);
        var restoredFinal = stepRange(restored, primary, h1, checkpointIndex, primary.size());

        SimulationStepper fresh = stepper(provider, parameters);
        var freshFinal = stepRange(fresh, primary, h1, 0, primary.size());

        assertThat(restoredFinal.result()).usingRecursiveComparison().isEqualTo(freshFinal.result());
        assertThat(restored.currentState().strategyState()).isEqualTo(fresh.currentState().strategyState());
        assertThat(restored.currentState().resolvedParamsHash()).isEqualTo(fresh.currentState().resolvedParamsHash());
        assertThat(restored.currentState().currentPhase()).isNotBlank().isIn(ichimokuPhaseIds());
    }

    private static StrategyIntentResult firstIntent(TradeIntentStrategy strategy, List<BarEvent> bars) {
        for (int index = 1; index <= bars.size(); index++) {
            var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(bars.subList(0, index)));
            if (!result.tradeIntents().isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static StrategyIntentResult firstIntentWithH1(
            TradeIntentStrategy strategy,
            List<BarEvent> primary,
            List<BarEvent> h1
    ) {
        for (int index = 1; index <= primary.size(); index++) {
            var result = strategy.onBarIntent(DoflamingoStrategyTestSupport.contextWithH1(
                    primary.subList(0, index),
                    h1.subList(0, Math.min(index, h1.size()))
            ));
            if (!result.tradeIntents().isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static StrategyIntentResult replayRangeWithH1(
            TradeIntentStrategy strategy,
            List<BarEvent> primary,
            List<BarEvent> h1,
            int start,
            int endExclusive
    ) {
        StrategyIntentResult result = StrategyIntentResult.empty();
        for (int index = start + 1; index <= endExclusive; index++) {
            result = strategy.onBarIntent(DoflamingoStrategyTestSupport.contextWithH1(
                    primary.subList(0, index),
                    h1.subList(0, Math.min(index, h1.size()))
            ));
        }
        return result;
    }

    private static StrategyIntentResult replayRange(
            TradeIntentStrategy strategy,
            List<BarEvent> primary,
            int start,
            int endExclusive
    ) {
        StrategyIntentResult result = StrategyIntentResult.empty();
        for (int index = start + 1; index <= endExclusive; index++) {
            result = strategy.onBarIntent(DoflamingoStrategyTestSupport.context(primary.subList(0, index)));
        }
        return result;
    }

    private static List<String> replayDirectActions(TradeIntentStrategy strategy, List<BarEvent> primary) {
        List<String> actions = new ArrayList<>();
        for (int index = 1; index <= primary.size(); index++) {
            strategy.onBarIntent(DoflamingoStrategyTestSupport.context(primary.subList(0, index))).tradeIntents().stream()
                    .map(intent -> intent.action().name())
                    .forEach(actions::add);
        }
        return actions;
    }

    private static List<String> replayDirectActionsWithH1(TradeIntentStrategy strategy, List<BarEvent> primary, List<BarEvent> h1) {
        List<String> actions = new ArrayList<>();
        for (int index = 1; index <= primary.size(); index++) {
            strategy.onBarIntent(DoflamingoStrategyTestSupport.contextWithH1(
                    primary.subList(0, index),
                    h1.subList(0, Math.min(index, h1.size()))
            )).tradeIntents().stream()
                    .map(intent -> intent.action().name())
                    .forEach(actions::add);
        }
        return actions;
    }

    private static StrategyParameters multiV4ResumeParameters() {
        return new StrategyParameters(Map.of(
                "macdFastPeriod", 3,
                "macdSlowPeriod", 7,
                "macdSignalPeriod", 8,
                "minConfidence", "0.50",
                "sessionGating", false
        ));
    }

    private static StrategyParameters ichimokuResumeParameters() {
        return new StrategyParameters(Map.ofEntries(
                Map.entry("trendAverageLookback", 50),
                Map.entry("minConfidence", "0.50"),
                Map.entry("htfCloudBiasMode", "ALIGN_WITH_TRADE"),
                Map.entry("sessionGating", false),
                Map.entry("minKumoThicknessAtr", "0.0"),
                Map.entry("minFutureCloudSpreadAtr", "0.0"),
                Map.entry("maxEntryAtrFromCloudTop", "0.0"),
                Map.entry("requireChikouClearSpace", false),
                Map.entry("requireFutureCloudWidening", false),
                Map.entry("volumeConfirmMultiple", "0.0"),
                Map.entry("atrExpansionMultiple", "0.0")
        ));
    }

    private static SimulationStepper stepper(DoflamingoIchimokuMo002BetaV4StrategyProvider provider, StrategyParameters parameters) {
        return new SimulationStepper(
                List.of((TradeSignalStrategy) provider.create(parameters, null)),
                METADATA,
                256,
                java.time.Clock.systemUTC(),
                DoflamingoIchimokuMo002BetaV4StrategyProvider.STRATEGY_VERSION,
                parameters
        );
    }

    private static SimulationStepper multiStepper(DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider provider, StrategyParameters parameters) {
        return new SimulationStepper(
                List.of((TradeSignalStrategy) provider.create(parameters, null)),
                METADATA,
                256,
                java.time.Clock.systemUTC(),
                DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider.STRATEGY_VERSION,
                parameters
        );
    }

    private static List<String> ichimokuPhaseIds() {
        return new DoflamingoIchimokuMo002BetaV4StrategyProvider().descriptor().reasoningModel().phases().stream()
                .map(org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor::phaseId)
                .toList();
    }

    private static List<String> trendV4PhaseIds() {
        return new DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider().descriptor().reasoningModel().phases().stream()
                .map(org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor::phaseId)
                .toList();
    }

    private static org.algotradex.platform.core.strategy.simulation.SimulationStepResult stepRange(
            SimulationStepper stepper,
            List<BarEvent> primary,
            List<BarEvent> h1,
            int start,
            int endExclusive
    ) {
        org.algotradex.platform.core.strategy.simulation.SimulationStepResult result = null;
        for (int index = start; index < endExclusive; index++) {
            result = stepper.step(primary.get(index), visibility(primary.get(index), h1, index + 1), MarketContextSnapshot.empty());
        }
        return result;
    }

    private static List<String> replayStepperActions(
            SimulationStepper stepper,
            List<BarEvent> primary,
            List<BarEvent> h1
    ) {
        List<String> actions = new ArrayList<>();
        for (int index = 0; index < primary.size(); index++) {
            stepper.step(primary.get(index), visibility(primary.get(index), h1, index + 1), MarketContextSnapshot.empty())
                    .result()
                    .emittedTradeIntents()
                    .stream()
                    .map(intent -> intent.action().name())
                    .forEach(actions::add);
        }
        return actions;
    }

    private static MarketDataVisibilitySnapshot visibility(BarEvent current, List<BarEvent> h1, int visibleBars) {
        return new MarketDataVisibilitySnapshot(
                current.occurredAt(),
                current.timeframe(),
                Map.of("H1", h1.subList(0, Math.min(visibleBars, h1.size()))),
                List.of()
        );
    }
}
