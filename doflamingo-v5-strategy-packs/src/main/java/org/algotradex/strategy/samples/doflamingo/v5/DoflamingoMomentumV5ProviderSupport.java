package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.simulation.ConditionRole;
import org.algotradex.platform.contracts.simulation.ReasoningConditionDescriptor;
import org.algotradex.platform.contracts.simulation.ReasoningModel;
import org.algotradex.platform.contracts.simulation.ReasoningPhaseDescriptor;
import org.algotradex.platform.core.api.dto.common.indicator.StrategyChartStudy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyDescriptor;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyContextTimeframeRule;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyIdentity;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterDefinition;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterResumePolicy;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameterSchema;
import org.algotradex.platform.core.api.enums.strategy.StrategyCapability;
import org.algotradex.platform.core.api.enums.strategy.StrategyParameterType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class DoflamingoMomentumV5ProviderSupport {
    static final String PROVIDER_ID = "atx-strategy-samples";
    private static final String FORMULA_VERSION = "doflamingo-momentum-v5-beta.1";

    private DoflamingoMomentumV5ProviderSupport() {
    }

    static StrategyDescriptor descriptor(
            String strategyId,
            String strategyVersion,
            String displayName,
            String description,
            List<String> supportedTimeframes,
            List<String> requiredContexts,
            List<String> optionalContexts,
            List<StrategyContextTimeframeRule> contextRules,
            StrategyParameterSchema schema
    ) {
        return new StrategyDescriptor(
                new StrategyIdentity(strategyId, strategyVersion),
                PROVIDER_ID,
                displayName,
                description,
                supportedTimeframes,
                List.of("INDEX", "FUTURES", "EQUITY"),
                requiredContexts,
                optionalContexts,
                contextRules,
                capabilities(),
                schema,
                studies(),
                reasoningModel()
        );
    }

    static List<StrategyCapability> capabilities() {
        return List.of(
                StrategyCapability.LONG_SIGNALS,
                StrategyCapability.SHORT_SIGNALS,
                StrategyCapability.TRADE_INTENT,
                StrategyCapability.LONG_ENTRY_INTENT,
                StrategyCapability.SHORT_ENTRY_INTENT,
                StrategyCapability.EXIT_INTENT,
                StrategyCapability.SCALE_OUT_INTENT,
                StrategyCapability.SCALE_IN_INTENT,
                StrategyCapability.RISK_AWARE_SIZING,
                StrategyCapability.PARAMETERIZED
        );
    }

    static List<StrategyChartStudy> studies() {
        return List.of(
                study("ichimoku", "Ichimoku Cloud", "cloud-structure", Map.of("conversion", 9, "base", 26, "spanB", 52, "displacement", 26), true),
                study("ema", "EMA Stack", "trend-compression-expansion", Map.of("fast", 9, "mid", 20, "anchor", 50), true),
                study("macd", "MACD", "momentum-confirmation", Map.of("fast", 12, "slow", 26, "signal", 9), true),
                study("stoch-rsi", "Stochastic RSI", "momentum-confirmation", Map.of("period", 14, "k", 3, "d", 3), true),
                study("psar", "PSAR", "momentum-confirmation", Map.of("step", "0.02", "max", "0.20"), true),
                study("atr", "ATR and Chandelier", "risk-location", Map.of("period", 14, "chandelierLookback", 22), true),
                study("volume-pulse", "Volume Pulse", "participation", Map.of("lookback", 20), true)
        );
    }

    static ReasoningModel reasoningModel() {
        return new ReasoningModel(
                "doflamingo-momentum-v5-beta-reasoning-v1",
                "Expose how context permission, cloud structure, momentum, participation, risk, and lifecycle state produced or blocked a V5 beta momentum intent.",
                "Doflamingo Momentum V5 Beta explains each closed-bar decision using stable condition IDs and numeric evidence.",
                List.of(
                        phase("context", "Context", "Check higher-timeframe cloud permission before using execution-timeframe momentum."),
                        phase("structure", "Structure", "Confirm primary cloud, EMA stack, and continuation trigger quality."),
                        phase("momentum", "Momentum", "Require participation plus two-of-three MACD, Stoch RSI, and PSAR confirmation."),
                        phase("risk", "Risk", "Reject weak confidence, poor stop distance, overextension, cooldown, or session/event gates."),
                        phase("lifecycle", "Lifecycle", "For open positions, prioritize exits before scale-out and scale-in.")
                ),
                List.of(
                        condition("context-bias", "Context cloud bias", ConditionRole.ENTRY_FILTER, true, "context", "Higher timeframe must permit the side when required."),
                        condition("cloud-structure", "Cloud structure", ConditionRole.REGIME_FILTER, true, "structure", "Price, future cloud, conversion/base, and cloud thickness must align."),
                        condition("ema-structure", "EMA structure", ConditionRole.ENTRY_FILTER, true, "structure", "EMA stack or pullback-resume structure must support continuation."),
                        condition("momentum-confirmation", "Momentum confirmation", ConditionRole.ENTRY_TRIGGER, true, "momentum", "At least two momentum confirmations must agree."),
                        condition("participation", "Participation", ConditionRole.ENTRY_FILTER, true, "momentum", "Volume pulse and ATR expansion must fit the session/horizon."),
                        condition("risk-location", "Risk location", ConditionRole.RISK_GUARD, true, "risk", "Stop distance, overextension, and confidence must be acceptable."),
                        condition("lifecycle-state", "Lifecycle state", ConditionRole.POSITION_CONTEXT, false, "lifecycle", "Open-position lifecycle actions are evaluated before new entries.")
                )
        );
    }

    static StrategyParameterDefinition integer(String key, String label, String description, int fallback, int min, int max) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.INTEGER, label, description, true, fallback,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), List.of()));
    }

    static StrategyParameterDefinition decimal(String key, String label, String description, String fallback, String min, String max) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.DECIMAL, label, description, true, new BigDecimal(fallback),
                new BigDecimal(min), new BigDecimal(max), List.of()));
    }

    static StrategyParameterDefinition bool(String key, String label, String description, boolean fallback) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.BOOLEAN, label, description, true, fallback,
                null, null, List.of()));
    }

    static StrategyParameterDefinition enumeration(String key, String label, String description, String fallback, List<String> allowedValues) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.ENUM, label, description, true, fallback,
                null, null, allowedValues));
    }

    static StrategyParameterDefinition text(String key, String label, String description, String fallback) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.STRING, label, description, true, fallback,
                null, null, List.of()));
    }

    static StrategyParameterDefinition multiEnum(String key, String label, String description, List<String> fallback, List<String> allowedValues) {
        return withPolicy(new StrategyParameterDefinition(key, StrategyParameterType.MULTI_ENUM, label, description, false, fallback,
                null, null, allowedValues));
    }

    private static StrategyParameterDefinition withPolicy(StrategyParameterDefinition definition) {
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
                StrategyParameterResumePolicy.forwardOnly()
        );
    }

    private static StrategyChartStudy study(String id, String name, String role, Map<String, Object> parameters, boolean required) {
        return new StrategyChartStudy(id, name, role, parameters, FORMULA_VERSION, required, "Computed locally by the Doflamingo V5 beta sample strategy.");
    }

    private static ReasoningPhaseDescriptor phase(String id, String label, String description) {
        return new ReasoningPhaseDescriptor(id, label, description);
    }

    private static ReasoningConditionDescriptor condition(String id, String label, ConditionRole role, boolean required, String phase, String purpose) {
        return new ReasoningConditionDescriptor(id, label, role, required, phase, purpose,
                label + " passed.",
                label + " blocked the setup.");
    }
}
