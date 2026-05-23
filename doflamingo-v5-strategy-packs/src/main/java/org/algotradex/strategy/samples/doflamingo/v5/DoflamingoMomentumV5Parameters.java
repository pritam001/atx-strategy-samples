package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.common.enums.IntendedHorizonLabel;
import org.algotradex.platform.core.api.dto.common.replay.TimeframeAlignment;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyParameters;

import java.math.BigDecimal;
import java.util.List;

import static java.util.Objects.requireNonNull;

record DoflamingoMomentumV5Parameters(
        String strategyId,
        String strategyVersion,
        Variant variant,
        String horizonMode,
        IntendedHorizonLabel horizonLabel,
        String primaryContextTimeframe,
        String contextEvidenceKey,
        boolean allowOvernight,
        boolean allowShorts,
        BiasMode contextBiasMode,
        EntryMode entryMode,
        int ichimokuConversionPeriod,
        int ichimokuBasePeriod,
        int ichimokuSpanBPeriod,
        int ichimokuDisplacement,
        int emaFastPeriod,
        int emaMidPeriod,
        int emaAnchorPeriod,
        int smaSlowPeriod,
        int macdFastPeriod,
        int macdSlowPeriod,
        int macdSignalPeriod,
        int stochRsiPeriod,
        int stochRsiK,
        int stochRsiD,
        int atrPeriod,
        int chandelierLookbackBars,
        int volumeLookbackBars,
        int primaryWarmupBars,
        int contextWarmupBars,
        int skipOpeningMinutes,
        String middayStart,
        String middayEnd,
        String freshEntryCutoff,
        String forceExitTime,
        BigDecimal volumePulseMultiple,
        BigDecimal strongVolumePulseMultiple,
        BigDecimal atrExpansionMultiple,
        BigDecimal riskFraction,
        BigDecimal maxRiskFractionAfterScaleIn,
        BigDecimal initialAtrStopMultiple,
        boolean atrPercentileStopScaling,
        int atrStopPercentileLookbackBars,
        BigDecimal minAtrStopMultiple,
        BigDecimal maxAtrStopMultiple,
        BigDecimal cloudStopBufferAtr,
        BigDecimal chandelierAtrMultiple,
        BigDecimal minStopPct,
        BigDecimal maxStopPct,
        BigDecimal maxGapAdversePct,
        BigDecimal initialTargetR,
        boolean enableScaleOut,
        BigDecimal scaleOutAtR,
        BigDecimal scaleOutFraction,
        BigDecimal runnerTargetR,
        boolean enableScaleIn,
        BigDecimal scaleInAtR,
        BigDecimal scaleInFraction,
        int maxScaleIns,
        boolean scaleInRequiresNewExtreme,
        int staleBars,
        BigDecimal staleMinR,
        int maxHoldingBars,
        int cooldownBars,
        int structureExitConfirmBars,
        BigDecimal minConfidence,
        BigDecimal scaleInMinConfidence,
        BigDecimal maxEntryAtrFromCloudTop,
        BigDecimal maxEntryAtrFromCloudTopStrongVolume,
        BigDecimal minCloudThicknessAtr,
        BigDecimal minFutureSpreadAtr,
        List<String> skipMarketRegimes,
        boolean avoidFreshEntryOnFridayAfternoon,
        boolean avoidFreshEntryBeforeKnownHoliday,
        boolean avoidFreshEntryBeforeEarnings,
        String manualEventBlackoutMode,
        boolean allowIndexFuturesOvernight,
        boolean emitDiagnostics
) {
    enum Variant {
        INTRADAY,
        SWING
    }

    enum BiasMode {
        OFF,
        PREFER_AGREEMENT,
        REQUIRE_AGREEMENT
    }

    enum EntryMode {
        STRICT_CLOUD,
        BREAKOUT,
        PULLBACK_RESUME,
        EARLY_TRANSITION,
        HYBRID
    }

    DoflamingoMomentumV5Parameters {
        requireNonNull(strategyId, "strategyId");
        requireNonNull(strategyVersion, "strategyVersion");
        requireNonNull(variant, "variant");
        requireNonNull(horizonMode, "horizonMode");
        requireNonNull(horizonLabel, "horizonLabel");
        requireNonNull(primaryContextTimeframe, "primaryContextTimeframe");
        requireNonNull(contextEvidenceKey, "contextEvidenceKey");
        requireNonNull(contextBiasMode, "contextBiasMode");
        requireNonNull(entryMode, "entryMode");
        skipMarketRegimes = List.copyOf(skipMarketRegimes == null ? List.of() : skipMarketRegimes);
    }

    static DoflamingoMomentumV5Parameters intraday(StrategyParameters effective) {
        return new DoflamingoMomentumV5Parameters(
                DoflamingoMomentumV5IntradayStrategyProvider.STRATEGY_ID,
                DoflamingoMomentumV5IntradayStrategyProvider.STRATEGY_VERSION,
                Variant.INTRADAY,
                "INTRADAY",
                IntendedHorizonLabel.INTRADAY,
                "H1",
                "contextCloudBias",
                false,
                true,
                BiasMode.valueOf(effective.string("contextCloudBiasMode", effective.string("h1CloudBiasMode", "REQUIRE_AGREEMENT"))),
                EntryMode.valueOf(effective.string("entryMode", "HYBRID")),
                effective.integer("ichimokuConversionPeriod", 9),
                effective.integer("ichimokuBasePeriod", 26),
                effective.integer("ichimokuSpanBPeriod", 52),
                effective.integer("ichimokuDisplacement", 26),
                effective.integer("emaFastPeriod", 9),
                effective.integer("emaMidPeriod", 20),
                effective.integer("emaAnchorPeriod", 50),
                effective.integer("smaSlowPeriod", 200),
                effective.integer("macdFastPeriod", 12),
                effective.integer("macdSlowPeriod", 26),
                effective.integer("macdSignalPeriod", 9),
                effective.integer("stochRsiPeriod", 14),
                effective.integer("stochRsiK", 3),
                effective.integer("stochRsiD", 3),
                effective.integer("atrPeriod", 14),
                effective.integer("chandelierLookbackBars", 22),
                effective.integer("volumeLookbackBars", 20),
                effective.integer("primaryWarmupBars", 90),
                effective.integer("contextWarmupBars", 80),
                effective.integer("skipOpeningMinutes", 15),
                effective.string("middayStart", "11:30"),
                effective.string("middayEnd", "13:45"),
                effective.string("freshEntryCutoff", "15:05"),
                effective.string("forceExitTime", "15:15"),
                effective.decimal("volumePulseMultiple", BigDecimal.valueOf(1.5)),
                effective.decimal("strongVolumePulseMultiple", BigDecimal.valueOf(2.0)),
                effective.decimal("atrExpansionMultiple", BigDecimal.valueOf(1.10)),
                effective.decimal("riskFraction", BigDecimal.valueOf(0.005)),
                effective.decimal("maxRiskFractionAfterScaleIn", BigDecimal.valueOf(0.010)),
                effective.decimal("initialAtrStopMultiple", BigDecimal.valueOf(1.20)),
                false,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                effective.decimal("cloudStopBufferAtr", BigDecimal.valueOf(0.25)),
                effective.decimal("chandelierAtrMultiple", BigDecimal.valueOf(2.50)),
                effective.decimal("minStopPct", BigDecimal.valueOf(0.40)),
                effective.decimal("maxStopPct", BigDecimal.valueOf(2.50)),
                effective.decimal("maxGapAdversePct", BigDecimal.valueOf(3.00)),
                effective.decimal("initialTargetR", BigDecimal.valueOf(2.50)),
                effective.bool("enableScaleOut", true),
                effective.decimal("scaleOutAtR", BigDecimal.valueOf(1.25)),
                effective.decimal("scaleOutFraction", BigDecimal.valueOf(0.40)),
                effective.decimal("runnerTargetR", BigDecimal.valueOf(3.50)),
                effective.bool("enableScaleIn", false),
                effective.decimal("scaleInAtR", BigDecimal.valueOf(1.25)),
                effective.decimal("scaleInFraction", BigDecimal.valueOf(0.25)),
                effective.integer("maxScaleIns", 1),
                effective.bool("scaleInRequiresNewExtreme", true),
                effective.integer("staleBars", 10),
                effective.decimal("staleMinR", BigDecimal.valueOf(0.30)),
                effective.integer("maxHoldingBars", 24),
                effective.integer("cooldownBars", 4),
                effective.integer("structureExitConfirmBars", 1),
                effective.decimal("minConfidence", BigDecimal.valueOf(0.65)),
                effective.decimal("scaleInMinConfidence", BigDecimal.valueOf(0.80)),
                effective.decimal("maxEntryAtrFromCloudTop", BigDecimal.valueOf(3.00)),
                effective.decimal("maxEntryAtrFromCloudTopStrongVolume", BigDecimal.valueOf(4.00)),
                effective.decimal("minCloudThicknessAtr", BigDecimal.valueOf(0.05)),
                effective.decimal("minFutureSpreadAtr", BigDecimal.valueOf(0.05)),
                effective.stringList("skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY", "RANGING_HIGH_VOLATILITY")),
                false,
                false,
                false,
                "OFF",
                false,
                effective.bool("emitDiagnostics", true)
        );
    }

    static DoflamingoMomentumV5Parameters swing(StrategyParameters effective) {
        return new DoflamingoMomentumV5Parameters(
                DoflamingoMomentumV5SwingStrategyProvider.STRATEGY_ID,
                DoflamingoMomentumV5SwingStrategyProvider.STRATEGY_VERSION,
                Variant.SWING,
                "SWING",
                IntendedHorizonLabel.SWING,
                "D1",
                "contextCloudBias",
                effective.bool("allowOvernight", true),
                effective.bool("allowShorts", false),
                BiasMode.valueOf(effective.string("contextCloudBiasMode", effective.string("dailyCloudBiasMode", "PREFER_AGREEMENT"))),
                EntryMode.valueOf(effective.string("entryMode", "HYBRID")),
                effective.integer("ichimokuConversionPeriod", 9),
                effective.integer("ichimokuBasePeriod", 26),
                effective.integer("ichimokuSpanBPeriod", 52),
                effective.integer("ichimokuDisplacement", 26),
                effective.integer("emaFastPeriod", 9),
                effective.integer("emaMidPeriod", 20),
                effective.integer("emaAnchorPeriod", 50),
                effective.integer("smaSlowPeriod", 200),
                effective.integer("macdFastPeriod", 12),
                effective.integer("macdSlowPeriod", 26),
                effective.integer("macdSignalPeriod", 9),
                effective.integer("stochRsiPeriod", 14),
                effective.integer("stochRsiK", 3),
                effective.integer("stochRsiD", 3),
                effective.integer("atrPeriod", 14),
                effective.integer("chandelierLookbackBars", 22),
                effective.integer("volumeLookbackBars", 20),
                effective.integer("primaryWarmupBars", 120),
                effective.integer("contextWarmupBars", 90),
                0,
                "00:00",
                "00:00",
                "23:59",
                "23:59",
                effective.decimal("volumePulseMultiple", BigDecimal.valueOf(1.20)),
                effective.decimal("strongVolumePulseMultiple", BigDecimal.valueOf(1.60)),
                effective.decimal("atrExpansionMultiple", BigDecimal.valueOf(1.05)),
                effective.decimal("riskFraction", BigDecimal.valueOf(0.003)),
                effective.decimal("maxRiskFractionAfterScaleIn", BigDecimal.valueOf(0.007)),
                effective.decimal("initialAtrStopMultiple", BigDecimal.valueOf(2.75)),
                effective.bool("atrPercentileStopScaling", true),
                effective.integer("atrStopPercentileLookbackBars", 60),
                effective.decimal("minAtrStopMultiple", BigDecimal.valueOf(2.50)),
                effective.decimal("maxAtrStopMultiple", BigDecimal.valueOf(3.00)),
                effective.decimal("cloudStopBufferAtr", BigDecimal.valueOf(0.35)),
                effective.decimal("chandelierAtrMultiple", BigDecimal.valueOf(3.00)),
                effective.decimal("minStopPct", BigDecimal.valueOf(0.80)),
                effective.decimal("maxStopPct", BigDecimal.valueOf(6.00)),
                effective.decimal("maxGapAdversePct", BigDecimal.valueOf(3.00)),
                effective.decimal("initialTargetR", BigDecimal.valueOf(3.00)),
                effective.bool("enableScaleOut", true),
                effective.decimal("scaleOutAtR", BigDecimal.valueOf(1.50)),
                effective.decimal("scaleOutFraction", BigDecimal.valueOf(0.50)),
                effective.decimal("runnerTargetR", BigDecimal.valueOf(5.00)),
                effective.bool("enableScaleIn", false),
                effective.decimal("scaleInAtR", BigDecimal.valueOf(1.75)),
                effective.decimal("scaleInFraction", BigDecimal.valueOf(0.25)),
                effective.integer("maxScaleIns", 1),
                effective.bool("scaleInRequiresNewExtreme", true),
                effective.integer("staleBars", 20),
                effective.decimal("staleMinR", BigDecimal.valueOf(0.50)),
                effective.integer("maxHoldingBars", 96),
                effective.integer("cooldownBars", 6),
                effective.integer("structureExitConfirmBars", 2),
                effective.decimal("minConfidence", BigDecimal.valueOf(0.68)),
                effective.decimal("scaleInMinConfidence", BigDecimal.valueOf(0.82)),
                effective.decimal("maxEntryAtrFromCloudTop", BigDecimal.ZERO),
                effective.decimal("maxEntryAtrFromCloudTopStrongVolume", BigDecimal.ZERO),
                effective.decimal("minCloudThicknessAtr", BigDecimal.valueOf(0.05)),
                effective.decimal("minFutureSpreadAtr", BigDecimal.valueOf(0.05)),
                effective.stringList("skipMarketRegimes", List.of("RANGING_LOW_VOLATILITY")),
                effective.bool("avoidFreshEntryOnFridayAfternoon", true),
                effective.bool("avoidFreshEntryBeforeKnownHoliday", true),
                effective.bool("avoidFreshEntryBeforeEarnings", true),
                effective.string("manualEventBlackoutMode", "WARN_ONLY"),
                effective.bool("allowIndexFuturesOvernight", false),
                effective.bool("emitDiagnostics", true)
        );
    }

    String contextTimeframeFor(String primaryTimeframe) {
        String normalized = TimeframeAlignment.normalize(primaryTimeframe);
        return switch (variant) {
            case INTRADAY -> switch (normalized) {
                case "M1" -> "M5";
                case "M5" -> "M15";
                case "M15" -> "H1";
                default -> primaryContextTimeframe;
            };
            case SWING -> switch (normalized) {
                case "M5" -> "M15";
                case "M15" -> "H1";
                case "H1" -> "D1";
                default -> primaryContextTimeframe;
            };
        };
    }
}
