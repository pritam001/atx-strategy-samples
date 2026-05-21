# Doflamingo Momentum V5 Beta — Intraday and Swing Strategy Specifications

**Strategy family:** Doflamingo Momentum V5 Beta
**Markets:** Indian equities, NSE/BSE cash equities, NSE index futures, liquid F&O stocks
**Design status:** Implementation-ready beta specification
**Primary objective:** Identify directional momentum, enter long/short, ride continuation, scale only into winners, and exit when the momentum thesis fails.
**Important:** This is a deterministic strategy-design document, not investment advice. The goal is to maximize *net expectancy under bounded drawdown*, not to guarantee maximum profit.

---

## 0. Source-grounded design assumptions

This spec is designed for the ATX Slice-1 strategy runtime and uses `TradeIntentStrategy`, because the strategy must emit lifecycle-aware intents: entry, exit, scale-out, optional scale-in, risk-aware sizing, stop policy, target policy, and evidence. The ATX Strategy Author Guide defines `TradeIntentStrategy` as the SPI to use when the strategy must say “enter now with these exits” rather than merely emit a signal, and it expects lifecycle capabilities such as long/short entry, exit, scale, reversal, and risk-aware sizing to be advertised by the provider.

The design also inherits the Doflamingo lifecycle direction: preserve previous versions as baselines, create a new strategy ID for material behavior changes, add closed-bar indicators, dynamic confidence, explicit horizon, better risk exits, optional scale-out, max holding bars, anti-chop or regime filters, and structured evidence.

Momentum V5 is deliberately split into two separate beta strategies:

```text
1. doflamingo-momentum-v5-beta-intraday
2. doflamingo-momentum-v5-beta-swing
```

Do **not** ship this as one broad “intraday + swing” profile by default. The risk model, holding horizon, stop width, session behavior, and gap exposure are different enough that they should be separate catalog entries.

---

## 1. Shared family thesis

Doflamingo Momentum V5 Beta exploits directional continuation after structure and momentum align. The core edge is not a single indicator. It is a confluence event:

```text
multi-timeframe cloud structure
+ trend compression / expansion
+ momentum confirmation
+ participation / volume pulse
+ adaptive risk location
+ lifecycle management
```

The strategy should avoid predicting bottoms and tops. It should wait until the market has shown a directional expansion event, then participate with defined risk. It should add only when the position is already working, scale out at predefined R-multiples, and let a smaller runner continue until cloud, momentum, PSAR, Chandelier, or time-stop evidence invalidates the thesis.

---

## 2. Shared indicator model

### 2.1 Ichimoku cloud

Use the textbook Ichimoku tuple as fixed defaults, not as the first optimization surface:

```yaml
ichimokuConversionPeriod: 9
ichimokuBasePeriod: 26
ichimokuSpanBPeriod: 52
ichimokuDisplacement: 26
```

Derived features are the tunable parts:

```text
cloudTop = max(spanA, spanB)
cloudFloor = min(spanA, spanB)
cloudThicknessAtr = abs(spanA - spanB) / ATR
futureCloudBias = BULLISH if futureSpanA > futureSpanB else BEARISH
futureSpreadAtr = abs(futureSpanA - futureSpanB) / ATR
priceDistanceFromCloudAtr = abs(close - cloud boundary) / ATR
conversionBaseState = BULLISH if conversionLine > baseLine else BEARISH
```

Implementation warning:

```text
Do not use future candles. “Future cloud” means the leading span values computed from data visible at the current closed bar and projected by displacement. It does not mean looking ahead into future price bars.
```

### 2.2 Moving averages

Use moving averages as trend and compression lenses, not as standalone entry triggers.

```yaml
emaFastPeriod: 9
emaMidPeriod: 20
emaAnchorPeriod: 50
smaSlowPeriod: 200        # optional, mostly for swing/equity filtering
```

Long structural preference:

```text
EMA9 > EMA20 > EMA50
close > EMA20
EMA20 slope >= 0
```

Short structural preference:

```text
EMA9 < EMA20 < EMA50
close < EMA20
EMA20 slope <= 0
```

### 2.3 MACD

```yaml
macdFastPeriod: 12
macdSlowPeriod: 26
macdSignalPeriod: 9
```

Long confirmation:

```text
macdLine > signalLine
histogram > 0
histogram rising over last 2 bars
```

Short confirmation:

```text
macdLine < signalLine
histogram < 0
histogram falling over last 2 bars
```

### 2.4 Stochastic RSI

```yaml
stochRsiPeriod: 14
stochRsiK: 3
stochRsiD: 3
```

Long confirmation:

```text
K > D
K between 20 and 85
no bearish K/D cross in last 2 bars
```

Short confirmation:

```text
K < D
K between 15 and 80
no bullish K/D cross in last 2 bars
```

### 2.5 PSAR

```yaml
psarAccelerationStep: 0.02
psarAccelerationMax: 0.20
```

Long confirmation:

```text
PSAR below close
no fresh bearish flip
```

Short confirmation:

```text
PSAR above close
no fresh bullish flip
```

### 2.6 ATR and Chandelier stop

```yaml
atrPeriod: 14
chandelierLookbackBars: 22
```

Long Chandelier:

```text
highestHighSinceEntry - chandelierAtrMultiple * ATR
```

Short Chandelier:

```text
lowestLowSinceEntry + chandelierAtrMultiple * ATR
```

### 2.7 Volume pulse

```text
volumePulse = currentVolume / SMA(volume, 20)
```

Use volume as a participation filter. It should not override broken structure.

---

## 3. Shared lifecycle event vocabulary

Both V5 strategies should emit one of these event states per closed bar:

```text
NO_EVENT
WARMUP
NO_TRADE_SESSION_FILTER
NO_TRADE_REGIME_FILTER
NO_TRADE_RISK_FILTER
NO_TRADE_CHOP_FILTER
LONG_ARMED
SHORT_ARMED
ENTER_LONG
ENTER_SHORT
HOLD_LONG
HOLD_SHORT
SCALE_OUT_LONG
SCALE_OUT_SHORT
SCALE_IN_LONG
SCALE_IN_SHORT
EXIT_LONG_STOP
EXIT_SHORT_STOP
EXIT_LONG_STRUCTURE_BREAK
EXIT_SHORT_STRUCTURE_BREAK
EXIT_LONG_MOMENTUM_DECAY
EXIT_SHORT_MOMENTUM_DECAY
EXIT_LONG_TIME_STOP
EXIT_SHORT_TIME_STOP
EXIT_LONG_EOD
EXIT_SHORT_EOD
COOLDOWN
```

Even when no trade is emitted, diagnostics should record the dominant reason:

```text
warmupMissingBars
sessionGateBlocked
regimeGateBlocked
cloudBiasFailed
momentumConfirmationFailed
riskDistanceRejected
positionAlreadyOpen
cooldownActive
```

---

## 4. Shared confidence model

Use a transparent weighted score. Do not emit a fixed confidence value.

```text
Cloud structure alignment        30 points
Higher-timeframe alignment       15 points
Momentum confirmation            20 points
Volume / participation           10 points
Risk location                    15 points
Market cleanliness               10 points
```

Then:

```text
confidence = clamp(score / 100, 0.50, 0.95)
```

Reject new entries when:

```text
confidence < minConfidence
```

Recommended evidence fields:

```text
strategyId
strategyVersion
horizonMode
primaryTimeframe
contextTimeframes
side
entryMode
cloudTop
cloudFloor
futureCloudBias
conversionBaseState
h1CloudBias or d1CloudBias
emaStackState
macdLine
macdSignal
macdHistogram
macdHistogramSlope
stochRsiK
stochRsiD
psarDirection
volumePulse
atr
priceDistanceFromCloudAtr
initialStop
stopPct
targetR
currentR if position exists
barsHeld if position exists
confidence
blockedReason if rejected
```

---

# Part A — Doflamingo Momentum V5 Beta Intraday

## A1. Identity

```yaml
strategyId: doflamingo-momentum-v5-beta-intraday
strategyVersion: 5.0.0-beta.1
displayName: Doflamingo Momentum V5 Beta Intraday
family: DOFLAMINGO
style: MULTI_CLOUD_MOMENTUM_RIDER
spi: TradeIntentStrategy
defaultHorizon: INTRADAY
allowOvernight: false
```

## A2. Thesis

The intraday variant is designed to capture short-term momentum bursts in NIFTY, BANKNIFTY, and liquid F&O names after a valid expansion event. It is strongest during opening continuation, gap momentum continuation, trend-day expansion, and power-hour continuation. It avoids passive midday chop unless there is unusually strong volume and ATR expansion.

This strategy should be treated as a same-day momentum rider:

```text
Find directional expansion → enter next open → scale out into strength → trail/exit runner → flatten by end of day.
```

## A3. SPI choice

Use `TradeIntentStrategy`.

Reason:

```text
The strategy must emit entry, stop, target, scale-out, optional scale-in, stale exits, time exits, EOD exits, and structured evidence. Signal-only output cannot represent the intended lifecycle.
```

## A4. Descriptor sketch

```yaml
strategyId: doflamingo-momentum-v5-beta-intraday
strategyVersion: 5.0.0-beta.1
provider: atx-strategy-samples
spi: TradeIntentStrategy

supportedTimeframes:
  - M1
  - M5
  - M15

preferredTimeframes:
  - M15

requiredContextTimeframes: []
optionalContextTimeframes: []

contextTimeframeRules:
  - primaryTimeframe: M1
    contextTimeframe: M5
    required: true
  - primaryTimeframe: M5
    contextTimeframe: M15
    required: true
  - primaryTimeframe: M15
    contextTimeframe: H1
    required: true

supportedAssetClasses:
  - INDEX
  - FUTURES
  - EQUITY

capabilities:
  - LONG_SIGNALS
  - SHORT_SIGNALS
  - TRADE_INTENT
  - LONG_ENTRY_INTENT
  - SHORT_ENTRY_INTENT
  - EXIT_INTENT
  - SCALE_OUT_INTENT
  - SCALE_IN_INTENT
  - RISK_AWARE_SIZING
  - PARAMETERIZED
```

## A5. Intraday parameters

```yaml
# Cloud structure
ichimokuConversionPeriod: 9
ichimokuBasePeriod: 26
ichimokuSpanBPeriod: 52
ichimokuDisplacement: 26
h1CloudBiasMode: REQUIRE_AGREEMENT     # OFF | PREFER_AGREEMENT | REQUIRE_AGREEMENT
useDailyBias: false
entryMode: HYBRID                      # STRICT_CLOUD | EARLY_TRANSITION | PULLBACK_RESUME | HYBRID

# Trend and momentum
emaFastPeriod: 9
emaMidPeriod: 20
emaAnchorPeriod: 50
macdFastPeriod: 12
macdSlowPeriod: 26
macdSignalPeriod: 9
stochRsiPeriod: 14
stochRsiK: 3
stochRsiD: 3
psarAccelerationStep: 0.02
psarAccelerationMax: 0.20

# Participation
volumeLookbackBars: 20
volumePulseMultiple: 1.5
strongVolumePulseMultiple: 2.0
atrExpansionMultiple: 1.10

# Session gates, IST
skipOpeningMinutes: 15
openingContinuationStart: "09:30"
openingContinuationEnd: "10:45"
middayStart: "11:30"
middayEnd: "13:45"
powerHourStart: "14:15"
freshEntryCutoff: "15:05"
forceExitTime: "15:15"
expiryDayMode: SKIP_INDEX_FRESH_ENTRY  # OFF | SKIP_INDEX_FRESH_ENTRY | SKIP_ALL_FRESH_ENTRY

# Risk
riskFraction: 0.005
maxRiskFractionAfterScaleIn: 0.010
atrPeriod: 14
initialAtrStopMultiple: 1.20
cloudStopBufferAtr: 0.25
chandelierLookbackBars: 22
chandelierAtrMultiple: 2.50
minStopPct: 0.40
maxStopPct: 2.50

# Profit capture
initialTargetR: 2.50
enableScaleOut: true
scaleOutAtR: 1.25
scaleOutFraction: 0.40
runnerTargetR: 3.50

# Scale-in / pyramiding
enableScaleIn: false
scaleInAtR: 1.25
scaleInFraction: 0.25
maxScaleIns: 1
scaleInRequiresNewExtreme: true

# Momentum decay / stale exits
staleBars: 10
staleMinR: 0.30
maxHoldingBars: 24
cooldownBars: 4
structureExitConfirmBars: 1

# Quality gates
minConfidence: 0.65
maxEntryAtrFromCloudTop: 3.00
maxEntryAtrFromCloudTopStrongVolume: 4.00
minCloudThicknessAtr: 0.05
minFutureSpreadAtr: 0.05
skipMarketRegimes:
  - RANGING_LOW_VOLATILITY
  - RANGING_HIGH_VOLATILITY
```

## A6. Warmup requirements

The strategy should emit `WARMUP` until enough bars exist for all primary and context indicators.

Recommended minimums:

```text
M15 primary history: max(ichimokuSpanBPeriod + ichimokuDisplacement, emaAnchorPeriod, macdSlowPeriod + macdSignalPeriod, stochRsiPeriod + stochRsiK + stochRsiD, chandelierLookbackBars) + 10
H1 context history: ichimokuSpanBPeriod + ichimokuDisplacement + 5
Volume history: volumeLookbackBars + 5
```

Practical default:

```text
primaryWarmupBars = 90
h1WarmupBars = 80
```

## A7. Session gates

### A7.1 Default no-entry windows

```text
09:15–09:30 IST:
  No fresh entry by default. Allow only if explicit gap-momentum mode is enabled.

11:30–13:45 IST:
  Suppress fresh entries unless volumePulse >= strongVolumePulseMultiple
  AND ATR expansion is true.

After 15:05 IST:
  No fresh entries.

15:15 IST onwards:
  Force flatten for intraday mode.
```

### A7.2 Preferred windows

```text
09:30–10:45:
  Opening continuation, ORB continuation, gap follow-through.

14:15–15:05:
  Power-hour continuation.
```

### A7.3 Expiry behavior

Default:

```text
For index instruments, skip fresh entries on configured weekly/monthly expiry days.
```

Current SPI fallback:

```text
Because exact expiry-day support may not be first-class in the runtime, use a deterministic parameter list or BFF-prepared scenario tag when available. Do not let the strategy read arbitrary local calendar files during replay.
```

## A8. Intraday long setup

A long setup requires all major groups to pass.

### A8.1 Higher-timeframe permission

If `h1CloudBiasMode = REQUIRE_AGREEMENT`:

```text
H1 close > H1 cloudTop
AND H1 futureCloudBias == BULLISH
AND H1 conversionLine >= H1 baseLine
```

If `h1CloudBiasMode = PREFER_AGREEMENT`:

```text
Add confidence when H1 agrees.
Do not block unless H1 is strongly bearish.
```

If `h1CloudBiasMode = OFF`:

```text
Do not use H1 as a gate, but still include H1 evidence when available.
```

### A8.2 Primary cloud structure

```text
close > cloudTop
futureCloudBias == BULLISH
conversionLine > baseLine
cloudThicknessAtr >= minCloudThicknessAtr
futureSpreadAtr >= minFutureSpreadAtr
```

### A8.3 EMA structure

At least one of these should pass:

```text
Strict trend stack:
  EMA9 > EMA20 > EMA50
  AND close > EMA20

Cloud momentum stack:
  EMA9 > cloudTop
  AND EMA20 slope >= 0

Pullback-resume stack:
  close > EMA20
  AND prior low touched EMA20 or cloudTop within 0.35 ATR in last 5 bars
  AND current close breaks prior 3-bar high
```

### A8.4 Momentum confirmation

Require at least two of three:

```text
MACD bullish:
  macdLine > signalLine
  AND histogram rising over last 2 bars

Stoch RSI bullish:
  K > D
  AND K between 20 and 85

PSAR bullish:
  PSAR below close
```

### A8.5 Participation filter

```text
volumePulse >= volumePulseMultiple
```

For midday entries:

```text
volumePulse >= strongVolumePulseMultiple
AND ATR / SMA(ATR, 20) >= atrExpansionMultiple
```

### A8.6 Anti-chase filter

```text
priceDistanceFromCloudAtr = (close - cloudTop) / ATR
```

Rules:

```text
if priceDistanceFromCloudAtr <= maxEntryAtrFromCloudTop:
  pass

if maxEntryAtrFromCloudTop < priceDistanceFromCloudAtr <= maxEntryAtrFromCloudTopStrongVolume:
  pass only if volumePulse >= strongVolumePulseMultiple
  AND MACD histogram is expanding

if priceDistanceFromCloudAtr > maxEntryAtrFromCloudTopStrongVolume:
  reject
```

## A9. Intraday short setup

The short setup mirrors the long setup.

### A9.1 Higher-timeframe permission

If `h1CloudBiasMode = REQUIRE_AGREEMENT`:

```text
H1 close < H1 cloudFloor
AND H1 futureCloudBias == BEARISH
AND H1 conversionLine <= H1 baseLine
```

### A9.2 Primary cloud structure

```text
close < cloudFloor
futureCloudBias == BEARISH
conversionLine < baseLine
cloudThicknessAtr >= minCloudThicknessAtr
futureSpreadAtr >= minFutureSpreadAtr
```

### A9.3 EMA structure

At least one of these should pass:

```text
Strict trend stack:
  EMA9 < EMA20 < EMA50
  AND close < EMA20

Cloud momentum stack:
  EMA9 < cloudFloor
  AND EMA20 slope <= 0

Pullback-resume stack:
  close < EMA20
  AND prior high touched EMA20 or cloudFloor within 0.35 ATR in last 5 bars
  AND current close breaks prior 3-bar low
```

### A9.4 Momentum confirmation

Require at least two of three:

```text
MACD bearish:
  macdLine < signalLine
  AND histogram falling over last 2 bars

Stoch RSI bearish:
  K < D
  AND K between 15 and 80

PSAR bearish:
  PSAR above close
```

### A9.5 Participation and anti-chase

```text
volumePulse >= volumePulseMultiple
priceDistanceFromCloudAtr = (cloudFloor - close) / ATR
```

Use the same anti-chase thresholds as the long side.

## A10. Intraday trigger modes

### A10.1 STRICT_CLOUD

Long:

```text
previous close was <= cloudTop within last 5 bars
current close > cloudTop
futureCloudBias == BULLISH
momentum confirmation passes
```

Short:

```text
previous close was >= cloudFloor within last 5 bars
current close < cloudFloor
futureCloudBias == BEARISH
momentum confirmation passes
```

### A10.2 PULLBACK_RESUME

Long:

```text
existing bullish cloud state
price pulled back to EMA20 or cloudTop within 0.35 ATR
current close breaks prior 3-bar high
MACD histogram resumes rising
```

Short:

```text
existing bearish cloud state
price pulled back to EMA20 or cloudFloor within 0.35 ATR
current close breaks prior 3-bar low
MACD histogram resumes falling
```

### A10.3 EARLY_TRANSITION

Long:

```text
futureCloudBias flips BULLISH within last 3 bars
close > baseLine
conversionLine > baseLine
volumePulse >= strongVolumePulseMultiple
PSAR bullish
```

Short:

```text
futureCloudBias flips BEARISH within last 3 bars
close < baseLine
conversionLine < baseLine
volumePulse >= strongVolumePulseMultiple
PSAR bearish
```

Use early transition mode carefully; it should have a higher `minConfidence` or require stronger volume.

## A11. Entry policy

```yaml
entryPolicy:
  type: MARKET_NEXT_OPEN
```

Rationale:

```text
The decision is made from a fully closed bar. The simulated entry should occur at the next bar open to avoid same-bar lookahead.
```

## A12. Initial stop logic

### A12.1 Long initial stop candidates

```text
cloudStop = min(cloudFloor, baseLine) - cloudStopBufferAtr * ATR
atrStop = entry - initialAtrStopMultiple * ATR
swingStop = lastSwingLow(5 to 10 bars) - 0.25 * ATR
```

Use the nearest valid stop that is still below entry and not inside obvious noise:

```text
initialStop = max(valid cloudStop, atrStop, swingStop)
```

### A12.2 Short initial stop candidates

```text
cloudStop = max(cloudTop, baseLine) + cloudStopBufferAtr * ATR
atrStop = entry + initialAtrStopMultiple * ATR
swingStop = lastSwingHigh(5 to 10 bars) + 0.25 * ATR
```

Use the nearest valid stop that is still above entry:

```text
initialStop = min(valid cloudStop, atrStop, swingStop)
```

### A12.3 Stop quality filter

```text
stopPct = abs(entry - initialStop) / entry * 100
```

Reject if:

```text
stopPct < minStopPct
stopPct > maxStopPct
```

## A13. Target policy

Initial target:

```text
target = entry + initialTargetR * initialRisk for long
target = entry - initialTargetR * initialRisk for short
```

Intent metadata:

```yaml
targetPolicy:
  type: R_MULTIPLE
  value: 2.50
```

## A14. Scale-out rules

Long or short:

```text
if enableScaleOut
AND currentR >= scaleOutAtR
AND scaleOutCount == 0
AND position still aligned with cloud
THEN emit SCALE_OUT side with scaleOutFraction
```

Default:

```yaml
scaleOutAtR: 1.25
scaleOutFraction: 0.40
```

After scale-out, the remaining runner exits on:

```text
Chandelier trail
PSAR reversal with momentum confirmation
cloud structure break
stale/time stop
EOD flatten
```

Because stop mutation may not be supported directly, do not rely on modifying the active stop after scale-out unless the runtime has an explicit `MODIFY_EXIT` action. Emit explicit exit intents when the runner invalidates.

## A15. Scale-in rules

Default:

```yaml
enableScaleIn: false
```

Experimental intraday scale-in may be enabled only for winners.

Long scale-in:

```text
position side is LONG
currentR >= scaleInAtR
scaleInCount < maxScaleIns
close makes new 5-bar high
volumePulse >= volumePulseMultiple
MACD histogram still rising
price remains above cloudTop
PSAR remains bullish
combined risk <= maxRiskFractionAfterScaleIn
```

Short scale-in:

```text
position side is SHORT
currentR >= scaleInAtR
scaleInCount < maxScaleIns
close makes new 5-bar low
volumePulse >= volumePulseMultiple
MACD histogram still falling
price remains below cloudFloor
PSAR remains bearish
combined risk <= maxRiskFractionAfterScaleIn
```

Reject scale-in when:

```text
currentR < 0
price reclaims/breaks the wrong side of cloud
PSAR flips against the position
cooldown active
session cutoff reached
```

## A16. Intraday exit rules

### A16.1 Long exits

Emit `EXIT_LONG_*` when any condition fires:

```text
STOP:
  low <= activeStop

CLOUD BREAK:
  close < cloudFloor

BASELINE BREAK:
  conversionLine < baseLine for structureExitConfirmBars

EMA MOMENTUM BREAK:
  close < EMA20
  AND MACD histogram falling

PSAR REVERSAL:
  PSAR flips bearish
  AND MACD or Stoch RSI confirms bearish reversal

CHANDERLIER TRAIL:
  close < longChandelierStop after scale-out or after currentR >= 1.0

STALE:
  barsHeld >= staleBars
  AND currentR <= staleMinR

TIME STOP:
  barsHeld >= maxHoldingBars

EOD FLATTEN:
  current IST time >= forceExitTime
```

### A16.2 Short exits

Emit `EXIT_SHORT_*` when any condition fires:

```text
STOP:
  high >= activeStop

CLOUD RECLAIM:
  close > cloudTop

BASELINE BREAK:
  conversionLine > baseLine for structureExitConfirmBars

EMA MOMENTUM BREAK:
  close > EMA20
  AND MACD histogram rising

PSAR REVERSAL:
  PSAR flips bullish
  AND MACD or Stoch RSI confirms bullish reversal

CHANDERLIER TRAIL:
  close > shortChandelierStop after scale-out or after currentR >= 1.0

STALE:
  barsHeld >= staleBars
  AND currentR <= staleMinR

TIME STOP:
  barsHeld >= maxHoldingBars

EOD FLATTEN:
  current IST time >= forceExitTime
```

## A17. Intraday confidence scoring detail

```text
Cloud structure alignment, 30:
  +10 close on correct side of cloud
  +8 future cloud aligned
  +6 conversion/base aligned
  +3 sufficient cloud thickness
  +3 sufficient future spread

H1 alignment, 15:
  +10 H1 close on correct side of cloud
  +5 H1 future cloud aligned

Momentum, 20:
  +8 MACD line/signal aligned
  +5 MACD histogram slope aligned
  +4 Stoch RSI aligned
  +3 PSAR aligned

Participation, 10:
  +7 volumePulse >= threshold
  +3 ATR expansion or strong session bucket

Risk location, 15:
  +8 stopPct inside ideal range
  +4 not overextended from cloud
  +3 targetR >= required minimum

Market cleanliness, 10:
  +5 not in skipped regime
  +3 not midday unless strong volume
  +2 cooldown/session gates clean
```

Entry requirement:

```text
confidence >= 0.65
```

Scale-in requirement:

```text
confidence >= 0.80
```

## A18. Intraday output example

```json
{
  "strategyId": "doflamingo-momentum-v5-beta-intraday",
  "eventType": "ENTER_LONG",
  "direction": "LONG",
  "entryPolicy": "MARKET_NEXT_OPEN",
  "confidence": 0.78,
  "risk": {
    "riskFraction": 0.005,
    "initialStop": 47680.0,
    "stopPct": 1.18,
    "targetR": 2.5,
    "scaleOutAtR": 1.25,
    "scaleOutFraction": 0.40
  },
  "evidence": {
    "primaryTimeframe": "M15",
    "contextTimeframe": "H1",
    "sessionBucket": "OPENING_CONTINUATION",
    "cloudState": "BULLISH_ABOVE_CLOUD",
    "futureCloudBias": "BULLISH",
    "h1CloudBias": "BULLISH",
    "conversionBaseState": "BULLISH",
    "emaStackState": "BULLISH_STACK",
    "macdHistogramSlope": "RISING",
    "stochRsiState": "BULLISH_NOT_OVERBOUGHT",
    "psarDirection": "BULLISH",
    "volumePulse": 1.82,
    "priceDistanceFromCloudAtr": 1.35
  }
}
```

---

# Part B — Doflamingo Momentum V5 Beta Swing

## B1. Identity

```yaml
strategyId: doflamingo-momentum-v5-beta-swing
strategyVersion: 5.0.0-beta.1
displayName: Doflamingo Momentum V5 Beta Swing
family: DOFLAMINGO
style: MULTI_CLOUD_MOMENTUM_RIDER
authoringMode: lifecycle-aware
spi: TradeIntentStrategy
defaultHorizon: SWING
allowOvernight: true
```

## B2. Thesis

The swing variant is designed to capture multi-day momentum continuation in liquid Indian equities and, selectively, index futures. It uses the selected execution timeframe with a mapped higher-timeframe cloud context for directional permission. It accepts overnight gap risk only when the higher-timeframe thesis is strong enough and risk is sized smaller than the intraday variant.

The swing strategy should be thought of as:

```text
Mapped context confirms direction → M5/M15/H1 pullback or breakout triggers entry → hold through multi-day continuation → scale out and trail using cloud/base/Chandelier evidence.
```

## B3. SPI choice

Use `TradeIntentStrategy`.

Reason:

```text
Swing trades require explicit overnight-aware risk, wider stops, time stops, runner logic, and multi-day invalidation. Signal-only output cannot describe the full plan.
```

## B4. Descriptor sketch

```yaml
strategyId: doflamingo-momentum-v5-beta-swing
strategyVersion: 5.0.0-beta.1
provider: atx-strategy-samples
spi: TradeIntentStrategy

supportedTimeframes:
  - M5
  - M15
  - H1

preferredTimeframes:
  - M15
  - H1

requiredContextTimeframes: []
optionalContextTimeframes: []

contextTimeframeRules:
  - primaryTimeframe: M5
    contextTimeframe: M15
    required: true
  - primaryTimeframe: M15
    contextTimeframe: H1
    required: true
  - primaryTimeframe: H1
    contextTimeframe: D1
    required: true

supportedAssetClasses:
  - EQUITY
  - FUTURES
  - INDEX

preferredInstruments:
  - liquid F&O equities
  - large-cap equities
  - NIFTY futures with explicit overnight-risk acceptance
  - BANKNIFTY futures with explicit overnight-risk acceptance

capabilities:
  - LONG_SIGNALS
  - SHORT_SIGNALS
  - TRADE_INTENT
  - LONG_ENTRY_INTENT
  - SHORT_ENTRY_INTENT
  - EXIT_INTENT
  - SCALE_OUT_INTENT
  - SCALE_IN_INTENT
  - RISK_AWARE_SIZING
  - PARAMETERIZED
```

## B5. Swing parameters

```yaml
# Cloud structure
ichimokuConversionPeriod: 9
ichimokuBasePeriod: 26
ichimokuSpanBPeriod: 52
ichimokuDisplacement: 26
contextCloudBiasMode: REQUIRE_AGREEMENT    # OFF | PREFER_AGREEMENT | REQUIRE_AGREEMENT
entryMode: HYBRID                          # BREAKOUT | PULLBACK_RESUME | EARLY_TRANSITION | HYBRID

# Trend and momentum
emaFastPeriod: 9
emaMidPeriod: 20
emaAnchorPeriod: 50
smaSlowPeriod: 200
macdFastPeriod: 12
macdSlowPeriod: 26
macdSignalPeriod: 9
stochRsiPeriod: 14
stochRsiK: 3
stochRsiD: 3
psarAccelerationStep: 0.02
psarAccelerationMax: 0.20

# Participation
volumeLookbackBars: 20
volumePulseMultiple: 1.20
strongVolumePulseMultiple: 1.60
atrExpansionMultiple: 1.05

# Swing risk
riskFraction: 0.003
maxRiskFractionAfterScaleIn: 0.007
atrPeriod: 14
initialAtrStopMultiple: 1.80
cloudStopBufferAtr: 0.35
chandelierLookbackBars: 22
chandelierAtrMultiple: 3.00
minStopPct: 0.80
maxStopPct: 6.00
maxGapAdversePct: 3.00

# Profit capture
initialTargetR: 3.00
enableScaleOut: true
scaleOutAtR: 1.50
scaleOutFraction: 0.50
runnerTargetR: 5.00

# Scale-in / pyramiding
enableScaleIn: false
scaleInAtR: 1.75
scaleInFraction: 0.25
maxScaleIns: 1
scaleInRequiresNewExtreme: true

# Holding horizon
staleBars: 20
staleMinR: 0.50
maxHoldingBars: 64          # H1 bars, roughly 8 trading days
cooldownBars: 6
structureExitConfirmBars: 2

# India-specific swing controls
avoidFreshEntryOnFridayAfternoon: true
avoidFreshEntryBeforeKnownHoliday: true
avoidFreshEntryBeforeEarnings: true
manualEventBlackoutMode: WARN_ONLY         # OFF | WARN_ONLY | BLOCK
allowIndexFuturesOvernight: false

# Quality gates
minConfidence: 0.68
scaleInMinConfidence: 0.82
maxEntryAtrFromCloudTop: 3.50
maxEntryAtrFromCloudTopStrongVolume: 5.00
minCloudThicknessAtr: 0.05
minFutureSpreadAtr: 0.05
skipMarketRegimes:
  - RANGING_LOW_VOLATILITY
```

## B6. Warmup requirements

Recommended minimums:

```text
M5/M15/H1 primary history: 120 bars
Mapped context history: 90 bars
Volume history: 25 bars on the execution timeframe
```

Swing should not emit trades without its mapped context unless `contextCloudBiasMode = OFF`.

## B7. Swing universe filters

Preferred instruments:

```text
liquid F&O equities
large-cap cash equities
sector leaders
index futures only when overnight risk is explicitly enabled
```

Avoid by default:

```text
illiquid small-caps
circuit-prone names
stocks with very low volume
fresh entries before known earnings if event blackout is configured
fresh overnight entries before known holidays
```

Current SPI fallback:

```text
If lot size, circuit limits, exact holidays, or earnings calendars are not exposed by the runtime, make them explicit scenario/universe responsibilities. The strategy should not read arbitrary local files during replay.
```

## B8. Swing long setup

### B8.1 Mapped context permission

If `contextCloudBiasMode = REQUIRE_AGREEMENT`:

```text
context close > context cloudTop
AND context futureCloudBias == BULLISH
AND context conversionLine >= context baseLine
```

Optional added mapped-context trend confirmation:

```text
context EMA20 > context EMA50
OR context close > context SMA200
```

If `contextCloudBiasMode = PREFER_AGREEMENT`:

```text
Use mapped-context alignment as a confidence modifier.
Block only if context close < context cloudFloor and context future cloud is bearish.
```

### B8.2 Execution timeframe structure

For M5/M15/H1:

```text
close > execution cloudTop
futureCloudBias == BULLISH
conversionLine > baseLine
cloudThicknessAtr >= minCloudThicknessAtr
futureSpreadAtr >= minFutureSpreadAtr
```

### B8.3 Swing entry modes

#### Breakout continuation

```text
close breaks above prior 10-bar high
AND close > cloudTop
AND volumePulse >= volumePulseMultiple
AND MACD histogram rising
```

#### Pullback-resume continuation

```text
D1 bullish permission is active
execution timeframe pulled back to EMA20, baseLine, or cloudTop
pullback did not close below cloudFloor
current close breaks prior 3-bar high
MACD histogram turns up or Stoch RSI crosses bullish
```

#### Early transition

Use only with stronger filters:

```text
futureCloudBias flips BULLISH within last 5 bars
close > baseLine
conversionLine > baseLine
volumePulse >= strongVolumePulseMultiple
PSAR bullish
D1 is not bearish
```

## B9. Swing short setup

Mirror the long setup.

### B9.1 Mapped context permission

If `contextCloudBiasMode = REQUIRE_AGREEMENT`:

```text
context close < context cloudFloor
AND context futureCloudBias == BEARISH
AND context conversionLine <= context baseLine
```

Optional daily trend confirmation:

```text
D1 EMA20 < D1 EMA50
OR D1 close < D1 SMA200
```

### B9.2 Execution timeframe structure

```text
close < execution cloudFloor
futureCloudBias == BEARISH
conversionLine < baseLine
cloudThicknessAtr >= minCloudThicknessAtr
futureSpreadAtr >= minFutureSpreadAtr
```

### B9.3 Short entry modes

#### Breakdown continuation

```text
close breaks below prior 10-bar low
AND close < cloudFloor
AND volumePulse >= volumePulseMultiple
AND MACD histogram falling
```

#### Pullback-resume continuation

```text
D1 bearish permission is active
execution timeframe pulled back to EMA20, baseLine, or cloudFloor
pullback did not close above cloudTop
current close breaks prior 3-bar low
MACD histogram turns down or Stoch RSI crosses bearish
```

#### Early transition

```text
futureCloudBias flips BEARISH within last 5 bars
close < baseLine
conversionLine < baseLine
volumePulse >= strongVolumePulseMultiple
PSAR bearish
D1 is not bullish
```

## B10. Swing entry policy

```yaml
entryPolicy:
  type: MARKET_NEXT_OPEN
```

For M5/M15/H1 execution:

```text
Decision at closed primary bar → entry at next primary open.
```

Do not simulate entering at the same bar close unless explicitly supported by the evaluation policy.

## B11. Swing stop logic

### B11.1 Long initial stop candidates

```text
cloudStop = min(execution cloudFloor, baseLine) - cloudStopBufferAtr * ATR
atrStop = entry - initialAtrStopMultiple * ATR
swingStop = lastSwingLow(10 to 20 bars) - 0.35 * ATR
dailyStructureStop = D1 baseLine or D1 cloudFloor if close enough
```

Use:

```text
initialStop = max(valid cloudStop, atrStop, swingStop, dailyStructureStop)
```

Reject if:

```text
stopPct < minStopPct
stopPct > maxStopPct
```

### B11.2 Short initial stop candidates

```text
cloudStop = max(execution cloudTop, baseLine) + cloudStopBufferAtr * ATR
atrStop = entry + initialAtrStopMultiple * ATR
swingStop = lastSwingHigh(10 to 20 bars) + 0.35 * ATR
dailyStructureStop = D1 baseLine or D1 cloudTop if close enough
```

Use:

```text
initialStop = min(valid cloudStop, atrStop, swingStop, dailyStructureStop)
```

Reject if stop quality fails.

## B12. Swing target policy

Initial target:

```text
initialTargetR = 3.00
```

Runner target:

```text
runnerTargetR = 5.00
```

Do not force full exit at 3R if the daily trend remains strongly aligned. Use scale-out + runner.

## B13. Swing scale-out rules

Long or short:

```text
if enableScaleOut
AND currentR >= scaleOutAtR
AND scaleOutCount == 0
THEN emit SCALE_OUT side with scaleOutFraction
```

Default:

```yaml
scaleOutAtR: 1.50
scaleOutFraction: 0.50
```

After scale-out, runner management should prefer:

```text
Chandelier stop
D1 base-line break
D1 cloud invalidation
execution timeframe PSAR reversal with momentum confirmation
```

## B14. Swing scale-in rules

Default:

```yaml
enableScaleIn: false
```

Experimental long scale-in:

```text
position side is LONG
currentR >= scaleInAtR
scaleInCount < maxScaleIns
D1 remains bullish
execution close makes new 10-bar high
volumePulse >= volumePulseMultiple
MACD histogram rising
combined risk <= maxRiskFractionAfterScaleIn
```

Experimental short scale-in:

```text
position side is SHORT
currentR >= scaleInAtR
scaleInCount < maxScaleIns
D1 remains bearish
execution close makes new 10-bar low
volumePulse >= volumePulseMultiple
MACD histogram falling
combined risk <= maxRiskFractionAfterScaleIn
```

Reject scale-in if:

```text
currentR < 0
D1 permission no longer aligned
execution cloud is broken/reclaimed
PSAR flips against the position
known event blackout is active
scale-in confidence < scaleInMinConfidence
```

## B15. Swing exit rules

### B15.1 Long exits

```text
STOP:
  low <= activeStop

EXECUTION CLOUD BREAK:
  close < execution cloudFloor for structureExitConfirmBars

DAILY STRUCTURE BREAK:
  D1 close < D1 baseLine
  OR D1 close < D1 cloudFloor

BASELINE BREAK:
  conversionLine < baseLine for structureExitConfirmBars

MOMENTUM DECAY:
  MACD histogram falling for 3 bars
  AND close < EMA20

PSAR REVERSAL:
  PSAR flips bearish
  AND Stoch RSI or MACD confirms

CHANDERLIER TRAIL:
  close < longChandelierStop after scale-out or after currentR >= 1.5

ADVERSE GAP:
  gap against position exceeds maxGapAdversePct
  AND close fails to reclaim EMA20 or baseLine

STALE:
  barsHeld >= staleBars
  AND currentR <= staleMinR

TIME STOP:
  barsHeld >= maxHoldingBars
```

### B15.2 Short exits

```text
STOP:
  high >= activeStop

EXECUTION CLOUD RECLAIM:
  close > execution cloudTop for structureExitConfirmBars

DAILY STRUCTURE BREAK:
  D1 close > D1 baseLine
  OR D1 close > D1 cloudTop

BASELINE BREAK:
  conversionLine > baseLine for structureExitConfirmBars

MOMENTUM DECAY:
  MACD histogram rising for 3 bars
  AND close > EMA20

PSAR REVERSAL:
  PSAR flips bullish
  AND Stoch RSI or MACD confirms

CHANDERLIER TRAIL:
  close > shortChandelierStop after scale-out or after currentR >= 1.5

ADVERSE GAP:
  gap against position exceeds maxGapAdversePct
  AND close fails to reject EMA20 or baseLine

STALE:
  barsHeld >= staleBars
  AND currentR <= staleMinR

TIME STOP:
  barsHeld >= maxHoldingBars
```

## B16. Swing confidence scoring detail

```text
Daily structure alignment, 25:
  +10 D1 close on correct side of cloud
  +6 D1 future cloud aligned
  +5 D1 conversion/base aligned
  +4 D1 EMA20/EMA50 or SMA200 trend aligned

Execution structure alignment, 25:
  +8 execution close on correct side of cloud
  +6 future cloud aligned
  +5 conversion/base aligned
  +3 sufficient cloud thickness
  +3 sufficient future spread

Momentum, 20:
  +8 MACD aligned
  +5 MACD histogram slope aligned
  +4 Stoch RSI aligned
  +3 PSAR aligned

Participation, 10:
  +7 volumePulse >= threshold
  +3 ATR expansion or breakout candle quality

Risk location, 15:
  +7 stopPct inside ideal range
  +4 not overextended from cloud
  +4 targetR >= 3.0

Market cleanliness, 5:
  +3 not in skipped regime
  +2 not blocked by event/holiday/weekend rules
```

Entry requirement:

```text
confidence >= 0.68
```

Scale-in requirement:

```text
confidence >= 0.82
```

## B17. Swing output example

```json
{
  "strategyId": "doflamingo-momentum-v5-beta-swing",
  "eventType": "ENTER_LONG",
  "direction": "LONG",
  "entryPolicy": "MARKET_NEXT_OPEN",
  "confidence": 0.74,
  "risk": {
    "riskFraction": 0.003,
    "initialStop": 2420.0,
    "stopPct": 2.85,
    "targetR": 3.0,
    "scaleOutAtR": 1.5,
    "scaleOutFraction": 0.50
  },
  "evidence": {
    "primaryTimeframe": "H1",
    "contextTimeframe": "D1",
    "dailyCloudBias": "BULLISH",
    "executionCloudState": "BULLISH_ABOVE_CLOUD",
    "futureCloudBias": "BULLISH",
    "conversionBaseState": "BULLISH",
    "emaStackState": "BULLISH_STACK",
    "entryMode": "PULLBACK_RESUME",
    "macdHistogramSlope": "RISING",
    "stochRsiState": "BULLISH",
    "psarDirection": "BULLISH",
    "volumePulse": 1.34,
    "priceDistanceFromCloudAtr": 1.90,
    "overnightRiskAccepted": true
  }
}
```

---

# Part C — Intraday vs Swing comparison

| Dimension | V5 Beta Intraday | V5 Beta Swing |
|---|---|---|
| Strategy ID | `doflamingo-momentum-v5-beta-intraday` | `doflamingo-momentum-v5-beta-swing` |
| Default horizon | Same-day | Multi-day |
| Primary timeframe | M1, M5, M15 | M5, M15, H1 |
| Required context | M1→M5, M5→M15, M15→H1 | M5→M15, M15→H1, H1→D1 |
| Overnight | No | Yes |
| Best instruments | BANKNIFTY, NIFTY, liquid F&O names | Liquid F&O stocks, large caps, selective index futures |
| Risk fraction | 0.5% | 0.3% |
| Stop width | 0.4%–2.5% | 0.8%–6.0% |
| Initial target | 2.5R | 3.0R |
| Scale-out | 1.25R / 40% | 1.5R / 50% |
| Scale-in default | Disabled | Disabled |
| Holding cap | ~24 M15 bars | ~64 H1 bars |
| Key extra gate | Session bucket | Daily cloud bias / event blackout |
| Key exit | EOD flatten | Daily structure break / Chandelier |

---

# Part D — Implementation structure

Recommended module layout:

```text
doflamingo-v5-strategy-packs/
  src/main/java/org/algotradex/strategy/samples/doflamingo/v5/
    DoflamingoMomentumV5IntradayStrategy.java
    DoflamingoMomentumV5IntradayStrategyProvider.java
    DoflamingoMomentumV5SwingStrategy.java
    DoflamingoMomentumV5SwingStrategyProvider.java
    DoflamingoMomentumV5Params.java
    DoflamingoMomentumV5IndicatorMath.java
    DoflamingoMomentumV5IchimokuSnapshot.java
    DoflamingoMomentumV5MomentumSnapshot.java
    DoflamingoMomentumV5RiskModel.java
    DoflamingoMomentumV5ConfidenceModel.java
    DoflamingoMomentumV5SessionGate.java
    DoflamingoMomentumV5Evidence.java
  src/main/resources/META-INF/services/
    org.algotradex.platform.core.strategy.spi.StrategyProvider
```

Provider registration should expose both providers:

```text
org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5IntradayStrategyProvider
org.algotradex.strategy.samples.doflamingo.v5.DoflamingoMomentumV5SwingStrategyProvider
```

## D1. Shared onBarIntent flow

```text
onBarIntent(context):
  bar = context.currentBar()
  position = context.instrumentPosition()
  history = context.history(primaryTimeframe)
  contextHistory = context.history(requiredContextTimeframe)
  collectThoughtEvidence = context.collectReasoningEvidence()

  if warmup not complete:
    return diagnostic(WARMUP)

  snapshots = computeIndicators(history, contextHistory)
  session = classifySession(bar.occurredAt)
  regime = context.marketContext()

  if position exists:
    exits = evaluateExits(position, snapshots, session, regime)
    scaleOut = evaluateScaleOut(position, snapshots)
    scaleIn = evaluateScaleIn(position, snapshots, session, regime)
    return intents in priority order: exits > scaleOut > scaleIn > hold diagnostics

  if cooldown active:
    return diagnostic(COOLDOWN)

  if fresh entry gates fail:
    return diagnostic(blocked reason)

  longCandidate = evaluateLongCandidate(snapshots, session, regime)
  shortCandidate = evaluateShortCandidate(snapshots, session, regime)

  if both candidates pass:
    choose higher confidence if spread >= 0.05
    otherwise emit no trade due to directional conflict

  if chosen confidence >= minConfidence:
    build entry intent with stop, target, sizing, invalidation, evidence

  if collectThoughtEvidence:
    attach ThoughtConditionEvidence and currentPhase from the same evaluation

  return StrategyIntentResult(tradeSignals, tradeIntents, diagnostics, optionalThoughtEvidence, optionalCurrentPhase)
```

## D2. Intent priority

When multiple lifecycle events are true on the same bar:

```text
1. Hard stop / forced risk exit
2. EOD flatten for intraday
3. Structure break exit
4. Momentum decay exit
5. Time/stale exit
6. Scale-out
7. Scale-in
8. Fresh entry
9. No event / hold diagnostic
```

Do not scale in and exit on the same bar. Exit wins.

---

# Part E — Framework dependencies and current fallbacks

| Dependency | Why it matters | Current fallback |
|---|---|---|
| Named indicator SPI / registered Ichimoku, PSAR, Chandelier | Better chart and catalog integration | Compute locally in strategy helper classes and emit indicator usage metadata |
| Stop modification / `MODIFY_EXIT` | Move stop to break-even or tighten trailing after scale-out | Do not mutate stop. Emit explicit exit intents when runner invalidates |
| Pyramiding semantics | Scale-in needs “add to winner” validation | Keep `enableScaleIn=false` by default; when enabled, emit evidence and cap `maxScaleIns=1` |
| Time-stop as first-class exit policy | Max holding and stale exits should be runtime-visible | Strategy emits explicit `EXIT_*_TIME_STOP` or `EXIT_*_STALE` |
| Lot size / tick size / segment | Needed for realistic Indian F&O sizing | Use risk fraction only; round size in execution/evaluation layer when metadata exists |
| NSE/BSE holidays | Avoid swing entries before holidays | Use scenario/BFF-provided metadata or manual calendar parameter; do not read local files |
| Expiry-day helper | Index momentum can distort on expiry | Use parameterized expiry dates/tags; default index fresh-entry skip only when known |
| Cost model | High-turnover intraday performance is overstated without costs | Apply cost model in evaluation/RunSet layer; keep strategy cost-aware through conservative thresholds |
| Circuit-limit hints | Small/mid-cap momentum can become untradeable | Restrict universe to liquid large-cap/F&O names unless circuit metadata exists |
| Options chain / Greeks | Would improve expiry and index derivative logic | Out of scope; V5 trades underlying/index/futures bars only |

---

# Part F — Backtest and validation plan

## F1. Minimum datasets

```text
Intraday:
  BANKNIFTY M15, 1–2 years
  NIFTY M15, 1–2 years
  20–50 liquid F&O equities M15, 1–2 years

Swing:
  50–100 liquid equities M15/H1 with mapped context, 2 years
  NIFTY / BANKNIFTY M15/H1 with mapped context, 2 years, only if overnight enabled
```

## F2. Required baselines

```text
doflamingo-ichimoku-mo-002-beta-v3 or v4, where available
doflamingo-multi-indicator-v6-trend-reversal-v3 or v4, where available
ema-trend-structure-pullback-v2
no-trade baseline
simple EMA trend baseline
```

## F3. RunSet slices

```text
long vs short
instrument
timeframe
session bucket for intraday
expiry vs non-expiry for index intraday
D1-aligned vs D1-not-aligned for swing
market regime
entry mode
stop mode
scale-out enabled vs disabled
scale-in disabled vs enabled
```

## F4. Primary metrics

```text
StrategyPlanDQS
net return after costs when cost model is available
max drawdown
expectancy R
profit factor
win rate
average trade duration
MFE / MAE
profit capture ratio
stale-exit frequency
stop-out frequency
scale-out contribution
runner contribution
long-short asymmetry
```

## F5. Acceptance criteria for beta promotion

Intraday should not be promoted unless:

```text
1. Net performance beats Doflamingo V3/V4 and EMA pullback baseline on at least one index and a liquid equity basket.
2. Drawdown is lower or expectancy is materially higher after cost assumptions.
3. Midday entries are either rare or empirically profitable.
4. Shorts do not degrade the combined strategy; otherwise ship long-only and short-only modes separately.
5. Scale-in improves expectancy after costs; otherwise keep it disabled.
```

Swing should not be promoted unless:

```text
1. D1 alignment materially improves expectancy or drawdown.
2. Overnight gap losses remain within accepted drawdown bounds.
3. Event blackout rules reduce tail losses without deleting most winners.
4. Stop width distribution stays within maxStopPct without over-rejecting valid leaders.
5. Runner contribution is positive; otherwise use simpler fixed target + exit.
```

---

# Part G — Failure modes

## G1. Intraday failure modes

```text
False trend day:
  Cloud and MACD align briefly, then price snaps back into the range.
  Mitigation: require volume pulse, ATR expansion, and quick cloud invalidation exit.

Midday chop:
  Low-volume bars create indicator alignment but no follow-through.
  Mitigation: suppress midday unless volumePulse and ATR expansion are strong.

Expiry distortion:
  Index moves reverse sharply due to options positioning/unwinds.
  Mitigation: skip fresh index entries on known expiry days.

Overextended chase:
  Strategy enters after price is already too far from cloud support.
  Mitigation: anti-chase ATR distance gate with strong-volume exception only.

High-cost overtrading:
  Many small winners disappear after brokerage/STT/slippage.
  Mitigation: minimum confidence, minimum targetR, volume gate, cooldown, cost-aware evaluation.
```

## G2. Swing failure modes

```text
Overnight gap against position:
  Stop cannot prevent open-gap loss.
  Mitigation: smaller riskFraction, maxGapAdversePct, event blackout, avoid illiquid names.

Earnings/news shock:
  Technical structure fails due to new information.
  Mitigation: event blackout when metadata exists; otherwise restrict universe or lower risk.

Sector rotation reversal:
  Mapped context remains bullish while flows rotate away.
  Mitigation: faster primary-timeframe momentum decay and context baseline exits.

Illiquid stock behavior:
  Wide gaps and low volume distort ATR/cloud signals.
  Mitigation: liquid F&O/large-cap universe only.

Too-wide stops:
  Swing cloud stops can be valid but capital-inefficient.
  Mitigation: reject stopPct > maxStopPct and prefer pullback-resume entries.
```

---

# Part H — Recommended implementation order

```text
1. Implement shared indicator math and snapshots.
2. Implement intraday V5 with long and short entries, no scale-in.
3. Add exits: stop, cloud break, PSAR/momentum reversal, stale, max holding, EOD.
4. Add scale-out and runner exits.
5. Run M15 index and equity RunSets against V3/V4 baselines.
6. Implement swing V5 with mapped context, no scale-in.
7. Add swing event blackout hooks as scenario parameters, not local file reads.
8. Add optional scale-in only after baseline strategy is stable.
9. Add Strategy Behavior Analysis evidence samples and indicator usage manifest.
10. Promote from beta only after cost-aware RunSets and regime-sliced validation.
```

---

# Part I — Practical default presets

## I1. Conservative intraday preset

```yaml
strategyId: doflamingo-momentum-v5-beta-intraday
parameters:
  h1CloudBiasMode: REQUIRE_AGREEMENT
  entryMode: STRICT_CLOUD
  volumePulseMultiple: 1.7
  minConfidence: 0.70
  initialAtrStopMultiple: 1.2
  maxStopPct: 2.0
  initialTargetR: 2.2
  enableScaleOut: true
  scaleOutAtR: 1.2
  enableScaleIn: false
  skipOpeningMinutes: 15
  freshEntryCutoff: "15:00"
  forceExitTime: "15:15"
```

## I2. Aggressive intraday preset

```yaml
strategyId: doflamingo-momentum-v5-beta-intraday
parameters:
  h1CloudBiasMode: PREFER_AGREEMENT
  entryMode: HYBRID
  volumePulseMultiple: 1.4
  minConfidence: 0.64
  initialAtrStopMultiple: 1.1
  maxStopPct: 2.5
  initialTargetR: 2.5
  enableScaleOut: true
  scaleOutAtR: 1.25
  enableScaleIn: true
  maxScaleIns: 1
  scaleInAtR: 1.5
```

## I3. Conservative swing preset

```yaml
strategyId: doflamingo-momentum-v5-beta-swing
parameters:
  contextCloudBiasMode: REQUIRE_AGREEMENT
  entryMode: PULLBACK_RESUME
  riskFraction: 0.0025
  initialAtrStopMultiple: 2.0
  maxStopPct: 5.0
  initialTargetR: 3.0
  enableScaleOut: true
  scaleOutAtR: 1.5
  enableScaleIn: false
  avoidFreshEntryOnFridayAfternoon: true
  allowIndexFuturesOvernight: false
```

## I4. Aggressive swing preset

```yaml
strategyId: doflamingo-momentum-v5-beta-swing
parameters:
  contextCloudBiasMode: PREFER_AGREEMENT
  entryMode: HYBRID
  riskFraction: 0.0035
  initialAtrStopMultiple: 1.7
  maxStopPct: 6.0
  initialTargetR: 3.5
  enableScaleOut: true
  scaleOutAtR: 1.5
  enableScaleIn: true
  maxScaleIns: 1
  scaleInAtR: 2.0
```

---

# Part J — Final recommendation

Ship both as beta strategies, but validate them separately:

```text
doflamingo-momentum-v5-beta-intraday:
  Primary use: same-day M1/M5/M15 momentum on indices and liquid F&O names.
  Default: no overnight, mapped context required, no scale-in.

Doflamingo-momentum-v5-beta-swing:
  Primary use: M5/M15/H1 execution with mapped higher-timeframe trend for liquid equities.
  Default: overnight allowed, smaller risk, wider stops, mapped context required, no scale-in.
```

The most important implementation principle is this:

```text
Entries should be selective, exits should be decisive, and scale-ins should be treated as experimental until RunSet evidence proves they improve net expectancy after costs.
```
