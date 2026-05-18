# Range S/R V2 — Improvement Plan (v2.1)

> **Audience.** Whoever picks up the next iteration of `range-sr-v2-strategy`.
>
> **Goal.** SRB v2 is producing **high-quality but low-volume trades** — 261 trades
> across 11 of 18 instruments over 2 years (~12 per instrument-year). Win rate 34.5%,
> avg PnL per trade 0.072%, worst loss only −1.94%. The structural-pullback edge is
> intact; the strategy is just *under-emitting*. Loosen specific gates that are not
> alpha-bearing while preserving the discipline.
>
> **Constraints.**
> - Do not change the strategy archetype (pullback to defended levels — works because every gate is archetype-matched).
> - Do not weaken the structural-stop / structural-target discipline.
> - Each relaxation must be A/B testable; ship one at a time.
>
> **Data baseline (RunSet `20260518115530`, 2y NSE EQ M15):**
> 11 runs, 261 trades, 34.48% win rate, 0.072% avg PnL/trade, +3.69%/−1.94% largest W/L, 1.71% avg total/run, 8.74% overlap.
>
> **Method.** Read v2 strategy (704 lines) + provider (178 lines) + parameters (31 lines). Identify each hard reject in the entry pipeline and rate it: keep / loosen / make adaptive.

---

## 1. The Entry Pipeline — 11 Hard Rejects

`RangeSrV2Strategy.java::onBarIntent + evaluate` walks the bar through 11 sequential gates. Each returns `Optional.empty()` on miss. Rejection is multiplicative — even modest per-gate rejection compounds.

| # | Gate | Location | What it does | Rejection cost (qualitative) |
|---|---|---|---|---|
| 1 | Cooldown | line 108 | Skip if `currentBar.occurredAt < cooldownUntil` (default 4h) | Moderate — caps re-entry density |
| 2 | History sufficient | line 116 | `executionBars >= 20 && bars4h >= 50` | Small — only fires at run start |
| 3 | H4 ADX ≥ 20 | line 232 | Below 20 = skip | **High** — sideways instruments emit zero trades |
| 4 | Side from EMA50 | line 235 | `price == EMA50` → empty (rare) | Negligible |
| 5 | Both swing high & low found | line 254 | Need both above and below price | Small — fractal pivots usually exist |
| 6 | Position-in-range zone | line 261 | BUY only in DISCOUNT (lower half); SELL only in PREMIUM | **High** — eliminates middle 4% of range as tradeable |
| 7 | Confluence ≥ 2 of 4 | line 271 | Pivot + round number + fib + extreme — 2 must hit | **Moderate-High** |
| 8 | Level touch | line 274 | Last bar's low/high must touch `level ± 0.2%` | **High** — narrow proximity tolerance |
| 9 | Tier-1 reversal pattern (confidence ≥ 1.0) | line 277 | Only engulfing / morning-star / three-soldiers (and bearish equivalents) qualify | **Biggest single emission killer** — 3 patterns vs 6 in the catalogue |
| 10 | Anti-overshoot guard | line 286 | Reject if bar low closed *through* the level | Moderate — kills valid breakdown-and-reclaim setups |
| 11 | Structural target at ≥ 2R away | line 305 | Refuses entries with no pivot at `≥ 2.0 × risk` distance | **High** — narrow ranges produce zero trades |

The combination of #3 (ADX floor) + #6 (zone gate) + #8 (level tolerance) + #9 (pattern strictness) + #11 (RR floor) is what compresses 2,000+ candidate setups per instrument-year down to ~12.

---

## 2. Defaults That Are Too Tight (Specific Sites)

From `RangeSrV2StrategyProvider.java::SCHEMA`:

| Parameter | Default | Range | Verdict | Why |
|---|---|---|---|---|
| `minPatternConfidence` | **1.0 (strictest)** | 0.5–1.0 | **Loosen** | At 1.0, only the 3 strongest patterns qualify. Tier-2 patterns (piercing 0.8, hammer 0.7, dark-cloud 0.8, shooting-star 0.7) all rejected. Loosening to 0.7 multiplies pattern-detection rate ~3×. |
| `levelTolerancePct` | 0.002 (0.2%) | 0.0001–0.05 | **Loosen + make adaptive** | At 0.2%, a stock at ₹500 must touch within ₹1.00 of the level. On high-vol bars or large-cap stocks, this is missed even at well-defended levels. |
| `midlineTolerancePct` | 0.02 (2%) | 0.0–0.20 | Keep | Defines the neutral zone. 2% on each side = 4% middle band. Reasonable. |
| `minTrendAdx` | 20 | 1–80 | **Loosen slightly** | At 20, instruments with ADX 12–18 (low-volatility-but-tradeable) emit zero. Lowering to 15 retains the trend-filter intent without blocking sleepy stocks. |
| `minConfluence` | 2 of 4 | 1–4 | Keep at 2 | Lower would degrade quality; higher would over-filter. |
| `atrMultMinRR` | 2.0 | 0.5–10.0 | Keep at 2.0 | Strong structural discipline — refuses trades without a real target. |
| `atrMultSL` | 1.5 | 0.1–10.0 | Keep | Reasonable. |
| `use15mStructure` | false | bool | **Expose as parameter** (with HYBRID option) | M15 pivots produce more candidate levels. Hybrid mode would use H4 for trend, M15 for level-touch — could double the candidate-level pool without losing structural discipline. |
| `cooldownHours` | 4 | 0–72 | Keep | Reasonable per-instrument re-entry guard. |
| `pivotLookback` | 3 | 1–10 | Keep | 3-bar fractal wing is standard. |
| `htfLookback` | 200 | 50–1000 | **Investigate** | 200 H4 bars = ~33 days of H4. For a 2-year backtest, this is fine in steady state but might cause early-window rejections. |
| `ltfLookback` | 200 | 20–2000 | Keep | Reasonable. |

---

## 3. The Failed-Runs Problem (11 of 18 instruments only)

7 of 18 instruments produced no analysis. Likely causes, in order of probability:

1. **ADX never reached 20** on those instruments → never traded → no trades to analyze
2. **H4 history sparse for some instruments** — if the AngelOne H4 data isn't fully populated, `bars4h.size() < 50` fires on early bars and `pivots.isEmpty()` later
3. **Pivot detection failed** — instruments with very gentle drift may never produce fractal pivots at `pivotLookback=3`
4. **Range-zone rejection** — if price spent the whole window near midline, neither DISCOUNT nor PREMIUM qualified

**Recommendation:** add a per-instrument diagnostic emission so the strategy can surface *why* it didn't emit trades on an instrument (so the RunSet UI shows "ADX never reached threshold" rather than a blank cell). This is a small SPI ask — emit a `diagnostics` line in `StrategyIntentResult` even on empty bars.

---

## 4. v2.1 Improvement Proposals (Ranked by Expected Emission Lift × Quality Preservation)

### PR-S1 — Loosen `minPatternConfidence` 1.0 → 0.7 (BIGGEST UNLOCK)

**Change.** Default `minPatternConfidence: 1.0 → 0.7`.

**Effect.** Accept piercing, hammer, dark-cloud, shooting-star in addition to the existing engulfing / morning-star / three-soldiers / evening-star / three-crows. Doji (0.5) remains excluded.

**Why this preserves edge.** All accepted patterns are still classical, multi-bar reversal patterns that require either body-engulfment, wick-rejection, or doji-after-trend confirmation. The quality cliff is at 0.5 (doji), not 0.7. Tier-2 patterns at a confluence-3 defended level are still high-probability setups.

**Expected emission change.** ~2.5–3× (pattern detection rate jumps; not every pattern site still passes the other gates).

**A/B acceptance.** Win rate may drop 1–3pp (32–34% expected, down from 34.5%). Acceptable trade-off if total return per run improves >2×. If win rate drops more than 4pp, revert.

**Stretch — surface pattern-tier as a parameter.** Replace the `minPatternConfidence` decimal with `patternTier: STRICT_TIER1 | TIER1_OR_TIER2 | ALL_PATTERNS`. Default `TIER1_OR_TIER2`. Cleaner to reason about than a confidence number.

### PR-S2 — Volatility-adaptive level tolerance

**Change.** Replace `levelTolerancePct: 0.002` constant with adaptive:

```
effective_tolerance = clamp(0.5 * (atrExecution / price), 0.001, 0.005)
```

**Effect.** Low-vol stocks (Indian banks) keep tight 0.1–0.2% tolerance. High-vol stocks (small-caps, BANKNIFTY) get 0.4–0.5% tolerance — more level touches qualify.

**Why this preserves edge.** Tolerance scales with what "near a level" actually means on the instrument. A ₹1.00 wick at ₹500 is structurally significant; a ₹1.00 wick at BANKNIFTY 47000 is noise. ATR-relative tolerance accounts for this.

**Expected emission change.** +15–30% (concentrates the lift on high-vol instruments).

**A/B acceptance.** Confluence gate keeps quality; volatility-adaptive tolerance should not change win rate materially.

**Implementation note.** Already have `atrExecution` computed in `evaluate()` (line 290). One line.

### PR-S3 — Lower `minTrendAdx` 20 → 15

**Change.** Default `minTrendAdx: 20 → 15`.

**Effect.** Opens the door to instruments in ADX 15–20 regime (range-bound-but-directional). Indian large-caps spend significant time here.

**Why this preserves edge.** ADX 15 is still "weak trend" not "no trend". The strategy is a pullback strategy — it needs SOME directional bias for `side` determination, but not strong-trend conditions.

**Expected emission change.** +20–40% (some of the 7 failed instruments likely fall in ADX 15–19).

**A/B acceptance.** Win rate may drop 1–2pp. If overall return per run improves, keep. Otherwise revert to 20.

**Stretch.** Make ADX threshold instrument-aware via market context: `if marketContext().trendStrength == WEAK use 15, if STRONG use 25`.

### PR-S4 — Add Tier-2-pattern + High-confluence confidence-equivalence rule

**Change.** Inside `reversalPattern()`, if a Tier-2 pattern (piercing, hammer, dark-cloud, shooting-star) is detected AND `confluenceScore == 4` (all four structural factors at the level), treat as Tier-1 equivalent.

**Effect.** Patterns that *would* pass current `minPatternConfidence = 1.0` if Codex stays strict on PR-S1 can still emit when they sit at A++ defended levels.

**Why this preserves edge.** "Hammer at level with pivot + round number + fib + extreme all aligned" is genuinely Tier-1 in classical interpretation. The 4-of-4 confluence + Tier-2 pattern is statistically rare and high-quality.

**Expected emission change.** Small (+5–10%) — A++ confluence is rare.

**A/B acceptance.** Net-positive by construction; only adds trades at the highest-confluence setups.

### PR-S5 — Add `pivotSource = HTF | LTF | HYBRID` parameter (default HYBRID)

**Change.** Currently `use15mStructure: boolean` (binary). Replace with `pivotSource: HTF | LTF | HYBRID`:
- HTF (current `false`): H4 pivots for both trend and structure
- LTF (current `true`): execution-timeframe pivots for structure
- **HYBRID (new default)**: H4 for trend + zone determination, M15 pivots for confluence + target

**Effect.** HYBRID mode keeps the H4 directional gate but adds M15's higher-resolution pivots as candidate levels. More levels → more touch events → more setups.

**Why this preserves edge.** H4 still defines the regime (DISCOUNT/PREMIUM zone, trend side). M15 just provides more granular structure. Confluence requirement at the M15 level is unchanged.

**Expected emission change.** +30–80% (M15 has 16× more bars than H4; pivot count roughly proportional).

**A/B acceptance.** Win rate may drop if M15 pivots are noisy. If confluence-2 still holds, edge survives. A/B carefully — this is the change most likely to alter strategy character.

### PR-S6 — Per-instrument diagnostic emission

**Change.** When `evaluate()` returns empty, emit a `StrategyIntentResult.empty()` with a `diagnostics` line tagged with the rejecting gate (e.g., `"reject:adx-below-min"`, `"reject:no-confluence"`, `"reject:no-pattern"`).

**Effect.** The RunSet detail page can show per-instrument rejection reasons; the user can see *why* 7 of 18 instruments produced zero trades.

**Why this preserves edge.** Pure observability — no behavior change.

**Expected emission change.** Zero (it's a diagnostic).

**Implementation note.** Already supported by SPI — `StrategyIntentResult(signals, intents, diagnostics)` accepts the third arg.

### PR-S7 — Investigate H4 lookback for early-window rejections

**Change.** Inspect why specific instruments produced zero trades. If `bars4h.size() < 50` is firing because H4 history is short, increase `htfLookback` default to 250 OR change the floor check from `< 50` to a date-window-relative threshold.

**Effect.** If the failed runs are H4-history-bound, this fixes them. If they're ADX-bound, PR-S3 fixes them.

**Expected emission change.** Variable, depends on which instruments are failing for which reason.

### PR-S8 — Allow second-touch entries within cooldown

**Change.** Within `cooldownHours`, allow ONE additional entry if (a) the level is touched again, AND (b) confluence has *increased* since the first signal (e.g., a new pivot has formed adjacent to the level).

**Effect.** Catches the "first touch fails, second touch is the real entry" pattern that's actually a stronger signal.

**Why this preserves edge.** Re-entry is gated on improved structural confluence, not lookback time alone.

**Expected emission change.** Small (+5–15%), but the additional trades should be higher-quality than baseline.

**A/B acceptance.** Will likely improve win rate while modestly adding volume. Lower-risk PR.

### PR-S9 — Range-position softening with confluence compensation

**Change.** If `position == MIDLINE` (currently blanket reject), allow entry only if `confluence == 4` (all four factors) AND a Tier-1 pattern fires.

**Effect.** Picks up the rare A++ midline-bounce setup (a stock that pulls back to its own mid-range after a structure violation, with all structural confluence aligned).

**Why this preserves edge.** Compensating for the lower position-in-range quality by requiring maximum structural confluence + pattern quality.

**Expected emission change.** Tiny (+2–5%), but adds the very highest-quality midline setups.

---

## 5. Implementation Order (Biggest-Unlock-First, Safest-First)

Each PR is independently shippable and A/B testable. Verify on the same 18-instrument RunSet as `20260518115530` after each.

1. **PR-S1 — pattern confidence 1.0 → 0.7** (or surface as tier enum). Biggest single emission unlock. Expected: 2.5–3× trades, win rate drops 1–3pp.
2. **PR-S6 — diagnostic emission** for failed-run visibility. Zero behavior change, immediate observability win.
3. **PR-S2 — volatility-adaptive level tolerance**. Modest emission lift, high quality preservation.
4. **PR-S3 — ADX 20 → 15**. Opens new instruments, modest win-rate cost.
5. **PR-S4 — Tier-2 + max-confluence equivalence**. Small lift, zero quality risk.
6. **PR-S8 — second-touch within cooldown**. Small lift, quality-positive.
7. **PR-S7 — investigate H4 lookback**. Diagnostic-driven; outcome shapes the fix.
8. **PR-S5 — `pivotSource = HYBRID` default**. Biggest emission lift remaining but biggest character-change risk — ship last and carefully.
9. **PR-S9 — midline-confluence-4 softening**. Smallest lift; ship if everything else lands cleanly.

Expected cumulative outcome after S1+S2+S3+S4: trade count rises from 261 → ~700–900 (~3×) while win rate compresses to ~32% (still above Doflamingo). Per-instrument coverage should improve from 11/18 to ~16/18.

---

## 6. Acceptance Criteria for v2.1

A v2.1 release ships when, on the same RunSet `20260518115530` instrument set + window:

1. **Trade count rises ≥ 2×** (target ≥520).
2. **Per-instrument coverage rises** from 11 of 18 to ≥ 15 of 18 (the failed-runs problem is at least partially solved).
3. **Win rate stays ≥ 31%** (acceptable to lose 3pp of selectivity for ≥ 2× volume).
4. **Avg PnL per trade stays ≥ 0.05%** (above Doflamingo V3 0.064% would be ideal but not required).
5. **Largest loss stays ≥ −2.5%** (structural stop discipline preserved).
6. **Avg total/run rises to ≥ 3%** (current 1.71%; lifted by volume).

If after PR-S1 + PR-S2 + PR-S3 the strategy doesn't meet criteria #1 and #6, fall back to v2 defaults and re-investigate which gate is the actual blocker. The PR-S1 lift alone should clear it.

---

## 7. What NOT to Change

The following decisions are correct and should be preserved:

- **Two-timeframe architecture** (H4 trend context + M15 execution). This is the strategy's edge.
- **Confluence-2-of-4 requirement**. Multi-source structural confirmation is the noise filter.
- **Tier-1 pattern minimum** at top-of-range or bottom-of-range. Don't degrade pattern quality alongside loosening confidence — keep the floor at 0.7, never lower.
- **`atrMultMinRR = 2.0`** — refusing trades without a real 2R+ structural target IS the strategy.
- **Structural stop (level ± 1.5×ATR)**. The reason worst losses are −1.94% vs Doflamingo's −3.29% is this anchoring.
- **`riskUsdPerTrade` fixed-dollar sizing**. Disciplined and instrument-agnostic.
- **`SetupType.PULLBACK`** label. Matches the actual mechanic.
- **Cooldown hours (not bars)**. Time-based cooldown is right for a structure-pullback strategy whose level only meaningfully redefends after hours, not bars.

Anything that touches these is no longer "v2.1 calibration" — it's a different strategy.

---

## 8. Risks

- **PR-S5 (HYBRID pivots) could change strategy character.** M15 pivots are noisier and might compound with PR-S1 (looser patterns) into a meaningfully different setup distribution. Ship S5 LAST and A/B carefully against v2 baseline, not against the post-S1 baseline.
- **PR-S3 (ADX 15) opens lower-trend regimes** where reversal patterns are less reliable. Acceptable trade-off if confluence holds, but worth monitoring per-instrument variance after.
- **PR-S1 default change might surprise existing scenario configs.** Make `patternTier: TIER1_OR_TIER2` the new default but bump `STRATEGY_VERSION` to `2.1.0` so existing configs explicitly opt into the looser mode by re-running scenarios.

---

## 9. Why SRB Is Worth Investing In

Compared to Doflamingo v3/v4 (currently 11–13% return drop in v4 → v4.2 path), SRB v2 has the **right shape** — it's archetype-pure, confluence-driven, structurally-anchored, and disciplined. The improvement plan above lifts trade volume by 2–3× while preserving the structural-pullback edge.

If even half the v2.1 PRs ship cleanly, SRB becomes the strongest sample strategy in the catalogue on a per-trade-quality basis, and the second-strongest on absolute return. It's a model the future v5 Doflamingo refactor should learn from.

The recommended sequence is light-touch: ship PR-S1, S2, S6 first (low-risk, big-impact). Validate. Then S3, S4, S8 (medium-risk, medium-impact). Then S5 (highest-impact but highest character-change risk) last. After each PR, measure on the same 18-instrument 2y window before progressing.
