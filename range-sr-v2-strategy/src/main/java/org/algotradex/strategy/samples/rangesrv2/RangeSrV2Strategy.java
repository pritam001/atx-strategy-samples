package org.algotradex.strategy.samples.rangesrv2;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.enums.LifecycleRole;
import org.algotradex.platform.contracts.common.enums.OrderType;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.ids.StrategyIntentId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.SuggestedTradeParams;
import org.algotradex.platform.contracts.common.value.TagSet;
import org.algotradex.platform.contracts.common.value.TimeHorizon;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.intelligence.TradeIntentEntry;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitRule;
import org.algotradex.platform.contracts.intelligence.TradeIntentHorizon;
import org.algotradex.platform.contracts.intelligence.TradeIntentPreconditions;
import org.algotradex.platform.contracts.intelligence.TradeIntentSizing;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Lifecycle-aware range support/resistance sample using H4 context and M15 execution bars.
 * <p>
 * The strategy requires H4 context history for trend strength and structure, then evaluates the
 * current M15 bar for discount/premium location, defended level confluence, and reversal-pattern
 * confirmation. Accepted setups emit both a legacy {@link TradeSignal} and an entry
 * {@link StrategyTradeIntent} with structured evidence and risk-aware sizing metadata.
 * <p>
 * The implementation keeps a per-instrument cooldown map and is expected to be used as a fresh,
 * run-scoped instance. It is deterministic for the same ordered M15/H4 histories and effective
 * parameters, but it is not thread-safe.
 * <p>
 * The sample does not own execution, broker routing, exchange session rules, lot/tick conversion,
 * slippage, or portfolio accounting. The {@code riskUsdPerTrade} parameter is sample sizing input
 * for intent metadata, not a broker-side risk guarantee.
 */
public final class RangeSrV2Strategy implements TradeIntentStrategy {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int ADX_PERIOD = 14;
    private static final int EMA_PERIOD = 50;
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final RangeSrV2Parameters params;
    private final Map<String, Instant> cooldownUntilByInstrument = new LinkedHashMap<>();

    RangeSrV2Strategy(RangeSrV2Parameters params) {
        this.params = requireNonNull(params, "params");
    }

    @Override
    public String strategyId() {
        return RangeSrV2StrategyProvider.STRATEGY_ID;
    }

    @Override
    public List<StrategyCapability> lifecycleCapabilities() {
        return List.of(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        BarEvent current = context.currentBar();
        String instrumentId = current.instrument().instrumentId();
        Instant cooldownUntil = cooldownUntilByInstrument.get(instrumentId);
        if (cooldownUntil != null && current.occurredAt().isBefore(cooldownUntil)) {
            return StrategyIntentResult.empty();
        }

        List<BarEvent> bars15m = last(context.history("M15"), params.ltfLookback());
        List<BarEvent> bars4h = last(context.history("H4"), params.htfLookback());
        if (bars15m.size() < 20 || bars4h.size() < 50) {
            return StrategyIntentResult.empty();
        }

        Optional<Setup> setup = evaluate(current, bars4h, bars15m);
        if (setup.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        Setup accepted = setup.get();
        TradeSignal signal = signal(current, accepted);
        StrategyTradeIntent intent = intent(context, accepted);
        if (params.cooldownHours() > 0) {
            cooldownUntilByInstrument.put(instrumentId, current.occurredAt().plus(Duration.ofHours(params.cooldownHours())));
        }
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    static BigDecimal ema(List<BarEvent> bars, int period) {
        if (bars.size() < period) {
            return BigDecimal.ZERO;
        }
        BigDecimal seed = bars.subList(0, period).stream()
                .map(bar -> bar.ohlcv().close())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), MC);
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), MC);
        BigDecimal ema = seed;
        for (int index = period; index < bars.size(); index++) {
            BigDecimal close = bars.get(index).ohlcv().close();
            ema = close.subtract(ema, MC).multiply(multiplier, MC).add(ema, MC);
        }
        return ema;
    }

    static BigDecimal atr(List<BarEvent> bars, int period) {
        if (bars.size() < period + 1) {
            return BigDecimal.ZERO;
        }
        int start = Math.max(1, bars.size() - period);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int index = start; index < bars.size(); index++) {
            sum = sum.add(trueRange(bars.get(index), bars.get(index - 1)), MC);
            count++;
        }
        return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), MC);
    }

    static BigDecimal adx(List<BarEvent> bars, int period) {
        if (bars.size() < period + 1) {
            return BigDecimal.ZERO;
        }
        int start = Math.max(1, bars.size() - period);
        BigDecimal tr = BigDecimal.ZERO;
        BigDecimal plusDm = BigDecimal.ZERO;
        BigDecimal minusDm = BigDecimal.ZERO;
        for (int index = start; index < bars.size(); index++) {
            BarEvent current = bars.get(index);
            BarEvent previous = bars.get(index - 1);
            BigDecimal upMove = current.ohlcv().high().subtract(previous.ohlcv().high(), MC);
            BigDecimal downMove = previous.ohlcv().low().subtract(current.ohlcv().low(), MC);
            if (upMove.compareTo(downMove) > 0 && upMove.signum() > 0) {
                plusDm = plusDm.add(upMove, MC);
            }
            if (downMove.compareTo(upMove) > 0 && downMove.signum() > 0) {
                minusDm = minusDm.add(downMove, MC);
            }
            tr = tr.add(trueRange(current, previous), MC);
        }
        if (tr.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal plusDi = plusDm.multiply(ONE_HUNDRED, MC).divide(tr, MC);
        BigDecimal minusDi = minusDm.multiply(ONE_HUNDRED, MC).divide(tr, MC);
        BigDecimal denominator = plusDi.add(minusDi, MC);
        return denominator.signum() == 0
                ? BigDecimal.ZERO
                : plusDi.subtract(minusDi, MC).abs().multiply(ONE_HUNDRED, MC).divide(denominator, MC);
    }

    static List<Pivot> fractalPivots(List<BarEvent> bars, int lookback) {
        List<Pivot> pivots = new ArrayList<>();
        if (bars.size() < lookback * 2 + 1) {
            return pivots;
        }
        for (int index = lookback; index < bars.size() - lookback; index++) {
            BarEvent candidate = bars.get(index);
            boolean high = true;
            boolean low = true;
            for (int offset = 1; offset <= lookback; offset++) {
                if (candidate.ohlcv().high().compareTo(bars.get(index - offset).ohlcv().high()) <= 0
                        || candidate.ohlcv().high().compareTo(bars.get(index + offset).ohlcv().high()) <= 0) {
                    high = false;
                }
                if (candidate.ohlcv().low().compareTo(bars.get(index - offset).ohlcv().low()) >= 0
                        || candidate.ohlcv().low().compareTo(bars.get(index + offset).ohlcv().low()) >= 0) {
                    low = false;
                }
            }
            if (high) {
                pivots.add(new Pivot(PivotType.HIGH, candidate.ohlcv().high(), candidate.occurredAt(), index));
            }
            if (low) {
                pivots.add(new Pivot(PivotType.LOW, candidate.ohlcv().low(), candidate.occurredAt(), index));
            }
        }
        return pivots;
    }

    private Optional<Setup> evaluate(BarEvent current, List<BarEvent> bars4h, List<BarEvent> bars15m) {
        BigDecimal price = current.ohlcv().close();
        BigDecimal adx4h = adx(bars4h, ADX_PERIOD);
        if (adx4h.compareTo(params.minTrendAdx()) < 0) {
            return Optional.empty();
        }
        BigDecimal ema50 = ema(bars4h, EMA_PERIOD);
        Side side;
        if (price.compareTo(ema50) > 0) {
            side = Side.BUY;
        } else if (price.compareTo(ema50) < 0) {
            side = Side.SELL;
        } else {
            return Optional.empty();
        }

        List<BarEvent> structureBars = params.use15mStructure() ? bars15m : bars4h;
        List<Pivot> pivots = fractalPivots(structureBars, params.pivotLookback());
        Optional<Pivot> high = pivots.stream()
                .filter(pivot -> pivot.type() == PivotType.HIGH && pivot.price().compareTo(price) > 0)
                .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
        Optional<Pivot> low = pivots.stream()
                .filter(pivot -> pivot.type() == PivotType.LOW && pivot.price().compareTo(price) < 0)
                .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
        if (high.isEmpty() || low.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal rangeHigh = high.get().price();
        BigDecimal rangeLow = low.get().price();
        BigDecimal midline = rangeHigh.add(rangeLow, MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal rangeTolerance = rangeHigh.subtract(rangeLow, MC).abs().multiply(params.midlineTolerancePct(), MC);
        PositionInRange position = position(price, midline, rangeTolerance);
        if (side == Side.BUY && position != PositionInRange.DISCOUNT) {
            return Optional.empty();
        }
        if (side == Side.SELL && position != PositionInRange.PREMIUM) {
            return Optional.empty();
        }

        Pivot defended = side == Side.BUY ? low.get() : high.get();
        BigDecimal level = defended.price();
        int confluence = confluenceScore(level, rangeHigh, rangeLow, pivots, bars4h, side);
        if (confluence < params.minConfluence()) {
            return Optional.empty();
        }
        if (!touchesLevel(bars15m.get(bars15m.size() - 1), level, side)) {
            return Optional.empty();
        }
        Optional<Pattern> pattern = reversalPattern(bars15m, side)
                .filter(candidate -> candidate.confidence().compareTo(params.minPatternConfidence()) >= 0);
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        BarEvent lastBar = bars15m.get(bars15m.size() - 1);
        if (side == Side.BUY && lastBar.ohlcv().low().compareTo(level) < 0 && lastBar.ohlcv().close().compareTo(level) <= 0) {
            return Optional.empty();
        }
        if (side == Side.SELL && lastBar.ohlcv().high().compareTo(level) > 0 && lastBar.ohlcv().close().compareTo(level) >= 0) {
            return Optional.empty();
        }

        BigDecimal atr15m = atr(bars15m, ATR_PERIOD);
        if (atr15m.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal stop = side == Side.BUY
                ? level.subtract(params.atrMultSL().multiply(atr15m, MC), MC)
                : level.add(params.atrMultSL().multiply(atr15m, MC), MC);
        BigDecimal risk = price.subtract(stop, MC).abs();
        if (risk.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal minTargetDistance = params.atrMultMinRR().multiply(risk, MC);
        Optional<Pivot> targetPivot = targetPivot(pivots, price, side, minTargetDistance);
        if (targetPivot.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal target = targetPivot.get().price();
        BigDecimal reward = target.subtract(price, MC).abs();
        BigDecimal rr = reward.divide(risk, MC);
        BigDecimal quantity = params.riskUsdPerTrade().divide(risk, MC);
        return Optional.of(new Setup(side, price, stop, target, risk, rr, quantity, level, adx4h, ema50, midline, confluence, pattern.get()));
    }

    private TradeSignal signal(BarEvent bar, Setup setup) {
        Direction direction = setup.side() == Side.BUY ? Direction.LONG : Direction.SHORT;
        return new TradeSignal(
                RangeSrV2StrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value().toLowerCase(Locale.ROOT) + '-' + direction.name().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                new ConfidenceScore(normalize(setup.pattern().confidence())),
                SetupType.PULLBACK,
                new TimeHorizon("intraday", Duration.ofHours(Math.max(1, params.cooldownHours()))),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                new SuggestedTradeParams(decimal(setup.entry()), decimal(setup.stop()), decimal(setup.target()), decimal(setup.quantity()), OrderType.MARKET),
                tags(setup),
                bar.cohort(),
                bar.baseline()
        );
    }

    private StrategyTradeIntent intent(StrategyExecutionContext context, Setup setup) {
        BarEvent bar = context.currentBar();
        PositionSide side = setup.side() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
        StrategyTradeAction action = setup.side() == Side.BUY ? StrategyTradeAction.ENTER_LONG : StrategyTradeAction.ENTER_SHORT;
        return new StrategyTradeIntent(
                "1.0.0",
                new StrategyIntentId("intent-" + strategyId() + "-entry-" + bar.eventId().value().toLowerCase(Locale.ROOT)),
                strategyId(),
                RangeSrV2StrategyProvider.STRATEGY_VERSION,
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                side,
                new ConfidenceScore(normalize(setup.pattern().confidence())),
                SetupType.PULLBACK,
                LifecycleRole.ENTRY,
                TradeIntentEntry.marketNextOpen(),
                new TradeIntentExitPolicy(
                        new TradeIntentExitRule(StrategyExitRuleType.STRUCTURE, decimal(setup.stop()), "Stop beyond defended structure plus ATR buffer"),
                        new TradeIntentExitRule(StrategyExitRuleType.STRUCTURE, decimal(setup.target()), "Target is first real structure pivot at minimum RR"),
                        null
                ),
                new TradeIntentSizing(StrategySizingType.NORMALIZED_UNITS, decimal(setup.quantity()), null, null, null),
                new TradeIntentHorizon(Math.max(1, params.cooldownHours() * 4), Duration.ofHours(Math.max(1, params.cooldownHours())), IntendedHorizonLabel.INTRADAY),
                new TradeIntentPreconditions(true, false, PositionSide.ANY, null),
                null,
                reason(setup),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                bar.cohort(),
                bar.baseline()
        );
    }

    private StrategyTradeIntentReason reason(Setup setup) {
        List<String> evidence = List.of(
                "side=" + setup.side(),
                "pattern=" + setup.pattern().type(),
                "patternTier=" + setup.pattern().tier(),
                "confluence=" + setup.confluence(),
                "entry=" + decimal(setup.entry()).toPlainString(),
                "level=" + decimal(setup.level()).toPlainString(),
                "stop=" + decimal(setup.stop()).toPlainString(),
                "target=" + decimal(setup.target()).toPlainString(),
                "risk=" + decimal(setup.risk()).toPlainString(),
                "rr=" + decimal(setup.rr()).toPlainString(),
                "requestedUnits=" + decimal(setup.quantity()).toPlainString(),
                "adx4h=" + decimal(setup.adx4h()).toPlainString(),
                "ema50h4=" + decimal(setup.ema50()).toPlainString(),
                "midline=" + decimal(setup.midline()).toPlainString()
        );
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                condition("h4-trend", "H4 ADX trend gate", "adx4h", setup.adx4h(), ">=", "minTrendAdx", params.minTrendAdx(), true),
                condition("zone-match", "Premium/discount zone gate", "side", setup.side().name(), "matches", "zone", setup.side() == Side.BUY ? "DISCOUNT" : "PREMIUM", true),
                condition("confluence", "Structure confluence gate", "confluence", BigDecimal.valueOf(setup.confluence()), ">=", "minConfluence", BigDecimal.valueOf(params.minConfluence()), true),
                condition("pattern", "M15 reversal pattern gate", "patternConfidence", setup.pattern().confidence(), ">=", "minPatternConfidence", params.minPatternConfidence(), true),
                condition("rr", "Real target RR gate", "rr", setup.rr(), ">=", "minRR", params.atrMultMinRR(), true)
        );
        return new StrategyTradeIntentReason(
                "Range S/R v2 " + setup.side().name().toLowerCase(Locale.ROOT) + " pullback at defended structure with real target",
                evidence,
                List.of("range-sr-v2", "h4-structure", "m15-confirmation", setup.pattern().type()),
                conditions
        );
    }

    private static StrategyTradeIntentConditionEvidence condition(
            String id,
            String label,
            String leftName,
            BigDecimal leftValue,
            String operator,
            String rightName,
            BigDecimal rightValue,
            boolean passed
    ) {
        String message = leftName + "=" + decimal(leftValue).toPlainString() + " " + operator + " " + rightName + "=" + decimal(rightValue).toPlainString();
        return new StrategyTradeIntentConditionEvidence(id, label, leftName, decimal(leftValue), operator, rightName, decimal(rightValue), passed, message);
    }

    private static StrategyTradeIntentConditionEvidence condition(
            String id,
            String label,
            String leftName,
            String leftValue,
            String operator,
            String rightName,
            String rightValue,
            boolean passed
    ) {
        String message = leftName + "=" + leftValue + " " + operator + " " + rightName + "=" + rightValue;
        return new StrategyTradeIntentConditionEvidence(id, label, leftName, null, operator, rightName, null, passed, message);
    }

    private TagSet tags(Setup setup) {
        return new TagSet(List.of(
                "strategy=range-sr-v2",
                "pattern=" + setup.pattern().type(),
                "confluence=" + setup.confluence(),
                "rr=" + decimal(setup.rr()).toPlainString(),
                "risk=" + decimal(setup.risk()).toPlainString()
        ));
    }

    private int confluenceScore(BigDecimal level, BigDecimal rangeHigh, BigDecimal rangeLow, List<Pivot> pivots, List<BarEvent> bars4h, Side side) {
        int score = 0;
        if (nearPivot(level, pivots)) {
            score++;
        }
        if (near(level, roundNumber(level))) {
            score++;
        }
        BigDecimal range = rangeHigh.subtract(rangeLow, MC);
        BigDecimal fib50 = rangeLow.add(range.multiply(BigDecimal.valueOf(0.50), MC), MC);
        BigDecimal fib618 = rangeLow.add(range.multiply(BigDecimal.valueOf(0.618), MC), MC);
        if (near(level, fib50) || near(level, fib618)) {
            score++;
        }
        List<BarEvent> recent = last(bars4h, Math.min(30, bars4h.size()));
        BigDecimal extreme = side == Side.BUY
                ? recent.stream().map(bar -> bar.ohlcv().low()).min(BigDecimal::compareTo).orElse(level)
                : recent.stream().map(bar -> bar.ohlcv().high()).max(BigDecimal::compareTo).orElse(level);
        if (near(level, extreme)) {
            score++;
        }
        return score;
    }

    private Optional<Pattern> reversalPattern(List<BarEvent> bars, Side side) {
        if (bars.size() < 2) {
            return Optional.empty();
        }
        List<Pattern> patterns = side == Side.BUY ? bullishPatterns(bars) : bearishPatterns(bars);
        return patterns.stream()
                .filter(pattern -> pattern.side() == side)
                .max(Comparator.comparing(Pattern::confidence));
    }

    private List<Pattern> bullishPatterns(List<BarEvent> bars) {
        List<Pattern> patterns = new ArrayList<>();
        BarEvent current = bars.get(bars.size() - 1);
        BarEvent previous = bars.get(bars.size() - 2);
        if (bearish(previous) && bullish(current)
                && current.ohlcv().open().compareTo(previous.ohlcv().close()) <= 0
                && current.ohlcv().close().compareTo(previous.ohlcv().open()) >= 0) {
            patterns.add(new Pattern("bullish-engulfing", Side.BUY, BigDecimal.ONE, 1));
        }
        if (bars.size() >= 3) {
            BarEvent first = bars.get(bars.size() - 3);
            if (bearish(first) && smallBody(previous) && bullish(current)
                    && current.ohlcv().close().compareTo(midBody(first)) > 0) {
                patterns.add(new Pattern("morning-star", Side.BUY, BigDecimal.ONE, 1));
            }
            if (bullish(first) && bullish(previous) && bullish(current)
                    && previous.ohlcv().close().compareTo(first.ohlcv().close()) > 0
                    && current.ohlcv().close().compareTo(previous.ohlcv().close()) > 0) {
                patterns.add(new Pattern("three-soldiers", Side.BUY, BigDecimal.ONE, 1));
            }
        }
        if (bearish(previous) && bullish(current) && current.ohlcv().close().compareTo(midBody(previous)) > 0) {
            patterns.add(new Pattern("piercing", Side.BUY, BigDecimal.valueOf(0.8), 2));
        }
        if (hammer(current)) {
            patterns.add(new Pattern("hammer", Side.BUY, BigDecimal.valueOf(0.7), 2));
        }
        if (doji(current)) {
            patterns.add(new Pattern("doji", Side.BUY, BigDecimal.valueOf(0.5), 3));
        }
        return patterns;
    }

    private List<Pattern> bearishPatterns(List<BarEvent> bars) {
        List<Pattern> patterns = new ArrayList<>();
        BarEvent current = bars.get(bars.size() - 1);
        BarEvent previous = bars.get(bars.size() - 2);
        if (bullish(previous) && bearish(current)
                && current.ohlcv().open().compareTo(previous.ohlcv().close()) >= 0
                && current.ohlcv().close().compareTo(previous.ohlcv().open()) <= 0) {
            patterns.add(new Pattern("bearish-engulfing", Side.SELL, BigDecimal.ONE, 1));
        }
        if (bars.size() >= 3) {
            BarEvent first = bars.get(bars.size() - 3);
            if (bullish(first) && smallBody(previous) && bearish(current)
                    && current.ohlcv().close().compareTo(midBody(first)) < 0) {
                patterns.add(new Pattern("evening-star", Side.SELL, BigDecimal.ONE, 1));
            }
            if (bearish(first) && bearish(previous) && bearish(current)
                    && previous.ohlcv().close().compareTo(first.ohlcv().close()) < 0
                    && current.ohlcv().close().compareTo(previous.ohlcv().close()) < 0) {
                patterns.add(new Pattern("three-crows", Side.SELL, BigDecimal.ONE, 1));
            }
        }
        if (bullish(previous) && bearish(current) && current.ohlcv().close().compareTo(midBody(previous)) < 0) {
            patterns.add(new Pattern("dark-cloud", Side.SELL, BigDecimal.valueOf(0.8), 2));
        }
        if (shootingStar(current)) {
            patterns.add(new Pattern("shooting-star", Side.SELL, BigDecimal.valueOf(0.7), 2));
        }
        if (doji(current)) {
            patterns.add(new Pattern("doji", Side.SELL, BigDecimal.valueOf(0.5), 3));
        }
        return patterns;
    }

    private Optional<Pivot> targetPivot(List<Pivot> pivots, BigDecimal price, Side side, BigDecimal minTargetDistance) {
        if (side == Side.BUY) {
            BigDecimal minimum = price.add(minTargetDistance, MC);
            return pivots.stream()
                    .filter(pivot -> pivot.type() == PivotType.HIGH && pivot.price().compareTo(minimum) >= 0)
                    .min(Comparator.comparing(Pivot::price));
        }
        BigDecimal maximum = price.subtract(minTargetDistance, MC);
        return pivots.stream()
                .filter(pivot -> pivot.type() == PivotType.LOW && pivot.price().compareTo(maximum) <= 0)
                .max(Comparator.comparing(Pivot::price));
    }

    private boolean touchesLevel(BarEvent bar, BigDecimal level, Side side) {
        BigDecimal tolerance = level.multiply(params.levelTolerancePct(), MC);
        return side == Side.BUY
                ? bar.ohlcv().low().compareTo(level.add(tolerance, MC)) <= 0
                : bar.ohlcv().high().compareTo(level.subtract(tolerance, MC)) >= 0;
    }

    private boolean nearPivot(BigDecimal level, List<Pivot> pivots) {
        return pivots.stream().anyMatch(pivot -> near(level, pivot.price()));
    }

    private boolean near(BigDecimal left, BigDecimal right) {
        if (right.signum() == 0) {
            return left.subtract(right, MC).abs().compareTo(params.levelTolerancePct()) <= 0;
        }
        return left.subtract(right, MC).abs().divide(right.abs(), MC).compareTo(params.levelTolerancePct()) <= 0;
    }

    private static PositionInRange position(BigDecimal price, BigDecimal midline, BigDecimal tolerance) {
        if (price.compareTo(midline.subtract(tolerance, MC)) < 0) {
            return PositionInRange.DISCOUNT;
        }
        if (price.compareTo(midline.add(tolerance, MC)) > 0) {
            return PositionInRange.PREMIUM;
        }
        return PositionInRange.MIDLINE;
    }

    private static BigDecimal roundNumber(BigDecimal level) {
        BigDecimal absolute = level.abs();
        BigDecimal increment;
        if (absolute.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            increment = BigDecimal.valueOf(100);
        } else if (absolute.compareTo(BigDecimal.valueOf(100)) >= 0) {
            increment = BigDecimal.TEN;
        } else if (absolute.compareTo(BigDecimal.TEN) >= 0) {
            increment = BigDecimal.ONE;
        } else {
            increment = BigDecimal.valueOf(0.1);
        }
        return BigDecimal.valueOf(Math.round(level.divide(increment, MC).doubleValue())).multiply(increment, MC);
    }

    private static BigDecimal trueRange(BarEvent current, BarEvent previous) {
        BigDecimal highLow = current.ohlcv().high().subtract(current.ohlcv().low(), MC).abs();
        BigDecimal highClose = current.ohlcv().high().subtract(previous.ohlcv().close(), MC).abs();
        BigDecimal lowClose = current.ohlcv().low().subtract(previous.ohlcv().close(), MC).abs();
        return highLow.max(highClose).max(lowClose);
    }

    private static boolean bullish(BarEvent bar) {
        return bar.ohlcv().close().compareTo(bar.ohlcv().open()) > 0;
    }

    private static boolean bearish(BarEvent bar) {
        return bar.ohlcv().close().compareTo(bar.ohlcv().open()) < 0;
    }

    private static BigDecimal body(BarEvent bar) {
        return bar.ohlcv().close().subtract(bar.ohlcv().open(), MC).abs();
    }

    private static BigDecimal range(BarEvent bar) {
        return bar.ohlcv().high().subtract(bar.ohlcv().low(), MC).abs();
    }

    private static boolean smallBody(BarEvent bar) {
        BigDecimal range = range(bar);
        return range.signum() > 0 && body(bar).divide(range, MC).compareTo(BigDecimal.valueOf(0.35)) <= 0;
    }

    private static BigDecimal midBody(BarEvent bar) {
        return bar.ohlcv().open().add(bar.ohlcv().close(), MC).divide(BigDecimal.valueOf(2), MC);
    }

    private static boolean hammer(BarEvent bar) {
        BigDecimal body = body(bar);
        BigDecimal lowerWick = bar.ohlcv().open().min(bar.ohlcv().close()).subtract(bar.ohlcv().low(), MC);
        BigDecimal upperWick = bar.ohlcv().high().subtract(bar.ohlcv().open().max(bar.ohlcv().close()), MC);
        return body.signum() > 0 && lowerWick.compareTo(body.multiply(BigDecimal.valueOf(2), MC)) >= 0
                && upperWick.compareTo(body) <= 0;
    }

    private static boolean shootingStar(BarEvent bar) {
        BigDecimal body = body(bar);
        BigDecimal upperWick = bar.ohlcv().high().subtract(bar.ohlcv().open().max(bar.ohlcv().close()), MC);
        BigDecimal lowerWick = bar.ohlcv().open().min(bar.ohlcv().close()).subtract(bar.ohlcv().low(), MC);
        return body.signum() > 0 && upperWick.compareTo(body.multiply(BigDecimal.valueOf(2), MC)) >= 0
                && lowerWick.compareTo(body) <= 0;
    }

    private static boolean doji(BarEvent bar) {
        BigDecimal range = range(bar);
        return range.signum() > 0 && body(bar).divide(range, MC).compareTo(BigDecimal.valueOf(0.10)) <= 0;
    }

    private static <T> List<T> last(List<T> values, int count) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.subList(Math.max(0, values.size() - count), values.size());
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    enum Side {
        BUY,
        SELL
    }

    enum PivotType {
        HIGH,
        LOW
    }

    enum PositionInRange {
        DISCOUNT,
        PREMIUM,
        MIDLINE
    }

    record Pivot(PivotType type, BigDecimal price, Instant occurredAt, int index) {
    }

    record Pattern(String type, Side side, BigDecimal confidence, int tier) {
    }

    record Setup(
            Side side,
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal target,
            BigDecimal risk,
            BigDecimal rr,
            BigDecimal quantity,
            BigDecimal level,
            BigDecimal adx4h,
            BigDecimal ema50,
            BigDecimal midline,
            int confluence,
            Pattern pattern
    ) {
    }
}
