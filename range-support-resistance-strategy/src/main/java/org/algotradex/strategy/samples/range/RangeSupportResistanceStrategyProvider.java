package org.algotradex.strategy.samples.range;

import org.algotradex.platform.core.api.dto.common.strategy.*;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.List;

/**
 * ServiceLoader entrypoint for {@code range-support-resistance-v1}.
 * <p>
 * The provider publishes the sample descriptor, validates bounded lookback/tolerance/risk-reward
 * parameters, and creates fresh run-scoped signal strategies for M15, H1, and D1 equity/index bars.
 * The descriptor advertises the signal contract; validation remains the authority for effective
 * runtime parameters.
 * <p>
 * This plugin boundary does not load market data, sequence replay bars, route orders, or account
 * for portfolio state.
 */
public final class RangeSupportResistanceStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "range-support-resistance-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    private static final String LOOKBACK = "lookback";
    private static final String TOLERANCE = "tolerance";
    private static final String RISK_REWARD = "riskReward";
    private static final String CONFIDENCE = "confidence";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(
                    LOOKBACK,
                    StrategyParameterType.INTEGER,
                    "Lookback",
                    "Closed-bar count used to derive the range support and resistance.",
                    true,
                    30,
                    BigDecimal.valueOf(2),
                    BigDecimal.valueOf(500),
                    List.of()
            ),
            new StrategyParameterDefinition(
                    TOLERANCE,
                    StrategyParameterType.DECIMAL,
                    "Tolerance",
                    "Maximum fractional distance from support or resistance to qualify as near the level.",
                    true,
                    BigDecimal.valueOf(0.002),
                    BigDecimal.valueOf(0.0001),
                    BigDecimal.valueOf(0.05),
                    List.of()
            ),
            new StrategyParameterDefinition(
                    RISK_REWARD,
                    StrategyParameterType.DECIMAL,
                    "Risk Reward",
                    "Reward multiple used to project the suggested target from the entry and stop.",
                    true,
                    BigDecimal.valueOf(2.0),
                    BigDecimal.valueOf(0.1),
                    BigDecimal.valueOf(20.0),
                    List.of()
            ),
            new StrategyParameterDefinition(
                    CONFIDENCE,
                    StrategyParameterType.DECIMAL,
                    "Confidence",
                    "Confidence score attached to emitted range reversal signals.",
                    true,
                    BigDecimal.valueOf(0.70),
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    List.of()
            )
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Range Support/Resistance",
            "Sample external strategy that emits closed-bar range support/resistance confirmation signals.",
            List.of("M15", "H1", "D1"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.SHORT_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        return StrategyParameterValidator.validate(SCHEMA, parameters);
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid range support/resistance strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new RangeSupportResistanceStrategy(
                effective.integer(LOOKBACK, 30),
                effective.decimal(TOLERANCE, BigDecimal.valueOf(0.002)),
                effective.decimal(RISK_REWARD, BigDecimal.valueOf(2.0)),
                effective.decimal(CONFIDENCE, BigDecimal.valueOf(0.70))
        );
    }
}
