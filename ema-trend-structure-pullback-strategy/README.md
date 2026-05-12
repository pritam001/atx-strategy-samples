# EMA Trend Structure Pullback Lifecycle

Strategy ID: `ema-trend-structure-pullback-v2`

Version: `2.0.0`

Provider: `atx-strategy-samples`

This sample emits lifecycle trade intents for EMA trend-structure pullback setups. It keeps the original EMA pullback and bullish transition entry model, then adds runtime-readable entry, exit, scale-out, optional scale-in, stop, stale-trade, and max-holding discipline.

Capabilities:

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

Key lifecycle defaults:

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

The production defaults remain EMA20/50/200. The descriptor intentionally permits compact EMA periods such as 3/5/8 so the bundled replay scenario can validate lifecycle behavior with a small, self-contained fixture.

Run the compact v2 scenario after packaging the sample jar:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config ema-trend-structure-pullback-strategy/src/test/resources/ema-trend-structure-pullback-v2-scenario/scenario.yaml \
  --out /tmp/atx-ema-trend-structure-pullback-v2-run
```
