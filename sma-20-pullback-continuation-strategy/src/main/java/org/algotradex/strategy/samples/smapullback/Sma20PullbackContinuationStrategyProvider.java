package org.algotradex.strategy.samples.smapullback;

import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
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
 * ServiceLoader entrypoint for {@code sma-20-pullback-continuation-v1}.
 * <p>
 * The provider exposes descriptor metadata, chart-study metadata, parameter validation, and fresh
 * run-scoped strategy creation for M15/H1 equity and index bars. The descriptor describes the
 * sample's signal surface; the platform still owns replay sequencing, strategy discovery, execution
 * routing, and portfolio state.
 * <p>
 * Validation enforces relationships that the generic schema cannot express, such as fast SMA before
 * slow SMA and slope lookback smaller than the slow period.
 */
public final class Sma20PullbackContinuationStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "sma-20-pullback-continuation-v1";
    public static final String STRATEGY_VERSION = "1.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    static final String FAST_SMA_PERIOD = "fastSmaPeriod";
    static final String SLOW_SMA_PERIOD = "slowSmaPeriod";
    static final String SLOPE_LOOKBACK_BARS = "slopeLookbackBars";
    static final String MIN_SMA20_SLOPE_PCT = "minSma20SlopePct";
    static final String TOUCH_TOLERANCE_PCT = "touchTolerancePct";
    static final String MAX_ENTRY_EXTENSION_PCT = "maxEntryExtensionPct";
    static final String CONSOLIDATION_LOOKBACK_BARS = "consolidationLookbackBars";
    static final String COOLDOWN_BARS = "cooldownBars";
    static final String MIN_CONFIDENCE = "minConfidence";
    static final String ALLOW_SHORTS = "allowShorts";
    static final String USE_SMA200_OBSTACLE_FILTER = "useSma200ObstacleFilter";

    private static final String FORMULA_VERSION = "atx-indicator-formula-v1";

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            new StrategyParameterDefinition(FAST_SMA_PERIOD, StrategyParameterType.INTEGER, "Fast SMA Period",
                    "Closed-bar period count for the active pullback SMA guide.", true, 20, BigDecimal.valueOf(5), BigDecimal.valueOf(100), List.of()),
            new StrategyParameterDefinition(SLOW_SMA_PERIOD, StrategyParameterType.INTEGER, "Slow SMA Period",
                    "Closed-bar period count for the support/resistance context SMA.", true, 200, BigDecimal.valueOf(50), BigDecimal.valueOf(400), List.of()),
            new StrategyParameterDefinition(SLOPE_LOOKBACK_BARS, StrategyParameterType.INTEGER, "Slope Lookback Bars",
                    "Closed-bar lookback used to classify the fast SMA slope.", true, 5, BigDecimal.valueOf(3), BigDecimal.valueOf(20), List.of()),
            new StrategyParameterDefinition(MIN_SMA20_SLOPE_PCT, StrategyParameterType.DECIMAL, "Minimum SMA20 Slope %",
                    "Minimum fast-SMA slope percentage over the lookback window to classify trend.", true, BigDecimal.valueOf(0.05), BigDecimal.ZERO, BigDecimal.valueOf(5.0), List.of()),
            new StrategyParameterDefinition(TOUCH_TOLERANCE_PCT, StrategyParameterType.DECIMAL, "Touch Tolerance %",
                    "Price is considered near the fast SMA when within this percentage.", true, BigDecimal.valueOf(0.20), BigDecimal.ZERO, BigDecimal.valueOf(5.0), List.of()),
            new StrategyParameterDefinition(MAX_ENTRY_EXTENSION_PCT, StrategyParameterType.DECIMAL, "Maximum Entry Extension %",
                    "Skip entries when close is farther than this percentage from the fast SMA.", true, BigDecimal.valueOf(1.25), BigDecimal.valueOf(0.01), BigDecimal.valueOf(20.0), List.of()),
            new StrategyParameterDefinition(CONSOLIDATION_LOOKBACK_BARS, StrategyParameterType.INTEGER, "Consolidation Lookback Bars",
                    "Recent closed bars used to detect pullback/touch and breakout trigger levels.", true, 4, BigDecimal.valueOf(2), BigDecimal.valueOf(12), List.of()),
            new StrategyParameterDefinition(COOLDOWN_BARS, StrategyParameterType.INTEGER, "Cooldown Bars",
                    "Number of bars to suppress after an emitted signal.", true, 8, BigDecimal.ONE, BigDecimal.valueOf(50), List.of()),
            new StrategyParameterDefinition(MIN_CONFIDENCE, StrategyParameterType.DECIMAL, "Minimum Confidence",
                    "Minimum computed setup-quality confidence required to emit a TradeSignal.", true, BigDecimal.valueOf(0.62), BigDecimal.valueOf(0.50), BigDecimal.valueOf(0.90), List.of()),
            new StrategyParameterDefinition(ALLOW_SHORTS, StrategyParameterType.BOOLEAN, "Allow Shorts",
                    "Emit short pullback continuation signals when conditions mirror the long setup.", true, true, null, null, List.of()),
            new StrategyParameterDefinition(USE_SMA200_OBSTACLE_FILTER, StrategyParameterType.BOOLEAN, "Use SMA200 Obstacle Filter",
                    "Downgrade confidence when the slow SMA is a nearby support/resistance obstacle.", true, true, null, null, List.of())
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "SMA 20 Pullback Continuation",
            "External baseline candidate that emits SMA20 pullback continuation setup events with SMA200 context.",
            List.of("M15", "H1"),
            List.of("EQUITY", "INDEX"),
            List.of(StrategyCapability.LONG_SIGNALS, StrategyCapability.SHORT_SIGNALS, StrategyCapability.PARAMETERIZED),
            SCHEMA,
            List.of(
                    smaStudy(20, "pullback-guide", true),
                    smaStudy(200, "support-resistance-context", false)
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
        if (effective.integer(SLOW_SMA_PERIOD, 200) <= effective.integer(FAST_SMA_PERIOD, 20)) {
            issues.add(new StrategyValidationIssue(SLOW_SMA_PERIOD, "Slow SMA period must be greater than fast SMA period"));
        }
        if (effective.integer(SLOPE_LOOKBACK_BARS, 5) >= effective.integer(SLOW_SMA_PERIOD, 200)) {
            issues.add(new StrategyValidationIssue(SLOPE_LOOKBACK_BARS, "Slope lookback must be smaller than slow SMA period"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return List.of(
                smaStudy(effectiveParameters.integer(FAST_SMA_PERIOD, 20), "pullback-guide", true),
                smaStudy(effectiveParameters.integer(SLOW_SMA_PERIOD, 200), "support-resistance-context", false)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid SMA 20 pullback continuation strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        return new Sma20PullbackContinuationStrategy(new Sma20PullbackParameters(
                effective.integer(FAST_SMA_PERIOD, 20),
                effective.integer(SLOW_SMA_PERIOD, 200),
                effective.integer(SLOPE_LOOKBACK_BARS, 5),
                effective.decimal(MIN_SMA20_SLOPE_PCT, BigDecimal.valueOf(0.05)),
                effective.decimal(TOUCH_TOLERANCE_PCT, BigDecimal.valueOf(0.20)),
                effective.decimal(MAX_ENTRY_EXTENSION_PCT, BigDecimal.valueOf(1.25)),
                effective.integer(CONSOLIDATION_LOOKBACK_BARS, 4),
                effective.integer(COOLDOWN_BARS, 8),
                effective.decimal(MIN_CONFIDENCE, BigDecimal.valueOf(0.62)),
                effective.bool(ALLOW_SHORTS, true),
                effective.bool(USE_SMA200_OBSTACLE_FILTER, true)
        ));
    }

    private static StrategyChartStudy smaStudy(int period, String role, boolean required) {
        return new StrategyChartStudy("sma", "SMA", role, Map.of("period", period), FORMULA_VERSION, required, "");
    }
}
