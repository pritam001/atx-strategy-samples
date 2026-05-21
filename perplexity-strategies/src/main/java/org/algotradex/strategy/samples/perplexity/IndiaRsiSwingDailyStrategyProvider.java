package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextFrameSnapshot;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.enums.marketcontext.TrendDirection;
import org.algotradex.platform.core.api.indicator.RollingIndicators;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.algotradex.strategy.samples.perplexity.PerplexityStrategySupport.BarMath;

public final class IndiaRsiSwingDailyStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "india-rsi-swing-daily-v1";
    private static final List<StrategyParameterDefinition> PARAMETERS = List.of(
            PerplexityStrategySupport.integerParam("fastEmaPeriod", "Fast EMA period", "Trend-resumption EMA period.", 10, 2, 100),
            PerplexityStrategySupport.integerParam("slowEmaPeriod", "Slow EMA period", "Primary trend EMA period.", 30, 3, 300),
            PerplexityStrategySupport.integerParam("rsiPeriod", "RSI period", "Closed bars used for pullback RSI.", 14, 2, 100),
            PerplexityStrategySupport.integerParam("pullbackRsiMin", "Pullback RSI minimum", "Lower RSI bound for a tradable pullback.", 40, 1, 80),
            PerplexityStrategySupport.integerParam("pullbackRsiMax", "Pullback RSI maximum", "Upper RSI bound for a tradable pullback.", 60, 20, 99),
            PerplexityStrategySupport.integerParam("volumeLookbackBars", "Volume lookback", "Closed bars used for swing participation.", 20, 2, 100),
            PerplexityStrategySupport.decimalParam("minRelativeVolume", "Minimum relative volume", "Current volume divided by prior average volume.", "1.0000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("stopPct", "Stop percentage", "Protective stop distance as a decimal fraction.", "0.0250", "0.0010", "0.5000"),
            PerplexityStrategySupport.decimalParam("targetRMultiple", "Target R multiple", "Reward-to-risk target multiple.", "2.0000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("riskFraction", "Risk fraction", "Fraction of equity requested for risk sizing.", "0.0100", "0.0000", "1.0000"),
            PerplexityStrategySupport.integerParam("maxHoldingBars", "Maximum holding bars", "Lifecycle time exit in H1/D1 bars.", 20, 1, 500),
            PerplexityStrategySupport.integerParam("cooldownBars", "Cooldown bars", "Flat bars to wait after an entry or lifecycle exit before a new entry.", 0, 0, 500),
            PerplexityStrategySupport.boolParam("skipOnExpiry", "Skip on expiry", "Skip new entries on heuristic Indian derivative expiry sessions.", true),
            PerplexityStrategySupport.boolParam("allowShorts", "Allow shorts", "Allow bearish swing pullback shorts.", true)
    );
    private static final StrategyParameterSchema SCHEMA = PerplexityStrategySupport.schema(PARAMETERS);
    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, PerplexityStrategySupport.VERSION),
            PerplexityStrategySupport.PROVIDER_ID,
            "India RSI Swing Daily",
            "Low-framework-risk Indian swing baseline using EMA trend, RSI pullback zone, volume confirmation, and lifecycle exits.",
            List.of("H1", "D1"),
            PerplexityStrategySupport.INDIA_ASSET_CLASSES,
            PerplexityStrategySupport.LIFECYCLE_CAPABILITIES,
            SCHEMA,
            List.of(
                    PerplexityStrategySupport.study("ema", "Fast EMA", "trend-resumption", Map.of("period", 10), "Pullback resumption filter."),
                    PerplexityStrategySupport.study("ema", "Slow EMA", "trend", Map.of("period", 30), "Primary swing trend filter."),
                    PerplexityStrategySupport.study("rsi", "RSI", "pullback", Map.of("period", 14), "Pullback-zone oscillator."),
                    PerplexityStrategySupport.study("volume-sma", "Volume SMA", "confirmation", Map.of("period", 20), "Participation confirmation.")
            ),
            PerplexityStrategySupport.reasoningModel(STRATEGY_ID,
                    "RSI swing waits for EMA trend alignment, RSI pullback into zone, resumption candle, and relative-volume confirmation.",
                    List.of(
                            PerplexityStrategySupport.descriptor("rsi-swing.warmup", "RSI swing warmup complete", ConditionRole.ENTRY_FILTER, true, "warmup", "Seed EMA, RSI, and volume windows."),
                            PerplexityStrategySupport.descriptor("rsi-swing.trend", "EMA trend is aligned", ConditionRole.REGIME_FILTER, true, "scanning", "Avoid counter-trend swing pullbacks."),
                            PerplexityStrategySupport.descriptor("rsi-swing.pullback-zone", "RSI is in pullback zone", ConditionRole.ENTRY_FILTER, true, "scanning", "Require pullback RSI to be in the configured band."),
                            PerplexityStrategySupport.descriptor("rsi-swing.resumption", "Current bar resumes trend direction", ConditionRole.ENTRY_TRIGGER, true, "signal", "Require price to resume with the trend."),
                            PerplexityStrategySupport.descriptor("rsi-swing.volume", "Relative volume confirms participation", ConditionRole.ENTRY_FILTER, true, "signal", "Require enough participation for the swing entry."),
                            PerplexityStrategySupport.descriptor("rsi-swing.lifecycle", "Lifecycle exit", ConditionRole.EXIT_TRIGGER, false, "lifecycle", "Explain time exits while a position is open."),
                            PerplexityStrategySupport.descriptor("rsi-swing.cooldown", "Cooldown is clear", ConditionRole.POSITION_CONTEXT, true, "cooldown", "Avoid immediate re-entry after lifecycle actions.")
                    ))
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        return PerplexityStrategySupport.validate(parameters, PARAMETERS);
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyParameters effective = validate(parameters).effectiveParameters();
        return new IndiaRsiSwingDailyStrategy(IndiaRsiSwingDailyParameters.from(effective));
    }
}

record IndiaRsiSwingDailyParameters(
        int fastEmaPeriod,
        int slowEmaPeriod,
        int rsiPeriod,
        int pullbackRsiMin,
        int pullbackRsiMax,
        int volumeLookbackBars,
        double minRelativeVolume,
        double stopPct,
        double targetRMultiple,
        BigDecimal riskFraction,
        int maxHoldingBars,
        int cooldownBars,
        boolean skipOnExpiry,
        boolean allowShorts
) {
    static IndiaRsiSwingDailyParameters from(StrategyParameters parameters) {
        return new IndiaRsiSwingDailyParameters(
                parameters.integer("fastEmaPeriod", 10),
                parameters.integer("slowEmaPeriod", 30),
                parameters.integer("rsiPeriod", 14),
                parameters.integer("pullbackRsiMin", 40),
                parameters.integer("pullbackRsiMax", 60),
                parameters.integer("volumeLookbackBars", 20),
                parameters.decimal("minRelativeVolume", new BigDecimal("1.0000")).doubleValue(),
                parameters.decimal("stopPct", new BigDecimal("0.0250")).doubleValue(),
                parameters.decimal("targetRMultiple", new BigDecimal("2.0000")).doubleValue(),
                parameters.decimal("riskFraction", new BigDecimal("0.0100")),
                parameters.integer("maxHoldingBars", 20),
                parameters.integer("cooldownBars", 0),
                parameters.bool("skipOnExpiry", true),
                parameters.bool("allowShorts", true)
        );
    }
}

final class IndiaRsiSwingDailyStrategy implements TradeIntentStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private final IndiaRsiSwingDailyParameters params;
    private final RollingIndicators.Ema fastEmaIndicator;
    private final RollingIndicators.Ema slowEmaIndicator;
    private final RollingIndicators.SimpleRsi rsiIndicator;
    private int processedBars;
    private int cooldownRemaining;
    private String lastProcessedEventId = "";
    private double currentFastEma = Double.NaN;
    private double currentSlowEma = Double.NaN;
    private double currentRsi = Double.NaN;

    IndiaRsiSwingDailyStrategy(IndiaRsiSwingDailyParameters params) {
        this.params = params;
        this.fastEmaIndicator = new RollingIndicators.Ema(params.fastEmaPeriod());
        this.slowEmaIndicator = new RollingIndicators.Ema(params.slowEmaPeriod());
        this.rsiIndicator = new RollingIndicators.SimpleRsi(params.rsiPeriod());
    }

    @Override
    public String strategyId() {
        return IndiaRsiSwingDailyStrategyProvider.STRATEGY_ID;
    }

    @Override
    public String stateSchemaVersion() {
        return "india-rsi-swing-daily-v1-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "processedBars", processedBars,
                "cooldownRemaining", cooldownRemaining,
                "lastProcessedEventId", lastProcessedEventId,
                "currentFastEma", currentFastEma,
                "currentSlowEma", currentSlowEma,
                "currentRsi", currentRsi,
                "fastEma", fastEmaIndicator.snapshotState(),
                "slowEma", slowEmaIndicator.snapshotState(),
                "rsi", rsiIndicator.snapshotState()
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        processedBars = asInt(state == null ? null : state.get("processedBars"), 0);
        cooldownRemaining = asInt(state == null ? null : state.get("cooldownRemaining"), 0);
        lastProcessedEventId = asString(state == null ? null : state.get("lastProcessedEventId"));
        currentFastEma = asDouble(state == null ? null : state.get("currentFastEma"), Double.NaN);
        currentSlowEma = asDouble(state == null ? null : state.get("currentSlowEma"), Double.NaN);
        currentRsi = asDouble(state == null ? null : state.get("currentRsi"), Double.NaN);
        if (state != null && state.get("fastEma") != null) {
            fastEmaIndicator.restoreState(ResumableStrategy.STATE_MAPPER.convertValue(state.get("fastEma"), RollingIndicators.EmaState.class));
            slowEmaIndicator.restoreState(ResumableStrategy.STATE_MAPPER.convertValue(state.get("slowEma"), RollingIndicators.EmaState.class));
            rsiIndicator.restoreState(ResumableStrategy.STATE_MAPPER.convertValue(state.get("rsi"), RollingIndicators.SimpleRsiState.class));
        }
    }

    @Override
    public void restoreStateForReWarm(Map<String, Object> checkpointState) {
        processedBars = 0;
        lastProcessedEventId = "";
        currentFastEma = Double.NaN;
        currentSlowEma = Double.NaN;
        currentRsi = Double.NaN;
        fastEmaIndicator.restoreState(new RollingIndicators.Ema(params.fastEmaPeriod()).snapshotState());
        slowEmaIndicator.restoreState(new RollingIndicators.Ema(params.slowEmaPeriod()).snapshotState());
        rsiIndicator.restoreState(new RollingIndicators.SimpleRsi(params.rsiPeriod()).snapshotState());
        cooldownRemaining = asInt(checkpointState == null ? null : checkpointState.get("cooldownRemaining"), 0);
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        BarEvent current = context.currentBar();
        if (context.instrumentPosition().hasPosition()) {
            if (context.instrumentPosition().barsHeld() >= params.maxHoldingBars()) {
                armCooldown();
                return PerplexityStrategySupport.exitResult(strategyId(), current, context.instrumentPosition().side(), SetupType.PULLBACK, 0.70d,
                        List.of("strategy_family=india_rsi_swing_daily", "exit=time"),
                        PerplexityStrategySupport.reason("RSI swing lifecycle time exit", List.of("barsHeld=" + context.instrumentPosition().barsHeld()), List.of("rsi-swing", "exit"), List.of()));
            }
            return StrategyIntentResult.empty();
        }
        if (consumeCooldown()) {
            return StrategyIntentResult.empty();
        }
        if (params.skipOnExpiry() && PerplexityStrategySupport.isIndianExpirySession(current)) {
            return StrategyIntentResult.empty();
        }

        List<BarEvent> bars = context.instrumentHistory();
        int readiness = Math.max(Math.max(params.slowEmaPeriod(), params.rsiPeriod() + 1), params.volumeLookbackBars() + 1);
        if (bars.size() < readiness || !advanceIndicators(bars)) {
            return StrategyIntentResult.empty();
        }

        double fastEma = currentFastEma;
        double slowEma = currentSlowEma;
        double close = BarMath.close(current);
        double previousClose = BarMath.close(bars.get(bars.size() - 2));
        double rsi = currentRsi;
        double averageVolume = BarMath.averageVolumeBeforeCurrent(bars, params.volumeLookbackBars());
        double relativeVolume = BarMath.volume(current) / averageVolume;
        boolean volumeReady = Double.isFinite(relativeVolume) && relativeVolume >= params.minRelativeVolume();
        boolean emaBullishTrend = close > slowEma && fastEma > slowEma;
        boolean emaBearishTrend = close < slowEma && fastEma < slowEma;
        Optional<TrendDirection> marketTrend = marketTrendDirection(context);
        boolean bullishTrend = marketTrend.map(direction -> direction == TrendDirection.UP).orElse(emaBullishTrend);
        boolean bearishTrend = marketTrend.map(direction -> direction == TrendDirection.DOWN).orElse(emaBearishTrend);
        boolean rsiPullback = rsi >= params.pullbackRsiMin() && rsi <= params.pullbackRsiMax();
        boolean bullishResumption = close > previousClose && close >= BarMath.open(current) && close >= fastEma;
        boolean bearishResumption = close < previousClose && close <= BarMath.open(current) && close <= fastEma;
        Direction direction = bullishTrend && rsiPullback && bullishResumption
                ? Direction.LONG
                : params.allowShorts() && bearishTrend && rsiPullback && bearishResumption ? Direction.SHORT : null;
        if (direction == null || !volumeReady || !Double.isFinite(rsi)) {
            return StrategyIntentResult.empty();
        }

        double stop = direction == Direction.LONG ? close * (1.0d - params.stopPct()) : close * (1.0d + params.stopPct());
        double risk = Math.max(Math.abs(close - stop), close * 0.0025d);
        double target = direction == Direction.LONG
                ? close + (risk * params.targetRMultiple())
                : close - (risk * params.targetRMultiple());
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                PerplexityStrategySupport.condition("rsi-swing.trend", "EMA trend is aligned", "fastMinusSlow", fastEma - slowEma, direction == Direction.LONG ? ">" : "<", "zero", 0.0d, direction == Direction.LONG ? bullishTrend : bearishTrend),
                PerplexityStrategySupport.condition("rsi-swing.pullback-zone", "RSI is in pullback zone", "rsi", rsi, "between", "zoneMid", (params.pullbackRsiMin() + params.pullbackRsiMax()) / 2.0d, rsiPullback),
                PerplexityStrategySupport.condition("rsi-swing.resumption", "Current bar resumes trend direction", "close", close, direction == Direction.LONG ? ">" : "<", "previousClose", previousClose, direction == Direction.LONG ? bullishResumption : bearishResumption),
                PerplexityStrategySupport.condition("rsi-swing.volume", "Relative volume confirms participation", "relativeVolume", relativeVolume, ">=", "minimum", params.minRelativeVolume(), volumeReady)
        );
        StrategyTradeIntentReason reason = PerplexityStrategySupport.reason(
                "EMA trend pullback resumes while RSI is in the swing pullback zone",
                List.of(
                        "fastEma=" + PerplexityStrategySupport.price(fastEma),
                        "slowEma=" + PerplexityStrategySupport.price(slowEma),
                        "rsi=" + PerplexityStrategySupport.price(rsi),
                        "relativeVolume=" + PerplexityStrategySupport.price(relativeVolume),
                        "trendSource=" + (marketTrend.isPresent() ? "market-context" : "ema"),
                        "marketTrendDirection=" + marketTrend.map(Enum::name).orElse("UNKNOWN")
                ),
                List.of("india", "rsi", "ema", "swing", "lifecycle"),
                conditions
        );
        double confidence = swingConfidence(fastEma, slowEma, close, relativeVolume);
        StrategyIntentResult result = PerplexityStrategySupport.entryResult(
                strategyId(),
                direction,
                SetupType.PULLBACK,
                current,
                close,
                stop,
                target,
                confidence,
                params.maxHoldingBars(),
                IntendedHorizonLabel.SWING,
                params.riskFraction(),
                List.of("strategy_family=india_rsi_swing_daily", "setup=rsi_swing_pullback", "market=india", "formula_version=india-rsi-swing-daily-v1"),
                reason
        );
        armCooldown();
        return result;
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        if (context.instrumentPosition().hasPosition()) {
            return List.of(PerplexityStrategySupport.evidence("rsi-swing.lifecycle", "Lifecycle exit", ConditionRole.EXIT_TRIGGER,
                    context.instrumentPosition().barsHeld() >= params.maxHoldingBars(), "Time exit is ready", "Holding period has not reached the time exit"));
        }
        if (cooldownRemaining > 0) {
            return List.of(PerplexityStrategySupport.evidence("rsi-swing.cooldown", "Cooldown is clear", ConditionRole.POSITION_CONTEXT,
                    false, "Cooldown is clear", "Cooldown is still active"));
        }
        List<BarEvent> bars = context.instrumentHistory();
        int readiness = Math.max(Math.max(params.slowEmaPeriod(), params.rsiPeriod() + 1), params.volumeLookbackBars() + 1);
        if (bars.size() < readiness || !evaluateIndicatorsWithoutAdvancing(bars)) {
            return List.of(PerplexityStrategySupport.evidence("rsi-swing.warmup", "RSI swing warmup complete", ConditionRole.ENTRY_FILTER,
                    false, "Indicators are ready", "Waiting for EMA, RSI, and volume windows"));
        }
        BarEvent current = context.currentBar();
        double close = BarMath.close(current);
        double previousClose = BarMath.close(bars.get(bars.size() - 2));
        double averageVolume = BarMath.averageVolumeBeforeCurrent(bars, params.volumeLookbackBars());
        double relativeVolume = BarMath.volume(current) / averageVolume;
        Optional<TrendDirection> marketTrend = marketTrendDirection(context);
        boolean emaBullishTrend = close > currentSlowEma && currentFastEma > currentSlowEma;
        boolean emaBearishTrend = close < currentSlowEma && currentFastEma < currentSlowEma;
        boolean bullishTrend = marketTrend.map(direction -> direction == TrendDirection.UP).orElse(emaBullishTrend);
        boolean bearishTrend = marketTrend.map(direction -> direction == TrendDirection.DOWN).orElse(emaBearishTrend);
        boolean rsiPullback = currentRsi >= params.pullbackRsiMin() && currentRsi <= params.pullbackRsiMax();
        boolean bullishResumption = close > previousClose && close >= BarMath.open(current) && close >= currentFastEma;
        boolean bearishResumption = close < previousClose && close <= BarMath.open(current) && close <= currentFastEma;
        boolean volumeReady = Double.isFinite(relativeVolume) && relativeVolume >= params.minRelativeVolume();
        return List.of(
                PerplexityStrategySupport.evidence("rsi-swing.trend", "EMA trend is aligned", ConditionRole.REGIME_FILTER,
                        bullishTrend || bearishTrend, "Trend is aligned", "Trend is not aligned"),
                PerplexityStrategySupport.evidence("rsi-swing.pullback-zone", "RSI is in pullback zone", ConditionRole.ENTRY_FILTER,
                        rsiPullback, "RSI is inside the pullback zone", "RSI is outside the pullback zone"),
                PerplexityStrategySupport.evidence("rsi-swing.resumption", "Current bar resumes trend direction", ConditionRole.ENTRY_TRIGGER,
                        bullishResumption || params.allowShorts() && bearishResumption, "Current bar resumes trend direction", "Current bar has not resumed"),
                PerplexityStrategySupport.evidence("rsi-swing.volume", "Relative volume confirms participation", ConditionRole.ENTRY_FILTER,
                        volumeReady, "Relative volume confirms participation", "Relative volume is below the threshold")
        );
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        if (context.instrumentPosition().hasPosition()) {
            return "lifecycle";
        }
        if (cooldownRemaining > 0) {
            return "cooldown";
        }
        List<ThoughtConditionEvidence> evidence = evaluateReasoning(context);
        if (evidence.stream().anyMatch(item -> item.conditionId().endsWith("warmup"))) {
            return "warmup";
        }
        return evidence.stream().filter(item -> !item.conditionId().endsWith("volume")).allMatch(ThoughtConditionEvidence::passed)
                ? "signal"
                : "scanning";
    }

    private boolean advanceIndicators(List<BarEvent> bars) {
        if (bars.isEmpty()) {
            return Double.isFinite(currentFastEma) && Double.isFinite(currentSlowEma) && Double.isFinite(currentRsi);
        }
        int startIndex = nextUnprocessedIndex(bars);
        if (startIndex >= bars.size()) {
            return Double.isFinite(currentFastEma) && Double.isFinite(currentSlowEma) && Double.isFinite(currentRsi);
        }
        for (int index = startIndex; index < bars.size(); index++) {
            BarEvent bar = bars.get(index);
            double close = BarMath.close(bar);
            OptionalDouble fast = fastEmaIndicator.update(close);
            OptionalDouble slow = slowEmaIndicator.update(close);
            OptionalDouble rsi = rsiIndicator.update(close);
            fast.ifPresent(value -> currentFastEma = value);
            slow.ifPresent(value -> currentSlowEma = value);
            rsi.ifPresent(value -> currentRsi = value);
            lastProcessedEventId = bar.eventId().value();
        }
        processedBars += bars.size() - startIndex;
        return Double.isFinite(currentFastEma) && Double.isFinite(currentSlowEma) && Double.isFinite(currentRsi);
    }

    private int nextUnprocessedIndex(List<BarEvent> bars) {
        if (!lastProcessedEventId.isBlank()) {
            for (int index = bars.size() - 1; index >= 0; index--) {
                if (lastProcessedEventId.equals(bars.get(index).eventId().value())) {
                    return index + 1;
                }
            }
            if (processedBars > bars.size()) {
                return bars.size() - 1;
            }
        }
        if (processedBars <= 0) {
            return 0;
        }
        return processedBars <= bars.size() ? processedBars : bars.size() - 1;
    }

    private boolean evaluateIndicatorsWithoutAdvancing(List<BarEvent> bars) {
        Map<String, Object> checkpoint = snapshotState();
        boolean ready = advanceIndicators(bars);
        restoreState(checkpoint);
        return ready;
    }

    private Optional<TrendDirection> marketTrendDirection(StrategyExecutionContext context) {
        Optional<MarketContextFrameSnapshot> frame = context.marketContext(context.currentBar().timeframe());
        return frame.map(MarketContextFrameSnapshot::trendDirection)
                .filter(direction -> direction != TrendDirection.UNKNOWN);
    }

    private boolean consumeCooldown() {
        if (cooldownRemaining <= 0) {
            return false;
        }
        cooldownRemaining--;
        return true;
    }

    private void armCooldown() {
        cooldownRemaining = Math.max(cooldownRemaining, params.cooldownBars());
    }

    private double swingConfidence(double fastEma, double slowEma, double close, double relativeVolume) {
        // Blend EMA separation with participation above the configured baseline.
        return Math.min(0.90d,
                0.62d
                        + Math.min(0.14d, Math.abs(fastEma - slowEma) / Math.max(close, 0.0001d))
                        + Math.min(0.12d, (relativeVolume - params.minRelativeVolume()) * 0.04d));
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : "";
    }
}
