package org.algotradex.strategy.samples.range;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.OrderType;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.SuggestedTradeParams;
import org.algotradex.platform.contracts.common.value.TimeHorizon;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Closed-bar support/resistance sample strategy.
 */
public final class RangeSupportResistanceStrategy implements TradeSignalStrategy {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final int lookback;
    private final BigDecimal tolerance;
    private final BigDecimal riskReward;
    private final BigDecimal confidence;

    RangeSupportResistanceStrategy(int lookback, BigDecimal tolerance, BigDecimal riskReward, BigDecimal confidence) {
        if (lookback < 2) {
            throw new IllegalArgumentException("lookback must be >= 2");
        }
        this.lookback = lookback;
        this.tolerance = requireNonNull(tolerance, "tolerance");
        this.riskReward = requireNonNull(riskReward, "riskReward");
        this.confidence = requireNonNull(confidence, "confidence");
    }

    private static boolean bullishConfirmation(BarEvent current, BarEvent previous) {
        return current.ohlcv().close().compareTo(current.ohlcv().open()) > 0
                && current.ohlcv().close().compareTo(previous.ohlcv().high()) > 0;
    }

    private static boolean bearishConfirmation(BarEvent current, BarEvent previous) {
        return current.ohlcv().close().compareTo(current.ohlcv().open()) < 0
                && current.ohlcv().close().compareTo(previous.ohlcv().low()) < 0;
    }

    private static BigDecimal minLow(List<BarEvent> bars) {
        return bars.stream()
                .map(bar -> bar.ohlcv().low())
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    private static BigDecimal maxHigh(List<BarEvent> bars) {
        return bars.stream()
                .map(bar -> bar.ohlcv().high())
                .max(BigDecimal::compareTo)
                .orElseThrow();
    }

    @Override
    public String strategyId() {
        return RangeSupportResistanceStrategyProvider.STRATEGY_ID;
    }

    @Override
    public Optional<TradeSignal> onBar(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.size() < lookback + 2) {
            return Optional.empty();
        }

        BarEvent current = context.currentBar();
        BarEvent previous = history.get(history.size() - 2);
        List<BarEvent> rangeWindow = history.subList(history.size() - 1 - lookback, history.size() - 1);
        BigDecimal support = minLow(rangeWindow);
        BigDecimal resistance = maxHigh(rangeWindow);
        BigDecimal price = current.ohlcv().close();

        if (support.signum() > 0 && near(price, support) && bullishConfirmation(current, previous)) {
            BigDecimal target = price.add(riskReward.multiply(price.subtract(support), MATH_CONTEXT), MATH_CONTEXT);
            return Optional.of(signal(current, Direction.LONG, price, support, target));
        }
        if (resistance.signum() > 0 && near(price, resistance) && bearishConfirmation(current, previous)) {
            BigDecimal target = price.subtract(riskReward.multiply(resistance.subtract(price), MATH_CONTEXT), MATH_CONTEXT);
            return Optional.of(signal(current, Direction.SHORT, price, resistance, target));
        }
        return Optional.empty();
    }

    private boolean near(BigDecimal price, BigDecimal level) {
        BigDecimal distance = price.subtract(level).abs();
        BigDecimal ratio = distance.divide(level, MATH_CONTEXT);
        return ratio.compareTo(tolerance) < 0;
    }

    private TradeSignal signal(BarEvent bar, Direction direction, BigDecimal entry, BigDecimal stop, BigDecimal target) {
        return new TradeSignal(
                RangeSupportResistanceStrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value() + '-' + direction.name().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                new ConfidenceScore(confidence),
                SetupType.MEAN_REVERSION,
                new TimeHorizon("intraday", Duration.ofHours(4)),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                new SuggestedTradeParams(entry, stop, target, null, OrderType.MARKET),
                null,
                bar.cohort(),
                bar.baseline()
        );
    }
}
