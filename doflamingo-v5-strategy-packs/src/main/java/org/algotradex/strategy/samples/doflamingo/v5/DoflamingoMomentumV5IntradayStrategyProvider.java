package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyContextTimeframeRule;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationIssue;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.bool;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.decimal;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.enumeration;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.integer;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.multiEnum;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.text;

public final class DoflamingoMomentumV5IntradayStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-momentum-v5-beta-intraday";
    public static final String STRATEGY_VERSION = "5.0.0-beta.1";

    private static final List<String> REGIMES = List.of("RANGING_LOW_VOLATILITY", "RANGING_HIGH_VOLATILITY", "TRENDING", "VOLATILE_TREND");
    static final List<StrategyContextTimeframeRule> CONTEXT_RULES = List.of(
            new StrategyContextTimeframeRule("M1", "M5", true),
            new StrategyContextTimeframeRule("M5", "M15", true),
            new StrategyContextTimeframeRule("M15", "H1", true)
    );

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(commonDefinitions(List.of(
            enumeration("horizonMode", "Horizon Mode", "Fixed catalog horizon for the intraday V5 beta.", "INTRADAY", List.of("INTRADAY")),
            enumeration("contextCloudBiasMode", "Context Cloud Bias Mode", "Mapped higher-timeframe cloud-bias handling for intraday entries.", "REQUIRE_AGREEMENT", List.of("OFF", "PREFER_AGREEMENT", "REQUIRE_AGREEMENT")),
            integer("skipOpeningMinutes", "Skip Opening Minutes", "Fresh-entry embargo after NSE cash open.", 15, 0, 120),
            text("middayStart", "Midday Start", "Start of passive midday window in IST.", "11:30"),
            text("middayEnd", "Midday End", "End of passive midday window in IST.", "13:45"),
            text("freshEntryCutoff", "Fresh Entry Cutoff", "No fresh entries at or after this IST time.", "15:05"),
            text("forceExitTime", "Force Exit Time", "Flatten intraday positions at or after this IST time.", "15:15"),
            decimal("riskFraction", "Risk Fraction", "Default risk fraction for intraday entries.", "0.005", "0.0001", "0.05"),
            decimal("maxRiskFractionAfterScaleIn", "Max Risk After Scale-In", "Combined risk cap after a pyramiding intent.", "0.010", "0.0001", "0.05"),
            decimal("initialAtrStopMultiple", "Initial ATR Stop Multiple", "ATR multiple used by the intraday stop candidate.", "1.20", "0.10", "10.00"),
            decimal("chandelierAtrMultiple", "Chandelier ATR Multiple", "ATR multiple for runner invalidation after scale-out.", "2.50", "0.10", "10.00"),
            decimal("minStopPct", "Minimum Stop Percent", "Lower bound for intraday stop distance.", "0.40", "0.01", "20.00"),
            decimal("maxStopPct", "Maximum Stop Percent", "Upper bound for intraday stop distance.", "2.50", "0.01", "20.00"),
            decimal("initialTargetR", "Initial Target R", "Initial target expressed as reward-to-risk multiple.", "2.50", "0.10", "20.00"),
            decimal("scaleOutAtR", "Scale Out At R", "Winning R multiple required for scale-out.", "1.25", "0.10", "20.00"),
            decimal("scaleOutFraction", "Scale Out Fraction", "Open-position fraction to scale out.", "0.40", "0.01", "1.00"),
            decimal("runnerTargetR", "Runner Target R", "Runner target expressed as reward-to-risk multiple.", "3.50", "0.10", "30.00"),
            decimal("scaleInAtR", "Scale In At R", "Winning R multiple required for optional scale-in.", "1.25", "0.10", "20.00"),
            decimal("staleMinR", "Stale Minimum R", "Maximum R multiple below which an old position is stale.", "0.30", "-5.00", "20.00"),
            integer("staleBars", "Stale Bars", "Bars held before weak progress can trigger stale exit.", 10, 1, 500),
            integer("maxHoldingBars", "Max Holding Bars", "Hard intraday holding cap in primary bars.", 24, 1, 1000),
            integer("cooldownBars", "Cooldown Bars", "Bars to wait after emitted lifecycle action.", 4, 0, 100),
            integer("structureExitConfirmBars", "Structure Exit Confirm Bars", "Consecutive structure-break bars required for exits.", 1, 1, 20),
            decimal("minConfidence", "Minimum Confidence", "Minimum confidence for fresh entries.", "0.65", "0.00", "1.00"),
            decimal("scaleInMinConfidence", "Scale-In Minimum Confidence", "Minimum confidence for optional scale-in.", "0.80", "0.00", "1.00"),
            decimal("maxEntryAtrFromCloudTop", "Max Entry ATR From Cloud", "Normal anti-chase distance from cloud boundary.", "3.00", "0.00", "50.00"),
            decimal("maxEntryAtrFromCloudTopStrongVolume", "Strong-Volume Max Entry ATR", "Anti-chase distance allowed when volume and MACD are strong.", "4.00", "0.00", "50.00"),
            multiEnum("skipMarketRegimes", "Skip Market Regimes", "Market regimes where fresh entries are skipped when supplied by context.", List.of("RANGING_LOW_VOLATILITY", "RANGING_HIGH_VOLATILITY"), REGIMES)
    )));

    private static final StrategyDescriptor DESCRIPTOR = DoflamingoMomentumV5ProviderSupport.descriptor(
            STRATEGY_ID,
            STRATEGY_VERSION,
            "Doflamingo Momentum V5 Beta Intraday",
            "Same-day Doflamingo momentum rider using mapped higher-timeframe permission, M1/M5/M15 cloud momentum, explicit exits, scale-out, and structured reasoning evidence.",
            List.of("M1", "M5", "M15"),
            List.of(),
            List.of(),
            CONTEXT_RULES,
            SCHEMA
    );

    @Override
    public StrategyDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public StrategyValidationResult validate(StrategyParameters parameters) {
        return validateWithDomainRules(StrategyParameterValidator.validate(SCHEMA, parameters));
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return DoflamingoMomentumV5ProviderSupport.studies();
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo Momentum V5 intraday parameters: " + validation.issues());
        }
        return new DoflamingoMomentumV5IntradayStrategy(DoflamingoMomentumV5Parameters.intraday(validation.effectiveParameters()));
    }

    private static List<StrategyParameterDefinition> commonDefinitions(List<StrategyParameterDefinition> variantDefinitions) {
        List<StrategyParameterDefinition> definitions = new ArrayList<>();
        definitions.addAll(List.of(
                integer("ichimokuConversionPeriod", "Ichimoku Conversion Period", "Tenkan/conversion period.", 9, 2, 100),
                integer("ichimokuBasePeriod", "Ichimoku Base Period", "Kijun/base period.", 26, 3, 200),
                integer("ichimokuSpanBPeriod", "Ichimoku Span B Period", "Span B lookback period.", 52, 4, 300),
                integer("ichimokuDisplacement", "Ichimoku Displacement", "Closed-bar cloud displacement.", 26, 1, 100),
                enumeration("entryMode", "Entry Mode", "V5 entry trigger mode.", "HYBRID", List.of("STRICT_CLOUD", "BREAKOUT", "PULLBACK_RESUME", "EARLY_TRANSITION", "HYBRID")),
                integer("emaFastPeriod", "Fast EMA Period", "Fast EMA trend lens.", 9, 2, 200),
                integer("emaMidPeriod", "Mid EMA Period", "Mid EMA trend and exit lens.", 20, 3, 300),
                integer("emaAnchorPeriod", "Anchor EMA Period", "Anchor EMA trend lens.", 50, 4, 500),
                integer("smaSlowPeriod", "Slow SMA Period", "Optional slow swing/equity trend lens.", 200, 5, 1000),
                integer("macdFastPeriod", "MACD Fast Period", "MACD fast EMA period.", 12, 2, 100),
                integer("macdSlowPeriod", "MACD Slow Period", "MACD slow EMA period.", 26, 3, 200),
                integer("macdSignalPeriod", "MACD Signal Period", "MACD signal EMA period.", 9, 2, 100),
                integer("stochRsiPeriod", "Stoch RSI Period", "RSI lookback used by Stoch RSI.", 14, 2, 100),
                integer("stochRsiK", "Stoch RSI K", "K smoothing period.", 3, 1, 20),
                integer("stochRsiD", "Stoch RSI D", "D smoothing period.", 3, 1, 20),
                integer("atrPeriod", "ATR Period", "ATR period used for risk and expansion.", 14, 2, 100),
                integer("chandelierLookbackBars", "Chandelier Lookback", "Lookback for runner Chandelier invalidation.", 22, 2, 300),
                integer("volumeLookbackBars", "Volume Lookback", "Lookback for volume pulse.", 20, 2, 300),
                integer("primaryWarmupBars", "Primary Warmup Bars", "Minimum primary bars before fresh entries.", 90, 1, 2000),
                integer("contextWarmupBars", "Context Warmup Bars", "Minimum context bars when context bias is required.", 80, 1, 2000),
                decimal("volumePulseMultiple", "Volume Pulse Multiple", "Minimum volume pulse for fresh entries.", "1.50", "0.00", "20.00"),
                decimal("strongVolumePulseMultiple", "Strong Volume Pulse Multiple", "Strong volume override threshold.", "2.00", "0.00", "20.00"),
                decimal("atrExpansionMultiple", "ATR Expansion Multiple", "ATR expansion ratio used in stronger filters.", "1.10", "0.00", "20.00"),
                decimal("cloudStopBufferAtr", "Cloud Stop Buffer ATR", "ATR buffer beyond cloud/base stop.", "0.25", "0.00", "20.00"),
                bool("enableScaleOut", "Enable Scale-Out", "Emit partial exits for winners.", true),
                bool("enableScaleIn", "Enable Scale-In", "Emit optional add-to-winner intents.", false),
                decimal("scaleInFraction", "Scale-In Fraction", "Open-position fraction requested for scale-in.", "0.25", "0.01", "1.00"),
                integer("maxScaleIns", "Max Scale-Ins", "Maximum scale-in intents per position.", 1, 0, 10),
                bool("scaleInRequiresNewExtreme", "Scale-In Requires New Extreme", "Require fresh high/low before scale-in.", true),
                decimal("maxGapAdversePct", "Max Adverse Gap Percent", "Adverse gap threshold for swing-style exits when available.", "3.00", "0.00", "50.00"),
                decimal("minCloudThicknessAtr", "Minimum Cloud Thickness ATR", "Minimum present cloud thickness in ATR units.", "0.05", "0.00", "20.00"),
                decimal("minFutureSpreadAtr", "Minimum Future Spread ATR", "Minimum future cloud spread in ATR units.", "0.05", "0.00", "20.00"),
                bool("emitDiagnostics", "Emit Diagnostics", "Emit accept/reject diagnostic strings for Strategy Thinking and RunSet analysis.", true)
        ));
        definitions.addAll(variantDefinitions);
        return definitions;
    }

    static StrategyValidationResult validateWithDomainRules(StrategyValidationResult result) {
        if (!result.valid()) {
            return result;
        }
        StrategyParameters effective = result.effectiveParameters();
        List<StrategyValidationIssue> issues = new ArrayList<>();
        if (effective.integer("ichimokuConversionPeriod", 9) >= effective.integer("ichimokuBasePeriod", 26)) {
            issues.add(new StrategyValidationIssue("ichimokuConversionPeriod", "Conversion period must be less than base period"));
        }
        if (effective.integer("ichimokuBasePeriod", 26) >= effective.integer("ichimokuSpanBPeriod", 52)) {
            issues.add(new StrategyValidationIssue("ichimokuBasePeriod", "Base period must be less than Span B period"));
        }
        if (effective.integer("emaFastPeriod", 9) >= effective.integer("emaMidPeriod", 20)) {
            issues.add(new StrategyValidationIssue("emaFastPeriod", "Fast EMA period must be less than mid EMA period"));
        }
        if (effective.integer("emaMidPeriod", 20) >= effective.integer("emaAnchorPeriod", 50)) {
            issues.add(new StrategyValidationIssue("emaMidPeriod", "Mid EMA period must be less than anchor EMA period"));
        }
        if (effective.integer("macdFastPeriod", 12) >= effective.integer("macdSlowPeriod", 26)) {
            issues.add(new StrategyValidationIssue("macdFastPeriod", "MACD fast period must be less than slow period"));
        }
        if (effective.decimal("minStopPct", BigDecimal.ZERO).compareTo(effective.decimal("maxStopPct", BigDecimal.ONE)) > 0) {
            issues.add(new StrategyValidationIssue("minStopPct", "Minimum stop percent must be <= maximum stop percent"));
        }
        if (effective.decimal("scaleOutFraction", BigDecimal.valueOf(0.40)).compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(new StrategyValidationIssue("scaleOutFraction", "Scale-out fraction must be positive"));
        }
        if (effective.decimal("minConfidence", BigDecimal.ZERO).compareTo(effective.decimal("scaleInMinConfidence", BigDecimal.ONE)) > 0) {
            issues.add(new StrategyValidationIssue("minConfidence", "Entry confidence should be <= scale-in confidence"));
        }
        return issues.isEmpty() ? result : StrategyValidationResult.invalid(issues);
    }
}
