# Range S/R v2 Strategy

## Purpose

`range-sr-v2` is a higher-timeframe structure and lower-timeframe reversal strategy. It trades with the H4 trend, only
from real H4 support/resistance pullback zones, and emits a signal only when an M15 reversal pattern confirms at the
defended level. It does not fabricate targets: the take-profit must be a real H4 pivot at least the configured minimum
R multiple away.

## Runtime Contract

- Home: `atx-strategy-samples/range-sr-v2-strategy`
- Strategy id: `range-sr-v2`
- Strategy version: `1.0.0`
- Provider id: `atx-strategy-samples`
- Primary timeframe: `M15`
- Required context timeframe: `H4`
- SPI: `TradeIntentStrategy`
- Emissions: one `TradeSignal` plus one entry `StrategyTradeIntent`
- Cooldown: one signal per instrument for the configured cooldown window, default `4h`

The strategy reads only closed replay data from `StrategyExecutionContext`. M15 bars come from the primary instrument
history and H4 bars come from `context.history("H4")`.

## Default Parameters

| Key | Default | Meaning |
| --- | ---: | --- |
| `minTrendAdx` | `20` | Minimum H4 ADX(14) required before a setup can trade. |
| `minPatternConfidence` | `1.0` | Strict Tier-1 pattern gate by default. |
| `minConfluence` | `2` | Minimum score for the defended structural level. |
| `atrMultSL` | `1.5` | Stop buffer beyond structure using ATR(14) on M15. |
| `atrMultMinRR` | `2.0` | Minimum real-structure target distance in R. |
| `riskUsdPerTrade` | `1.0` | Requested unit size uses this dollar risk divided by stop distance. |
| `use15mStructure` | `false` | Default structure source is H4 pivots. |
| `htfLookback` | `200` | Maximum H4 bars used for trend and structure. |
| `ltfLookback` | `200` | Maximum M15 bars used for pattern and ATR. |
| `pivotLookback` | `3` | Confirmed fractal pivot wing size. |
| `cooldownHours` | `4` | Per-instrument setup cooldown. |
| `levelTolerancePct` | `0.002` | Price/level proximity and round-number tolerance. |
| `midlineTolerancePct` | `0.02` | Premium/discount neutral band around the active range midline. |

## Decision Flow

1. Require enough H4 and M15 history.
2. Compute H4 ADX(14); skip if it is below `minTrendAdx`.
3. Compute H4 EMA(50). Close above EMA means long bias, close below EMA means short bias.
4. Build confirmed fractal pivots from H4, find nearest pivot high above price and nearest pivot low below price.
5. Longs require discount position below the active range midline; shorts require premium position above it.
6. Score confluence on the defended level using pivot evidence, round-number proximity, fib 0.5/0.618 proximity, and
   recent weekly extreme proximity.
7. Detect M15 reversal patterns on the latest closed bar. Tier-1 patterns are engulfing, morning/evening star, three
   soldiers, and three crows.
8. Apply stop-hunt filter: a wick through the level must close back on the safe side.
9. Place the stop beyond the defended level by `atrMultSL * ATR(14)`.
10. Find the first H4 pivot in the trade direction at least `atrMultMinRR * risk` away. Skip if absent.
11. Emit signal and entry intent with entry, stop, target, requested units, RR, confluence, pattern, and condition
    evidence.

## Validation Expectations

The module must pass provider/schema tests, ServiceLoader discovery, focused strategy unit tests for every gate, and a
sample M15 + H4 context replay fixture.
