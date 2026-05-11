# Doflamingo Lifecycle Alignment and Adaptive V2 Plan

Status: proposal and implementation backlog

Applies to: `doflamingo-strategy-pack`

Current strategy IDs:

- `doflamingo-ichimoku-mo-002-beta-v1`
- `doflamingo-multi-indicator-v6-trend-reversal-v1`

Recommended new strategy IDs:

- `doflamingo-ichimoku-mo-002-beta-v2`
- `doflamingo-multi-indicator-v6-trend-reversal-v2`

## Context

The lifecycle DDR makes lifecycle-aware strategies first-class in ATX. A strategy can emit entry, exit, scale, flatten, reverse, duration-aware, and portfolio-aware trade intent. Signal-only strategies still work, but they fall back to standardized policy, including the 20-candle standardized evaluation path. Lifecycle strategies should be evaluated through `StrategyPlanDQS` and should make their plan quality observable through explicit intent metadata.

The current Doflamingo Java ports are better than signal-only ports because they now implement `TradeIntentStrategy` and emit `StrategyTradeIntent` entry and exit intents. They are still closer to original-logic fidelity ports than ATX-native lifecycle strategies.

The decision is to preserve v1 as the fidelity baseline, fix v1 metadata and lifecycle contract alignment, and add v2 variants for adaptive ATX lifecycle evaluation.

## Decision

Keep the current v1 strategy IDs as original Doflamingo fidelity ports. Do not silently change their alpha behavior beyond lifecycle metadata and intent-contract fixes.

Add v2 strategy IDs for ATX-adaptive versions. The v2 variants should use current closed-bar indicators, dynamic confidence, explicit horizon, better risk exits, optional scale-out, max holding bars, and anti-chop or regime filters.

Use v1 vs v2 comparisons to determine whether the original idea is weak or whether the current port is delayed and under-specified for lifecycle evaluation.

## Current V1 Behavior

### Ichimoku Momentum 002 Beta

Implementation:

- Strategy: `doflamingo-strategy-pack/src/main/java/org/algotradex/strategy/samples/doflamingo/DoflamingoIchimokuMo002BetaStrategy.java`
- Provider: `doflamingo-strategy-pack/src/main/java/org/algotradex/strategy/samples/doflamingo/DoflamingoIchimokuMo002BetaStrategyProvider.java`

The strategy implements `TradeIntentStrategy`. It emits a long signal plus `ENTER_LONG` when the Ichimoku/cloud/moving-average acceleration setup is true. It emits `EXIT_LONG` when the cloud exit condition appears and the platform reports an open instrument position.

Current entry condition:

- `low > presentSpanB`
- `EMA9 > presentSpanA`
- present cloud is red: `presentSpanB > presentSpanA`
- future cloud is green: `futureSpanA > futureSpanB`
- `conversionLine > baseLine`
- `trendScore > trendAverage`
- `trendScore > 0`
- no existing platform position

Current exit condition:

- exit long when `presentSpanA > candle high`
- emit the exit only when `context.instrumentPosition().hasPosition()` is true

This matches the original JS intent: enter only after the cloud and trend acceleration conditions align, and exit when Span A overtakes the candle high.

### Multi Indicator V6 Trend Reversal

Implementation:

- Strategy: `doflamingo-strategy-pack/src/main/java/org/algotradex/strategy/samples/doflamingo/DoflamingoMultiIndicatorV6TrendReversalStrategy.java`
- Provider: `doflamingo-strategy-pack/src/main/java/org/algotradex/strategy/samples/doflamingo/DoflamingoMultiIndicatorV6TrendReversalStrategyProvider.java`

The strategy implements `TradeIntentStrategy`. It emits a long signal plus `ENTER_LONG` when PSAR direction is up, MACD or Stoch RSI confirms a bullish reversal, and price is above Ichimoku Span B. It emits `EXIT_LONG` when the fixed stop or reversal confirmation appears and the platform reports an open position.

Current entry condition:

- PSAR direction is up
- MACD buy pattern or Stoch RSI oversold bullish cross is present
- `close > presentSpanB`
- no existing platform position

Current reversal exit condition:

- PSAR flipped from up to down
- `close < presentSpanB`
- Stoch RSI sell signal or MACD sell signal is present

The current Java port preserves the original long-only JS behavior: PSAR direction, MACD/Stoch RSI reversal confirmation, Span B confirmation, fixed 2% stop, and full exit on stop or reversal confirmation.

## Alignment Gaps

### 1. Provider descriptors hide lifecycle capabilities

The strategy instances expose lifecycle capabilities through `lifecycleCapabilities()`.

Ichimoku currently exposes:

- `LONG_SIGNALS`
- `TRADE_INTENT`
- `LONG_ENTRY_INTENT`
- `EXIT_INTENT`

Multi V6 currently exposes:

- `LONG_SIGNALS`
- `TRADE_INTENT`
- `LONG_ENTRY_INTENT`
- `EXIT_INTENT`
- `RISK_AWARE_SIZING`

The provider descriptors still expose only:

- `LONG_SIGNALS`
- `PARAMETERIZED`

This creates a catalog mismatch. BFF/Web projections and strategy filters that rely on descriptors will under-report the lifecycle surface.

Recommended v1 fix:

- Ichimoku descriptor should expose `LONG_SIGNALS`, `TRADE_INTENT`, `LONG_ENTRY_INTENT`, `EXIT_INTENT`, and `PARAMETERIZED`.
- Multi V6 descriptor should expose `LONG_SIGNALS`, `TRADE_INTENT`, `LONG_ENTRY_INTENT`, `EXIT_INTENT`, `RISK_AWARE_SIZING`, and `PARAMETERIZED`.

Acceptance criteria:

- Provider descriptor tests assert the lifecycle capabilities.
- Catalog/API projection shows the same lifecycle capability surface as the strategy instance.

### 2. Confidence is fixed

Both providers define a `confidence` parameter with default `0.70`. The strategies pass that same value into every signal and intent. `DoflamingoSignalSupport` only clamps and rounds the configured value.

Under `StrategyPlanDQS`, this should not be treated as calibrated signal confidence. Constant confidence should either receive neutral confidence quality or emit a diagnostic that the strategy is using static confidence.

Recommended v1 fix:

- Rename the parameter to `minConfidence` only when introducing v2.
- For v1, preserve `confidence` for compatibility but mark it as fixed-confidence metadata in the reason/evidence.
- Ensure diagnostics can distinguish constant confidence from signal-strength confidence.

Recommended v2 fix:

- Compute confidence from setup evidence.
- Clamp final confidence to `[0.50, 0.95]`.
- Emit only when computed confidence is at least `minConfidence`.

### 3. Java indicator helpers likely add one extra bar of delay

The original Doflamingo JS reads current indicator arrays at the current index, such as `ema9_Data[idx]`, `leadingSpanA_Data[idx]`, `macdHistArr[idx]`, `kArr[idx]`, `dArr[idx]`, and `psarArr[idx]`.

The Java port uses intentionally lagged helper paths in `DoflamingoIndicatorMath`, including:

- `laggedEma(...)`
- `laggedSma(...)`
- `laggedMacd(...)`
- `laggedStochRsi(...)`

This can make the Java port fire one bar later than the original JS logic.

Recommended v2 fix:

- Replace extra-lagged helper use with current closed-bar helper use:
  - `laggedEma` -> `closedBarEma`
  - `laggedSma` -> `closedBarSma`
  - `laggedMacd` -> `closedBarMacd`
  - `laggedStochRsi` -> `closedBarStochRsi`
- Keep Ichimoku displacement semantics intact:
  - present Span A/B should still use the displaced cloud source
  - future Span A/B can remain the current calculated cloud projection

Rationale:

ATX emits strategy intent after the bar closes and fills at the next bar open. Current closed-bar indicators are replay-safe in that model and do not require an additional one-bar delay.

### 4. Internal active state can desync from platform lifecycle state

Ichimoku sets `activeLongSetup = true` when it emits an entry intent. Multi V6 sets `activeStopLoss` when it emits an entry intent.

The platform can validate or reject an intent. A strategy should not assume that an emitted entry became a position.

Current exit logic checks `context.instrumentPosition().hasPosition()` before emitting exit intents, which is correct. The weak part is that internal state can still suppress future entries after a rejected entry.

Recommended v1 fix:

- Treat `context.instrumentPosition()` as the source of truth for actual lifecycle position state.
- If `activeLongSetup` is true but no platform position exists, reset it or allow re-entry after a short cooldown.
- If `activeStopLoss` is set but no platform position exists, reset it.

Recommended v2 fix:

- Use internal booleans only as setup-cycle state.
- Derive stop and lifecycle state from the actual platform position when available.
- Prefer platform average entry price over signal-close price for stop calculation once a position exists.

### 5. Exit sizing should mean full close

The original Doflamingo JS exits were all-out sells. The current support helper creates every intent with `TradeIntentSizing.normalizedUnit()`, including exits.

That can work accidentally if the runtime treats one normalized unit as the whole position. It is still semantically wrong for an `EXIT_LONG` intent.

Recommended v1 fix:

- Entry sizing can remain normalized unit or risk-governed full-position sizing.
- Exit sizing should explicitly mean close full long exposure.
- Use a close-fraction factory such as `TradeIntentSizing.closeFraction(1.0)` if available.
- If no such contract exists, add or reuse the closest existing full-close representation.

Acceptance criteria:

- Exit intent tests assert full-position close semantics.
- Runtime evaluation does not depend on normalized unit size to interpret a full exit.

### 6. Intent horizon is unknown

`DoflamingoSignalSupport` gives `TradeSignal` a four-hour `TimeHorizon`, but every lifecycle intent uses `TradeIntentHorizon.unknown()`.

The DDR supports explicit duration and horizon for lifecycle strategies. These strategies need that metadata because the expected holding period is part of lifecycle plan quality.

Recommended v1 fix:

- Add `maxHoldingBars`.
- Add an intended horizon label.
- Populate lifecycle intent horizon explicitly.

Suggested defaults:

- Ichimoku 002 Beta:
  - `maxHoldingBars = 96` on M15
  - horizon label: `INTRADAY` or `SWING`, depending on configured timeframe
- Multi V6:
  - `maxHoldingBars = 48` or `64` on M15
  - horizon label: `INTRADAY`

### 7. Multi V6 stop handling may be duplicated

Multi V6 entry intent includes a percent stop-loss policy through `longEntryIntent(..., stopLossPct, ...)`. The strategy also tracks `activeStopLoss` and emits an explicit `EXIT_LONG` when price crosses the stop.

This is correct only if the runtime treats the entry exit policy as metadata. If the runtime executes `TradeIntentExitPolicy.PERCENT`, manual stop exits can duplicate runtime stop behavior.

Required implementation check:

- Verify whether `TradeIntentExitPolicy.PERCENT` automatically exits the position in the current plan runtime.
- If yes, remove manual `activeStopLoss` execution or convert it to diagnostics.
- If no, keep manual stop exit and document that the exit policy is advisory metadata.

Preferred direction:

- Let the platform runtime own executable stop policy.
- Keep strategy code responsible for declaring the intended risk plan and for emitting non-policy exits such as reversal, stale trade, and structure break exits.

### 8. Intent evidence is empty

`DoflamingoSignalSupport` currently builds `StrategyTradeIntentReason` with a summary string, no evidence, and the tag `doflamingo`.

That limits plan traceability. `StrategyPlanDQS` should be able to diagnose why the strategy emitted an entry, exit, or scale intent.

Recommended v1 fix:

- Add evidence strings to each intent reason.
- Keep the reason summary short.
- Include enough values to reconstruct the condition.

Example evidence:

- `presentSpanB=...`
- `presentSpanA=...`
- `futureCloud=GREEN`
- `conversionAboveBase=true`
- `trendScore=...`
- `trendAverage=...`
- `macdHistogram=...`
- `stochK=...`
- `stochD=...`
- `psarDirection=UP`
- `confidenceMode=STATIC`
- `confidenceScore=...`

## V1 Alignment Backlog

These changes should be done before building adaptive v2 variants because they improve catalog truth and lifecycle evaluation without changing the core Doflamingo alpha logic.

| Priority | Change | Scope |
| --- | --- | --- |
| 1 | Add lifecycle capabilities to provider descriptors | Provider metadata only |
| 2 | Add explicit entry policy, preferably `MARKET_NEXT_OPEN`, if the contract supports it | Intent construction |
| 3 | Use full-close sizing for `EXIT_LONG` | Intent construction |
| 4 | Add explicit horizon and `maxHoldingBars` parameter | Provider schema and intent construction |
| 5 | Reset internal active state when platform position state disagrees | Strategy state handling |
| 6 | Verify stop-policy runtime semantics and remove duplicate execution if needed | Multi V6 strategy/runtime boundary |
| 7 | Add evidence strings to `StrategyTradeIntentReason` | Strategy reason construction |
| 8 | Mark v1 confidence as static in diagnostics or evidence | Reason/evidence metadata |

## Adaptive V2 Design

### Shared V2 Contract

Both v2 strategies should:

- use new strategy IDs rather than changing v1 behavior
- expose lifecycle capabilities through provider descriptors
- use current closed-bar indicator values where replay-safe
- keep Ichimoku displacement handling intact
- use `minConfidence` instead of emitted fixed `confidence`
- compute confidence from setup evidence
- include evidence values in every emitted intent
- set explicit entry policy, sizing, horizon, and preconditions
- use platform position state as the source of truth
- include `maxHoldingBars`
- emit stale-trade or structure-break exits where the original logic leaves positions idle

### Ichimoku Momentum 002 Beta V2

Recommended ID:

- `doflamingo-ichimoku-mo-002-beta-v2`

Keep v1 as strict original logic. V2 should preserve strict behavior as a mode, then add adaptive modes.

Suggested parameters:

- `entryMode = STRICT_BETA | EARLY_TRANSITION | HYBRID`
- `minConfidence = 0.60`
- `maxHoldingBars = 96`
- `enableProtectiveStop = true`
- `stopMode = CLOUD_OR_ATR`
- `atrStopMultiple = 1.5`
- `cloudStopBufferPct = 0.25`
- `enableStructureExits = true`

Indicator timing:

- Use current closed-bar EMA9 and current closed-bar trend score.
- Keep present and future Ichimoku cloud semantics aligned with the original displacement model.

Confidence scoring:

| Area | Points | Evidence |
| --- | ---: | --- |
| Cloud transition | 25 | present cloud red, future cloud green, future spread positive or expanding, conversion above base |
| Price/cloud strength | 20 | low above Span B, EMA9 above Span A, close above Span B without overextension |
| Trend acceleration | 25 | trend score above average, trend score positive, trend acceleration above recent bars, bullish SMA/EMA transition |
| Risk location | 15 | entry not too far above cloud or EMA9, candle not overextended |
| Market cleanliness | 15 | EMA structure not mixed, no deep bearish moving-average stack, ATR/volatility not extreme |

Emission rule:

- `confidence = clamp(score / 100, 0.50, 0.95)`
- emit only when `confidence >= minConfidence`

Risk and exit behavior:

- Keep original exit: `presentSpanA > high`.
- Add structure exits:
  - `close < presentSpanB`
  - `conversionLine < baseLine` for N bars
  - `EMA9 < presentSpanA`
  - `barsHeld >= maxHoldingBars`
- Add protective stop when supported:
  - cloud stop: `min(presentSpanB, baseLine) - buffer`
  - ATR stop: `entry - atrStopMultiple * ATR`

Early-entry mode:

- `STRICT_BETA` keeps the current v1 entry condition.
- `EARLY_TRANSITION` allows entry when:
  - `close > presentSpanB`
  - future cloud is green
  - conversion is above base
  - trend score is above trend average
  - close breaks the prior 5-bar high
- `HYBRID` can emit earlier entries at lower confidence and upgrade confidence when strict beta confirmation appears.

### Multi Indicator V6 Trend Reversal V2

Recommended ID:

- `doflamingo-multi-indicator-v6-trend-reversal-v2`

Suggested parameters:

- `minConfidence = 0.60`
- `trendFilterMode = NONE | SOFT | STRICT`
- `stopMode = PERCENT | ATR | CLOUD | ATR_OR_PERCENT_MAX`
- `stopLossPct = 2.0`
- `atrStopMultiple = 1.5`
- `maxHoldingBars = 48` or `64`
- `staleBars = 16`
- `staleMinR = 0.25`
- `scaleOutAtR = 1.0`
- `scaleOutFraction = 0.50`
- `trailAfterScaleOut = true`

Indicator timing:

- Use current closed-bar PSAR, MACD, and Stoch RSI values rather than extra-lagged values where replay-safe.
- Preserve original reversal pattern semantics unless a parameter explicitly enables adaptive behavior.

Confidence scoring:

| Area | Points | Evidence |
| --- | ---: | --- |
| PSAR direction/reversal quality | 20 | direction up, recent PSAR flip from down to up, PSAR below low by meaningful distance |
| Momentum reversal | 25 | MACD buy pattern, histogram increasing, Stoch RSI oversold bullish cross, bonus when both confirm |
| Cloud support | 20 | close above Span B, safe margin above Span B, Span B rising or flat |
| Risk location | 15 | stop distance not too wide, entry not far above cloud, candle closes strong |
| Anti-chop filter | 20 | limited PSAR whipsaw, EMA stack not mixed, volatility not choppy, close above EMA20/EMA50 or trend score not deeply negative |

Emission rule:

- `confidence = clamp(score / 100, 0.50, 0.95)`
- reject if `confidence < minConfidence`

Trend filter:

- Default `trendFilterMode = SOFT`.
- `SOFT` allows long reversal entries when at least one condition holds:
  - close is above EMA50
  - trend score is above recent trend average
  - close reclaimed Span B with MACD confirmation
- `STRICT` rejects entries in a strong bearish stack or persistent strong downtrend.
- `NONE` preserves less-filtered reversal behavior for experiments.

Stop policy:

- The original fixed 2% stop is a useful fidelity baseline, but it is not instrument-aware.
- V2 should support ATR-aware stop distance:
  - `stopDistance = max(1.0 * ATR, 1.0% of close)`
  - cap at `2.5%` unless the run explicitly configures a wider stop
- If the runtime only supports percent stops today, the strategy can compute ATR exits manually until contract/runtime support is added.

Scale-out behavior:

- On `ENTER_LONG`, declare the initial stop.
- If the position reaches `+1R`, emit `SCALE_OUT_LONG` for 50% when supported.
- Hold the remainder while PSAR remains up.
- Emit `EXIT_LONG` on reversal confirmation, stop, structure break, or stale-trade exit.

Stale-trade exit:

- If `barsHeld >= staleBars` and current unrealized R is below `staleMinR`, emit `EXIT_LONG`.
- This reduces capital tied up in reversal ideas that fail to follow through.

## Expected Evaluation Impact

The expected improvement is tied to the new lifecycle scoring model.

| DQS area | Expected effect |
| --- | --- |
| Return quality | Better exits, less MFE giveback, fewer delayed entries |
| Risk-control quality | Explicit stops, position-state alignment, max holding discipline |
| Lifecycle-management quality | Entry, exit, scale, horizon, and precondition intent are more explicit |
| Capital-efficiency quality | Fewer stale positions and less all-in-style exposure |
| Confidence quality | Dynamic signal-strength confidence replaces constant `0.70` confidence |
| Trace quality | Evidence fields explain why each intent was emitted |

## Validation Plan

Run these checks after implementation.

1. Provider descriptor tests
   - descriptor capabilities match strategy lifecycle capabilities
   - v1 and v2 IDs are both discoverable through `ServiceLoader`

2. Intent contract tests
   - `ENTER_LONG` uses explicit entry policy when supported
   - `EXIT_LONG` uses full-close sizing
   - lifecycle intents have explicit horizon
   - reason evidence is non-empty
   - static-confidence v1 intents identify static confidence
   - v2 confidence changes when setup evidence changes

3. State synchronization tests
   - rejected or absent platform position does not block future entries forever
   - stop state is reset when no platform position exists
   - exit intents require actual platform position state

4. Indicator timing tests
   - v1 remains fidelity-compatible
   - v2 current closed-bar helpers do not use future bars
   - v2 signals appear no later than v1 when the extra lag is the only difference

5. Runtime stop semantics check
   - verify whether `TradeIntentExitPolicy.PERCENT` is executable or advisory
   - avoid duplicate stop exits if the runtime owns stop execution

6. Replay comparison
   - compare v1 fidelity vs v2 adaptive
   - compare StandardizedDQS vs StrategyPlanDQS
   - compare per-regime performance
   - compare confidence calibration and diagnostic quality
   - inspect lifecycle traces for entry, exit, scale, horizon, and evidence quality

## Assumptions And Open Checks

- Current closed-bar indicator values are replay-safe because strategy evaluation runs after bar close and fills at next bar open.
- The contract may or may not already expose full-close sizing and max-bars horizon factories. If not, add them to the platform contract before relying on synthetic encodings.
- The runtime semantics for `TradeIntentExitPolicy.PERCENT` must be verified before changing Multi V6 stop execution.
- Position state must expose enough information for v2 features such as average entry, bars held, scale count, and current R multiple. If not, add the missing lifecycle context or keep the v2 feature disabled.
- Dynamic confidence scoring will need calibration against replay results. The point tables above are initial scoring models, not final calibrated weights.

## Scope

In scope:

- Doflamingo sample-pack lifecycle alignment
- v1 metadata and intent-contract fixes
- v2 ATX-adaptive strategy design
- validation plan for lifecycle evaluation

Out of scope:

- changing v1 alpha behavior beyond metadata and intent-contract alignment
- changing the lifecycle DDR
- changing platform runtime stop execution before verifying current semantics
- production parameter calibration without replay evidence
