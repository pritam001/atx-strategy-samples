package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader entrypoint for {@code doflamingo-ichimoku-mo-002-beta-v1}.
 * <p>
 * The provider publishes the descriptor, parameter schema, chart-study metadata, and factory for the
 * v1 Ichimoku momentum sample. The descriptor keeps the original sample as a long signal strategy,
 * while the created implementation can also expose structured lifecycle intents to runtimes that
 * understand them.
 * <p>
 * This plugin does not own strategy discovery beyond the ServiceLoader file, market-data
 * provisioning, replay scheduling, execution, broker routing, or portfolio accounting.
 */
public final class DoflamingoIchimokuMo002BetaStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-ichimoku-mo-002-beta-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "doflamingo-strategy-pack";

    static final String CONFIDENCE = "confidence";
    static final String TREND_AVERAGE_LOOKBACK = "trendAverageLookback";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(CONFIDENCE, StrategyParameterType.DECIMAL, "Confidence",
                    "Confidence score attached to emitted Doflamingo Ichimoku beta long setup signals.",
                    true, BigDecimal.valueOf(0.70), BigDecimal.ZERO, BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(TREND_AVERAGE_LOOKBACK, StrategyParameterType.INTEGER, "Trend Average Lookback",
                    "Number of trend score bars used by the Doflamingo 002 beta acceleration filter.",
                    true, 10, BigDecimal.valueOf(2), BigDecimal.valueOf(200), List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Ichimoku Momentum 002 Beta",
            "Doflamingo long-only Ichimoku momentum variant with positive moving-average trend acceleration.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA,
            List.of(
                    DoflamingoChartStudies.ichimoku("primary-signal", true),
                    DoflamingoChartStudies.ema(9, "price-momentum-filter", false),
                    DoflamingoChartStudies.sma(20, "trend-score-component", false),
                    DoflamingoChartStudies.sma(50, "trend-score-component", false),
                    DoflamingoChartStudies.sma(200, "trend-score-component", false),
                    DoflamingoChartStudies.unsupported("trend-score", "Trend Score", "trend-acceleration-filter", Map.of("lookback", 10), false)
            )
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
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                DoflamingoChartStudies.ichimoku("primary-signal", true),
                DoflamingoChartStudies.ema(9, "price-momentum-filter", false),
                DoflamingoChartStudies.sma(20, "trend-score-component", false),
                DoflamingoChartStudies.sma(50, "trend-score-component", false),
                DoflamingoChartStudies.sma(200, "trend-score-component", false),
                DoflamingoChartStudies.unsupported("trend-score", "Trend Score", "trend-acceleration-filter",
                        Map.of("lookback", effectiveParameters.integer(TREND_AVERAGE_LOOKBACK, 10)), false)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo Ichimoku beta parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new DoflamingoIchimokuMo002BetaStrategy(
                effective.decimal(CONFIDENCE, BigDecimal.valueOf(0.70)),
                effective.integer(TREND_AVERAGE_LOOKBACK, 10)
        );
    }
}
