package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.intelligence.StrategyTradeIntentConditionEvidence;
import org.algotradex.platform.core.api.dto.common.marketcontext.MarketContextSnapshot;
import org.algotradex.platform.core.api.dto.common.strategy.StrategyExecutionContext;
import org.algotradex.platform.core.api.enums.marketcontext.MarketContextReadiness;
import org.algotradex.platform.core.api.enums.marketcontext.PrimaryMarketRegime;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Entry-only market-regime skip policy shared by the Doflamingo v4 samples.
 * <p>
 * The filter maps provider parameter values into filterable {@link PrimaryMarketRegime} values and
 * blocks new entries only when market context is ready and in a skipped regime. It does not suppress
 * lifecycle exits, scale events, or reversals for existing runtime positions.
 */
final class DoflamingoMarketRegimeFilter {
    static final String SKIP_MARKET_REGIMES = "skipMarketRegimes";
    static final List<String> ALLOWED_REGIME_NAMES = Arrays.stream(PrimaryMarketRegime.values())
            .filter(DoflamingoMarketRegimeFilter::isFilterable)
            .map(PrimaryMarketRegime::name)
            .toList();

    private DoflamingoMarketRegimeFilter() {
    }

    static Set<PrimaryMarketRegime> regimes(List<String> names) {
        EnumSet<PrimaryMarketRegime> regimes = EnumSet.noneOf(PrimaryMarketRegime.class);
        if (names == null) {
            return regimes;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            PrimaryMarketRegime regime = PrimaryMarketRegime.valueOf(name.trim());
            if (!isFilterable(regime)) {
                throw new IllegalArgumentException("Market regime cannot be used as a skip option: " + regime);
            }
            regimes.add(regime);
        }
        return regimes;
    }

    static boolean entryBlocked(StrategyExecutionContext context, Set<PrimaryMarketRegime> skippedRegimes) {
        if (skippedRegimes == null || skippedRegimes.isEmpty()) {
            return false;
        }
        MarketContextSnapshot snapshot = context.marketContext();
        return snapshot.readiness() == MarketContextReadiness.READY
                && skippedRegimes.contains(snapshot.primaryRegime());
    }

    static StrategyTradeIntentConditionEvidence allowedCondition(
            String conditionId,
            StrategyExecutionContext context,
            Set<PrimaryMarketRegime> skippedRegimes
    ) {
        String regime = currentRegimeName(context);
        String skipped = skippedNames(skippedRegimes).toString();
        boolean allowed = !entryBlocked(context, skippedRegimes);
        return new StrategyTradeIntentConditionEvidence(
                conditionId,
                "Market regime is allowed for entry",
                "Market regime",
                null,
                "not in",
                "Skip market regimes",
                null,
                allowed,
                "Market regime " + regime + " not in skipMarketRegimes " + skipped
        );
    }

    static String marketRegimeEvidence(StrategyExecutionContext context) {
        return "marketRegime=" + currentRegimeName(context);
    }

    static String skipRegimesEvidence(Set<PrimaryMarketRegime> skippedRegimes) {
        return "skipMarketRegimes=" + skippedNames(skippedRegimes);
    }

    private static String currentRegimeName(StrategyExecutionContext context) {
        if (context == null || context.marketContext() == null || context.marketContext().primaryRegime() == null) {
            return PrimaryMarketRegime.UNKNOWN.name();
        }
        return context.marketContext().primaryRegime().name();
    }

    private static List<String> skippedNames(Set<PrimaryMarketRegime> skippedRegimes) {
        if (skippedRegimes == null || skippedRegimes.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(PrimaryMarketRegime.values())
                .filter(skippedRegimes::contains)
                .map(PrimaryMarketRegime::name)
                .toList();
    }

    private static boolean isFilterable(PrimaryMarketRegime regime) {
        return regime != PrimaryMarketRegime.INSUFFICIENT_DATA
                && regime != PrimaryMarketRegime.UNKNOWN;
    }
}
