# EMA Trend Structure Pullback Lifecycle V2 Plan

Status: implementation plan

Applies to: `ema-trend-structure-pullback-strategy`

Current strategy ID:

- `ema-trend-structure-pullback-v1`

Replacement strategy ID:

- `ema-trend-structure-pullback-v2`

## Context

`ema-trend-structure-pullback-v1` is a signal-only EMA20/50/200 continuation strategy. It computes closed-bar EMA structure, arms pullbacks, scores setup candidates, and emits one `TradeSignal` when a pullback continuation or bullish transition breakout confirms.

That is enough for standardized signal evaluation, but it does not let ATX evaluate the strategy lifecycle. The strategy does not emit entry, exit, scale-out, scale-in, max-holding, stale-trade, or position-management intents. `StrategyPlanDQS` therefore cannot judge exit quality, scale quality, duration discipline, or risk control from the strategy's own plan.

The platform contract already supports the lifecycle surface needed for v2:

- Capabilities include `TRADE_INTENT`, `LONG_ENTRY_INTENT`, `SHORT_ENTRY_INTENT`, `EXIT_INTENT`, `SCALE_IN_INTENT`, `SCALE_OUT_INTENT`, and `RISK_AWARE_SIZING`.
- Actions include `ENTER_LONG`, `ENTER_SHORT`, `EXIT_LONG`, `EXIT_SHORT`, `SCALE_IN_LONG`, `SCALE_IN_SHORT`, `SCALE_OUT_LONG`, and `SCALE_OUT_SHORT`.
- Sizing includes `RISK_FRACTION`, `CLOSE_FRACTION`, and `SCALE_FRACTION`.
- Runtime stop execution is proven for percent and RR-style offsets. For v2, EMA/ATR/structure stop anchors must be converted to a percent stop policy before emitting an entry intent.

## Decision

Replace the current signal-only v1 implementation with lifecycle v2 logic in the same module. Do not preserve backward compatibility for v1.

Use:

- Strategy ID: `ema-trend-structure-pullback-v2`
- Version: `2.0.0`
- Display name: `EMA Trend Structure Pullback Lifecycle`
- Provider ID: keep `atx-strategy-samples`

Keep the existing EMA setup engine as the entry detector, but change the strategy from `TradeSignalStrategy` to `TradeIntentStrategy`.

V2 emits:

- `TradeSignal` plus `ENTER_LONG` or `ENTER_SHORT` for accepted entry setups.
- Lifecycle-only `SCALE_OUT_*`, optional `SCALE_IN_*`, and `EXIT_*` intents while a platform position exists.
- Structured `StrategyTradeIntentReason.conditions` for every emitted intent.

No new platform contract, BFF, or Web fields are required.

## Mechanism

### Provider Surface

The provider descriptor must advertise:

- `LONG_SIGNALS`
- `SHORT_SIGNALS`
- `TRADE_INTENT`
- `LONG_ENTRY_INTENT`
- `SHORT_ENTRY_INTENT`
- `EXIT_INTENT`
- `SCALE_OUT_INTENT`
- `SCALE_IN_INTENT`
- `RISK_AWARE_SIZING`
- `PARAMETERIZED`

Keep the existing chart studies:

- EMA fast overlay
- EMA medium overlay
- EMA slow overlay
- EMA Trend Structure pane

Keep existing v1 parameters and defaults:

- `fastEmaPeriod = 20`
- `mediumEmaPeriod = 50`
- `slowEmaPeriod = 200`
- `slopeLookbackBars = 5`
- `flatSlopeThresholdPct = 0.05`
- `compressedSeparationThresholdPct = 0.50`
- `expandingSeparationThresholdPct = 1.50`
- `chopCrossLookbackBars = 20`
- `chopCrossCountThreshold = 5`
- `pullbackLookbackBars = 8`
- `pullbackMinBars = 2`
- `emaTouchTolerancePct = 0.20`
- `maxDistanceFromFastEmaPct = 3.00`
- `idealDistanceFromFastEmaPct = 2.00`
- `maxDistanceFromMediumEmaPct = 6.00`
- `priorBreakoutLookbackBars = 3`
- `transitionBreakoutLookbackBars = 5`
- `minConfidence = 0.70`
- `allowShorts = false`
- `cooldownBars = 10`

Add lifecycle parameters:

- `riskFraction = 0.01`
- `maxHoldingBars = 48`
- `staleBars = 16`
- `staleMinR = 0.25`
- `stopMode = EMA50_OR_ATR`
- `atrPeriod = 14`
- `atrStopMultiple = 1.5`
- `minStopPct = 0.50`
- `maxStopPct = 3.00`
- `enableScaleOut = true`
- `scaleOutAtR = 1.0`
- `scaleOutFraction = 0.50`
- `trailAfterScaleOut = true`
- `enableScaleIn = false`
- `scaleInAtR = 0.50`
- `maxScaleIns = 1`
- `scaleInFraction = 0.25`
- `breakEvenAfterScaleOut = true`
- `exitOnCompression = true`
- `exitOnChop = true`

Validation additions:

- `riskFraction` must be `0.00..0.02`.
- `scaleOutFraction` and `scaleInFraction` must be `0.01..1.00`.
- `staleBars < maxHoldingBars`.
- `minStopPct <= maxStopPct`.
- `scaleOutAtR > 0`.
- `scaleInAtR > 0`.
- `maxScaleIns >= 0`.

### Evaluation Flow

On every bar:

1. Build closed-bar EMA series and current `EmaSnapshot`.
2. If indicators are not ready, emit nothing.
3. Read `context.instrumentPosition()` and use it as the source of truth.
4. If a position exists, evaluate management intents in this order:
   - hard exit
   - scale-out
   - scale-in
   - stale or max-holding exit
   - otherwise hold by emitting no intent
5. If no position exists:
   - reset local lifecycle drift when needed
   - arm pullbacks
   - evaluate entry candidates
   - emit `TradeSignal` plus entry intent when the strongest candidate meets `minConfidence`

Hard exits must win over scale-in. Scale-out should win over stale exit when the trade is profitable. The strategy should not emit an entry while the platform reports an open instrument position.

### Entry Logic

Reuse current v1 setup candidates:

- bullish pullback continuation
- bullish transition breakout
- bearish pullback continuation when `allowShorts=true`

Long pullback entry still requires:

- bullish EMA alignment
- EMA20 and EMA50 rising
- clean structure
- not compressed
- not choppy
- real pullback toward or below EMA20
- pullback held EMA50
- current bar closes back above EMA20
- current close above previous close
- close location at least `0.65`
- distance from EMA20 not above `maxDistanceFromFastEmaPct`
- confidence at least `minConfidence`
- no current platform position

Short entry mirrors the long logic when `allowShorts=true`.

Entry intent shape:

- `entry = TradeIntentEntry.marketNextOpen()`
- `sizing = RISK_FRACTION` with `riskFraction`
- `horizon = new TradeIntentHorizon(maxHoldingBars, null, INTRADAY)`
- `exitPolicy = computed percent stop`
- `preconditions = no existing position`
- action is `ENTER_LONG` or `ENTER_SHORT`

Emit a matching `TradeSignal` for signal analytics and fusion.

### Stop Policy

Compute a strategy stop from visible closed-bar data, then emit it as a `PERCENT` stop rule.

For long entries:

- `emaStop = EMA50 - atrStopMultiple * ATR`
- `pullbackStop = recentPullbackLow - atrStopMultiple * ATR`
- `atrStop = close - atrStopMultiple * ATR`
- choose the stop below close that produces the widest valid distance before clamping
- `stopPct = clamp(distancePct(close, selectedStop), minStopPct, maxStopPct)`

For short entries:

- `emaStop = EMA50 + atrStopMultiple * ATR`
- `pullbackStop = recentPullbackHigh + atrStopMultiple * ATR`
- `atrStop = close + atrStopMultiple * ATR`
- choose the stop above close that produces the widest valid distance before clamping
- `stopPct = clamp(distancePct(selectedStop, close), minStopPct, maxStopPct)`

`stopMode = EMA50_OR_ATR` means use EMA50/pullback structure and ATR as competing anchors, then convert the selected anchor to a percent stop. Do not emit duplicate manual stop exits for the same entry stop. Runtime owns entry stop execution.

### Position Management

Scale-out:

- Emit `SCALE_OUT_LONG` or `SCALE_OUT_SHORT` when `enableScaleOut=true`.
- Require `position.scaleOutCount() == 0`.
- Require `currentR >= scaleOutAtR`.
- Require `position.maxFavorablePct() > 0`.
- Require trend not fully broken.
- Use `SCALE_FRACTION` with `requestedFraction=scaleOutFraction`.

Trail after scale-out:

- When `trailAfterScaleOut=true` and `position.scaleOutCount() > 0`, exit remainder on:
  - close beyond EMA20 against the position and EMA20 slope weakens, or
  - close beyond EMA50 against the position.

Stale exit:

- Emit full close when `barsHeld >= staleBars` and `currentR <= staleMinR`.

Structure-break exit:

- Long exits when any condition is true:
  - close below EMA50
  - EMA stack becomes `MIXED_STACK`
  - EMA stack becomes `BEARISH_STACK`
  - compression appears and `currentR <= 0`
  - recent EMA cross count reaches threshold and `currentR <= 0`
- Short exits mirror those rules.

Max-holding exit:

- Emit full close when `barsHeld >= maxHoldingBars`.
- Do not extend the hold in v2. Future calibration can revisit trend-based hold extension after replay evidence exists.

Scale-in:

- Keep disabled by default.
- When enabled, emit `SCALE_IN_LONG` or `SCALE_IN_SHORT` only if:
  - `currentR >= scaleInAtR`
  - `position.scaleInCount() < maxScaleIns`
  - position is profitable
  - trend stack remains aligned
  - a renewed pullback to EMA20 holds EMA50
  - current bar reclaims EMA20
  - no compression or chop condition is present
- Use `SCALE_FRACTION` with `requestedFraction=scaleInFraction`.
- Set `TradeIntentPreconditions.maxScaleCount=maxScaleIns`.
- Never scale into a losing position.

### Strategy Thinking Evidence

Every intent must use `StrategyTradeIntentReason(summary, evidence, tags, conditions)`.

Stable condition IDs are required because the Web thinking pane keys condition rows by ID.

Entry condition IDs:

- `ema-v2.bullish-stack` or `ema-v2.bearish-stack`
- `ema-v2.slope-aligned`
- `ema-v2.pullback-real`
- `ema-v2.pullback-held-ema50`
- `ema-v2.reclaim-ema20`
- `ema-v2.not-compressed`
- `ema-v2.not-choppy`
- `ema-v2.distance-acceptable`
- `ema-v2.stop-distance-acceptable`
- `ema-v2.confidence-threshold`

Scale-out condition IDs:

- `ema-v2.scale-out-r-multiple`
- `ema-v2.scale-out-favorable-excursion`
- `ema-v2.scale-out-trend-valid`

Scale-in condition IDs:

- `ema-v2.scale-in-r-positive`
- `ema-v2.scale-in-count-available`
- `ema-v2.scale-in-renewed-pullback`
- `ema-v2.scale-in-not-choppy`
- `ema-v2.scale-in-confidence-threshold`

Exit condition IDs:

- `ema-v2.exit-structure-break`
- `ema-v2.exit-stale-bars`
- `ema-v2.exit-stale-r`
- `ema-v2.exit-post-scale-trail`
- `ema-v2.exit-max-holding`
- `ema-v2.exit-compression`
- `ema-v2.exit-chop`

Evidence strings should include:

- setup kind
- strength score
- confidence
- EMA stack
- trend structure
- EMA20 slope percent
- EMA50 slope percent
- EMA separation percent
- recent cross count
- distance from EMA20 percent
- stop mode
- stop percent
- risk fraction
- max holding bars
- position bars held and current R for lifecycle intents

Tags should include:

- `ema-trend-structure`
- `v2`
- `lifecycle`
- `entry`, `exit`, `scale-out`, or `scale-in`
- `pullback` or `transition`
- `risk`
- `confidence`

### Confidence Model

Entry confidence:

- Keep existing strength score calculation.
- Compute `confidence = clamp(strengthScore / 100, 0.50, 0.95)`.
- Add v2 modifiers before clamping:
  - `+0.05` when stop distance is inside the ideal range.
  - `-0.10` when stop distance is clamped at `maxStopPct`.
  - `-0.10` when platform reports existing instrument exposure; in normal flow this should suppress entry before scoring.

Scale-out confidence:

- Base `0.75`.
- Add `0.10` when `currentR >= 1.5`.
- Add `0.05` when MFE is positive.
- Subtract `0.05` when EMA20 slope is weakening.
- Clamp to `0.50..0.95`.

Exit confidence:

- `0.90` for close beyond EMA50 with mixed or opposite stack.
- `0.80` for post-scale trailing weakness.
- `0.75` for compression or chop while losing.
- `0.70` for stale trade.
- `0.65` for max holding timeout.
- Use the highest applicable reason confidence when multiple exit reasons are true.

Scale-in confidence:

- Start from the renewed setup strength score.
- Require final confidence at least `0.80`.
- Reject scale-in if confidence is below `0.80`, even if `minConfidence` is lower.

## Alternatives Considered

1. Add a separate v2 module and keep v1 unchanged.
   - Rejected for this task because backward compatibility is explicitly not required. Replacing the existing strategy keeps one catalog entry and one module to maintain.

2. Keep signal-only output and rely on standardized evaluation.
   - Rejected because the goal is lifecycle evaluation through `StrategyPlanDQS`. Signal-only output cannot express exits, scale-outs, stale exits, or max holding discipline.

3. Emit ATR, structure, or indicator stop rule types directly.
   - Rejected for v2 implementation because runtime execution is safest with percent/RR offsets today. The strategy will compute EMA/ATR/structure stops internally and emit a percent stop policy.

4. Enable scale-in by default.
   - Rejected because scale-in increases exposure and can overfit replay results. V2 should first validate entry, exit, and scale-out behavior.

5. Emit explicit `HOLD` intents.
   - Rejected for now. No intent is cleaner unless the UI or evaluator needs hold traces later.

## Trade-Offs And Consequences

- Replacing v1 changes the strategy ID and output shape. Existing scenarios that reference `ema-trend-structure-pullback-v1` must be updated to `ema-trend-structure-pullback-v2`.
- V2 becomes position-state dependent. Unit tests must construct `StrategyInstrumentPosition` states for exits, scale-outs, and scale-ins.
- Percent stop mapping loses the semantic distinction between EMA50, pullback-low/high, and ATR stops at runtime. The reason evidence must preserve the selected anchor and raw stop values for review.
- Recomputing EMA series on each bar remains acceptable for sample strategy scope. If replay performance becomes an issue, introduce rolling EMA state later.
- Short lifecycle is supported by contract and should be implemented, but `allowShorts=false` remains the default.

## Test Plan

Provider tests:

- Descriptor ID is `ema-trend-structure-pullback-v2`.
- Version is `2.0.0`.
- Capabilities include lifecycle, scale, short-entry, and risk-aware sizing capabilities.
- Parameter defaults include lifecycle fields.
- Validation rejects invalid EMA period ordering, invalid stop ranges, invalid stale/max holding relationship, invalid scale fractions, and invalid scale thresholds.
- ServiceLoader discovers the provider.

Entry tests:

- Bullish pullback emits `TradeSignal` plus `ENTER_LONG`.
- Bearish pullback emits `ENTER_SHORT` only when `allowShorts=true`.
- Entry intent uses `MARKET_NEXT_OPEN`.
- Entry intent uses `RISK_FRACTION`.
- Entry intent has `maxHoldingBars`.
- Entry intent has a percent stop policy.
- Entry reason includes stable condition evidence.
- Entry confidence changes with strength/stop evidence.
- No entry is emitted when a platform position exists.

Exit tests:

- Close below EMA50 emits `EXIT_LONG`.
- Mixed stack while losing emits `EXIT_LONG`.
- Compression while losing emits `EXIT_LONG` when `exitOnCompression=true`.
- Chop while losing emits `EXIT_LONG` when `exitOnChop=true`.
- `staleBars` plus low R emits `EXIT_LONG`.
- `maxHoldingBars` emits `EXIT_LONG`.
- Post-scale weakness emits `EXIT_LONG` when `trailAfterScaleOut=true`.
- Full-close exits use `CLOSE_FRACTION` with `requestedFraction=1.0`.

Scale-out tests:

- `currentR >= scaleOutAtR` emits `SCALE_OUT_LONG`.
- Scale-out emits once when `scaleOutCount == 0`.
- Scale-out uses `SCALE_FRACTION` with `scaleOutFraction`.
- Scale-out evidence includes R multiple, MFE, trend validity, and confidence.

Scale-in tests:

- Scale-in is disabled by default.
- Scale-in is emitted only when enabled and position R is positive.
- Scale-in is blocked when losing.
- Scale-in is blocked after `maxScaleIns`.
- Scale-in is blocked in chop/compression.
- Scale-in requires confidence at least `0.80`.

Runtime checks:

- Run `mvn -f atx-strategy-samples/pom.xml -pl ema-trend-structure-pullback-strategy test`.
- Run `mvn -f atx-strategy-samples/pom.xml package`.
- Add a replay scenario for the v2 sample and verify report JSON contains `StrategyPlanDQS` lifecycle traces, condition evidence, source bar IDs, position linkage IDs, and accepted entry/scale/exit intents.

## Assumptions

- Current closed-bar EMA values are replay-safe because strategy evaluation runs after the bar close and fills at next bar open.
- `context.instrumentPosition()` is the lifecycle source of truth.
- Runtime owns entry stop execution; strategy should not duplicate the same entry stop as a manual exit.
- Percent stop policies are the safest executable stop representation for the first v2 implementation.
- Global portfolio exposure controls are out of scope because the strategy currently has instrument-position visibility, not full portfolio risk allocation.

## Scope

In scope:

- Replace `ema-trend-structure-pullback-v1` code with v2 lifecycle behavior.
- Update provider descriptor, parameters, validation, capabilities, tests, docs, and sample scenario references.
- Add deterministic Strategy Thinking evidence for every emitted intent.

Out of scope:

- Preserving v1 strategy ID or signal-only behavior.
- Adding new platform contract fields.
- Adding new Web/BFF rendering behavior.
- Calibrating parameters for production trading.
