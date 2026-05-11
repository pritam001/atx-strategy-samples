package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.EventId;
import org.algotradex.platform.contracts.common.ids.ReplayId;
import org.algotradex.platform.contracts.common.ids.RunId;
import org.algotradex.platform.contracts.common.refs.InstrumentRef;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.market.OHLCV;
import org.algotradex.platform.core.api.dto.common.replay.ReplayRunMetadata;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DoflamingoStrategyTestSupport {
    private static final InstrumentRef INSTRUMENT = new InstrumentRef("NIFTY50", "Nifty 50", "NSE", AssetClass.INDEX, "INR");
    private static final ReplayRunMetadata METADATA = new ReplayRunMetadata(
            new RunId("run-doflamingo-test"),
            new ReplayId("replay-doflamingo-test"),
            ReplayMode.FULL_RUN
    );

    private DoflamingoStrategyTestSupport() {
    }

    static StrategyExecutionContext context(List<BarEvent> history) {
        return new StrategyExecutionContext(METADATA, history.getLast(), history);
    }

    static List<BarEvent> ichimokuBetaSetupBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 300.0d;
        for (int index = 0; index < 230; index++) {
            close -= 0.45d;
            bars.add(bar(index, close + 0.20d, close + 0.90d, close - 0.90d, close));
        }
        for (int index = 230; index < 258; index++) {
            close += 5.0d;
            bars.add(bar(index, close - 0.35d, close + 1.25d, close - 0.55d, close));
        }
        return bars;
    }

    static List<BarEvent> multiIndicatorV6SetupBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 220.0d;
        for (int index = 0; index < 78; index++) {
            close -= 1.10d;
            bars.add(bar(index, close + 0.30d, close + 0.80d, close - 0.85d, close));
        }
        for (int index = 78; index < 86; index++) {
            close += 30.0d;
            bars.add(bar(index, close - 0.45d, close + 1.30d, close - 0.55d, close));
        }
        return bars;
    }

    static BarEvent nextBarAfter(List<BarEvent> history, double open, double high, double low, double close) {
        return bar(history.size(), open, high, low, close);
    }

    private static BarEvent bar(int index, double open, double high, double low, double close) {
        String eventId = "bar-%03d".formatted(index + 1).toLowerCase(Locale.ROOT);
        Instant occurredAt = Instant.parse("2026-04-11T09:15:00Z").plusSeconds(900L * index);
        return new BarEvent(
                "1.0.0",
                new EventId(eventId),
                INSTRUMENT,
                occurredAt,
                "M15",
                new OHLCV(
                        decimal(open),
                        decimal(high),
                        decimal(low),
                        decimal(close),
                        BigDecimal.valueOf(1000L + index)
                ),
                new SourceRef(SourceType.ADAPTER, "doflamingo-test"),
                null,
                null,
                null
        );
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
