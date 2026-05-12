package org.algotradex.strategy.samples.doflamingo;

import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader entrypoint for {@code doflamingo-multi-indicator-v6-trend-reversal-v1}.
 * <p>
 * The provider exposes metadata and validation for the original long-only Doflamingo V6 sample,
 * including MACD, Stoch RSI, PSAR, Ichimoku, and fixed-stop parameters. It creates a fresh
 * run-scoped strategy instance after validating threshold and MACD period relationships.
 * <p>
 * Descriptor and chart-study metadata are advisory registry/UI contracts. Replay sequencing,
 * accepted-position state, execution routing, fills, and portfolio accounting stay with the
 * platform runtime.
 */
public final class DoflamingoMultiIndicatorV6TrendReversalStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-multi-indicator-v6-trend-reversal-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "doflamingo-strategy-pack";

    static final String CONFIDENCE = "confidence";
    static final String MACD_FAST_PERIOD = "macdFastPeriod";
    static final String MACD_SLOW_PERIOD = "macdSlowPeriod";
    static final String MACD_SIGNAL_PERIOD = "macdSignalPeriod";
    static final String STOCH_OVERBOUGHT = "stochOverbought";
    static final String STOCH_OVERSOLD = "stochOversold";
    static final String STOP_LOSS_PCT = "stopLossPct";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(CONFIDENCE, StrategyParameterType.DECIMAL, "Confidence",
                    "Confidence score attached to emitted Doflamingo v6 long setup signals.",
                    true, BigDecimal.valueOf(0.70), BigDecimal.ZERO, BigDecimal.ONE, List.of()),
            new StrategyParameterDefinition(MACD_FAST_PERIOD, StrategyParameterType.INTEGER, "MACD Fast Period",
                    "Fast EMA period used by the Doflamingo v6 MACD pattern.",
                    true, 12, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(MACD_SLOW_PERIOD, StrategyParameterType.INTEGER, "MACD Slow Period",
                    "Slow EMA period used by the Doflamingo v6 MACD pattern.",
                    true, 26, BigDecimal.valueOf(3), BigDecimal.valueOf(200), List.of()),
            new StrategyParameterDefinition(MACD_SIGNAL_PERIOD, StrategyParameterType.INTEGER, "MACD Signal Period",
                    "Signal EMA period used by the Doflamingo v6 MACD pattern.",
                    true, 9, BigDecimal.valueOf(2), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(STOCH_OVERBOUGHT, StrategyParameterType.DECIMAL, "Stoch Overbought",
                    "Stoch RSI overbought threshold used by the reset rule.",
                    true, BigDecimal.valueOf(80), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(STOCH_OVERSOLD, StrategyParameterType.DECIMAL, "Stoch Oversold",
                    "Stoch RSI oversold threshold used by the entry rule.",
                    true, BigDecimal.valueOf(20), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(STOP_LOSS_PCT, StrategyParameterType.DECIMAL, "Stop Loss Percent",
                    "Run-scoped invalidation threshold below the emitted long setup entry close.",
                    true, BigDecimal.valueOf(2.0), BigDecimal.valueOf(0.1), BigDecimal.valueOf(20), List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Doflamingo Multi Indicator V6 Trend Reversal",
            "Doflamingo long-only PSAR, MACD/Stoch RSI, and Ichimoku trend reversal setup.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA,
            List.of(
                    DoflamingoChartStudies.ichimoku("trend-reversal-context", true),
                    DoflamingoChartStudies.macd(12, 26, 9, "momentum-pattern", true),
                    DoflamingoChartStudies.unsupported("stoch-rsi", "Stoch RSI", "reset-and-entry-filter", Map.of("rsiPeriod", 14, "stochasticPeriod", 14, "kPeriod", 3, "dPeriod", 3), true),
                    DoflamingoChartStudies.unsupported("psar", "PSAR", "trend-reversal-trigger", Map.of(), true)
            )
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
        StrategyParameters effective = result.effectiveParameters();
        List<StrategyValidationIssue> issues = new ArrayList<>();
        if (effective.integer(MACD_SLOW_PERIOD, 26) <= effective.integer(MACD_FAST_PERIOD, 12)) {
            issues.add(new StrategyValidationIssue(MACD_SLOW_PERIOD, "MACD slow period must be greater than fast period"));
        }
        if (effective.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20))
                .compareTo(effective.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80))) >= 0) {
            issues.add(new StrategyValidationIssue(STOCH_OVERSOLD, "Stoch oversold threshold must be below overbought threshold"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                DoflamingoChartStudies.ichimoku("trend-reversal-context", true),
                DoflamingoChartStudies.macd(
                        effectiveParameters.integer(MACD_FAST_PERIOD, 12),
                        effectiveParameters.integer(MACD_SLOW_PERIOD, 26),
                        effectiveParameters.integer(MACD_SIGNAL_PERIOD, 9),
                        "momentum-pattern",
                        true),
                DoflamingoChartStudies.unsupported("stoch-rsi", "Stoch RSI", "reset-and-entry-filter", Map.of(
                        "rsiPeriod", 14,
                        "stochasticPeriod", 14,
                        "kPeriod", 3,
                        "dPeriod", 3,
                        "oversold", effectiveParameters.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20)),
                        "overbought", effectiveParameters.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80))
                ), true),
                DoflamingoChartStudies.unsupported("psar", "PSAR", "trend-reversal-trigger", Map.of(), true)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo v6 trend reversal parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new DoflamingoMultiIndicatorV6TrendReversalStrategy(
                effective.decimal(CONFIDENCE, BigDecimal.valueOf(0.70)),
                effective.integer(MACD_FAST_PERIOD, 12),
                effective.integer(MACD_SLOW_PERIOD, 26),
                effective.integer(MACD_SIGNAL_PERIOD, 9),
                effective.decimal(STOCH_OVERBOUGHT, BigDecimal.valueOf(80)),
                effective.decimal(STOCH_OVERSOLD, BigDecimal.valueOf(20)),
                effective.decimal(STOP_LOSS_PCT, BigDecimal.valueOf(2.0))
        );
    }
}
