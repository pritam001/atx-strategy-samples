package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.algotradex.strategy.samples.perplexity.PerplexityStrategySupport.BarMath;

public final class IndiaOrbBreakoutStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "india-orb-breakout-v1";
    static final int DEFAULT_OPENING_RANGE_BARS = 6;
    static final int M15_DEFAULT_OPENING_RANGE_BARS = 3;
    private static final List<StrategyParameterDefinition> PARAMETERS = List.of(
            PerplexityStrategySupport.integerParam("openingRangeBars", "Opening range bars", "Closed bars used to define the opening range. Runtime default is M5=6 and M15=3.", DEFAULT_OPENING_RANGE_BARS, 2, 24),
            PerplexityStrategySupport.integerParam("atrPeriod", "ATR period", "Closed-bar ATR period used for stop distance.", 14, 2, 100),
            PerplexityStrategySupport.integerParam("volumeLookbackBars", "Volume lookback", "Closed bars used for relative-volume confirmation.", 20, 2, 100),
            PerplexityStrategySupport.decimalParam("breakoutBufferPct", "Breakout buffer", "Minimum close beyond range boundary as a decimal percentage.", "0.0010", "0.0000", "0.0500"),
            PerplexityStrategySupport.decimalParam("minRelativeVolume", "Minimum relative volume", "Current volume divided by prior average volume.", "1.5000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("stopAtrMultiple", "Stop ATR multiple", "ATR multiple used for protective stop distance.", "1.0000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("targetRMultiple", "Target R multiple", "Reward-to-risk target multiple.", "2.0000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("riskFraction", "Risk fraction", "Fraction of equity requested for risk sizing.", "0.0100", "0.0000", "1.0000"),
            PerplexityStrategySupport.integerParam("maxHoldingBars", "Maximum holding bars", "Lifecycle time exit in execution bars.", 36, 1, 500),
            PerplexityStrategySupport.integerParam("cooldownBars", "Cooldown bars", "Flat bars to wait after an entry or lifecycle exit before a new entry.", 0, 0, 500),
            PerplexityStrategySupport.boolParam("skipOnExpiry", "Skip on expiry", "Skip new entries on heuristic Indian derivative expiry sessions.", true),
            PerplexityStrategySupport.boolParam("enforceSessionGate", "Enforce session gate", "Only allow new entries during the fixed India ORB window.", true),
            PerplexityStrategySupport.boolParam("allowShorts", "Allow shorts", "Allow downside opening-range breakdowns.", true)
    );
    private static final StrategyParameterSchema SCHEMA = PerplexityStrategySupport.schema(PARAMETERS);
    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, PerplexityStrategySupport.VERSION),
            PerplexityStrategySupport.PROVIDER_ID,
            "India ORB Breakout",
            "Indian-market opening range breakout using closed bars, relative volume, ATR stop distance, and lifecycle exits.",
            List.of("M5", "M15"),
            PerplexityStrategySupport.INDIA_ASSET_CLASSES,
            PerplexityStrategySupport.LIFECYCLE_CAPABILITIES,
            SCHEMA,
            List.of(
                    PerplexityStrategySupport.study("opening-range", "Opening Range", "range", Map.of("bars", DEFAULT_OPENING_RANGE_BARS), "Manual Indian cash/F&O session range; M15 runtime default uses 3 bars."),
                    PerplexityStrategySupport.study("atr", "ATR", "risk", Map.of("period", 14), "Closed-bar ATR stop distance."),
                    PerplexityStrategySupport.study("volume-sma", "Volume SMA", "confirmation", Map.of("period", 20), "Relative-volume breakout confirmation.")
            ),
            PerplexityStrategySupport.reasoningModel(STRATEGY_ID,
                    "ORB breakout waits for the opening range, a close beyond the range, relative-volume confirmation, and ATR risk readiness.",
                    List.of(
                            PerplexityStrategySupport.descriptor("orb.range-ready", "Opening range is ready", ConditionRole.ENTRY_FILTER, true, "warmup", "Do not trade until the opening range is complete."),
                            PerplexityStrategySupport.descriptor("orb.breakout", "Close broke opening range", ConditionRole.ENTRY_TRIGGER, true, "signal", "Require a close beyond the range boundary."),
                            PerplexityStrategySupport.descriptor("orb.volume", "Relative volume confirms breakout", ConditionRole.ENTRY_FILTER, true, "signal", "Require breakout participation."),
                            PerplexityStrategySupport.descriptor("orb.atr-ready", "ATR is available", ConditionRole.RISK_GUARD, true, "signal", "Require ATR for stop sizing."),
                            PerplexityStrategySupport.descriptor("orb.lifecycle", "Lifecycle exit", ConditionRole.EXIT_TRIGGER, false, "lifecycle", "Explain invalidation and time exits."),
                            PerplexityStrategySupport.descriptor("orb.cooldown", "Cooldown is clear", ConditionRole.POSITION_CONTEXT, true, "cooldown", "Avoid immediate re-entry after lifecycle actions.")
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
        return new IndiaOrbBreakoutStrategy(IndiaOrbBreakoutParameters.from(effective));
    }
}

record IndiaOrbBreakoutParameters(
        int openingRangeBars,
        int atrPeriod,
        int volumeLookbackBars,
        double breakoutBufferPct,
        double minRelativeVolume,
        double stopAtrMultiple,
        double targetRMultiple,
        BigDecimal riskFraction,
        int maxHoldingBars,
        int cooldownBars,
        boolean skipOnExpiry,
        boolean enforceSessionGate,
        boolean allowShorts
) {
    static IndiaOrbBreakoutParameters from(StrategyParameters parameters) {
        return new IndiaOrbBreakoutParameters(
                parameters.integer("openingRangeBars", IndiaOrbBreakoutStrategyProvider.DEFAULT_OPENING_RANGE_BARS),
                parameters.integer("atrPeriod", 14),
                parameters.integer("volumeLookbackBars", 20),
                parameters.decimal("breakoutBufferPct", new BigDecimal("0.0010")).doubleValue(),
                parameters.decimal("minRelativeVolume", new BigDecimal("1.5000")).doubleValue(),
                parameters.decimal("stopAtrMultiple", new BigDecimal("1.0000")).doubleValue(),
                parameters.decimal("targetRMultiple", new BigDecimal("2.0000")).doubleValue(),
                parameters.decimal("riskFraction", new BigDecimal("0.0100")),
                parameters.integer("maxHoldingBars", 36),
                parameters.integer("cooldownBars", 0),
                parameters.bool("skipOnExpiry", true),
                parameters.bool("enforceSessionGate", true),
                parameters.bool("allowShorts", true)
        );
    }
}

final class IndiaOrbBreakoutStrategy implements TradeIntentStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private static final int INVALIDATION_WINDOW_BARS = 2;

    private final IndiaOrbBreakoutParameters params;
    private int cooldownRemaining;
    private Direction activeDirection;
    private double activeRangeHigh = Double.NaN;
    private double activeRangeLow = Double.NaN;

    IndiaOrbBreakoutStrategy(IndiaOrbBreakoutParameters params) {
        this.params = params;
    }

    @Override
    public String strategyId() {
        return IndiaOrbBreakoutStrategyProvider.STRATEGY_ID;
    }

    @Override
    public String stateSchemaVersion() {
        return "india-orb-breakout-v1-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "cooldownRemaining", cooldownRemaining,
                "activeDirection", activeDirection == null ? "" : activeDirection.name(),
                "activeRangeHigh", activeRangeHigh,
                "activeRangeLow", activeRangeLow
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        cooldownRemaining = asInt(state == null ? null : state.get("cooldownRemaining"), 0);
        String direction = String.valueOf(state == null ? "" : state.getOrDefault("activeDirection", ""));
        activeDirection = direction.isBlank() ? null : Direction.valueOf(direction);
        activeRangeHigh = asDouble(state == null ? null : state.get("activeRangeHigh"), Double.NaN);
        activeRangeLow = asDouble(state == null ? null : state.get("activeRangeLow"), Double.NaN);
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        BarEvent current = context.currentBar();
        if (context.instrumentPosition().hasPosition()) {
            StrategyIntentResult invalidation = invalidationExit(context);
            if (!invalidation.tradeIntents().isEmpty()) {
                return invalidation;
            }
            if (context.instrumentPosition().barsHeld() >= params.maxHoldingBars()) {
                clearActiveEntry();
                armCooldown();
                return PerplexityStrategySupport.exitResult(strategyId(), current, context.instrumentPosition().side(), SetupType.BREAKOUT, 0.70d,
                        List.of("strategy_family=india_orb_breakout", "exit=time"),
                        PerplexityStrategySupport.reason("ORB lifecycle time exit", List.of("barsHeld=" + context.instrumentPosition().barsHeld()), List.of("orb", "exit"), List.of()));
            }
            return StrategyIntentResult.empty();
        }
        if (consumeCooldown()) {
            return StrategyIntentResult.empty();
        }
        if (params.skipOnExpiry() && PerplexityStrategySupport.isIndianExpirySession(current)) {
            return StrategyIntentResult.empty();
        }
        if (params.enforceSessionGate()
                && !PerplexityStrategySupport.isWithinIndiaWindow(current, PerplexityStrategySupport.ORB_WINDOW_START, PerplexityStrategySupport.ORB_WINDOW_END)) {
            return StrategyIntentResult.empty();
        }

        List<BarEvent> bars = context.instrumentHistory();
        List<BarEvent> sessionBars = BarMath.sameIndiaSession(bars, current);
        int openingRangeBars = openingRangeBarsFor(current);
        if (sessionBars.size() <= openingRangeBars) {
            return StrategyIntentResult.empty();
        }

        double rangeHigh = BarMath.highestHigh(sessionBars, 0, openingRangeBars);
        double rangeLow = BarMath.lowestLow(sessionBars, 0, openingRangeBars);
        double atr = BarMath.atr(bars, params.atrPeriod());
        double averageVolume = BarMath.averageVolumeBeforeCurrent(bars, params.volumeLookbackBars());
        double relativeVolume = BarMath.volume(current) / averageVolume;
        double close = BarMath.close(current);
        double buffer = params.breakoutBufferPct();
        boolean rangeReady = Double.isFinite(rangeHigh) && Double.isFinite(rangeLow) && rangeHigh > rangeLow;
        boolean atrReady = Double.isFinite(atr) && atr > 0.0d;
        boolean volumeReady = Double.isFinite(relativeVolume) && relativeVolume >= params.minRelativeVolume();
        boolean longBreakout = rangeReady && close > rangeHigh * (1.0d + buffer);
        boolean shortBreakout = params.allowShorts() && rangeReady && close < rangeLow * (1.0d - buffer);
        Direction direction = longBreakout ? Direction.LONG : shortBreakout ? Direction.SHORT : null;
        if (direction == null || !atrReady || !volumeReady) {
            return StrategyIntentResult.empty();
        }

        double stopDistance = Math.max(atr * params.stopAtrMultiple(), close * 0.0025d);
        double stop = direction == Direction.LONG ? close - stopDistance : close + stopDistance;
        double target = direction == Direction.LONG
                ? close + (stopDistance * params.targetRMultiple())
                : close - (stopDistance * params.targetRMultiple());
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                PerplexityStrategySupport.condition("orb.range-ready", "Opening range is ready", "rangeWidth", rangeHigh - rangeLow, ">", "zero", 0.0d, rangeReady),
                PerplexityStrategySupport.condition("orb.breakout", "Close broke opening range", "close", close, direction == Direction.LONG ? ">" : "<", "rangeBoundary", direction == Direction.LONG ? rangeHigh : rangeLow, direction == Direction.LONG ? longBreakout : shortBreakout),
                PerplexityStrategySupport.condition("orb.volume", "Relative volume confirms breakout", "relativeVolume", relativeVolume, ">=", "minimum", params.minRelativeVolume(), volumeReady),
                PerplexityStrategySupport.condition("orb.atr-ready", "ATR is available", "atr", atr, ">", "zero", 0.0d, atrReady)
        );
        StrategyTradeIntentReason reason = PerplexityStrategySupport.reason(
                "Opening range breakout with relative-volume and ATR confirmation",
                List.of(
                        "rangeHigh=" + PerplexityStrategySupport.price(rangeHigh),
                        "rangeLow=" + PerplexityStrategySupport.price(rangeLow),
                        "relativeVolume=" + PerplexityStrategySupport.price(relativeVolume),
                        "atr=" + PerplexityStrategySupport.price(atr)
                ),
                List.of("india", "orb", "breakout", "closed-bars", "lifecycle"),
                conditions
        );
        double confidence = breakoutConfidence(relativeVolume, close, direction == Direction.LONG ? rangeHigh : rangeLow);
        StrategyIntentResult result = PerplexityStrategySupport.entryResult(
                strategyId(),
                direction,
                SetupType.BREAKOUT,
                current,
                close,
                stop,
                target,
                confidence,
                params.maxHoldingBars(),
                IntendedHorizonLabel.INTRADAY,
                params.riskFraction(),
                List.of("strategy_family=india_orb_breakout", "setup=opening_range_breakout", "market=india", "formula_version=india-orb-breakout-v1"),
                reason
        );
        trackActiveEntry(direction, rangeHigh, rangeLow);
        armCooldown();
        return result;
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        if (context.instrumentPosition().hasPosition()) {
            return List.of(PerplexityStrategySupport.evidence("orb.lifecycle", "Lifecycle exit", ConditionRole.EXIT_TRIGGER,
                    activeDirection != null || context.instrumentPosition().barsHeld() >= params.maxHoldingBars(), "Lifecycle exit is being evaluated", "No lifecycle exit yet"));
        }
        if (cooldownRemaining > 0) {
            return List.of(PerplexityStrategySupport.evidence("orb.cooldown", "Cooldown is clear", ConditionRole.POSITION_CONTEXT,
                    false, "Cooldown is clear", "Cooldown is still active"));
        }
        BarEvent current = context.currentBar();
        List<BarEvent> bars = context.instrumentHistory();
        List<BarEvent> sessionBars = BarMath.sameIndiaSession(bars, current);
        int openingRangeBars = openingRangeBarsFor(current);
        if (sessionBars.size() <= openingRangeBars) {
            return List.of(PerplexityStrategySupport.evidence("orb.range-ready", "Opening range is ready", ConditionRole.ENTRY_FILTER,
                    false, "Opening range is ready", "Opening range is still forming"));
        }
        double rangeHigh = BarMath.highestHigh(sessionBars, 0, openingRangeBars);
        double rangeLow = BarMath.lowestLow(sessionBars, 0, openingRangeBars);
        double atr = BarMath.atr(bars, params.atrPeriod());
        double averageVolume = BarMath.averageVolumeBeforeCurrent(bars, params.volumeLookbackBars());
        double relativeVolume = BarMath.volume(current) / averageVolume;
        double close = BarMath.close(current);
        boolean rangeReady = Double.isFinite(rangeHigh) && Double.isFinite(rangeLow) && rangeHigh > rangeLow;
        boolean breakout = rangeReady && (close > rangeHigh * (1.0d + params.breakoutBufferPct())
                || params.allowShorts() && close < rangeLow * (1.0d - params.breakoutBufferPct()));
        boolean atrReady = Double.isFinite(atr) && atr > 0.0d;
        boolean volumeReady = Double.isFinite(relativeVolume) && relativeVolume >= params.minRelativeVolume();
        return List.of(
                PerplexityStrategySupport.evidence("orb.range-ready", "Opening range is ready", ConditionRole.ENTRY_FILTER, rangeReady, "Opening range is ready", "Opening range is invalid"),
                PerplexityStrategySupport.evidence("orb.breakout", "Close broke opening range", ConditionRole.ENTRY_TRIGGER, breakout, "Close broke the opening range", "Close is still inside the opening range"),
                PerplexityStrategySupport.evidence("orb.volume", "Relative volume confirms breakout", ConditionRole.ENTRY_FILTER, volumeReady, "Relative volume confirms breakout", "Relative volume is below threshold"),
                PerplexityStrategySupport.evidence("orb.atr-ready", "ATR is available", ConditionRole.RISK_GUARD, atrReady, "ATR is available for stop sizing", "ATR is not ready")
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
        if (evidence.stream().anyMatch(item -> item.conditionId().equals("orb.range-ready") && !item.passed())) {
            return "warmup";
        }
        return evidence.stream().anyMatch(item -> item.conditionId().equals("orb.breakout") && item.passed()) ? "signal" : "scanning";
    }

    private StrategyIntentResult invalidationExit(StrategyExecutionContext context) {
        if (activeDirection == null || context.instrumentPosition().barsHeld() > INVALIDATION_WINDOW_BARS) {
            return StrategyIntentResult.empty();
        }
        double close = BarMath.close(context.currentBar());
        boolean invalidated = switch (activeDirection) {
            case LONG -> close <= activeRangeHigh;
            case SHORT -> close >= activeRangeLow;
            default -> false;
        };
        if (!invalidated) {
            return StrategyIntentResult.empty();
        }
        double rangeHigh = activeRangeHigh;
        double rangeLow = activeRangeLow;
        clearActiveEntry();
        armCooldown();
        return PerplexityStrategySupport.exitResult(strategyId(), context.currentBar(), context.instrumentPosition().side(), SetupType.BREAKOUT, 0.76d,
                List.of("strategy_family=india_orb_breakout", "exit=invalidation"),
                PerplexityStrategySupport.reason(
                        "ORB breakout invalidated by close back inside opening range",
                        List.of(
                                "barsHeld=" + context.instrumentPosition().barsHeld(),
                                "close=" + PerplexityStrategySupport.price(close),
                                "rangeHigh=" + PerplexityStrategySupport.price(rangeHigh),
                                "rangeLow=" + PerplexityStrategySupport.price(rangeLow)
                        ),
                        List.of("orb", "exit", "exit=invalidation"),
                        List.of()
                ));
    }

    private void trackActiveEntry(Direction direction, double rangeHigh, double rangeLow) {
        this.activeDirection = direction;
        this.activeRangeHigh = rangeHigh;
        this.activeRangeLow = rangeLow;
    }

    private void clearActiveEntry() {
        this.activeDirection = null;
        this.activeRangeHigh = Double.NaN;
        this.activeRangeLow = Double.NaN;
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

    private int openingRangeBarsFor(BarEvent current) {
        if ("M15".equalsIgnoreCase(current.timeframe())
                && params.openingRangeBars() == IndiaOrbBreakoutStrategyProvider.DEFAULT_OPENING_RANGE_BARS) {
            return IndiaOrbBreakoutStrategyProvider.M15_DEFAULT_OPENING_RANGE_BARS;
        }
        return params.openingRangeBars();
    }

    private double breakoutConfidence(double relativeVolume, double close, double rangeBoundary) {
        // Blend participation excess with distance beyond the range boundary.
        return Math.min(0.90d,
                0.62d
                        + Math.min(0.18d, (relativeVolume - params.minRelativeVolume()) * 0.05d)
                        + Math.min(0.10d, Math.abs(close - rangeBoundary) / close));
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
