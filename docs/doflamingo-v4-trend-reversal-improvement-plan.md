# Doflamingo Trend Reversal V4 — Improvement Plan

> **Audience.** Whoever picks up the next iteration of the
> `doflamingo-multi-indicator-v6-trend-reversal-*` strategy.
>
> **Goal.** Diagnose why **Trend Reversal V3** under-performs Ichimoku Momentum V3 on
> Indian-market NSE EQ instruments, then propose a v4 design that closes the gap.
>
> **Method.** Read v3 strategy + provider + indicator math + regime filter + lifecycle
> helper; cross-check against `atx-platform-core/docs/strategy-author-guide.md` (canonical
> SPI) and `atx-design-docs/planning/atx-custom-strategy-plan.md` §2 (Indian-market
> context) + §3.5 (Reversal Trading blueprint). Grounded in observed performance from
> RunSet `20260516203807` (2y backtest, 13 NSE EQ instruments, M15).
>
> **Status.** V4 is implemented as a new strategy-samples module,
> `doflamingo-v4-strategy-packs`. The original v3 module remains unchanged. This doc
> keeps the review evidence and records the supported v4 subset plus platform gaps.
>
> **Companion docs.**
> - `atx-platform-core/docs/strategy-author-guide.md` — canonical SPI.
> - `atx-design-docs/planning/atx-custom-strategy-plan.md` — strategy-family blueprints (§2 India context, §3.5 Reversal
    Trading).
> - `atx-design-docs/planning/atx-backtest-framework-asks.md` — SPI gaps the strategy hits (FA-INST-03 session,
    FA-INST-05 expiry, FA-INST-04 circuits, FA-RISK-06 time-stop).
> - This repo's `docs/doflamingo-v3-shorting.md` — v3 short-side DDR. Long-side baseline contract lives there too.

---

## 0. Regression Status And V4.1 Calibration

**Implementation status (2026-05-17 RunSet `20260517211242`):** V4 shipped with all
entry gates enabled by default, producing catastrophic over-filtering (-71% trades on
Ichimoku, -98% on Trend Reversal, -90% / -96% on net return). See
`doflamingo-v4-regression-rca.md` for the RCA and the V4.1 default-relaxation plan.

The original strategy-mechanic findings in this doc remain valid; only the default
on/off decisions were revisited. V4.1 keeps the same strategy id and relaxes defaults
so the strict gates remain opt-in where they have not been A/B validated. RunSet
`20260517220546` then confirmed Trend Reversal V4.1 as the stable split-verdict
outcome: same return class as V3 with fewer trades, higher per-trade quality, and
smaller worst loss.

## 0.1. Current Implementation Status And Platform Gaps

Implemented module:

- `doflamingo-v4-strategy-packs/`
- Provider id: `doflamingo-v4-strategy-packs`
- Strategy id: `doflamingo-multi-indicator-v6-trend-reversal-v4`
- Strategy version: `4.1.0`
- V3 strategy/provider code was not modified.

Implemented within the current strategy SPI:

- strict momentum defaults: no `adaptiveMomentumMode` parameter, no MACD zero-cross
  fallback, Stoch threshold tightened to the configured oversold/overbought thresholds
- cooldown, structure-exit confirmation, recent RSI extreme, volume confirmation,
  long PSAR distance, deterministic session gate, portfolio drawdown gate
- explicit time-stop exits and RR target metadata on entry exit policies
- V4.1 keeps the entry-tightening trifecta, trend filter, cooldown, structure-exit,
  stale-exit, target-R, scale-out, PSAR distance, and risk defaults enabled. It
  default-disables RSI-extreme lookback, volume confirmation, portfolio drawdown,
  session gating, and regime skipping until A/B data supports re-enabling them.

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

## 1. Performance Evidence

RunSet `20260516203807` — 2 strategies × 13 NSE EQ instruments × M15 × ~2 years.

| Metric                          | Ichimoku Momentum V3 (outperformer) | Trend Reversal V3 (underperformer)                              |
|---------------------------------|-------------------------------------|-----------------------------------------------------------------|
| Primary score (StrategyPlanDQS) | 0.4407                              | 0.414                                                           |
| Net return (cell-aggregated)    | **+34.56%**                         | **+27.83%**                                                     |
| Positions completed             | 2,516 / 6 unresolved                | 2,895 / 6 unresolved                                            |
| Intents emitted                 | 4,888                               | **5,902 (+21%)**                                                |
| Per-instrument range            | −7.39% to +15.35% (~23pp spread)    | **−12.26% to +19.34% (~31pp spread)**                           |
| Big losers (per instrument)     | RELIANCE −7.39% (only one)          | RELIANCE −11.95%, HAL −12.26%, TITAN −10.89%, BHARTIARTL −4.35% |

Pattern: Trend Reversal fires **~21% more signals**, gets **~7pp less net return**,
with **~35% wider per-instrument variance**. Classic over-trading + insufficient
regime fit + weak stop discipline.

---

## 2. Trend Reversal V3 — Deep Review

**File:**
`doflamingo-v3-strategy-packs/src/main/java/org/algotradex/strategy/samples/doflamingov3/DoflamingoMultiIndicatorV6TrendReversalStrategy.java` (
988 lines)

+ provider (`…Provider.java`, 290 lines).

### 2.1 The "reversal" label is misleading

Long-entry hard gate (line 202–204):

```
directionNowUp (psar < low)  AND  momentumConfirmed  AND  close > presentSpanB
```

This is a **PSAR-flip + cloud-reclaim continuation** entry, not a counter-trend reversal.
`SetupType.REVERSAL` (line 257, 264) misadvertises what the strategy does. The mismatch
matters because:

- Evaluation expects reversal-style hit rate (30–40%) and asymmetric reward.
- The strategy emits in trending regimes where its actual mechanics (continuation) would
  be a fit, but the reversal-style stop placement is wrong for them.

### 2.2 Where the over-emission is coming from

| #   | Site                                                                                                                                         | v3 default                                                           | Failure mode                                                                                                                                                                                                               |
|-----|----------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F1  | Line 168–169: `buySignalMacd` = `originalBuySignalMacd OR (histogram > 0 AND prev histogram ≤ 0)`                                            | OR enabled                                                           | **MACD zero-cross OR** is a single-bar trigger. The original 3-bar curl-up was the v2 semantic; v3 relaxed it. Every zero-cross fires regardless of momentum context.                                                      |
| F2  | Line 160: `buySignalStoch` requires `min(prevK, K) < stochOversold + 10`                                                                     | `stochOversold = 20`, so check is `< 30`                             | **Stoch threshold drift** — `< 30` is barely a filter on M15 equities; nominally "oversold crossover" but functionally "any K-D cross in the lower-half of the range".                                                     |
| F3  | Line 197–201: `ADAPTIVE_CONFIRMATION` mode = `momentumConfirmed = original OR (macdHistogramRising OR stochKRising)`                         | `adaptiveMomentumMode = STRICT_REVERSAL` default but the mode exists | Single-bar deltas (`histogramRising` / `KRising` vs previous bar) print ~50% of the time. When enabled, this becomes a confirmation rubber-stamp.                                                                          |
| F4  | Line 678: `trendFilterMode = SOFT`                                                                                                           | default `SOFT`                                                       | SOFT OR's four sub-clauses including `close > spanB AND macd rising` — passes too easily.                                                                                                                                  |
| F5  | Line 234, 798: confidence floor starts at 0.46; `directionNowUp +0.10`, `close > spanB +0.08`, `trendFilterPassed +0.04` = **0.68 baseline** | `minConfidence = 0.60`                                               | **Confidence floor is decorative.** Every hard-gated entry exceeds 0.60 before any MACD/Stoch bonus. The threshold filters nothing in practice.                                                                            |
| F6  | No `cooldownRemaining` field anywhere                                                                                                        | n/a                                                                  | **No re-entry cooldown.** Same-bar after exit, strategy can re-enter. On RELIANCE / HAL / TITAN this is consistent with the per-instrument chop-loop losses.                                                               |
| F7  | Provider line 142: `skipMarketRegimes` default `[]`                                                                                          | empty                                                                | **No regime gate default.** Plan §3.5 explicitly recommends `[STRONG_TREND_HIGH_VOLATILITY]` for reversal. CHOPPY_HIGH_VOLATILITY destroys reversal strategies too.                                                        |
| F8  | Line 355: `exit = reversalConfirmed OR staleExit OR structureBreak OR postScaleWeakness`                                                     | n/a                                                                  | **No hard time-stop short-circuit.** When `barsHeld >= maxHoldingBars` (64 bars = 16h), there's no forced exit branch. Relies on platform `TradeIntentHorizon` (advisory).                                                 |
| F9  | Line 348: `staleExit = barsHeld >= 16 AND currentR <= 0.25`                                                                                  | `staleBars = 16`, `staleMinR = 0.25`                                 | Stale-exit is **too generous** — 4h × <0.25R lets flat trades drag for a half session.                                                                                                                                     |
| F10 | Line 298: long-side scale-out at `currentR >= 1.0`, fraction `0.50`                                                                          | one-shot                                                             | Scaling out at 1R while running a default 2.0–2.5% stop means **risk-reward is asymmetric in the wrong direction**: 0.5 × 1R win locked in, 0.5 × residual position carries asymmetric risk into the no-target exit logic. |

### 2.3 Long-side over-emission summary

`5,902 intents / 13 instruments / 2y ≈ 113 per instrument-year ≈ 1 every 2 days on
M15`. That's a high firing rate for a strategy whose name is "Reversal". The long side
likely accounts for 70%+ of intents given how relaxed the long-side gating is vs the
short side (the short side has `cleanShortStructure` confluence + PSAR-distance floor

+ overextension cap — the long side has none of those).

### 2.4 Exit policy is too loose

- `staleExit` at 16 bars × <0.25R is the dominant exit on losing trades. Bigger stops
    + late staleExit = realized losses larger than declared R.
- No target-R exit. Trades only exit on momentum-decay signals (reversalConfirmed,
  structureBreak, postScaleWeakness). Winners that don't decay cleanly give back gains.
- `trailAfterScaleOut = true` only triggers on `close < ema50 AND macd weakening`
  (line 350–354) — too weak as a protective trail.

### 2.5 Lifecycle has no instrument-level brake

Beyond the per-position lifecycle, there's no **strategy-level circuit breaker** — no
"three losses in a row on RELIANCE → stop trading RELIANCE for the next N bars".
RELIANCE −11.95%, HAL −12.26%, TITAN −10.89% suggest the strategy keeps re-entering on
the same instruments where it's losing.

---

## 3. Ichimoku Momentum V3 — Why It Outperforms

**File:** `…/DoflamingoIchimokuMo002BetaStrategy.java` (797 lines).

| #  | Mechanism                                                                                                                                                                                 | Effect                                                                                                                                                                       |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| I1 | Line 203–209: strict-mode entry stacks **7 AND conditions** (`low > spanB`, `ema9 > spanA`, present-cloud color, future-cloud color, `conversion > base`, `trend > average`, `trend > 0`) | Structural-only gates; no single-bar escape clauses.                                                                                                                         |
| I2 | Line 211–217: EARLY_TRANSITION mode requires `close > prior 5-bar high`                                                                                                                   | Real breakout confluence, no analog in Trend Reversal.                                                                                                                       |
| I3 | Provider line 100–101: `cooldownBars = 3` default                                                                                                                                         | 45-minute lockout on M15 after every entry or exit — kills the same-day re-entry chop.                                                                                       |
| I4 | Line 397–399: `structureExitConfirmBars = 2`                                                                                                                                              | Conversion-below-base must hold 2 consecutive bars before triggering exit; smoothing prevents single-bar noise.                                                              |
| I5 | Line 712: `if (earlySetup && !strictSetup) score -= 0.04`                                                                                                                                 | **Actively penalizes** the looser branch. Trend Reversal has no such "you took the easier path" penalty.                                                                     |
| I6 | Line 635–644: `cloudStopPercent` derives the stop **price** from `min(spanB, baseLine) - buffer`                                                                                          | Stops are structurally anchored, not parametric. Trend Reversal can do this (`stopMode = CLOUD`) but defaults to `ATR_OR_PERCENT_MAX` where the fixed 2.0–2.5% can dominate. |
| I7 | `SetupType.CONTINUATION` (line 252, 257)                                                                                                                                                  | Matches actual mechanics. Evaluation expectations align.                                                                                                                     |

### Structural difference summarized

Ichimoku treats the present-cloud + future-cloud configuration as a **gate** (entry
condition); Trend Reversal treats each indicator as a **possible reason** to enter and
OR's the easy paths. Ichimoku adds **temporal smoothing** (cooldown + structure-confirm-
bars) that Trend Reversal lacks entirely.

---

## 4. Indian-Market-Specific Failure Modes

Cross-referenced against `atx-design-docs/planning/atx-custom-strategy-plan.md` §2.6
(recurring Indian regime patterns) and §3.5 (Reversal blueprint).

### 4.1 Regime gate empty by default

`skipMarketRegimes` default `[]` (provider line 142). Plan §3.5 explicitly:
> `skipMarketRegimes MULTI_ENUM default=[STRONG_TREND_HIGH_VOLATILITY]`

Plus per §2.6, **CHOPPY_HIGH_VOLATILITY** destroys reversal strategies. The big losers
(HAL, TITAN, RELIANCE) are large-cap stocks that spent significant 2024-26 windows in
strong-trend regimes — exactly the regime where reversal mechanics produce false
positives. The strategy keeps catching the knife.

### 4.2 F&O expiry days unhandled

Per §2.6:
> Expiry-day mean reversion in indices: options sellers pin the index to max-pain
> strike. Trend systems often give back gains; range systems do well.

The 13 test instruments are NSE EQ (not indices), BUT all of them are **F&O underlyings**
(RELIANCE, SBIN, HAL, M&M, TITAN, BHARTIARTL, etc.). Monthly-expiry pin behavior
distorts trade outcomes on the underlying. No `currentBar.occurredAt → DayOfWeek` parsing
exists; no skip mechanism. SPI gap is FA-INST-05 but the per-strategy fallback (parse
IST + last-Thursday rule) is two lines of code.

### 4.3 Power hour (14:30–15:15 IST) is unhandled

Per §3.5 India-specific notes:
> Power hour produces frequent exhaustion patterns on indices; this is a
> high-probability window if expiry-day gating is enabled.

Without session gating, power-hour exhaustion candles are blended with mid-day chop
fakeouts. The strategy can't tell them apart.

### 4.4 Earnings days on single stock unhandled

Per §3.5:
> Earnings reversal on single stocks: optional, but author owns the earnings calendar.

No skip mechanism exists. RELIANCE quarterly results reliably produce reversal-strategy
carnage — this is consistent with the -11.95% RELIANCE outcome.

### 4.5 Opening range (09:15–09:30 IST) unhandled

Per §2.6:
> Trend strategies should treat the first 15-30 minutes after a >0.5% gap differently
> from a flat open.

PSAR-flips on the first 15-min bar of the day are gap-driven, not exhaustion-driven —
structurally meaningless for a reversal/continuation strategy.

### 4.6 Confidence calibration is broken across both v3 strategies

Confidence floors (Trend Reversal 0.46, Ichimoku 0.45) plus always-true bonuses put
emitted intents in a 0.68–0.85 cluster. With `minConfidence = 0.60` as the threshold,
the meaningful range is effectively unused. The signal-quality dial doesn't dial
anything. Lift back to Ichimoku too (see §8).

---

## 5. v4 Improvement Plan

### 5.1 Entry gates to ADD

| #   | Gate                                             | Mechanism                                                                                    | Default                                                                                                 |
|-----|--------------------------------------------------|----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| E1  | **Drop MACD zero-cross OR**                      | Remove line 168–169 fallback; keep only `originalBuySignalMacd` (3-bar curl-up)              | Tightens MACD to V2-original semantic.                                                                  |
| E2  | **Tighten Stoch threshold**                      | Replace `< stochOversold + 10` with `< stochOversold`                                        | Default `< 20`, not `< 30`.                                                                             |
| E3  | **Remove `ADAPTIVE_CONFIRMATION` mode entirely** | Delete enum value + the OR branch (line 197–201)                                             | One less knob, one fewer escape hatch.                                                                  |
| E4  | **Multi-bar momentum confirmation**              | Replace single-bar `macdHistogramRising` with `N-of-3-bars rising`                           | Default `>= 2 of last 3`.                                                                               |
| E5  | **Cooldown after entry / exit**                  | Mirror Ichimoku's `cooldownRemaining` field + tick-down each bar                             | `cooldownBars = 4` (1h M15).                                                                            |
| E6  | **Volume confirmation**                          | Current bar's volume vs 20-bar SMA                                                           | `volumeConfirmMultiple = 1.0` (skip ghost-volume bars).                                                 |
| E7  | **Anti-knife-catch RSI lookback**                | Require RSI < `rsiOversoldLong` within last N bars, not just the current bar                 | `requireRsiExtremeWithinBars = 5` per §3.5 mitigant.                                                    |
| E8  | **Long-side PSAR-distance floor**                | Mirror the short-side `MIN_SHORT_PSAR_DISTANCE_PCT = 0.05` to the long side                  | `psarMinDistanceLongPct = 0.05`.                                                                        |
| E9  | **Regime gate default**                          | Set `skipMarketRegimes` default to `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | `CHOPPY_HIGH_VOLATILITY` is not a platform enum; `RANGING_HIGH_VOLATILITY` is the supported substitute. |
| E10 | **Switch `trendFilterMode` default to `STRICT`** | SOFT OR's four sub-clauses; STRICT requires all                                              | Default `STRICT`.                                                                                       |
| E11 | **Tighten confidence baseline**                  | Drop the 0.46 floor to 0.30; reweight bonuses so the 0.60 threshold actually filters         | Bonus weights tuned so ~30–40% of hard-gated bars fail the threshold.                                   |

### 5.2 Exit policy changes

| #   | Change                       | v3 baseline                                   | v4 default                                                       | Reason                                                                                     |
|-----|------------------------------|-----------------------------------------------|------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| X1  | Tighten `staleBars`          | 16 (4h M15)                                   | 12 (3h)                                                          | Eject faster from flat trades.                                                             |
| X2  | Raise `staleMinR`            | 0.25                                          | 0.40                                                             | Stop hoping; flush flat trades sooner.                                                     |
| X3  | Add explicit time-stop emit  | n/a (relies on platform `TradeIntentHorizon`) | Strategy emits `EXIT_LONG/SHORT` at `barsHeld >= maxHoldingBars` | Make exits deterministic, not advisory.                                                    |
| X4  | Tighten max-holding          | 64 (16h ≈ 3 sessions)                         | 32 (8h ≈ 1 session)                                              | Reversal/continuation thesis decays within a session.                                      |
| X5  | Add post-scale weakness exit | `trailAfterScaleOut` weak (line 350–354)      | Emit explicit exit when post-scale momentum weakens              | Current SPI supports explicit exits, not runtime trailing-stop mutation.                   |
| X6  | Add fixed target-R exit      | None (only momentum-decay exits)              | `targetRMultiple = 2.5`                                          | Lock-in mechanic; reversal/continuation winners that don't decay cleanly stop giving back. |
| X7  | `stopMode` default           | `ATR_OR_PERCENT_MAX`                          | `ATR`                                                            | `_MAX` widens stops unnecessarily.                                                         |
| X8  | `stopLossPct`                | 2.0                                           | 1.5                                                              | RELIANCE/HAL bled through 2% stops repeatedly.                                             |
| X9  | `maxStopPct`                 | 2.5                                           | 2.0                                                              | Cap adaptive stops tighter.                                                                |
| X10 | `minStopPct`                 | 1.0                                           | 0.6                                                              | Allow tighter when cloud is close.                                                         |

### 5.3 Lifecycle changes

| #  | Change                                                | Default                                                                                                              |
|----|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| L1 | `maxConsecutiveLosses` circuit-breaker per instrument | Deferred. Needs platform-visible closed-trade outcomes for stop/target/time exits before this can be correct.        |
| L2 | Tighten scale-out trigger                             | Move from `currentR ≥ 1.0` → `currentR ≥ 1.25`. Avoid scaling out before the trade has earned its keep.              |
| L3 | Reduce `scaleOutFraction`                             | From `0.50` → `0.40`. Keep more of the runner on.                                                                    |
| L4 | Break-even after scale-out                            | Deferred. The current SPI does not support mutating the active stop after scale-out; v4 uses explicit exits instead. |
| L5 | `enableScaleIn` long-side: still off                  | Avoid pyramiding into reversal trades until §3.5 long-side scale-in is validated.                                    |
| L6 | Reduce `riskFraction`                                 | `0.01 → 0.0075`. Per §3.5, reversal hit-rate ≈ 30–40%; size down.                                                    |

### 5.4 New parameters introduced

```
cooldownBars                  INTEGER  default=4    range=0..50    rationale=re-entry chop guard (Ichimoku parity)
structureExitConfirmBars      INTEGER  default=2    range=1..10    rationale=Ichimoku-style smoothing on exits
volumeConfirmMultiple         DECIMAL  default=1.0  range=0.0..3.0 rationale=skip ghost-volume bars
psarMinDistanceLongPct        DECIMAL  default=0.05 range=0.0..1.0 rationale=long-side parity with short
requireRsiExtremeWithinBars   INTEGER  default=5    range=1..20    rationale=anti-knife-catch lookback
targetRMultiple               DECIMAL  default=2.5  range=1.0..10  rationale=fixed-target exit
sessionGating                 BOOLEAN  default=true rationale=deterministic NSE regular-session approximation
maxPortfolioDrawdownPct       DECIMAL  default=8.0  range=0..100   rationale=portfolio-level entry brake
```

Deferred original-plan parameters:

```
expiryGating                  PLATFORM GAP  exact holiday-adjusted expiry calendar needed
earningsCalendarRef           PLATFORM GAP  replay-safe event-calendar data source needed
maxConsecutiveLosses          PLATFORM GAP  closed-trade outcome callback needed
breakEvenAfterScaleOut        PLATFORM GAP  active stop mutation after scale-out needed
```

### 5.5 Indian-market gates (FE sketch)

```java
boolean inAllowedSession(BarEvent bar, boolean enabled) {
    if (!enabled) return true;
    ZonedDateTime ist = bar.occurredAt().atZone(ZoneId.of("Asia/Kolkata"));
    LocalTime t = ist.toLocalTime();
    DayOfWeek dow = ist.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY
        && dow != DayOfWeek.SUNDAY
        && !t.isBefore(LocalTime.of(9, 15))
        && !t.isAfter(LocalTime.of(15, 0));
}
```

Expiry-day gate — hand-rolled until FA-INST-05 lands:
```java
boolean isFnoExpiryDay(BarEvent bar, String instrumentId) {
    ZonedDateTime ist = bar.occurredAt().atZone(ZoneId.of("Asia/Kolkata"));
    DayOfWeek dow = ist.getDayOfWeek();
    LocalDate date = ist.toLocalDate();
    // NIFTY weekly = Thu; BANKNIFTY weekly = Wed; FINNIFTY = Tue
    // Monthly = last Thursday of month
    if (instrumentId.contains("NIFTY") && dow == DayOfWeek.THURSDAY) return true;
    if (instrumentId.contains("BANKNIFTY") && dow == DayOfWeek.WEDNESDAY) return true;
    if (instrumentId.contains("FINNIFTY") && dow == DayOfWeek.TUESDAY) return true;
    // Single-stock F&O — monthly only, last Thursday
    if (dow == DayOfWeek.THURSDAY) {
        LocalDate nextThursday = date.plusWeeks(1);
        if (nextThursday.getMonthValue() != date.getMonthValue()) return true;
    }
    return false;
}
```

Earnings calendar is **opt-in** — author provides a CSV path (`symbol,date` rows);
strategy reads at instantiation, skips entry on listed dates ±1.

Current implementation note: the expiry-day and earnings-calendar sketches above are
not implemented in v4. They remain platform gaps because exact expiry calendars and
file-backed event calendars need replay-safe platform contracts.

### 5.6 Defaults summary table — v3 vs v4

| Parameter                 | v3 default                               | v4 default                                                | Rationale                                                         |
|---------------------------|------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------|
| `adaptiveMomentumMode`    | `STRICT_REVERSAL` (default; mode exists) | **enum removed**                                          | Eliminates the OR-rubber-stamp branch (E3).                       |
| `trendFilterMode`         | `SOFT`                                   | `STRICT`                                                  | Stops the four-sub-clause OR (E10).                               |
| `stopMode`                | `ATR_OR_PERCENT_MAX`                     | `ATR`                                                     | Removes the `_MAX` widening (X7).                                 |
| `stopLossPct`             | 2.0                                      | 1.5                                                       | X8                                                                |
| `maxStopPct`              | 2.5                                      | 2.0                                                       | X9                                                                |
| `minStopPct`              | 1.0                                      | 0.6                                                       | X10                                                               |
| `staleBars`               | 16                                       | 12                                                        | X1                                                                |
| `staleMinR`               | 0.25                                     | 0.40                                                      | X2                                                                |
| `maxHoldingBars`          | 64                                       | 32                                                        | X4                                                                |
| `riskFraction`            | 0.01                                     | 0.0075                                                    | L6                                                                |
| `scaleOutAtR`             | 1.0                                      | 1.25                                                      | L2                                                                |
| `scaleOutFraction`        | 0.50                                     | 0.40                                                      | L3                                                                |
| `skipMarketRegimes`       | `[]`                                     | `[STRONG_TREND_HIGH_VOLATILITY, RANGING_HIGH_VOLATILITY]` | E9; `CHOPPY_HIGH_VOLATILITY` is unsupported by the platform enum. |
| `minConfidence`           | 0.60                                     | 0.60 (kept; baseline + bonus weights re-tuned via E11)    | Threshold finally has teeth.                                      |
| `stochOversold + slack`   | `+10` (effective `< 30`)                 | `+0` (`< 20`)                                             | E2                                                                |
| `allowReversal`           | false                                    | false                                                     | No change.                                                        |
| `enableScaleIn` long-side | n/a                                      | n/a (deferred)                                            | L5                                                                |

### 5.7 Capability flags

v4 should advertise the same set as v3 plus:

- `PORTFOLIO_AWARE` — strategy now reads `portfolioState.maxDrawdownPctSoFar` for an
  optional drawdown circuit-breaker (off by default, parameter
  `maxDrawdownCircuitPct = 0` disabled).
- No new capability for a consecutive-loss tracker. It is not implemented because the
  current strategy hooks do not expose a closed-trade callback for platform-managed
  stop/target/time exits.

---

## 6. Backwards-Compat Decision

**Recommendation: clean break — new strategy ID `doflamingo-multi-indicator-v6-trend-reversal-v4`.**

Reasons:

1. ≥10 parameter default tightenings + 11 new parameters + 1 enum removal = silent
   behavior break for anyone with stored v3 RunSet configs.
2. New parameters and planned calendar hooks carry IST / calendar dependencies that
   don't belong silently bolted onto v3. Current v4 ships the supported `sessionGating`
   subset and leaves expiry / earnings hooks as platform gaps.
3. The project already adopts new strategy IDs for material behavior changes (v2 → v3
   was exactly this; see `doflamingo-v3-shorting.md` DDR).
4. Keep v3 in the catalogue as the **baseline for comparative RunSets** — you need both
   versions present to validate v4 actually outperforms.

Migration: keep v3 provider unchanged. New v4 provider is a fresh
`*StrategyV4Provider.java`; it initially shipped as `STRATEGY_VERSION = "4.0.0"` and
the V4.1 default calibration now uses `STRATEGY_VERSION = "4.1.0"` with
`STRATEGY_ID = "doflamingo-multi-indicator-v6-trend-reversal-v4"`. V4 owns its helper
copies inside the new module so v3 helper behavior remains unchanged.

### Module layout

Implemented layout:

- New module: `doflamingo-v4-strategy-packs/`
- Parent reactor includes both `doflamingo-v3-strategy-packs` and
  `doflamingo-v4-strategy-packs`
- V4 has its own provider classes, helper classes, tests, and META-INF service entry
- V3 remains available as the comparative baseline and its files are unchanged

---

## 7. Test Strategy

Per `atx-platform-core/docs/strategy-author-guide.md` §1, use the **Fixture loop** with
`GoldenRunFixtures.load(...)`. Specific assertions:

| #  | Fixture                                                                                                    | Assertion                                                                                                                                                 | Catches                                                                                                                       |
|----|------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| T1 | `banknifty-15m-research-expiry`                                                                            | Deferred until platform provides an exact trading-calendar / expiry source.                                                                               | F&O-expiry failure mode (§4.2).                                                                                               |
| T2 | `banknifty-15m-overlap-suppression`                                                                        | `assert no second ENTER_LONG within cooldownBars of prior exit`                                                                                           | Re-entry chop loop (F6, E5).                                                                                                  |
| T3 | New `reliance-15m-chop-2026q1` synthesized from the actual losing RELIANCE bars in RunSet `20260516203807` | `v4 intentCount <= 0.5 × v3 intentCount`; loss-streak assertion deferred until closed-trade outcomes are visible to strategy code.                        | Per-instrument over-trading + circuit-breaker.                                                                                |
| T4 | All-13-instrument golden runset                                                                            | `stddev(per_instrument_net_return_pct) <= 8.0` (v3 baseline ≈ 12pp); `EvaluationAssertions.assertStrategyOnlyDirectionalHitRateGreaterThan(result, 0.45)` | Per-instrument variance (§1). May need a new helper `assertPerInstrumentReturnSpread(result, maxSpreadPp = 22.0)` in testkit. |
| T5 | Calibration test on any happy fixture                                                                      | Bucket emitted intents into confidence deciles; assert hit-rate monotonically increases with decile                                                       | Confidence calibration (§4.6, E11).                                                                                           |
| T6 | Synthetic IST-timestamped bars 09:15–09:30 + 14:30–15:15                                                   | `sessionGating = SKIP_OPENING_15M` produces zero intents before 09:30                                                                                     | Session gating regression (§4.5).                                                                                             |
| T7 | Synthetic context with `marketContext().primaryRegime = RANGING_HIGH_VOLATILITY`                           | Zero new long entries                                                                                                                                     | Regime gate (E9); platform substitute for planned `CHOPPY_HIGH_VOLATILITY`.                                                   |
| T8 | `banknifty-15m-happy-path` (v3 baseline preserved)                                                         | `v4_intentCount <= v3_intentCount` snapshot                                                                                                               | No silent over-emission regression on the canonical fixture. Per `doflamingo-v3-shorting.md` §10.                             |
| T9 | Any standard fixture                                                                                       | `RecommendationAssertions.assertNoDuplicateRecommendations(result)`                                                                                       | Duplicate-entry-in-position guard.                                                                                            |

---

## 8. Cross-Cutting Lift-Backs Shared With Ichimoku Momentum V4

> **See companion doc:** `doflamingo-v4-ichimoku-momentum-improvement-plan.md` (this
> repo) for the Ichimoku-specific v4 plan (Chikou clear-space, Kumo thickness gate,
> future-cloud spread + slope, TK cross freshness, HTF cloud bias, cloud-edge trailing,
> forward-twist exit, anti-overextension, EARLY_TRANSITION strengthening, flat-cloud
> collapse). That doc owns the strategy-specific levers; this section owns the
> **cross-cutting** v4 program changes that BOTH strategies adopt.

The following infrastructure is shared by both v4 strategies and should be developed
once, consumed by both:

| Shared component                       | Owned by                                          | Both v4 strategies adopt                                                                                                            |
|----------------------------------------|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Session gating helper (IST parsing)    | New `DoflamingoSessionGating.java` shared utility | `sessionGating` parameter, default `SKIP_OPENING_15M`                                                                               |
| F&O expiry calendar lookup             | Platform gap                                      | Not implemented; exact holiday-adjusted expiry needs a platform trading-calendar contract.                                          |
| `maxConsecutiveLosses` circuit-breaker | Platform gap                                      | Not implemented; strategy hooks do not expose closed-trade outcomes for all platform-managed exits.                                 |
| Earnings calendar opt-in CSV loader    | Platform gap                                      | Not implemented; file-backed calendars need a replay-safe platform data-source contract.                                            |
| Confidence-baseline recalibration      | Tune per-strategy; pattern is shared              | Drop floor from ~0.46 → ~0.30; reweight bonuses so 30–40% of hard-gated bars fail the threshold                                     |
| Target-R exit                          | Per-strategy `targetRMultiple` parameter          | Trend Reversal default `2.5`; Ichimoku Momentum default `2.0` (optional — Trend Trading blueprint §3.1 prefers trailing-stop carry) |

### Coordination recommendation

Ship the Trend Reversal v4 entry-tightening trifecta (PRs 1–3 of §9 below — MACD
zero-cross OR removal + Stoch threshold + `ADAPTIVE_CONFIRMATION` mode removal) first;
it's the biggest single emission-count win. Then ship Ichimoku v4 PR-IK1 (Chikou) and
PR-IK2 (anti-overextension) — both target the per-instrument variance issue. After
those four PRs land, the shared infrastructure can be developed once and consumed by
both strategies in parallel.

### A new regime-conditioned hybrid family worth considering

Per §3.5 thesis, reversal needs **ranges**; per Ichimoku design, continuation needs
**structure**. The 31pp Trend Reversal spread almost certainly maps to regime
mis-routing: RELIANCE is structurally a strong-trend large-cap (Ichimoku territory);
M&M during this window was rangier (Reversal territory). A meta-strategy that routes
by `marketContext().primaryRegime` — Reversal in `RANGING_*` + `CHOPPY_LOW_VOLATILITY`,
Ichimoku Continuation in `STRONG_TREND_*` + `WEAK_TREND_LOW_VOLATILITY` — would address
the per-instrument variance directly.

This is a separate strategy family (potentially `doflamingo-regime-router-v1`) and out
of scope for either v4, but worth filing for the next planning cycle.

---

## 9. Implementation Order (Biggest-Win-First)

Each step is independently shippable. Verify with the existing fixture tests after
each, plus a new comparison run against RunSet `20260516203807`'s 13 instruments.

1. **PR-1 — entry-tightening trifecta.** Remove MACD zero-cross OR (F1/E1) +
   tighten Stoch threshold (F2/E2) + remove `ADAPTIVE_CONFIRMATION` mode (F3/E3).
   Estimated to drop emission count 30–40% by itself. The single most leverage-rich
   PR; ship first.
2. **PR-2 — cooldown + structure-confirm.** Add `cooldownBars = 4` and
   `structureExitConfirmBars = 2`, mirror Ichimoku. Highest-leverage anti-chop change.
3. **PR-3 — trend-filter STRICT.** Switch `trendFilterMode` default to STRICT.
4. **PR-4 — stops + holding tighten.** `stopMode = ATR`, `stopLossPct = 1.5`,
   `maxStopPct = 2.0`, `staleBars = 12`, `staleMinR = 0.40`, `maxHoldingBars = 32`.
5. **PR-5 — regime gate default.** `skipMarketRegimes = [STRONG_TREND_HIGH_VOLATILITY,
   RANGING_HIGH_VOLATILITY]` default. `CHOPPY_HIGH_VOLATILITY` requires a platform enum
   change if it should become first-class.
6. **PR-6 — target-R exit + post-scale weakness exit.** Add fixed
   `targetRMultiple = 2.5` metadata and emit explicit exits when post-scale momentum
   weakens. Runtime ATR trailing-stop mutation is a platform gap.
7. **PR-7 — session gating** (IST parsing). Default `SKIP_OPENING_15M`. Just two
   lines of IST math; removes the opening-range PSAR-flip false positives.
8. **PR-8 — `maxConsecutiveLosses` circuit-breaker.** Deferred until the platform
   exposes closed-trade outcomes to strategy code.
9. **PR-9 — F&O expiry gating** (calendar lookup). Deferred until the platform exposes
   an exact holiday-adjusted trading calendar / expiry source.
10. **PR-10 — earnings calendar gate** (opt-in, external data path). Deferred until the
    platform provides a replay-safe event-calendar data source.
11. **PR-11 — confidence-baseline recalibration** (E11). Tune so 30–40% of hard-gated
    bars fail the threshold; verify with T5 calibration test.
12. **PR-12 — break-even-after-scale-out + scale-out tightening** (L2/L3/L4).
    Scale-out tightening is implemented; break-even stop mutation is deferred until the
    platform supports active stop modification.

PRs 1–5 are pure code edits with no SPI/runtime dependencies and no calendar data —
ship those first; measure delta on the same 13-instrument RunSet.

---

## 10. Docs Sweep (`atx-strategy-samples/docs/`)

| File                                                                                            | Status                                                                                                                        | Recommendation                                                                                                                                                                                                            |
|-------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `doflamingo-lifecycle-alignment-and-v2-plan.md`                                                 | **Stale.** Describes v1→v2 migration; both modules retired in commit `9f8d90c`. References strategy IDs that no longer exist. | **Retire** (move to `docs/archive/` or delete; git log preserves the audit trail).                                                                                                                                        |
| `doflamingo-v3-shorting.md`                                                                     | **Needs reframing.** Reads as if V2 is still around.                                                                          | **Edit** §1/§2 to read "V3 is the surviving Doflamingo line; long-side carried over from retired V2; this DDR covers the short-side extension." Add a §10 cross-link to **this doc** for the v4 trend-reversal evolution. |
| `ema-trend-structure-pullback-lifecycle-v2-plan.md`                                             | **Current.** Module exists; doc accurate.                                                                                     | Keep. Optionally add a back-link: "Reference: `atx-platform-core/docs/strategy-author-guide.md` §14."                                                                                                                     |
| `range-sr-v2-strategy-plan.md`                                                                  | **Current.** Module exists; doc accurate.                                                                                     | Keep.                                                                                                                                                                                                                     |
| `AlgoTradeX Indian Market Strategies – Detailed Specs For 20 Families.md`                       | **Perplexity source spec.** Background brief; parameter names drift from current implementations.                             | Keep, but add a header banner: "Input spec from Perplexity (Nov 2025). Authoritative API: `atx-platform-core/docs/strategy-author-guide.md`; family blueprints: `atx-design-docs/planning/atx-custom-strategy-plan.md`."  |
| `Principles and Technical-Indicator Strategies for Indian Equity Swing and Intraday Trading.md` | **Background reading.** Generic Indian-market education.                                                                      | Keep. Optionally rename to `background-indian-market-principles.md` for discoverability.                                                                                                                                  |
| **`doflamingo-v4-trend-reversal-improvement-plan.md`** (this file)                              | **New.**                                                                                                                      | Cross-link from the project README + `doflamingo-v3-shorting.md` §10.                                                                                                                                                     |
| **`doflamingo-v4-ichimoku-momentum-improvement-plan.md`**                                       | **New (parallel).**                                                                                                           | Ichimoku-specific v4 plan covering Chikou, Kumo thickness, HTF context, anti-overextension, cloud-edge trail, forward-twist exit. Cross-links to this doc's §8 for shared cross-cutting infrastructure.                   |

### README.md update needed (separate slice)

`atx-strategy-samples/README.md` is stale — still references retired Doflamingo v1/v2
modules. Per the audit in `atx-design-docs/planning/atx-perplexity-strategies-improvement-plan.md`
§5, the README needs:

- Remove v1/v2 build/install commands and "Available samples" list entries.
- Add the surviving v3 + (when shipped) v4 strategies to "Available samples".
- Add a link to `atx-platform-core/docs/strategy-author-guide.md` for new authors.
- Add a link to `atx-design-docs/planning/atx-custom-strategy-plan.md` for blueprints.

---

## 11. Risks & Open Questions

- **`structureExitConfirmBars` may delay legitimate exits** during fast moves.
  Mitigation: tested by T8 (no-regression on `banknifty-15m-happy-path`).
- **Regime gate default may suppress too much** on instruments that are persistently
  in the gated regimes. Mitigation: parameter is overridable; document this as an
  expected behavior in the strategy description string.
- **Earnings calendar opt-in** means most production runs won't have it set. The
  documentation should make this explicit: a v4 strategy without an earnings file is
  not protected against earnings-day reversals on single-stock instruments.
- **F&O monthly-expiry detection logic** in §5.5 is approximate (last-Thursday rule).
  Real SEBI calendar has holiday adjustments (when last Thursday is a holiday, expiry
  moves to Wednesday). Worth a `holidayCalendarRef` parameter or — better — wait for
  FA-INST-05 (BFF-provided trading calendar) and skip the per-strategy approximation.
- **Confidence recalibration (E11)** is the highest-risk change in the plan — it
  re-tunes a number the platform uses for ranking. Test T5 catches gross regressions
  but A/B testing against v3 on the same RunSet is mandatory before merging.

---

## 12. Acceptance Criteria for v4

A v4 release ships when, on the same RunSet `20260516203807` instrument set + window:

1. **Intent count drops** by ≥30% (from 5,902 → ≤4,130).
2. **Per-instrument variance drops** below 22pp (from 31pp).
3. **No instrument loses more than -8.0%** (v3 had three at -10.89 / -11.95 / -12.26%).
4. **Net return** improves to ≥31% (v3 27.83%; closer to Ichimoku's 34.56%).
5. **`assertNoDuplicateRecommendations(result)`** passes (T9).
6. **`assertStrategyOnlyDirectionalHitRateGreaterThan(result, 0.45)`** passes (T4).
7. **No regression on Ichimoku side** (Ichimoku is untouched; this is a smoke test).

A v4 that ships without #1 + #2 isn't actually solving the over-trading problem
flagged in §1.
