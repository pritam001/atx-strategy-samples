package org.algotradex.strategy.samples.perplexity;

import org.algotradex.platform.contracts.common.enums.Direction;
import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.contracts.common.enums.LifecycleRole;
import org.algotradex.platform.contracts.common.enums.OrderType;
import org.algotradex.platform.contracts.common.enums.PositionSide;
import org.algotradex.platform.contracts.common.enums.SourceType;
import org.algotradex.platform.contracts.common.enums.StrategyExitRuleType;
import org.algotradex.platform.contracts.common.enums.StrategySizingType;
import org.algotradex.platform.contracts.common.enums.StrategyTradeAction;
import org.algotradex.platform.contracts.common.ids.SignalId;
import org.algotradex.platform.contracts.common.ids.StrategyIntentId;
import org.algotradex.platform.contracts.common.refs.SourceRef;
import org.algotradex.platform.contracts.common.value.ConfidenceScore;
import org.algotradex.platform.contracts.common.value.SuggestedTradeParams;
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
import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ReasoningConditionDescriptor;
import org.algotradex.platform.contracts.simulation.ReasoningModel;
import org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor;
import org.algotradex.platform.contracts.simulation.ThoughtConditionEvidence;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterResumePolicy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIntentResult;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PerplexityStrategySupport {
    static final String PROVIDER_ID = "atx-strategy-samples-perplexity";
    static final String VERSION = "1.0.0";
    static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    static final LocalTime ORB_WINDOW_START = LocalTime.of(9, 30);
    static final LocalTime ORB_WINDOW_END = LocalTime.of(10, 30);
    static final LocalTime BOLLINGER_WINDOW_START = LocalTime.of(9, 45);
    static final LocalTime BOLLINGER_WINDOW_END = LocalTime.of(14, 0);
    static final LocalTime VWAP_WINDOW_START = LocalTime.of(11, 30);
    static final LocalTime VWAP_WINDOW_END = LocalTime.of(14, 0);
    static final List<String> INDIA_ASSET_CLASSES = List.of("EQUITY", "INDEX", "FUTURE", "ETF");
    static final List<StrategyCapability> LIFECYCLE_CAPABILITIES = List.of(
            StrategyCapability.LONG_SIGNALS,
            StrategyCapability.SHORT_SIGNALS,
            StrategyCapability.TRADE_INTENT,
            StrategyCapability.LONG_ENTRY_INTENT,
            StrategyCapability.SHORT_ENTRY_INTENT,
            StrategyCapability.EXIT_INTENT,
            StrategyCapability.RISK_AWARE_SIZING,
            StrategyCapability.PARAMETERIZED
    );

    private PerplexityStrategySupport() {
    }

    static StrategyParameterDefinition integerParam(String key, String label, String description, int defaultValue, int min, int max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.INTEGER, label, description, true, defaultValue, bd(min), bd(max), List.of(), resumePolicyFor(key));
    }

    static StrategyParameterDefinition decimalParam(String key, String label, String description, String defaultValue, String min, String max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.DECIMAL, label, description, true, new BigDecimal(defaultValue), new BigDecimal(min), new BigDecimal(max), List.of(), resumePolicyFor(key));
    }

    static StrategyParameterDefinition boolParam(String key, String label, String description, boolean defaultValue) {
        return new StrategyParameterDefinition(key, StrategyParameterType.BOOLEAN, label, description, true, defaultValue, null, null, List.of(), resumePolicyFor(key));
    }

    static StrategyParameterSchema schema(List<StrategyParameterDefinition> definitions) {
        return new StrategyParameterSchema(definitions);
    }

    static StrategyValidationResult validate(StrategyParameters supplied, List<StrategyParameterDefinition> definitions) {
        Map<String, Object> effective = defaults(definitions);
        effective.putAll(supplied.values());
        StrategyParameters parameters = new StrategyParameters(effective);
        List<StrategyValidationIssue> issues = new ArrayList<>();
        for (StrategyParameterDefinition definition : definitions) {
            validateDefinition(parameters, definition, issues);
        }
        return issues.isEmpty()
                ? StrategyValidationResult.valid(parameters)
                : StrategyValidationResult.invalid(issues);
    }

    static Map<String, Object> defaults(List<StrategyParameterDefinition> definitions) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (StrategyParameterDefinition definition : definitions) {
            values.put(definition.key(), definition.defaultValue());
        }
        return values;
    }

    static StrategyChartStudy study(String indicatorId, String displayName, String role, Map<String, Object> parameters, String notes) {
        return new StrategyChartStudy(indicatorId, displayName, role, parameters, VERSION, true, notes);
    }

    static ReasoningModel reasoningModel(String strategyId, String thesis, List<ReasoningConditionDescriptor> conditions) {
        return new ReasoningModel(
                VERSION + "-" + strategyId + "-reasoning-v1",
                thesis,
                strategyId + " phase={phase}; blocked={blocked_by}.",
                List.of(
                        new ReasoningPhaseDescriptor("warmup", "Warmup", "Seed windows and indicator state."),
                        new ReasoningPhaseDescriptor("scanning", "Scanning", "Watch for setup conditions."),
                        new ReasoningPhaseDescriptor("signal", "Signal", "Entry conditions are present."),
                        new ReasoningPhaseDescriptor("lifecycle", "Lifecycle", "Manage open-position exits and invalidations."),
                        new ReasoningPhaseDescriptor("cooldown", "Cooldown", "Suppress duplicate entries after a lifecycle action.")
                ),
                conditions
        );
    }

    static ReasoningConditionDescriptor descriptor(String id, String label, ConditionRole role, boolean required, String phase, String purpose) {
        return new ReasoningConditionDescriptor(id, label, role, required, phase, purpose,
                label + " passed", label + " blocked the setup");
    }

    static ThoughtConditionEvidence evidence(String id, String label, ConditionRole role, boolean passed, String passedMessage, String failedMessage) {
        return new ThoughtConditionEvidence(id, label, role, passed, passed ? passedMessage : failedMessage);
    }

    static StrategyIntentResult entryResult(
            String strategyId,
            Direction direction,
            SetupType setupType,
            BarEvent bar,
            double entry,
            double stop,
            double target,
            double confidence,
            int maxHoldingBars,
            IntendedHorizonLabel horizonLabel,
            BigDecimal riskFraction,
            List<String> tags,
            StrategyTradeIntentReason reason
    ) {
        PositionSide side = direction == Direction.LONG ? PositionSide.LONG : PositionSide.SHORT;
        StrategyTradeAction action = direction == Direction.LONG ? StrategyTradeAction.ENTER_LONG : StrategyTradeAction.ENTER_SHORT;
        BigDecimal entryPrice = price(entry);
        BigDecimal stopPrice = price(stop);
        BigDecimal targetPrice = price(target);
        BigDecimal stopPct = price((Math.abs(entry - stop) / Math.max(Math.abs(entry), 0.0001d)) * 100.0d);
        BigDecimal targetR = price(Math.abs(target - entry) / Math.max(Math.abs(entry - stop), 0.0001d));
        Duration maxHoldingDuration = horizonDuration(maxHoldingBars, bar.timeframe(), horizonLabel);
        ConfidenceScore score = new ConfidenceScore(confidence(confidence));
        SourceRef sourceRef = new SourceRef(SourceType.STRATEGY, strategyId);
        TradeSignal signal = new TradeSignal(
                VERSION,
                new SignalId("signal-" + strategyId + '-' + bar.eventId().value().toLowerCase(Locale.ROOT) + '-' + direction.name().toLowerCase(Locale.ROOT)),
                bar.instrument(),
                direction,
                score,
                setupType,
                new TimeHorizon(horizonLabel.name().toLowerCase(Locale.ROOT), maxHoldingDuration),
                bar.occurredAt(),
                sourceRef,
                new SuggestedTradeParams(entryPrice, stopPrice, targetPrice, BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP), OrderType.MARKET),
                new TagSet(tags),
                bar.cohort(),
                bar.baseline()
        );
        StrategyTradeIntent intent = new StrategyTradeIntent(
                VERSION,
                new StrategyIntentId("intent-" + strategyId + '-' + bar.eventId().value().toLowerCase(Locale.ROOT) + '-' + action.name().toLowerCase(Locale.ROOT)),
                strategyId,
                VERSION,
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                side,
                score,
                setupType,
                LifecycleRole.ENTRY,
                TradeIntentEntry.marketNextOpen(),
                new TradeIntentExitPolicy(
                        new TradeIntentExitRule(StrategyExitRuleType.PERCENT, stopPct, "Protective stop as percent distance from entry"),
                        new TradeIntentExitRule(StrategyExitRuleType.RR, targetR, "Target expressed as reward-to-risk multiple"),
                        null
                ),
                new TradeIntentSizing(StrategySizingType.RISK_FRACTION, null, null, riskFraction.setScale(4, RoundingMode.HALF_UP), null),
                new TradeIntentHorizon(maxHoldingBars, maxHoldingDuration, horizonLabel),
                new TradeIntentPreconditions(true, false, PositionSide.ANY, null),
                null,
                reason,
                sourceRef,
                bar.cohort(),
                bar.baseline()
        );
        return new StrategyIntentResult(List.of(signal), List.of(intent), List.of());
    }

    static StrategyIntentResult exitResult(
            String strategyId,
            BarEvent bar,
            PositionSide side,
            SetupType setupType,
            double confidence,
            List<String> tags,
            StrategyTradeIntentReason reason
    ) {
        // Lifecycle exits keep the entry setup family so downstream DQS and reporting stay comparable.
        if (side != PositionSide.LONG && side != PositionSide.SHORT) {
            return StrategyIntentResult.empty();
        }
        StrategyTradeAction action = side == PositionSide.LONG ? StrategyTradeAction.EXIT_LONG : StrategyTradeAction.EXIT_SHORT;
        SourceRef sourceRef = new SourceRef(SourceType.STRATEGY, strategyId);
        StrategyTradeIntent intent = new StrategyTradeIntent(
                VERSION,
                new StrategyIntentId("intent-" + strategyId + '-' + bar.eventId().value().toLowerCase(Locale.ROOT) + '-' + action.name().toLowerCase(Locale.ROOT)),
                strategyId,
                VERSION,
                bar.instrument(),
                bar.occurredAt(),
                bar.eventId().value(),
                action,
                side,
                new ConfidenceScore(confidence(confidence)),
                setupType,
                LifecycleRole.EXIT,
                TradeIntentEntry.marketNextOpen(),
                TradeIntentExitPolicy.none(),
                new TradeIntentSizing(StrategySizingType.CLOSE_FRACTION, null, BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP), null, null),
                TradeIntentHorizon.unknown(),
                new TradeIntentPreconditions(false, true, side, null),
                null,
                reason,
                sourceRef,
                bar.cohort(),
                bar.baseline()
        );
        return new StrategyIntentResult(List.of(), List.of(intent), List.of());
    }

    static StrategyTradeIntentReason reason(String summary, List<String> evidence, List<String> tags, List<StrategyTradeIntentConditionEvidence> conditions) {
        return new StrategyTradeIntentReason(summary, evidence, tags, conditions);
    }

    static StrategyTradeIntentConditionEvidence condition(
            String id,
            String label,
            String leftName,
            double leftValue,
            String operator,
            String rightName,
            double rightValue,
            boolean passed
    ) {
        return new StrategyTradeIntentConditionEvidence(
                id,
                label,
                leftName,
                price(leftValue),
                operator,
                rightName,
                price(rightValue),
                passed,
                label + (passed ? " passed" : " failed")
        );
    }

    static BigDecimal price(double value) {
        if (!Double.isFinite(value)) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    private static StrategyParameterResumePolicy resumePolicyFor(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (normalized.equals("cooldownbars")
                || normalized.equals("maxholdingbars")
                || normalized.equals("skiponexpiry")
                || normalized.equals("enforcesessiongate")
                || normalized.equals("allowshorts")
                || normalized.equals("riskfraction")
                || normalized.contains("threshold")
                || normalized.contains("confidence")
                || normalized.contains("multiple")
                || normalized.contains("buffer")
                || normalized.contains("bandpct")
                || normalized.contains("stddev")
                || normalized.contains("oversold")
                || normalized.contains("overbought")) {
            return StrategyParameterResumePolicy.forwardOnly();
        }
        if (normalized.contains("period")) {
            return StrategyParameterResumePolicy.lookback(300);
        }
        if (normalized.contains("lookback") || normalized.contains("rangebars")) {
            return StrategyParameterResumePolicy.lookback(300);
        }
        return StrategyParameterResumePolicy.forwardOnly();
    }

    static boolean isWithinIndiaWindow(BarEvent bar, LocalTime startInclusive, LocalTime endExclusive) {
        LocalTime localTime = LocalTime.ofInstant(bar.occurredAt(), INDIA_ZONE);
        return !localTime.isBefore(startInclusive) && localTime.isBefore(endExclusive);
    }

    static boolean isIndianExpirySession(BarEvent bar) {
        LocalDate sessionDate = LocalDate.ofInstant(bar.occurredAt(), INDIA_ZONE);
        String identity = (bar.instrument().instrumentId() + " " + bar.instrument().symbol()).toUpperCase(Locale.ROOT);
        if (identity.contains("FINNIFTY")) {
            return sessionDate.getDayOfWeek() == DayOfWeek.TUESDAY;
        }
        if (identity.contains("BANKNIFTY")) {
            return sessionDate.getDayOfWeek() == DayOfWeek.WEDNESDAY;
        }
        if (identity.contains("NIFTY50") || identity.contains("NIFTY")) {
            return sessionDate.getDayOfWeek() == DayOfWeek.THURSDAY;
        }
        return sessionDate.getDayOfWeek() == DayOfWeek.THURSDAY && sessionDate.plusWeeks(1).getMonth() != sessionDate.getMonth();
    }

    private static Duration horizonDuration(int maxHoldingBars, String timeframe, IntendedHorizonLabel horizonLabel) {
        int bars = Math.max(1, maxHoldingBars);
        String normalized = timeframe == null ? "" : timeframe.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "M1" -> Duration.ofMinutes(bars);
            case "M5" -> Duration.ofMinutes(bars * 5L);
            case "M15" -> Duration.ofMinutes(bars * 15L);
            case "H1" -> Duration.ofHours(bars);
            case "H4" -> Duration.ofHours(bars * 4L);
            case "D", "D1" -> Duration.ofDays(bars);
            default -> horizonLabel == IntendedHorizonLabel.SWING ? Duration.ofDays(bars) : Duration.ofMinutes(bars * 5L);
        };
    }

    private static BigDecimal confidence(double value) {
        double clamped = Math.max(0.0d, Math.min(1.0d, value));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    private static void validateDefinition(StrategyParameters parameters, StrategyParameterDefinition definition, List<StrategyValidationIssue> issues) {
        try {
            if (definition.type() == StrategyParameterType.INTEGER) {
                BigDecimal value = BigDecimal.valueOf(parameters.integer(definition.key(), ((Number) definition.defaultValue()).intValue()));
                validateRange(definition, value, issues);
            } else if (definition.type() == StrategyParameterType.DECIMAL) {
                validateRange(definition, parameters.decimal(definition.key(), (BigDecimal) definition.defaultValue()), issues);
            } else if (definition.type() == StrategyParameterType.BOOLEAN) {
                parameters.bool(definition.key(), (Boolean) definition.defaultValue());
            }
        } catch (RuntimeException exception) {
            issues.add(new StrategyValidationIssue(definition.key(), "Invalid parameter value: " + exception.getMessage()));
        }
    }

    private static void validateRange(StrategyParameterDefinition definition, BigDecimal value, List<StrategyValidationIssue> issues) {
        if (definition.min() != null && value.compareTo(definition.min()) < 0) {
            issues.add(new StrategyValidationIssue(definition.key(), "Value must be greater than or equal to " + definition.min()));
        }
        if (definition.max() != null && value.compareTo(definition.max()) > 0) {
            issues.add(new StrategyValidationIssue(definition.key(), "Value must be less than or equal to " + definition.max()));
        }
    }

    static final class BarMath {
        private BarMath() {
        }

        static double open(BarEvent bar) {
            return bar.ohlcv().open().doubleValue();
        }

        static double high(BarEvent bar) {
            return bar.ohlcv().high().doubleValue();
        }

        static double low(BarEvent bar) {
            return bar.ohlcv().low().doubleValue();
        }

        static double close(BarEvent bar) {
            return bar.ohlcv().close().doubleValue();
        }

        static double volume(BarEvent bar) {
            return bar.ohlcv().volume().doubleValue();
        }

        static List<BarEvent> sameIndiaSession(List<BarEvent> bars, BarEvent current) {
            LocalDate sessionDate = LocalDate.ofInstant(current.occurredAt(), INDIA_ZONE);
            return bars.stream()
                    .filter(bar -> LocalDate.ofInstant(bar.occurredAt(), INDIA_ZONE).equals(sessionDate))
                    .toList();
        }

        static double highestHigh(List<BarEvent> bars, int fromInclusive, int toExclusive) {
            double value = Double.NEGATIVE_INFINITY;
            for (int index = fromInclusive; index < toExclusive; index++) {
                value = Math.max(value, high(bars.get(index)));
            }
            return value;
        }

        static double lowestLow(List<BarEvent> bars, int fromInclusive, int toExclusive) {
            double value = Double.POSITIVE_INFINITY;
            for (int index = fromInclusive; index < toExclusive; index++) {
                value = Math.min(value, low(bars.get(index)));
            }
            return value;
        }

        static double stddevClose(List<BarEvent> bars, int period, double mean) {
            if (bars.size() < period) {
                return Double.NaN;
            }
            double sum = 0.0d;
            for (int index = bars.size() - period; index < bars.size(); index++) {
                double diff = close(bars.get(index)) - mean;
                sum += diff * diff;
            }
            return Math.sqrt(sum / period);
        }

        static double atr(List<BarEvent> bars, int period) {
            if (bars.size() <= period) {
                return Double.NaN;
            }
            double sum = 0.0d;
            for (int index = bars.size() - period; index < bars.size(); index++) {
                BarEvent bar = bars.get(index);
                double previousClose = close(bars.get(index - 1));
                double trueRange = Math.max(
                        high(bar) - low(bar),
                        Math.max(Math.abs(high(bar) - previousClose), Math.abs(low(bar) - previousClose))
                );
                sum += trueRange;
            }
            return sum / period;
        }

        static double averageVolumeBeforeCurrent(List<BarEvent> bars, int lookback) {
            if (bars.size() <= lookback) {
                return Double.NaN;
            }
            double sum = 0.0d;
            int start = bars.size() - lookback - 1;
            int end = bars.size() - 1;
            for (int index = start; index < end; index++) {
                sum += volume(bars.get(index));
            }
            return sum / lookback;
        }

        static double sessionVwap(List<BarEvent> bars) {
            double pv = 0.0d;
            double volume = 0.0d;
            for (BarEvent bar : bars) {
                double typical = (high(bar) + low(bar) + close(bar)) / 3.0d;
                double barVolume = volume(bar);
                pv += typical * barVolume;
                volume += barVolume;
            }
            return volume == 0.0d ? Double.NaN : pv / volume;
        }
    }
}
