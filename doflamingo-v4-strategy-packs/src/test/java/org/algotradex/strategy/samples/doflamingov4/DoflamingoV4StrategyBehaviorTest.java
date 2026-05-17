package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoV4StrategyBehaviorTest {
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
    void ichimokuV4RequiresVisibleH1BiasBeforeFlatEntry() {
        var provider = new DoflamingoIchimokuMo002BetaV4StrategyProvider();
        TradeIntentStrategy strategy = (TradeIntentStrategy) provider.create(new StrategyParameters(Map.of(
                "trendAverageLookback", 50,
                "minConfidence", "0.50",
                "sessionGating", false,
                "minKumoThicknessAtr", "0.0",
                "minFutureCloudSpreadAtr", "0.0",
                "maxEntryAtrFromCloudTop", "50.0",
                "requireChikouClearSpace", false,
                "requireFutureCloudWidening", false,
                "volumeConfirmMultiple", "0.0",
                "atrExpansionMultiple", "0.0"
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
}
