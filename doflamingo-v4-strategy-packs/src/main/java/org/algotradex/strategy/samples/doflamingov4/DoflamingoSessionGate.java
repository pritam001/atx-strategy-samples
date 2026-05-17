package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class DoflamingoSessionGate {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_OPEN = LocalTime.of(9, 15);
    private static final LocalTime LAST_ENTRY = LocalTime.of(15, 0);

    private DoflamingoSessionGate() {
    }

    static boolean entryAllowed(StrategyExecutionContext context, boolean enabled) {
        if (!enabled) {
            return true;
        }
        ZonedDateTime ist = context.currentBar().occurredAt().atZone(IST);
        DayOfWeek day = ist.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = ist.toLocalTime();
        return !time.isBefore(NSE_OPEN) && !time.isAfter(LAST_ENTRY);
    }

    static StrategyTradeIntentConditionEvidence allowedCondition(
            String conditionId,
            StrategyExecutionContext context,
            boolean enabled
    ) {
        return DoflamingoSignalSupport.condition(
                conditionId,
                "Entry is inside the deterministic regular-session gate",
                "Session allowed",
                entryAllowed(context, enabled) ? 1.0d : 0.0d,
                "=",
                "Required",
                1.0d,
                entryAllowed(context, enabled)
        );
    }

    static String sessionEvidence(StrategyExecutionContext context, boolean enabled) {
        ZonedDateTime ist = context.currentBar().occurredAt().atZone(IST);
        return "sessionGate=" + (enabled ? "NSE_REGULAR_APPROX" : "OFF") + ",barTimeIst=" + ist.toLocalDateTime();
    }
}
