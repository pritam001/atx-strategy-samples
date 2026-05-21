package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.LifecycleRole;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.ids.StrategyIntentId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class DoflamingoMomentumV5IntentSupport {
    private DoflamingoMomentumV5IntentSupport() {
    }

    static TradeSignal signal(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            SetupType setupType
    ) {
        BarEvent bar = context.currentBar();
        Direction direction = side == PositionSide.SHORT ? Direction.SHORT : Direction.LONG;
        return new TradeSignal(
                params.strategyVersion(),
                new SignalId("signal-" + params.strategyId() + '-' + side.name().toLowerCase(Locale.ROOT)
                        + '-' + bar.eventId().value().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                new ConfidenceScore(normalized(confidence)),
                setupType,
                new TimeHorizon(params.horizonMode().toLowerCase(Locale.ROOT), horizonDuration(params, bar.timeframe())),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, params.strategyId()),
                null,
                new TagSet(List.of("strategy_family=doflamingo", "formula_version=doflamingo-momentum-v5-beta")),
                bar.cohort(),
                bar.baseline()
        );
    }

    static StrategyTradeIntent entryIntent(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            TradeIntentExitPolicy exitPolicy,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                params,
                context,
                side == PositionSide.SHORT ? StrategyTradeAction.ENTER_SHORT : StrategyTradeAction.ENTER_LONG,
                side,
                LifecycleRole.ENTRY,
                SetupType.CONTINUATION,
                confidence,
                exitPolicy,
                new TradeIntentPreconditions(true, false, PositionSide.ANY, null),
                riskFractionSizing(params.riskFraction()),
                horizon(params),
                reason,
                "entry"
        );
    }

    static StrategyTradeIntent exitIntent(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                params,
                context,
                side == PositionSide.SHORT ? StrategyTradeAction.EXIT_SHORT : StrategyTradeAction.EXIT_LONG,
                side,
                LifecycleRole.EXIT,
                SetupType.CONTINUATION,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, null),
                closeFractionSizing(),
                horizon(params),
                reason,
                "exit"
        );
    }

    static StrategyTradeIntent scaleOutIntent(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                params,
                context,
                side == PositionSide.SHORT ? StrategyTradeAction.SCALE_OUT_SHORT : StrategyTradeAction.SCALE_OUT_LONG,
                side,
                LifecycleRole.SCALE_OUT,
                SetupType.CONTINUATION,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, null),
                scaleFractionSizing(params.scaleOutFraction()),
                horizon(params),
                reason,
                "scale-out"
        );
    }

    static StrategyTradeIntent scaleInIntent(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                params,
                context,
                side == PositionSide.SHORT ? StrategyTradeAction.SCALE_IN_SHORT : StrategyTradeAction.SCALE_IN_LONG,
                side,
                LifecycleRole.SCALE_IN,
                SetupType.CONTINUATION,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, params.maxScaleIns()),
                scaleFractionSizing(params.scaleInFraction()),
                horizon(params),
                reason,
                "scale-in"
        );
    }

    static TradeIntentExitPolicy percentStopWithRrTarget(BigDecimal stopLossPct, BigDecimal targetRMultiple, String description) {
        if (stopLossPct == null || stopLossPct.signum() <= 0) {
            return TradeIntentExitPolicy.none();
        }
        return new TradeIntentExitPolicy(
                new TradeIntentExitRule(StrategyExitRuleType.PERCENT, stopLossPct.setScale(4, RoundingMode.HALF_UP), description),
                new TradeIntentExitRule(StrategyExitRuleType.RR, targetRMultiple.setScale(4, RoundingMode.HALF_UP), "Target expressed as reward-to-risk multiple"),
                null
        );
    }

    static StrategyTradeIntentReason reason(
            String summary,
            List<String> evidence,
            List<String> tags,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        return new StrategyTradeIntentReason(summary, evidence, tags, conditions);
    }

    static StrategyTradeIntentConditionEvidence condition(
            String conditionId,
            String label,
            String leftName,
            double leftValue,
            String operator,
            String rightName,
            double rightValue,
            boolean passed
    ) {
        return condition(conditionId, label, leftName, decimalOrNull(leftValue), operator, rightName, decimalOrNull(rightValue), passed);
    }

    static StrategyTradeIntentConditionEvidence condition(
            String conditionId,
            String label,
            String leftName,
            BigDecimal leftValue,
            String operator,
            String rightName,
            BigDecimal rightValue,
            boolean passed
    ) {
        String message = leftName + " " + conditionValue(leftValue) + " " + operator + " " + rightName + " " + conditionValue(rightValue);
        return new StrategyTradeIntentConditionEvidence(
                conditionId,
                label,
                leftName,
                scaledOrNull(leftValue),
                operator,
                rightName,
                scaledOrNull(rightValue),
                passed,
                message
        );
    }

    static BigDecimal confidence(double score) {
        return BigDecimal.valueOf(Math.max(0.50d, Math.min(0.95d, score))).setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static StrategyTradeIntent intent(
            DoflamingoMomentumV5Parameters params,
            StrategyExecutionContext context,
            StrategyTradeAction action,
            PositionSide side,
            LifecycleRole role,
            SetupType setupType,
            BigDecimal confidence,
            TradeIntentExitPolicy exitPolicy,
            TradeIntentPreconditions preconditions,
            TradeIntentSizing sizing,
            TradeIntentHorizon horizon,
            StrategyTradeIntentReason reason,
            String idPrefix
    ) {
        BarEvent bar = context.currentBar();
        return new StrategyTradeIntent(
                "1.0.0",
                new StrategyIntentId("intent-" + params.strategyId() + '-' + idPrefix + '-' + bar.eventId().value().toLowerCase(Locale.ROOT)),
                params.strategyId(),
                params.strategyVersion(),
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                side,
                new ConfidenceScore(normalized(confidence)),
                setupType,
                role,
                TradeIntentEntry.marketNextOpen(),
                exitPolicy,
                sizing,
                horizon,
                preconditions,
                null,
                reason,
                new SourceRef(SourceType.STRATEGY, params.strategyId()),
                bar.cohort(),
                bar.baseline()
        );
    }

    private static TradeIntentSizing riskFractionSizing(BigDecimal riskFraction) {
        return new TradeIntentSizing(StrategySizingType.RISK_FRACTION, null, null, scaledOrDefault(riskFraction, BigDecimal.valueOf(0.01)), BigDecimal.ONE);
    }

    private static TradeIntentSizing closeFractionSizing() {
        return new TradeIntentSizing(StrategySizingType.CLOSE_FRACTION, null, BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP), null, null);
    }

    private static TradeIntentSizing scaleFractionSizing(BigDecimal fraction) {
        return new TradeIntentSizing(StrategySizingType.SCALE_FRACTION, null, scaledOrDefault(fraction, BigDecimal.valueOf(0.50)), null, null);
    }

    private static TradeIntentHorizon horizon(DoflamingoMomentumV5Parameters params) {
        return new TradeIntentHorizon(params.maxHoldingBars(), null, params.horizonLabel());
    }

    private static Duration horizonDuration(DoflamingoMomentumV5Parameters params, String timeframe) {
        long minutes = switch (timeframe == null ? "" : timeframe.toUpperCase(Locale.ROOT)) {
            case "M5" -> 5L;
            case "M15" -> 15L;
            case "H1" -> 60L;
            case "H4" -> 240L;
            case "D1" -> 1440L;
            default -> 60L;
        };
        return Duration.ofMinutes(minutes * Math.max(1, params.maxHoldingBars()));
    }

    private static BigDecimal normalized(BigDecimal confidence) {
        BigDecimal source = confidence == null ? BigDecimal.valueOf(0.50) : confidence;
        return source.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledOrDefault(BigDecimal value, BigDecimal fallback) {
        return (value == null ? fallback : value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimalOrNull(double value) {
        return Double.isFinite(value) ? decimal(value) : null;
    }

    private static String conditionValue(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
