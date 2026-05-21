package org.algotradex.strategy.samples.doflamingo.v5;

import org.algotradex.platform.contracts.market.BarEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

final class DoflamingoMomentumV5IndicatorMath {
    private DoflamingoMomentumV5IndicatorMath() {
    }

    static Optional<IchimokuSnapshot> ichimoku(List<BarEvent> bars, DoflamingoMomentumV5Parameters params) {
        int currentIndex = bars.size() - 1;
        int presentSourceIndex = currentIndex - params.ichimokuDisplacement();
        if (presentSourceIndex < params.ichimokuSpanBPeriod() - 1
                || currentIndex < params.ichimokuSpanBPeriod() - 1) {
            return Optional.empty();
        }
        double conversion = midpoint(bars, currentIndex, params.ichimokuConversionPeriod());
        double base = midpoint(bars, currentIndex, params.ichimokuBasePeriod());
        double futureSpanA = (conversion + base) / 2.0d;
        double futureSpanB = midpoint(bars, currentIndex, params.ichimokuSpanBPeriod());
        double presentConversion = midpoint(bars, presentSourceIndex, params.ichimokuConversionPeriod());
        double presentBase = midpoint(bars, presentSourceIndex, params.ichimokuBasePeriod());
        double presentSpanA = (presentConversion + presentBase) / 2.0d;
        double presentSpanB = midpoint(bars, presentSourceIndex, params.ichimokuSpanBPeriod());
        return Optional.of(new IchimokuSnapshot(conversion, base, presentSpanA, presentSpanB, futureSpanA, futureSpanB));
    }

    static Optional<MarketSnapshot> snapshot(List<BarEvent> bars, DoflamingoMomentumV5Parameters params) {
        if (bars == null || bars.isEmpty()) {
            return Optional.empty();
        }
        int index = bars.size() - 1;
        Optional<IchimokuSnapshot> ichimoku = ichimoku(bars, params);
        OptionalDouble atr = atr(bars, index, params.atrPeriod());
        OptionalDouble averageAtr = averageAtr(bars, index, params.atrPeriod(), params.volumeLookbackBars());
        OptionalDouble emaFast = ema(bars, index, params.emaFastPeriod());
        OptionalDouble emaMid = ema(bars, index, params.emaMidPeriod());
        OptionalDouble emaMidPrevious = ema(bars, index - 1, params.emaMidPeriod());
        OptionalDouble emaAnchor = ema(bars, index, params.emaAnchorPeriod());
        OptionalDouble smaSlow = sma(bars, index, params.smaSlowPeriod());
        Optional<MacdSnapshot> macd = macd(bars, index, params.macdFastPeriod(), params.macdSlowPeriod(), params.macdSignalPeriod());
        Optional<StochRsiSnapshot> stochRsi = stochRsi(bars, index, params.stochRsiPeriod(), params.stochRsiK(), params.stochRsiD());
        if (ichimoku.isEmpty() || atr.isEmpty() || emaFast.isEmpty() || emaMid.isEmpty()
                || emaMidPrevious.isEmpty() || emaAnchor.isEmpty() || macd.isEmpty()) {
            return Optional.empty();
        }
        double volumePulse = volumePulse(bars, index, params.volumeLookbackBars());
        boolean psarBullish = psarBullish(bars, index, params.emaFastPeriod());
        return Optional.of(new MarketSnapshot(
                bars.get(index),
                index,
                open(bars.get(index)),
                high(bars.get(index)),
                low(bars.get(index)),
                close(bars.get(index)),
                volume(bars.get(index)),
                ichimoku.get(),
                atr.getAsDouble(),
                averageAtr.orElse(atr.getAsDouble()),
                emaFast.getAsDouble(),
                emaMid.getAsDouble(),
                emaMidPrevious.getAsDouble(),
                emaAnchor.getAsDouble(),
                smaSlow.orElse(Double.NaN),
                macd.get(),
                stochRsi.orElse(StochRsiSnapshot.missing()),
                psarBullish,
                volumePulse
        ));
    }

    static OptionalDouble atr(List<BarEvent> bars, int index, int period) {
        if (period < 1 || index < period) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - period + 1; candidate <= index; candidate++) {
            BarEvent bar = bars.get(candidate);
            double previousClose = close(bars.get(candidate - 1));
            double highLow = high(bar) - low(bar);
            double highClose = Math.abs(high(bar) - previousClose);
            double lowClose = Math.abs(low(bar) - previousClose);
            total += Math.max(highLow, Math.max(highClose, lowClose));
        }
        return OptionalDouble.of(total / period);
    }

    static OptionalDouble ema(List<BarEvent> bars, int index, int period) {
        if (period < 1 || index < period - 1) {
            return OptionalDouble.empty();
        }
        double[] ema = emaSeries(closes(bars), period);
        double value = ema[index];
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    static double previousHigh(List<BarEvent> bars, int index, int lookback) {
        int start = Math.max(0, index - lookback);
        double value = Double.NEGATIVE_INFINITY;
        for (int candidate = start; candidate < index; candidate++) {
            value = Math.max(value, high(bars.get(candidate)));
        }
        return Double.isFinite(value) ? value : high(bars.get(index));
    }

    static double previousLow(List<BarEvent> bars, int index, int lookback) {
        int start = Math.max(0, index - lookback);
        double value = Double.POSITIVE_INFINITY;
        for (int candidate = start; candidate < index; candidate++) {
            value = Math.min(value, low(bars.get(candidate)));
        }
        return Double.isFinite(value) ? value : low(bars.get(index));
    }

    static double highestHigh(List<BarEvent> bars, int index, int lookback) {
        int start = Math.max(0, index - lookback + 1);
        double value = Double.NEGATIVE_INFINITY;
        for (int candidate = start; candidate <= index; candidate++) {
            value = Math.max(value, high(bars.get(candidate)));
        }
        return value;
    }

    static double lowestLow(List<BarEvent> bars, int index, int lookback) {
        int start = Math.max(0, index - lookback + 1);
        double value = Double.POSITIVE_INFINITY;
        for (int candidate = start; candidate <= index; candidate++) {
            value = Math.min(value, low(bars.get(candidate)));
        }
        return value;
    }

    private static OptionalDouble sma(List<BarEvent> bars, int index, int period) {
        if (period < 1 || index < period - 1) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - period + 1; candidate <= index; candidate++) {
            total += close(bars.get(candidate));
        }
        return OptionalDouble.of(total / period);
    }

    private static OptionalDouble averageAtr(List<BarEvent> bars, int index, int atrPeriod, int lookback) {
        if (lookback < 1 || index < atrPeriod + lookback - 1) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - lookback + 1; candidate <= index; candidate++) {
            OptionalDouble atr = atr(bars, candidate, atrPeriod);
            if (atr.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += atr.getAsDouble();
        }
        return OptionalDouble.of(total / lookback);
    }

    private static Optional<MacdSnapshot> macd(List<BarEvent> bars, int index, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod < 1 || slowPeriod <= fastPeriod || signalPeriod < 1 || index < slowPeriod + signalPeriod) {
            return Optional.empty();
        }
        double[] closes = closes(bars);
        double[] fast = emaSeries(closes, fastPeriod);
        double[] slow = emaSeries(closes, slowPeriod);
        double[] macdLine = new double[closes.length];
        Arrays.fill(macdLine, Double.NaN);
        for (int i = 0; i < closes.length; i++) {
            if (Double.isFinite(fast[i]) && Double.isFinite(slow[i])) {
                macdLine[i] = fast[i] - slow[i];
            }
        }
        double[] signal = emaSeriesIgnoringNaN(macdLine, signalPeriod);
        if (!Double.isFinite(macdLine[index]) || !Double.isFinite(signal[index])
                || !Double.isFinite(macdLine[index - 1]) || !Double.isFinite(signal[index - 1])
                || !Double.isFinite(macdLine[index - 2]) || !Double.isFinite(signal[index - 2])) {
            return Optional.empty();
        }
        double histogram = macdLine[index] - signal[index];
        double previousHistogram = macdLine[index - 1] - signal[index - 1];
        double secondPreviousHistogram = macdLine[index - 2] - signal[index - 2];
        return Optional.of(new MacdSnapshot(macdLine[index], signal[index], histogram, previousHistogram, secondPreviousHistogram));
    }

    private static Optional<StochRsiSnapshot> stochRsi(List<BarEvent> bars, int index, int rsiPeriod, int kPeriod, int dPeriod) {
        int minimum = rsiPeriod + kPeriod + dPeriod;
        if (rsiPeriod < 1 || kPeriod < 1 || dPeriod < 1 || index < minimum) {
            return Optional.empty();
        }
        double[] rsi = rsiSeries(closes(bars), rsiPeriod);
        double[] k = rollingStoch(rsi, rsiPeriod, kPeriod);
        double[] d = smaSeries(k, dPeriod);
        if (!Double.isFinite(k[index]) || !Double.isFinite(d[index])) {
            return Optional.empty();
        }
        double previousK = Double.isFinite(k[index - 1]) ? k[index - 1] : k[index];
        double previousD = Double.isFinite(d[index - 1]) ? d[index - 1] : d[index];
        return Optional.of(new StochRsiSnapshot(k[index], d[index], previousK, previousD));
    }

    private static double[] emaSeries(double[] values, int period) {
        double[] ema = new double[values.length];
        Arrays.fill(ema, Double.NaN);
        if (period < 1 || values.length < period) {
            return ema;
        }
        double seed = 0.0d;
        for (int i = 0; i < period; i++) {
            if (!Double.isFinite(values[i])) {
                return ema;
            }
            seed += values[i];
        }
        ema[period - 1] = seed / period;
        double multiplier = 2.0d / (period + 1.0d);
        for (int i = period; i < values.length; i++) {
            ema[i] = values[i] * multiplier + ema[i - 1] * (1.0d - multiplier);
        }
        return ema;
    }

    private static double[] emaSeriesIgnoringNaN(double[] values, int period) {
        double[] ema = new double[values.length];
        Arrays.fill(ema, Double.NaN);
        List<Double> seedValues = new ArrayList<>();
        int seedIndex = -1;
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i])) {
                seedValues.add(values[i]);
                if (seedValues.size() == period) {
                    seedIndex = i;
                    break;
                }
            }
        }
        if (seedIndex < 0) {
            return ema;
        }
        double seed = seedValues.stream().mapToDouble(Double::doubleValue).sum() / period;
        ema[seedIndex] = seed;
        double multiplier = 2.0d / (period + 1.0d);
        for (int i = seedIndex + 1; i < values.length; i++) {
            ema[i] = Double.isFinite(values[i])
                    ? values[i] * multiplier + ema[i - 1] * (1.0d - multiplier)
                    : ema[i - 1];
        }
        return ema;
    }

    private static double[] rsiSeries(double[] closes, int period) {
        double[] rsi = new double[closes.length];
        Arrays.fill(rsi, Double.NaN);
        if (period < 1 || closes.length <= period) {
            return rsi;
        }
        double gain = 0.0d;
        double loss = 0.0d;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            gain += Math.max(0.0d, change);
            loss += Math.max(0.0d, -change);
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        rsi[period] = avgLoss == 0.0d ? 100.0d : 100.0d - (100.0d / (1.0d + avgGain / avgLoss));
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(0.0d, change)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(0.0d, -change)) / period;
            rsi[i] = avgLoss == 0.0d ? 100.0d : 100.0d - (100.0d / (1.0d + avgGain / avgLoss));
        }
        return rsi;
    }

    private static double[] rollingStoch(double[] values, int lookback, int smooth) {
        double[] raw = new double[values.length];
        Arrays.fill(raw, Double.NaN);
        for (int index = 0; index < values.length; index++) {
            if (index < lookback || !Double.isFinite(values[index])) {
                continue;
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int candidate = index - lookback + 1; candidate <= index; candidate++) {
                if (Double.isFinite(values[candidate])) {
                    min = Math.min(min, values[candidate]);
                    max = Math.max(max, values[candidate]);
                }
            }
            if (Double.isFinite(min) && Double.isFinite(max) && max > min) {
                raw[index] = ((values[index] - min) / (max - min)) * 100.0d;
            }
        }
        return smaSeries(raw, smooth);
    }

    private static double[] smaSeries(double[] values, int period) {
        double[] sma = new double[values.length];
        Arrays.fill(sma, Double.NaN);
        for (int index = period - 1; index < values.length; index++) {
            double total = 0.0d;
            boolean complete = true;
            for (int candidate = index - period + 1; candidate <= index; candidate++) {
                if (!Double.isFinite(values[candidate])) {
                    complete = false;
                    break;
                }
                total += values[candidate];
            }
            if (complete) {
                sma[index] = total / period;
            }
        }
        return sma;
    }

    private static boolean psarBullish(List<BarEvent> bars, int index, int fastPeriod) {
        if (index < Math.max(2, fastPeriod - 1)) {
            return false;
        }
        OptionalDouble fastEma = ema(bars, index, fastPeriod);
        OptionalDouble previousFastEma = ema(bars, index - 1, fastPeriod);
        if (fastEma.isEmpty() || previousFastEma.isEmpty()) {
            return close(bars.get(index)) > close(bars.get(index - 1));
        }
        return close(bars.get(index)) >= fastEma.getAsDouble()
                && fastEma.getAsDouble() >= previousFastEma.getAsDouble()
                && low(bars.get(index)) >= low(bars.get(index - 1)) * 0.995d;
    }

    private static double volumePulse(List<BarEvent> bars, int index, int period) {
        if (period < 1 || index < period - 1) {
            return 0.0d;
        }
        double total = 0.0d;
        for (int candidate = index - period + 1; candidate <= index; candidate++) {
            total += volume(bars.get(candidate));
        }
        double average = total / period;
        return average <= 0.0d ? 0.0d : volume(bars.get(index)) / average;
    }

    private static double midpoint(List<BarEvent> bars, int index, int period) {
        int start = index - period + 1;
        double highestHigh = Double.NEGATIVE_INFINITY;
        double lowestLow = Double.POSITIVE_INFINITY;
        for (int candidate = start; candidate <= index; candidate++) {
            highestHigh = Math.max(highestHigh, high(bars.get(candidate)));
            lowestLow = Math.min(lowestLow, low(bars.get(candidate)));
        }
        return (highestHigh + lowestLow) / 2.0d;
    }

    private static double[] closes(List<BarEvent> bars) {
        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = close(bars.get(i));
        }
        return closes;
    }

    static double open(BarEvent bar) {
        return bar.ohlcv().open().doubleValue();
    }

    static double high(BarEvent bar) {
        return bar.ohlcv().high().doubleValue();
    }

    static double low(BarEvent bar) {
        return bar.ohlcv().low().doubleValue();
    }

    static double close(BarEvent bar) {
        return bar.ohlcv().close().doubleValue();
    }

    static double volume(BarEvent bar) {
        return bar.ohlcv().volume().doubleValue();
    }

    record IchimokuSnapshot(
            double conversionLine,
            double baseLine,
            double presentSpanA,
            double presentSpanB,
            double futureSpanA,
            double futureSpanB
    ) {
        double cloudTop() {
            return Math.max(presentSpanA, presentSpanB);
        }

        double cloudFloor() {
            return Math.min(presentSpanA, presentSpanB);
        }

        String futureBias() {
            return futureSpanA > futureSpanB ? "BULLISH" : "BEARISH";
        }

        String conversionBaseState() {
            if (conversionLine > baseLine) {
                return "BULLISH";
            }
            if (conversionLine < baseLine) {
                return "BEARISH";
            }
            return "NEUTRAL";
        }
    }

    record MarketSnapshot(
            BarEvent currentBar,
            int index,
            double open,
            double high,
            double low,
            double close,
            double volume,
            IchimokuSnapshot ichimoku,
            double atr,
            double averageAtr,
            double emaFast,
            double emaMid,
            double emaMidPrevious,
            double emaAnchor,
            double smaSlow,
            MacdSnapshot macd,
            StochRsiSnapshot stochRsi,
            boolean psarBullish,
            double volumePulse
    ) {
        double cloudThicknessAtr() {
            return Math.abs(ichimoku.presentSpanA() - ichimoku.presentSpanB()) / safeAtr();
        }

        double futureSpreadAtr() {
            return Math.abs(ichimoku.futureSpanA() - ichimoku.futureSpanB()) / safeAtr();
        }

        double atrExpansionRatio() {
            return averageAtr <= 0.0d ? 1.0d : atr / averageAtr;
        }

        double safeAtr() {
            return atr <= 0.0d ? Math.max(0.01d, high - low) : atr;
        }

        String cloudBias() {
            if (close > ichimoku.cloudTop() && ichimoku.futureSpanA() >= ichimoku.futureSpanB()
                    && ichimoku.conversionLine() >= ichimoku.baseLine()) {
                return "BULLISH";
            }
            if (close < ichimoku.cloudFloor() && ichimoku.futureSpanA() <= ichimoku.futureSpanB()
                    && ichimoku.conversionLine() <= ichimoku.baseLine()) {
                return "BEARISH";
            }
            return "NEUTRAL";
        }

        String emaStackState() {
            if (emaFast > emaMid && emaMid > emaAnchor) {
                return "BULLISH_STACK";
            }
            if (emaFast < emaMid && emaMid < emaAnchor) {
                return "BEARISH_STACK";
            }
            return "MIXED_STACK";
        }
    }

    record MacdSnapshot(double line, double signal, double histogram, double previousHistogram, double secondPreviousHistogram) {
        boolean bullish() {
            return line > signal && histogram > 0.0d && histogram > previousHistogram && previousHistogram >= secondPreviousHistogram;
        }

        boolean bearish() {
            return line < signal && histogram < 0.0d && histogram < previousHistogram && previousHistogram <= secondPreviousHistogram;
        }

        String slopeLabel() {
            if (histogram > previousHistogram && previousHistogram >= secondPreviousHistogram) {
                return "RISING";
            }
            if (histogram < previousHistogram && previousHistogram <= secondPreviousHistogram) {
                return "FALLING";
            }
            return "FLAT";
        }
    }

    record StochRsiSnapshot(double k, double d, double previousK, double previousD) {
        static StochRsiSnapshot missing() {
            return new StochRsiSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        boolean bullish() {
            return Double.isFinite(k) && Double.isFinite(d) && k > d && k >= 20.0d && k <= 85.0d && previousK >= previousD;
        }

        boolean bearish() {
            return Double.isFinite(k) && Double.isFinite(d) && k < d && k >= 15.0d && k <= 80.0d && previousK <= previousD;
        }

        String stateLabel(boolean bullishSide) {
            if (!Double.isFinite(k) || !Double.isFinite(d)) {
                return "MISSING";
            }
            if (bullishSide && bullish()) {
                return "BULLISH_NOT_OVERBOUGHT";
            }
            if (!bullishSide && bearish()) {
                return "BEARISH_NOT_OVERSOLD";
            }
            return "NEUTRAL";
        }
    }
}
