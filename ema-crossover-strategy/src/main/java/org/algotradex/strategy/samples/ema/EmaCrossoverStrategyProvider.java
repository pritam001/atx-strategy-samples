package org.algotradex.strategy.samples.ema;

import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ReasoningConditionDescriptor;
import org.algotradex.platform.contracts.simulation.ReasoningModel;
import org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.*;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.List;

/**
 * ServiceLoader entrypoint for {@code ema-crossover-v1}.
 * <p>
 * The provider exposes descriptor metadata, parameter validation, and fresh run-scoped strategy
 * construction for a simple EMA crossover sample on M15, H1, and D1 equity/index bars. The
 * descriptor is plugin metadata consumed by registry and UI surfaces; the created strategy remains
 * the runtime signal producer.
 * <p>
 * This boundary does not own market-data loading, replay sequencing, execution, broker routing, or
 * portfolio accounting. Invalid effective parameters are rejected before an instance is created.
 */
public final class EmaCrossoverStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "ema-crossover-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    private static final String FAST_PERIOD = "fastEmaPeriod";
    private static final String SLOW_PERIOD = "slowEmaPeriod";
    private static final String MIN_CONFIDENCE = "minConfidence";
    private static final String ALLOW_SHORTS = "allowShorts";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            withResumePolicy(new StrategyParameterDefinition(
                    FAST_PERIOD,
                    StrategyParameterType.INTEGER,
                    "Fast EMA Period",
                    "Closed-bar period count for the faster EMA.",
                    true,
                    9,
                    BigDecimal.valueOf(2),
                    BigDecimal.valueOf(100),
                    List.of()
            )),
            withResumePolicy(new StrategyParameterDefinition(
                    SLOW_PERIOD,
                    StrategyParameterType.INTEGER,
                    "Slow EMA Period",
                    "Closed-bar period count for the slower EMA.",
                    true,
                    21,
                    BigDecimal.valueOf(3),
                    BigDecimal.valueOf(250),
                    List.of()
            )),
            withResumePolicy(new StrategyParameterDefinition(
                    MIN_CONFIDENCE,
                    StrategyParameterType.DECIMAL,
                    "Minimum Confidence",
                    "Confidence score attached to emitted crossover signals.",
                    true,
                    BigDecimal.valueOf(0.68),
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    List.of()
            )),
            withResumePolicy(new StrategyParameterDefinition(
                    ALLOW_SHORTS,
                    StrategyParameterType.BOOLEAN,
                    "Allow Shorts",
                    "Emit short signals when the fast EMA crosses below the slow EMA.",
                    true,
                    false,
                    null,
                    null,
                    List.of()
            ))
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "EMA Crossover",
            "Sample external strategy that emits closed-bar EMA crossover signals.",
            List.of("M15", "H1", "D1"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.SHORT_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA,
            reasoningModel()
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        StrategyValidationResult result = StrategyParameterValidator.validate(SCHEMA, parameters);
        if (!result.valid()) {
            return result;
        }

        int fastPeriod = result.effectiveParameters().integer(FAST_PERIOD, 9);
        int slowPeriod = result.effectiveParameters().integer(SLOW_PERIOD, 21);
        if (fastPeriod >= slowPeriod) {
            return StrategyValidationResult.invalid(List.of(
                    new StrategyValidationIssue(SLOW_PERIOD, "Slow EMA period must be greater than fast EMA period")
            ));
        }
        return result;
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid EMA crossover strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new EmaCrossoverStrategy(
                effective.integer(FAST_PERIOD, 9),
                effective.integer(SLOW_PERIOD, 21),
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.68)),
                effective.bool(ALLOW_SHORTS, false)
        );
    }

    private static StrategyParameterDefinition withResumePolicy(StrategyParameterDefinition definition) {
        StrategyParameterResumePolicy policy = switch (definition.key()) {
            case FAST_PERIOD -> StrategyParameterResumePolicy.lookback(100);
            case SLOW_PERIOD -> StrategyParameterResumePolicy.lookback(250);
            case MIN_CONFIDENCE, ALLOW_SHORTS -> StrategyParameterResumePolicy.forwardOnly();
            default -> StrategyParameterResumePolicy.lookback(null);
        };
        return new StrategyParameterDefinition(
                definition.key(),
                definition.type(),
                definition.label(),
                definition.description(),
                definition.required(),
                definition.defaultValue(),
                definition.min(),
                definition.max(),
                definition.allowedValues(),
                policy
        );
    }

    private static ReasoningModel reasoningModel() {
        return new ReasoningModel(
                STRATEGY_VERSION + "-reasoning-v1",
                "EMA crossover waits for seeded fast and slow EMA series, then fires when the fast EMA crosses the slow EMA.",
                "EMA crossover phase={phase}; blocked={blocked_by}.",
                List.of(
                        new ReasoningPhaseDescriptor("warmup", "Warmup", "Seed the fast and slow EMA series."),
                        new ReasoningPhaseDescriptor("scanning", "Scanning", "Track the fast/slow relationship without a cross."),
                        new ReasoningPhaseDescriptor("signal", "Signal", "A fast/slow EMA cross fired on the current closed bar.")
                ),
                List.of(
                        new ReasoningConditionDescriptor("ema-crossover.warmup", "EMA crossover warmup complete", ConditionRole.ENTRY_FILTER, true, "warmup", "Ensure indicator state is seeded before any crossover decision.", "Both EMAs are seeded", "Waiting for enough bars to seed both EMAs"),
                        new ReasoningConditionDescriptor("ema-crossover.long-cross", "Fast EMA crossed above slow EMA", ConditionRole.ENTRY_TRIGGER, false, "signal", "Explain long crossover trigger state.", "Fast EMA crossed above slow EMA", "Fast EMA has not crossed above slow EMA"),
                        new ReasoningConditionDescriptor("ema-crossover.short-cross", "Fast EMA crossed below slow EMA", ConditionRole.ENTRY_TRIGGER, false, "signal", "Explain short crossover trigger state.", "Fast EMA crossed below slow EMA", "Fast EMA has not crossed below slow EMA")
                )
        );
    }
}
