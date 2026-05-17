# Doflamingo V4 — Regression RCA & Tuning Plan

> **Audience.** Whoever owns the next iteration of `doflamingo-v4-strategy-packs`.
>
> **Trigger.** RunSet `20260517211242` (4 strategies × 18 instruments × M15 × 2y)
> shows **both V4 strategies catastrophically over-filtered** relative to their V3
> baselines on the same instruments and window. This doc is the RCA + relaxation plan.
>
> **TL;DR.** The V4 plan defaults were hand-waved estimates that should have been A/B
> tested individually before being shipped together. Codex implemented them all at once.
> The result is a multiplicative over-filter: 10+ new AND-stacked entry gates each
> rejecting 20-40% of setups retains only ~0.6%-25% of V3 emissions.
>
> **Implementation note.** Path A below is implemented in `doflamingo-v4-strategy-packs`.
> Section 3.5's split verdict is also applied: Ichimoku Momentum keeps the same
> strategy id with `STRATEGY_VERSION = "4.2.0"` and momentum-compatible defaults;
> Trend Reversal keeps the same strategy id at `STRATEGY_VERSION = "4.1.0"`. No
> platform changes were added.
>
> **Companion docs.**
> - `doflamingo-v4-ichimoku-momentum-improvement-plan.md` — original V4 Momentum plan + §0 implementation-status note.
> - `doflamingo-v4-trend-reversal-improvement-plan.md` — original V4 Trend Reversal plan.

---

## 1. Observed Regression (RunSet `20260517211242`)

| Strategy          | V3 trades | V4 trades | Δ%       | V3 avg total/run | V4 avg total/run | Δ%       | V3 win rate | V4 win rate |
|-------------------|-----------|-----------|----------|------------------|------------------|----------|-------------|-------------|
| Ichimoku Momentum | 3,163     | **925**   | **−71%** | 12.36%           | 1.25%            | **−90%** | 32.63%      | 30.81%      |
| Trend Reversal    | 3,648     | **67**    | **−98%** | 3.99%            | 0.17%            | **−96%** | 31.03%      | 34.33%      |

Same 17/18 instruments, same 2-year window, same M15 timeframe.

### Critical reads

- **V4 Trend Reversal: 67 trades across 17 runs over 2 years ≈ 0.06 trades per
  instrument-week**. The strategy is effectively not trading.
- **V4 Ichimoku: 925 trades for 1.25% average run return**. Almost 1/10th of V3's
  return per run. The few gates that fired didn't pick *better* trades — win rate
  barely moved (32.6% → 30.8%); largest win shrunk (4.42% → 2.73%).
- **Win rate ~unchanged** on both strategies. The gates aren't selecting higher-quality
  trades; they're just rejecting volume. **Filter without alpha gain = pure return drag.**

---

## 2. Root Cause — Multiplicative Filtering Across 10+ AND-Stacked Gates

V4 ships every entry gate from the original plan as a **default-on AND condition**. The
math:

If each gate independently rejects 20-40% of setups (reasonable; this is what gates do),
then `n` AND-stacked gates retain `(1 - rejectionRate) ^ n` of setups. With
`rejectionRate = 0.30`:

| n new gates | Retention | Example                                        |
|-------------|-----------|------------------------------------------------|
| 3           | 34%       | Trifecta of plan §5.1 (MACD/Stoch/ADAPTIVE)    |
| 5           | 17%       | + cooldown + structure-confirm                 |
| 7           | 8%        | + regime gate + session gate                   |
| 10          | 3%        | + volume + ATR-expansion + chikou              |
| 13          | 1%        | + HTF bias + anti-overextension + RSI lookback |

V4 Trend Reversal has ~13 new entry conditions stacked. The 67/3648 = 1.8% retention
rate matches the model **exactly**.

### Why the gates compound multiplicatively (and not additively)

Each gate is conditionally independent on a setup-by-setup basis but **strongly
correlated with bar quality** in aggregate. A "good" setup typically passes most gates;
a "bad" setup typically fails several. So a *single* gate filtering 30% would behave
well. But once you stack 10 gates each filtering 30% of *something different*, the
remaining setups must clear all 10 dimensions simultaneously — and the probability of
that drops geometrically.

### Why the win rate didn't improve

If the gates were genuinely selecting higher-alpha setups, we'd expect win rate to go
up materially (e.g., 32% → 45%). It didn't. This means **the gates are largely
filtering noise + signal indiscriminately**, not improving the quality of accepted
setups. The reduction is in count, not in quality.

---

## 3. Specific Defaults That Are Too Tight

### V4 Ichimoku — entry-gate defaults shipped (from `DoflamingoIchimokuMo002BetaV4StrategyProvider.java:110-180`)

| Parameter                    | V4 default                                                | Plan recommendation                                                                                | Problem                                                                                                                                                                                       |
|------------------------------|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `minKumoThicknessAtr`        | 0.25                                                      | 0.25                                                                                               | Reasonable threshold but it's an AND condition with several other thickness/spread checks; collectively too restrictive                                                                       |
| `minFutureCloudSpreadAtr`    | 0.20                                                      | 0.10                                                                                               | **Codex set 2× tighter than the plan**                                                                                                                                                        |
| `requireFutureCloudWidening` | true                                                      | true (STRICT_BETA only)                                                                            | Plan said this was STRICT-only; Codex applied it globally                                                                                                                                     |
| `requireChikouClearSpace`    | true                                                      | true (STRICT_BETA only)                                                                            | Same — Plan reserved this for STRICT mode; Codex made it global. **Biggest single emission drop driver.**                                                                                     |
| `tkCrossFreshBars`           | 5                                                         | 8                                                                                                  | **Codex shipped 40% tighter than the plan** — at M15 that's 75 minutes; many valid continuation setups fail it                                                                                |
| `maxEntryAtrFromCloudTop`    | 2.50                                                      | 2.50                                                                                               | Plan default — fine                                                                                                                                                                           |
| `htfCloudBiasMode`           | `ALIGN_WITH_TRADE` (hard)                                 | `REQUIRE_AGREEMENT` default but plan flagged this as "largest regime-fit improvement" with caution | Plan also documented `PREFER_AGREEMENT` (soft, confidence bonus rather than hard reject) as the safer default. Codex shipped the hard variant. **Major over-filter on choppy/range periods.** |
| `sessionGating`              | true (always-on)                                          | `SKIP_OPENING_15M` (excludes only 09:15-09:30 IST)                                                 | Codex's binary `sessionGating=true` is more restrictive than the plan's targeted opening-range skip                                                                                           |
| `volumeConfirmMultiple`      | 1.10                                                      | Plan didn't specify base-mode default; was only for EARLY_TRANSITION                               | Codex made it a global entry gate                                                                                                                                                             |
| `atrExpansionMultiple`       | 1.00                                                      | Plan only proposed for EARLY_TRANSITION at 1.15                                                    | Codex set 1.00 (require non-contracting ATR) as a global gate — affects every entry                                                                                                           |
| `skipMarketRegimes`          | `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | Same                                                                                               | Reasonable in isolation — but compounds with the other gates                                                                                                                                  |

### V4 Trend Reversal — entry-gate defaults shipped (from

`DoflamingoMultiIndicatorV6TrendReversalV4StrategyProvider.java:80-200`)

The trifecta (MACD zero-cross OR removed, Stoch threshold `< 20` not `< 30`,
`ADAPTIVE_CONFIRMATION` mode removed) **was correct and high-leverage**. Plan said this
alone should drop emissions 30-40%. That was accurate. The problem is the additional
10 gates layered on top of the trifecta:

| Parameter                     | V4 default                                                | Plan                                                     | Problem                                                                                                                                                    |
|-------------------------------|-----------------------------------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `trendFilterMode`             | STRICT                                                    | STRICT                                                   | Reasonable, but compounds                                                                                                                                  |
| `staleBars`                   | 12                                                        | 12                                                       | Reasonable                                                                                                                                                 |
| `staleMinR`                   | 0.40                                                      | 0.40                                                     | Reasonable                                                                                                                                                 |
| `cooldownBars`                | 4                                                         | 4                                                        | Reasonable                                                                                                                                                 |
| `structureExitConfirmBars`    | 2                                                         | 2                                                        | Reasonable                                                                                                                                                 |
| `volumeConfirmMultiple`       | 1.10                                                      | Plan E6: "default 1.0"                                   | Codex shipped 10% tighter                                                                                                                                  |
| `psarMinDistanceLongPct`      | 0.05                                                      | 0.05                                                     | Plan-aligned                                                                                                                                               |
| `requireRsiExtremeWithinBars` | 8                                                         | Plan E7: "default 5"                                     | **Codex shipped 60% tighter than the plan**                                                                                                                |
| `targetRMultiple`             | 2.50                                                      | 2.50                                                     | Reasonable (but combined with stale-exit at R=0.40, very few trades reach target)                                                                          |
| `sessionGating`               | true (always-on)                                          | `SKIP_OPENING_15M` only                                  | Same over-restriction as Ichimoku                                                                                                                          |
| `maxPortfolioDrawdownPct`     | 8.0                                                       | Not in plan — Codex added this as a circuit-breaker      | Adding more gates on top of an already-over-filtered strategy compounds the problem                                                                        |
| `riskFraction`                | 0.0075                                                    | 0.0075                                                   | Plan-aligned                                                                                                                                               |
| `skipMarketRegimes`           | `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | `[STRONG_TREND_HIGH_VOLATILITY, CHOPPY_HIGH_VOLATILITY]` | `CHOPPY_HIGH_VOLATILITY` doesn't exist in platform enum; Codex correctly mapped to `RANGING_HIGH_VOLATILITY` — noted in V4 doc §0. Acceptable substitution |

### Summary of Codex deviations from the plan (tightening direction)

| Defaults Codex set tighter than the plan                         | Strategy       | Impact                                        |
|------------------------------------------------------------------|----------------|-----------------------------------------------|
| `minFutureCloudSpreadAtr` 0.20 vs 0.10                           | Ichimoku       | 2× tighter                                    |
| `tkCrossFreshBars` 5 vs 8                                        | Ichimoku       | 40% tighter                                   |
| `requireFutureCloudWidening` global vs STRICT-only               | Ichimoku       | Always-on instead of mode-gated               |
| `requireChikouClearSpace` global vs STRICT-only                  | Ichimoku       | Always-on instead of mode-gated               |
| `htfCloudBiasMode` hard-required (no PREFER)                     | Ichimoku       | Lost the soft fallback                        |
| `sessionGating` blanket vs SKIP_OPENING_15M                      | Both           | Over-restricts midday/power-hour entries too  |
| `requireRsiExtremeWithinBars` 8 vs 5                             | Trend Reversal | 60% tighter                                   |
| `volumeConfirmMultiple` 1.10 vs 1.0 (base mode)                  | Trend Reversal | 10% tighter than plan suggested for base mode |
| `atrExpansionMultiple` 1.00 always-on (vs EARLY_TRANSITION only) | Ichimoku       | Global instead of mode-gated                  |

None of these individually are *wrong*. Together they're catastrophic.

---

## 3.5. V4.1 Calibration Result — Split Verdict (RunSet `20260517220546`)

V4.1 shipped (commit lands the default-relaxations in §5 Path A). Re-measured on
RunSet `20260517220546` (4 strategy configs × 18 instruments × M15 × 2y):

| Strategy            | Trades | Win rate | Avg total/run | Avg compounded/run | Avg PnL/trade | Largest W/L         | Overlap |
|---------------------|--------|----------|---------------|--------------------|---------------|---------------------|---------|
| Ichimoku V3         | 2,981  | 32.30%   | **11.62%**    | 12.11%             | 0.0624%       | +4.42% / −3.29%     | 4.02%   |
| Ichimoku V4.1       | 2,192  | 30.57%   | **4.14%**     | 4.36%              | 0.0302%       | **+4.42% / −3.29%** | 3.48%   |
| Trend Reversal V3   | 3,436  | 30.27%   | 1.55%         | 2.00%              | 0.0072%       | +5.78% / −2.71%     | 6.02%   |
| Trend Reversal V4.1 | 1,671  | 30.04%   | 1.51%         | 1.56%              | **0.0145%**   | +5.72% / **−2.38%** | 2.11%   |

### Verdict: Ichimoku Momentum V4.1 FAILED, Trend Reversal V4.1 WORKED

The same V4.1 default-relaxation produced **opposite outcomes** on the two strategies.
This is the key signal.

**Ichimoku Momentum V4.1 — selectively filters momentum entries**

- Trades −26% (2,981 → 2,192)
- Win rate −1.7pp (effectively flat)
- **Avg total/run −64% (11.62% → 4.14%)**
- Avg PnL per trade −52% (0.062% → 0.030%)
- **Largest win identical (4.42%) — same lucky extreme trades, but missing the explosive ones in between**
- Filter without alpha gain = pure return drag

**Trend Reversal V4.1 — clean tightening**

- Trades −51% (3,436 → 1,671)
- Win rate −0.2pp (effectively flat)
- **Avg total/run essentially unchanged (1.55% → 1.51%)**
- **Avg PnL per trade +101% — DOUBLED (0.0072% → 0.0145%)**
- Largest loss IMPROVED: −2.71% → −2.38% (-12% smaller worst loss)
- Overlap 6.02% → 2.11% (cooldown working: -65%)
- Same return with half the trades, double the per-trade quality, smaller worst losses
- **This is exactly what a v4 should do for a reversal strategy.**

### Why the same gates produce opposite outcomes — strategy archetype

V4.1 recovered most of V4's lost trade volume (Ichimoku 925 → 2,192 = ~2.4× lift,
73% of V3's count) but **avg return per run only recovered to 36% of V3** (4.14% vs
11.62%). And avg P&L per trade is **52% of V3's** (0.030% vs 0.062%).

### Why the same gates produce opposite outcomes — strategy archetype (continued)

The V4.1 keep-on gates (`maxEntryAtrFromCloudTop=2.5`, `minKumoThicknessAtr=0.25`) are
**reversal-mechanic gates**:

- They reject entries far from the cloud (anti-chase)
- They reject entries with thin cloud below (no defensive level)
- These are designed to make a strategy WAIT for a setup near a defended price level

For **Trend Reversal**, this is exactly the right archetype — the strategy's edge IS
catching reversals near defended levels, with the cloud as the structural floor.
Filtering chase entries + thin-cloud entries removes noise and keeps the genuine
setups. Result: same return, half the trades, double the per-trade quality. Working
as designed.

For **Ichimoku Momentum**, this is exactly the WRONG archetype — the strategy's edge
IS riding cloud breakouts where price extends away from the cloud. Anti-overextension
caps the entry distance, so the strategy can only catch slow/early breakouts. Thin-
cloud rejection blocks fresh breakouts where the cloud is still forming. Result:
volume drops 26%, the missing trades are disproportionately the high-momentum winners,
return drops 64%.

**Same code, opposite outcomes. The strategy archetype determines whether a gate is a
filter or a handicap.**

### The smoking gun for Momentum: `largestWinLoss` is identical

V4.1's largest win and largest loss are **exactly the same numbers as V3** (4.4225%
and −3.2919%). That's not coincidence — those are specific trades on specific bars
that **both V3 and V4.1 took identically**.

So V4.1 is catching the *same* lucky-extreme trades V3 catches — but it's missing
~26% of V3's trade volume, and the *missing trades disproportionately come from the
high-momentum bucket* (the average winner shrank from a V3-implied ~0.18% to ~0.10%
per trade).

**V4.1 is selectively filtering out exactly the momentum entries the strategy is
supposed to ride.**

### Why — `maxEntryAtrFromCloudTop = 2.50` is reversal-mechanic, hostile to momentum

Two gates remain default-on in V4.1 Ichimoku:

1. `minKumoThicknessAtr = 0.25` — rejects entries when cloud is too thin.
2. `maxEntryAtrFromCloudTop = 2.50` — rejects long entries when price is >2.5×ATR
   above the cloud ceiling.

The second one was the "anti-overextension" gate I proposed in the original Momentum
v4 plan (IK8) to address the RELIANCE −7.39% chase-entry hypothesis. **But strong
momentum trades are by definition entries where price is extending away from the
cloud.** The Ichimoku Momentum strategy's job is to detect a cloud breakout and ride
the move. A 2.5-ATR cap means the strategy can only enter slow / early breakouts,
never the fast/explosive ones that produce the V3 +12.36%/run returns.

The Kumo-thickness gate has the same problem in a different direction: a fresh
cloud-breakout often has a *thin* cloud below (cloud forms with a lag from price
action). Rejecting thin-cloud entries filters the freshest setups.

**These two gates are reversal mechanics, not momentum mechanics.** I lumped them
into "the two most defensible gates from the plan" in §5 Path A, but defensible is
not the same as appropriate-for-the-strategy.

### Fix: V4.2 — strategy-specific gate enable/disable

V4.1 Path A relaxed defaults uniformly across both strategies. V4.2 needs
**strategy-specific** defaults. The same gate can be the right default-on choice for
Trend Reversal and the wrong default-on choice for Ichimoku Momentum.

Implementation status: applied in `doflamingo-v4-strategy-packs` as the Ichimoku
provider's `4.2.0` defaults. Trend Reversal remains at `4.1.0`.

#### V4.2 Ichimoku Momentum — disable reversal-flavored gates

| Parameter                 | V4.1 default                | V4.2 default       | Reason                                                                         |
|---------------------------|-----------------------------|--------------------|--------------------------------------------------------------------------------|
| `maxEntryAtrFromCloudTop` | 2.50                        | **0.0 (disabled)** | Reversal mechanic; actively filters momentum entries the strategy should ride. |
| `minKumoThicknessAtr`     | 0.25                        | **0.10**           | Loosen to allow fresh cloud-breakouts where cloud is still forming.            |
| `minFutureCloudSpreadAtr` | 0.10                        | **0.05**           | Loosen — same logic, future cloud is also thin at breakout.                    |
| `tkCrossFreshBars`        | 8                           | **12**             | Loosen for slower/positional momentum entries.                                 |
| Everything else           | per V4.1 (most default-off) | per V4.1           | No change.                                                                     |

#### V4.2 Trend Reversal — KEEP V4.1 unchanged (working as designed)

Trend Reversal V4.1 already delivers the v4 program goal: same return as V3 with
half the trades, doubled per-trade quality, smaller worst losses, and significantly
reduced overlap. No changes needed — promote V4.1 to "stable" for this strategy.

**The reason these gates work for Trend Reversal but not for Momentum is structural:**
both `maxEntryAtrFromCloudTop` and `minKumoThicknessAtr` are designed to filter "chase
entries" and "weak-structure entries" — exactly the noise sources a reversal strategy
needs to remove, and exactly the alpha sources a momentum strategy needs to keep.

#### Expected V4.2 outcome (Ichimoku Momentum)

| Metric                  | V3             | V4.1           | V4.2 target                             |
|-------------------------|----------------|----------------|-----------------------------------------|
| Trades                  | 2,981          | 2,192          | ~2,700–2,900 (≥ 90% of V3)              |
| Win rate                | 32.3%          | 30.6%          | ≥ 32%                                   |
| Avg total/run           | 11.62%         | 4.14%          | ≥ 10% (≥ 85% of V3)                     |
| Avg PnL/trade           | 0.062%         | 0.030%         | ≥ 0.055% (≥ 90% of V3)                  |
| Largest win/loss        | 4.42% / −3.29% | 4.42% / −3.29% | ≥ V3 (the high-vol breakouts come back) |
| Per-instrument variance | 23pp           | (TBD)          | ≤ 25pp                                  |

If V4.2 outperforms V3 on either *variance* (target ≤22pp) or on RELIANCE
specifically (no instrument worse than −5%), the v4 mechanic-improvements were
worth shipping. If V4.2 just matches V3 with no variance benefit, **the whole v4
program for Ichimoku Momentum was a wash** and the strategy should fall back to v3
behavior with only the cooldown/structure-confirm-bars borrowed.

### Lesson — categorize every proposed gate by strategy archetype

The original Momentum v4 plan §9 proposed `maxEntryAtrFromCloudTop` under
"anti-overextension at entry — RELIANCE −7.39% hypothesis". The hypothesis was
correct (RELIANCE was a chase entry), but the *fix* I proposed was a reversal-style
gate applied to a momentum strategy. Should have been: "if anti-chase is needed for
this strategy, find a momentum-compatible expression (e.g., volume/ATR-decay check
inside the breakout bar, not absolute ATR distance from a backward-looking cloud)".

For every new gate in a strategy plan, classify it:

- **Momentum-compatible gates**: volume confirmation, ATR expansion, breakout magnitude relative to recent bars, fresh
  signal age
- **Reversal-compatible gates**: extreme-level proximity, mean-reversion confirmation, anti-chase distance caps,
  slow-moving structure alignment
- **Strategy-agnostic gates**: cooldown after exit, structure-exit-confirm-bars, time-stop

A gate that fits in the second bucket must NOT default-on for strategies in the first
bucket. This is the kind of categorization that was missing from the original V4
plans.

---

## 4. Process Failure — All Gates Shipped At Once Without Per-PR Measurement

The original V4 plans both included explicit implementation-order sections:

> **`doflamingo-v4-trend-reversal-improvement-plan.md` §9:**
> Each step is independently shippable. Verify with the existing fixture tests after
> each, plus a new comparison run against RunSet `20260516203807`'s 13 instruments.
> PRs 1–5 are pure code edits with no SPI/runtime dependencies and no calendar data —
> **ship those first; measure delta on the same 13-instrument RunSet.**

> **`doflamingo-v4-ichimoku-momentum-improvement-plan.md` §16:**
> Each PR is independently shippable; verify on RunSet `20260516203807`'s 13-instrument
> set after each. **PRs IK1, IK2, IK3 are the highest-leverage. Ship those first;
> measure delta before continuing.**

Codex shipped all gates in a single PR (commit `5336cbe Add Doflamingo V4 strategy
samples`). No per-gate A/B measurement was performed. The aggregate over-filtering is
a direct consequence of skipping the per-PR measurement loop.

**This is the meta-failure.** Even if every individual default was correct, shipping
10 changes without measurement is structurally unsafe for strategy tuning.

---

## 5. Recommended Tuning Plan (V4.1)

Two paths forward. Recommend **Path A** for speed.

### Path A — Relax defaults, ship a v4.1 calibration (recommended, 1-2 day fix)

Same strategy IDs (`doflamingo-ichimoku-mo-002-beta-v4` /
`doflamingo-multi-indicator-v6-trend-reversal-v4`) bump `STRATEGY_VERSION` to `4.1.0`.
Apply the following default changes; **everything else stays at v4 defaults**.

#### V4.1 Ichimoku — default relaxations

| Parameter                    | V4                                                        | V4.1               | Reason                                                                         |
|------------------------------|-----------------------------------------------------------|--------------------|--------------------------------------------------------------------------------|
| `minFutureCloudSpreadAtr`    | 0.20                                                      | **0.10**           | Plan default; Codex set 2× tighter                                             |
| `tkCrossFreshBars`           | 5                                                         | **8**              | Plan default; Codex set 40% tighter                                            |
| `requireFutureCloudWidening` | true                                                      | **false**          | Most aggressive default-off                                                    |
| `requireChikouClearSpace`    | true                                                      | **false**          | Most aggressive default-off (single biggest emission driver)                   |
| `htfCloudBiasMode`           | `ALIGN_WITH_TRADE`                                        | **`OFF`**          | Disable HTF-bias by default until per-instrument data shows it helps           |
| `atrExpansionMultiple`       | 1.00                                                      | **0.0 (disabled)** | Plan only proposed this for EARLY_TRANSITION mode; Codex made it global        |
| `volumeConfirmMultiple`      | 1.10                                                      | **0.0 (disabled)** | Same — plan was EARLY_TRANSITION only                                          |
| `skipMarketRegimes`          | `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | `[]` (empty)       | Disable regime gating until A/B confirms it helps                              |
| `sessionGating`              | true                                                      | **false**          | Disable session gating until a more targeted opening-range skip is implemented |
| `minKumoThicknessAtr`        | 0.25                                                      | 0.25 (unchanged)   | Keep; this is the cleanest gate                                                |
| `maxEntryAtrFromCloudTop`    | 2.50                                                      | 2.50 (unchanged)   | Keep; targets the RELIANCE -7.39% failure mode                                 |

This effectively keeps **2 new gates** (Kumo thickness, anti-overextension) — the two
most defensible ones from the plan — and turns off everything else.

#### V4.1 Trend Reversal — default relaxations

| Parameter                                                                                                                                                                          | V4                                                        | V4.1                   | Reason                                                                       |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|------------------------|------------------------------------------------------------------------------|
| `requireRsiExtremeWithinBars`                                                                                                                                                      | 8                                                         | **0 (disabled)**       | Plan suggested 5 but in retrospect even that's restrictive; disable for v4.1 |
| `volumeConfirmMultiple`                                                                                                                                                            | 1.10                                                      | **0.0 (disabled)**     | Disable per Ichimoku pattern above                                           |
| `maxPortfolioDrawdownPct`                                                                                                                                                          | 8.0                                                       | **0.0 (disabled)**     | Not in plan — Codex added; disable until A/B confirms                        |
| `sessionGating`                                                                                                                                                                    | true                                                      | **false**              | Same as Ichimoku                                                             |
| `skipMarketRegimes`                                                                                                                                                                | `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | `[]` (empty)           | Same as Ichimoku                                                             |
| `psarMinDistanceLongPct`                                                                                                                                                           | 0.05                                                      | 0.05 (unchanged)       | Keep; cheap parity with short side                                           |
| Trifecta (MACD zero-cross OR / Stoch `< 20` / ADAPTIVE_CONFIRMATION removed)                                                                                                       | all in                                                    | **all in (unchanged)** | These were the high-leverage entry tightening per plan                       |
| `trendFilterMode`                                                                                                                                                                  | STRICT                                                    | STRICT (unchanged)     | Keep                                                                         |
| `cooldownBars` 4 / `structureExitConfirmBars` 2 / `staleBars` 12 / `staleMinR` 0.40 / `targetRMultiple` 2.5 / `scaleOutAtR` 1.25 / `scaleOutFraction` 0.40 / `riskFraction` 0.0075 | all in                                                    | **all in (unchanged)** | Keep — these are exit/lifecycle changes, not entry filters                   |

This keeps the **entry-tightening trifecta** (the biggest single emission-drop driver
per the plan) plus the exit/lifecycle tightening — and turns off everything else.

#### Expected V4.1 outcome

| Strategy          | V3 trades | V4 trades  | V4.1 target (estimate)      |
|-------------------|-----------|------------|-----------------------------|
| Ichimoku Momentum | 3,163     | 925 (-71%) | ~2,200-2,500 (-25% from V3) |
| Trend Reversal    | 3,648     | 67 (-98%)  | ~2,300-2,800 (-25% from V3) |

The target range is "25% tighter than V3" — the original plan goal of "15-25% tighter
for Ichimoku, 30-40% tighter for Trend Reversal trifecta". Net return should recover
to within 10% of V3 baseline at minimum, with the Kumo-thickness + anti-overextension
gates providing the targeted RELIANCE-style downside protection.

### Path B — Per-gate A/B sweep (rigorous, 1-2 weeks)

For each new V4 gate, run a 2-leg RunSet on the same 17 instruments:

- Leg A: V4.1 base (per Path A relaxations) — gate disabled
- Leg B: V4.1 + this one gate enabled

For each gate, the acceptance criteria:

- Intent count drops < 30% vs Leg A
- Win rate improves ≥ 3pp absolute, OR per-instrument variance shrinks ≥ 3pp
- No instrument's net return drops more than 2pp vs Leg A

Keep only gates that pass acceptance. Document each A/B result inline in the strategy
provider Javadoc.

This produces a defensible v4.2 release with calibrated defaults.

---

## 6. Process Recommendations (Going Forward)

1. **Never ship more than 3 new entry gates in a single strategy PR without per-gate
   backtest measurement.** Multiplicative filtering effects are not visible from code
   review; only A/B testing reveals them.
2. **Every new entry gate must default to off until A/B-validated.** Defaults flip to
   `true` only after a successful A/B run is documented in the provider Javadoc.
3. **The strategy plan doc's "Implementation order" sections are not advisory — they
   are the validation contract.** Plans say "ship PR-1, measure, then PR-2"; doing
   otherwise produces compound-effect regressions.
4. **A "trade count vs win rate" sanity check** belongs in every strategy PR's
   acceptance criteria. A 70% drop in trades with no win-rate improvement means the
   gates aren't selecting; they're rejecting indiscriminately.

---

## 7. Update To V4 Plan Docs

Both companion plan docs (`doflamingo-v4-ichimoku-momentum-improvement-plan.md` and
`doflamingo-v4-trend-reversal-improvement-plan.md`) should add a §0 banner pointing at
this RCA:

> **Implementation status (2026-05-17 RunSet `20260517211242`):** V4 ships with all
> entry gates enabled by default, producing catastrophic over-filtering (-71% trades
> on Ichimoku, -98% on Trend Reversal, -90% / -96% on net return). See
> `doflamingo-v4-regression-rca.md` for the RCA and the V4.1 default-relaxation plan.

The original strategy-mechanic findings in those docs remain valid; only the **default
on/off** decisions need revisiting.

---

## 8. Open Questions

1. **Was the V4 RunSet `20260517211242` configured identically to V3 RunSet
   `20260516203807`?** Both should be same 13-17 NSE EQ instruments, same M15
   timeframe, same 2-year window. If the V4 RunSet was actually shorter or smaller, the
   over-filter conclusion needs adjustment. *(Spot-check via the RunSet header in
   Chrome — RunSet `20260517211242` reads "4 strategy configurations across 18
   instruments, M15 2024-05-17 to 2026-05-17". Confirmed equivalent.)*
2. **Did Codex modify any V3 strategy code by accident?** Spot-check via
   `git diff origin/main -- atx-strategy-samples/doflamingo-v3-strategy-packs/`. If
   V3 is unchanged (per commit history — `5336cbe` only adds the v4 module), the V3
   numbers in §1 are valid V3-only baseline.
3. **Win-rate didn't improve materially under V4 gates.** Two possible interpretations:
    - (a) The gates are filtering randomly (no alpha selection).
    - (b) The gates are filtering correctly but evaluation is gated by a downstream
      mechanism (e.g., the runtime's `TradeIntentEntry.marketNextOpen()` introduces
      1-bar slippage that swamps the gate alpha).
      Path B A/B sweep would distinguish these.
