package org.algotradex.strategy.samples.trendpullbackv3;

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
import org.algotradex.platform.core.api.service.strategy.TradeIntentStrategy;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceLoader entrypoint for {@code trend-pullback-v3}.
 *
 * <p>This is the evolution of the original {@code range-sr-v2} sample. The strategy ID, version,
 * and module are new; the parent module {@code range-sr-v2-strategy} is preserved unchanged.
 *
 * <p>The descriptor still advertises a lower-timeframe pullback strategy that requires H4 context
 * history. v3 changes the defaults so the strategy emits more trades while keeping the structural-
 * pullback discipline. See {@code atx-strategy-samples/docs/range-sr-v2-improvement-plan.md} for
 * the full per-PR rationale.
 *
 * <p>Provider validation bounds effective parameters, checks lookback relationships, and creates a
 * fresh run-scoped {@link TradeIntentStrategy} implementation. This provider is the plugin
 * metadata/factory boundary only.
 */
public final class TrendPullbackV3StrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "trend-pullback-v3";
    public static final String STRATEGY_VERSION = "3.0.0";
    public static final String PROVIDER_ID = "atx-strategy-samples";

    static final String MIN_TREND_ADX = "minTrendAdx";
    static final String PATTERN_TIER = "patternTier";
    static final String MIN_CONFLUENCE = "minConfluence";
    static final String ATR_MULT_SL = "atrMultSL";
    static final String ATR_MULT_MIN_RR = "atrMultMinRR";
    static final String RISK_USD_PER_TRADE = "riskUsdPerTrade";
    static final String PIVOT_SOURCE = "pivotSource";
    static final String HTF_LOOKBACK = "htfLookback";
    static final String LTF_LOOKBACK = "ltfLookback";
    static final String PIVOT_LOOKBACK = "pivotLookback";
    static final String COOLDOWN_HOURS = "cooldownHours";
    static final String LEVEL_TOLERANCE_PCT = "levelTolerancePct";
    static final String MIDLINE_TOLERANCE_PCT = "midlineTolerancePct";
    static final String VOLATILITY_ADAPTIVE_TOLERANCE = "volatilityAdaptiveTolerance";
    static final String ADAPTIVE_TOLERANCE_MIN = "adaptiveToleranceMin";
    static final String ADAPTIVE_TOLERANCE_MAX = "adaptiveToleranceMax";
    static final String ALLOW_MIDLINE_WITH_MAX_CONFLUENCE = "allowMidlineWithMaxConfluence";
    static final String ALLOW_SECOND_TOUCH_IN_COOLDOWN = "allowSecondTouchInCooldown";
    static final String TIER2_WITH_MAX_CONFLUENCE_COUNTS_AS_TIER1 = "tier2WithMaxConfluenceCountsAsTier1";
    static final String EMIT_DIAGNOSTICS = "emitDiagnostics";

    private static final String INDICATOR_FORMULA_VERSION = "atx-indicator-formula-v1";

    // PR-S1: relax pattern tier default — TIER1_OR_TIER2 accepts piercing/dark-cloud (0.8) and
    //        hammer/shooting-star (0.7) in addition to the original tier-1 patterns.
    // PR-S3: relax ADX floor 20 → 15 — opens trading on ADX-15-to-20 instruments.
    // PR-S2: enable volatility-adaptive level tolerance by default; tolerance bounds 0.001..0.005.
    // PR-S5: HYBRID pivot source by default — H4 for trend + zone, LTF for level + confluence.
    // PR-S4: tier-2 patterns at full 4-of-4 confluence count as tier-1 quality.
    // PR-S8: allow second-touch within cooldown if confluence is strictly higher.
    // PR-S9: allow MIDLINE-zone entries when confluence == 4 (compensates for relaxed zone gate).
    // PR-S6: emit per-rejection diagnostics by default so failed-run analysis surfaces in the UI.
    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(List.of(
            decimal(MIN_TREND_ADX, "Minimum H4 ADX",
                    "Minimum ADX(14) required on H4 before trading. v3 lowers from 20 to 15 to open ADX-15-to-20 instruments.",
                    "15", "1", "80"),
            enumeration(PATTERN_TIER, "Pattern Tier",
                    "Reversal-pattern strictness. TIER1_STRICT = v2 behavior (engulfing/morning-star/three-soldiers only). TIER1_OR_TIER2 = v3 default (also accepts piercing, hammer, dark-cloud, shooting-star). ALL_PATTERNS = also doji.",
                    "TIER1_OR_TIER2", List.of("TIER1_STRICT", "TIER1_OR_TIER2", "ALL_PATTERNS")),
            integer(MIN_CONFLUENCE, "Minimum Confluence",
                    "Minimum defended-level confluence factors required (pivot, round number, fib, swing extreme).", 2, 1, 4),
            decimal(ATR_MULT_SL, "ATR Stop Multiple",
                    "ATR(14) multiple placed beyond the defended structure level.", "1.5", "0.1", "10.0"),
            decimal(ATR_MULT_MIN_RR, "Minimum RR Multiple",
                    "Minimum real-structure target distance in R. Refuses trades without a clean structural target.", "2.0", "0.5", "10.0"),
            decimal(RISK_USD_PER_TRADE, "Risk USD Per Trade",
                    "Dollar risk used to convert stop distance into requested units.", "1.0", "0.01", "100000.0"),
            enumeration(PIVOT_SOURCE, "Pivot Source",
                    "Source of pivots. HTF = v2 behavior (H4 only). LTF = execution-timeframe only. HYBRID = v3 default (H4 for trend + zone, LTF for level + confluence).",
                    "HYBRID", List.of("HTF", "LTF", "HYBRID")),
            integer(HTF_LOOKBACK, "H4 Lookback", "Maximum H4 candles used for trend and HTF structure.", 200, 50, 1000),
            integer(LTF_LOOKBACK, "Execution Lookback",
                    "Maximum execution-timeframe candles used for pattern, execution checks, and (in HYBRID/LTF) pivots.", 200, 20, 2000),
            integer(PIVOT_LOOKBACK, "Pivot Lookback", "Confirmed fractal-pivot wing size.", 3, 1, 10),
            integer(COOLDOWN_HOURS, "Cooldown Hours",
                    "Per-instrument setup cooldown after a signal. v3 still uses hours, not bars.", 4, 0, 72),
            decimal(LEVEL_TOLERANCE_PCT, "Level Tolerance %",
                    "Fractional proximity tolerance for levels/round numbers/fibs. When volatilityAdaptiveTolerance=true, this becomes the BASE that ATR/price scales.",
                    "0.002", "0.0001", "0.05"),
            decimal(MIDLINE_TOLERANCE_PCT, "Midline Tolerance %",
                    "Neutral band around the active range midline.", "0.02", "0.0", "0.20"),
            bool(VOLATILITY_ADAPTIVE_TOLERANCE, "Volatility-Adaptive Tolerance",
                    "When true, level tolerance = clamp(0.5 * ATR/price, adaptiveToleranceMin, adaptiveToleranceMax). High-vol instruments get a wider effective tolerance.",
                    true),
            decimal(ADAPTIVE_TOLERANCE_MIN, "Adaptive Tolerance Min",
                    "Floor for the volatility-adaptive tolerance.", "0.001", "0.0001", "0.05"),
            decimal(ADAPTIVE_TOLERANCE_MAX, "Adaptive Tolerance Max",
                    "Ceiling for the volatility-adaptive tolerance.", "0.005", "0.0001", "0.05"),
            bool(ALLOW_MIDLINE_WITH_MAX_CONFLUENCE, "Allow Midline With Max Confluence",
                    "When true, MIDLINE-zone entries are accepted if all four confluence factors hit. Defaults to true; small lift on A++ midline setups.",
                    true),
            bool(ALLOW_SECOND_TOUCH_IN_COOLDOWN, "Allow Second Touch In Cooldown",
                    "When true, a second entry is permitted within the cooldown window if the level is retouched with strictly higher confluence than the first.",
                    true),
            bool(TIER2_WITH_MAX_CONFLUENCE_COUNTS_AS_TIER1, "Tier-2 + Max Confluence = Tier-1",
                    "When true, tier-2 patterns (piercing, hammer, dark-cloud, shooting-star) at 4-of-4 confluence count as tier-1 quality even under TIER1_STRICT.",
                    true),
            bool(EMIT_DIAGNOSTICS, "Emit Diagnostics",
                    "When true, every rejecting evaluation emits a diagnostics line in StrategyIntentResult so the RunSet UI can show why an instrument didn't trade.",
                    true)
    ));

    private static final StrategyDescriptor DESCRIPTOR = new StrategyDescriptor(
            new StrategyIdentity(STRATEGY_ID, STRATEGY_VERSION),
            PROVIDER_ID,
            "Trend Pullback v3",
            "Evolution of Range S/R v2 — relaxed pattern tier, volatility-adaptive level tolerance, hybrid pivot source, and per-rejection diagnostics. H4 trend context + LTF structural pullback at defended levels.",
            List.of("M1", "M5", "M15"),
            List.of("EQUITY", "INDEX", "CRYPTO"),
            List.of("H4"),
            List.of(),
            List.of(
                    StrategyCapability.LONG_SIGNALS,
                    StrategyCapability.SHORT_SIGNALS,
                    StrategyCapability.TRADE_INTENT,
                    StrategyCapability.LONG_ENTRY_INTENT,
                    StrategyCapability.SHORT_ENTRY_INTENT,
                    StrategyCapability.RISK_AWARE_SIZING,
                    StrategyCapability.PARAMETERIZED
            ),
            SCHEMA,
            List.of(
                    study("adx", "ADX", "h4-trend-strength", Map.of("period", 14, "timeframe", "H4"), true),
                    study("ema", "EMA", "h4-ema50-trend", Map.of("period", 50, "timeframe", "H4"), true),
                    study("atr", "ATR", "execution-stop-buffer", Map.of("period", 14, "timeframe", "PRIMARY"), true),
                    study("fractal-pivots", "Fractal Pivots", "structure-levels", Map.of("lookback", 3, "timeframe", "HYBRID"), true)
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
        if (effective.integer(LTF_LOOKBACK, 200) < 20) {
            issues.add(new StrategyValidationIssue(LTF_LOOKBACK, "Execution lookback must be at least 20"));
        }
        if (effective.integer(HTF_LOOKBACK, 200) < 50) {
            issues.add(new StrategyValidationIssue(HTF_LOOKBACK, "H4 lookback must be at least 50"));
        }
        if (effective.integer(PIVOT_LOOKBACK, 3) * 2 + 1 > effective.integer(HTF_LOOKBACK, 200)) {
            issues.add(new StrategyValidationIssue(PIVOT_LOOKBACK, "Pivot lookback is too large for H4 lookback"));
        }
        // adaptiveToleranceMin must be <= adaptiveToleranceMax when adaptive mode is on
        if (effective.bool(VOLATILITY_ADAPTIVE_TOLERANCE, true)) {
            BigDecimal min = effective.decimal(ADAPTIVE_TOLERANCE_MIN, BigDecimal.valueOf(0.001));
            BigDecimal max = effective.decimal(ADAPTIVE_TOLERANCE_MAX, BigDecimal.valueOf(0.005));
            if (min.compareTo(max) > 0) {
                issues.add(new StrategyValidationIssue(ADAPTIVE_TOLERANCE_MIN,
                        "adaptiveToleranceMin must be <= adaptiveToleranceMax"));
            }
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        String pivotSourceLabel = effectiveParameters.string(PIVOT_SOURCE, "HYBRID");
        return List.of(
                study("adx", "ADX", "h4-trend-strength", Map.of("period", 14, "timeframe", "H4"), true),
                study("ema", "EMA", "h4-ema50-trend", Map.of("period", 50, "timeframe", "H4"), true),
                study("atr", "ATR", "execution-stop-buffer", Map.of("period", 14, "timeframe", "PRIMARY"), true),
                study("fractal-pivots", "Fractal Pivots", "structure-levels",
                        Map.of("lookback", effectiveParameters.integer(PIVOT_LOOKBACK, 3),
                                "timeframe", pivotSourceLabel), true)
        );
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Trend Pullback v3 strategy parameters: " + validation.issues());
        }
        StrategyParameters effective = validation.effectiveParameters();
        TrendPullbackV3Parameters.PatternTier tier = parseEnum(
                effective.string(PATTERN_TIER, "TIER1_OR_TIER2"),
                TrendPullbackV3Parameters.PatternTier.class,
                TrendPullbackV3Parameters.PatternTier.TIER1_OR_TIER2);
        TrendPullbackV3Parameters.PivotSource pivotSource = parseEnum(
                effective.string(PIVOT_SOURCE, "HYBRID"),
                TrendPullbackV3Parameters.PivotSource.class,
                TrendPullbackV3Parameters.PivotSource.HYBRID);
        return new TrendPullbackV3Strategy(new TrendPullbackV3Parameters(
                effective.decimal(MIN_TREND_ADX, BigDecimal.valueOf(15)),
                tier,
                effective.integer(MIN_CONFLUENCE, 2),
                effective.decimal(ATR_MULT_SL, BigDecimal.valueOf(1.5)),
                effective.decimal(ATR_MULT_MIN_RR, BigDecimal.valueOf(2.0)),
                effective.decimal(RISK_USD_PER_TRADE, BigDecimal.ONE),
                pivotSource,
                effective.integer(HTF_LOOKBACK, 200),
                effective.integer(LTF_LOOKBACK, 200),
                effective.integer(PIVOT_LOOKBACK, 3),
                effective.integer(COOLDOWN_HOURS, 4),
                effective.decimal(LEVEL_TOLERANCE_PCT, BigDecimal.valueOf(0.002)),
                effective.decimal(MIDLINE_TOLERANCE_PCT, BigDecimal.valueOf(0.02)),
                effective.bool(VOLATILITY_ADAPTIVE_TOLERANCE, true),
                effective.decimal(ADAPTIVE_TOLERANCE_MIN, BigDecimal.valueOf(0.001)),
                effective.decimal(ADAPTIVE_TOLERANCE_MAX, BigDecimal.valueOf(0.005)),
                effective.bool(ALLOW_MIDLINE_WITH_MAX_CONFLUENCE, true),
                effective.bool(ALLOW_SECOND_TOUCH_IN_COOLDOWN, true),
                effective.bool(TIER2_WITH_MAX_CONFLUENCE_COUNTS_AS_TIER1, true),
                effective.bool(EMIT_DIAGNOSTICS, true)
        ));
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static StrategyParameterDefinition integer(String key, String label, String description, int defaultValue, int min, int max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.INTEGER, label, description, true,
                defaultValue, BigDecimal.valueOf(min), BigDecimal.valueOf(max), List.of());
    }

    private static StrategyParameterDefinition decimal(String key, String label, String description, String defaultValue, String min, String max) {
        return new StrategyParameterDefinition(key, StrategyParameterType.DECIMAL, label, description, true,
                new BigDecimal(defaultValue), new BigDecimal(min), new BigDecimal(max), List.of());
    }

    private static StrategyParameterDefinition bool(String key, String label, String description, boolean defaultValue) {
        return new StrategyParameterDefinition(key, StrategyParameterType.BOOLEAN, label, description, true,
                defaultValue, null, null, List.of());
    }

    private static StrategyParameterDefinition enumeration(String key, String label, String description, String defaultValue, List<String> allowed) {
        return new StrategyParameterDefinition(key, StrategyParameterType.ENUM, label, description, true,
                defaultValue, null, null, allowed);
    }

    private static StrategyChartStudy study(String indicatorId, String displayName, String role, Map<String, Object> parameters, boolean required) {
        return new StrategyChartStudy(indicatorId, displayName, role, parameters, INDICATOR_FORMULA_VERSION, required, "");
    }
}
