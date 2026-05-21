package org.algotradex.strategy.samples.trendpullbackv3;

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
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
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
 * Trend Pullback v3 — evolution of the {@code range-sr-v2} sample.
 *
 * <p>Same archetype: H4 trend gate + defended-level confluence + reversal-pattern confirmation +
 * structural stop + structural target + fixed-USD risk sizing. v3 changes the dials so the strategy
 * emits more trades while preserving the structural-pullback edge:
 *
 * <ul>
 *   <li>{@link TrendPullbackV3Parameters.PatternTier#TIER1_OR_TIER2} default — accepts piercing,
 *       hammer, dark-cloud, shooting-star (in addition to the original tier-1 patterns).
 *   <li>Volatility-adaptive level tolerance — scales by execution-bar ATR/price.
 *   <li>{@link TrendPullbackV3Parameters.PivotSource#HYBRID} default — H4 for trend + zone, LTF
 *       pivots for level + confluence + target. More candidate levels, same structural rigor.
 *   <li>ADX floor lowered from 20 to 15 — opens lower-trend instruments.
 *   <li>Tier-2 + 4-of-4 confluence equivalence — tier-2 pattern at A++ confluence counts as tier-1.
 *   <li>MIDLINE-zone entry allowed when confluence == 4 (opt-in via parameter).
 *   <li>Second-touch within cooldown allowed when confluence has strictly increased.
 *   <li>Per-rejection diagnostics emitted on every empty bar (when enabled) so the UI can show
 *       why an instrument didn't trade.
 * </ul>
 *
 * <p>The strategy is deterministic for the same ordered LTF/H4 histories and effective parameters,
 * but it is not thread-safe (uses per-instrument state). Use a fresh, run-scoped instance.
 *
 * <p>The sample does not own execution, broker routing, exchange session rules, lot/tick conversion,
 * slippage, or portfolio accounting. The {@code riskUsdPerTrade} parameter is sample sizing input
 * for intent metadata, not a broker-side risk guarantee.
 */
public final class TrendPullbackV3Strategy implements TradeIntentStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int ADX_PERIOD = 14;
    private static final int EMA_PERIOD = 50;
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal HALF = BigDecimal.valueOf(0.5);
    private static final BigDecimal MIN_NORMALIZED_UNITS = new BigDecimal("0.0001");

    private final TrendPullbackV3Parameters params;
    private final Map<String, Instant> cooldownUntilByInstrument = new LinkedHashMap<>();
    private final Map<String, Integer> lastSignalConfluenceByInstrument = new LinkedHashMap<>();

    TrendPullbackV3Strategy(TrendPullbackV3Parameters params) {
        this.params = requireNonNull(params, "params");
    }

    @Override
    public String strategyId() {
        return TrendPullbackV3StrategyProvider.STRATEGY_ID;
    }

    @Override
    public String stateSchemaVersion() {
        return "trend-pullback-v3-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "cooldownUntilByInstrument", Map.copyOf(cooldownUntilByInstrument),
                "lastSignalConfluenceByInstrument", Map.copyOf(lastSignalConfluenceByInstrument)
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        cooldownUntilByInstrument.clear();
        lastSignalConfluenceByInstrument.clear();
        Object cooldowns = state == null ? null : state.get("cooldownUntilByInstrument");
        if (cooldowns instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    cooldownUntilByInstrument.put(key.toString(), instant(value));
                }
            });
        }
        Object confluence = state == null ? null : state.get("lastSignalConfluenceByInstrument");
        if (confluence instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value instanceof Number number) {
                    lastSignalConfluenceByInstrument.put(key.toString(), number.intValue());
                }
            });
        }
    }

    @Override
    public Map<String, Object> mergeRewarmedState(Map<String, Object> checkpointState, Map<String, Object> rewarmedState) {
        return Map.copyOf(checkpointState == null ? Map.of() : checkpointState);
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

    private static Instant instant(Object value) {
        return value instanceof Instant instant ? instant : Instant.parse(value.toString());
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        BarEvent current = context.currentBar();
        String instrumentId = current.instrument().instrumentId();
        Instant cooldownUntil = cooldownUntilByInstrument.get(instrumentId);
        boolean inCooldown = cooldownUntil != null && current.occurredAt().isBefore(cooldownUntil);

        String executionTimeframe = executionTimeframe(current);
        List<BarEvent> executionBars = last(context.history(executionTimeframe), params.ltfLookback());
        List<BarEvent> bars4h = last(context.history("H4"), params.htfLookback());
        if (executionBars.size() < 20 || bars4h.size() < 50) {
            return diagnosticsOnly("reject:history-insufficient",
                    "executionBars=" + executionBars.size() + " bars4h=" + bars4h.size());
        }

        // PR-S6: When in cooldown, only allow if PR-S8 second-touch-with-higher-confluence is enabled
        // — but we still need to *evaluate* the bar to discover the candidate confluence. If the
        // candidate confluence isn't strictly higher than the previous signal, we drop with a
        // diagnostic indicating "reject:cooldown-active".
        Evaluation evaluation = evaluate(current, bars4h, executionBars, executionTimeframe);
        if (evaluation.setup().isEmpty()) {
            return diagnosticsOnly(evaluation.rejectionCode(), evaluation.rejectionDetail());
        }

        Setup accepted = evaluation.setup().get();
        if (inCooldown) {
            int previousConfluence = lastSignalConfluenceByInstrument.getOrDefault(instrumentId, 0);
            if (!params.allowSecondTouchInCooldown() || accepted.confluence() <= previousConfluence) {
                return diagnosticsOnly("reject:cooldown-active",
                        "cooldownUntil=" + cooldownUntil + " confluence=" + accepted.confluence()
                                + " priorConfluence=" + previousConfluence);
            }
        }

        TradeSignal signal = signal(current, accepted);
        StrategyTradeIntent intent = intent(context, accepted);
        if (params.cooldownHours() > 0) {
            cooldownUntilByInstrument.put(instrumentId,
                    current.occurredAt().plus(Duration.ofHours(params.cooldownHours())));
        }
        lastSignalConfluenceByInstrument.put(instrumentId, accepted.confluence());
        List<String> diagnostics = params.emitDiagnostics()
                ? List.of("accept:" + accepted.side().name().toLowerCase(Locale.ROOT) + ":conf-" + accepted.confluence())
                : List.of();
        return new StrategyIntentResult(List.of(signal), List.of(intent), diagnostics);
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        BarEvent current = context.currentBar();
        String executionTimeframe = executionTimeframe(current);
        List<BarEvent> executionBars = last(context.history(executionTimeframe), params.ltfLookback());
        List<BarEvent> bars4h = last(context.history("H4"), params.htfLookback());
        if (executionBars.size() < 20 || bars4h.size() < 50) {
            return List.of(
                    numericEvidence("h4-trend", "H4 trend strength", ConditionRole.REGIME_FILTER, false,
                            "bars4h", BigDecimal.valueOf(bars4h.size()), ">=", "requiredBars", BigDecimal.valueOf(50),
                            "Need at least 50 H4 bars before trend strength is reliable."),
                    numericEvidence("pullback-pattern", "Pullback pattern", ConditionRole.ENTRY_TRIGGER, false,
                            "executionBars", BigDecimal.valueOf(executionBars.size()), ">=", "requiredBars", BigDecimal.valueOf(20),
                            "Need at least 20 execution bars before pullback pattern checks run.")
            );
        }
        Evaluation evaluation = evaluate(current, bars4h, executionBars, executionTimeframe);
        if (evaluation.setup().isPresent()) {
            Setup accepted = evaluation.setup().get();
            return List.of(
                    numericEvidence("h4-trend", "H4 trend strength", ConditionRole.REGIME_FILTER, true,
                            "adx4h", accepted.adx4h(), ">=", "minTrendAdx", params.minTrendAdx(),
                            "H4 trend strength passed."),
                    new ThoughtConditionEvidence("pivot-source", "Pivot source context", ConditionRole.ENTRY_FILTER, true,
                            "Pivot source " + params.pivotSource() + " produced a defended " + accepted.position() + " zone."),
                    numericEvidence("pullback-pattern", "Pullback pattern", ConditionRole.ENTRY_TRIGGER, true,
                            "patternConfidence", accepted.pattern().confidence(), ">=", "minPatternConfidence", params.minPatternConfidence(),
                            accepted.pattern().type() + " confirms the pullback."),
                    numericEvidence("confluence", "Structure confluence", ConditionRole.ENTRY_FILTER, true,
                            "confluence", BigDecimal.valueOf(accepted.confluence()), ">=", "minConfluence", BigDecimal.valueOf(params.minConfluence()),
                            "Defended level has enough confluence."),
                    new ThoughtConditionEvidence("tolerance", "Adaptive level tolerance", ConditionRole.ENTRY_FILTER, true,
                            params.volatilityAdaptiveTolerance() ? "ATR-scaled tolerance accepted the level touch." : "Fixed tolerance accepted the level touch."),
                    numericEvidence("rr", "Reward/risk", ConditionRole.RISK_GUARD, true,
                            "rr", accepted.rr(), ">=", "minRR", params.atrMultMinRR(),
                            "Nearest real structure target satisfies the reward/risk requirement.")
            );
        }
        String blockedId = conditionIdForRejection(evaluation.rejectionCode());
        return List.of(
                new ThoughtConditionEvidence(blockedId, labelForCondition(blockedId), roleForCondition(blockedId), false,
                        evaluation.rejectionCode() + " " + evaluation.rejectionDetail())
        );
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        BarEvent current = context.currentBar();
        if (context.instrumentPosition().hasPosition()) {
            return "risk";
        }
        Instant cooldownUntil = cooldownUntilByInstrument.get(current.instrument().instrumentId());
        if (cooldownUntil != null && current.occurredAt().isBefore(cooldownUntil)) {
            return "risk";
        }
        String executionTimeframe = executionTimeframe(current);
        List<BarEvent> executionBars = last(context.history(executionTimeframe), params.ltfLookback());
        List<BarEvent> bars4h = last(context.history("H4"), params.htfLookback());
        if (bars4h.size() < 50) {
            return "regime";
        }
        if (executionBars.size() < 20) {
            return "trigger";
        }
        Evaluation evaluation = evaluate(current, bars4h, executionBars, executionTimeframe);
        if (evaluation.setup().isPresent()) {
            return "risk";
        }
        return phaseForCondition(conditionIdForRejection(evaluation.rejectionCode()));
    }

    private StrategyIntentResult diagnosticsOnly(String code, String detail) {
        if (!params.emitDiagnostics()) {
            return StrategyIntentResult.empty();
        }
        return new StrategyIntentResult(List.of(), List.of(), List.of(code + " " + detail));
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

    private static String executionTimeframe(BarEvent current) {
        String timeframe = current.timeframe();
        return timeframe == null || timeframe.isBlank() ? "M15" : timeframe.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Evaluates the current bar; returns either an accepted setup or a rejection code + detail
     * that callers can surface as diagnostics.
     */
    private Evaluation evaluate(BarEvent current, List<BarEvent> bars4h, List<BarEvent> executionBars, String executionTimeframe) {
        BigDecimal price = current.ohlcv().close();
        BigDecimal adx4h = adx(bars4h, ADX_PERIOD);
        if (adx4h.compareTo(params.minTrendAdx()) < 0) {
            return Evaluation.reject("reject:adx-below-min",
                    "adx4h=" + decimal(adx4h) + " minTrendAdx=" + decimal(params.minTrendAdx()));
        }
        BigDecimal ema50 = ema(bars4h, EMA_PERIOD);
        Side side;
        if (price.compareTo(ema50) > 0) {
            side = Side.BUY;
        } else if (price.compareTo(ema50) < 0) {
            side = Side.SELL;
        } else {
            return Evaluation.reject("reject:price-at-ema50",
                    "price=" + decimal(price) + " ema50=" + decimal(ema50));
        }

        // PR-S5: HYBRID/LTF pivot source. HYBRID = H4 for trend + zone; LTF pivots for level
        //        proximity + confluence + target.
        List<BarEvent> structureBars = switch (params.pivotSource()) {
            case HTF -> bars4h;
            case LTF -> executionBars;
            case HYBRID -> executionBars;
        };
        List<Pivot> pivots = fractalPivots(structureBars, params.pivotLookback());
        // For HYBRID, the range high/low + midline use H4 pivots (trend zone semantics); confluence
        // uses LTF pivots (level density). For HTF and LTF modes, both come from the chosen source.
        List<Pivot> zonePivots = params.pivotSource() == TrendPullbackV3Parameters.PivotSource.HYBRID
                ? fractalPivots(bars4h, params.pivotLookback())
                : pivots;
        Optional<Pivot> zoneHigh = zonePivots.stream()
                .filter(pivot -> pivot.type() == PivotType.HIGH && pivot.price().compareTo(price) > 0)
                .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
        Optional<Pivot> zoneLow = zonePivots.stream()
                .filter(pivot -> pivot.type() == PivotType.LOW && pivot.price().compareTo(price) < 0)
                .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
        if (zoneHigh.isEmpty() || zoneLow.isEmpty()) {
            return Evaluation.reject("reject:no-zone-pivots", "missing high/low pivot for zone");
        }

        BigDecimal rangeHigh = zoneHigh.get().price();
        BigDecimal rangeLow = zoneLow.get().price();
        BigDecimal midline = rangeHigh.add(rangeLow, MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal rangeTolerance = rangeHigh.subtract(rangeLow, MC).abs().multiply(params.midlineTolerancePct(), MC);
        PositionInRange position = position(price, midline, rangeTolerance);

        // PR-S9: MIDLINE-zone entries allowed only when confluence == 4. Defer the
        //        decision to after confluence is computed (so the diagnostic is precise).
        boolean midlineAllowed = params.allowMidlineWithMaxConfluence();
        if (side == Side.BUY && position == PositionInRange.PREMIUM) {
            return Evaluation.reject("reject:zone-wrong-side-long-in-premium",
                    "side=BUY position=PREMIUM");
        }
        if (side == Side.SELL && position == PositionInRange.DISCOUNT) {
            return Evaluation.reject("reject:zone-wrong-side-short-in-discount",
                    "side=SELL position=DISCOUNT");
        }
        if (position == PositionInRange.MIDLINE && !midlineAllowed) {
            return Evaluation.reject("reject:zone-midline-disallowed", "position=MIDLINE");
        }

        // Defended level uses LTF pivots in HYBRID/LTF mode for finer structure; HTF mode uses
        // the H4 zone pivot we already have.
        Pivot defended = switch (params.pivotSource()) {
            case HTF -> side == Side.BUY ? zoneLow.get() : zoneHigh.get();
            case LTF, HYBRID -> findDefendedPivot(pivots, price, side).orElse(side == Side.BUY ? zoneLow.get() : zoneHigh.get());
        };
        BigDecimal level = defended.price();

        BigDecimal atrExecution = atr(executionBars, ATR_PERIOD);
        if (atrExecution.signum() <= 0) {
            return Evaluation.reject("reject:atr-zero", "atrExecution=0 — insufficient TR history");
        }
        BigDecimal effectiveLevelTolerance = effectiveLevelTolerance(price, atrExecution);

        int confluence = confluenceScore(level, rangeHigh, rangeLow, pivots, bars4h, side, effectiveLevelTolerance);
        // PR-S9: midline-zone entry requires confluence == 4 (max)
        if (position == PositionInRange.MIDLINE && confluence < 4) {
            return Evaluation.reject("reject:zone-midline-below-max-confluence",
                    "confluence=" + confluence + " required=4 (for midline)");
        }
        // PR-S4: tier-2 + 4-of-4 confluence counts as tier-1 equivalent (always; doesn't depend on
        // patternTier setting). Track this separately for the pattern gate below.
        boolean confluenceMaxedOut = confluence == 4;
        if (confluence < params.minConfluence()) {
            return Evaluation.reject("reject:confluence-below-min",
                    "confluence=" + confluence + " minConfluence=" + params.minConfluence());
        }

        if (!touchesLevel(executionBars.get(executionBars.size() - 1), level, side, effectiveLevelTolerance)) {
            return Evaluation.reject("reject:level-not-touched",
                    "tolerance=" + decimal(effectiveLevelTolerance)
                            + (params.volatilityAdaptiveTolerance() ? " (adaptive)" : " (fixed)"));
        }

        Optional<Pattern> patternOpt = reversalPattern(executionBars, side);
        if (patternOpt.isEmpty()) {
            return Evaluation.reject("reject:no-pattern", "no reversal pattern at level");
        }
        Pattern pattern = patternOpt.get();
        BigDecimal minPatternConfidence = params.minPatternConfidence();
        boolean tier2AsTier1 = params.tier2WithMaxConfluenceCountsAsTier1()
                && confluenceMaxedOut
                && pattern.confidence().compareTo(BigDecimal.valueOf(0.7)) >= 0;
        if (pattern.confidence().compareTo(minPatternConfidence) < 0 && !tier2AsTier1) {
            return Evaluation.reject("reject:pattern-confidence-below-min",
                    "pattern=" + pattern.type() + " confidence=" + decimal(pattern.confidence())
                            + " min=" + decimal(minPatternConfidence));
        }

        BarEvent lastBar = executionBars.get(executionBars.size() - 1);
        if (side == Side.BUY && lastBar.ohlcv().low().compareTo(level) < 0 && lastBar.ohlcv().close().compareTo(level) <= 0) {
            return Evaluation.reject("reject:overshoot-long",
                    "low closed through level for long entry");
        }
        if (side == Side.SELL && lastBar.ohlcv().high().compareTo(level) > 0 && lastBar.ohlcv().close().compareTo(level) >= 0) {
            return Evaluation.reject("reject:overshoot-short",
                    "high closed through level for short entry");
        }

        BigDecimal stop = side == Side.BUY
                ? level.subtract(params.atrMultSL().multiply(atrExecution, MC), MC)
                : level.add(params.atrMultSL().multiply(atrExecution, MC), MC);
        BigDecimal risk = price.subtract(stop, MC).abs();
        if (risk.signum() <= 0) {
            return Evaluation.reject("reject:risk-zero", "stop-price distance is zero");
        }
        BigDecimal minTargetDistance = params.atrMultMinRR().multiply(risk, MC);
        Optional<Pivot> targetPivot = targetPivot(pivots, price, side, minTargetDistance);
        if (targetPivot.isEmpty()) {
            return Evaluation.reject("reject:no-structural-target",
                    "no pivot at >= " + decimal(minTargetDistance) + " distance");
        }
        BigDecimal target = targetPivot.get().price();
        BigDecimal reward = target.subtract(price, MC).abs();
        BigDecimal rr = reward.divide(risk, MC);
        BigDecimal quantity = params.riskUsdPerTrade().divide(risk, MC);

        Setup setup = new Setup(side, executionTimeframe, price, stop, target, risk, rr, quantity, level,
                adx4h, ema50, midline, confluence, pattern, tier2AsTier1, position);
        return Evaluation.accept(setup);
    }

    private Optional<Pivot> findDefendedPivot(List<Pivot> pivots, BigDecimal price, Side side) {
        if (side == Side.BUY) {
            return pivots.stream()
                    .filter(pivot -> pivot.type() == PivotType.LOW && pivot.price().compareTo(price) < 0)
                    .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
        }
        return pivots.stream()
                .filter(pivot -> pivot.type() == PivotType.HIGH && pivot.price().compareTo(price) > 0)
                .min(Comparator.comparing(pivot -> pivot.price().subtract(price, MC).abs()));
    }

    /**
     * PR-S2: volatility-adaptive level tolerance. When enabled, tolerance is
     * {@code clamp(0.5 * ATR/price, adaptiveToleranceMin, adaptiveToleranceMax)}. Otherwise the
     * fixed {@code levelTolerancePct} is returned.
     */
    private BigDecimal effectiveLevelTolerance(BigDecimal price, BigDecimal atr) {
        if (!params.volatilityAdaptiveTolerance() || price.signum() <= 0 || atr.signum() <= 0) {
            return params.levelTolerancePct();
        }
        BigDecimal candidate = atr.divide(price, MC).multiply(HALF, MC);
        if (candidate.compareTo(params.adaptiveToleranceMin()) < 0) {
            return params.adaptiveToleranceMin();
        }
        if (candidate.compareTo(params.adaptiveToleranceMax()) > 0) {
            return params.adaptiveToleranceMax();
        }
        return candidate;
    }

    private TradeSignal signal(BarEvent bar, Setup setup) {
        Direction direction = setup.side() == Side.BUY ? Direction.LONG : Direction.SHORT;
        return new TradeSignal(
                TrendPullbackV3StrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value().toLowerCase(Locale.ROOT) + '-' + direction.name().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                new ConfidenceScore(normalize(setup.pattern().confidence())),
                SetupType.PULLBACK,
                new TimeHorizon("intraday", Duration.ofHours(Math.max(1, params.cooldownHours()))),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                new SuggestedTradeParams(decimal(setup.entry()), decimal(setup.stop()), decimal(setup.target()), normalizedUnits(setup.quantity()), OrderType.MARKET),
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
                TrendPullbackV3StrategyProvider.STRATEGY_VERSION,
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
                new TradeIntentSizing(StrategySizingType.NORMALIZED_UNITS, normalizedUnits(setup.quantity()), null, null, null),
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
                "patternConfidence=" + decimal(setup.pattern().confidence()).toPlainString(),
                "tier2AsTier1=" + setup.tier2AsTier1(),
                "position=" + setup.position(),
                "confluence=" + setup.confluence(),
                "pivotSource=" + params.pivotSource().name(),
                "entry=" + decimal(setup.entry()).toPlainString(),
                "level=" + decimal(setup.level()).toPlainString(),
                "stop=" + decimal(setup.stop()).toPlainString(),
                "target=" + decimal(setup.target()).toPlainString(),
                "risk=" + decimal(setup.risk()).toPlainString(),
                "rr=" + decimal(setup.rr()).toPlainString(),
                "requestedUnits=" + normalizedUnits(setup.quantity()).toPlainString(),
                "adx4h=" + decimal(setup.adx4h()).toPlainString(),
                "ema50h4=" + decimal(setup.ema50()).toPlainString(),
                "midline=" + decimal(setup.midline()).toPlainString()
        );
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                condition("h4-trend", "H4 ADX trend gate", "adx4h", setup.adx4h(), ">=", "minTrendAdx", params.minTrendAdx(), true),
                condition("zone-match", "Premium/discount zone gate", "position", setup.position().name(), "matches", "side", setup.side().name(), true),
                condition("confluence", "Structure confluence gate", "confluence", BigDecimal.valueOf(setup.confluence()), ">=", "minConfluence", BigDecimal.valueOf(params.minConfluence()), true),
                condition("pattern", setup.executionTimeframe() + " reversal pattern gate", "patternConfidence", setup.pattern().confidence(), ">=", "minPatternConfidence", params.minPatternConfidence(), true),
                condition("rr", "Real target RR gate", "rr", setup.rr(), ">=", "minRR", params.atrMultMinRR(), true)
        );
        return new StrategyTradeIntentReason(
                "Trend Pullback v3 " + setup.side().name().toLowerCase(Locale.ROOT) + " pullback at defended structure with real target",
                evidence,
                List.of("trend-pullback-v3", "h4-structure", setup.executionTimeframe().toLowerCase(Locale.ROOT) + "-confirmation",
                        setup.pattern().type(), "pivot-source-" + params.pivotSource().name().toLowerCase(Locale.ROOT)),
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

    private static ThoughtConditionEvidence numericEvidence(
            String id,
            String label,
            ConditionRole role,
            boolean passed,
            String leftName,
            BigDecimal leftValue,
            String operator,
            String rightName,
            BigDecimal rightValue,
            String message
    ) {
        BigDecimal left = decimal(leftValue);
        BigDecimal right = decimal(rightValue);
        BigDecimal distance = passed ? null : right.subtract(left, MC).abs();
        return new ThoughtConditionEvidence(id, label, role, passed, leftName, left, operator, rightName, right, distance, message);
    }

    private static String conditionIdForRejection(String code) {
        if (code == null) {
            return "pullback-pattern";
        }
        if (code.contains("adx") || code.contains("ema50")) {
            return "h4-trend";
        }
        if (code.contains("pivot") || code.contains("zone")) {
            return "pivot-source";
        }
        if (code.contains("confluence")) {
            return "confluence";
        }
        if (code.contains("level") || code.contains("tolerance")) {
            return "tolerance";
        }
        if (code.contains("pattern") || code.contains("overshoot")) {
            return "pullback-pattern";
        }
        if (code.contains("target") || code.contains("risk") || code.contains("atr")) {
            return "rr";
        }
        if (code.contains("cooldown")) {
            return "cooldown";
        }
        return "pullback-pattern";
    }

    private static String labelForCondition(String conditionId) {
        return switch (conditionId) {
            case "h4-trend" -> "H4 trend strength";
            case "pivot-source" -> "Pivot source context";
            case "confluence" -> "Structure confluence";
            case "tolerance" -> "Adaptive level tolerance";
            case "rr" -> "Reward/risk";
            case "cooldown" -> "Cooldown";
            default -> "Pullback pattern";
        };
    }

    private static ConditionRole roleForCondition(String conditionId) {
        return switch (conditionId) {
            case "h4-trend" -> ConditionRole.REGIME_FILTER;
            case "rr" -> ConditionRole.RISK_GUARD;
            case "cooldown" -> ConditionRole.POSITION_CONTEXT;
            case "pullback-pattern" -> ConditionRole.ENTRY_TRIGGER;
            default -> ConditionRole.ENTRY_FILTER;
        };
    }

    private static String phaseForCondition(String conditionId) {
        return switch (conditionId) {
            case "h4-trend" -> "regime";
            case "pivot-source", "confluence" -> "structure";
            case "rr", "cooldown" -> "risk";
            default -> "trigger";
        };
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
                "strategy=trend-pullback-v3",
                "pattern=" + setup.pattern().type(),
                "patternTier=" + setup.pattern().tier(),
                "confluence=" + setup.confluence(),
                "position=" + setup.position(),
                "pivotSource=" + params.pivotSource().name(),
                "tier2AsTier1=" + setup.tier2AsTier1(),
                "rr=" + decimal(setup.rr()).toPlainString(),
                "risk=" + decimal(setup.risk()).toPlainString()
        ));
    }

    private int confluenceScore(BigDecimal level, BigDecimal rangeHigh, BigDecimal rangeLow,
                                List<Pivot> pivots, List<BarEvent> bars4h, Side side,
                                BigDecimal levelTolerance) {
        int score = 0;
        if (nearPivot(level, pivots, levelTolerance)) {
            score++;
        }
        if (near(level, roundNumber(level), levelTolerance)) {
            score++;
        }
        BigDecimal range = rangeHigh.subtract(rangeLow, MC);
        BigDecimal fib50 = rangeLow.add(range.multiply(BigDecimal.valueOf(0.50), MC), MC);
        BigDecimal fib618 = rangeLow.add(range.multiply(BigDecimal.valueOf(0.618), MC), MC);
        if (near(level, fib50, levelTolerance) || near(level, fib618, levelTolerance)) {
            score++;
        }
        List<BarEvent> recent = last(bars4h, Math.min(30, bars4h.size()));
        BigDecimal extreme = side == Side.BUY
                ? recent.stream().map(bar -> bar.ohlcv().low()).min(BigDecimal::compareTo).orElse(level)
                : recent.stream().map(bar -> bar.ohlcv().high()).max(BigDecimal::compareTo).orElse(level);
        if (near(level, extreme, levelTolerance)) {
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

    private boolean touchesLevel(BarEvent bar, BigDecimal level, Side side, BigDecimal levelTolerance) {
        BigDecimal tolerance = level.multiply(levelTolerance, MC);
        return side == Side.BUY
                ? bar.ohlcv().low().compareTo(level.add(tolerance, MC)) <= 0
                : bar.ohlcv().high().compareTo(level.subtract(tolerance, MC)) >= 0;
    }

    private boolean nearPivot(BigDecimal level, List<Pivot> pivots, BigDecimal levelTolerance) {
        return pivots.stream().anyMatch(pivot -> near(level, pivot.price(), levelTolerance));
    }

    private boolean near(BigDecimal left, BigDecimal right, BigDecimal levelTolerance) {
        if (right.signum() == 0) {
            return left.subtract(right, MC).abs().compareTo(levelTolerance) <= 0;
        }
        return left.subtract(right, MC).abs().divide(right.abs(), MC).compareTo(levelTolerance) <= 0;
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

    private static BigDecimal normalizedUnits(BigDecimal value) {
        BigDecimal rounded = decimal(value);
        return value.signum() > 0 && rounded.signum() == 0 ? MIN_NORMALIZED_UNITS : rounded;
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
            String executionTimeframe,
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
            Pattern pattern,
            boolean tier2AsTier1,
            PositionInRange position
    ) {
    }

    /** Internal: setup or rejection-reason. */
    private record Evaluation(Optional<Setup> setup, String rejectionCode, String rejectionDetail) {
        static Evaluation accept(Setup setup) {
            return new Evaluation(Optional.of(setup), "", "");
        }
        static Evaluation reject(String code, String detail) {
            return new Evaluation(Optional.empty(), code, detail);
        }
    }
}
