package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.intelligence.SetupType;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.algotradex.strategy.samples.perplexity.PerplexityStrategySupport.BarMath;

public final class IndiaVwapReversionStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "india-vwap-reversion-v1";
    private static final List<StrategyParameterDefinition> PARAMETERS = List.of(
            PerplexityStrategySupport.decimalParam("vwapBandPct", "VWAP band", "Distance from session VWAP used as the reversion band.", "0.0060", "0.0001", "0.1000"),
            PerplexityStrategySupport.integerParam("atrPeriod", "ATR period", "Closed-bar ATR period used for stop distance.", 14, 2, 100),
            PerplexityStrategySupport.integerParam("volumeLookbackBars", "Volume lookback", "Closed bars used for participation confirmation.", 20, 2, 100),
            PerplexityStrategySupport.decimalParam("minRelativeVolume", "Minimum relative volume", "Current volume divided by prior average volume.", "1.0000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("stopAtrMultiple", "Stop ATR multiple", "ATR multiple used for protective stop distance.", "0.8000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("targetRMultiple", "Target R multiple", "Reward-to-risk target multiple.", "1.5000", "0.1000", "10.0000"),
            PerplexityStrategySupport.decimalParam("riskFraction", "Risk fraction", "Fraction of equity requested for risk sizing.", "0.0100", "0.0000", "1.0000"),
            PerplexityStrategySupport.integerParam("maxHoldingBars", "Maximum holding bars", "Lifecycle time exit in execution bars.", 24, 1, 500),
            PerplexityStrategySupport.integerParam("cooldownBars", "Cooldown bars", "Flat bars to wait after an entry or lifecycle exit before a new entry.", 0, 0, 500),
            PerplexityStrategySupport.boolParam("skipOnExpiry", "Skip on expiry", "Skip new entries on heuristic Indian derivative expiry sessions.", true),
            PerplexityStrategySupport.boolParam("enforceSessionGate", "Enforce session gate", "Only allow new entries during the fixed India VWAP reversion window.", true),
            PerplexityStrategySupport.boolParam("allowShorts", "Allow shorts", "Allow upper-band VWAP fade shorts.", true)
    );
    private static final StrategyParameterSchema SCHEMA = PerplexityStrategySupport.schema(PARAMETERS);
    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, PerplexityStrategySupport.VERSION),
            PerplexityStrategySupport.PROVIDER_ID,
            "India VWAP Reversion",
            "Indian intraday session VWAP reversion using closed M5/M15 bars, participation, ATR stops, and lifecycle exits.",
            List.of("M5", "M15"),
            PerplexityStrategySupport.INDIA_ASSET_CLASSES,
            PerplexityStrategySupport.LIFECYCLE_CAPABILITIES,
            SCHEMA,
            List.of(
                    PerplexityStrategySupport.study("session-vwap", "Session VWAP", "mean-reversion-anchor", Map.of("session", "Asia/Kolkata"), "Internal closed-bar session VWAP."),
                    PerplexityStrategySupport.study("vwap-band", "VWAP Band", "entry-zone", Map.of("bandPct", "0.006"), "Percentage envelope around VWAP."),
                    PerplexityStrategySupport.study("atr", "ATR", "risk", Map.of("period", 14), "Closed-bar ATR stop distance.")
            )
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
        return new IndiaVwapReversionStrategy(IndiaVwapReversionParameters.from(effective));
    }
}

record IndiaVwapReversionParameters(
        double vwapBandPct,
        int atrPeriod,
        int volumeLookbackBars,
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
    static IndiaVwapReversionParameters from(StrategyParameters parameters) {
        return new IndiaVwapReversionParameters(
                parameters.decimal("vwapBandPct", new BigDecimal("0.0060")).doubleValue(),
                parameters.integer("atrPeriod", 14),
                parameters.integer("volumeLookbackBars", 20),
                parameters.decimal("minRelativeVolume", new BigDecimal("1.0000")).doubleValue(),
                parameters.decimal("stopAtrMultiple", new BigDecimal("0.8000")).doubleValue(),
                parameters.decimal("targetRMultiple", new BigDecimal("1.5000")).doubleValue(),
                parameters.decimal("riskFraction", new BigDecimal("0.0100")),
                parameters.integer("maxHoldingBars", 24),
                parameters.integer("cooldownBars", 0),
                parameters.bool("skipOnExpiry", true),
                parameters.bool("enforceSessionGate", true),
                parameters.bool("allowShorts", true)
        );
    }
}

final class IndiaVwapReversionStrategy implements TradeIntentStrategy {
    private static final int FOLLOW_THROUGH_WINDOW_BARS = 3;

    private final IndiaVwapReversionParameters params;
    private int cooldownRemaining;
    private Direction activeDirection;
    private double activeVwap = Double.NaN;

    IndiaVwapReversionStrategy(IndiaVwapReversionParameters params) {
        this.params = params;
    }

    @Override
    public String strategyId() {
        return IndiaVwapReversionStrategyProvider.STRATEGY_ID;
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
                return PerplexityStrategySupport.exitResult(strategyId(), current, context.instrumentPosition().side(), SetupType.MEAN_REVERSION, 0.70d,
                        List.of("strategy_family=india_vwap_reversion", "exit=time"),
                        PerplexityStrategySupport.reason("VWAP reversion lifecycle time exit", List.of("barsHeld=" + context.instrumentPosition().barsHeld()), List.of("vwap", "exit"), List.of()));
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
                && !PerplexityStrategySupport.isWithinIndiaWindow(current, PerplexityStrategySupport.VWAP_WINDOW_START, PerplexityStrategySupport.VWAP_WINDOW_END)) {
            return StrategyIntentResult.empty();
        }

        List<BarEvent> bars = context.instrumentHistory();
        List<BarEvent> sessionBars = BarMath.sameIndiaSession(bars, current);
        if (sessionBars.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        double vwap = BarMath.sessionVwap(sessionBars);
        double lowerBand = vwap * (1.0d - params.vwapBandPct());
        double upperBand = vwap * (1.0d + params.vwapBandPct());
        double atr = BarMath.atr(bars, params.atrPeriod());
        double averageVolume = BarMath.averageVolumeBeforeCurrent(bars, params.volumeLookbackBars());
        double relativeVolume = BarMath.volume(current) / averageVolume;
        double close = BarMath.close(current);
        boolean sessionReady = Double.isFinite(vwap) && Double.isFinite(atr) && atr > 0.0d;
        boolean volumeReady = Double.isFinite(relativeVolume) && relativeVolume >= params.minRelativeVolume();
        boolean longReclaim = BarMath.low(current) <= lowerBand && close > BarMath.open(current);
        boolean shortReclaim = params.allowShorts() && BarMath.high(current) >= upperBand && close < BarMath.open(current);
        Direction direction = longReclaim ? Direction.LONG : shortReclaim ? Direction.SHORT : null;
        if (direction == null || !sessionReady || !volumeReady) {
            return StrategyIntentResult.empty();
        }

        double stopDistance = Math.max(atr * params.stopAtrMultiple(), close * 0.0025d);
        double stop = direction == Direction.LONG ? close - stopDistance : close + stopDistance;
        double target = direction == Direction.LONG
                ? close + (stopDistance * params.targetRMultiple())
                : close - (stopDistance * params.targetRMultiple());
        List<StrategyTradeIntentConditionEvidence> conditions = List.of(
                PerplexityStrategySupport.condition("vwap.session-ready", "Session VWAP and ATR are ready", "vwap", vwap, ">", "zero", 0.0d, sessionReady),
                PerplexityStrategySupport.condition("vwap.lower-band-reclaim", "Price rejected outer VWAP band", direction == Direction.LONG ? "low" : "high", direction == Direction.LONG ? BarMath.low(current) : BarMath.high(current), direction == Direction.LONG ? "<=" : ">=", "band", direction == Direction.LONG ? lowerBand : upperBand, direction == Direction.LONG ? longReclaim : shortReclaim),
                PerplexityStrategySupport.condition("vwap.volume", "Relative volume shows participation", "relativeVolume", relativeVolume, ">=", "minimum", params.minRelativeVolume(), volumeReady)
        );
        StrategyTradeIntentReason reason = PerplexityStrategySupport.reason(
                "Price rejected the session VWAP outer band with participation",
                List.of(
                        "vwap=" + PerplexityStrategySupport.price(vwap),
                        "lowerBand=" + PerplexityStrategySupport.price(lowerBand),
                        "upperBand=" + PerplexityStrategySupport.price(upperBand),
                        "relativeVolume=" + PerplexityStrategySupport.price(relativeVolume),
                        "atr=" + PerplexityStrategySupport.price(atr)
                ),
                List.of("india", "vwap", "mean-reversion", "closed-bars", "lifecycle"),
                conditions
        );
        double confidence = reversionConfidence(close, vwap, relativeVolume);
        StrategyIntentResult result = PerplexityStrategySupport.entryResult(
                strategyId(),
                direction,
                SetupType.MEAN_REVERSION,
                current,
                close,
                stop,
                target,
                confidence,
                params.maxHoldingBars(),
                IntendedHorizonLabel.INTRADAY,
                params.riskFraction(),
                List.of("strategy_family=india_vwap_reversion", "setup=session_vwap_reversion", "market=india", "formula_version=india-vwap-reversion-v1"),
                reason
        );
        trackActiveEntry(direction, vwap);
        armCooldown();
        return result;
    }

    private StrategyIntentResult invalidationExit(StrategyExecutionContext context) {
        if (activeDirection == null) {
            return StrategyIntentResult.empty();
        }
        double close = BarMath.close(context.currentBar());
        boolean followedThrough = switch (activeDirection) {
            case LONG -> close >= activeVwap;
            case SHORT -> close <= activeVwap;
            default -> true;
        };
        if (followedThrough) {
            clearActiveEntry();
            return StrategyIntentResult.empty();
        }
        if (context.instrumentPosition().barsHeld() < FOLLOW_THROUGH_WINDOW_BARS) {
            return StrategyIntentResult.empty();
        }
        double vwap = activeVwap;
        clearActiveEntry();
        armCooldown();
        return PerplexityStrategySupport.exitResult(strategyId(), context.currentBar(), context.instrumentPosition().side(), SetupType.MEAN_REVERSION, 0.74d,
                List.of("strategy_family=india_vwap_reversion", "exit=invalidation"),
                PerplexityStrategySupport.reason(
                        "VWAP reversion invalidated by missing VWAP follow-through",
                        List.of(
                                "barsHeld=" + context.instrumentPosition().barsHeld(),
                                "close=" + PerplexityStrategySupport.price(close),
                                "entryVwap=" + PerplexityStrategySupport.price(vwap)
                        ),
                        List.of("vwap", "exit", "exit=invalidation"),
                        List.of()
                ));
    }

    private void trackActiveEntry(Direction direction, double vwap) {
        this.activeDirection = direction;
        this.activeVwap = vwap;
    }

    private void clearActiveEntry() {
        this.activeDirection = null;
        this.activeVwap = Double.NaN;
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

    private double reversionConfidence(double close, double vwap, double relativeVolume) {
        // Blend distance from VWAP with participation above the configured baseline.
        return Math.min(0.88d,
                0.60d
                        + Math.min(0.18d, Math.abs(close - vwap) / Math.max(vwap, 0.0001d))
                        + Math.min(0.10d, (relativeVolume - params.minRelativeVolume()) * 0.04d));
    }
}
