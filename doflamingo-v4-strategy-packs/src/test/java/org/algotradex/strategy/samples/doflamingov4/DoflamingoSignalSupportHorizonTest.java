package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoSignalSupportHorizonTest {
    private static final int MAX_HOLDING_BARS = 42;

    @Test
    void labelsEntryAndReversalHorizonsByResultingPositionSide() {
        var context = DoflamingoStrategyTestSupport.context(DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars().subList(0, 1));

        assertHorizon(
                DoflamingoSignalSupport.longEntryIntent(
                        "test-strategy",
                        "1.0.0",
                        context,
                        BigDecimal.valueOf(0.70),
                        SetupType.REVERSAL,
                        BigDecimal.valueOf(0.01),
                        TradeIntentExitPolicy.none(),
                        MAX_HOLDING_BARS,
                        "long entry",
                        List.of(),
                        List.of(),
                        List.of()
                ),
                StrategyTradeAction.ENTER_LONG,
                IntendedHorizonLabel.SWING
        );
        assertHorizon(
                DoflamingoSignalSupport.shortEntryIntent(
                        "test-strategy",
                        "1.0.0",
                        context,
                        BigDecimal.valueOf(0.70),
                        SetupType.REVERSAL,
                        BigDecimal.valueOf(0.01),
                        TradeIntentExitPolicy.none(),
                        MAX_HOLDING_BARS,
                        "short entry",
                        List.of(),
                        List.of(),
                        List.of()
                ),
                StrategyTradeAction.ENTER_SHORT,
                IntendedHorizonLabel.INTRADAY
        );
        assertHorizon(
                DoflamingoSignalSupport.reverseLongToShortIntent(
                        "test-strategy",
                        "1.0.0",
                        context,
                        BigDecimal.valueOf(0.70),
                        SetupType.REVERSAL,
                        BigDecimal.valueOf(0.01),
                        TradeIntentExitPolicy.none(),
                        MAX_HOLDING_BARS,
                        "reverse to short",
                        List.of(),
                        List.of(),
                        List.of()
                ),
                StrategyTradeAction.REVERSE_LONG_TO_SHORT,
                IntendedHorizonLabel.INTRADAY
        );
        assertHorizon(
                DoflamingoSignalSupport.reverseShortToLongIntent(
                        "test-strategy",
                        "1.0.0",
                        context,
                        BigDecimal.valueOf(0.70),
                        SetupType.REVERSAL,
                        BigDecimal.valueOf(0.01),
                        TradeIntentExitPolicy.none(),
                        MAX_HOLDING_BARS,
                        "reverse to long",
                        List.of(),
                        List.of(),
                        List.of()
                ),
                StrategyTradeAction.REVERSE_SHORT_TO_LONG,
                IntendedHorizonLabel.SWING
        );
    }

    private static void assertHorizon(
            StrategyTradeIntent intent,
            StrategyTradeAction expectedAction,
            IntendedHorizonLabel expectedLabel
    ) {
        assertThat(intent.action()).isEqualTo(expectedAction);
        assertThat(intent.horizon().maxHoldingBars()).isEqualTo(MAX_HOLDING_BARS);
        assertThat(intent.horizon().intendedHorizonLabel()).isEqualTo(expectedLabel);
    }
}
