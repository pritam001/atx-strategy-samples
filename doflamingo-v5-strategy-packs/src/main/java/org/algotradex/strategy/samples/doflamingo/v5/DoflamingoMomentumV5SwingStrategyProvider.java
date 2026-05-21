package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyContextTimeframeRule;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyInstantiationContext;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyValidationResult;
import org.algotradex.platform.core.api.service.strategy.StrategyProvider;
import org.algotradex.platform.core.api.service.strategy.TradeSignalStrategy;
import org.algotradex.platform.core.api.util.StrategyParameterValidator;

import java.util.ArrayList;
import java.util.List;

import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.bool;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.decimal;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.enumeration;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.integer;
import static org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5ProviderSupport.multiEnum;

public final class DoflamingoMomentumV5SwingStrategyProvider implements StrategyProvider {
    public static final String STRATEGY_ID = "doflamingo-momentum-v5-beta-swing";
    public static final String STRATEGY_VERSION = "5.0.0-beta.1";

    private static final List<String> REGIMES = List.of("RANGING_LOW_VOLATILITY", "RANGING_HIGH_VOLATILITY", "TRENDING", "VOLATILE_TREND");
    static final List<StrategyContextTimeframeRule> CONTEXT_RULES = List.of(
            new StrategyContextTimeframeRule("M5", "M15", true),
            new StrategyContextTimeframeRule("M15", "H1", true),
            new StrategyContextTimeframeRule("H1", "D1", true)
    );

    private static final StrategyParameterSchema SCHEMA = new StrategyParameterSchema(commonDefinitions(List.of(
            enumeration("horizonMode", "Horizon Mode", "Fixed catalog horizon for the swing V5 beta.", "SWING", List.of("SWING")),
            bool("allowOvernight", "Allow Overnight", "Swing variant accepts overnight risk by design.", true),
            enumeration("contextCloudBiasMode", "Context Cloud Bias Mode", "Mapped higher-timeframe cloud-bias handling for swing entries.", "REQUIRE_AGREEMENT", List.of("OFF", "PREFER_AGREEMENT", "REQUIRE_AGREEMENT")),
            decimal("riskFraction", "Risk Fraction", "Default risk fraction for swing entries.", "0.003", "0.0001", "0.05"),
            decimal("maxRiskFractionAfterScaleIn", "Max Risk After Scale-In", "Combined risk cap after a pyramiding intent.", "0.007", "0.0001", "0.05"),
            decimal("initialAtrStopMultiple", "Initial ATR Stop Multiple", "ATR multiple used by the swing stop candidate.", "1.80", "0.10", "10.00"),
            decimal("chandelierAtrMultiple", "Chandelier ATR Multiple", "ATR multiple for swing runner invalidation.", "3.00", "0.10", "10.00"),
            decimal("minStopPct", "Minimum Stop Percent", "Lower bound for swing stop distance.", "0.80", "0.01", "20.00"),
            decimal("maxStopPct", "Maximum Stop Percent", "Upper bound for swing stop distance.", "6.00", "0.01", "20.00"),
            decimal("initialTargetR", "Initial Target R", "Initial target expressed as reward-to-risk multiple.", "3.00", "0.10", "20.00"),
            decimal("scaleOutAtR", "Scale Out At R", "Winning R multiple required for scale-out.", "1.50", "0.10", "20.00"),
            decimal("scaleOutFraction", "Scale Out Fraction", "Open-position fraction to scale out.", "0.50", "0.01", "1.00"),
            decimal("runnerTargetR", "Runner Target R", "Runner target expressed as reward-to-risk multiple.", "5.00", "0.10", "30.00"),
            decimal("scaleInAtR", "Scale In At R", "Winning R multiple required for optional scale-in.", "1.75", "0.10", "20.00"),
            decimal("staleMinR", "Stale Minimum R", "Maximum R multiple below which an old position is stale.", "0.50", "-5.00", "20.00"),
            integer("staleBars", "Stale Bars", "Bars held before weak progress can trigger stale exit.", 20, 1, 500),
            integer("maxHoldingBars", "Max Holding Bars", "Hard swing holding cap in primary bars.", 64, 1, 1000),
            integer("cooldownBars", "Cooldown Bars", "Bars to wait after emitted lifecycle action.", 6, 0, 100),
            integer("structureExitConfirmBars", "Structure Exit Confirm Bars", "Consecutive structure-break bars required for exits.", 2, 1, 20),
            decimal("minConfidence", "Minimum Confidence", "Minimum confidence for fresh entries.", "0.68", "0.00", "1.00"),
            decimal("scaleInMinConfidence", "Scale-In Minimum Confidence", "Minimum confidence for optional scale-in.", "0.82", "0.00", "1.00"),
            decimal("maxEntryAtrFromCloudTop", "Max Entry ATR From Cloud", "Normal anti-chase distance from cloud boundary.", "3.50", "0.00", "50.00"),
            decimal("maxEntryAtrFromCloudTopStrongVolume", "Strong-Volume Max Entry ATR", "Anti-chase distance allowed when volume and MACD are strong.", "5.00", "0.00", "50.00"),
            bool("avoidFreshEntryOnFridayAfternoon", "Avoid Friday Afternoon", "Avoid fresh swing entries late on Fridays when metadata supports it.", true),
            bool("avoidFreshEntryBeforeKnownHoliday", "Avoid Known Holiday", "Avoid fresh entries before known holidays when metadata supports it.", true),
            bool("avoidFreshEntryBeforeEarnings", "Avoid Earnings", "Avoid fresh entries before known earnings when metadata supports it.", true),
            enumeration("manualEventBlackoutMode", "Manual Event Blackout Mode", "Fallback event-blackout behavior when scenario metadata exists.", "WARN_ONLY", List.of("OFF", "WARN_ONLY", "BLOCK")),
            bool("allowIndexFuturesOvernight", "Allow Index Futures Overnight", "Whether index futures may be held overnight.", false),
            multiEnum("skipMarketRegimes", "Skip Market Regimes", "Market regimes where fresh entries are skipped when supplied by context.", List.of("RANGING_LOW_VOLATILITY"), REGIMES)
    )));

    private static final StrategyDescriptor DESCRIPTOR = DoflamingoMomentumV5ProviderSupport.descriptor(
            STRATEGY_ID,
            STRATEGY_VERSION,
            "Doflamingo Momentum V5 Beta Swing",
            "Multi-day Doflamingo momentum rider using mapped higher-timeframe permission, M5/M15/H1 execution structure, wider risk, runner management, and structured reasoning evidence.",
            List.of("M5", "M15", "H1"),
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
        return DoflamingoMomentumV5IntradayStrategyProvider.validateWithDomainRules(StrategyParameterValidator.validate(SCHEMA, parameters));
    }

    @Override
    public List<StrategyChartStudy> effectiveChartStudies(StrategyParameters effectiveParameters) {
        return DoflamingoMomentumV5ProviderSupport.studies();
    }

    @Override
    public TradeSignalStrategy create(StrategyParameters parameters, StrategyInstantiationContext context) {
        StrategyValidationResult validation = validate(parameters);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid Doflamingo Momentum V5 swing parameters: " + validation.issues());
        }
        return new DoflamingoMomentumV5SwingStrategy(DoflamingoMomentumV5Parameters.swing(validation.effectiveParameters()));
    }

    private static List<StrategyParameterDefinition> commonDefinitions(List<StrategyParameterDefinition> variantDefinitions) {
        List<StrategyParameterDefinition> definitions = new ArrayList<>();
        definitions.addAll(List.of(
                integer("ichimokuConversionPeriod", "Ichimoku Conversion Period", "Tenkan/conversion period.", 9, 2, 100),
                integer("ichimokuBasePeriod", "Ichimoku Base Period", "Kijun/base period.", 26, 3, 200),
                integer("ichimokuSpanBPeriod", "Ichimoku Span B Period", "Span B lookback period.", 52, 4, 300),
                integer("ichimokuDisplacement", "Ichimoku Displacement", "Closed-bar cloud displacement.", 26, 1, 100),
                enumeration("entryMode", "Entry Mode", "V5 entry trigger mode.", "HYBRID", List.of("BREAKOUT", "PULLBACK_RESUME", "EARLY_TRANSITION", "HYBRID")),
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
                integer("primaryWarmupBars", "Primary Warmup Bars", "Minimum primary bars before fresh entries.", 120, 1, 2000),
                integer("contextWarmupBars", "Context Warmup Bars", "Minimum context bars when context bias is required.", 90, 1, 2000),
                decimal("volumePulseMultiple", "Volume Pulse Multiple", "Minimum volume pulse for fresh entries.", "1.20", "0.00", "20.00"),
                decimal("strongVolumePulseMultiple", "Strong Volume Pulse Multiple", "Strong volume override threshold.", "1.60", "0.00", "20.00"),
                decimal("atrExpansionMultiple", "ATR Expansion Multiple", "ATR expansion ratio used in stronger filters.", "1.05", "0.00", "20.00"),
                decimal("cloudStopBufferAtr", "Cloud Stop Buffer ATR", "ATR buffer beyond cloud/base stop.", "0.35", "0.00", "20.00"),
                bool("enableScaleOut", "Enable Scale-Out", "Emit partial exits for winners.", true),
                bool("enableScaleIn", "Enable Scale-In", "Emit optional add-to-winner intents.", false),
                decimal("scaleInFraction", "Scale-In Fraction", "Open-position fraction requested for scale-in.", "0.25", "0.01", "1.00"),
                integer("maxScaleIns", "Max Scale-Ins", "Maximum scale-in intents per position.", 1, 0, 10),
                bool("scaleInRequiresNewExtreme", "Scale-In Requires New Extreme", "Require fresh high/low before scale-in.", true),
                decimal("maxGapAdversePct", "Max Adverse Gap Percent", "Adverse gap threshold for swing gap invalidation.", "3.00", "0.00", "50.00"),
                decimal("minCloudThicknessAtr", "Minimum Cloud Thickness ATR", "Minimum present cloud thickness in ATR units.", "0.05", "0.00", "20.00"),
                decimal("minFutureSpreadAtr", "Minimum Future Spread ATR", "Minimum future cloud spread in ATR units.", "0.05", "0.00", "20.00"),
                bool("emitDiagnostics", "Emit Diagnostics", "Emit accept/reject diagnostic strings for Strategy Thinking and RunSet analysis.", true)
        ));
        definitions.addAll(variantDefinitions);
        return definitions;
    }
}
