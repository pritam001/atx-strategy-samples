package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.TagSet;
import org.algotradex.platform.contracts.common.value.TimeHorizon;
import org.algotradex.platform.contracts.intelligence.SetupType;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static java.util.Objects.requireNonNull;

/**
 * Lifecycle-aware EMA trend-structure pullback sample strategy.
 * <p>
 * The strategy evaluates closed bars against a fast/medium/slow EMA structure, tracks pullback
 * state per side, and emits a {@link TradeSignal} plus a {@link StrategyTradeIntent} when a
 * continuation or transition setup reaches the configured confidence threshold. While a position is
 * present, it may emit exit, scale-out, or optional scale-in intents using the runtime position
 * snapshot.
 * <p>
 * Instances are mutable and run-scoped. They are deterministic for the same ordered history,
 * position snapshot stream, and effective parameters, but they are not thread-safe and must not be
 * reused across concurrent replay runs.
 * <p>
 * This sample owns setup scoring and intent metadata only. It does not execute orders, route broker
 * requests, reserve capital, compute portfolio-level exposure, or guarantee that downstream runtime
 * accepts any emitted lifecycle intent.
 */
public final class EmaTrendStructurePullbackStrategy implements TradeIntentStrategy, ResumableStrategy, StrategyReasoningEvaluator {
    private static final double IDEAL_DISTANCE_FROM_FAST_EMA_MIN_PCT = 0.10d;
    private static final double TRANSITION_MAX_DISTANCE_FROM_FAST_EMA_PCT = 2.50d;

    private final EmaTrendStructurePullbackParameters params;
    private final SideRuntime longRuntime = new SideRuntime();
    private final SideRuntime shortRuntime = new SideRuntime();
    private int processedBars;
    private String lastProcessedEventId = "";

    EmaTrendStructurePullbackStrategy(EmaTrendStructurePullbackParameters params) {
        this.params = requireNonNull(params, "params");
    }

    @Override
    public String strategyId() {
        return EmaTrendStructurePullbackStrategyProvider.STRATEGY_ID;
    }

    @Override
    public String stateSchemaVersion() {
        return "ema-trend-structure-pullback-v2-state-v2";
    }

    @Override
    public Map<String, Object> snapshotState() {
        return Map.of(
                "processedBars", processedBars,
                "lastProcessedEventId", lastProcessedEventId,
                "longRuntime", sideRuntimeState(longRuntime),
                "shortRuntime", sideRuntimeState(shortRuntime)
        );
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        processedBars = asInt(state == null ? null : state.get("processedBars"), 0);
        lastProcessedEventId = asString(state == null ? null : state.get("lastProcessedEventId"));
        restoreSideRuntime(longRuntime, state == null ? null : state.get("longRuntime"));
        restoreSideRuntime(shortRuntime, state == null ? null : state.get("shortRuntime"));
    }

    @Override
    public List<StrategyCapability> lifecycleCapabilities() {
        return List.of(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.SCALE_IN_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
    }

    @Override
    public StrategyIntentResult onBarIntent(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.isEmpty()) {
            return StrategyIntentResult.empty();
        }
        EmaSeries series = EmaSeries.from(history, params);
        int finalIndex = history.size() - 1;
        if (!series.readyAt(finalIndex, params)) {
            return StrategyIntentResult.empty();
        }

        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            int startIndex = nextUnprocessedIndex(history);
            for (int index = startIndex; index <= finalIndex; index++) {
                markProcessed(history.get(index));
            }
            EmaSnapshot snapshot = EmaSnapshot.from(series, finalIndex, params);
            return lifecycleIntent(context, series, snapshot, position);
        }

        int startIndex = nextUnprocessedIndex(history);
        if (startIndex > finalIndex) {
            return StrategyIntentResult.empty();
        }

        Optional<SetupCandidate> latestCandidate = Optional.empty();
        for (int index = startIndex; index <= finalIndex; index++) {
            Optional<SetupCandidate> candidate = processIndex(series, index);
            if (candidate.isPresent() && index == finalIndex) {
                latestCandidate = candidate;
            }
            markProcessed(history.get(index));
        }
        if (latestCandidate.isEmpty()) {
            return StrategyIntentResult.empty();
        }

        SetupCandidate candidate = latestCandidate.get();
        StopComputation stop = stopComputation(series, candidate);
        BigDecimal adjustedConfidence = entryConfidence(candidate.strengthScore(), stop);
        if (adjustedConfidence.compareTo(params.minConfidence()) < 0) {
            return StrategyIntentResult.empty();
        }
        TradeSignal signal = signal(context, candidate, adjustedConfidence);
        StrategyTradeIntent intent = EmaTrendStructureIntentSupport.entryIntent(
                strategyId(),
                EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                context,
                side(candidate.direction()),
                adjustedConfidence,
                candidate.setupType(),
                params.riskFraction(),
                stop.exitPolicy(),
                params.maxHoldingBars(),
                entryReason(candidate, stop, adjustedConfidence)
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    @Override
    public List<ThoughtConditionEvidence> evaluateReasoning(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.isEmpty()) {
            return List.of(new ThoughtConditionEvidence("ema-stack", "EMA stack alignment", ConditionRole.REGIME_FILTER, false,
                    "No primary history is available yet."));
        }
        EmaSeries series = EmaSeries.from(history, params);
        int finalIndex = history.size() - 1;
        if (!series.readyAt(finalIndex, params)) {
            return List.of(new ThoughtConditionEvidence("ema-stack", "EMA stack alignment", ConditionRole.REGIME_FILTER, false,
                    "EMA stack is not ready; slow EMA plus slope history is still warming."));
        }
        EmaSnapshot snapshot = EmaSnapshot.from(series, finalIndex, params);
        StrategyInstrumentPosition position = context.instrumentPosition();
        if (position.hasPosition()) {
            return List.of(
                    new ThoughtConditionEvidence("cooldown", "Cooldown and position context", ConditionRole.POSITION_CONTEXT, false,
                            "Existing " + position.side() + " position is active; new entries are blocked."),
                    new ThoughtConditionEvidence("lifecycle-exit", "Lifecycle exit guard", ConditionRole.EXIT_TRIGGER, false,
                            "Lifecycle checks are active for the open position.")
            );
        }
        Optional<SetupCandidate> candidate = evaluateCandidateWithoutAdvancing(series, finalIndex);
        boolean trendReady = snapshot.trendStructure() == TrendStructure.CLEAN_UPTREND
                || snapshot.trendStructure() == TrendStructure.CLEAN_DOWNTREND
                || snapshot.trendStructure() == TrendStructure.EARLY_UPTREND
                || snapshot.trendStructure() == TrendStructure.EARLY_DOWNTREND
                || snapshot.trendStructure() == TrendStructure.PULLBACK_IN_UPTREND
                || snapshot.trendStructure() == TrendStructure.PULLBACK_IN_DOWNTREND;
        if (candidate.isEmpty()) {
            return List.of(
                    new ThoughtConditionEvidence("ema-stack", "EMA stack alignment", ConditionRole.REGIME_FILTER, trendReady,
                            "Trend structure is " + snapshot.trendStructure() + "."),
                    new ThoughtConditionEvidence("pullback", "Pullback touch", ConditionRole.ENTRY_TRIGGER, false,
                            "No qualifying pullback or transition setup exists on the current bar."),
                    new ThoughtConditionEvidence("distance-risk", "EMA distance risk", ConditionRole.RISK_GUARD, false,
                            "Distance checks did not produce an entry candidate."),
                    new ThoughtConditionEvidence("cooldown", "Cooldown and position context", ConditionRole.POSITION_CONTEXT, true,
                            "No open position blocks a new setup.")
            );
        }
        SetupCandidate setup = candidate.get();
        StopComputation stop = stopComputation(series, setup);
        BigDecimal confidence = entryConfidence(setup.strengthScore(), stop);
        boolean confidencePassed = confidence.compareTo(params.minConfidence()) >= 0;
        return List.of(
                new ThoughtConditionEvidence("ema-stack", "EMA stack alignment", ConditionRole.REGIME_FILTER, true,
                        "Trend structure is " + setup.snapshot().trendStructure() + "."),
                new ThoughtConditionEvidence("pullback", "Pullback touch", ConditionRole.ENTRY_TRIGGER, true,
                        setup.kind() + " candidate passed pullback quality checks."),
                numericEvidence("distance-risk", "EMA distance risk", ConditionRole.RISK_GUARD, confidencePassed,
                        "confidence", confidence, ">=", "minConfidence", params.minConfidence(),
                        confidencePassed ? "Confidence and distance risk are acceptable." : "Confidence after distance adjustment is below minimum."),
                new ThoughtConditionEvidence("cooldown", "Cooldown and position context", ConditionRole.POSITION_CONTEXT, true,
                        "No open position blocks a new setup.")
        );
    }

    @Override
    public String currentPhase(StrategyExecutionContext context) {
        requireNonNull(context, "context");
        List<BarEvent> history = context.instrumentHistory();
        if (history.isEmpty()) {
            return "trend";
        }
        EmaSeries series = EmaSeries.from(history, params);
        int finalIndex = history.size() - 1;
        if (!series.readyAt(finalIndex, params)) {
            return "trend";
        }
        if (context.instrumentPosition().hasPosition()) {
            return "lifecycle";
        }
        if (longRuntime.state() == SignalState.SIGNAL_EMITTED || shortRuntime.state() == SignalState.SIGNAL_EMITTED) {
            return "lifecycle";
        }
        if (longRuntime.state() == SignalState.PULLBACK_ARMED || shortRuntime.state() == SignalState.PULLBACK_ARMED) {
            return "pullback";
        }
        EmaSnapshot snapshot = EmaSnapshot.from(series, finalIndex, params);
        return trendStructureReady(snapshot.trendStructure()) ? "pullback" : "trend";
    }

    @Override
    public Optional<TradeSignal> onBar(StrategyExecutionContext context) {
        return onBarIntent(context).tradeSignals().stream().findFirst();
    }

    static double[] emaSeries(List<BarEvent> history, int period) {
        double[] closes = new double[history.size()];
        for (int index = 0; index < history.size(); index++) {
            closes[index] = close(history.get(index));
        }
        return computeEmaSeries(closes, period);
    }

    private StrategyIntentResult lifecycleIntent(
            StrategyExecutionContext context,
            EmaSeries series,
            EmaSnapshot snapshot,
            StrategyInstrumentPosition position
    ) {
        PositionSide side = position.side();
        if (side != PositionSide.LONG && side != PositionSide.SHORT) {
            return StrategyIntentResult.empty();
        }

        BigDecimal currentR = currentR(position);
        StopComputation lifecycleStop = lifecycleStopComputation(series, snapshot, side);
        ExitAssessment hardExit = hardExitAssessment(snapshot, position, currentR);
        if (hardExit.exit()) {
            StrategyTradeIntent intent = EmaTrendStructureIntentSupport.exitIntent(
                    strategyId(),
                    EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                    context,
                    side,
                    hardExit.confidence(),
                    SetupType.PULLBACK,
                    params.maxHoldingBars(),
                    exitReason(snapshot, lifecycleStop, position, currentR, hardExit)
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }

        if (canScaleOut(snapshot, position, currentR)) {
            BigDecimal confidence = scaleOutConfidence(snapshot, position, currentR);
            StrategyTradeIntent intent = EmaTrendStructureIntentSupport.scaleOutIntent(
                    strategyId(),
                    EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                    context,
                    side,
                    confidence,
                    SetupType.PULLBACK,
                    params.scaleOutFraction(),
                    params.maxHoldingBars(),
                    scaleOutReason(snapshot, lifecycleStop, position, currentR, confidence)
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }

        ScaleInAssessment scaleIn = scaleInAssessment(series, snapshot, position, currentR);
        if (scaleIn.emit()) {
            StrategyTradeIntent intent = EmaTrendStructureIntentSupport.scaleInIntent(
                    strategyId(),
                    EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                    context,
                    side,
                    scaleIn.confidence(),
                    SetupType.PULLBACK,
                    params.scaleInFraction(),
                    params.maxScaleIns(),
                    params.maxHoldingBars(),
                    scaleInReason(snapshot, lifecycleStop, position, currentR, scaleIn)
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }

        ExitAssessment timeExit = staleOrMaxExitAssessment(snapshot, position, currentR);
        if (timeExit.exit()) {
            StrategyTradeIntent intent = EmaTrendStructureIntentSupport.exitIntent(
                    strategyId(),
                    EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                    context,
                    side,
                    timeExit.confidence(),
                    SetupType.PULLBACK,
                    params.maxHoldingBars(),
                    exitReason(snapshot, lifecycleStop, position, currentR, timeExit)
            );
            return new StrategyIntentResult(List.of(), List.of(intent), List.of());
        }

        return StrategyIntentResult.empty();
    }

    private ExitAssessment hardExitAssessment(EmaSnapshot snapshot, StrategyInstrumentPosition position, BigDecimal currentR) {
        boolean isLong = position.side() == PositionSide.LONG;
        boolean closeBeyondMedium = isLong ? snapshot.close() < snapshot.ema50() : snapshot.close() > snapshot.ema50();
        boolean mixedStack = snapshot.emaStack() == EmaStack.MIXED_STACK;
        boolean mixedStackLosing = mixedStack && currentR.signum() <= 0;
        boolean oppositeStack = isLong ? snapshot.emaStack() == EmaStack.BEARISH_STACK : snapshot.emaStack() == EmaStack.BULLISH_STACK;
        boolean structureBreak = closeBeyondMedium || mixedStackLosing || oppositeStack;
        boolean compression = params.exitOnCompression()
                && currentR.signum() <= 0
                && snapshot.compressionState() == CompressionState.COMPRESSED;
        boolean chop = params.exitOnChop()
                && currentR.signum() <= 0
                && snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold();
        boolean postScaleWeakness = params.trailAfterScaleOut()
                && position.scaleOutCount() > 0
                && postScaleWeakness(snapshot, position.side());
        boolean breakEvenFailure = params.breakEvenAfterScaleOut()
                && position.scaleOutCount() > 0
                && currentR.signum() <= 0;
        boolean exit = structureBreak || compression || chop || postScaleWeakness || breakEvenFailure;
        return new ExitAssessment(
                exit,
                structureBreak,
                closeBeyondMedium,
                mixedStack,
                mixedStackLosing,
                oppositeStack,
                compression,
                chop,
                postScaleWeakness,
                breakEvenFailure,
                false,
                false,
                exitConfidence(closeBeyondMedium, mixedStackLosing || oppositeStack, compression, chop, postScaleWeakness || breakEvenFailure, false, false)
        );
    }

    private ExitAssessment staleOrMaxExitAssessment(EmaSnapshot snapshot, StrategyInstrumentPosition position, BigDecimal currentR) {
        boolean stale = position.barsHeld() >= params.staleBars() && currentR.compareTo(params.staleMinR()) <= 0;
        boolean maxHolding = position.barsHeld() >= params.maxHoldingBars();
        return new ExitAssessment(
                stale || maxHolding,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                stale,
                maxHolding,
                exitConfidence(false, false, false, false, false, stale, maxHolding)
        );
    }

    private boolean canScaleOut(EmaSnapshot snapshot, StrategyInstrumentPosition position, BigDecimal currentR) {
        return params.enableScaleOut()
                && position.scaleOutCount() == 0
                && currentR.compareTo(params.scaleOutAtR()) >= 0
                && position.maxFavorablePct().signum() > 0
                && trendValidForScale(position.side(), snapshot);
    }

    private ScaleInAssessment scaleInAssessment(
            EmaSeries series,
            EmaSnapshot snapshot,
            StrategyInstrumentPosition position,
            BigDecimal currentR
    ) {
        if (!params.enableScaleIn()) {
            return ScaleInAssessment.disabled();
        }
        boolean rPositive = currentR.compareTo(params.scaleInAtR()) >= 0 && currentR.signum() > 0;
        boolean countAvailable = position.scaleInCount() < params.maxScaleIns();
        boolean notCompressed = snapshot.compressionState() != CompressionState.COMPRESSED;
        boolean notChoppy = snapshot.recentPriceEmaCrossCount() < params.chopCrossCountThreshold();
        boolean clean = notCompressed && notChoppy;
        boolean trendAligned = trendAlignedForSide(position.side(), snapshot);
        PullbackQuality renewedPullback = renewedPullbackQuality(position.side(), series, snapshot.index());
        boolean renewed = trendAligned
                && clean
                && renewedPullback.realPullback()
                && renewedPullback.heldMediumEma()
                && reclaimedFastEma(position.side(), snapshot);
        int score = scorePrimary(direction(position.side()), snapshot, renewedPullback);
        BigDecimal confidence = confidence(score);
        boolean confidenceOk = confidence.compareTo(BigDecimal.valueOf(0.80)) >= 0;
        boolean emit = rPositive && countAvailable && renewed && clean && confidenceOk;
        return new ScaleInAssessment(emit, rPositive, countAvailable, renewed, notCompressed, notChoppy, confidenceOk, confidence);
    }

    private StopComputation stopComputation(EmaSeries series, SetupCandidate candidate) {
        return stopComputation(series, candidate.direction(), candidate.anchorIndex(), candidate.snapshot());
    }

    private StopComputation lifecycleStopComputation(EmaSeries series, EmaSnapshot snapshot, PositionSide side) {
        int anchorIndex = Math.max(0, snapshot.index() - params.pullbackLookbackBars());
        return stopComputation(series, direction(side), anchorIndex, snapshot);
    }

    private StopComputation stopComputation(EmaSeries series, Direction direction, int anchorIndex, EmaSnapshot snapshot) {
        double close = snapshot.close();
        double atr = atr(series, snapshot.index(), params.atrPeriod()).orElse(0.0d);
        double buffer = atr * params.atrStopMultiple().doubleValue();
        double structureAnchor = direction == Direction.LONG
                ? recentLow(series, anchorIndex, snapshot.index())
                : recentHigh(series, anchorIndex, snapshot.index());
        double[] anchors = direction == Direction.LONG
                ? new double[]{snapshot.ema50() - buffer, structureAnchor - buffer, close - buffer}
                : new double[]{snapshot.ema50() + buffer, structureAnchor + buffer, close + buffer};
        String[] names = new String[]{"ema50_atr", "pullback_atr", "close_atr"};

        double selected = Double.NaN;
        double rawStopPct = Double.NaN;
        String selectedName = "fallback";
        for (int index = 0; index < anchors.length; index++) {
            double anchor = anchors[index];
            boolean validSide = direction == Direction.LONG ? anchor < close : anchor > close;
            if (!Double.isFinite(anchor) || !validSide) {
                continue;
            }
            double distance = direction == Direction.LONG
                    ? percentageOfClose(close - anchor, close)
                    : percentageOfClose(anchor - close, close);
            if (distance > rawStopPct || Double.isNaN(rawStopPct)) {
                selected = anchor;
                rawStopPct = distance;
                selectedName = names[index];
            }
        }
        if (!Double.isFinite(rawStopPct) || rawStopPct <= 0.0d) {
            rawStopPct = params.minStopPct().doubleValue();
            selected = direction == Direction.LONG
                    ? close * (1.0d - pct(params.minStopPct()))
                    : close * (1.0d + pct(params.minStopPct()));
        }

        double stopPct = Math.max(params.minStopPct().doubleValue(), Math.min(params.maxStopPct().doubleValue(), rawStopPct));
        boolean clampedAtMax = rawStopPct > params.maxStopPct().doubleValue();
        BigDecimal stopPctDecimal = EmaTrendStructureIntentSupport.decimal(stopPct);
        return new StopComputation(
                selectedName,
                selected,
                rawStopPct,
                stopPctDecimal,
                clampedAtMax,
                EmaTrendStructureIntentSupport.percentStop(
                        stopPctDecimal,
                        "EMA pullback v2 " + params.stopMode() + " percent stop from " + selectedName
                )
        );
    }

    private BigDecimal entryConfidence(int strengthScore, StopComputation stop) {
        double score = strengthScore / 100.0d;
        boolean insideStopRange = stop.rawStopPct() >= params.minStopPct().doubleValue()
                && stop.rawStopPct() <= params.maxStopPct().doubleValue();
        if (insideStopRange) {
            score += 0.05d;
        }
        if (stop.clampedAtMax()) {
            score -= 0.10d;
        }
        return EmaTrendStructureIntentSupport.confidence(score);
    }

    private Optional<SetupCandidate> processIndex(EmaSeries series, int index) {
        longRuntime.onNewBar();
        shortRuntime.onNewBar();
        if (!series.readyAt(index, params)) {
            return Optional.empty();
        }

        EmaSnapshot snapshot = EmaSnapshot.from(series, index, params);
        resetInvalidatedSideState(snapshot);
        armPullbacks(series, snapshot);

        List<SetupCandidate> candidates = new ArrayList<>();
        primaryPullbackCandidate(Direction.LONG, series, snapshot).ifPresent(candidates::add);
        bullishTransitionCandidate(series, snapshot).ifPresent(candidates::add);
        if (params.allowShorts()) {
            primaryPullbackCandidate(Direction.SHORT, series, snapshot).ifPresent(candidates::add);
        }

        Optional<SetupCandidate> selected = candidates.stream()
                .filter(candidate -> candidate.confidence().compareTo(params.minConfidence()) >= 0)
                .max(Comparator.comparingInt(SetupCandidate::strengthScore)
                        .thenComparing(candidate -> candidate.kind().priority()));
        selected.ifPresent(candidate -> runtime(candidate.direction()).markEmitted(candidate.anchorIndex(), params.cooldownBars()));
        return selected;
    }

    private Optional<SetupCandidate> evaluateCandidateWithoutAdvancing(EmaSeries series, int index) {
        Map<String, Object> checkpoint = snapshotState();
        try {
            return processIndex(series, index);
        } finally {
            restoreState(checkpoint);
        }
    }

    private void resetInvalidatedSideState(EmaSnapshot snapshot) {
        if (snapshot.close() < snapshot.ema50()
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || snapshot.emaStack() == EmaStack.MIXED_STACK
                || snapshot.emaStack() == EmaStack.BEARISH_STACK
                || snapshot.emaStack() == EmaStack.BEARISH_TRANSITION) {
            longRuntime.reset();
        }
        if (snapshot.close() > snapshot.ema50()
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || snapshot.emaStack() == EmaStack.MIXED_STACK
                || snapshot.emaStack() == EmaStack.BULLISH_STACK
                || snapshot.emaStack() == EmaStack.BULLISH_TRANSITION) {
            shortRuntime.reset();
        }
    }

    private void armPullbacks(EmaSeries series, EmaSnapshot snapshot) {
        if (bullishEmaAlignment(snapshot)
                && bullishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && isPullbackBar(Direction.LONG, series, snapshot.index())) {
            longRuntime.arm(snapshot.index());
        }
        if (params.allowShorts()
                && bearishEmaAlignment(snapshot)
                && bearishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && isPullbackBar(Direction.SHORT, series, snapshot.index())) {
            shortRuntime.arm(snapshot.index());
        }
    }

    private Optional<SetupCandidate> primaryPullbackCandidate(Direction direction, EmaSeries series, EmaSnapshot snapshot) {
        SideRuntime runtime = runtime(direction);
        if (runtime.state() != SignalState.PULLBACK_ARMED || !runtime.canEmit(runtime.armedAnchorIndex())) {
            return Optional.empty();
        }
        int anchorIndex = runtime.armedAnchorIndex();
        if (anchorIndex < snapshot.index() - params.pullbackLookbackBars()
                || anchorIndex > snapshot.index() - params.pullbackMinBars()) {
            return Optional.empty();
        }
        if (direction == Direction.LONG && !currentLongContinuationConfirm(snapshot)) {
            return Optional.empty();
        }
        if (direction == Direction.SHORT && !currentShortContinuationConfirm(snapshot)) {
            return Optional.empty();
        }

        PullbackQuality pullback = pullbackQuality(direction, series, anchorIndex, snapshot.index());
        if (!pullback.realPullback() || !pullback.heldMediumEma()) {
            return Optional.empty();
        }
        if (hardReject(direction, snapshot, params.maxDistanceFromFastEmaPct().doubleValue())) {
            return Optional.empty();
        }

        SetupKind kind = direction == Direction.LONG
                ? SetupKind.BULLISH_PULLBACK_CONTINUATION
                : SetupKind.BEARISH_PULLBACK_CONTINUATION;
        int score = scorePrimary(direction, snapshot, pullback);
        return Optional.of(new SetupCandidate(direction, kind, SetupType.PULLBACK, anchorIndex, score, confidence(score), snapshot, pullback));
    }

    private Optional<SetupCandidate> bullishTransitionCandidate(EmaSeries series, EmaSnapshot snapshot) {
        int anchorIndex = snapshot.index() - 1;
        if (anchorIndex < 0 || !longRuntime.canEmit(anchorIndex)) {
            return Optional.empty();
        }
        if (snapshot.emaStack() != EmaStack.BULLISH_TRANSITION
                || snapshot.ema20SlopePct() <= 0.0d
                || snapshot.ema50SlopePct() <= 0.0d
                || snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.previousEmaSeparationPct() > params.compressedSeparationThresholdPct().doubleValue()
                || snapshot.emaSeparationPct() <= snapshot.previousEmaSeparationPct()
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()
                || sideDistancePct(Direction.LONG, snapshot.close(), snapshot.ema20()) > TRANSITION_MAX_DISTANCE_FROM_FAST_EMA_PCT
                || !breaksPriorHigh(series, snapshot.index(), params.transitionBreakoutLookbackBars())) {
            return Optional.empty();
        }

        int score = scoreTransition(snapshot);
        return Optional.of(new SetupCandidate(Direction.LONG, SetupKind.BULLISH_TRANSITION_BREAKOUT,
                SetupType.CONTINUATION, anchorIndex, score, confidence(score), snapshot, new PullbackQuality(true, true, true)));
    }

    private boolean currentLongContinuationConfirm(EmaSnapshot snapshot) {
        return snapshot.emaStack() == EmaStack.BULLISH_STACK
                && bullishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && snapshot.close() > snapshot.ema20()
                && snapshot.close() > snapshot.previousClose()
                && snapshot.closeLocation() >= 0.65d;
    }

    private boolean currentShortContinuationConfirm(EmaSnapshot snapshot) {
        return snapshot.emaStack() == EmaStack.BEARISH_STACK
                && bearishSlope(snapshot)
                && cleanEnoughForSetup(snapshot)
                && snapshot.close() < snapshot.ema20()
                && snapshot.close() < snapshot.previousClose()
                && snapshot.closeLocation() <= 0.35d;
    }

    private boolean hardReject(Direction direction, EmaSnapshot snapshot, double maxFastDistancePct) {
        if (snapshot.compressionState() == CompressionState.COMPRESSED
                || snapshot.trendStructure() == TrendStructure.MA_COMPRESSION
                || snapshot.trendStructure() == TrendStructure.CHOPPY
                || snapshot.recentPriceEmaCrossCount() >= params.chopCrossCountThreshold()) {
            return true;
        }
        if (direction == Direction.LONG && (!bullishSlope(snapshot) || snapshot.emaStack() == EmaStack.MIXED_STACK)) {
            return true;
        }
        if (direction == Direction.SHORT && (!bearishSlope(snapshot) || snapshot.emaStack() == EmaStack.MIXED_STACK)) {
            return true;
        }
        return sideDistancePct(direction, snapshot.close(), snapshot.ema20()) > maxFastDistancePct;
    }

    private boolean cleanEnoughForSetup(EmaSnapshot snapshot) {
        return snapshot.compressionState() != CompressionState.COMPRESSED
                && snapshot.recentPriceEmaCrossCount() < params.chopCrossCountThreshold();
    }

    private boolean bullishEmaAlignment(EmaSnapshot snapshot) {
        return snapshot.ema20() > snapshot.ema50() && snapshot.ema50() > snapshot.ema200();
    }

    private boolean bearishEmaAlignment(EmaSnapshot snapshot) {
        return snapshot.ema20() < snapshot.ema50() && snapshot.ema50() < snapshot.ema200();
    }

    private boolean bullishSlope(EmaSnapshot snapshot) {
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        return snapshot.ema20SlopePct() > threshold
                && snapshot.ema50SlopePct() > threshold
                && snapshot.ema200SlopePct() >= -threshold;
    }

    private boolean bearishSlope(EmaSnapshot snapshot) {
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        return snapshot.ema20SlopePct() < -threshold
                && snapshot.ema50SlopePct() < -threshold
                && snapshot.ema200SlopePct() <= threshold;
    }

    private boolean isPullbackBar(Direction direction, EmaSeries series, int index) {
        if (Double.isNaN(series.ema20()[index]) || Double.isNaN(series.ema50()[index])) {
            return false;
        }
        double tolerance = pct(params.emaTouchTolerancePct());
        double close = series.close()[index];
        if (direction == Direction.LONG) {
            boolean closedBelowFastHeldMedium = close < series.ema20()[index] && close >= series.ema50()[index];
            boolean approachedFastHeldMedium = series.low()[index] <= series.ema20()[index] * (1.0d + tolerance)
                    && close >= series.ema50()[index]
                    && sideDistancePct(Direction.LONG, close, series.ema20()[index]) <= params.maxDistanceFromFastEmaPct().doubleValue();
            return closedBelowFastHeldMedium || approachedFastHeldMedium;
        }
        boolean closedAboveFastHeldMedium = close > series.ema20()[index] && close <= series.ema50()[index];
        boolean approachedFastHeldMedium = series.high()[index] >= series.ema20()[index] * (1.0d - tolerance)
                && close <= series.ema50()[index]
                && sideDistancePct(Direction.SHORT, close, series.ema20()[index]) <= params.maxDistanceFromFastEmaPct().doubleValue();
        return closedAboveFastHeldMedium || approachedFastHeldMedium;
    }

    private PullbackQuality pullbackQuality(Direction direction, EmaSeries series, int anchorIndex, int currentIndex) {
        boolean realPullback = false;
        boolean heldMedium = true;
        boolean controlledDepth = true;
        double maxAdverseFastDistance = 0.0d;

        for (int index = anchorIndex; index < currentIndex; index++) {
            if (Double.isNaN(series.ema20()[index]) || Double.isNaN(series.ema50()[index])) {
                continue;
            }
            double close = series.close()[index];
            if (direction == Direction.LONG) {
                boolean belowFast = close < series.ema20()[index] && close >= series.ema50()[index];
                boolean approachedFast = series.low()[index] <= series.ema20()[index] * (1.0d + pct(params.emaTouchTolerancePct()))
                        && close >= series.ema50()[index];
                realPullback = realPullback || belowFast || approachedFast;
                heldMedium = heldMedium && close >= series.ema50()[index];
                maxAdverseFastDistance = Math.max(maxAdverseFastDistance, Math.max(0.0d, percentageChange(series.ema20()[index], Math.min(close, series.low()[index]))));
            } else {
                boolean aboveFast = close > series.ema20()[index] && close <= series.ema50()[index];
                boolean approachedFast = series.high()[index] >= series.ema20()[index] * (1.0d - pct(params.emaTouchTolerancePct()))
                        && close <= series.ema50()[index];
                realPullback = realPullback || aboveFast || approachedFast;
                heldMedium = heldMedium && close <= series.ema50()[index];
                maxAdverseFastDistance = Math.max(maxAdverseFastDistance, Math.max(0.0d, percentageChange(Math.max(close, series.high()[index]), series.ema20()[index])));
            }
        }
        controlledDepth = maxAdverseFastDistance <= params.maxDistanceFromFastEmaPct().doubleValue();
        return new PullbackQuality(realPullback, heldMedium, controlledDepth);
    }

    private int scorePrimary(Direction direction, EmaSnapshot snapshot, PullbackQuality pullback) {
        int score = 0;
        score += stackScore(direction, snapshot);
        score += slopeScore(direction, snapshot);
        if (pullback.realPullback()) score += 8;
        if (pullback.heldMediumEma()) score += 6;
        if ((direction == Direction.LONG && snapshot.close() > snapshot.ema20())
                || (direction == Direction.SHORT && snapshot.close() < snapshot.ema20())) {
            score += 4;
        }
        if (pullback.controlledDepth()) score += 2;
        score += momentumScore(direction, snapshot);
        score += structureCleanlinessScore(snapshot);
        score += riskLocationScore(direction, snapshot);
        return Math.min(100, score);
    }

    private int scoreTransition(EmaSnapshot snapshot) {
        int score = 0;
        score += stackScore(Direction.LONG, snapshot);
        score += slopeScore(Direction.LONG, snapshot);
        if (snapshot.previousEmaSeparationPct() <= params.compressedSeparationThresholdPct().doubleValue()) score += 8;
        if (snapshot.emaSeparationPct() > snapshot.previousEmaSeparationPct()) score += 6;
        score += 4;
        if (snapshot.ema20SlopePct() > 0.0d && snapshot.ema50SlopePct() > 0.0d) score += 2;
        score += momentumScore(Direction.LONG, snapshot);
        score += structureCleanlinessScore(snapshot);
        score += riskLocationScore(Direction.LONG, snapshot);
        return Math.min(100, score);
    }

    private int stackScore(Direction direction, EmaSnapshot snapshot) {
        if (direction == Direction.LONG) {
            if (snapshot.close() > snapshot.ema20() && snapshot.ema20() > snapshot.ema50() && snapshot.ema50() > snapshot.ema200()) {
                return 20;
            }
            if (snapshot.close() > snapshot.ema20() && snapshot.ema20() > snapshot.ema50() && snapshot.ema50() <= snapshot.ema200()) {
                return 12;
            }
            return 0;
        }
        if (snapshot.close() < snapshot.ema20() && snapshot.ema20() < snapshot.ema50() && snapshot.ema50() < snapshot.ema200()) {
            return 20;
        }
        return 0;
    }

    private int slopeScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        double threshold = params.flatSlopeThresholdPct().doubleValue();
        if (direction == Direction.LONG) {
            if (snapshot.ema20SlopePct() > threshold) score += 6;
            if (snapshot.ema50SlopePct() > threshold) score += 6;
            if (snapshot.ema200SlopePct() >= -threshold) score += 3;
        } else {
            if (snapshot.ema20SlopePct() < -threshold) score += 6;
            if (snapshot.ema50SlopePct() < -threshold) score += 6;
            if (snapshot.ema200SlopePct() <= threshold) score += 3;
        }
        return score;
    }

    private int momentumScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        if (direction == Direction.LONG) {
            if (snapshot.close() > snapshot.previousClose()) score += 5;
            if (snapshot.closeLocation() >= 0.65d) score += 5;
            if (snapshot.breaksPriorHigh() || (snapshot.threeBarReturnPct() > 0.0d
                    && sideDistancePct(direction, snapshot.close(), snapshot.ema20()) <= params.maxDistanceFromFastEmaPct().doubleValue())) {
                score += 5;
            }
        } else {
            if (snapshot.close() < snapshot.previousClose()) score += 5;
            if (snapshot.closeLocation() <= 0.35d) score += 5;
            if (snapshot.breaksPriorLow() || (snapshot.threeBarReturnPct() < 0.0d
                    && sideDistancePct(direction, snapshot.close(), snapshot.ema20()) <= params.maxDistanceFromFastEmaPct().doubleValue())) {
                score += 5;
            }
        }
        return score;
    }

    private int structureCleanlinessScore(EmaSnapshot snapshot) {
        int score = 0;
        if (snapshot.recentPriceEmaCrossCount() < 3) score += 7;
        if (snapshot.compressionState() != CompressionState.COMPRESSED) score += 5;
        if (snapshot.trendStructure() != TrendStructure.MIXED
                && snapshot.trendStructure() != TrendStructure.CHOPPY
                && snapshot.trendStructure() != TrendStructure.MA_COMPRESSION) {
            score += 3;
        }
        return score;
    }

    private int riskLocationScore(Direction direction, EmaSnapshot snapshot) {
        int score = 0;
        double fastDistance = sideDistancePct(direction, snapshot.close(), snapshot.ema20());
        if (fastDistance >= IDEAL_DISTANCE_FROM_FAST_EMA_MIN_PCT
                && fastDistance <= params.idealDistanceFromFastEmaPct().doubleValue()) {
            score += 10;
        }
        if (sideDistancePct(direction, snapshot.close(), snapshot.ema50()) <= params.maxDistanceFromMediumEmaPct().doubleValue()) {
            score += 5;
        }
        return score;
    }

    private BigDecimal confidence(int strengthScore) {
        double clamped = Math.max(0.50d, Math.min(0.95d, strengthScore / 100.0d));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
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
        BigDecimal left = leftValue.setScale(4, RoundingMode.HALF_UP);
        BigDecimal right = rightValue.setScale(4, RoundingMode.HALF_UP);
        BigDecimal distance = passed ? null : right.subtract(left).abs();
        return new ThoughtConditionEvidence(id, label, role, passed, leftName, left, operator, rightName, right, distance, message);
    }

    private StrategyTradeIntentReason entryReason(SetupCandidate candidate, StopComputation stop, BigDecimal confidence) {
        EmaSnapshot snapshot = candidate.snapshot();
        Direction direction = candidate.direction();
        PullbackQuality pullback = candidate.pullbackQuality();
        boolean stackAligned = direction == Direction.LONG
                ? snapshot.emaStack() == EmaStack.BULLISH_STACK || snapshot.emaStack() == EmaStack.BULLISH_TRANSITION
                : snapshot.emaStack() == EmaStack.BEARISH_STACK || snapshot.emaStack() == EmaStack.BEARISH_TRANSITION;
        boolean slopeAligned = direction == Direction.LONG ? bullishSlope(snapshot) : bearishSlope(snapshot);
        boolean reclaim = direction == Direction.LONG ? snapshot.close() > snapshot.ema20() : snapshot.close() < snapshot.ema20();
        boolean notCompressed = snapshot.compressionState() != CompressionState.COMPRESSED;
        boolean notChoppy = snapshot.recentPriceEmaCrossCount() < params.chopCrossCountThreshold();
        double fastDistance = sideDistancePct(direction, snapshot.close(), snapshot.ema20());
        boolean distanceAcceptable = fastDistance <= params.maxDistanceFromFastEmaPct().doubleValue();
        boolean stopAcceptable = stop.stopPct().compareTo(params.minStopPct()) >= 0 && stop.stopPct().compareTo(params.maxStopPct()) <= 0;
        String stackConditionId = direction == Direction.LONG ? "ema-v2.bullish-stack" : "ema-v2.bearish-stack";
        return EmaTrendStructureIntentSupport.reason(
                "EMA trend-structure pullback v2 entry: " + candidate.kind().tagValue(),
                commonEvidence(snapshot, List.of(
                        "setupKind=" + candidate.kind().tagValue(),
                        "strengthScore=" + candidate.strengthScore(),
                        "confidence=" + confidence,
                        "stopMode=" + params.stopMode(),
                        "stopAnchor=" + stop.anchorName(),
                        "rawStopPct=" + formatPct(stop.rawStopPct()),
                        "stopPct=" + stop.stopPct(),
                        "riskFraction=" + params.riskFraction(),
                        "maxHoldingBars=" + params.maxHoldingBars()
                )),
                List.of("ema-trend-structure", "v2", "lifecycle", "entry", setupTag(candidate), "risk", "confidence"),
                List.of(
                        EmaTrendStructureIntentSupport.condition(stackConditionId, "EMA stack supports entry side", "Stack aligned", stackAligned ? 1.0d : 0.0d, "=", "Required", 1.0d, stackAligned),
                        EmaTrendStructureIntentSupport.condition("ema-v2.slope-aligned", "EMA slopes align with entry side", "Slope aligned", slopeAligned ? 1.0d : 0.0d, "=", "Required", 1.0d, slopeAligned),
                        EmaTrendStructureIntentSupport.condition("ema-v2.pullback-real", "Pullback touched the fast EMA zone", "Real pullback", pullback.realPullback() ? 1.0d : 0.0d, "=", "Required", 1.0d, pullback.realPullback()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.pullback-held-ema50", "Pullback held the medium EMA", "Held EMA50", pullback.heldMediumEma() ? 1.0d : 0.0d, "=", "Required", 1.0d, pullback.heldMediumEma()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.reclaim-ema20", "Current close reclaimed EMA20", "Close", snapshot.close(), direction == Direction.LONG ? ">" : "<", "EMA20", snapshot.ema20(), reclaim),
                        EmaTrendStructureIntentSupport.condition("ema-v2.not-compressed", "EMA stack is not compressed", "Compression", notCompressed ? 0.0d : 1.0d, "=", "Compressed flag", 0.0d, notCompressed),
                        EmaTrendStructureIntentSupport.condition("ema-v2.not-choppy", "Recent EMA crosses remain below chop threshold", "Recent crosses", snapshot.recentPriceEmaCrossCount(), "<", "Chop threshold", params.chopCrossCountThreshold(), notChoppy),
                        EmaTrendStructureIntentSupport.condition("ema-v2.distance-acceptable", "Close is not overextended from EMA20", "Distance from EMA20 %", fastDistance, "<=", "Maximum distance %", params.maxDistanceFromFastEmaPct().doubleValue(), distanceAcceptable),
                        EmaTrendStructureIntentSupport.condition("ema-v2.stop-distance-acceptable", "Computed stop distance is within configured bounds", "Stop %", stop.stopPct(), "<=", "Maximum stop %", params.maxStopPct(), stopAcceptable),
                        EmaTrendStructureIntentSupport.condition("ema-v2.confidence-threshold", "Dynamic confidence meets threshold", "Confidence", confidence, ">=", "Minimum confidence", params.minConfidence(), true)
                )
        );
    }

    private StrategyTradeIntentReason scaleOutReason(
            EmaSnapshot snapshot,
            StopComputation stop,
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            BigDecimal confidence
    ) {
        boolean trendValid = trendValidForScale(position.side(), snapshot);
        return EmaTrendStructureIntentSupport.reason(
                "EMA trend-structure pullback v2 scale-out at configured R multiple",
                lifecycleEvidence(snapshot, stop, position, currentR, List.of(
                        "scaleOutAtR=" + params.scaleOutAtR(),
                        "scaleOutFraction=" + params.scaleOutFraction(),
                        "confidence=" + confidence
                )),
                List.of("ema-trend-structure", "v2", "lifecycle", "scale-out", "pullback", "risk", "confidence"),
                List.of(
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-out-r-multiple", "Current R reached scale-out threshold", "Current R", currentR, ">=", "Scale-out R", params.scaleOutAtR(), true),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-out-favorable-excursion", "Position has favorable excursion", "Max favorable %", position.maxFavorablePct(), ">", "Zero", BigDecimal.ZERO, position.maxFavorablePct().signum() > 0),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-out-trend-valid", "Trend is not fully broken", "Trend valid", trendValid ? 1.0d : 0.0d, "=", "Required", 1.0d, trendValid)
                )
        );
    }

    private StrategyTradeIntentReason scaleInReason(
            EmaSnapshot snapshot,
            StopComputation stop,
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            ScaleInAssessment assessment
    ) {
        return EmaTrendStructureIntentSupport.reason(
                "EMA trend-structure pullback v2 scale-in on renewed pullback confirmation",
                lifecycleEvidence(snapshot, stop, position, currentR, List.of(
                        "scaleInAtR=" + params.scaleInAtR(),
                        "scaleInFraction=" + params.scaleInFraction(),
                        "maxScaleIns=" + params.maxScaleIns(),
                        "scaleInNotCompressed=" + assessment.notCompressed(),
                        "scaleInNotChoppy=" + assessment.notChoppy(),
                        "confidence=" + assessment.confidence()
                )),
                List.of("ema-trend-structure", "v2", "lifecycle", "scale-in", "pullback", "risk", "confidence"),
                List.of(
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-in-r-positive", "Scale-in requires profitable R multiple", "Current R", currentR, ">=", "Scale-in R", params.scaleInAtR(), assessment.rPositive()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-in-count-available", "Scale-in count remains available", "Scale-in count", position.scaleInCount(), "<", "Maximum scale-ins", params.maxScaleIns(), assessment.countAvailable()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-in-renewed-pullback", "Renewed EMA20 pullback confirmation exists", "Renewed pullback", assessment.renewedPullback() ? 1.0d : 0.0d, "=", "Required", 1.0d, assessment.renewedPullback()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-in-not-choppy", "Structure is not compressed or choppy", "Clean structure", assessment.clean() ? 1.0d : 0.0d, "=", "Required", 1.0d, assessment.clean()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.scale-in-confidence-threshold", "Scale-in confidence meets stricter threshold", "Confidence", assessment.confidence(), ">=", "Scale-in confidence", BigDecimal.valueOf(0.80), assessment.confidenceOk())
                )
        );
    }

    private StrategyTradeIntentReason exitReason(
            EmaSnapshot snapshot,
            StopComputation stop,
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            ExitAssessment assessment
    ) {
        String summary = "EMA trend-structure pullback v2 lifecycle exit";
        if (assessment.postScaleWeakness() && assessment.breakEvenFailure()) {
            summary = summary + ": post-scale trailing weakness and breakeven failure";
        } else if (assessment.postScaleWeakness()) {
            summary = summary + ": post-scale trailing weakness";
        } else if (assessment.breakEvenFailure()) {
            summary = summary + ": breakeven failure after scale-out";
        }
        return EmaTrendStructureIntentSupport.reason(
                summary,
                lifecycleEvidence(snapshot, stop, position, currentR, List.of(
                        "structureBreak=" + assessment.structureBreak(),
                        "closeBeyondMedium=" + assessment.closeBeyondMedium(),
                        "mixedStack=" + assessment.mixedStack(),
                        "mixedStackLosing=" + assessment.mixedStackLosing(),
                        "oppositeStack=" + assessment.oppositeStack(),
                        "compressionExit=" + assessment.compression(),
                        "chopExit=" + assessment.chop(),
                        "postScaleWeakness=" + assessment.postScaleWeakness(),
                        "breakEvenFailure=" + assessment.breakEvenFailure(),
                        "postScaleTrail=" + assessment.postScaleExit(),
                        "staleExit=" + assessment.stale(),
                        "maxHoldingExit=" + assessment.maxHolding(),
                        "confidence=" + assessment.confidence()
                )),
                List.of("ema-trend-structure", "v2", "lifecycle", "exit", "pullback", "risk", "confidence"),
                List.of(
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-structure-break", "EMA structure has broken", "Structure break", assessment.structureBreak() ? 1.0d : 0.0d, "=", "Required", 1.0d, assessment.structureBreak()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-stale-bars", "Bars held reached stale threshold", "Bars held", position.barsHeld(), ">=", "Stale bars", params.staleBars(), assessment.stale()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-stale-r", "R multiple remains below stale threshold", "Current R", currentR, "<=", "Stale minimum R", params.staleMinR(), assessment.stale()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-post-scale-trail", "Post-scale trailing or breakeven weakness", "Post-scale weakness", assessment.postScaleExit() ? 1.0d : 0.0d, "=", "Required", 1.0d, assessment.postScaleExit()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-max-holding", "Bars held reached max holding threshold", "Bars held", position.barsHeld(), ">=", "Max holding bars", params.maxHoldingBars(), assessment.maxHolding()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-compression", "Losing trade entered EMA compression", "Compression", assessment.compression() ? 1.0d : 0.0d, "=", "Required", 1.0d, assessment.compression()),
                        EmaTrendStructureIntentSupport.condition("ema-v2.exit-chop", "Losing trade entered chop", "Recent crosses", snapshot.recentPriceEmaCrossCount(), ">=", "Chop threshold", params.chopCrossCountThreshold(), assessment.chop())
                )
        );
    }

    private TradeSignal signal(StrategyExecutionContext context, SetupCandidate candidate, BigDecimal confidence) {
        BarEvent bar = context.currentBar();
        EmaSnapshot snapshot = candidate.snapshot();
        return new TradeSignal(
                EmaTrendStructurePullbackStrategyProvider.STRATEGY_VERSION,
                new SignalId("signal-" + strategyId() + '-' + bar.eventId().value() + '-' + candidate.kind().tagValue()),
                bar.instrument(),
                candidate.direction(),
                new ConfidenceScore(confidence),
                candidate.setupType(),
                new TimeHorizon("short_continuation", Duration.ofHours(4)),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId()),
                null,
                new TagSet(List.of(
                        "strategy_family=ema_trend_structure_pullback",
                        "strategy_id=" + strategyId(),
                        "setup=" + candidate.kind().tagValue(),
                        "strength_score=" + candidate.strengthScore(),
                        "ema_stack=" + snapshot.emaStack().name().toLowerCase(Locale.ROOT),
                        "trend_structure=" + snapshot.trendStructure().name().toLowerCase(Locale.ROOT),
                        "recent_cross_count=" + snapshot.recentPriceEmaCrossCount(),
                        "distance_from_ema20_pct=" + formatPct(sideDistancePct(candidate.direction(), snapshot.close(), snapshot.ema20())),
                        "formula_version=ema-trend-structure-pullback-v2"
                )),
                bar.cohort(),
                bar.baseline()
        );
    }

    private SideRuntime runtime(Direction direction) {
        return direction == Direction.LONG ? longRuntime : shortRuntime;
    }

    private void resetRuntime() {
        processedBars = 0;
        lastProcessedEventId = "";
        longRuntime.resetAll();
        shortRuntime.resetAll();
    }

    private int nextUnprocessedIndex(List<BarEvent> history) {
        if (!lastProcessedEventId.isBlank()) {
            for (int index = history.size() - 1; index >= 0; index--) {
                if (lastProcessedEventId.equals(history.get(index).eventId().value())) {
                    return index + 1;
                }
            }
            if (processedBars > history.size()) {
                // Retained Simulation Lab windows may omit the checkpoint anchor. In that case,
                // the only bar guaranteed to be post-checkpoint is the current tail bar.
                return history.size() - 1;
            }
        }
        if (processedBars <= 0) {
            return 0;
        }
        return processedBars <= history.size() ? processedBars : history.size() - 1;
    }

    private void markProcessed(BarEvent bar) {
        processedBars++;
        lastProcessedEventId = bar.eventId().value();
    }

    private List<String> commonEvidence(EmaSnapshot snapshot, List<String> additionalEvidence) {
        List<String> evidence = new ArrayList<>();
        evidence.add("emaStack=" + snapshot.emaStack());
        evidence.add("trendStructure=" + snapshot.trendStructure());
        evidence.add("ema20SlopePct=" + formatPct(snapshot.ema20SlopePct()));
        evidence.add("ema50SlopePct=" + formatPct(snapshot.ema50SlopePct()));
        evidence.add("emaSeparationPct=" + formatPct(snapshot.emaSeparationPct()));
        evidence.add("recentCrossCount=" + snapshot.recentPriceEmaCrossCount());
        evidence.add("distanceFromEma20Pct=" + formatPct(Math.abs(percentageChange(snapshot.close(), snapshot.ema20()))));
        evidence.addAll(additionalEvidence);
        return evidence;
    }

    private List<String> lifecycleEvidence(
            EmaSnapshot snapshot,
            StopComputation stop,
            StrategyInstrumentPosition position,
            BigDecimal currentR,
            List<String> additionalEvidence
    ) {
        List<String> evidence = new ArrayList<>();
        evidence.add("positionSide=" + position.side());
        evidence.add("stopMode=" + params.stopMode());
        evidence.add("stopAnchor=" + stop.anchorName());
        evidence.add("currentStopPct=" + stop.stopPct());
        evidence.add("riskFraction=" + params.riskFraction());
        evidence.add("maxHoldingBars=" + params.maxHoldingBars());
        evidence.add("positionBarsHeld=" + position.barsHeld());
        evidence.add("currentR=" + currentR);
        evidence.add("mfeR=" + mfeR(position));
        evidence.add("maxFavorablePct=" + position.maxFavorablePct());
        evidence.add("scaleOutCount=" + position.scaleOutCount());
        evidence.add("scaleInCount=" + position.scaleInCount());
        evidence.addAll(additionalEvidence);
        return commonEvidence(snapshot, evidence);
    }

    private String setupTag(SetupCandidate candidate) {
        return candidate.kind() == SetupKind.BULLISH_TRANSITION_BREAKOUT ? "transition" : "pullback";
    }

    private BigDecimal scaleOutConfidence(EmaSnapshot snapshot, StrategyInstrumentPosition position, BigDecimal currentR) {
        double score = 0.75d;
        if (currentR.compareTo(BigDecimal.valueOf(1.50)) >= 0) {
            score += 0.10d;
        }
        if (position.maxFavorablePct().signum() > 0) {
            score += 0.05d;
        }
        if (ema20Weakening(snapshot, position.side())) {
            score -= 0.05d;
        }
        return EmaTrendStructureIntentSupport.confidence(score);
    }

    private BigDecimal exitConfidence(
            boolean closeBeyondMedium,
            boolean mixedOrOppositeStack,
            boolean compression,
            boolean chop,
            boolean postScaleWeakness,
            boolean stale,
            boolean maxHolding
    ) {
        double score = 0.50d;
        if (closeBeyondMedium && mixedOrOppositeStack) {
            score = Math.max(score, 0.90d);
        } else if (closeBeyondMedium || mixedOrOppositeStack) {
            score = Math.max(score, 0.85d);
        }
        if (postScaleWeakness) {
            score = Math.max(score, 0.80d);
        }
        if (compression || chop) {
            score = Math.max(score, 0.75d);
        }
        if (stale) {
            score = Math.max(score, 0.70d);
        }
        if (maxHolding) {
            score = Math.max(score, 0.65d);
        }
        return EmaTrendStructureIntentSupport.confidence(score);
    }

    private boolean postScaleWeakness(EmaSnapshot snapshot, PositionSide side) {
        if (side == PositionSide.LONG) {
            return (snapshot.close() < snapshot.ema20() && snapshot.ema20SlopePct() <= params.flatSlopeThresholdPct().doubleValue())
                    || snapshot.close() < snapshot.ema50();
        }
        return (snapshot.close() > snapshot.ema20() && snapshot.ema20SlopePct() >= -params.flatSlopeThresholdPct().doubleValue())
                || snapshot.close() > snapshot.ema50();
    }

    private boolean ema20Weakening(EmaSnapshot snapshot, PositionSide side) {
        return side == PositionSide.LONG
                ? snapshot.ema20SlopePct() <= params.flatSlopeThresholdPct().doubleValue()
                : snapshot.ema20SlopePct() >= -params.flatSlopeThresholdPct().doubleValue();
    }

    private boolean trendValidForScale(PositionSide side, EmaSnapshot snapshot) {
        if (side == PositionSide.LONG) {
            return snapshot.close() >= snapshot.ema50()
                    && snapshot.emaStack() != EmaStack.BEARISH_STACK
                    && snapshot.emaStack() != EmaStack.MIXED_STACK;
        }
        return snapshot.close() <= snapshot.ema50()
                && snapshot.emaStack() != EmaStack.BULLISH_STACK
                && snapshot.emaStack() != EmaStack.MIXED_STACK;
    }

    private boolean trendAlignedForSide(PositionSide side, EmaSnapshot snapshot) {
        return side == PositionSide.LONG
                ? snapshot.emaStack() == EmaStack.BULLISH_STACK && bullishSlope(snapshot)
                : snapshot.emaStack() == EmaStack.BEARISH_STACK && bearishSlope(snapshot);
    }

    private PullbackQuality renewedPullbackQuality(PositionSide side, EmaSeries series, int currentIndex) {
        int anchorIndex = Math.max(0, currentIndex - params.pullbackLookbackBars());
        return pullbackQuality(direction(side), series, anchorIndex, currentIndex);
    }

    private boolean reclaimedFastEma(PositionSide side, EmaSnapshot snapshot) {
        if (side == PositionSide.LONG) {
            return snapshot.close() > snapshot.ema20()
                    && snapshot.close() > snapshot.previousClose()
                    && snapshot.closeLocation() >= 0.65d;
        }
        return snapshot.close() < snapshot.ema20()
                && snapshot.close() < snapshot.previousClose()
                && snapshot.closeLocation() <= 0.35d;
    }

    private static BigDecimal currentR(StrategyInstrumentPosition position) {
        return position.currentRMultiple() == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : position.currentRMultiple();
    }

    private static BigDecimal mfeR(StrategyInstrumentPosition position) {
        BigDecimal initialRisk = position.initialRiskUnit();
        if (initialRisk == null || initialRisk.signum() == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return position.maxFavorablePct().divide(initialRisk, 4, RoundingMode.HALF_UP);
    }

    private static PositionSide side(Direction direction) {
        return direction == Direction.LONG ? PositionSide.LONG : PositionSide.SHORT;
    }

    private static Direction direction(PositionSide side) {
        return side == PositionSide.LONG ? Direction.LONG : Direction.SHORT;
    }

    private static boolean breaksPriorHigh(EmaSeries series, int index, int lookbackBars) {
        return series.close()[index] > priorHigh(series, index, lookbackBars);
    }

    private static boolean breaksPriorLow(EmaSeries series, int index, int lookbackBars) {
        return series.close()[index] < priorLow(series, index, lookbackBars);
    }

    private static double priorHigh(EmaSeries series, int index, int lookbackBars) {
        double high = Double.NEGATIVE_INFINITY;
        int start = Math.max(0, index - lookbackBars);
        for (int candidate = start; candidate < index; candidate++) {
            high = Math.max(high, series.high()[candidate]);
        }
        return high;
    }

    private static double priorLow(EmaSeries series, int index, int lookbackBars) {
        double low = Double.POSITIVE_INFINITY;
        int start = Math.max(0, index - lookbackBars);
        for (int candidate = start; candidate < index; candidate++) {
            low = Math.min(low, series.low()[candidate]);
        }
        return low;
    }

    private static double recentLow(EmaSeries series, int startIndex, int endIndex) {
        double low = Double.POSITIVE_INFINITY;
        int start = Math.max(0, startIndex);
        int end = Math.min(series.low().length - 1, endIndex);
        for (int index = start; index <= end; index++) {
            low = Math.min(low, series.low()[index]);
        }
        return low;
    }

    private static double recentHigh(EmaSeries series, int startIndex, int endIndex) {
        double high = Double.NEGATIVE_INFINITY;
        int start = Math.max(0, startIndex);
        int end = Math.min(series.high().length - 1, endIndex);
        for (int index = start; index <= end; index++) {
            high = Math.max(high, series.high()[index]);
        }
        return high;
    }

    private static OptionalDouble atr(EmaSeries series, int index, int period) {
        if (period <= 0 || index <= 0 || index < period) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        int start = index - period + 1;
        for (int cursor = start; cursor <= index; cursor++) {
            double highLow = series.high()[cursor] - series.low()[cursor];
            double highPrevClose = Math.abs(series.high()[cursor] - series.close()[cursor - 1]);
            double lowPrevClose = Math.abs(series.low()[cursor] - series.close()[cursor - 1]);
            total += Math.max(highLow, Math.max(highPrevClose, lowPrevClose));
        }
        return OptionalDouble.of(total / period);
    }

    private static int countRecentPriceEmaCrosses(EmaSeries series, int currentIndex, int lookbackBars) {
        int start = Math.max(1, currentIndex - lookbackBars + 1);
        int crosses = 0;
        for (int index = start; index <= currentIndex; index++) {
            if (crossed(series.close()[index - 1], series.close()[index], series.ema20()[index - 1], series.ema20()[index])) crosses++;
            if (crossed(series.close()[index - 1], series.close()[index], series.ema50()[index - 1], series.ema50()[index])) crosses++;
        }
        return crosses;
    }

    private static boolean crossed(double previousClose, double currentClose, double previousEma, double currentEma) {
        if (Double.isNaN(previousEma) || Double.isNaN(currentEma)) {
            return false;
        }
        return (previousClose - previousEma) * (currentClose - currentEma) < 0.0d;
    }

    private static double[] computeEmaSeries(double[] values, int period) {
        double[] series = new double[values.length];
        java.util.Arrays.fill(series, Double.NaN);
        if (period <= 0) {
            return series;
        }
        double seedTotal = 0.0d;
        double current = Double.NaN;
        double alpha = 2.0d / (period + 1.0d);
        for (int index = 0; index < values.length; index++) {
            double value = values[index];
            if (index < period - 1) {
                seedTotal += value;
                continue;
            }
            if (index == period - 1) {
                seedTotal += value;
                current = seedTotal / period;
            } else {
                current = (value * alpha) + (current * (1.0d - alpha));
            }
            series[index] = current;
        }
        return series;
    }

    private static double sideDistancePct(Direction direction, double close, double ema) {
        return direction == Direction.LONG ? percentageChange(close, ema) : percentageChange(ema, close);
    }

    private static double percentageChange(double value, double base) {
        if (base == 0.0d) {
            return 0.0d;
        }
        return ((value - base) / base) * 100.0d;
    }

    private static double percentageOfClose(double value, double close) {
        if (close == 0.0d) {
            return 0.0d;
        }
        return (value / Math.abs(close)) * 100.0d;
    }

    private static Map<String, Object> sideRuntimeState(SideRuntime runtime) {
        return Map.of(
                "state", runtime.state.name(),
                "armedAnchorIndex", runtime.armedAnchorIndex,
                "lastSignalAnchorIndex", runtime.lastSignalAnchorIndex,
                "cooldownRemaining", runtime.cooldownRemaining
        );
    }

    private static void restoreSideRuntime(SideRuntime runtime, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            runtime.resetAll();
            return;
        }
        Object state = map.get("state");
        runtime.state = SignalState.valueOf(state == null ? SignalState.IDLE.name() : state.toString());
        runtime.armedAnchorIndex = asInt(map.get("armedAnchorIndex"), -1);
        runtime.lastSignalAnchorIndex = asInt(map.get("lastSignalAnchorIndex"), -1);
        runtime.cooldownRemaining = asInt(map.get("cooldownRemaining"), 0);
    }

    private static int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : "";
    }

    private static double pct(BigDecimal value) {
        return value.doubleValue() / 100.0d;
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double close(BarEvent bar) {
        return bar.ohlcv().close().doubleValue();
    }

    private static boolean trendStructureReady(TrendStructure trendStructure) {
        return trendStructure == TrendStructure.CLEAN_UPTREND
                || trendStructure == TrendStructure.CLEAN_DOWNTREND
                || trendStructure == TrendStructure.EARLY_UPTREND
                || trendStructure == TrendStructure.EARLY_DOWNTREND
                || trendStructure == TrendStructure.PULLBACK_IN_UPTREND
                || trendStructure == TrendStructure.PULLBACK_IN_DOWNTREND;
    }

    private enum SignalState {
        IDLE,
        PULLBACK_ARMED,
        SIGNAL_EMITTED
    }

    private enum EmaStack {
        BULLISH_STACK,
        BEARISH_STACK,
        BULLISH_TRANSITION,
        BEARISH_TRANSITION,
        MIXED_STACK
    }

    private enum SlopeState {
        RISING,
        FALLING,
        FLAT,
        MIXED
    }

    private enum CompressionState {
        COMPRESSED,
        EXPANDING,
        NORMAL
    }

    private enum TrendStructure {
        CLEAN_UPTREND,
        PULLBACK_IN_UPTREND,
        EARLY_UPTREND,
        MA_COMPRESSION,
        CHOPPY,
        MIXED,
        EARLY_DOWNTREND,
        PULLBACK_IN_DOWNTREND,
        CLEAN_DOWNTREND
    }

    private enum SetupKind {
        BULLISH_PULLBACK_CONTINUATION("bullish_pullback_continuation", 3),
        BEARISH_PULLBACK_CONTINUATION("bearish_pullback_continuation", 3),
        BULLISH_TRANSITION_BREAKOUT("bullish_transition_breakout", 1);

        private final String tagValue;
        private final int priority;

        SetupKind(String tagValue, int priority) {
            this.tagValue = tagValue;
            this.priority = priority;
        }

        String tagValue() {
            return tagValue;
        }

        int priority() {
            return priority;
        }
    }

    private record StopComputation(
            String anchorName,
            double anchorPrice,
            double rawStopPct,
            BigDecimal stopPct,
            boolean clampedAtMax,
            TradeIntentExitPolicy exitPolicy
    ) {
    }

    private record ExitAssessment(
            boolean exit,
            boolean structureBreak,
            boolean closeBeyondMedium,
            boolean mixedStack,
            boolean mixedStackLosing,
            boolean oppositeStack,
            boolean compression,
            boolean chop,
            boolean postScaleWeakness,
            boolean breakEvenFailure,
            boolean stale,
            boolean maxHolding,
            BigDecimal confidence
    ) {
        boolean postScaleExit() {
            return postScaleWeakness || breakEvenFailure;
        }
    }

    private record ScaleInAssessment(
            boolean emit,
            boolean rPositive,
            boolean countAvailable,
            boolean renewedPullback,
            boolean notCompressed,
            boolean notChoppy,
            boolean confidenceOk,
            BigDecimal confidence
    ) {
        boolean clean() {
            return notCompressed && notChoppy;
        }

        static ScaleInAssessment disabled() {
            return new ScaleInAssessment(false, false, false, false, false, false, false, BigDecimal.valueOf(0.50));
        }
    }

    private static final class SideRuntime {
        private SignalState state = SignalState.IDLE;
        private int armedAnchorIndex = -1;
        private int lastSignalAnchorIndex = -1;
        private int cooldownRemaining;

        void onNewBar() {
            if (cooldownRemaining > 0) {
                cooldownRemaining--;
            }
        }

        SignalState state() {
            return state;
        }

        int armedAnchorIndex() {
            return armedAnchorIndex;
        }

        void arm(int anchorIndex) {
            if (cooldownRemaining > 0) {
                return;
            }
            if (state == SignalState.PULLBACK_ARMED) {
                return;
            }
            if (state == SignalState.SIGNAL_EMITTED && anchorIndex == lastSignalAnchorIndex) {
                return;
            }
            state = SignalState.PULLBACK_ARMED;
            armedAnchorIndex = anchorIndex;
        }

        boolean canEmit(int anchorIndex) {
            return anchorIndex >= 0 && cooldownRemaining <= 0 && anchorIndex != lastSignalAnchorIndex;
        }

        void markEmitted(int anchorIndex, int cooldownBars) {
            state = SignalState.SIGNAL_EMITTED;
            armedAnchorIndex = -1;
            lastSignalAnchorIndex = anchorIndex;
            cooldownRemaining = cooldownBars;
        }

        void reset() {
            if (state != SignalState.IDLE) {
                state = SignalState.IDLE;
                armedAnchorIndex = -1;
                cooldownRemaining = 0;
            }
        }

        void resetAll() {
            state = SignalState.IDLE;
            armedAnchorIndex = -1;
            lastSignalAnchorIndex = -1;
            cooldownRemaining = 0;
        }
    }

    private record EmaSeries(
            double[] open,
            double[] high,
            double[] low,
            double[] close,
            double[] ema20,
            double[] ema50,
            double[] ema200
    ) {
        private static EmaSeries from(List<BarEvent> history, EmaTrendStructurePullbackParameters params) {
            double[] open = new double[history.size()];
            double[] high = new double[history.size()];
            double[] low = new double[history.size()];
            double[] close = new double[history.size()];
            for (int index = 0; index < history.size(); index++) {
                BarEvent bar = history.get(index);
                open[index] = bar.ohlcv().open().doubleValue();
                high[index] = bar.ohlcv().high().doubleValue();
                low[index] = bar.ohlcv().low().doubleValue();
                close[index] = bar.ohlcv().close().doubleValue();
            }
            return new EmaSeries(
                    open,
                    high,
                    low,
                    close,
                    computeEmaSeries(close, params.fastEmaPeriod()),
                    computeEmaSeries(close, params.mediumEmaPeriod()),
                    computeEmaSeries(close, params.slowEmaPeriod())
            );
        }

        private boolean readyAt(int index, EmaTrendStructurePullbackParameters params) {
            int slopeBaseIndex = index - params.slopeLookbackBars();
            return index + 1 >= params.slowEmaPeriod() + params.slopeLookbackBars()
                    && slopeBaseIndex >= 0
                    && !Double.isNaN(ema20[index])
                    && !Double.isNaN(ema50[index])
                    && !Double.isNaN(ema200[index])
                    && !Double.isNaN(ema20[slopeBaseIndex])
                    && !Double.isNaN(ema50[slopeBaseIndex])
                    && !Double.isNaN(ema200[slopeBaseIndex]);
        }
    }

    private record EmaSnapshot(
            int index,
            double close,
            double previousClose,
            double ema20,
            double ema50,
            double ema200,
            double ema20SlopePct,
            double ema50SlopePct,
            double ema200SlopePct,
            double emaSeparationPct,
            double previousEmaSeparationPct,
            int recentPriceEmaCrossCount,
            double closeLocation,
            double threeBarReturnPct,
            boolean breaksPriorHigh,
            boolean breaksPriorLow,
            EmaStack emaStack,
            SlopeState slopeState,
            CompressionState compressionState,
            TrendStructure trendStructure
    ) {
        private static EmaSnapshot from(EmaSeries series, int index, EmaTrendStructurePullbackParameters params) {
            int slopeBaseIndex = index - params.slopeLookbackBars();
            double close = series.close[index];
            double previousClose = series.close[index - 1];
            double ema20 = series.ema20[index];
            double ema50 = series.ema50[index];
            double ema200 = series.ema200[index];
            double ema20SlopePct = percentageChange(ema20, series.ema20[slopeBaseIndex]);
            double ema50SlopePct = percentageChange(ema50, series.ema50[slopeBaseIndex]);
            double ema200SlopePct = percentageChange(ema200, series.ema200[slopeBaseIndex]);
            double emaSeparationPct = emaSeparationPct(close, ema20, ema50, ema200);
            double previousEmaSeparationPct = emaSeparationPct(series.close[index - 1], series.ema20[index - 1], series.ema50[index - 1], series.ema200[index - 1]);
            int recentCrossCount = countRecentPriceEmaCrosses(series, index, params.chopCrossLookbackBars());
            double closeLocation = closeLocation(series, index);
            double threeBarReturnPct = index >= 3 ? percentageChange(close, series.close[index - 3]) : 0.0d;
            boolean breaksPriorHigh = EmaTrendStructurePullbackStrategy.breaksPriorHigh(series, index, params.priorBreakoutLookbackBars());
            boolean breaksPriorLow = EmaTrendStructurePullbackStrategy.breaksPriorLow(series, index, params.priorBreakoutLookbackBars());
            EmaStack emaStack = classifyEmaStack(close, ema20, ema50, ema200);
            SlopeState slopeState = classifySlopeState(ema20SlopePct, ema50SlopePct, params.flatSlopeThresholdPct().doubleValue());
            CompressionState compressionState = classifyCompressionState(emaSeparationPct, params);
            TrendStructure trendStructure = classifyTrendStructure(close, ema20, ema50, ema200, ema20SlopePct,
                    emaStack, slopeState, compressionState, recentCrossCount, params.chopCrossCountThreshold());
            return new EmaSnapshot(index, close, previousClose, ema20, ema50, ema200, ema20SlopePct, ema50SlopePct,
                    ema200SlopePct, emaSeparationPct, previousEmaSeparationPct, recentCrossCount, closeLocation,
                    threeBarReturnPct, breaksPriorHigh, breaksPriorLow, emaStack, slopeState, compressionState, trendStructure);
        }

        private static double emaSeparationPct(double close, double ema20, double ema50, double ema200) {
            if (Double.isNaN(ema20) || Double.isNaN(ema50) || Double.isNaN(ema200)) {
                return Double.NaN;
            }
            return percentageOfClose(Math.max(ema20, Math.max(ema50, ema200)) - Math.min(ema20, Math.min(ema50, ema200)), close);
        }

        private static double closeLocation(EmaSeries series, int index) {
            double range = series.high[index] - series.low[index];
            if (range <= 0.0d) {
                return 0.50d;
            }
            return (series.close[index] - series.low[index]) / range;
        }

        private static EmaStack classifyEmaStack(double close, double ema20, double ema50, double ema200) {
            if (close >= ema20 && ema20 > ema50 && ema50 > ema200) return EmaStack.BULLISH_STACK;
            if (close <= ema20 && ema20 < ema50 && ema50 < ema200) return EmaStack.BEARISH_STACK;
            if (close > ema20 && ema20 > ema50 && ema50 <= ema200) return EmaStack.BULLISH_TRANSITION;
            if (close < ema20 && ema20 < ema50 && ema50 >= ema200) return EmaStack.BEARISH_TRANSITION;
            return EmaStack.MIXED_STACK;
        }

        private static SlopeState classifySlopeState(double ema20SlopePct, double ema50SlopePct, double threshold) {
            if (ema20SlopePct > threshold && ema50SlopePct > threshold) return SlopeState.RISING;
            if (ema20SlopePct < -threshold && ema50SlopePct < -threshold) return SlopeState.FALLING;
            if (Math.abs(ema20SlopePct) <= threshold && Math.abs(ema50SlopePct) <= threshold) return SlopeState.FLAT;
            return SlopeState.MIXED;
        }

        private static CompressionState classifyCompressionState(double emaSeparationPct, EmaTrendStructurePullbackParameters params) {
            if (emaSeparationPct <= params.compressedSeparationThresholdPct().doubleValue()) return CompressionState.COMPRESSED;
            if (emaSeparationPct >= params.expandingSeparationThresholdPct().doubleValue()) return CompressionState.EXPANDING;
            return CompressionState.NORMAL;
        }

        private static TrendStructure classifyTrendStructure(
                double close,
                double ema20,
                double ema50,
                double ema200,
                double ema20SlopePct,
                EmaStack emaStack,
                SlopeState slopeState,
                CompressionState compressionState,
                int recentCrossCount,
                int chopThreshold
        ) {
            if (emaStack == EmaStack.BULLISH_STACK && slopeState == SlopeState.RISING && compressionState != CompressionState.COMPRESSED) return TrendStructure.CLEAN_UPTREND;
            if (emaStack == EmaStack.BEARISH_STACK && slopeState == SlopeState.FALLING && compressionState != CompressionState.COMPRESSED) return TrendStructure.CLEAN_DOWNTREND;
            if (compressionState == CompressionState.COMPRESSED) return TrendStructure.MA_COMPRESSION;
            if (emaStack == EmaStack.BULLISH_TRANSITION && ema20SlopePct > 0.0d) return TrendStructure.EARLY_UPTREND;
            if (emaStack == EmaStack.BEARISH_TRANSITION && ema20SlopePct < 0.0d) return TrendStructure.EARLY_DOWNTREND;
            if (ema20 > ema50 && ema50 > ema200 && close < ema20 && close >= ema50) return TrendStructure.PULLBACK_IN_UPTREND;
            if (ema20 < ema50 && ema50 < ema200 && close > ema20 && close <= ema50) return TrendStructure.PULLBACK_IN_DOWNTREND;
            if (recentCrossCount >= chopThreshold) return TrendStructure.CHOPPY;
            return TrendStructure.MIXED;
        }
    }

    private record PullbackQuality(boolean realPullback, boolean heldMediumEma, boolean controlledDepth) {
    }

    private record SetupCandidate(
            Direction direction,
            SetupKind kind,
            SetupType setupType,
            int anchorIndex,
            int strengthScore,
            BigDecimal confidence,
            EmaSnapshot snapshot,
            PullbackQuality pullbackQuality
    ) {
    }
}
