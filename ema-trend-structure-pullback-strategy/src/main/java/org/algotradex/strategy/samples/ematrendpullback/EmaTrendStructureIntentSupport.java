package org.algotradex.strategy.samples.ematrendpullback;

import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.enums.LifecycleRole;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.StrategyIntentId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
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
import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

final class EmaTrendStructureIntentSupport {
    private EmaTrendStructureIntentSupport() {
    }

    static StrategyTradeIntent entryIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal riskFraction,
            TradeIntentExitPolicy exitPolicy,
            int maxHoldingBars,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                strategyId,
                strategyVersion,
                context,
                side == PositionSide.LONG ? StrategyTradeAction.ENTER_LONG : StrategyTradeAction.ENTER_SHORT,
                side,
                LifecycleRole.ENTRY,
                setupType,
                confidence,
                exitPolicy,
                new TradeIntentPreconditions(true, false, PositionSide.ANY, null),
                riskFractionSizing(riskFraction),
                horizon(maxHoldingBars),
                reason,
                "entry"
        );
    }

    static StrategyTradeIntent exitIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            SetupType setupType,
            int maxHoldingBars,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                strategyId,
                strategyVersion,
                context,
                side == PositionSide.LONG ? StrategyTradeAction.EXIT_LONG : StrategyTradeAction.EXIT_SHORT,
                side,
                LifecycleRole.EXIT,
                setupType,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, null),
                closeFractionSizing(),
                horizon(maxHoldingBars),
                reason,
                "exit"
        );
    }

    static StrategyTradeIntent scaleOutIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal scaleOutFraction,
            int maxHoldingBars,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                strategyId,
                strategyVersion,
                context,
                side == PositionSide.LONG ? StrategyTradeAction.SCALE_OUT_LONG : StrategyTradeAction.SCALE_OUT_SHORT,
                side,
                LifecycleRole.SCALE_OUT,
                setupType,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, null),
                scaleFractionSizing(scaleOutFraction),
                horizon(maxHoldingBars),
                reason,
                "scale-out"
        );
    }

    static StrategyTradeIntent scaleInIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            PositionSide side,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal scaleInFraction,
            int maxScaleIns,
            int maxHoldingBars,
            StrategyTradeIntentReason reason
    ) {
        return intent(
                strategyId,
                strategyVersion,
                context,
                side == PositionSide.LONG ? StrategyTradeAction.SCALE_IN_LONG : StrategyTradeAction.SCALE_IN_SHORT,
                side,
                LifecycleRole.SCALE_IN,
                setupType,
                confidence,
                TradeIntentExitPolicy.none(),
                new TradeIntentPreconditions(false, true, side, maxScaleIns),
                scaleFractionSizing(scaleInFraction),
                horizon(maxHoldingBars),
                reason,
                "scale-in"
        );
    }

    static TradeIntentExitPolicy percentStop(BigDecimal stopLossPct, String description) {
        if (stopLossPct == null || stopLossPct.signum() <= 0) {
            return TradeIntentExitPolicy.none();
        }
        return new TradeIntentExitPolicy(
                new TradeIntentExitRule(StrategyExitRuleType.PERCENT, stopLossPct.setScale(4, RoundingMode.HALF_UP), description),
                TradeIntentExitRule.none(),
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
            int leftValue,
            String operator,
            String rightName,
            int rightValue,
            boolean passed
    ) {
        return condition(conditionId, label, leftName, BigDecimal.valueOf(leftValue), operator, rightName, BigDecimal.valueOf(rightValue), passed);
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

    static BigDecimal confidence(double value) {
        return BigDecimal.valueOf(Math.max(0.50d, Math.min(0.95d, value))).setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static StrategyTradeIntent intent(
            String strategyId,
            String strategyVersion,
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
                new StrategyIntentId("intent-" + strategyId + '-' + idPrefix + '-' + bar.eventId().value().toLowerCase(Locale.ROOT)),
                strategyId,
                strategyVersion,
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                side,
                new ConfidenceScore(normalizedConfidence(confidence)),
                setupType,
                role,
                TradeIntentEntry.marketNextOpen(),
                exitPolicy,
                sizing,
                horizon,
                preconditions,
                null,
                reason,
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
        return new TradeIntentSizing(StrategySizingType.CLOSE_FRACTION, null, BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP), null, null);
    }

    private static TradeIntentSizing scaleFractionSizing(BigDecimal scaleFraction) {
        return new TradeIntentSizing(
                StrategySizingType.SCALE_FRACTION,
                null,
                scaledOrDefault(scaleFraction, BigDecimal.valueOf(0.50)),
                null,
                null
        );
    }

    private static TradeIntentHorizon horizon(int maxHoldingBars) {
        return new TradeIntentHorizon(maxHoldingBars, null, IntendedHorizonLabel.INTRADAY);
    }

    private static BigDecimal normalizedConfidence(BigDecimal confidence) {
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
        return value == null ? "n/a" : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
