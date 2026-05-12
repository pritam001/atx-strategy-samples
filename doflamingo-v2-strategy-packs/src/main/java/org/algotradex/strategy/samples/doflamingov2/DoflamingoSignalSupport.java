package org.algotradex.strategy.samples.doflamingov2;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
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

final class DoflamingoSignalSupport {
    private static final Duration DEFAULT_HORIZON = Duration.ofHours(4);

    private DoflamingoSignalSupport() {
    }

    static TradeSignal longSignal(String strategyId, String strategyVersion, StrategyExecutionContext context,
                                  BigDecimal confidence, SetupType setupType) {
        BarEvent bar = context.currentBar();
        return new TradeSignal(
                strategyVersion,
                new SignalId("signal-" + strategyId + "-long-" + bar.eventId().value().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                Direction.LONG,
                new ConfidenceScore(normalizedConfidence(confidence)),
                setupType,
                new TimeHorizon("intraday", DEFAULT_HORIZON),
                bar.occurredAt(),
                new SourceRef(SourceType.STRATEGY, strategyId),
                null,
                null,
                bar.cohort(),
                bar.baseline()
        );
    }

    static StrategyTradeIntent longEntryIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal riskFraction,
            TradeIntentExitPolicy exitPolicy,
            int maxHoldingBars,
            String reason,
            List<String> evidence,
            List<String> tags,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        BarEvent bar = context.currentBar();
        return intent(
                strategyId,
                strategyVersion,
                context,
                StrategyTradeAction.ENTER_LONG,
                LifecycleRole.ENTRY,
                setupType,
                confidence,
                exitPolicy,
                new TradeIntentPreconditions(true, false, PositionSide.ANY, null),
                riskFractionSizing(riskFraction),
                new TradeIntentHorizon(maxHoldingBars, null, IntendedHorizonLabel.INTRADAY),
                reason,
                evidence,
                tags,
                "entry-" + bar.eventId().value().toLowerCase(Locale.ROOT),
                conditions
        );
    }

    static StrategyTradeIntent longExitIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            String reason
    ) {
        return longExitIntent(strategyId, strategyVersion, context, confidence, setupType, reason, List.of(), List.of(), List.of());
    }

    static StrategyTradeIntent longExitIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            String reason,
            List<String> evidence,
            List<String> tags,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        BarEvent bar = context.currentBar();
        return intent(
                strategyId,
                strategyVersion,
                context,
                StrategyTradeAction.EXIT_LONG,
                LifecycleRole.EXIT,
                setupType,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, PositionSide.LONG, null),
                closeFractionSizing(),
                TradeIntentHorizon.unknown(),
                reason,
                evidence,
                tags,
                "exit-" + bar.eventId().value().toLowerCase(Locale.ROOT),
                conditions
        );
    }

    static StrategyTradeIntent scaleOutIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal scaleOutFraction,
            String reason,
            List<String> evidence,
            List<String> tags,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        BarEvent bar = context.currentBar();
        return intent(
                strategyId,
                strategyVersion,
                context,
                StrategyTradeAction.SCALE_OUT_LONG,
                LifecycleRole.SCALE_OUT,
                setupType,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, PositionSide.LONG, null),
                scaleFractionSizing(scaleOutFraction),
                TradeIntentHorizon.unknown(),
                reason,
                evidence,
                tags,
                "scale-out-" + bar.eventId().value().toLowerCase(Locale.ROOT),
                conditions
        );
    }

    static TradeIntentExitPolicy percentStop(BigDecimal stopLossPct, String description) {
        if (stopLossPct == null || stopLossPct.signum() <= 0) {
            return TradeIntentExitPolicy.none();
        }
        return new TradeIntentExitPolicy(
                new TradeIntentExitRule(StrategyExitRuleType.PERCENT, stopLossPct, description),
                TradeIntentExitRule.none(),
                null
        );
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
        BigDecimal left = decimalOrNull(leftValue);
        BigDecimal right = decimalOrNull(rightValue);
        String message = leftName + " " + conditionValue(left) + " " + operator + " " + rightName + " " + conditionValue(right);
        return new StrategyTradeIntentConditionEvidence(
                conditionId,
                label,
                leftName,
                left,
                operator,
                rightName,
                right,
                passed,
                message
        );
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
                leftValue == null ? null : leftValue.setScale(4, RoundingMode.HALF_UP),
                operator,
                rightName,
                rightValue == null ? null : rightValue.setScale(4, RoundingMode.HALF_UP),
                passed,
                message
        );
    }

    private static StrategyTradeIntent intent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            StrategyTradeAction action,
            LifecycleRole role,
            SetupType setupType,
            BigDecimal confidence,
            TradeIntentExitPolicy exitPolicy,
            TradeIntentPreconditions preconditions,
            TradeIntentSizing sizing,
            TradeIntentHorizon horizon,
            String reason,
            List<String> evidence,
            List<String> tags,
            String idSuffix,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        BarEvent bar = context.currentBar();
        return new StrategyTradeIntent(
                "1.0.0",
                new StrategyIntentId("intent-" + strategyId + "-" + idSuffix),
                strategyId,
                strategyVersion,
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                PositionSide.LONG,
                new ConfidenceScore(normalizedConfidence(confidence)),
                setupType,
                role,
                TradeIntentEntry.marketNextOpen(),
                exitPolicy,
                sizing,
                horizon,
                preconditions,
                null,
                new StrategyTradeIntentReason(
                        reason == null || reason.isBlank() ? "Doflamingo v2 lifecycle rule" : reason,
                        evidence,
                        tags,
                        conditions),
                new SourceRef(SourceType.STRATEGY, strategyId),
                bar.cohort(),
                bar.baseline()
        );
    }

    private static TradeIntentSizing riskFractionSizing(BigDecimal riskFraction) {
        return new TradeIntentSizing(
                StrategySizingType.RISK_FRACTION,
                null,
                null,
                scaledOrDefault(riskFraction, BigDecimal.valueOf(0.01)),
                BigDecimal.ONE
        );
    }

    private static TradeIntentSizing closeFractionSizing() {
        return new TradeIntentSizing(StrategySizingType.CLOSE_FRACTION, null, BigDecimal.ONE, null, null);
    }

    private static TradeIntentSizing scaleFractionSizing(BigDecimal scaleOutFraction) {
        return new TradeIntentSizing(
                StrategySizingType.SCALE_FRACTION,
                null,
                scaledOrDefault(scaleOutFraction, BigDecimal.valueOf(0.50)),
                null,
                null
        );
    }

    private static BigDecimal normalizedConfidence(BigDecimal confidence) {
        BigDecimal source = confidence == null ? BigDecimal.valueOf(0.50) : confidence;
        BigDecimal bounded = source.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return bounded.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledOrDefault(BigDecimal value, BigDecimal fallback) {
        return (value == null ? fallback : value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimalOrNull(double value) {
        return Double.isFinite(value) ? decimal(value) : null;
    }

    private static String conditionValue(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
