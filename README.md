# AlgoTradeX Strategy Samples

This project contains trusted external strategy plugins used to verify the custom strategy extension path.

Build the samples:

```bash
mvn -f atx-strategy-samples/pom.xml package
```

Install a sample for local Core CLI or BFF discovery:

```bash
mkdir -p ~/.atx/plugins/strategies
cp atx-strategy-samples/ema-crossover-strategy/target/ema-crossover-strategy-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
cp atx-strategy-samples/range-support-resistance-strategy/target/range-support-resistance-strategy-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
cp atx-strategy-samples/sma-20-pullback-continuation-strategy/target/sma-20-pullback-continuation-strategy-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
cp atx-strategy-samples/doflamingo-strategy-pack/target/doflamingo-strategy-pack-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
cp atx-strategy-samples/doflamingo-v2-strategy-packs/target/doflamingo-v2-strategy-packs-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
cp atx-strategy-samples/ema-trend-structure-pullback-strategy/target/ema-trend-structure-pullback-strategy-0.1.0-SNAPSHOT.jar ~/.atx/plugins/strategies/
```

The platform discovers plugins from `ATX_STRATEGY_PLUGIN_DIR` when set, otherwise from `~/.atx/plugins/strategies`.

Available samples:

- `ema-crossover-v1`: emits closed-bar EMA crossover signals.
- `range-support-resistance-v1`: emits closed-bar support/resistance confirmation signals with suggested entry, stop,
  target, and market order parameters.
- `sma-20-pullback-continuation-v1`: emits closed-bar SMA20 pullback continuation setup events using SMA200 as
  support/resistance context.
- `doflamingo-ichimoku-mo-002-beta-v1`: ports the Doflamingo 002 beta Ichimoku momentum setup as a long-only continuation
  signal.
- `doflamingo-multi-indicator-v6-trend-reversal-v1`: ports the Doflamingo V6 trend-reversal setup as a long-only reversal
  signal.
- `doflamingo-ichimoku-mo-002-beta-v2`: ATX-adaptive Ichimoku lifecycle variant with structured trade-intent
  condition evidence, risk-fraction entries, full-close exits, runtime stop policy, explicit holding horizon, and optional
  market-context regime skip filters.
- `doflamingo-multi-indicator-v6-trend-reversal-v2`: ATX-adaptive Multi V6 lifecycle variant with structured
  condition evidence, explicit adaptive momentum mode, trend-filtered entries, runtime stop policy, stale exits, and
  one-shot scale-out intents. The v2 Doflamingo strategies expose `skipMarketRegimes` as a multi-select parameter for
  primary market-context regimes such as `RANGING_LOW_VOLATILITY` or `STRONG_TREND_MEDIUM_VOLATILITY`; selected regimes
  suppress new entry signals only, while position lifecycle exits and scale-outs remain active.
- `ema-trend-structure-pullback-v2`: EMA trend-structure pullback lifecycle variant with signal-plus-intent entries,
  EMA50/ATR-derived percent stops, full-close exits, stale and max-holding discipline, one-shot scale-outs, and optional
  scale-ins. Defaults remain EMA20/50/200; compact EMA periods are allowed so the bundled replay fixture can validate
  lifecycle behavior with a small dataset.

Run the EMA sample scenario through the Core CLI:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config ema-crossover-strategy/src/test/resources/ema-crossover-scenario/scenario.yaml \
  --out /tmp/atx-ema-crossover-run
```

Run the range support/resistance sample scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config range-support-resistance-strategy/src/test/resources/range-support-resistance-scenario/scenario.yaml \
  --out /tmp/atx-range-support-resistance-run
```

Run the SMA 20 pullback continuation sample scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config sma-20-pullback-continuation-strategy/src/test/resources/sma-20-pullback-continuation-scenario/scenario.yaml \
  --out /tmp/atx-sma-20-pullback-continuation-run
```

Run the Doflamingo Ichimoku beta sample scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config doflamingo-strategy-pack/src/test/resources/doflamingo-ichimoku-beta-scenario/scenario.yaml \
  --out /tmp/atx-doflamingo-ichimoku-beta-run
```

Run the Doflamingo V6 trend-reversal sample scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config doflamingo-strategy-pack/src/test/resources/doflamingo-v6-trend-reversal-scenario/scenario.yaml \
  --out /tmp/atx-doflamingo-v6-trend-reversal-run
```

Run the Doflamingo Ichimoku beta v2 adaptive lifecycle scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config doflamingo-v2-strategy-packs/src/test/resources/doflamingo-ichimoku-beta-v2-scenario/scenario.yaml \
  --out /tmp/atx-doflamingo-ichimoku-beta-v2-run
```

Run the Doflamingo V6 trend-reversal v2 adaptive lifecycle scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config doflamingo-v2-strategy-packs/src/test/resources/doflamingo-v6-trend-reversal-v2-scenario/scenario.yaml \
  --out /tmp/atx-doflamingo-v6-trend-reversal-v2-run
```

Run the EMA trend-structure pullback v2 lifecycle scenario:

```bash
ATX_STRATEGY_PLUGIN_DIR=~/.atx/plugins/strategies \
  java -jar ../atx-platform-core/atx-core-cli/target/atx-core-cli-0.1.0-SNAPSHOT.jar \
  replay run --config ema-trend-structure-pullback-strategy/src/test/resources/ema-trend-structure-pullback-v2-scenario/scenario.yaml \
  --out /tmp/atx-ema-trend-structure-pullback-v2-run
```
