package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.common.enums.AssetClass;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
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
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyPortfolioState;
import org.algotradex.platform.core.api.enums.marketcontext.MarketContextReadiness;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;
import org.algotradex.platform.core.api.enums.marketcontext.TrendDirection;
import org.algotradex.platform.core.api.enums.marketcontext.TrendStrength;
import org.algotradex.platform.core.api.enums.marketcontext.VolatilityBucket;
import org.algotradex.platform.core.api.enums.replay.ReplayMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    static StrategyExecutionContext context(List<BarEvent> history, PrimaryMarketRegime primaryRegime) {
        return new StrategyExecutionContext(
                METADATA,
                history.getLast(),
                history,
                null,
                marketContext(history, primaryRegime)
        );
    }

    static StrategyExecutionContext context(List<BarEvent> history, StrategyInstrumentPosition position) {
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

    static StrategyExecutionContext context(List<BarEvent> history, StrategyInstrumentPosition position, PrimaryMarketRegime primaryRegime) {
        return new StrategyExecutionContext(
                METADATA,
                history.getLast(),
                history,
                null,
                marketContext(history, primaryRegime),
                position,
                StrategyPortfolioState.empty()
        );
    }

    static StrategyExecutionContext contextWithH1(List<BarEvent> history, List<BarEvent> h1History) {
        return new StrategyExecutionContext(
                METADATA,
                history.getLast(),
                history,
                new MarketDataVisibilitySnapshot(
                        history.getLast().occurredAt(),
                        history.getLast().timeframe(),
                        Map.of("H1", h1History),
                        List.of()
                ),
                MarketContextSnapshot.empty(),
                StrategyInstrumentPosition.flat(),
                StrategyPortfolioState.empty()
        );
    }

    static StrategyInstrumentPosition longPosition(int barsHeld, double currentR, int scaleOutCount) {
        return longPosition(barsHeld, currentR, scaleOutCount, 3.0d, 1.0d);
    }

    static StrategyInstrumentPosition longPosition(
            int barsHeld,
            double currentR,
            int scaleOutCount,
            double maxFavorablePct,
            double maxAdversePct
    ) {
        return new StrategyInstrumentPosition(
                true,
                PositionSide.LONG,
                decimal(1.0d),
                decimal(100.0d),
                Instant.parse("2026-04-11T09:15:00Z"),
                barsHeld,
                decimal(2.0d),
                decimal(2.0d),
                decimal(currentR),
                decimal(2.0d),
                0,
                scaleOutCount,
                "",
                decimal(101.0d),
                decimal(maxFavorablePct),
                decimal(maxAdversePct)
        );
    }

    static StrategyInstrumentPosition shortPosition(int barsHeld, double currentR, int scaleOutCount) {
        return shortPosition(barsHeld, currentR, scaleOutCount, 3.0d, 1.0d);
    }

    static StrategyInstrumentPosition shortPosition(
            int barsHeld,
            double currentR,
            int scaleOutCount,
            double maxFavorablePct,
            double maxAdversePct
    ) {
        return shortPosition(barsHeld, currentR, 0, scaleOutCount, maxFavorablePct, maxAdversePct);
    }

    static StrategyInstrumentPosition shortPosition(
            int barsHeld,
            double currentR,
            int scaleInCount,
            int scaleOutCount,
            double maxFavorablePct,
            double maxAdversePct
    ) {
        return new StrategyInstrumentPosition(
                true,
                PositionSide.SHORT,
                decimal(1.0d),
                decimal(100.0d),
                Instant.parse("2026-04-11T09:15:00Z"),
                barsHeld,
                decimal(2.0d),
                decimal(2.0d),
                decimal(currentR),
                decimal(2.0d),
                scaleInCount,
                scaleOutCount,
                "",
                decimal(99.0d),
                decimal(maxFavorablePct),
                decimal(maxAdversePct)
        );
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

    static List<BarEvent> ichimokuBetaShortSetupBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 150.0d;
        for (int index = 0; index < 230; index++) {
            close += 0.45d;
            bars.add(bar(index, close - 0.20d, close + 0.90d, close - 0.90d, close));
        }
        for (int index = 230; index < 258; index++) {
            close -= 5.0d;
            bars.add(bar(index, close + 0.35d, close + 0.55d, close - 1.25d, close));
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

    static List<BarEvent> multiIndicatorV6ShortSetupBars() {
        List<BarEvent> bars = new ArrayList<>();
        double close = 120.0d;
        for (int index = 0; index < 78; index++) {
            close += 1.10d;
            bars.add(bar(index, close - 0.30d, close + 0.85d, close - 0.80d, close));
        }
        for (int index = 78; index < 86; index++) {
            close -= 30.0d;
            bars.add(bar(index, close + 0.45d, close + 0.55d, close - 1.30d, close));
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

    private static MarketContextSnapshot marketContext(List<BarEvent> history, PrimaryMarketRegime primaryRegime) {
        BarEvent current = history.getLast();
        TrendStrength trendStrength = trendStrength(primaryRegime);
        VolatilityBucket volatilityBucket = volatilityBucket(primaryRegime);
        return new MarketContextSnapshot(
                "ctx-" + primaryRegime.name().toLowerCase(Locale.ROOT),
                "NIFTY50",
                current.occurredAt(),
                current.occurredAt(),
                current.occurredAt(),
                current.timeframe(),
                Map.of(),
                primaryRegime.name(),
                List.of(primaryRegime.name()),
                Map.of(),
                trendStrength.name(),
                primaryRegime,
                primaryRegime,
                trendStrength,
                volatilityBucket,
                TrendDirection.UNKNOWN,
                MarketContextReadiness.READY,
                List.of()
        );
    }

    private static TrendStrength trendStrength(PrimaryMarketRegime primaryRegime) {
        if (primaryRegime.name().startsWith("RANGING")) {
            return TrendStrength.RANGING;
        }
        if (primaryRegime.name().startsWith("WEAK_TREND")) {
            return TrendStrength.WEAK_TREND;
        }
        if (primaryRegime.name().startsWith("STRONG_TREND")) {
            return TrendStrength.STRONG_TREND;
        }
        return TrendStrength.UNKNOWN;
    }

    private static VolatilityBucket volatilityBucket(PrimaryMarketRegime primaryRegime) {
        if (primaryRegime.name().endsWith("LOW_VOLATILITY")) {
            return VolatilityBucket.LOW_VOLATILITY;
        }
        if (primaryRegime.name().endsWith("MEDIUM_VOLATILITY")) {
            return VolatilityBucket.MEDIUM_VOLATILITY;
        }
        if (primaryRegime.name().endsWith("HIGH_VOLATILITY")) {
            return VolatilityBucket.HIGH_VOLATILITY;
        }
        return VolatilityBucket.UNKNOWN;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
