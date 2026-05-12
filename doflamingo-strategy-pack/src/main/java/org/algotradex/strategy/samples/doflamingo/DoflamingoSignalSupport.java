package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.LifecycleRole;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
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

/**
 * Internal factory for Doflamingo signal and lifecycle-intent contract records.
 * <p>
 * The helper keeps signal IDs, intent IDs, sizing metadata, exit policies, horizons, and condition
 * evidence consistent across the sample strategies. It is not a public SPI and does not decide
 * whether a setup is valid.
 * <p>
 * Built records are advisory strategy output. The helper does not execute trades, mutate positions,
 * route broker orders, or reserve portfolio capital.
 */
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
            BigDecimal stopLossPct,
            String reason
    ) {
        return longEntryIntent(strategyId, strategyVersion, context, confidence, setupType, stopLossPct, reason, List.of());
    }

    static StrategyTradeIntent longEntryIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            BigDecimal stopLossPct,
            String reason,
            List<StrategyTradeIntentConditionEvidence> conditions
    ) {
        BarEvent bar = context.currentBar();
        TradeIntentExitPolicy exitPolicy = stopLossPct == null
                ? TradeIntentExitPolicy.none()
                : new TradeIntentExitPolicy(
                new TradeIntentExitRule(StrategyExitRuleType.PERCENT, stopLossPct, "Doflamingo fixed stop loss"),
                TradeIntentExitRule.none(),
                null
        );
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
                reason,
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
        return longExitIntent(strategyId, strategyVersion, context, confidence, setupType, reason, List.of());
    }

    static StrategyTradeIntent longExitIntent(
            String strategyId,
            String strategyVersion,
            StrategyExecutionContext context,
            BigDecimal confidence,
            SetupType setupType,
            String reason,
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
                reason,
                "exit-" + bar.eventId().value().toLowerCase(Locale.ROOT),
                conditions
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
            String reason,
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
                null,
                exitPolicy,
                TradeIntentSizing.normalizedUnit(),
                TradeIntentHorizon.unknown(),
                preconditions,
                null,
                new StrategyTradeIntentReason(reason == null || reason.isBlank() ? "Doflamingo lifecycle rule" : reason, List.of(), List.of("doflamingo"), conditions),
                new SourceRef(SourceType.STRATEGY, strategyId),
                bar.cohort(),
                bar.baseline()
        );
    }

    private static BigDecimal normalizedConfidence(BigDecimal confidence) {
        BigDecimal bounded = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return bounded.setScale(4, RoundingMode.HALF_UP);
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
