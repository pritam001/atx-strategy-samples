# Doflamingo Ichimoku Momentum V4 — Improvement Plan

> **Audience.** Whoever picks up the next iteration of the
> `doflamingo-ichimoku-mo-002-beta-*` strategy.
>
> **Goal.** Ichimoku Momentum V3 is already the comparative outperformer of the v3 pair
> (+34.56% vs Trend Reversal's +27.83% on RunSet `20260516203807`). This plan identifies
> **Ichimoku-mechanic** opportunities to widen the gap further, address the single big
> loser (RELIANCE −7.39%), and compress per-instrument variance from 23pp → 18pp.
>
> **Scope distinction.** Several cross-cutting v4 improvements are documented as
> **lift-backs** in the companion `doflamingo-v4-trend-reversal-improvement-plan.md`
> §8. The shipped V4 implements the supported subset: session gating, target-R metadata,
> and shared confidence/regime filtering. F&O expiry skip, `maxConsecutiveLosses`, and
> earnings calendar opt-in remain platform gaps.
>
> **Method.** Read v3 strategy (797 lines) + provider (240 lines) + `DoflamingoIndicatorMath`
> Ichimoku math (`spanA`, `spanB`, `presentSpan*`, `futureSpan*`, `conversionLine`, `baseLine`)
> + cross-check against `atx-platform-core/docs/strategy-author-guide.md` and
> `atx-design-docs/planning/atx-custom-strategy-plan.md` §3.1 (Trend Trading blueprint).
>
> **Status.** V4 is implemented as a new strategy-samples module,
> `doflamingo-v4-strategy-packs`. The original v3 module remains unchanged. This doc
> keeps the Ichimoku review evidence and records the supported v4 subset plus platform
> gaps.
>
> **Companion docs.**
> - `doflamingo-v4-trend-reversal-improvement-plan.md` (this repo) — cross-cutting lift-backs in §8.
> - `atx-platform-core/docs/strategy-author-guide.md` — canonical SPI; especially §7.3 (multi-timeframe context).
> - `atx-design-docs/planning/atx-custom-strategy-plan.md` §3.1 — Trend Trading blueprint with H1 + D context-tf
  recommendation.

---

## 0. Regression Status And V4.2 Calibration

**Implementation status (2026-05-17 RunSet `20260517211242`):** V4 shipped with all
entry gates enabled by default, producing catastrophic over-filtering (-71% trades on
Ichimoku, -98% on Trend Reversal, -90% / -96% on net return). See
`doflamingo-v4-regression-rca.md` for the RCA, V4.1 default-relaxation plan, and
V4.2 split-verdict calibration.

The original strategy-mechanic findings in this doc remain valid; only the default
on/off decisions were revisited. V4.2 keeps the same strategy id and uses
momentum-compatible defaults so reversal-flavored gates remain opt-in where they have
not been A/B validated for this archetype.

## 0.1. Current Implementation Status And Platform Gaps

Implemented module:

- `doflamingo-v4-strategy-packs/`
- Provider id: `doflamingo-v4-strategy-packs`
- Strategy id: `doflamingo-ichimoku-mo-002-beta-v4`
- Strategy version: `4.2.0`
- V3 strategy/provider code was not modified.

Implemented within the current strategy SPI:

- H1 context declaration and configurable H1 cloud-bias gate. The V4.2 default is
  `OFF`; `ALIGN_WITH_TRADE` remains available as an explicit opt-in.
- present-cloud thickness, future-cloud spread, future-cloud widening, Chikou
  clear-space, TK freshness, anti-overextension, early-transition volume, ATR
  expansion, deterministic session gate, and RR target metadata. V4.2 relaxes Kumo
  thickness to `0.10`, future spread to `0.05`, TK freshness to `12`, and
  default-disables anti-overextension with `maxEntryAtrFromCloudTop=0.0`. Future
  widening, Chikou clear-space, H1 bias, early-transition volume, ATR expansion,
  session gating, and regime skipping also remain default-off until A/B data supports
  re-enabling them.

Platform gaps not added to platform:

- `CHOPPY_HIGH_VOLATILITY` is not a supported
  `PrimaryMarketRegime`. The current platform enum models choppy/range behavior as
  `RANGING_*`, so v4 maps the planned `CHOPPY_HIGH_VOLATILITY` skip to
  `RANGING_HIGH_VOLATILITY`.
- `earningsCalendarRef` / file-backed event calendar was not implemented. Strategy
  plugins should not read arbitrary local files during replay unless the platform
  provides a replay-safe data-source contract.
- True stop mutation, break-even-after-scaleout, runtime trailing-stop modification,
  and closed-trade consecutive-loss tracking are not available from the current
  strategy hooks. V4 emits explicit exits where possible and does not mutate active
  runtime stops.
- Exact holiday-adjusted expiry gating is not available as a platform trading-calendar
  facility. V4 uses deterministic regular-session gating only.

Decision: keep these as platform asks. Do not add strategy-local platform substitutes
that would make replay behavior depend on local files or non-authoritative calendars.

## 1. Why Ichimoku V3 Still Has v4 Work To Do

Ichimoku V3 is the outperformer, but it isn't problem-free:

| Metric (RunSet `20260516203807`, 2y NSE EQ M15) | Ichimoku V3                      | v4 target                            |
|-------------------------------------------------|----------------------------------|--------------------------------------|
| Net return                                      | +34.56%                          | ≥ +34.56% (no regression — critical) |
| Intent count                                    | 4,888                            | 3,700–4,150 (15–25% tighter gates)   |
| Positions completed                             | 2,516                            | similar                              |
| Per-instrument range                            | -7.39% to +15.35% (~23pp spread) | -4.0% to +15% (~18pp spread)         |
| Single big loser                                | RELIANCE −7.39%                  | None worse than -4%                  |

The −7.39% RELIANCE outcome plus the 23pp variance suggest **three structural gaps**
that have nothing to do with Trend Reversal's over-emission problem:

1. **No anti-overextension gate** — the strategy enters strong-trend large-caps when
   price is already 3-5 ATR above the cloud (chase entries).
2. **Missing classical Ichimoku confirmations** — Chikou span, Kumo thickness as a
   gate, future-cloud spread/slope as a graded measure rather than binary green/red.
3. **No multi-timeframe context** — descriptor declares no `requiredContextTimeframes`;
   the M15 strategy is entirely M15-myopic, contradicting §3.1 of the strategy plan
   which calls for `[H1, D]` context.

The rest of this doc proposes concrete Ichimoku-mechanic v4 changes that address each.

---

## 2. Indicator Parameters — Textbook Defaults, Not Over-Fit

`DoflamingoIndicatorMath.java:20-23` declares the standard Ichimoku tuple as **constants**:

```
ICHIMOKU_CONVERSION_PERIOD  = 9
ICHIMOKU_BASE_PERIOD        = 26
ICHIMOKU_SPAN_B_PERIOD      = 52
ICHIMOKU_DISPLACEMENT       = 26
```

These are not parameters — they cannot be fitted via descriptor. Good. **Keep them
constants in v4 too.** Document this explicitly in the v4 provider Javadoc so future
authors don't try to "tune" them. The right place for v4 knobs is on the *features
derived from the snapshot* (thickness, spread, slope, distance, freshness), not the
lookbacks themselves.

---

## 3. Kumo (Cloud) Thickness — Computed But Never Used As A Gate

**v3 reference.** `Strategy.java:696-700` computes a thickness proxy *inside the
confidence function* only:

```java
double cloudRange = Math.max(1.0d, Math.abs(ichimoku.presentSpanB() - ichimoku.presentSpanA()));
double distanceFromSupport = Math.max(0.0d, close - ichimoku.presentSpanB());
if(distanceFromSupport <=cloudRange *2.0d){score +=0.08d;}
```

Thickness is used to anchor a distance-from-support *bonus*. It never gates entry or
sizes risk.

Classical Ichimoku: a **thin Kumo** = weak structural support → trade lacks defensive
floor. A **thick Kumo** = strong wall → trade has real invalidation level.

### Proposal (v4-IK1)

| Parameter                 | Default             | Range                                | Rationale                                                                                                                   |
|---------------------------|---------------------|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `minKumoThicknessAtr`     | `0.25`              | `0..3.0`                             | Reject longs when `(spanA − spanB) / ATR < 0.25`; mirror for shorts. The thin-cloud entries are the −7.39% RELIANCE family. |
| `kumoThicknessSizingMode` | `LINEAR_UP_TO_1ATR` | `[NONE, LINEAR_UP_TO_1ATR, STEPPED]` | Scale `riskFraction` by `min(1.0, thicknessAtr / 1.0)`. Mechanically caps risk in thin-cloud regimes.                       |

---

## 4. Future-Cloud — Binary Green/Red Throws Away Information

**v3 reference.** `Strategy.java:199` reduces forward kumo to a single boolean:

```java
boolean futureGreenCloud = ichimoku.futureSpanA() > ichimoku.futureSpanB();
```

`Strategy.java:684-686` then gives a flat `+0.08` confidence bonus for any green. A
barely-green future kumo and a thick widening one print identically.

Two missed signals:

- **Spread**: `(futureSpanA − futureSpanB) / close × 100` proxies projected trend strength.
- **Slope**: `futureSpanA(t) − presentSpanA(t)` measures widening (accelerating) vs narrowing (decelerating).

### Proposal (v4-IK2)

| Parameter                    | Default                                                                               | Range    | Rationale                                                                                                          |
|------------------------------|---------------------------------------------------------------------------------------|----------|--------------------------------------------------------------------------------------------------------------------|
| `minFutureCloudSpreadPct`    | `0.10`                                                                                | `0..1.0` | Reject when `                                                                                                      |fSpanA − fSpanB| / close × 100 < 0.10`. |
| `requireFutureCloudWidening` | `true` (STRICT_BETA), `false` (EARLY_TRANSITION)                                      | bool     | Require `futureSpanA > presentSpanA` on long: leading edge of green cloud is *rising*, not just sitting above red. |
| Confidence grading           | Replace `+0.08` flat with a graded function of normalized spread, capping at `+0.12`. |

---

## 5. Chikou Span — Never Computed (Biggest Classical-Ichimoku Gap)

**v3 reference.** The `IchimokuSnapshot` record at `DoflamingoIndicatorMath.java:521-523`
exposes six fields — `conversionLine, baseLine, presentSpanA, presentSpanB,
futureSpanA, futureSpanB` — and **no chikou**.

Chikou = current close shifted back 26 bars. Classical confirmation requires Chikou
in *clear space* (above price 26 bars ago for longs; no overlap with the candles it
shadows). Roughly a third of false-positive Ichimoku breakouts are eliminated by a
Chikou-clear-space filter — the lagging span sitting *inside* prior price bars is the
hallmark of a chase entry on a stock that's been in a sideways range.

### Proposal (v4-IK3) — single highest-leverage Ichimoku upgrade

1. Add `chikou` to `IchimokuSnapshot` (additive — doesn't break v3 consumers). Value =
   the close *at the bar 26 bars in the past whose shadow lands at the current bar*.
   Concretely: at index `i`, Chikou compares `bars[i].close` to `bars[i-26].high/low`.
2. Add gate (long): `bars[currentIndex].close() > bars[currentIndex - 26].high()` —
   the lagging span clears the high of the bar it shadows. Mirror low for shorts.
3. Add second gate (STRICT_BETA only, optional): no candle body in the last
   `displacement` bars closes within ±0.5 × ATR of the Chikou trajectory — Chikou
   sits in clear air.
4. Parameter `requireChikouClearSpace BOOLEAN default=true` (STRICT_BETA), `false`
   (EARLY_TRANSITION).

---

## 6. Tenkan-Kijun Cross — Level Only, Not Slope Or Freshness

**v3 reference.** `Strategy.java:200`:

```java
boolean conversionAboveBase = ichimoku.conversionLine() > ichimoku.baseLine();
```

Static comparison. Passes every bar of every uptrend — even when the cross happened 40
bars ago and the trade is now a chase.

### Proposal (v4-IK4) — TK cross freshness

| Parameter           | Default | Range   | Rationale                                                                                                                                                                                                   |
|---------------------|---------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `maxTkCrossAgeBars` | `8`     | `0..50` | Require the most recent `conversion crossed-up base` event to have occurred within 8 closed bars (~2h on M15). Maintain a back-scan in `DoflamingoIndicatorMath` returning the bar index of the last cross. |

### Proposal (v4-IK5) — TK cross slope

| Parameter              | Default | Range    | Rationale                                                                                                                                                        |
|------------------------|---------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `minTkSlopePctAtCross` | `0.05`  | `0..5.0` | At the cross bar, require `(conversion(t) − conversion(t-3)) / close × 100 ≥ 0.05`. Flat crosses (both lines drifting sideways through each other) are filtered. |

Mirror symmetrically for short entries.

---

## 7. EARLY_TRANSITION Mode Is Too Easy

**v3 reference.** `Strategy.java:211-217`:

```java
boolean earlySetup = earlyCloseAboveSpanB
        && futureGreenCloud
        && conversionAboveBase
        && trendAboveAverage
        && earlyBreakout;   // close > prior 5-bar high
```

Five conditions but four are also true in mid-trend chase setups. The only thing that
makes EARLY a *transition* (not a continuation) is the prior-5-bar-high breakout — and
on M15 that's noisy. v3 partially compensates with a `-0.04` confidence penalty
(line 711-713) — small constant; not enough.

### Proposal (v4-IK6) — strengthen EARLY_TRANSITION gates

| Parameter                             | Default | Range      | Rationale                                                                                                                                                                                                                    |
|---------------------------------------|---------|------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `earlyTransitionVolumeMultiple`       | `1.3`   | `0.5..3.0` | Require current bar volume ≥ `1.3 × SMA20(volume)`. EARLY mode without volume confirmation is the canonical false-breakout setup.                                                                                            |
| `earlyTransitionAtrExpansionMultiple` | `1.15`  | `0.5..3.0` | Require current bar `(high − low) ≥ 1.15 × ATR14`. Filters compressed range bars that statistically close back below the prior high.                                                                                         |
| `requireKumoTwistRecentBars`          | `8`     | `0..50`    | For EARLY_TRANSITION only, require a *future-cloud color flip* (`futureSpanA × futureSpanB` ordering reversed) within the last 8 closed bars. This is what makes it a real transition entry, not a continuation in disguise. |

---

## 8. No Cloud-Edge Trailing Stop — Initial Stop Is Fire-And-Forget

**v3 reference.** `Strategy.java:586-606` builds a one-shot `stopPercent` from
`min(spanB, baseLine) - buffer` and hands it to `TradeIntentExitPolicy.percentStop`.
There's no mechanism to *trail along the rising cloud floor* as the trade progresses.

As price runs, the cloud usually rises behind it — that rising cloud floor is the
Ichimoku-native protective stop. v3 throws it away after entry.

### Proposal (v4-IK7) — cloud-edge trailing

| Parameter           | Default | Range | Rationale                                                                                                                                                                                                                                    |
|---------------------|---------|-------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cloudEdgeTrailing` | `true`  | bool  | On each bar of an open long, recompute `cloudFloor = min(presentSpanA, presentSpanB, baseLine) − cloudStopBufferPct%`. Emit explicit `EXIT_LONG` when `close < cloudFloor` (with `structureExitConfirmBars=2` smoothing). Mirror for shorts. |

This is **additive** to the §8 lift-back target-R exit:

- Target-R protects against giveback on flat-top winners.
- Cloud-edge trail protects against decay on slow-bleed winners.

Until FA-RISK-03 lands (in-place stop modification), the implementation emits an
explicit `EXIT_*` intent rather than mutating the active stop.

---

## 9. Anti-Overextension At Entry — RELIANCE −7.39% Hypothesis

**Hypothesis grounded in v3 code.** RELIANCE is a structural strong-trend large-cap.
The strict gates at `Strategy.java:203-209` all pass *most easily* when price is
*already extended* far above the cloud — exactly the chase scenario. `Strategy.java:228-231`
calls `confidence(...)` which at line 697-700 *rewards* `close − spanB ≤ 2 × cloudRange`
but does **not reject** the inverse case. Price 4–5× cloud-range above Span B still
enters; it just doesn't get the bonus.

Expected RELIANCE failure pattern: entries clustered at bars where (a) cloud was thin
(no support), (b) `(close − spanB) / ATR > 3` (extended), (c) price reverted to mean
within ~3 bars and took out the cloud stop.

### Proposal (v4-IK8) — anti-overextension gate

| Parameter                 | Default | Range   | Rationale                                                                                                                                               |
|---------------------------|---------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `maxEntryAtrFromCloudTop` | `2.5`   | `0..10` | Reject longs when `(close − max(presentSpanA, presentSpanB)) / ATR > 2.5`. Classic "no chasing" Ichimoku rule. Symmetric for shorts using `cloudFloor`. |

Combined with §3 thickness gate and §4 minimum-spread gate, this **triple-locks**
against the RELIANCE failure family.

---

## 10. No Multi-Timeframe Context

**v3 reference.** Descriptor at `Provider.java:140-169` has no `requiredContextTimeframes`
declaration. The M15 strategy is entirely M15-myopic.

`atx-custom-strategy-plan.md` §3.1 (line 219) explicitly recommends
`requiredContextTimeframes: [H1, D]` for Trend Trading, and §3.1 setup logic gates on
`marketContext(higherTfBias).trendStrength`. `strategy-author-guide.md` §7.3 (line 206)
confirms `context.history("H1")` works only if declared in `requiredContextTimeframes`.

### Proposal (v4-IK9) — H1 cloud bias gate

1. Add `requiredContextTimeframes = ["H1"]` to the v4 descriptor.
2. Compute the H1 Ichimoku snapshot from `context.history("H1")`.
3. New parameter:

| Parameter          | Default             | Values                                        | Rationale                                                                                                                                                                                                                            |
|--------------------|---------------------|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `htfCloudBiasMode` | `REQUIRE_AGREEMENT` | `[NONE, PREFER_AGREEMENT, REQUIRE_AGREEMENT]` | `REQUIRE_AGREEMENT` for longs: H1 `close > max(presentSpanA, presentSpanB)` AND H1 `futureSpanA > futureSpanB`. Reject if either fails. `PREFER_AGREEMENT`: `+0.10` confidence when both hold; `-0.05` otherwise, gate doesn't fail. |

This is the **single largest regime-fit improvement**; directly addresses the 23pp
per-instrument spread by refusing M15 longs when H1 is bearish. Mirror for shorts.

---

## 11. Forward-Cloud Twist As An Exit Signal

**v3 reference.** `Strategy.java:394-400` exit logic is entirely *present-cloud* /
present-base/conversion based — no forward-looking exit:

```java
boolean spanAOverHigh = ichimoku.presentSpanA() > current.ohlcv().high().doubleValue();
boolean closeBelowSpanB = close < ichimoku.presentSpanB();
boolean conversionBelowBase = ichimoku.conversionLine() < ichimoku.baseLine();
boolean emaBelowSpanA = ema9 < ichimoku.presentSpanA();
```

A **Kumo twist** — `futureSpanA crossing futureSpanB` — is the leading-edge regime-
change signal in Ichimoku. By the time `closeBelowSpanB` fires, the trade has typically
given back 1R+ of its high-water mark.

### Proposal (v4-IK10) — forward-twist exit

| Parameter                | Default | Range | Rationale                                                                                                                                                                                                                                    |
|--------------------------|---------|-------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `enableForwardTwistExit` | `true`  | bool  | Track prior bar's `futureGreenCloud` boolean as instance state. On flip green→red (long) or red→green (short), emit exit candidate. Require `structureExitConfirmBars=2` of confirmed flip. Strictly additive via OR to existing exit logic. |

Most valuable on slow-trend instruments where the present-cloud lags reversals badly.

---

## 12. Flat-Cloud Collapse — Mid-Trade Structural Evaporation

When `|spanA − spanB|` is near zero for a sustained window, neither side has structural
support; trades degrade to noise. §3 `minKumoThicknessAtr` gate addresses *entry* but
the strategy still **holds** trades through cloud collapses.

### Proposal (v4-IK11) — cloud-collapse exit

| Parameter              | Default | Range    | Rationale                                                                                                                                                     |
|------------------------|---------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cloudCollapseExitAtr` | `0.10`  | `0..0.5` | For an open position, emit exit when `kumoThicknessAtr < 0.10` for `structureExitConfirmBars` consecutive bars. The trade thesis has structurally evaporated. |

### Seasonal Indian-equity volatility note

Monsoon (Jun-Aug) and summer (Mar-May) routinely double ATR on large-caps vs autumn
baselines. Because all proposed thickness thresholds (`minKumoThicknessAtr`,
`cloudCollapseExitAtr`) are **ATR-normalized rather than raw-price**, they are
automatically robust to seasonal vol shifts. **Call this out explicitly in the v4
Javadoc** so the choice is documented and future authors don't replace ATR-normalized
thresholds with raw-price ones.

---

## 13. Defaults Summary — v3 vs v4

| Parameter                                              | v3 default             | v4 default                                                | Source |
|--------------------------------------------------------|------------------------|-----------------------------------------------------------|--------|
| `conversionPeriod/basePeriod/spanBPeriod/displacement` | 9/26/52/26 (constants) | unchanged                                                 | §2     |
| `minKumoThicknessAtr`                                  | n/a                    | `0.25`                                                    | §3     |
| `kumoThicknessSizingMode`                              | n/a                    | `LINEAR_UP_TO_1ATR`                                       | §3     |
| `minFutureCloudSpreadPct`                              | n/a                    | `0.10`                                                    | §4     |
| `requireFutureCloudWidening`                           | n/a                    | `true` (STRICT_BETA)                                      | §4     |
| `chikou` field on snapshot                             | n/a                    | added                                                     | §5     |
| `requireChikouClearSpace`                              | n/a                    | `true` (STRICT_BETA)                                      | §5     |
| `maxTkCrossAgeBars`                                    | n/a                    | `8`                                                       | §6     |
| `minTkSlopePctAtCross`                                 | n/a                    | `0.05`                                                    | §6     |
| `earlyTransitionVolumeMultiple`                        | n/a                    | `1.3`                                                     | §7     |
| `earlyTransitionAtrExpansionMultiple`                  | n/a                    | `1.15`                                                    | §7     |
| `requireKumoTwistRecentBars`                           | n/a                    | `8`                                                       | §7     |
| `cloudEdgeTrailing`                                    | n/a                    | `true`                                                    | §8     |
| `maxEntryAtrFromCloudTop`                              | n/a                    | `2.5`                                                     | §9     |
| `requiredContextTimeframes` (descriptor)               | `[]`                   | `["H1"]`                                                  | §10    |
| `htfCloudBiasMode`                                     | n/a                    | `REQUIRE_AGREEMENT`                                       | §10    |
| `enableForwardTwistExit`                               | n/a                    | `true`                                                    | §11    |
| `cloudCollapseExitAtr`                                 | n/a                    | `0.10`                                                    | §12    |
| `cooldownBars`                                         | `3`                    | unchanged (Ichimoku already had it)                       | —      |
| `structureExitConfirmBars`                             | `2`                    | unchanged                                                 | —      |
| (Lift-backs covered separately)                        | —                      | see `doflamingo-v4-trend-reversal-improvement-plan.md` §8 | —      |

---

## 14. Backwards-Compat Decision

**Recommendation: clean break — new strategy ID
`doflamingo-ichimoku-mo-002-beta-v4`**. The initial implementation shipped as
`STRATEGY_VERSION = "4.0.0"`; the V4.1 default calibration used
`STRATEGY_VERSION = "4.1.0"`; the split-verdict V4.2 calibration now uses
`STRATEGY_VERSION = "4.2.0"` with the same strategy id.

Reasons identical to the Trend Reversal v4 plan §6:

1. ≥11 new parameters + `requiredContextTimeframes` declaration change = silent
   behavior break for anyone with stored v3 RunSet configs.
2. New parameters and planned calendar hooks carry IST / calendar dependencies that
   don't belong silently bolted onto v3. Current v4 ships the supported `sessionGating`
   subset and leaves expiry / earnings hooks as platform gaps.
3. Keep v3 in the catalogue as the **comparative baseline** — required to verify v4
   outperformance on the same RunSet.

### Module layout

Implemented layout:

- New module: `doflamingo-v4-strategy-packs/`
- Parent reactor includes both `doflamingo-v3-strategy-packs` and
  `doflamingo-v4-strategy-packs`
- V4 has its own provider classes, helper classes, tests, and META-INF service entry
- V3 remains available as the comparative baseline and its files are unchanged

The same v4 module houses the parallel Trend Reversal v4 work (see companion plan doc).
Both v4 strategies share the new helper classes for supported cross-cutting concerns
such as session gating, regime filtering, indicator math, chart studies, and intent
construction. Expiry calendars, file-backed earnings calendars, true stop mutation, and
closed-trade loss streaks are not implemented because they need platform support.

---

## 15. Test Strategy For v4

Per `strategy-author-guide.md` §1 (GoldenRunFixtures loop), add **Ichimoku-mechanic**
fixtures, not just regime fixtures:

| #    | Fixture                                                                                               | Assertion                                                                                            | Catches                              |
|------|-------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|--------------------------------------|
| IT1  | `reliance-15m-chase-2026q1` — synthesized from losing RELIANCE bars in RunSet `20260516203807`        | With `maxEntryAtrFromCloudTop=2.5`, v4 `intentCount == 0` on these bars                              | Anti-overextension (§9).             |
| IT2  | `synthetic-thin-cloud` — bars where `(spanA − spanB) / ATR ∈ [0, 0.2]`                                | With `minKumoThicknessAtr=0.25`, zero entries                                                        | Thin-cloud rejection (§3).           |
| IT3  | `synthetic-stale-tk-cross` — TK cross at bar t, no entry trigger until t+15                           | With `maxTkCrossAgeBars=8`, zero entries at t+9 onward                                               | TK freshness (§6).                   |
| IT4  | `synthetic-chikou-overlap` — Chikou span sits inside prior price bars                                 | With `requireChikouClearSpace=true`, zero entries                                                    | Chikou clear-space gate (§5).        |
| IT5  | `htf-disagreement` — M15 STRICT_BETA setup passes; injected H1 history places price below H1 kumo     | With `htfCloudBiasMode=REQUIRE_AGREEMENT`, zero entries                                              | HTF-bias gate (§10).                 |
| IT6  | `forward-twist-during-hold` — open long, mid-trade `futureSpanA` crosses `futureSpanB` from green→red | After `structureExitConfirmBars=2`, exit emitted                                                     | Forward-twist exit (§11).            |
| IT7  | `cloud-edge-trailing-rise` — open long, cloud floor rises 1% per 5 bars; price drops 0.5%             | Stop tracks rising cloud floor; exit fires at `close < rising cloud floor`, not at static entry stop | Cloud-edge trail (§8).               |
| IT8  | All-13-instrument golden runset replay                                                                | `assertPerInstrumentReturnSpread(result, maxSpreadPp = 18.0)` (v3 ≈ 23pp)                            | Per-instrument variance compression. |
| IT9  | `banknifty-15m-happy-path` v3-baseline preserved                                                      | `v4_intentCount <= v3_intentCount` (snapshot); no-regression on canonical fixture                    | No silent over-emission.             |
| IT10 | Standard fixture                                                                                      | `RecommendationAssertions.assertNoDuplicateRecommendations(result)`                                  | Duplicate-entry guard.               |

---

## 16. Implementation Order (Biggest-Ichimoku-Win-First)

Each PR is independently shippable; verify on RunSet `20260516203807`'s 13-instrument
set after each.

1. **PR-IK1 — Chikou-clear-space gate (§5).** Largest single Ichimoku-mechanic gap;
   pure indicator-math addition. Expected to eliminate the chase-entry false-positive
   family.
2. **PR-IK2 — anti-overextension at entry (§9).** One-line condition; directly targets
   the RELIANCE −7.39% failure mode.
3. **PR-IK3 — kumo thickness gate + ATR-normalized sizing (§3).** Combines structural
   rejection (thin cloud → no entry) with risk scaling (thick cloud → larger size).
4. **PR-IK4 — future-cloud spread + slope (§4).** Upgrades the binary green/red gate
   to a graded measure.
5. **PR-IK5 — H1 cloud bias HTF gate (§10).** Requires `requiredContextTimeframes`
   declaration change; medium effort but highest regime-fit leverage.
6. **PR-IK6 — TK cross freshness + slope (§6).** Filters stale-cross continuation
   chases.
7. **PR-IK7 — cloud-edge trailing stop (§8).** Pairs with §8 lift-back target-R for
   full exit policy.
8. **PR-IK8 — forward-twist exit (§11).** Leading-edge exit signal.
9. **PR-IK9 — EARLY_TRANSITION strengthening (§7).** Volume + ATR-expansion + recent-
   twist requirement.
10. **PR-IK10 — flat-cloud collapse exit (§12).** Mid-trade structural-evaporation
    handling.

PRs IK1, IK2, IK3 are the highest-leverage. Ship those first; measure delta before
continuing.

---

## 17. Acceptance Criteria For v4

A v4 release ships when, on the same RunSet `20260516203807` instrument set + window:

1. **No instrument loses more than -4.0%** (v3 has RELIANCE −7.39%). Anti-overextension
    + HTF-bias + Chikou should work together to eliminate the chase-entry family.
2. **Per-instrument variance drops** below 18pp (from 23pp).
3. **Intent count drops** by 15–25% (v3 = 4,888; expect ~3,700–4,150). Smaller drop
   than Trend Reversal because Ichimoku is already gate-tight.
4. **Net return stays at or above v3** (≥ +34.5%). **Critical — Ichimoku is already
   the outperformer; v4 must not regress in pursuit of variance reduction.**
5. **Hit-rate ≥ 0.48 on long-only directional** (v3 baseline ~0.45 estimated).

---

## 18. Risks & Open Questions

- **HTF-bias gate may suppress too much** on instruments that trend on M15 against the
  H1 background. The `REQUIRE_AGREEMENT` default may need to soften to
  `PREFER_AGREEMENT` if IT4/IT5 fixture results are too aggressive.
- **Chikou clear-space gate is the largest mechanic change.** It will materially reduce
  intent count; verify IT9 (no-regression on baseline fixture) passes before merging.
- **Cloud-edge trailing may exit winners too early** on volatile bars; the
  `structureExitConfirmBars=2` smoothing helps but A/B testing on a wider fixture set
  is warranted.
- **Forward-twist exit** is a leading-edge signal — by design it will exit before the
  present-cloud confirms. Some exits will look "premature" in hindsight on continuing
  trends; the net-benefit comes from avoiding the giveback on reversing trends. Verify
  net-return acceptance #4 doesn't regress.
- **Confidence-baseline recalibration** is the highest-risk cross-cutting change
  (covered as a §8 lift-back from Trend Reversal v4 plan). A/B test against v3 on the
  same RunSet before merging.

---

## 19. Coordination With Trend Reversal V4

Both v4 strategies are implemented in the same new `doflamingo-v4-strategy-packs/`
module. **Shared infrastructure** that v4 introduces (and that both strategies consume):

| Shared component                                                     | Used by | Owned by                                                 |
|----------------------------------------------------------------------|---------|----------------------------------------------------------|
| Session gating helper (IST parsing)                                  | Both    | New shared utility class, `DoflamingoSessionGating.java` |
| F&O expiry calendar lookup                                           | Deferred | Platform gap; exact holiday-adjusted expiry needs a platform trading-calendar contract. |
| `maxConsecutiveLosses` circuit-breaker (per-strategy instance state) | Deferred | Platform gap; strategy hooks do not expose closed-trade outcomes for all platform-managed exits. |
| Earnings calendar opt-in CSV loader                                  | Deferred | Platform gap; file-backed calendars need a replay-safe platform data-source contract. |
| Confidence-baseline recalibration                                    | Both    | Tune per-strategy; pattern is shared                     |

**Coordination recommendation:** ship the Trend Reversal v4 entry-tightening trifecta
(MACD zero-cross OR removal, Stoch threshold, ADAPTIVE_CONFIRMATION mode removal) first
since it's the biggest single emission-count win. Then ship Ichimoku v4 PR-IK1 (Chikou)
and PR-IK2 (anti-overextension) — both target the per-instrument variance issue. After
those four PRs land, the supported shared infrastructure can be developed once and
consumed by both strategies in parallel. Expiry skip and closed-trade loss streaks
should wait for platform contracts.

See `doflamingo-v4-trend-reversal-improvement-plan.md` §8 (Lift-Backs) for the
cross-cutting program scope.
