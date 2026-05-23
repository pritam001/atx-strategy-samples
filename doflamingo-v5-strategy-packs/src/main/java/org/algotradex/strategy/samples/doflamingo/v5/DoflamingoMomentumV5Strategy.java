package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntent;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentReason;
import org.algotradex.platform.contracts.intelligence.TradeIntentExitPolicy;
import org.algotradex.platform.contracts.intelligence.TradeSignal;
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstrumentPosition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.service.strategy.ResumableStrategy;
import org.algotradex.platform.core.api.service.strategy.StrategyReasoningEvaluator;
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

abstract class DoflamingoMomentumV5Strategy implements TradeIntentStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DoflamingoMomentumV5Parameters params;
    private int cooldownRemaining;

    DoflamingoMomentumV5Strategy(DoflamingoMomentumV5Parameters params) {
        this.params = requireNonNull(params, "params");
    }

    @Override
    public String strategyId() {
        return params.strategyId();
    }

    @Override
    public String stateSchemaVersion() {
        return params.strategyId() + "-state-v1";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of("cooldownRemaining", cooldownRemaining);
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        Object value = state == null ? null : state.get("cooldownRemaining");
        cooldownRemaining = value instanceof Number number ? number.intValue() : value == null ? 0 : Integer.parseInt(value.toString());
    }

    @Override
    public List<StrategyCapability> lifecycleCapabilities() {
        return DoflamingoMomentumV5ProviderSupport.capabilities();
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            return lifecycleResult(context, position);
        }
        FlatEvaluation evaluation = evaluateFlat(context, context.collectReasoningEvidence());
        if (evaluation.intent().isPresent()) {
            cooldownRemaining = params.cooldownBars();
            StrategyTradeIntent intent = evaluation.intent().get();
            TradeSignal signal = evaluation.signal().orElseThrow();
            return result(context, List.of(signal), List.of(intent), "accept:" + intent.action().name(), evaluation.reasoning(), "risk");
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
        }
        return result(context, List.of(), List.of(), "reject:" + evaluation.rejectionCode() + " " + evaluation.rejectionDetail(), evaluation.reasoning(), evaluation.phase());
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        if (context.instrumentPosition().hasPosition()) {
            return lifecycleReasoning(context, context.instrumentPosition(), Optional.empty());
        }
        return evaluateFlat(context, true).reasoning();
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        if (context.instrumentPosition().hasPosition()) {
            return "lifecycle";
        }
        return evaluateFlat(context, false).phase();
    }

    private FlatEvaluation evaluateFlat(StrategyExecutionContext context, boolean includeReasoning) {
        BarEvent current = context.currentBar();
        String primaryTimeframe = timeframe(current);
        List<BarEvent> primary = context.history(primaryTimeframe);
        if (primary.size() < params.primaryWarmupBars()) {
            return rejected("warmupMissingBars", "primaryBars=" + primary.size(), "structure", includeReasoning ? List.of(
                    numericEvidence("cloud-structure", "Cloud structure", ConditionRole.REGIME_FILTER, false,
                            "primaryBars", decimal(primary.size()), ">=", "requiredBars", decimal(params.primaryWarmupBars()),
                            "Primary history is still warming.")
            ) : List.of());
        }

        Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> maybePrimary =
                DoflamingoMomentumV5IndicatorMath.snapshot(primary, params);
        if (maybePrimary.isEmpty()) {
            return rejected("warmupMissingBars", "indicator inputs unavailable", "structure", includeReasoning ? List.of(
                    new ThoughtConditionEvidence("cloud-structure", "Cloud structure", ConditionRole.REGIME_FILTER, false,
                            "Primary indicators are not ready on the closed bar.")
            ) : List.of());
        }
        DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot = maybePrimary.get();
        String contextTimeframe = params.contextTimeframeFor(primaryTimeframe);
        Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot = contextSnapshot(context, contextTimeframe);
        if (params.contextBiasMode() != DoflamingoMomentumV5Parameters.BiasMode.OFF && contextSnapshot.isEmpty()) {
            return rejected("contextBiasFailed", contextTimeframe + " context missing or warming", "context",
                    includeReasoning ? baseReasoning(context, snapshot, Optional.empty(), null, null, false, "Missing required context.") : List.of());
        }

        if (params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY && !freshEntryTimeAllowed(current)) {
            return rejected("sessionGateBlocked", "fresh entries blocked at " + istTime(current), "risk",
                    includeReasoning ? baseReasoning(context, snapshot, contextSnapshot, null, null, false, "Fresh entry session gate blocked.") : List.of());
        }

        if (cooldownRemaining > 0) {
            return rejected("cooldownActive", "cooldownRemaining=" + cooldownRemaining, "risk",
                    includeReasoning ? baseReasoning(context, snapshot, contextSnapshot, null, null, false, "Cooldown is active.") : List.of());
        }

        EntryCandidate longCandidate = evaluateSide(context, primary, snapshot, contextSnapshot, PositionSide.LONG);
        EntryCandidate shortCandidate = evaluateShortCandidate(context, primary, snapshot, contextSnapshot);
        Optional<EntryCandidate> selected = selectCandidate(longCandidate, shortCandidate);
        if (selected.isEmpty()) {
            EntryCandidate best = longCandidate.confidence().compareTo(shortCandidate.confidence()) >= 0 ? longCandidate : shortCandidate;
            return rejected(best.rejectionCode(), best.rejectionDetail(), phaseForRejection(best.rejectionCode()),
                    includeReasoning ? baseReasoning(context, snapshot, contextSnapshot, longCandidate, shortCandidate, false, best.rejectionDetail()) : List.of());
        }

        EntryCandidate candidate = selected.get();
        TradeSignal signal = DoflamingoMomentumV5IntentSupport.signal(params, context, candidate.side(), candidate.confidence(), candidate.setupType());
        StrategyTradeIntent intent = DoflamingoMomentumV5IntentSupport.entryIntent(
                params,
                context,
                candidate.side(),
                candidate.confidence(),
                DoflamingoMomentumV5IntentSupport.percentStopWithRrTarget(candidate.stop().stopPct(), params.initialTargetR(),
                        "Initial V5 " + params.horizonMode().toLowerCase(Locale.ROOT) + " stop from cloud/ATR/swing candidates"),
                entryReason(context, snapshot, contextSnapshot, candidate)
        );
        return new FlatEvaluation(Optional.of(signal), Optional.of(intent),
                includeReasoning ? baseReasoning(context, snapshot, contextSnapshot, longCandidate, shortCandidate, true, "Entry accepted.") : List.of(),
                "risk", "accepted", "");
    }

    private EntryCandidate evaluateSide(
            StrategyExecutionContext context,
            List<BarEvent> primary,
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot,
            PositionSide side
    ) {
        boolean bullish = side == PositionSide.LONG;
        boolean contextOk = contextBiasOk(contextSnapshot, bullish);
        boolean contextAligned = contextSnapshot.map(value -> bullish ? "BULLISH".equals(value.cloudBias()) : "BEARISH".equals(value.cloudBias())).orElse(false);
        boolean contextCompatible = contextSnapshot.map(value -> bullish ? !"BEARISH".equals(value.cloudBias()) : !"BULLISH".equals(value.cloudBias())).orElse(false);
        boolean cloudStructure = bullish
                ? snapshot.close() > snapshot.ichimoku().cloudTop()
                && "BULLISH".equals(snapshot.ichimoku().futureBias())
                && snapshot.ichimoku().conversionLine() > snapshot.ichimoku().baseLine()
                : snapshot.close() < snapshot.ichimoku().cloudFloor()
                && "BEARISH".equals(snapshot.ichimoku().futureBias())
                && snapshot.ichimoku().conversionLine() < snapshot.ichimoku().baseLine();
        boolean cloudQuality = snapshot.cloudThicknessAtr() >= params.minCloudThicknessAtr().doubleValue()
                && snapshot.futureSpreadAtr() >= params.minFutureSpreadAtr().doubleValue();
        boolean strictEma = bullish
                ? snapshot.emaFast() > snapshot.emaMid() && snapshot.emaMid() > snapshot.emaAnchor() && snapshot.close() > snapshot.emaMid()
                : snapshot.emaFast() < snapshot.emaMid() && snapshot.emaMid() < snapshot.emaAnchor() && snapshot.close() < snapshot.emaMid();
        boolean cloudMomentum = bullish
                ? snapshot.emaFast() > snapshot.ichimoku().cloudTop() && snapshot.emaMid() >= snapshot.emaMidPrevious()
                : snapshot.emaFast() < snapshot.ichimoku().cloudFloor() && snapshot.emaMid() <= snapshot.emaMidPrevious();
        boolean pullbackResume = pullbackResume(primary, snapshot, bullish);
        boolean breakout = bullish
                ? snapshot.close() > DoflamingoMomentumV5IndicatorMath.previousHigh(primary, snapshot.index(), params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 10 : 3)
                : snapshot.close() < DoflamingoMomentumV5IndicatorMath.previousLow(primary, snapshot.index(), params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 10 : 3);
        boolean futureFlip = futureCloudFlip(primary, bullish);
        boolean earlyTransition = futureFlip
                && (bullish ? snapshot.close() > snapshot.ichimoku().baseLine() && snapshot.ichimoku().conversionLine() > snapshot.ichimoku().baseLine()
                : snapshot.close() < snapshot.ichimoku().baseLine() && snapshot.ichimoku().conversionLine() < snapshot.ichimoku().baseLine())
                && snapshot.volumePulse() >= params.strongVolumePulseMultiple().doubleValue()
                && (bullish == snapshot.psarBullish());
        boolean emaStructure = switch (params.entryMode()) {
            case STRICT_CLOUD -> strictEma || cloudMomentum;
            case BREAKOUT -> breakout && (strictEma || cloudMomentum);
            case PULLBACK_RESUME -> pullbackResume;
            case EARLY_TRANSITION -> earlyTransition;
            case HYBRID -> strictEma || cloudMomentum || pullbackResume || breakout || earlyTransition;
        };
        boolean macd = bullish ? snapshot.macd().bullish() : snapshot.macd().bearish();
        boolean stoch = bullish ? snapshot.stochRsi().bullish() : snapshot.stochRsi().bearish();
        boolean psar = bullish ? snapshot.psarBullish() : !snapshot.psarBullish();
        int confirmations = (macd ? 1 : 0) + (stoch ? 1 : 0) + (psar ? 1 : 0);
        boolean momentum = confirmations >= 2;
        boolean participation = participationOk(context.currentBar(), snapshot);
        double distanceAtr = priceDistanceFromCloudAtr(snapshot, bullish);
        boolean antiChaseDisabled = params.maxEntryAtrFromCloudTop().signum() <= 0;
        boolean overextensionNormal = !antiChaseDisabled && distanceAtr <= params.maxEntryAtrFromCloudTop().doubleValue();
        boolean overextensionStrong = params.maxEntryAtrFromCloudTopStrongVolume().signum() > 0
                && distanceAtr <= params.maxEntryAtrFromCloudTopStrongVolume().doubleValue()
                && snapshot.volumePulse() >= params.strongVolumePulseMultiple().doubleValue()
                && (bullish ? snapshot.macd().histogram() > snapshot.macd().previousHistogram()
                : snapshot.macd().histogram() < snapshot.macd().previousHistogram());
        boolean antiChase = antiChaseDisabled || overextensionNormal || overextensionStrong;
        StopComputation stop = stop(primary, snapshot, side);
        boolean stopQuality = stop.valid();
        BigDecimal confidence = confidence(snapshot, contextAligned, contextCompatible, cloudStructure, cloudQuality,
                emaStructure, macd, stoch, psar, participation, antiChase, stopQuality);
        boolean confidenceOk = confidence.compareTo(params.minConfidence()) >= 0;
        boolean accepted = contextOk && cloudStructure && cloudQuality && emaStructure && momentum
                && participation && antiChase && stopQuality && confidenceOk;
        String rejectionCode = firstFailed(
                contextOk, "contextBiasFailed",
                cloudStructure && cloudQuality, "cloudBiasFailed",
                emaStructure, "structureFailed",
                momentum, "momentumConfirmationFailed",
                participation, "participationFailed",
                antiChase && stopQuality, "riskDistanceRejected",
                confidenceOk, "confidenceRejected"
        );
        return new EntryCandidate(side, accepted, confidence, stop, confirmations, distanceAtr, contextOk,
                cloudStructure, cloudQuality, emaStructure, macd, stoch, psar, participation, antiChase,
                confidenceOk, rejectionCode, detail(rejectionCode, side), setupType(pullbackResume, breakout, earlyTransition));
    }

    private EntryCandidate evaluateShortCandidate(
            StrategyExecutionContext context,
            List<BarEvent> primary,
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot
    ) {
        EntryCandidate candidate = evaluateSide(context, primary, snapshot, contextSnapshot, PositionSide.SHORT);
        return params.allowShorts() || !candidate.accepted() ? candidate : candidate.withPolicyRejection(
                "shortsDisabled",
                "Swing short entries are disabled by default for this strategy profile."
        );
    }

    private StrategyIntentResult lifecycleResult(StrategyExecutionContext context, StrategyInstrumentPosition position) {
        List<BarEvent> primary = context.history(context.currentBar().timeframe());
        Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> maybeSnapshot =
                DoflamingoMomentumV5IndicatorMath.snapshot(primary, params);
        PositionSide side = position.side();
        boolean bullish = side == PositionSide.LONG;
        if (params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY && eodFlattenDue(context.currentBar())) {
            return lifecycleIntent(context, position, maybeSnapshot, bullish ? "EXIT_LONG_EOD" : "EXIT_SHORT_EOD", "Intraday force-exit time reached.");
        }
        if (params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY
                && position.currentRMultiple() != null
                && position.currentRMultiple().compareTo(BigDecimal.valueOf(-1.0)) <= 0) {
            return lifecycleIntent(context, position, maybeSnapshot, bullish ? "EXIT_LONG_STOP" : "EXIT_SHORT_STOP", "Position reached the stop-risk boundary.");
        }
        if (maybeSnapshot.isPresent()) {
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot = maybeSnapshot.get();
            boolean structureBreak = confirmedCloudBreak(primary, bullish)
                    || (params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY && confirmedBaselineBreak(primary, bullish));
            if (structureBreak) {
                String summary = params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY
                        ? "Confirmed cloud or baseline structure broke against the position."
                        : "Confirmed cloud structure broke against the position.";
                return lifecycleIntent(context, position, maybeSnapshot,
                        bullish ? "EXIT_LONG_STRUCTURE_BREAK" : "EXIT_SHORT_STRUCTURE_BREAK", summary);
            }
            boolean momentumDecay = momentumDecay(snapshot, bullish);
            if (momentumDecay) {
                return lifecycleIntent(context, position, maybeSnapshot,
                        bullish ? "EXIT_LONG_MOMENTUM_DECAY" : "EXIT_SHORT_MOMENTUM_DECAY", "EMA and three-bar MACD histogram decay invalidated the runner.");
            }
            boolean chandelierBreak = chandelierBreak(primary, snapshot, bullish);
            if (chandelierBreak && rMultiple(position).compareTo(BigDecimal.ONE) >= 0) {
                return lifecycleIntent(context, position, maybeSnapshot,
                        bullish ? "EXIT_LONG_STRUCTURE_BREAK" : "EXIT_SHORT_STRUCTURE_BREAK", "Chandelier runner invalidation fired.");
            }
        }
        if (position.barsHeld() >= params.maxHoldingBars()) {
            return lifecycleIntent(context, position, maybeSnapshot, bullish ? "EXIT_LONG_TIME_STOP" : "EXIT_SHORT_TIME_STOP", "Maximum holding bars reached.");
        }
        if (position.barsHeld() >= params.staleBars() && rMultiple(position).compareTo(params.staleMinR()) <= 0) {
            return lifecycleIntent(context, position, maybeSnapshot, bullish ? "EXIT_LONG_TIME_STOP" : "EXIT_SHORT_TIME_STOP", "Stale position has not progressed enough.");
        }
        if (params.enableScaleOut() && position.scaleOutCount() == 0
                && rMultiple(position).compareTo(params.scaleOutAtR()) >= 0
                && position.maxFavorablePct().signum() > 0
                && maybeSnapshot.map(snapshot -> alignedWithCloud(snapshot, bullish)).orElse(true)) {
            StrategyTradeIntent intent = DoflamingoMomentumV5IntentSupport.scaleOutIntent(
                    params,
                    context,
                    side,
                    BigDecimal.valueOf(0.82),
                    lifecycleReason("SCALE_OUT_" + side.name(), "Winner reached the configured R multiple; scale out and keep the runner.", position, maybeSnapshot)
            );
            return result(context, List.of(), List.of(intent), "accept:" + intent.action().name(), lifecycleReasoningIfRequested(context, position, maybeSnapshot), "lifecycle");
        }
        if (params.enableScaleIn() && position.scaleInCount() < params.maxScaleIns()
                && rMultiple(position).compareTo(params.scaleInAtR()) >= 0
                && maybeSnapshot.map(snapshot -> alignedWithCloud(snapshot, bullish) && renewedExtreme(primary, snapshot, bullish)).orElse(false)) {
            StrategyTradeIntent intent = DoflamingoMomentumV5IntentSupport.scaleInIntent(
                    params,
                    context,
                    side,
                    BigDecimal.valueOf(0.84),
                    lifecycleReason("SCALE_IN_" + side.name(), "Winner renewed momentum and optional scale-in is enabled.", position, maybeSnapshot)
            );
            return result(context, List.of(), List.of(intent), "accept:" + intent.action().name(), lifecycleReasoningIfRequested(context, position, maybeSnapshot), "lifecycle");
        }
        return result(context, List.of(), List.of(), "hold:position-managed", lifecycleReasoningIfRequested(context, position, maybeSnapshot), "lifecycle");
    }

    private StrategyIntentResult lifecycleIntent(
            StrategyExecutionContext context,
            StrategyInstrumentPosition position,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> snapshot,
            String eventType,
            String summary
    ) {
        StrategyTradeIntent intent = DoflamingoMomentumV5IntentSupport.exitIntent(
                params,
                context,
                position.side(),
                BigDecimal.valueOf(0.88),
                lifecycleReason(eventType, summary, position, snapshot)
        );
        cooldownRemaining = params.cooldownBars();
        return result(context, List.of(), List.of(intent), "accept:" + intent.action().name(), lifecycleReasoningIfRequested(context, position, snapshot), "lifecycle");
    }

    private StrategyTradeIntentReason entryReason(
            StrategyExecutionContext context,
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot,
            EntryCandidate candidate
    ) {
        boolean bullish = candidate.side() == PositionSide.LONG;
        List<String> evidence = new ArrayList<>();
        evidence.add("eventType=ENTER_" + candidate.side().name());
        evidence.add("strategyId=" + params.strategyId());
        evidence.add("strategyVersion=" + params.strategyVersion());
        evidence.add("horizonMode=" + params.horizonMode());
        evidence.add("primaryTimeframe=" + timeframe(context.currentBar()));
        evidence.add("contextTimeframe=" + params.contextTimeframeFor(timeframe(context.currentBar())));
        evidence.add(params.contextEvidenceKey() + "=" + contextSnapshot.map(DoflamingoMomentumV5IndicatorMath.MarketSnapshot::cloudBias).orElse("MISSING"));
        evidence.add("side=" + candidate.side().name());
        evidence.add("entryMode=" + params.entryMode());
        evidence.add("cloudTop=" + decimal(snapshot.ichimoku().cloudTop()));
        evidence.add("cloudFloor=" + decimal(snapshot.ichimoku().cloudFloor()));
        evidence.add("futureCloudBias=" + snapshot.ichimoku().futureBias());
        evidence.add("conversionBaseState=" + snapshot.ichimoku().conversionBaseState());
        evidence.add("emaStackState=" + snapshot.emaStackState());
        evidence.add("macdLine=" + decimal(snapshot.macd().line()));
        evidence.add("macdSignal=" + decimal(snapshot.macd().signal()));
        evidence.add("macdHistogram=" + decimal(snapshot.macd().histogram()));
        evidence.add("macdHistogramSlope=" + snapshot.macd().slopeLabel());
        evidence.add("stochRsiK=" + evidenceValue(snapshot.stochRsi().k()));
        evidence.add("stochRsiD=" + evidenceValue(snapshot.stochRsi().d()));
        evidence.add("psarDirection=" + (snapshot.psarBullish() ? "BULLISH" : "BEARISH"));
        evidence.add("volumePulse=" + decimal(snapshot.volumePulse()));
        evidence.add("atr=" + decimal(snapshot.atr()));
        evidence.add("atrStopMultiple=" + candidate.stop().atrStopMultiple());
        evidence.add("priceDistanceFromCloudAtr=" + decimal(candidate.priceDistanceFromCloudAtr()));
        evidence.add("initialStop=" + candidate.stop().stopPrice());
        evidence.add("stopPct=" + candidate.stop().stopPct());
        evidence.add("targetR=" + params.initialTargetR().setScale(4, RoundingMode.HALF_UP));
        evidence.add("confidence=" + candidate.confidence());
        evidence.add("overnightRiskAccepted=" + params.allowOvernight());
        return DoflamingoMomentumV5IntentSupport.reason(
                "Doflamingo Momentum V5 " + params.horizonMode().toLowerCase(Locale.ROOT) + " " + (bullish ? "long" : "short")
                        + " entry: cloud, momentum, participation, and risk aligned.",
                evidence,
                List.of("doflamingo", "v5-beta", params.horizonMode().toLowerCase(Locale.ROOT), "momentum", "entry", "lifecycle"),
                entryConditions(snapshot, contextSnapshot, candidate)
        );
    }

    private StrategyTradeIntentReason lifecycleReason(
            String eventType,
            String summary,
            StrategyInstrumentPosition position,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> snapshot
    ) {
        List<String> evidence = new ArrayList<>();
        evidence.add("eventType=" + eventType);
        evidence.add("horizonMode=" + params.horizonMode());
        evidence.add("positionSide=" + position.side());
        evidence.add("positionBarsHeld=" + position.barsHeld());
        evidence.add("currentR=" + rMultiple(position));
        evidence.add("mfeR=" + position.maxFavorablePct());
        evidence.add("maeR=" + position.maxAdversePct());
        evidence.add("scaleOutCount=" + position.scaleOutCount());
        evidence.add("scaleInCount=" + position.scaleInCount());
        snapshot.ifPresent(value -> {
            evidence.add("cloudBias=" + value.cloudBias());
            evidence.add("emaStackState=" + value.emaStackState());
            evidence.add("macdHistogramSlope=" + value.macd().slopeLabel());
        });
        return DoflamingoMomentumV5IntentSupport.reason(
                "Doflamingo Momentum V5 lifecycle: " + summary,
                evidence,
                List.of("doflamingo", "v5-beta", params.horizonMode().toLowerCase(Locale.ROOT), "momentum", "lifecycle"),
                List.of(
                        DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.lifecycle-current-r", "Lifecycle current R", "Current R", rMultiple(position), ">=", "Reference", BigDecimal.ZERO, true),
                        DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.lifecycle-bars-held", "Lifecycle bars held", "Bars held", BigDecimal.valueOf(position.barsHeld()), ">=", "Zero", BigDecimal.ZERO, true)
                )
        );
    }

    private List<StrategyTradeIntentConditionEvidence> entryConditions(
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot,
            EntryCandidate candidate
    ) {
        return List.of(
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.context-bias", "Context cloud bias", "Context aligned", candidate.contextOk() ? 1.0d : 0.0d, "=", "Required", 1.0d, candidate.contextOk()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.cloud-structure", "Primary cloud structure", "Cloud structure", candidate.cloudStructure() ? 1.0d : 0.0d, "=", "Required", 1.0d, candidate.cloudStructure()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.cloud-quality", "Cloud quality", "Cloud thickness ATR", snapshot.cloudThicknessAtr(), ">=", "Minimum", params.minCloudThicknessAtr().doubleValue(), candidate.cloudQuality()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.ema-structure", "EMA structure", "EMA structure", candidate.emaStructure() ? 1.0d : 0.0d, "=", "Required", 1.0d, candidate.emaStructure()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.momentum-confirmation", "Momentum confirmation", "Confirmations", BigDecimal.valueOf(candidate.momentumConfirmations()), ">=", "Required", BigDecimal.valueOf(2), candidate.momentumConfirmations() >= 2),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.volume-pulse", "Volume pulse", "Volume pulse", snapshot.volumePulse(), ">=", "Minimum", params.volumePulseMultiple().doubleValue(), candidate.participation()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.anti-chase", "Anti-chase distance", "Distance ATR", candidate.priceDistanceFromCloudAtr(), "<=", "Maximum", params.maxEntryAtrFromCloudTopStrongVolume().doubleValue(), candidate.antiChase()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.stop-quality", "Stop quality", "Stop %", candidate.stop().stopPct(), ">=", "Minimum", params.minStopPct(), candidate.stop().valid()),
                DoflamingoMomentumV5IntentSupport.condition("doflamingo-v5.confidence-threshold", "Confidence threshold", "Confidence", candidate.confidence(), ">=", "Minimum", params.minConfidence(), candidate.confidenceOk())
        );
    }

    private List<ThoughtConditionEvidence> baseReasoning(
            StrategyExecutionContext context,
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot,
            EntryCandidate longCandidate,
            EntryCandidate shortCandidate,
            boolean accepted,
            String detail
    ) {
        EntryCandidate best = bestCandidate(longCandidate, shortCandidate);
        boolean contextOk = best == null ? contextSnapshot.isPresent() || params.contextBiasMode() == DoflamingoMomentumV5Parameters.BiasMode.OFF : best.contextOk();
        boolean structure = best != null && best.cloudStructure() && best.cloudQuality() && best.emaStructure();
        boolean momentum = best != null && best.momentumConfirmations() >= 2;
        boolean participation = best != null && best.participation();
        boolean risk = best != null && best.antiChase() && best.stop().valid() && best.confidenceOk();
        return List.of(
                new ThoughtConditionEvidence("context-bias", "Context cloud bias", ConditionRole.ENTRY_FILTER, contextOk,
                        contextOk ? "Context bias permits this side." : detail),
                new ThoughtConditionEvidence("cloud-structure", "Cloud structure", ConditionRole.REGIME_FILTER, structure,
                        structure ? "Cloud and EMA structure support continuation." : "Cloud, EMA, or entry-mode structure is incomplete."),
                new ThoughtConditionEvidence("momentum-confirmation", "Momentum confirmation", ConditionRole.ENTRY_TRIGGER, momentum,
                        momentum ? "At least two momentum confirmations agree." : "MACD, Stoch RSI, and PSAR did not reach two confirmations."),
                new ThoughtConditionEvidence("participation", "Participation", ConditionRole.ENTRY_FILTER, participation,
                        participation ? "Volume and ATR/session participation passed." : "Participation or session bucket blocked the setup."),
                numericEvidence("risk-location", "Risk location", ConditionRole.RISK_GUARD, risk,
                        "confidence", best == null ? BigDecimal.ZERO : best.confidence(), ">=", "minConfidence", params.minConfidence(),
                        risk || accepted ? "Risk location and confidence are acceptable." : "Stop quality, anti-chase distance, or confidence blocked the setup.")
        );
    }

    private List<ThoughtConditionEvidence> lifecycleReasoning(
            StrategyExecutionContext context,
            StrategyInstrumentPosition position,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> snapshot
    ) {
        boolean exitPressure = snapshot.map(value -> position.side() == PositionSide.LONG
                ? value.close() < value.emaMid() || value.close() < value.ichimoku().cloudFloor()
                : value.close() > value.emaMid() || value.close() > value.ichimoku().cloudTop()).orElse(false);
        boolean scaleOutReady = rMultiple(position).compareTo(params.scaleOutAtR()) >= 0 && position.scaleOutCount() == 0;
        boolean timeRisk = position.barsHeld() >= params.staleBars() || position.barsHeld() >= params.maxHoldingBars();
        return List.of(
                new ThoughtConditionEvidence("lifecycle-state", "Lifecycle state", ConditionRole.POSITION_CONTEXT, true,
                        "Existing " + position.side() + " position is active; lifecycle actions take priority over fresh entries."),
                new ThoughtConditionEvidence("lifecycle-exit", "Lifecycle exit guard", ConditionRole.EXIT_TRIGGER, exitPressure || timeRisk,
                        exitPressure || timeRisk ? "Exit pressure or time risk is present." : "No exit condition is dominant on this bar."),
                new ThoughtConditionEvidence("lifecycle-scale-out", "Lifecycle scale-out", ConditionRole.RISK_GUARD, scaleOutReady,
                        scaleOutReady ? "Position reached configured scale-out R." : "Scale-out threshold has not been met or already fired.")
        );
    }

    private List<ThoughtConditionEvidence> lifecycleReasoningIfRequested(
            StrategyExecutionContext context,
            StrategyInstrumentPosition position,
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> snapshot
    ) {
        return context.collectReasoningEvidence() ? lifecycleReasoning(context, position, snapshot) : List.of();
    }

    private Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot(StrategyExecutionContext context, String contextTimeframe) {
        List<BarEvent> contextBars = context.history(contextTimeframe);
        if (contextBars.size() < params.contextWarmupBars()) {
            return Optional.empty();
        }
        return DoflamingoMomentumV5IndicatorMath.snapshot(contextBars, params);
    }

    private Optional<EntryCandidate> selectCandidate(EntryCandidate longCandidate, EntryCandidate shortCandidate) {
        List<EntryCandidate> accepted = new ArrayList<>();
        if (longCandidate.accepted()) {
            accepted.add(longCandidate);
        }
        if (shortCandidate.accepted()) {
            accepted.add(shortCandidate);
        }
        if (accepted.isEmpty()) {
            return Optional.empty();
        }
        accepted.sort(Comparator.comparing(EntryCandidate::confidence).reversed());
        if (accepted.size() == 2
                && accepted.get(0).confidence().subtract(accepted.get(1).confidence()).abs().compareTo(BigDecimal.valueOf(0.05)) < 0) {
            return Optional.empty();
        }
        return Optional.of(accepted.getFirst());
    }

    private EntryCandidate bestCandidate(EntryCandidate longCandidate, EntryCandidate shortCandidate) {
        if (longCandidate == null) {
            return shortCandidate;
        }
        if (shortCandidate == null) {
            return longCandidate;
        }
        return longCandidate.confidence().compareTo(shortCandidate.confidence()) >= 0 ? longCandidate : shortCandidate;
    }

    private boolean contextBiasOk(Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> contextSnapshot, boolean bullish) {
        if (params.contextBiasMode() == DoflamingoMomentumV5Parameters.BiasMode.OFF) {
            return true;
        }
        if (contextSnapshot.isEmpty()) {
            return false;
        }
        String bias = contextSnapshot.get().cloudBias();
        if (params.contextBiasMode() == DoflamingoMomentumV5Parameters.BiasMode.REQUIRE_AGREEMENT) {
            return bullish ? "BULLISH".equals(bias) : "BEARISH".equals(bias);
        }
        return true;
    }

    private boolean participationOk(BarEvent bar, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot) {
        if (params.variant() == DoflamingoMomentumV5Parameters.Variant.INTRADAY && midday(bar)) {
            return snapshot.volumePulse() >= params.strongVolumePulseMultiple().doubleValue()
                    && snapshot.atrExpansionRatio() >= params.atrExpansionMultiple().doubleValue();
        }
        return snapshot.volumePulse() >= params.volumePulseMultiple().doubleValue();
    }

    private boolean pullbackResume(List<BarEvent> primary, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        int index = snapshot.index();
        double boundary = bullish ? Math.max(snapshot.emaMid(), snapshot.ichimoku().cloudTop()) : Math.min(snapshot.emaMid(), snapshot.ichimoku().cloudFloor());
        boolean touched = false;
        for (int candidate = Math.max(0, index - 5); candidate < index; candidate++) {
            BarEvent bar = primary.get(candidate);
            double distance = bullish
                    ? Math.abs(DoflamingoMomentumV5IndicatorMath.low(bar) - boundary)
                    : Math.abs(DoflamingoMomentumV5IndicatorMath.high(bar) - boundary);
            if (distance <= snapshot.safeAtr() * 0.35d) {
                touched = true;
                break;
            }
        }
        boolean breakout = bullish
                ? snapshot.close() > DoflamingoMomentumV5IndicatorMath.previousHigh(primary, index, 3)
                : snapshot.close() < DoflamingoMomentumV5IndicatorMath.previousLow(primary, index, 3);
        return touched && breakout;
    }

    private boolean futureCloudFlip(List<BarEvent> primary, boolean bullish) {
        int index = primary.size() - 1;
        Optional<DoflamingoMomentumV5IndicatorMath.IchimokuSnapshot> current = DoflamingoMomentumV5IndicatorMath.ichimoku(primary, params);
        if (current.isEmpty()) {
            return false;
        }
        for (int candidate = Math.max(0, index - (params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 5 : 3)); candidate < index; candidate++) {
            Optional<DoflamingoMomentumV5IndicatorMath.IchimokuSnapshot> previous =
                    DoflamingoMomentumV5IndicatorMath.ichimoku(primary.subList(0, candidate + 1), params);
            if (previous.isPresent()) {
                boolean wasOpposite = bullish ? previous.get().futureSpanA() <= previous.get().futureSpanB()
                        : previous.get().futureSpanA() >= previous.get().futureSpanB();
                boolean nowAligned = bullish ? current.get().futureSpanA() > current.get().futureSpanB()
                        : current.get().futureSpanA() < current.get().futureSpanB();
                if (wasOpposite && nowAligned) {
                    return true;
                }
            }
        }
        return false;
    }

    private StopComputation stop(List<BarEvent> primary, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, PositionSide side) {
        boolean bullish = side == PositionSide.LONG;
        double entry = snapshot.close();
        double buffer = params.cloudStopBufferAtr().doubleValue() * snapshot.safeAtr();
        double atrStopMultiple = effectiveAtrStopMultiple(primary, snapshot);
        double cloudStop = bullish
                ? Math.min(snapshot.ichimoku().cloudFloor(), snapshot.ichimoku().baseLine()) - buffer
                : Math.max(snapshot.ichimoku().cloudTop(), snapshot.ichimoku().baseLine()) + buffer;
        double atrStop = bullish
                ? entry - atrStopMultiple * snapshot.safeAtr()
                : entry + atrStopMultiple * snapshot.safeAtr();
        double swingStop = bullish
                ? DoflamingoMomentumV5IndicatorMath.lowestLow(primary, snapshot.index(), params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 20 : 10) - buffer
                : DoflamingoMomentumV5IndicatorMath.highestHigh(primary, snapshot.index(), params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 20 : 10) + buffer;
        double selected = bullish
                ? maxBelow(entry, cloudStop, atrStop, swingStop)
                : minAbove(entry, cloudStop, atrStop, swingStop);
        BigDecimal stopPct = decimal(Math.abs(entry - selected) / entry * 100.0d);
        boolean valid = Double.isFinite(selected)
                && (bullish ? selected < entry : selected > entry)
                && stopPct.compareTo(params.minStopPct()) >= 0
                && stopPct.compareTo(params.maxStopPct()) <= 0;
        return new StopComputation(decimal(selected), stopPct, valid, decimal(atrStopMultiple));
    }

    private double effectiveAtrStopMultiple(List<BarEvent> primary, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot) {
        double configured = params.initialAtrStopMultiple().doubleValue();
        if (params.variant() != DoflamingoMomentumV5Parameters.Variant.SWING || !params.atrPercentileStopScaling()) {
            return configured;
        }
        double min = params.minAtrStopMultiple().doubleValue();
        double max = params.maxAtrStopMultiple().doubleValue();
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return configured;
        }
        int lookback = Math.max(1, params.atrStopPercentileLookbackBars());
        int start = Math.max(params.atrPeriod(), snapshot.index() - lookback + 1);
        int observations = 0;
        int belowOrEqual = 0;
        for (int candidate = start; candidate <= snapshot.index(); candidate++) {
            OptionalDouble atr = DoflamingoMomentumV5IndicatorMath.atr(primary, candidate, params.atrPeriod());
            if (atr.isEmpty() || !Double.isFinite(atr.getAsDouble())) {
                continue;
            }
            observations++;
            if (atr.getAsDouble() <= snapshot.atr()) {
                belowOrEqual++;
            }
        }
        if (observations < 3) {
            return Math.max(min, Math.min(max, configured));
        }
        double percentile = (double) belowOrEqual / (double) observations;
        return min + (max - min) * percentile;
    }

    private BigDecimal confidence(
            DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot,
            boolean contextAligned,
            boolean contextCompatible,
            boolean cloudStructure,
            boolean cloudQuality,
            boolean emaStructure,
            boolean macd,
            boolean stoch,
            boolean psar,
            boolean participation,
            boolean antiChase,
            boolean stopQuality
    ) {
        double score = 0.0d;
        if (params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING) {
            score += contextAligned ? 25.0d : contextCompatible ? 12.0d : 0.0d;
            score += cloudStructure ? 19.0d : 0.0d;
            score += cloudQuality ? 6.0d : 0.0d;
        } else {
            score += cloudStructure ? 24.0d : 0.0d;
            score += cloudQuality ? 6.0d : 0.0d;
            score += contextAligned ? 15.0d : contextCompatible ? 7.0d : 0.0d;
        }
        score += emaStructure ? 8.0d : 0.0d;
        score += macd ? 13.0d : 0.0d;
        score += stoch ? 4.0d : 0.0d;
        score += psar ? 3.0d : 0.0d;
        score += participation ? 10.0d : 0.0d;
        score += stopQuality ? 8.0d : 0.0d;
        score += antiChase ? 4.0d : 0.0d;
        score += snapshot.atrExpansionRatio() >= params.atrExpansionMultiple().doubleValue() ? 3.0d : 0.0d;
        return DoflamingoMomentumV5IntentSupport.confidence(score / 100.0d);
    }

    private boolean alignedWithCloud(DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        return bullish
                ? snapshot.close() > snapshot.ichimoku().cloudTop() && "BULLISH".equals(snapshot.ichimoku().futureBias())
                : snapshot.close() < snapshot.ichimoku().cloudFloor() && "BEARISH".equals(snapshot.ichimoku().futureBias());
    }

    private boolean confirmedCloudBreak(List<BarEvent> primary, boolean bullish) {
        return confirmedStructureBreak(primary, snapshot -> bullish
                ? snapshot.close() < snapshot.ichimoku().cloudFloor()
                : snapshot.close() > snapshot.ichimoku().cloudTop());
    }

    private boolean confirmedBaselineBreak(List<BarEvent> primary, boolean bullish) {
        return confirmedStructureBreak(primary, snapshot -> bullish
                ? snapshot.close() < snapshot.ichimoku().baseLine()
                : snapshot.close() > snapshot.ichimoku().baseLine());
    }

    private boolean confirmedStructureBreak(
            List<BarEvent> primary,
            java.util.function.Predicate<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> predicate
    ) {
        int confirmBars = Math.max(1, params.structureExitConfirmBars());
        if (primary.size() < confirmBars) {
            return false;
        }
        for (int offset = confirmBars; offset > 0; offset--) {
            int endExclusive = primary.size() - offset + 1;
            Optional<DoflamingoMomentumV5IndicatorMath.MarketSnapshot> snapshot =
                    DoflamingoMomentumV5IndicatorMath.snapshot(primary.subList(0, endExclusive), params);
            if (snapshot.isEmpty() || !predicate.test(snapshot.get())) {
                return false;
            }
        }
        return true;
    }

    private boolean momentumDecay(DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        DoflamingoMomentumV5IndicatorMath.MacdSnapshot macd = snapshot.macd();
        return bullish
                ? snapshot.close() < snapshot.emaMid()
                && macd.histogram() < macd.previousHistogram()
                && macd.previousHistogram() < macd.secondPreviousHistogram()
                : snapshot.close() > snapshot.emaMid()
                && macd.histogram() > macd.previousHistogram()
                && macd.previousHistogram() > macd.secondPreviousHistogram();
    }

    private boolean renewedExtreme(List<BarEvent> primary, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        int lookback = params.variant() == DoflamingoMomentumV5Parameters.Variant.SWING ? 10 : 5;
        return bullish
                ? snapshot.close() > DoflamingoMomentumV5IndicatorMath.previousHigh(primary, snapshot.index(), lookback)
                : snapshot.close() < DoflamingoMomentumV5IndicatorMath.previousLow(primary, snapshot.index(), lookback);
    }

    private boolean chandelierBreak(List<BarEvent> primary, DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        if (primary.size() < params.chandelierLookbackBars()) {
            return false;
        }
        if (bullish) {
            double stop = DoflamingoMomentumV5IndicatorMath.highestHigh(primary, snapshot.index(), params.chandelierLookbackBars())
                    - params.chandelierAtrMultiple().doubleValue() * snapshot.safeAtr();
            return snapshot.close() < stop;
        }
        double stop = DoflamingoMomentumV5IndicatorMath.lowestLow(primary, snapshot.index(), params.chandelierLookbackBars())
                + params.chandelierAtrMultiple().doubleValue() * snapshot.safeAtr();
        return snapshot.close() > stop;
    }

    private boolean freshEntryTimeAllowed(BarEvent bar) {
        LocalTime time = istTime(bar);
        LocalTime open = LocalTime.of(9, 15).plusMinutes(params.skipOpeningMinutes());
        return !time.isBefore(open) && time.isBefore(parseTime(params.freshEntryCutoff()));
    }

    private boolean eodFlattenDue(BarEvent bar) {
        return !istTime(bar).isBefore(parseTime(params.forceExitTime()));
    }

    private boolean midday(BarEvent bar) {
        LocalTime time = istTime(bar);
        return !time.isBefore(parseTime(params.middayStart())) && time.isBefore(parseTime(params.middayEnd()));
    }

    private LocalTime istTime(BarEvent bar) {
        return bar.occurredAt().atZone(IST).toLocalTime();
    }

    private LocalTime parseTime(String value) {
        return LocalTime.parse(value);
    }

    private double priceDistanceFromCloudAtr(DoflamingoMomentumV5IndicatorMath.MarketSnapshot snapshot, boolean bullish) {
        return bullish
                ? Math.max(0.0d, snapshot.close() - snapshot.ichimoku().cloudTop()) / snapshot.safeAtr()
                : Math.max(0.0d, snapshot.ichimoku().cloudFloor() - snapshot.close()) / snapshot.safeAtr();
    }

    private BigDecimal rMultiple(StrategyInstrumentPosition position) {
        return position.currentRMultiple() == null ? BigDecimal.ZERO : position.currentRMultiple();
    }

    private StrategyIntentResult result(
            StrategyExecutionContext context,
            List<TradeSignal> signals,
            List<StrategyTradeIntent> intents,
            String diagnostic,
            List<ThoughtConditionEvidence> reasoning,
            String phase
    ) {
        List<String> diagnostics = params.emitDiagnostics() && diagnostic != null && !diagnostic.isBlank()
                ? List.of(diagnostic)
                : List.of();
        return new StrategyIntentResult(
                signals,
                intents,
                diagnostics,
                context.collectReasoningEvidence() ? reasoning : List.of(),
                context.collectReasoningEvidence() ? phase : ""
        );
    }

    private FlatEvaluation rejected(String code, String detail, String phase, List<ThoughtConditionEvidence> reasoning) {
        return new FlatEvaluation(Optional.empty(), Optional.empty(), reasoning, phase, code, detail);
    }

    private String firstFailed(Object... pairs) {
        for (int index = 0; index < pairs.length; index += 2) {
            if (!(Boolean) pairs[index]) {
                return (String) pairs[index + 1];
            }
        }
        return "none";
    }

    private String detail(String code, PositionSide side) {
        return switch (code) {
            case "contextBiasFailed" -> "Context cloud bias does not permit " + side;
            case "cloudBiasFailed" -> "Primary cloud structure is not aligned for " + side;
            case "structureFailed" -> "EMA or trigger-mode structure is not aligned for " + side;
            case "momentumConfirmationFailed" -> "Two-of-three momentum confirmation did not pass for " + side;
            case "participationFailed" -> "Volume or ATR participation filter blocked " + side;
            case "riskDistanceRejected" -> "Anti-chase distance or stop quality rejected " + side;
            case "confidenceRejected" -> "Weighted confidence was below threshold for " + side;
            default -> "No accepted " + side + " candidate";
        };
    }

    private String phaseForRejection(String code) {
        return switch (code) {
            case "contextBiasFailed" -> "context";
            case "cloudBiasFailed", "structureFailed" -> "structure";
            case "momentumConfirmationFailed", "participationFailed" -> "momentum";
            default -> "risk";
        };
    }

    private org.algotradex.platform.contracts.intelligence.SetupType setupType(boolean pullback, boolean breakout, boolean earlyTransition) {
        if (pullback) {
            return org.algotradex.platform.contracts.intelligence.SetupType.PULLBACK;
        }
        if (breakout || earlyTransition) {
            return org.algotradex.platform.contracts.intelligence.SetupType.BREAKOUT;
        }
        return org.algotradex.platform.contracts.intelligence.SetupType.CONTINUATION;
    }

    private String timeframe(BarEvent bar) {
        return bar.timeframe() == null || bar.timeframe().isBlank() ? "M15" : bar.timeframe().toUpperCase(Locale.ROOT);
    }

    private BigDecimal decimal(double value) {
        return DoflamingoMomentumV5IntentSupport.decimal(value);
    }

    private BigDecimal decimal(int value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String evidenceValue(double value) {
        return Double.isFinite(value) ? decimal(value).toPlainString() : "n/a";
    }

    private ThoughtConditionEvidence numericEvidence(
            String conditionId,
            String label,
            ConditionRole role,
            boolean passed,
            String leftName,
            BigDecimal leftValue,
            String operator,
            String rightName,
            BigDecimal rightValue,
            String detail
    ) {
        return new ThoughtConditionEvidence(conditionId, label, role, passed,
                detail + " (" + leftName + "=" + leftValue + " " + operator + " " + rightName + "=" + rightValue + ")");
    }

    private double maxBelow(double boundary, double... candidates) {
        double selected = Double.NEGATIVE_INFINITY;
        for (double candidate : candidates) {
            if (Double.isFinite(candidate) && candidate < boundary) {
                selected = Math.max(selected, candidate);
            }
        }
        return selected;
    }

    private double minAbove(double boundary, double... candidates) {
        double selected = Double.POSITIVE_INFINITY;
        for (double candidate : candidates) {
            if (Double.isFinite(candidate) && candidate > boundary) {
                selected = Math.min(selected, candidate);
            }
        }
        return selected;
    }

    private record FlatEvaluation(
            Optional<TradeSignal> signal,
            Optional<StrategyTradeIntent> intent,
            List<ThoughtConditionEvidence> reasoning,
            String phase,
            String rejectionCode,
            String rejectionDetail
    ) {
    }

    private record EntryCandidate(
            PositionSide side,
            boolean accepted,
            BigDecimal confidence,
            StopComputation stop,
            int momentumConfirmations,
            double priceDistanceFromCloudAtr,
            boolean contextOk,
            boolean cloudStructure,
            boolean cloudQuality,
            boolean emaStructure,
            boolean macd,
            boolean stoch,
            boolean psar,
            boolean participation,
            boolean antiChase,
            boolean confidenceOk,
            String rejectionCode,
            String rejectionDetail,
            org.algotradex.platform.contracts.intelligence.SetupType setupType
    ) {
        EntryCandidate withPolicyRejection(String rejectionCode, String rejectionDetail) {
            return new EntryCandidate(side, false, confidence, stop, momentumConfirmations, priceDistanceFromCloudAtr, contextOk,
                    cloudStructure, cloudQuality, emaStructure, macd, stoch, psar, participation, antiChase,
                    confidenceOk, rejectionCode, rejectionDetail, setupType);
        }
    }

    private record StopComputation(BigDecimal stopPrice, BigDecimal stopPct, boolean valid, BigDecimal atrStopMultiple) {
    }
}
