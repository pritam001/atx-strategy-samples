# Indian Market Technical Event Analysis

**Document type:** Implementation specification / product-architecture note
**Scope:** Gap-up, gap-down, opening-range, and high-volume movement detection for Indian equities, indices, and F&O-oriented replay workflows
**Target platform:** AlgoTradeX / ATX
**Version:** `indian-technical-events-v1.0-draft`
**Date:** 2026-05-20

---

## 0. Executive Summary

This document defines a deterministic **Indian Market Technical Event Analyzer** for AlgoTradeX. The analyzer detects and scores market events such as **gap up**, **gap down**, **gap-and-go**, **gap fade**, **opening-range breakout**, **opening-range breakdown**, and **high-volume movement**.

The key design decision is to keep this as an **event-analysis layer**, not a trading strategy. It should produce structured, auditable, replay-safe facts such as:

```text
SBIN had a strong gap-up, high-volume, no-fill opening-strength event.
```

A later strategy may consume those facts and decide whether to trade:

```text
Enter long only if event score >= 70, price holds above opening range, trend context is supportive, and RR >= 2.
```

This separation is important because ATX already treats strategies as composable components that detect, validate, score, and emit final trading signals. A technical event analyzer should therefore become reusable input to strategies, strategy behavior analysis, dashboards, and evaluation cohorts.

The implementation should live in Core, write deterministic JSON/Markdown artifacts, and expose projections through BFF/Web. It should not be recomputed in the browser.

---

## 1. Goals

### 1.1 Primary Goals

Build an event analyzer that can identify the following in Indian market data:

1. **Gap-up events** using current session open versus previous trading day adjusted close.
2. **Gap-down events** using the same adjusted-close logic.
3. **Strong and extreme gaps** using both percentage and ATR-normalized thresholds.
4. **Opening-range continuation**, especially gap-and-go behavior.
5. **Gap fade and gap fill** behavior.
6. **High-volume expansion** using same-time-of-day relative volume, not naïve full-day volume average.
7. **Volume shock / volume climax** events.
8. **Indian-market-specific warnings**, including price-band proximity, index-volume caveats, session rules, and corporate-action traps.
9. **Replay-safe event artifacts** that are useful before they become strategy signals.
10. **Cohort-ready labels** for later evaluation: instrument type, session bucket, gap class, volume class, continuation/fade class, and context tags.

### 1.2 Secondary Goals

1. Support Workbench visualization with event markers and detail drawers.
2. Enable strategy families such as Indian gap continuation, opening-range breakout, gap fade, and high-volume momentum.
3. Create a reusable scoring contract that strategies can consume.
4. Keep market-data vendor details outside Core runtime logic.
5. Provide an upgrade path for pre-open indicative price, futures OI, delivery volume, sector breadth, and news/result calendar integration.

---

## 2. Non-Goals

This document does **not** define:

1. Live trading execution.
2. Broker order placement.
3. Options-chain analysis.
4. Tick-level order-flow analysis.
5. News classification as a mandatory dependency.
6. ML-based signal generation.
7. A promise that every detected event should be traded.
8. Investment advice.

The output is a deterministic technical event layer. Trading remains a separate strategy/risk/evaluation decision.

---

## 3. Indian Market Assumptions

### 3.1 Exchange Session Structure

For NSE equity-market style analysis, session handling must be explicit.

Relevant NSE references:

- NSE market timings page lists normal market open at **09:15** and normal market close at **15:30** for equity derivatives.
  Source: [NSE Market Timings](https://www.nseindia.com/static/market-data/market-timings)
- NSE pre-open page says the pre-open session runs from **09:00 to 09:15**, with order collection and matching periods.
  Source: [NSE Pre-open Session](https://www.nseindia.com/static/products-services/equity-market-pre-open)
- NSE price-band page lists price-band categories including 2%, 5%, 10%, no standard price bands for derivative-available scrips, and 20% for remaining scrips.
  Source: [NSE Price Bands](https://www.nseindia.com/static/products-services/equity-market-price-bands)

The analyzer should treat regular-session trading as:

```text
Normal session open:  09:15 Asia/Kolkata
Normal session close: 15:30 Asia/Kolkata
Pre-open window:      09:00–09:15 Asia/Kolkata
Opening range v1:     09:15–09:30 Asia/Kolkata
Opening range v2:     09:15–09:45 Asia/Kolkata
Power hour:           14:30–15:30 Asia/Kolkata
```

### 3.2 Previous Close Logic

Gap detection must compare:

```text
today official open or first tradable regular-session open
vs
previous trading day adjusted close
```

Do **not** compare against:

- last bar in a partial or corrupted dataset;
- stale close before a holiday without checking the trading calendar;
- unadjusted close around split, bonus, demerger, special dividend, or symbol restructuring;
- pre-open indicative price unless the detector explicitly says it is a pre-open detector.

### 3.3 Index Volume Caveat

For spot indices such as NIFTY and BANKNIFTY, candle volume may be meaningless, zero, synthetic, or provider-dependent. For index high-volume movement, the analyzer should prefer:

1. Index futures volume.
2. ETF volume where appropriate.
3. Constituent-volume breadth.
4. Turnover or traded-value proxies.
5. F&O open-interest and volume when available.

For cash equities, candle volume is normally usable.

### 3.4 Price-Band Awareness

For equities, event interpretation changes near price bands.

Example:

```text
A 5% gap up in a highly liquid F&O stock may be a strong directional event.
A 5% gap up in a non-F&O stock with a 5% band may be a band-pressure / liquidity event.
```

The analyzer should tag, not suppress, such conditions:

```text
UPPER_BAND_PRESSURE
LOWER_BAND_PRESSURE
PRICE_BAND_NEAR_LIMIT
PRICE_BAND_HIT
```

---

## 4. ATX Architecture Fit

### 4.1 Placement

Recommended module:

```text
atx-platform-core/
  atx-core-technical-events/
    src/main/java/org/algotradex/platform/core/technicalevents/
```

Alternative early placement, if a new module is temporarily too heavy:

```text
atx-platform-core/atx-core-strategy-analysis/
  technicalevents/
```

The cleaner long-term choice is a standalone `atx-core-technical-events` module because technical event analysis is not the same as strategy behavior analysis. Strategy analysis explains what a strategy did. Technical event analysis describes what the market did.

### 4.2 Ownership Boundary

```text
Core       = computes technical-event truth and writes artifacts
BFF        = reads/projects Core-authored artifacts and may trigger generation
Web        = renders projections and chart markers; does not compute event truth
Marketdata = produces canonical replay-ready bars; does not classify events
Strategy   = may consume events but does not own global event taxonomy
Research   = may tag/explain event drivers but does not own detector math
```

### 4.3 Alignment With Existing ATX Principles

ATX already has several relevant principles:

- Marketdata produces canonical replay-ready OHLCV datasets.
- Core consumes canonical datasets and owns replay/evaluation truth.
- BFF/Web should not recompute Core product truth.
- Strategies should be deterministic and artifact-backed.
- Evaluation should be cohort-based and baseline-aware.

This feature should follow the same pattern.

### 4.4 High-Level Data Flow

```text
AngelOne / Marketdata provider
  -> canonical OHLCV CSV + manifest
  -> replay scenario
  -> Core replay timeline
  -> TechnicalEventAnalyzer
      -> GapDetector
      -> OpeningRangeDetector
      -> VolumeExpansionDetector
      -> GapContinuationFadeClassifier
      -> PriceBandDetector
      -> EventScoringService
      -> EventArtifactWriter
  -> run-<runId>-technical-events.json
  -> run-<runId>-technical-events.md
  -> BFF projection
  -> Workbench chart markers / event panel
  -> optional strategy consumption
```

---

## 5. Data Requirements

### 5.1 Required Inputs

The v1 analyzer needs:

```text
Daily bars:
- previous trading day adjusted close
- daily ATR context
- corporate-action adjusted history if available

Intraday bars:
- timeframe: M5 or M15 preferred
- open, high, low, close, volume
- canonical close timestamp
- symbol, instrument id, exchange, asset class

Session metadata:
- exchange timezone
- session open and close
- trading date
- prior trading date
- holiday/half-day status if available

Instrument metadata:
- instrument type: INDEX, EQUITY, FUTURE, ETF
- segment: CASH / FNO / INDEX / ETF if available
- price-band metadata where available
- F&O availability flag where available
```

### 5.2 Optional But Valuable Inputs

```text
Pre-open data:
- indicative equilibrium price
- pre-open matched quantity
- order imbalance if available

Derivatives context:
- futures volume
- futures open interest
- change in open interest
- expiry-day flag
- days to expiry

Equity context:
- delivery volume
- delivery percentage
- traded value
- block/bulk deal flag

Breadth and sector context:
- sector index direction
- advance/decline breadth
- constituent participation

Event calendar:
- earnings/results date
- board meeting
- dividend/split/bonus/demerger
- regulatory action
- major macro calendar
```

### 5.3 Canonical CSV Compatibility

The analyzer should consume canonical ATX marketdata, not raw provider DTOs.

Expected canonical CSV columns:

```csv
instrument_id,symbol,exchange,asset_class,timeframe,occurred_at_utc,open,high,low,close,volume,source,source_timestamp,source_timezone
```

Important timestamp rule:

```text
occurred_at_utc = canonical bar-close timestamp in UTC
```

This makes the event analyzer replay-safe because the first M15 bar from 09:15 to 09:30 IST appears as a completed event at 09:30 IST / 04:00 UTC.

---

## 6. Event Taxonomy

### 6.1 Directional Gap Events

```text
GAP_UP
GAP_DOWN
STRONG_GAP_UP
STRONG_GAP_DOWN
EXTREME_GAP_UP
EXTREME_GAP_DOWN
```

### 6.2 Gap Behavior Events

```text
GAP_AND_GO_LONG
GAP_AND_GO_SHORT
GAP_FADE_SHORT
GAP_FADE_LONG
GAP_FILL_UP
GAP_FILL_DOWN
PARTIAL_GAP_FILL_UP
PARTIAL_GAP_FILL_DOWN
NO_GAP_FILL
```

Meaning:

- `GAP_AND_GO_LONG`: gap up with follow-through strength and little/no gap fill.
- `GAP_AND_GO_SHORT`: gap down with follow-through weakness and little/no gap fill.
- `GAP_FADE_SHORT`: gap up rejected and sold back toward previous close.
- `GAP_FADE_LONG`: gap down rejected and bought back toward previous close.

### 6.3 Opening Range Events

```text
OPENING_RANGE_DEFINED
OPENING_RANGE_BREAKOUT
OPENING_RANGE_BREAKDOWN
OPENING_RANGE_REJECTION_UPPER
OPENING_RANGE_REJECTION_LOWER
OPENING_RANGE_INSIDE_DAY_START
```

### 6.4 Volume Events

```text
HIGH_VOLUME_EXPANSION
VOLUME_SHOCK
VOLUME_CLIMAX
LOW_VOLUME_GAP_TRAP
HIGH_VOLUME_REVERSAL
HIGH_VOLUME_CONTINUATION
```

### 6.5 Price-Band Events

```text
UPPER_BAND_PRESSURE
LOWER_BAND_PRESSURE
PRICE_BAND_HIT_UPPER
PRICE_BAND_HIT_LOWER
BAND_CONSTRAINED_GAP
```

### 6.6 Warning / Context Labels

```text
INDEX_VOLUME_PROXY_REQUIRED
INSUFFICIENT_VOLUME_BASELINE
INSUFFICIENT_DAILY_HISTORY
POSSIBLE_CORPORATE_ACTION
POSSIBLE_NEWS_OR_EARNINGS_GAP
EXPIRY_DAY_CONTEXT
HOLIDAY_ADJACENT_SESSION
LOW_LIQUIDITY
PROVIDER_VOLUME_MISSING
```

---

## 7. Core Formulas

### 7.1 Gap Percentage

```text
gapPct = ((todayOpen - previousCloseAdjusted) / previousCloseAdjusted) * 100
```

Examples:

```text
previousCloseAdjusted = 1000
todayOpen = 1025
gapPct = +2.5%

previousCloseAdjusted = 1000
todayOpen = 975
gapPct = -2.5%
```

### 7.2 ATR-Normalized Gap

```text
gapAtr = abs(todayOpen - previousCloseAdjusted) / ATR_14_daily
```

Why this matters:

```text
A 1% gap in a low-volatility large-cap may be significant.
A 1% gap in a high-volatility small/mid-cap may be normal noise.
```

### 7.3 Gap Fill Percentage

For gap up:

```text
gapSize = todayOpen - previousCloseAdjusted
lowestSinceOpen = min(low from regular-session open through current bar)
gapFilledAmount = max(0, todayOpen - lowestSinceOpen)
gapFillPct = min(100, (gapFilledAmount / gapSize) * 100)
```

For gap down:

```text
gapSize = previousCloseAdjusted - todayOpen
highestSinceOpen = max(high from regular-session open through current bar)
gapFilledAmount = max(0, highestSinceOpen - todayOpen)
gapFillPct = min(100, (gapFilledAmount / gapSize) * 100)
```

### 7.4 Opening Range

For M15 bars:

```text
OR15 high = high of first 09:15–09:30 bar
OR15 low  = low of first 09:15–09:30 bar
```

For M5 bars:

```text
OR15 high = max(high of first 3 M5 bars)
OR15 low  = min(low of first 3 M5 bars)
```

OR30:

```text
OR30 high = max(high from 09:15 through 09:45)
OR30 low  = min(low from 09:15 through 09:45)
```

### 7.5 Same-Time-of-Day Relative Volume

Do **not** compare 09:15–09:30 volume to average full-day volume.

Use same-time-of-day baseline:

```text
barRelVol = currentBarVolume / medianVolumeForSameTimeBucketOverLastNSessions
```

Cumulative version:

```text
cumRelVolAtTime = currentSessionCumulativeVolume / medianCumulativeVolumeAtSameTimeOverLastNSessions
```

Default lookback:

```text
N = 20 sessions
```

### 7.6 Range, Body, and Close Location

```text
barRange = high - low
body = abs(close - open)
bodyPct = body / max(barRange, tiny)
closeLocation = (close - low) / max(barRange, tiny)
rangePct = ((high - low) / previousCloseAdjusted) * 100
```

Interpretation:

```text
closeLocation >= 0.75  -> bullish close near high
closeLocation <= 0.25  -> bearish close near low
bodyPct >= 0.60        -> decisive candle
bodyPct <= 0.30        -> indecision or rejection candidate
```

### 7.7 Upper and Lower Wick Ratios

```text
upperWick = high - max(open, close)
lowerWick = min(open, close) - low
upperWickPct = upperWick / max(barRange, tiny)
lowerWickPct = lowerWick / max(barRange, tiny)
```

Useful interpretation:

```text
Gap up + large upper wick + close below open = rejection risk
Gap down + large lower wick + close above open = reversal risk
```

---

## 8. Threshold Defaults

These are recommended starting thresholds, not universal truths. They should be policy-versioned and later calibrated by instrument cohort.

### 8.1 Gap Thresholds — Indices

```yaml
indexGapThresholds:
  mildGapPct: 0.35
  strongGapPct: 0.75
  extremeGapPct: 1.25
  meaningfulGapAtr: 0.30
  strongGapAtr: 0.60
  extremeGapAtr: 1.00
```

### 8.2 Gap Thresholds — Liquid Large-Cap Equities

```yaml
equityGapThresholds:
  mildGapPct: 1.00
  strongGapPct: 2.00
  extremeGapPct: 3.50
  meaningfulGapAtr: 0.30
  strongGapAtr: 0.60
  extremeGapAtr: 1.00
```

### 8.3 Volume Thresholds

```yaml
volumeThresholds:
  aboveNormalRelVol: 1.50
  highRelVol: 2.00
  shockRelVol: 3.00
  climaxRelVol: 5.00
```

### 8.4 Opening Range Thresholds

```yaml
openingRangeThresholds:
  continuationMaxGapFillPct: 30
  fadeMinGapFillPct: 50
  bullishCloseLocation: 0.65
  bearishCloseLocation: 0.35
  decisiveBodyPct: 0.60
  rejectionWickPct: 0.45
```

---

## 9. Detector Specifications

## 9.1 `GapDetector`

### Purpose

Detect whether the current session opens materially above or below the previous adjusted close.

### Inputs

```text
previousTradingDate
previousCloseAdjusted
todayOpen
ATR_14_daily
instrumentType
priceBandInfo?
corporateActionFlags?
```

### Output Labels

```text
GAP_UP
GAP_DOWN
STRONG_GAP_UP
STRONG_GAP_DOWN
EXTREME_GAP_UP
EXTREME_GAP_DOWN
```

### Logic

```text
if previousCloseAdjusted missing:
    exclude event; warning = INSUFFICIENT_PREVIOUS_CLOSE

if todayOpen missing:
    exclude event; warning = INSUFFICIENT_OPEN_PRICE

gapPct = ((todayOpen - previousCloseAdjusted) / previousCloseAdjusted) * 100
gapAtr = abs(todayOpen - previousCloseAdjusted) / dailyATR14

thresholdSet = index thresholds if instrument is INDEX else equity thresholds

if gapPct >= mildGapPct and gapAtr >= minGapAtr:
    emit GAP_UP

if gapPct <= -mildGapPct and gapAtr >= minGapAtr:
    emit GAP_DOWN

upgrade severity using strong/extreme pct or ATR thresholds
```

### Notes

Use adjusted previous close wherever possible. If the input is not adjusted, the detector should still emit an event but include:

```text
warning = PREVIOUS_CLOSE_ADJUSTMENT_UNKNOWN
```

---

## 9.2 `OpeningRangeDetector`

### Purpose

Define the opening range and detect breakout, breakdown, or rejection from that range.

### Inputs

```text
intraday bars
session open = 09:15 IST
openingRangeMinutes = 15 or 30
bar timeframe = M5 or M15 preferred
```

### Output Labels

```text
OPENING_RANGE_DEFINED
OPENING_RANGE_BREAKOUT
OPENING_RANGE_BREAKDOWN
OPENING_RANGE_REJECTION_UPPER
OPENING_RANGE_REJECTION_LOWER
```

### Logic

```text
OR = bars from 09:15 through 09:15 + openingRangeMinutes
OR_high = max(high)
OR_low  = min(low)

for each later closed bar:
    if close > OR_high + breakoutBuffer:
        emit OPENING_RANGE_BREAKOUT
    if close < OR_low - breakoutBuffer:
        emit OPENING_RANGE_BREAKDOWN
```

Breakout buffer options:

```text
absoluteTickBuffer = 1–2 ticks
percentBuffer = 0.05% to 0.15%
atrBuffer = 0.05 * intradayATR
```

Default:

```text
breakoutBuffer = max(2 ticks, 0.05 * intradayATR)
```

---

## 9.3 `VolumeExpansionDetector`

### Purpose

Detect whether current volume is meaningful for the specific time of day.

### Inputs

```text
current bar volume
current cumulative session volume
same-time-of-day volume baseline over last 20 valid sessions
instrument type
volume proxy configuration
```

### Output Labels

```text
HIGH_VOLUME_EXPANSION
VOLUME_SHOCK
VOLUME_CLIMAX
LOW_VOLUME_GAP_TRAP
PROVIDER_VOLUME_MISSING
INDEX_VOLUME_PROXY_REQUIRED
```

### Logic

```text
if volume missing or zero and instrument is equity:
    warn PROVIDER_VOLUME_MISSING

if instrument is index and no volume proxy is configured:
    warn INDEX_VOLUME_PROXY_REQUIRED

barRelVol = currentBarVolume / medianSameBucketVolume20
cumRelVolAtTime = currentCumulativeVolume / medianSameTimeCumulativeVolume20

if barRelVol >= 2.0 or cumRelVolAtTime >= 1.8:
    emit HIGH_VOLUME_EXPANSION

if barRelVol >= 3.0:
    emit VOLUME_SHOCK

if barRelVol >= 5.0:
    emit VOLUME_CLIMAX

if gap is strong and barRelVol < 1.0:
    emit LOW_VOLUME_GAP_TRAP
```

### Baseline Construction

Use only valid prior sessions:

```text
validBaselineSession =
  same instrument
  same timeframe
  normal trading day
  no major data gaps
  enough bars to cover same bucket
```

Median is preferred to mean because volume has event-day outliers.

---

## 9.4 `GapContinuationFadeClassifier`

### Purpose

Classify post-gap behavior during the first 15–45 minutes.

### Inputs

```text
gap direction and severity
opening range
first 15m / first 30m bars
gapFillPct
barRelVol
cumRelVolAtTime
closeLocation
bodyPct
wick ratios
context tags
```

### Output Labels

```text
GAP_AND_GO_LONG
GAP_AND_GO_SHORT
GAP_FADE_SHORT
GAP_FADE_LONG
GAP_FILL_UP
GAP_FILL_DOWN
NO_GAP_FILL
PARTIAL_GAP_FILL_UP
PARTIAL_GAP_FILL_DOWN
```

### Gap-and-Go Long

For a gap up:

```text
conditions:
- gapPct >= mild or strong threshold
- gapAtr >= meaningful threshold
- gapFillPct <= 30 during first OR window
- first OR close near high or above todayOpen
- barRelVol >= 1.5 or cumRelVolAtTime >= 1.5
- closeLocation >= 0.65
- no bearish rejection wick dominating the first range
```

Upgrade confidence when:

```text
- price closes above opening range high
- volume shock confirms
- higher timeframe trend is up or not bearish
- sector/index breadth agrees
```

### Gap-and-Go Short

For a gap down:

```text
conditions:
- gapPct <= -mild or -strong threshold
- gapAtr >= meaningful threshold
- gapFillPct <= 30 during first OR window
- first OR close near low or below todayOpen
- barRelVol >= 1.5 or cumRelVolAtTime >= 1.5
- closeLocation <= 0.35
- no bullish rejection wick dominating the first range
```

### Gap-Fade Short

For a gap up:

```text
conditions:
- gap up detected
- price rejects above open or above OR high
- first 15m/30m close below todayOpen or back inside opening range
- gapFillPct >= 50
- closeLocation <= 0.35 or upperWickPct >= 0.45
- high volume with failure to hold is stronger fade evidence
```

### Gap-Fade Long

For a gap down:

```text
conditions:
- gap down detected
- price rejects below open or below OR low
- first 15m/30m close above todayOpen or back inside opening range
- gapFillPct >= 50
- closeLocation >= 0.65 or lowerWickPct >= 0.45
- high volume with failure to extend is stronger reversal evidence
```

---

## 9.5 `PriceBandDetector`

### Purpose

Detect whether the event is constrained or explained by price bands.

### Inputs

```text
priceBandLower
priceBandUpper
todayOpen
currentHigh
currentLow
currentClose
instrument metadata
F&O availability flag
```

### Output Labels

```text
UPPER_BAND_PRESSURE
LOWER_BAND_PRESSURE
PRICE_BAND_HIT_UPPER
PRICE_BAND_HIT_LOWER
BAND_CONSTRAINED_GAP
```

### Logic

```text
if priceBandUpper available:
    distanceToUpperPct = ((priceBandUpper - close) / close) * 100
    if distanceToUpperPct <= 0.25:
        emit UPPER_BAND_PRESSURE
    if high >= priceBandUpper or close >= priceBandUpper:
        emit PRICE_BAND_HIT_UPPER

if priceBandLower available:
    distanceToLowerPct = ((close - priceBandLower) / close) * 100
    if distanceToLowerPct <= 0.25:
        emit LOWER_BAND_PRESSURE
    if low <= priceBandLower or close <= priceBandLower:
        emit PRICE_BAND_HIT_LOWER
```

---

## 10. Event Scoring

### 10.1 Score Components

Recommended score:

```text
eventScore =
  0.30 * gapScore
+ 0.25 * volumeScore
+ 0.20 * openingRangeScore
+ 0.15 * contextScore
+ 0.10 * riskQualityScore
```

### 10.2 Gap Score

Inputs:

```text
abs(gapPct)
gapAtr
instrument type
price-band proximity
corporate-action confidence
```

Example mapping:

```text
mild gap only:                 40–55
strong pct or strong ATR:      60–75
extreme pct and extreme ATR:   80–95
corporate-action uncertainty:  cap at 60 unless confirmed
```

### 10.3 Volume Score

Inputs:

```text
barRelVol
cumRelVolAtTime
volume baseline quality
index volume proxy quality
```

Example mapping:

```text
barRelVol < 1.0:        20–35
barRelVol 1.5–2.0:      45–60
barRelVol 2.0–3.0:      60–75
barRelVol 3.0–5.0:      75–88
barRelVol > 5.0:        88–100
missing baseline:       cap at 50
index with no proxy:    cap at 40
```

### 10.4 Opening Range Score

Inputs:

```text
OR breakout/breakdown
OR hold/failure
close location
bodyPct
wick ratios
gapFillPct
```

Example mapping:

```text
no opening range confirmation: 30–45
range defined, no break:       45–55
close beyond OR with volume:   65–80
strong OR breakout + no fill:  80–95
rejection/failure:             score direction-specific fade instead
```

### 10.5 Context Score

Inputs:

```text
primary market regime
trend strength
volatility bucket
higher timeframe trend
sector alignment
expiry-day / macro-event tags
```

Context is descriptive and should not silently block the event. It should lower confidence or add warnings.

### 10.6 Risk Quality Score

Inputs:

```text
distance from prior close
distance to OR high/low
distance to VWAP if available
distance to price bands
ATR stop feasibility
nearby support/resistance if available
liquidity quality
```

This score is especially useful when strategies consume events.

### 10.7 Confidence Bands

```text
finalScore < 40       -> weak / informational only
40 <= score < 60      -> watchlist event
60 <= score < 75      -> valid technical event
75 <= score < 90      -> high-conviction event
score >= 90           -> exceptional event; likely news/institutional/special situation
```

---

## 11. Technical Event Contract

### 11.1 JSON Shape

```json
{
  "schemaVersion": "technical-events-v1.0",
  "policyVersion": "indian-technical-events-v1.0",
  "runId": "run-2026-05-20-001",
  "generatedAt": "2026-05-20T10:00:00Z",
  "metadata": {
    "instrumentId": "EQ-SBIN",
    "symbol": "SBIN",
    "exchange": "NSE",
    "assetClass": "EQUITY",
    "timeframe": "M15",
    "sessionDate": "2026-05-20",
    "sourceDatasetId": "angelone-sbin-m15-2026-05-20"
  },
  "events": []
}
```

### 11.2 Event Object

```json
{
  "eventId": "evt-2026-05-20-SBIN-GAP_AND_GO_LONG-001",
  "instrumentId": "EQ-SBIN",
  "symbol": "SBIN",
  "exchange": "NSE",
  "sessionDate": "2026-05-20",
  "timeframe": "M15",
  "detectedAt": "2026-05-20T04:00:00Z",
  "eventType": "GAP_AND_GO_LONG",
  "direction": "LONG",
  "severity": "HIGH",
  "confidence": 0.78,
  "scores": {
    "gapScore": 82,
    "volumeScore": 76,
    "openingRangeScore": 80,
    "contextScore": 65,
    "riskQualityScore": 70,
    "finalScore": 77
  },
  "evidence": {
    "previousCloseAdjusted": 825.4,
    "todayOpen": 842.0,
    "gapPct": 2.01,
    "gapAtr": 0.72,
    "openingRangeHigh": 848.5,
    "openingRangeLow": 839.2,
    "first15mClose": 847.8,
    "barRelVol": 2.4,
    "cumRelVolAtTime": 2.1,
    "gapFillPct": 8.0,
    "closeLocation": 0.92,
    "bodyPct": 0.73,
    "upperWickPct": 0.06,
    "lowerWickPct": 0.21
  },
  "labels": [
    "GAP_UP",
    "STRONG_GAP_UP",
    "HIGH_VOLUME_EXPANSION",
    "OPENING_RANGE_STRENGTH",
    "NO_GAP_FILL"
  ],
  "warnings": [
    "earnings-or-news-check-recommended"
  ],
  "sourceBarRefs": [
    {
      "timeframe": "M15",
      "occurredAtUtc": "2026-05-20T04:00:00Z"
    }
  ],
  "policyVersion": "indian-technical-events-v1.0"
}
```

### 11.3 Direction Enum

```text
LONG
SHORT
NEUTRAL
MIXED
```

### 11.4 Severity Enum

```text
LOW
MEDIUM
HIGH
EXTREME
```

### 11.5 Confidence

```text
confidence = finalScore / 100
```

Confidence should be capped downward when evidence is missing:

```text
missing previous adjusted close   -> no event or confidence <= 0.3
missing ATR                       -> confidence cap 0.7
missing volume baseline           -> volume score cap 0.5
index volume with no proxy        -> volume score cap 0.4
corporate-action uncertainty      -> gap score cap 0.6
```

---

## 12. Artifact Contract

### 12.1 Run-Level Artifacts

```text
run-<runId>-technical-events.json
run-<runId>-technical-events.md
```

### 12.2 RunSet-Level Artifacts

```text
runset-<runSetId>-technical-events.json
runset-<runSetId>-technical-events.md
```

### 12.3 Artifact Types

```text
technical-events-json
technical-events-markdown
runset-technical-events-json
runset-technical-events-markdown
```

### 12.4 Required Metadata

Each artifact must include:

```text
schemaVersion
policyVersion
runId or runSetId
strategyId? if generated in strategy run context
instrumentId
symbol
exchange
assetClass
timeframe
sessionDate or replay window
source dataset refs
source artifact hashes where available
marketContextPolicyVersion if context is used
generatedAt
warnings
```

### 12.5 Markdown Artifact Outline

```markdown
# Technical Events — <symbol> <sessionDate>

## Summary

## Detected Events

## Gap Analysis

## Opening Range Analysis

## Volume Analysis

## Price-Band / Warning Tags

## Event Evidence Table

## Policy Version And Inputs

## Data Quality Warnings
```

---

## 13. Java Implementation Shape

### 13.1 Package Layout

```text
org.algotradex.platform.core.technicalevents
  api/
    TechnicalEventAnalyzer.java
    TechnicalEventArtifactService.java
  model/
    TechnicalEvent.java
    TechnicalEventType.java
    TechnicalEventDirection.java
    TechnicalEventSeverity.java
    TechnicalEventEvidence.java
    TechnicalEventScores.java
    TechnicalEventWarning.java
    TechnicalEventReport.java
    TechnicalEventPolicy.java
    SessionWindow.java
    VolumeBaseline.java
  detector/
    GapDetector.java
    OpeningRangeDetector.java
    VolumeExpansionDetector.java
    GapContinuationFadeClassifier.java
    PriceBandDetector.java
    EventContextTagger.java
  scoring/
    TechnicalEventScoringService.java
    GapScoreCalculator.java
    VolumeScoreCalculator.java
    OpeningRangeScoreCalculator.java
    ContextScoreCalculator.java
    RiskQualityScoreCalculator.java
  baseline/
    SameTimeVolumeBaselineBuilder.java
    PreviousTradingSessionResolver.java
  artifact/
    TechnicalEventJsonRenderer.java
    TechnicalEventMarkdownRenderer.java
    TechnicalEventArtifactWriter.java
  testkit/
    TechnicalEventFixtureFactory.java
```

### 13.2 Service Interface

```java
public interface TechnicalEventAnalyzer {
    TechnicalEventReport analyze(TechnicalEventAnalysisRequest request);
}
```

### 13.3 Request Shape

```java
public record TechnicalEventAnalysisRequest(
    String runId,
    InstrumentRef instrument,
    String timeframe,
    LocalDate sessionDate,
    List<CanonicalBar> intradayBars,
    List<CanonicalBar> dailyBars,
    Optional<InstrumentMetadata> instrumentMetadata,
    Optional<MarketContextSeries> marketContext,
    TechnicalEventPolicy policy
) {}
```

### 13.4 Pipeline Pseudocode

```java
TechnicalEventReport analyze(request) {
    var session = sessionResolver.resolve(request.sessionDate(), request.instrument());
    var previous = previousSessionResolver.resolve(request.dailyBars(), request.sessionDate());
    var volumeBaseline = volumeBaselineBuilder.build(request.intradayBars(), request.policy());

    var gap = gapDetector.detect(request, previous, session);
    var openingRange = openingRangeDetector.detect(request, session);
    var volumeEvents = volumeExpansionDetector.detect(request, volumeBaseline, session);
    var bandEvents = priceBandDetector.detect(request, session);

    var behaviorEvents = gapContinuationFadeClassifier.classify(
        gap,
        openingRange,
        volumeEvents,
        request.intradayBars(),
        request.policy()
    );

    var allEvents = mergeAndDedupe(gap, openingRange, volumeEvents, bandEvents, behaviorEvents);
    var scored = scoringService.score(allEvents, request);

    return reportBuilder.build(request, scored);
}
```

### 13.5 Dedupe Rules

Avoid noisy repeated events.

```text
Rule 1: Emit one core GAP_UP/GAP_DOWN event per session.
Rule 2: Emit one OR definition event per session.
Rule 3: Emit continuation/fade classification once the first OR window is complete.
Rule 4: Emit a new event only when semantic state changes materially.
Rule 5: For repeated volume shock bars, either aggregate into one VOLUME_SHOCK_CLUSTER or emit only top-N evidence bars.
```

Example:

```text
Do not emit HIGH_VOLUME_EXPANSION on every bar from 09:15 to 10:30.
Emit one HIGH_VOLUME_EXPANSION event with evidence: firstDetectedAt, peakRelVolAt, peakRelVol.
```

---

## 14. Configuration Policy

### 14.1 YAML Policy

```yaml
technicalEventPolicy:
  id: indian-technical-events-v1.0

  session:
    exchange: NSE
    timezone: Asia/Kolkata
    preOpenStart: "09:00"
    normalOpen: "09:15"
    normalClose: "15:30"
    openingRangeMinutes: 15
    secondaryOpeningRangeMinutes: 30

  gap:
    useAdjustedPreviousClose: true
    indexMildPct: 0.35
    indexStrongPct: 0.75
    indexExtremePct: 1.25
    equityMildPct: 1.00
    equityStrongPct: 2.00
    equityExtremePct: 3.50
    minGapAtr: 0.30
    strongGapAtr: 0.60
    extremeGapAtr: 1.00

  volume:
    lookbackSessions: 20
    sameTimeBucket: true
    baselineStatistic: MEDIAN
    aboveNormalRelVol: 1.50
    highRelVol: 2.00
    shockRelVol: 3.00
    climaxRelVol: 5.00
    indexVolumeProxyMode: FUTURES_OR_CONSTITUENT_BREADTH

  openingRange:
    breakoutRequiresCloseBeyondRange: true
    breakoutBufferMode: MAX_TICKS_OR_ATR_FRACTION
    breakoutBufferTicks: 2
    breakoutAtrFraction: 0.05
    minCloseLocationForLong: 0.65
    maxCloseLocationForShort: 0.35
    decisiveBodyPct: 0.60
    rejectionWickPct: 0.45
    maxGapFillForContinuationPct: 30
    minGapFillForFadePct: 50

  scoring:
    gapWeight: 0.30
    volumeWeight: 0.25
    openingRangeWeight: 0.20
    contextWeight: 0.15
    riskQualityWeight: 0.10
    validEventScore: 60
    highConvictionScore: 75
    exceptionalScore: 90

  filters:
    requireAdjustedPreviousClose: true
    excludeInsufficientHistory: true
    markPriceBandEvents: true
    markPossibleNewsEvents: true
    markExpiryDayContext: true
    dedupeSemanticEvents: true
```

### 14.2 Policy Versioning

Any threshold change should update:

```text
policyVersion
policy hash
artifact metadata
snapshot tests
```

This prevents confusion when old and new event results differ.

---

## 15. BFF API Surface

### 15.1 Run-Level Read

```http
GET /api/runs/{runId}/technical-events
```

Response:

```json
{
  "runId": "run-001",
  "artifactType": "technical-events-json",
  "schemaVersion": "technical-events-v1.0",
  "policyVersion": "indian-technical-events-v1.0",
  "summary": {
    "eventCount": 5,
    "highestScore": 82,
    "primaryEventType": "GAP_AND_GO_LONG"
  },
  "events": []
}
```

### 15.2 Run-Level Generate

```http
POST /api/runs/{runId}/technical-events
```

Rules:

- BFF may trigger Core workflow generation.
- BFF must not compute event labels itself.
- If required marketdata artifacts are missing, return actionable error.

### 15.3 Artifact Preview

Existing artifact endpoints can support:

```text
technical-events-json
technical-events-markdown
```

### 15.4 Dataset Preview Variant

Optional future endpoint:

```http
POST /api/marketdata/datasets/{datasetId}/technical-events/preview
```

This would allow event analysis on a dataset before a full strategy run. Core should still own the analysis logic.

---

## 16. Web / Workbench Design

### 16.1 Technical Events Panel

Add a Run Viewer panel:

```text
Technical Events
  Summary tiles:
    - primary event
    - gap size
    - volume multiple
    - opening range result
    - event confidence
  Filters:
    - gap up/down
    - high volume
    - continuation
    - fade
    - price band
    - warnings
  Event table:
    - time
    - event type
    - direction
    - score
    - severity
    - labels
    - warnings
  Detail drawer:
    - formulas
    - evidence values
    - source bars
    - score breakdown
    - data-quality warnings
```

### 16.2 Chart Markers

Marker examples:

```text
09:30 GAP_UP + HIGH_VOLUME
09:45 OR_BREAKOUT
10:00 GAP_AND_GO_LONG
```

Recommended marker properties:

```json
{
  "time": "2026-05-20T04:00:00Z",
  "label": "Gap-and-go",
  "direction": "LONG",
  "score": 77,
  "severity": "HIGH",
  "colorToken": "semantic-positive"
}
```

### 16.3 UI Boundary Rule

The browser may calculate temporary visual helpers for chart display only, but it must not label official events. All official event labels, scores, and warnings come from Core artifacts through BFF projection.

---

## 17. Strategy Consumption

### 17.1 Event Analyzer Is Not a Strategy

The event analyzer emits facts. A strategy decides whether to trade.

```text
Event Analyzer:
  "Strong gap up; high volume; no fill; OR breakout."

Strategy:
  "Enter long because event score >= 75, trend is supportive, stop is feasible, and RR >= 2."
```

### 17.2 Example Strategy: `indian-gap-volume-continuation-v1`

#### Thesis

Trade continuation after strong opening gaps when volume participation confirms and price does not meaningfully fill the gap.

#### Inputs

```text
TechnicalEventReport
MarketContextSnapshot
ATR(14)
Opening range high/low
VWAP if available
Support/resistance if available
```

#### Long Setup

```text
- eventType in [GAP_AND_GO_LONG, OPENING_RANGE_BREAKOUT]
- finalScore >= 70
- gapFillPct <= 30
- barRelVol >= 1.8 or cumRelVolAtTime >= 1.8
- close above OR high
- higher timeframe trend not down
- price not within 0.25% of upper price band unless band strategy explicitly enabled
- stop below OR low or ATR buffer
- target at 2R or prior resistance
```

#### Short Setup

```text
- eventType in [GAP_AND_GO_SHORT, OPENING_RANGE_BREAKDOWN]
- finalScore >= 70
- gapFillPct <= 30
- barRelVol >= 1.8 or cumRelVolAtTime >= 1.8
- close below OR low
- higher timeframe trend not up
- price not within 0.25% of lower price band unless band strategy explicitly enabled
- stop above OR high or ATR buffer
- target at 2R or prior support
```

### 17.3 Example Strategy: `indian-gap-fade-v1`

#### Thesis

Fade failed gaps when the opening move is rejected, gap fill begins, and volume confirms failure rather than continuation.

#### Short After Gap Up

```text
- eventType = GAP_FADE_SHORT
- gapFillPct >= 50
- close below todayOpen or below OR low
- upperWickPct >= 0.45 or closeLocation <= 0.35
- high volume but no continuation
- avoid if strong higher timeframe trend is up
```

#### Long After Gap Down

```text
- eventType = GAP_FADE_LONG
- gapFillPct >= 50
- close above todayOpen or above OR high
- lowerWickPct >= 0.45 or closeLocation >= 0.65
- high volume but no continuation lower
- avoid if strong higher timeframe trend is down
```

---

## 18. Evaluation Design

### 18.1 Cohorts

Evaluate event quality by cohort:

```text
NIFTY index
BANKNIFTY index
NIFTY futures
BANKNIFTY futures
NIFTY 50 equities
BANKNIFTY constituent banks
F&O stocks
non-F&O liquid cash stocks
earnings days
non-earnings days
expiry days
non-expiry days
high VIX days
low VIX days
```

### 18.2 Event-Level Metrics

For each event type:

```text
count
average gapPct
average gapAtr
average barRelVol
continuation rate after 1R/2R proxy
full gap-fill rate
partial gap-fill rate
MFE after event
MAE after event
close-of-day direction match
next-N-bar return distribution
```

### 18.3 Strategy-Level Metrics

When a strategy consumes events:

```text
hit rate
expectancy R
profit factor
max drawdown
trade count
average R
median R
MFE/MAE
score vs outcome correlation
volumeScore vs outcome correlation
gapScore vs outcome correlation
```

### 18.4 Baselines

Compare against:

```text
strategy without technical events
opening-range breakout without gap filter
gap-only continuation without volume filter
gap-only fade without volume filter
no-trade baseline
random direction sanity baseline
```

### 18.5 Insight Examples

```text
High-volume gap-and-go events in NIFTY 50 stocks outperform low-volume gaps by +X expectancy R.
Gap fades work better in range-bound context than in strong-trend context.
BANKNIFTY gap downs with first-15m low closeLocation show higher continuation probability than gap ups.
Non-F&O stocks near price bands show high event scores but poor tradability.
```

---

## 19. Testing Plan

### 19.1 Unit Tests

```text
GapDetectorTest
- detects gap up/down
- classifies mild/strong/extreme
- handles missing previous close
- applies ATR normalization
- warns on corporate-action uncertainty

OpeningRangeDetectorTest
- computes OR15 from M15
- computes OR15 from M5
- detects breakout/breakdown only on closed bars
- does not use future bars

VolumeExpansionDetectorTest
- computes same-time-of-day baseline
- uses median not mean
- warns on insufficient baseline
- handles index volume proxy requirement

GapContinuationFadeClassifierTest
- gap up continuation
- gap up fade
- gap down continuation
- gap down fade
- partial fill vs full fill

PriceBandDetectorTest
- upper band pressure
- lower band pressure
- band hit
- no band metadata
```

### 19.2 Golden Tests

Create compact fixtures:

```text
fixture-gap-up-go-long-m15.csv
fixture-gap-up-fade-short-m15.csv
fixture-gap-down-go-short-m15.csv
fixture-gap-down-fade-long-m15.csv
fixture-low-volume-gap-trap-m15.csv
fixture-price-band-hit.csv
```

Expected artifacts:

```text
technical-events.snapshot.json
technical-events.snapshot.md
```

### 19.3 Replay-Safety Tests

1. First 09:15–09:30 M15 event is only visible at 09:30 IST.
2. OR15 classification cannot use 09:30–09:45 bar until that bar is closed.
3. Gap continuation/fade at 09:30 uses only first OR window.
4. Full-session classification, if any, must be labeled as end-of-day or post-session.

### 19.4 Data-Quality Tests

```text
missing previous adjusted close -> explicit exclusion
missing daily ATR -> warning and score cap
duplicate canonical bars -> fail or deterministic handling upstream
missing volume baseline -> warning and score cap
spot index volume missing -> proxy warning
holiday-adjacent prior close -> uses previous trading session, not calendar day
```

---

## 20. Acceptance Criteria

The feature is ready for v1 when:

1. Same input bars produce the same technical events.
2. Gap events use previous trading day adjusted close.
3. No future bars are used for opening-range or continuation/fade decisions.
4. Volume shock uses same-time-of-day baseline.
5. Spot-index volume is not treated as reliable unless a configured proxy exists.
6. Price-band proximity is tagged when metadata exists.
7. Every event includes evidence, score components, warnings, and policy version.
8. Missing data creates explicit exclusions or warnings, not silent guesses.
9. Events can be rendered in Workbench without Web recomputing them.
10. Strategy signals can consume events, but event artifacts are useful without any strategy.
11. Golden snapshots cover at least one example of each core event class.
12. BFF exposes read/generate endpoints without duplicating Core logic.
13. Web renders event tables, chart markers, evidence, and warnings from BFF projections only.
14. Event output can be grouped by cohort for evaluation.
15. Artifacts remain stable under repeated runs with the same data and policy.

---

## 21. MVP Build Plan

### Slice 1 — Core Offline Event Detector

Build:

```text
GapDetector
OpeningRangeDetector
VolumeExpansionDetector
TechnicalEventScoringService
TechnicalEventReport
TechnicalEventJsonRenderer
TechnicalEventMarkdownRenderer
```

Artifacts:

```text
run-<runId>-technical-events.json
run-<runId>-technical-events.md
```

No strategy consumption yet.

### Slice 2 — BFF Projection

Build:

```http
GET  /api/runs/{runId}/technical-events
POST /api/runs/{runId}/technical-events
```

Add artifact type classification:

```text
technical-events-json
technical-events-markdown
```

### Slice 3 — Workbench UI

Build:

```text
Technical Events panel
chart markers
event filter chips
event detail drawer
evidence table
warning badges
artifact download links
```

### Slice 4 — Strategy Consumption

Build:

```text
indian-gap-volume-continuation-v1
indian-gap-fade-v1
india-orb-breakout-event-aware-v1
```

### Slice 5 — RunSet / Cohort Evaluation

Build:

```text
event cohorts
event outcome summaries
score-vs-outcome correlation
volume-filter ablation
gap-only vs gap+volume baseline comparison
```

### Slice 6 — Advanced Indian Market Context

Add:

```text
pre-open indicative price
futures volume/OI proxy for index events
delivery volume for equities
price-band master integration
expiry-day tags
news/result calendar warning integration
sector breadth tags
```

---

## 22. Known Traps And How To Handle Them

### 22.1 Opening Volume Trap

The first 15 minutes naturally has high volume. Comparing the first 15m bar to average all-day volume is wrong.

Correct approach:

```text
Compare 09:15–09:30 volume to historical 09:15–09:30 volume.
```

### 22.2 Index Volume Trap

Spot-index volume may be zero, missing, or provider-dependent.

Correct approach:

```text
Use index futures volume, ETF volume, or constituent breadth.
```

### 22.3 Corporate Action Trap

A split/bonus/demerger can look like a massive gap.

Correct approach:

```text
Use adjusted close and corporate-action flags.
```

### 22.4 Price-Band Trap

A stock locked near upper/lower band may not be tradable even if technically strong.

Correct approach:

```text
Tag band pressure and let strategy/risk decide.
```

### 22.5 News Gap Trap

Results, regulatory orders, block deals, management commentary, and macro shocks create gaps that technical logic alone cannot explain.

Correct approach:

```text
Mark possible news/event context when available.
Do not suppress the technical event.
```

### 22.6 Gap-Fill Overcounting

Price may move toward the previous close over many bars.

Correct approach:

```text
Emit semantic state changes, not repeated duplicate events every bar.
```

### 22.7 Provider Timestamp Trap

Some providers return candle open time, while ATX replay uses closed-bar event time.

Correct approach:

```text
Normalize timestamps to bar-close UTC before analysis.
```

---

## 23. Example Reports

### 23.1 Example: Gap-and-Go Long

```markdown
## Primary Event

**GAP_AND_GO_LONG** — High confidence

- Previous adjusted close: 825.40
- Today open: 842.00
- Gap: +2.01%
- Gap ATR: 0.72
- First 15m close: 847.80
- OR15 high/low: 848.50 / 839.20
- Gap fill: 8.0%
- Bar relative volume: 2.4x
- Close location: 0.92
- Score: 77 / 100

Interpretation:
The instrument opened with a strong gap up, did not meaningfully fill the gap in the first 15 minutes, and closed near the high on high same-time-of-day relative volume. This is a valid technical continuation event. Strategy confirmation is still required before trade entry.
```

### 23.2 Example: Gap Fade Short

```markdown
## Primary Event

**GAP_FADE_SHORT** — Medium-high confidence

- Previous adjusted close: 1,000.00
- Today open: 1,025.00
- Gap: +2.50%
- Gap ATR: 0.85
- First 15m high: 1,030.00
- First 15m close: 1,006.00
- Gap fill: 76.0%
- Upper wick: 0.58
- Close location: 0.18
- Bar relative volume: 3.1x
- Score: 74 / 100

Interpretation:
The stock opened with a strong gap up but rejected quickly. High volume with a weak close suggests supply absorbed the gap. The event is a gap-fade candidate, not a continuation candidate.
```

---

## 24. Repository Change List

### 24.1 Core

```text
atx-platform-core/
  atx-core-technical-events/
    pom.xml
    src/main/java/.../api
    src/main/java/.../model
    src/main/java/.../detector
    src/main/java/.../scoring
    src/main/java/.../artifact
    src/test/java/.../detector
    src/test/resources/technical-events-fixtures
```

### 24.2 Workflow

```text
atx-core-workflow
  TechnicalEventRunArtifactWriter
  RunArtifact classification support
  CLI integration if needed
```

### 24.3 BFF

```text
atx-platform-bff-api
  TechnicalEventResponse
  TechnicalEventDetailResponse
  TechnicalEventScoreResponse
  TechnicalEventEvidenceResponse

atx-platform-bff-service
  TechnicalEventQueryService
  TechnicalEventGenerationService

atx-platform-bff-boot
  TechnicalEventController
```

### 24.4 Web

```text
atx-platform-web
  TechnicalEventsPanel.tsx
  TechnicalEventTable.tsx
  TechnicalEventDetailDrawer.tsx
  TechnicalEventMarkers.tsx
  technicalEventsApi.ts
```

### 24.5 Contracts

If the event type becomes a canonical cross-module contract, consider adding it to platform contracts later:

```text
org.algotradex.platform.contracts.intelligence.ContextSignal
```

or a new package:

```text
org.algotradex.platform.contracts.market.events
```

For v1, keeping it Core-owned as an artifact model is enough.

---

## 25. Suggested First Implementation Prompt For Codex / Claude

```text
Implement the AlgoTradeX Indian Technical Event Analyzer v1.

Scope:
- Create atx-core-technical-events module.
- Implement GapDetector, OpeningRangeDetector, VolumeExpansionDetector, GapContinuationFadeClassifier, PriceBandDetector, and TechnicalEventScoringService.
- Use closed-bar canonical OHLCV only.
- Use previous trading day adjusted close for gap logic.
- Use same-time-of-day relative volume baseline.
- Produce run-<runId>-technical-events.json and run-<runId>-technical-events.md.
- Include policyVersion, schemaVersion, evidence, score breakdown, labels, warnings, and source bar refs.
- Add golden snapshot tests for gap up continuation, gap up fade, gap down continuation, gap down fade, and low-volume gap trap.

Boundaries:
- Do not compute in BFF or Web.
- Do not depend on AngelOne SDK or provider DTOs.
- Do not place trading strategy decisions inside the event analyzer.
- Do not use future bars for first-15m or first-30m classification.
```

---

## 26. Source Anchors

This document was designed to fit the current ATX architecture and the Indian-market constraints below.

### ATX Source Anchors

- `atx-marketdata/README.md` — vendor-backed market-data ingestion, canonical CSV, manifest, raw JSONL, scenario generation, supported timeframes.
- `atx-marketdata/docs/adr_atx_marketdata_adapter_architecture_angelone_historical_ingestion.md` — canonical bar-close UTC timestamp semantics, deterministic fetch/sort/dedupe, canonical CSV schema, artifact layout, tests.
- `atx-design-docs/product/PRDs/Strategy_Composition_Module_System_PRD.md` — strategy as modules plus scoring logic that emits signals.
- `atx-design-docs/engineering/ADRs/ADR_ATX_Strategy_Behavior_Analysis_Architecture.md` — Core computes truth, BFF projects, Web renders; artifact-backed analysis architecture.
- `atx-design-docs/product/PRDs/AlgoTradeX_Decision_Intelligence_Evaluation_Framework_PRD.md` — cohort, baseline, deterministic evaluation, and insight requirements.
- `atx-strategy-samples/docs/AlgoTradeX Indian Market Strategies – Detailed Specs For 20 Families.md` — Indian strategy families including opening-range, breakout, volume expansion, VWAP, mean reversion, and session filters.

### External Indian Market References

- [NSE Market Timings](https://www.nseindia.com/static/market-data/market-timings)
- [NSE Pre-open Session](https://www.nseindia.com/static/products-services/equity-market-pre-open)
- [NSE Price Bands](https://www.nseindia.com/static/products-services/equity-market-price-bands)

---

## 27. One-Line Summary

Build **Indian Technical Events** as a deterministic, Core-owned, artifact-backed market-fact layer that detects gap-up, gap-down, opening-range, and high-volume movements using Indian-market session logic, same-time volume baselines, price-band awareness, and replay-safe evidence — then let strategies consume those events separately.
