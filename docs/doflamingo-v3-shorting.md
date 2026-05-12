# DDR — Doflamingo V3 Shorting Extension

## 1. Status

**Proposed for implementation after Doflamingo V2 lifecycle logic is present.**

This DDR defines how to create Doflamingo V3 strategies by preserving the existing V2 long-side lifecycle logic and adding short-side lifecycle logic.

The key rule is:

```text
V3 = V2 long lifecycle logic unchanged + additional short lifecycle logic
```

V3 must not be a rewrite of the long strategy. It is a short-enabled extension of the already implemented V2 strategy behavior.

---

## 2. Applies To

Doflamingo strategy pack:

```text
doflamingo-strategy-pack
```

Recommended V3 strategy IDs:

```text
doflamingo-multi-indicator-v6-trend-reversal-v3
doflamingo-ichimoku-mo-002-beta-v3
```

Existing V2 strategy IDs are assumed to already exist and should remain unchanged.

```text
doflamingo-multi-indicator-v6-trend-reversal-v2
doflamingo-ichimoku-mo-002-beta-v2
```

V3 providers should use a new strategy ID/version rather than silently changing V2 behavior.

Recommended versions:

```text
strategyVersion = 3.0.0
providerId = doflamingo-strategy-pack
```

---

## 3. Context

The Doflamingo V2 strategies are lifecycle-aware ATX strategies. They are expected to emit strategy trade intents such as:

```text
ENTER_LONG
EXIT_LONG
SCALE_OUT_LONG
SCALE_IN_LONG, if supported and enabled
HOLD/no-op when no lifecycle action is required
```

They should be evaluated through `StrategyPlanDQS`, while signal-only strategies still fall back to the ATX standardized 20-candle evaluation policy.

V3 extends the same lifecycle model to short-side behavior.

V3 must support:

```text
ENTER_SHORT
EXIT_SHORT
SCALE_OUT_SHORT, if scale-out is supported
SCALE_IN_SHORT, if scale-in is supported and enabled
optional reversal intent only if platform contracts already support it safely
```

The platform remains responsible for:

```text
risk validation
portfolio constraints
final decision
execution simulation
outcome truth
StrategyPlanDQS
reporting
```

The strategy proposes lifecycle intent. It does not directly execute trades.

---

## 4. Problem Statement

Doflamingo V2 can evaluate long-side lifecycle plans more fairly than signal-only DQS, but it still cannot answer whether the strategy logic works in bearish market conditions.

Without short support:

- bearish regimes are either ignored or only used as long-exit contexts;
- long-only strategies may show poor run-set performance during downtrends;
- strategy suitability analysis cannot distinguish “strategy does not work” from “strategy is missing short-side logic”; and
- `StrategyPlanDQS` cannot evaluate whether mirrored bearish logic improves return, drawdown, and capital efficiency.

V3 should add short-side logic while preserving V2 long-side behavior exactly, so V2 and V3 can be compared cleanly.

---

## 5. Decision Summary

ATX will add Doflamingo V3 variants with the same V2 long logic and additional short lifecycle logic.

### Decisions

1. V3 must preserve V2 long-side entry, exit, scaling, confidence, risk, and horizon logic.
2. V3 must add explicit short-side lifecycle intents.
3. V3 must expose short capabilities through strategy descriptors.
4. V3 must make shorting configurable through `allowShorts`.
5. V3 should default `allowShorts` to `true`, because the purpose of V3 is short support, while still allowing users to disable shorts.
6. V3 must be instrument-position-aware and must not open a short while a long position is active unless reversal is explicitly enabled and supported.
7. V3 must evaluate short exits, stop policies, scale-outs, stale-trade exits, and max-holding exits symmetrically to the long side.
8. V3 must include deterministic Strategy Thinking evidence for every short intent.
9. V3 must not change platform evaluation, BFF, Web, or broker behavior except where capability projections or tests require updates.

---

## 6. Non-Goals

V3 does **not** introduce:

- broker-connected short selling;
- real borrow availability checks;
- margin engine;
- portfolio optimizer;
- auto parameter optimization;
- ML/LLM signal classification;
- short support for instruments that the platform marks as non-shortable;
- hidden strategy-owned PnL truth;
- unmanaged martingale or unlimited doubling down.

If instrument shortability or margin is not yet modeled, V3 should expose short intent and let platform policy accept, reject, or mark the intent unsupported.

---

## 7. Strategy Capability Requirements

V3 provider descriptors must advertise both long and short lifecycle capabilities.

Recommended descriptor capabilities for both V3 strategies:

```text
LONG_SIGNALS
SHORT_SIGNALS
TRADE_INTENT
LONG_ENTRY_INTENT
SHORT_ENTRY_INTENT
EXIT_INTENT
RISK_AWARE_SIZING
PARAMETERIZED
```

If the current enum set supports more granular lifecycle capabilities, also use:

```text
SCALE_OUT_INTENT
SCALE_IN_INTENT
REVERSAL_INTENT, only if reversal is implemented
```

If these enums do not exist, Codex should not invent names blindly. It should inspect the current `StrategyCapability` enum and use only supported values, adding new capability enum values only if they are required by the current lifecycle DDR and implementation pattern.

---

## 8. Common V3 Parameters

V3 should keep all V2 parameters and add or expose short-side controls.

Recommended common short-related parameters:

```yaml
allowShorts: true
allowReversal: false
allowShortScaleIn: false
allowShortScaleOut: true
maxShortScaleIns: 1
shortScaleInAtR: 0.50
shortScaleOutAtR: 1.00
shortScaleOutFraction: 0.50
shortStopMode: MIRROR_LONG_STOP
shortMaxHoldingBars: same as long maxHoldingBars
shortStaleBars: same as long staleBars
shortStaleMinR: same as long staleMinR
```

If V2 already has side-neutral lifecycle parameters, reuse them instead of adding side-specific duplicates:

```text
enableScaleOut
scaleOutAtR
scaleOutFraction
trailAfterScaleOut
maxHoldingBars
staleBars
staleMinR
riskFraction
stopMode
atrStopMultiple
minStopPct
maxStopPct
```

Parameter validation must ensure:

```text
allowShorts is boolean
scaleOutFraction is > 0 and <= 1
shortScaleInAtR > 0
shortScaleOutAtR > 0
maxShortScaleIns >= 0
shortMaxHoldingBars > 0
shortStaleBars < shortMaxHoldingBars
riskFraction remains within platform-allowed range
```

---

## 9. Position-State Rules

V3 must treat the platform position state as the source of truth.

On each bar:

```text
if no instrument position exists:
  evaluate long and short entries

if a long position exists:
  run V2 long management logic unchanged
  do not emit ENTER_SHORT unless reversal is explicitly enabled and supported

if a short position exists:
  run V3 short management logic
  do not emit ENTER_LONG unless reversal is explicitly enabled and supported
```

### Reversal handling

Default:

```text
allowReversal = false
```

If an opposite setup appears while a position is open:

```text
if allowReversal = false:
  emit exit intent for current position if current position has invalidation
  do not immediately enter opposite side

if allowReversal = true and platform supports reversal:
  emit REVERSE_LONG_TO_SHORT or REVERSE_SHORT_TO_LONG

if allowReversal = true but platform lacks reversal intent:
  emit full exit intent only
  let the next bar decide new entry
```

This avoids accidental simultaneous long and short exposure in the same instrument.

---

# Part A — Multi Indicator V6 Trend Reversal V3

## 10. V2 Long Logic Must Remain Unchanged

V3 must preserve existing V2 long behavior.

Long entry remains the V2 logic:

```text
PSAR direction is UP
AND (MACD buy pattern OR Stoch RSI bullish oversold crossover)
AND price/cloud confirmation passes V2 long filter
AND no existing platform position
AND confidence >= minConfidence
```

Long lifecycle management remains V2 logic:

```text
EXIT_LONG on stop / reversal / stale / structure break / max holding according to V2
SCALE_OUT_LONG according to V2
SCALE_IN_LONG if V2 already supports it and it is enabled
```

Codex must not modify V2 long-side thresholds, confidence scoring, state transitions, or lifecycle rules except for shared helper refactoring required to support short-side symmetry.

---

## 11. Multi V6 Short Entry Logic

V3 adds a bearish mirror of the V2 long reversal logic.

### Primary short entry setup

Emit short setup only when:

```text
allowShorts = true
AND no current instrument position exists
AND PSAR direction is DOWN
AND (MACD sell pattern OR Stoch RSI bearish overbought crossover)
AND price is below bearish cloud threshold
AND anti-chop / risk / confidence filters pass
```

### PSAR bearish condition

```text
psarDirectionNowDown = PSAR is above current price/low according to existing V2 PSAR direction model
```

Mirror the long-side PSAR direction logic. If long logic defines up as:

```text
psar < current low
```

then short logic should define down as:

```text
psar > current high
```

or the closest existing PSAR direction state from the current indicator helper.

### MACD sell pattern

Mirror the existing V2 MACD buy pattern.

Recommended bearish MACD pattern:

```text
macdHistogram current < 0
AND macdHistogram previous < 0
AND macdHistogram secondPrevious > 0
AND macdSignal > 0
```

If V2 already improved the MACD sell condition, reuse the V2 sell condition exactly.

### Stoch RSI bearish overbought crossover

Mirror the long-side oversold bullish crossover.

Recommended bearish Stoch condition:

```text
previousK >= previousD
AND currentK < currentD
AND currentK > stochOverbought
```

If V2 uses a dynamic or smoother stochastic signal, mirror that implementation.

### Cloud confirmation for short

Prefer a stronger bearish cloud condition than just `close < SpanB`.

Recommended default:

```text
close < min(presentSpanA, presentSpanB)
```

Strict mode option:

```text
high < min(presentSpanA, presentSpanB)
```

Reason:

```text
For short entry, price should be below the cloud, not merely below one span.
```

Parameter:

```text
shortCloudMode = CLOSE_BELOW_CLOUD | HIGH_BELOW_CLOUD
```

Default:

```text
CLOSE_BELOW_CLOUD
```

### Short entry rejection rules

Reject short entry if any of these are true:

```text
allowShorts = false
position already exists
PSAR direction is not DOWN
MACD/Stoch bearish confirmation absent
price is not below cloud threshold
confidence < minConfidence
recent PSAR whipsaw / chop filter fails
entry is too far below cloud/EMA support
platform instrument is not shortable, if shortability is modeled
```

---

## 12. Multi V6 Short Stop and Risk Policy

Short stop must be above the short entry.

Supported stop modes should mirror long modes.

Recommended stop candidates:

```text
PERCENT stop:
  stop = entryPrice * (1 + stopLossPct / 100)

ATR stop:
  stop = entryPrice + atrStopMultiple * ATR

CLOUD stop:
  stop = max(presentSpanA, presentSpanB) + cloudStopBuffer
```

If V2 already supports a side-neutral stop calculation helper, extend it to support short direction.

If the current `TradeIntentExitPolicy` supports only percent stops, map the selected short stop distance into a percent stop:

```text
shortStopPct = abs(stop - entryPrice) / entryPrice * 100
```

Open question for implementation:

> Does the current runtime execute `TradeIntentExitPolicy` direction-aware for short positions? If not, V3 must either add direction-aware support or emit explicit stop exit intents when short stop is hit.

---

## 13. Multi V6 Short Exit Logic

Short exit mirrors long exit.

### Hard stop exit

Emit `EXIT_SHORT` when:

```text
current high >= activeShortStop
```

If platform exit policy auto-executes stops, do not duplicate this as a strategy intent. Instead, include the stop in entry exit policy and rely on the runtime.

### Bearish reversal invalidation exit

For short positions, exit when bullish reversal is confirmed:

```text
PSAR flipped from DOWN to UP
AND close > cloud threshold
AND (MACD buy signal OR Stoch RSI bullish signal)
```

Recommended cloud threshold:

```text
close > max(presentSpanA, presentSpanB)
```

Strict mode option:

```text
low > max(presentSpanA, presentSpanB)
```

### Structure break exit

Emit `EXIT_SHORT` when:

```text
close > max(presentSpanA, presentSpanB)
```

or when V2 trend/EMA/context filters detect short thesis invalidation.

### Stale short exit

Emit `EXIT_SHORT` when:

```text
barsHeld >= staleBars
AND currentR <= staleMinR
```

For shorts, `currentR` must be calculated direction-aware:

```text
profit increases when price moves down
```

### Max-holding short exit

Emit `EXIT_SHORT` when:

```text
barsHeld >= maxHoldingBars
```

unless V2 has an explicit trend-extension rule and V3 chooses to mirror it.

---

## 14. Multi V6 Short Scale-Out Logic

If V2 supports scale-out, V3 should mirror it for shorts.

Emit `SCALE_OUT_SHORT` when:

```text
allowShorts = true
enableScaleOut = true
position side is SHORT
scaleOutCount == 0
currentR >= scaleOutAtR
max favorable excursion is positive
short thesis is not invalidated
```

Default:

```text
scaleOutAtR = 1.0
scaleOutFraction = 0.50
```

After scale-out, exit remaining short if:

```text
close reclaims cloud
OR PSAR flips up with bullish confirmation
OR post-scale trailing rule is hit
```

---

## 15. Multi V6 Short Scale-In Logic

Scale-in should be disabled by default.

If enabled, only scale into winning shorts.

Emit `SCALE_IN_SHORT` only when:

```text
enableScaleIn = true
position side is SHORT
currentR >= shortScaleInAtR
scaleInCount < maxShortScaleIns
price forms renewed bearish continuation after pullback
PSAR remains DOWN
MACD/Stoch bearish confirmation renews
price remains below cloud threshold
not choppy
```

Hard reject scale-in if:

```text
currentR < 0
price is above cloud
PSAR no longer down
chop/whipsaw filter fails
max scale-ins reached
```

This prevents martingale-style behavior.

---

## 16. Multi V6 Short Confidence Scoring

Use dynamic confidence. Do not use fixed confidence for v3 short entries.

Recommended short confidence score: 100 points.

```text
PSAR bearish direction / reversal quality       20
MACD/Stoch bearish confirmation                 25
Cloud breakdown confirmation                    20
Risk location / non-extension                   15
Anti-chop / structure cleanliness               20
```

Suggested details:

```text
PSAR bearish direction / reversal quality:
  +10 PSAR direction is DOWN
  +5 recent PSAR flipped from UP to DOWN
  +5 PSAR distance from price is meaningful but not extended

MACD/Stoch bearish confirmation:
  +12 MACD sell pattern
  +8 Stoch RSI overbought bearish cross
  +5 both MACD and Stoch agree, capped so total section <= 25

Cloud breakdown confirmation:
  +10 close below cloud
  +5 strict high below cloud, if present
  +5 cloud/span direction supports resistance

Risk location / non-extension:
  +8 stop distance inside acceptable range
  +4 entry not far below cloud
  +3 close location bearish, e.g. close in lower candle range

Anti-chop / structure cleanliness:
  +8 no recent PSAR whipsaw
  +5 no mixed/choppy context
  +4 volatility not extreme against the setup
  +3 bearish trend or neutral-to-bearish structure
```

Final confidence:

```text
confidence = clamp(score / 100, 0.50, 0.95)
emit short only if confidence >= minConfidence
```

---

# Part B — Ichimoku Momentum 002 Beta V3

## 17. V2 Long Logic Must Remain Unchanged

V3 must preserve existing V2 long behavior.

Long entry remains the V2 cloud/momentum transition logic:

```text
price/cloud long confirmation passes
EMA9 / cloud momentum filter passes
present/future cloud transition is bullish
conversionLine > baseLine
trendScore > trendAverage
trendScore > 0
no existing position
confidence >= minConfidence
```

Long exits, scale-outs, stale exits, risk exits, and max-holding exits remain V2 logic.

Codex must not alter V2 long-side behavior except for shared helper extraction needed to implement mirrored short logic.

---

## 18. Ichimoku V3 Short Entry Logic

Ichimoku V3 adds a bearish mirror of the bullish cloud-transition setup.

### Primary short entry setup

Emit short setup only when:

```text
allowShorts = true
AND no current instrument position exists
AND price is below bearish cloud threshold
AND EMA9 is below bearish cloud/momentum threshold
AND present cloud is green
AND future cloud is red
AND conversionLine < baseLine
AND trendScore < trendAverage
AND trendScore < 0
AND confidence >= minConfidence
```

### Present cloud bearish-transition mirror

Long setup uses:

```text
present cloud red
future cloud green
```

Short setup should use:

```text
present cloud green
future cloud red
```

In terms of spans:

```text
present cloud green:
  presentSpanA > presentSpanB

future cloud red:
  futureSpanB > futureSpanA
```

If the existing implementation names red/green differently, use the existing V2 color semantics and mirror them.

### Price below cloud

Recommended short price confirmation:

```text
high < min(presentSpanA, presentSpanB)
```

Less strict option:

```text
close < min(presentSpanA, presentSpanB)
```

Parameter:

```text
shortCloudPriceMode = HIGH_BELOW_CLOUD | CLOSE_BELOW_CLOUD
```

Default:

```text
HIGH_BELOW_CLOUD
```

Rationale:

```text
Ichimoku 002 is a structural strategy. For shorting, the candle should ideally trade below the cloud, not merely close slightly under one span.
```

### EMA9 bearish momentum filter

Mirror long `EMA9 > presentSpanA`.

Recommended:

```text
EMA9 < presentSpanB
```

or stricter:

```text
EMA9 < min(presentSpanA, presentSpanB)
```

Parameter:

```text
shortEmaCloudMode = EMA9_BELOW_SPAN_B | EMA9_BELOW_CLOUD
```

Default:

```text
EMA9_BELOW_SPAN_B
```

### Trend acceleration bearish mirror

Long requires:

```text
trendScore > trendAverage
trendScore > 0
```

Short requires:

```text
trendScore < trendAverage
trendScore < 0
```

---

## 19. Ichimoku V3 Short Exit Logic

Short exit mirrors long cloud invalidation.

### Original long exit mirror

If long exit is:

```text
presentSpanA > candle high
```

then the short mirror should be:

```text
presentSpanA < candle low
```

Interpretation:

```text
price is now above Span A enough that the bearish cloud thesis is no longer valid.
```

This symmetry should be tested directly.

### Additional short safety exits

V3 should also support safer lifecycle exits if V2 long already supports their bullish equivalents.

Emit `EXIT_SHORT` when any of these are true:

```text
close > max(presentSpanA, presentSpanB)
conversionLine > baseLine for N bars
EMA9 > presentSpanA or EMA9 > max(presentSpanA, presentSpanB)
barsHeld >= maxHoldingBars
staleBars reached and currentR <= staleMinR
protective stop hit
```

Codex should mirror V2 long exits where equivalent long exits exist.

---

## 20. Ichimoku V3 Short Stop and Risk Policy

Ichimoku shorts require a protective stop, especially because the original long strategy had limited explicit risk control.

Recommended stop candidates:

```text
CLOUD stop:
  stop = max(presentSpanA, presentSpanB) + cloudStopBuffer

BASELINE stop:
  stop = max(baseLine, presentSpanA, presentSpanB) + buffer

ATR stop:
  stop = entry + atrStopMultiple * ATR
```

If runtime only supports percent stop policy:

```text
shortStopPct = abs(stop - entryPrice) / entryPrice * 100
```

Cap using:

```text
minStopPct
maxStopPct
```

Reject short entries if computed stop is too wide:

```text
shortStopPct > maxStopPct
```

---

## 21. Ichimoku V3 Short Scale-Out Logic

If V2 long supports scale-out, mirror it.

Emit `SCALE_OUT_SHORT` when:

```text
position side is SHORT
currentR >= scaleOutAtR
scaleOutCount == 0
bearish thesis has not invalidated
```

After scale-out, exit remaining short if:

```text
price reclaims cloud
conversionLine crosses above baseLine
EMA9 reclaims cloud
post-scale trailing rule is hit
```

---

## 22. Ichimoku V3 Short Confidence Scoring

Use signal-strength confidence, not fixed confidence.

Recommended score: 100 points.

```text
Bearish cloud transition                    25
Price/cloud bearish confirmation            20
Conversion/base bearish confirmation         10
Trend acceleration bearish confirmation      25
Risk location / non-extension                10
Market cleanliness / anti-chop               10
```

Suggested details:

```text
Bearish cloud transition:
  +10 present cloud green
  +10 future cloud red
  +5 future bearish cloud spread is meaningful or expanding

Price/cloud confirmation:
  +10 candle below cloud threshold
  +5 EMA9 below bearish cloud threshold
  +5 close not too far below cloud

Conversion/base:
  +10 conversionLine < baseLine

Trend acceleration:
  +10 trendScore < trendAverage
  +8 trendScore < 0
  +5 trendScore deterioration is meaningful
  +2 recent trend slope confirms down move

Risk location:
  +5 computed stop distance inside ideal range
  +5 entry not overextended below cloud

Cleanliness:
  +5 not choppy/mixed
  +5 volatility not extreme against the setup
```

Final confidence:

```text
confidence = clamp(score / 100, 0.50, 0.95)
emit short only if confidence >= minConfidence
```

---

# Part C — Shared Strategy Thinking Requirements

## 23. Deterministic Strategy Thinking

V3 must emit deterministic Strategy Thinking evidence for every short intent.

This is not LLM text. It is structured evidence.

Recommended fields in intent reason/evidence:

```text
strategyId
strategyVersion
side=SHORT
action=ENTER_SHORT | EXIT_SHORT | SCALE_OUT_SHORT | SCALE_IN_SHORT
setupName
confidence
strengthScore
condition pass/fail summary
indicator values
risk values
position values
```

## 24. Multi V6 Short Entry Evidence

Example evidence:

```text
setup=multi-v6-short-reversal
psarDirection=DOWN
psarCurrent=...
macdHistogram=...
macdPreviousHistogram=...
macdSignal=...
stochK=...
stochD=...
presentSpanA=...
presentSpanB=...
cloudMode=CLOSE_BELOW_CLOUD
close=...
shortStopPct=...
confidenceScore=...
confidence=...
```

Condition evidence examples:

```text
doflamingo-v3.short.psar-down=PASS
doflamingo-v3.short.macd-sell=PASS|FAIL
doflamingo-v3.short.stoch-sell=PASS|FAIL
doflamingo-v3.short.cloud-confirmation=PASS
doflamingo-v3.short.risk-location=PASS
doflamingo-v3.short.confidence-threshold=PASS
```

## 25. Ichimoku Short Entry Evidence

Example evidence:

```text
setup=ichimoku-beta-short-cloud-transition
presentCloud=GREEN
futureCloud=RED
conversionLine=...
baseLine=...
ema9=...
presentSpanA=...
presentSpanB=...
futureSpanA=...
futureSpanB=...
trendScore=...
trendAverage=...
shortStopPct=...
confidenceScore=...
confidence=...
```

Condition evidence examples:

```text
doflamingo-v3.short.present-cloud-green=PASS
doflamingo-v3.short.future-cloud-red=PASS
doflamingo-v3.short.conversion-below-base=PASS
doflamingo-v3.short.trend-negative=PASS
doflamingo-v3.short.trend-below-average=PASS
doflamingo-v3.short.price-below-cloud=PASS
doflamingo-v3.short.ema9-below-cloud=PASS
doflamingo-v3.short.confidence-threshold=PASS
```

---

# Part D — Runtime and Evaluation Semantics

## 26. Short PnL and R-Multiple Semantics

For short positions, all plan metrics must be direction-aware.

Short profit:

```text
profitPct = (entryPrice - exitPrice) / entryPrice * 100
```

Short loss:

```text
lossPct = (entryPrice - exitPrice) / entryPrice * 100
```

Negative value means price rose against the short.

Short R multiple:

```text
currentR = unrealizedShortProfitAbs / initialRiskAbs
```

Where:

```text
initialRiskAbs = stopPrice - entryPrice
```

for a short.

MFE/MAE must also be direction-aware:

```text
short MFE = favorable downward movement after entry
short MAE = adverse upward movement after entry
```

Codex must verify the current StrategyPlan evaluator already handles this correctly before relying on V3 short tests.

---

## 27. StrategyPlanDQS Compatibility

V3 should be evaluated by `StrategyPlanDQS`.

Short-side performance should contribute to the same component families:

```text
ReturnQuality
RiskControlQuality
LifecycleManagementQuality
CapitalEfficiencyQuality
RobustnessQuality
ConfidenceQuality
```

Short lifecycle behavior must be visible in component diagnostics:

```text
short entry count
short exit count
short scale-out count
short scale-in count
short win rate
short profit factor
short average R
short max drawdown contribution
short confidence calibration
short invalid directive rate
```

If current StrategyPlanDQS does not expose side-split diagnostics, add this as a recommended follow-up or implement if the existing artifact model supports it.

---

## 28. Signal-Only Fallback Rule

V3 strategies should emit trade intents. If a strategy emits only `SHORT` or `LONG` `TradeSignal` without lifecycle intent, ATX should evaluate it under the standardized signal-only policy.

Default signal-only policy remains:

```text
20-candle ATX standardized evaluation
```

V3 should not rely on signal-only fallback except for backward-compatible signal analytics.

---

# Part E — Provider, Catalog, and Chart Studies

## 29. Provider Requirements

V3 providers must:

- use new V3 strategy IDs;
- expose lifecycle and short capabilities;
- include `allowShorts` in parameter schema;
- include short lifecycle parameters where applicable;
- keep existing V2 long chart studies;
- add no misleading chart studies that imply backend truth;
- validate short-related parameters;
- create V3 strategy instances only after validation passes.

Provider display names:

```text
Doflamingo Multi Indicator V6 Trend Reversal V3 Shorting
Doflamingo Ichimoku Momentum 002 Beta V3 Shorting
```

Descriptions should say:

```text
V3 preserves V2 long lifecycle behavior and adds short lifecycle intent logic.
```

## 30. Chart Studies

Use existing V2 chart studies. V3 does not require new chart studies unless the current Web can visually distinguish short intent markers.

Recommended chart markers:

```text
ENTER_SHORT marker
EXIT_SHORT marker
SCALE_OUT_SHORT marker
SCALE_IN_SHORT marker, if enabled
```

If chart marker support is already direction-aware, no extra work is required.

---

# Part F — Tests

## 31. Shared V3 Tests

Codex should add tests for both V3 strategies:

```text
provider exposes SHORT_SIGNALS and SHORT_ENTRY_INTENT
allowShorts=false blocks all short entries
allowShorts=true allows short entries when mirrored conditions pass
existing long setup still emits the same long intent as V2
existing long management remains unchanged from V2
no position -> evaluates long/short candidates
long position -> does not emit ENTER_SHORT by default
short position -> does not emit ENTER_LONG by default
short exit emits full short close intent
short stop is above short entry
short confidence is dynamic, not fixed
short reason includes deterministic Strategy Thinking evidence
```

## 32. Multi V6 V3 Tests

```text
PSAR down + MACD sell + below cloud emits ENTER_SHORT
PSAR down + Stoch overbought sell + below cloud emits ENTER_SHORT
missing MACD/Stoch bearish confirmation emits no short
cloud confirmation failure emits no short
short stop hit emits EXIT_SHORT or is represented by executable stop policy
PSAR flip up + bullish confirmation emits EXIT_SHORT
short scale-out emits when currentR >= scaleOutAtR
short scale-in blocked while position is losing
short stale exit emits after staleBars when currentR <= staleMinR
```

## 33. Ichimoku V3 Tests

```text
present cloud green + future cloud red + conversion below base + negative trend acceleration emits ENTER_SHORT
price not below cloud emits no short
EMA9 not below bearish threshold emits no short
trendScore >= 0 emits no short
trendScore >= trendAverage emits no short
presentSpanA < candle low emits EXIT_SHORT
close above cloud emits EXIT_SHORT
short stale exit emits after staleBars when currentR <= staleMinR
short scale-out emits when currentR >= scaleOutAtR
```

## 34. Evaluation Tests

```text
StrategyPlan evaluator calculates short PnL directionally
short R multiple is positive when price moves down
short MFE/MAE are direction-aware
short scale-out reduces exposure correctly
full EXIT_SHORT closes short exposure
invalid shorting on non-shortable instrument is rejected or marked by platform policy
```

---

# Part G — Acceptance Criteria

V3 is complete when:

1. V3 strategy IDs exist for both Doflamingo strategies.
2. V2 long logic is preserved and tests confirm no long-side behavior regression.
3. V3 emits `ENTER_SHORT` when mirrored short setup conditions pass.
4. V3 emits `EXIT_SHORT` for stop, reversal, structure break, stale, or max-holding exits according to strategy-specific rules.
5. V3 emits `SCALE_OUT_SHORT` if V2 scale-out is supported and enabled.
6. V3 does not emit short entries when `allowShorts=false`.
7. V3 does not open simultaneous long and short positions by default.
8. V3 descriptor capabilities include short and lifecycle capability surface.
9. V3 short confidence is strength-based, not fixed.
10. V3 short intent reasons include deterministic Strategy Thinking evidence.
11. StrategyPlan evaluation handles short PnL, R, MFE, MAE, exits, and scale-outs correctly.
12. BFF/Web projections can display short lifecycle intents without computing strategy logic.
13. Tests pass.

---

# Part H — Open Implementation Questions

Codex must inspect the current implementation and resolve or record these questions.

## Q1. Short action enums

Does the current lifecycle action enum already include:

```text
ENTER_SHORT
EXIT_SHORT
SCALE_OUT_SHORT
SCALE_IN_SHORT
REVERSE_LONG_TO_SHORT
REVERSE_SHORT_TO_LONG
```

If not, add only the enum values required by the lifecycle DDR and V3 implementation.

## Q2. Position-side API

Does `context.instrumentPosition()` expose:

```text
side
averageEntryPrice
barsHeld
scaleOutCount
scaleInCount
currentR
maxFavorableExcursion
maxAdverseExcursion
```

If not, Codex should use the current available fields and document any missing fields.

## Q3. Full short close sizing

Does `TradeIntentSizing` support full close semantics for shorts?

Preferred:

```text
closeFraction(1.0)
```

If not, add or use the closest existing full-close representation.

## Q4. Direction-aware stop policy

Does `TradeIntentExitPolicy.PERCENT` understand side direction?

Long stop:

```text
entry * (1 - pct)
```

Short stop:

```text
entry * (1 + pct)
```

If not, V3 must either add direction-aware execution support or emit explicit strategy stop exits.

## Q5. Runtime execution of exit policy

Does the plan runtime execute exit policies, or are they advisory metadata?

If executable:

```text
avoid duplicate manual stop exits
```

If advisory:

```text
strategy must emit explicit stop exits
```

## Q6. Shortability constraints

Does ATX currently model whether an instrument can be shorted?

If yes:

```text
short intent should be rejected for non-shortable instruments
```

If no:

```text
V3 may emit short intent, and platform should treat shortability as a future governance policy
```

## Q7. Reversal support

Does the platform support atomic reversal intent?

If yes, V3 may optionally support reversal with `allowReversal=true`.

If no, default behavior should be:

```text
exit current side first
wait for next bar for opposite entry
```

## Q8. DQS side-split diagnostics

Does `StrategyPlanDQS` artifact expose long-vs-short breakdown?

If not, add or defer side-split diagnostics. V3 should at least tag each intent and outcome with side so later diagnostics can be computed.

---

# Implementation Instruction Summary for Codex

Implement Doflamingo V3 as short-enabled variants.

```text
Create:
  doflamingo-multi-indicator-v6-trend-reversal-v3
  doflamingo-ichimoku-mo-002-beta-v3

Preserve:
  all V2 long entry/exit/scale/risk/confidence/horizon behavior

Add:
  allowShorts parameter
  mirrored short entry logic
  EXIT_SHORT lifecycle logic
  SCALE_OUT_SHORT if scale-out exists
  SCALE_IN_SHORT only if supported and enabled
  short-specific confidence scoring
  short-specific Strategy Thinking evidence
  provider short capabilities
  tests
```

Do not alter V2 strategy IDs or silently change V2 long behavior.

