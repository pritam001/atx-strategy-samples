package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.contracts.market.BarEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DoflamingoIndicatorMathTest {
    @Test
    void rollingMultiIndicatorStateMatchesFullSeriesState() {
        List<BarEvent> bars = DoflamingoStrategyTestSupport.multiIndicatorV6SetupBars();
        DoflamingoIndicatorMath.MultiIndicatorTracker tracker = DoflamingoIndicatorMath.multiIndicatorTracker(3, 7, 8);

        for (int index = 0; index < bars.size(); index++) {
            Optional<DoflamingoIndicatorMath.MultiIndicatorState> rolling = tracker.update(bars.get(index));
            Optional<DoflamingoIndicatorMath.MultiIndicatorState> full = DoflamingoIndicatorMath.multiIndicatorState(
                    bars.subList(0, index + 1),
                    3,
                    7,
                    8
            );

            assertThat(rolling.isPresent())
                    .as("presence at index %s", index)
                    .isEqualTo(full.isPresent());
            if (full.isPresent()) {
                assertClose("presentSpanB", index, rolling.get().presentSpanB(), full.get().presentSpanB());
                assertClose("macdHistogram", index, rolling.get().macdHistogram(), full.get().macdHistogram());
                assertClose("previousMacdHistogram", index, rolling.get().previousMacdHistogram(), full.get().previousMacdHistogram());
                assertClose("secondPreviousMacdHistogram", index, rolling.get().secondPreviousMacdHistogram(), full.get().secondPreviousMacdHistogram());
                assertClose("macdSignal", index, rolling.get().macdSignal(), full.get().macdSignal());
                assertClose("stochK", index, rolling.get().stochK(), full.get().stochK());
                assertClose("previousStochK", index, rolling.get().previousStochK(), full.get().previousStochK());
                assertClose("stochD", index, rolling.get().stochD(), full.get().stochD());
                assertClose("previousStochD", index, rolling.get().previousStochD(), full.get().previousStochD());
                assertClose("psar", index, rolling.get().psar(), full.get().psar());
                assertClose("previousPsar", index, rolling.get().previousPsar(), full.get().previousPsar());
            }
        }
    }

    private static void assertClose(String field, int index, double actual, double expected) {
        if (Double.isNaN(expected)) {
            assertThat(actual)
                    .as("%s at index %s", field, index)
                    .isNaN();
            return;
        }
        assertThat(actual)
                .as("%s at index %s", field, index)
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.000000001d));
    }
}
