package org.algotradex.strategy.samples.doflamingov4;

import org.algotradex.platform.contracts.market.BarEvent;
import org.algotradex.platform.core.api.indicator.RollingIndicators;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Package-local indicator implementation used by the Doflamingo v4 sample strategies.
 * <p>
 * The calculations are deterministic helpers for sample strategy decisions and tests. They are not
 * a shared platform indicator service, market-data adapter, or guarantee of vendor/exchange formula
 * parity beyond this implementation.
 */
final class DoflamingoIndicatorMath {
    static final int ICHIMOKU_CONVERSION_PERIOD = 9;
    static final int ICHIMOKU_BASE_PERIOD = 26;
    static final int ICHIMOKU_SPAN_B_PERIOD = 52;
    static final int ICHIMOKU_DISPLACEMENT = 26;
    static final int TREND_MINIMUM_INDEX = 202;

    private DoflamingoIndicatorMath() {
    }

    static Optional<IchimokuSnapshot> ichimoku(List<BarEvent> bars) {
        int currentIndex = bars.size() - 1;
        int presentSourceIndex = currentIndex - ICHIMOKU_DISPLACEMENT;
        if (presentSourceIndex < ICHIMOKU_SPAN_B_PERIOD - 1
                || currentIndex < ICHIMOKU_SPAN_B_PERIOD - 1) {
            return Optional.empty();
        }

        double conversion = midpoint(bars, currentIndex, ICHIMOKU_CONVERSION_PERIOD);
        double base = midpoint(bars, currentIndex, ICHIMOKU_BASE_PERIOD);
        double futureSpanA = (conversion + base) / 2.0d;
        double futureSpanB = midpoint(bars, currentIndex, ICHIMOKU_SPAN_B_PERIOD);

        double presentConversion = midpoint(bars, presentSourceIndex, ICHIMOKU_CONVERSION_PERIOD);
        double presentBase = midpoint(bars, presentSourceIndex, ICHIMOKU_BASE_PERIOD);
        double presentSpanA = (presentConversion + presentBase) / 2.0d;
        double presentSpanB = midpoint(bars, presentSourceIndex, ICHIMOKU_SPAN_B_PERIOD);

        return Optional.of(new IchimokuSnapshot(
                conversion,
                base,
                presentSpanA,
                presentSpanB,
                futureSpanA,
                futureSpanB
        ));
    }

    static OptionalDouble laggedEma(List<BarEvent> bars, int index, int period) {
        if (index < period) {
            return OptionalDouble.empty();
        }
        double[] closes = closes(bars);
        double[] ema = emaSeries(closes, period);
        double value = ema[index - 1];
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    static OptionalDouble closedBarEma(List<BarEvent> bars, int index, int period) {
        if (index < period - 1) {
            return OptionalDouble.empty();
        }
        double[] closes = closes(bars);
        double[] ema = emaSeries(closes, period);
        double value = ema[index];
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
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

    static boolean volumeAtLeastAverageMultiple(List<BarEvent> bars, int index, int period, double multiple) {
        if (multiple <= 0.0d) {
            return true;
        }
        if (period < 1 || index < period - 1) {
            return false;
        }
        double total = 0.0d;
        for (int candidate = index - period + 1; candidate <= index; candidate++) {
            total += volume(bars.get(candidate));
        }
        double average = total / period;
        return average > 0.0d && volume(bars.get(index)) >= average * multiple;
    }

    static boolean recentRsiExtreme(List<BarEvent> bars, int index, int lookback, double threshold, boolean oversold) {
        if (lookback <= 0) {
            return true;
        }
        if (index < 14) {
            return false;
        }
        double[] rsi = rsiSeries(closes(bars), 14);
        int start = Math.max(14, index - lookback + 1);
        for (int candidate = start; candidate <= index; candidate++) {
            double value = rsi[candidate];
            if (!Double.isNaN(value) && (oversold ? value <= threshold : value >= threshold)) {
                return true;
            }
        }
        return false;
    }

    static boolean chikouClearSpace(List<BarEvent> bars, int index, boolean bullish) {
        int lagIndex = index - ICHIMOKU_DISPLACEMENT;
        if (lagIndex < 0 || lagIndex >= bars.size()) {
            return false;
        }
        double close = close(bars.get(index));
        BarEvent lagged = bars.get(lagIndex);
        return bullish ? close > high(lagged) : close < low(lagged);
    }

    static boolean tkConfirmationFresh(List<BarEvent> bars, int index, int lookback, boolean bullish) {
        if (lookback <= 0) {
            return true;
        }
        int start = Math.max(ICHIMOKU_BASE_PERIOD - 1, index - lookback + 1);
        for (int candidate = start; candidate <= index; candidate++) {
            double conversion = midpoint(bars, candidate, ICHIMOKU_CONVERSION_PERIOD);
            double base = midpoint(bars, candidate, ICHIMOKU_BASE_PERIOD);
            if (bullish ? conversion > base : conversion < base) {
                return true;
            }
        }
        return false;
    }

    static boolean atrExpansionAtLeast(List<BarEvent> bars, int index, int period, double multiple) {
        if (multiple <= 0.0d) {
            return true;
        }
        OptionalDouble current = atr(bars, index, period);
        OptionalDouble previous = atr(bars, index - 1, period);
        return current.isPresent() && previous.isPresent() && current.getAsDouble() >= previous.getAsDouble() * multiple;
    }

    static OptionalDouble trendScore(List<BarEvent> bars, int index) {
        if (index < TREND_MINIMUM_INDEX) {
            return OptionalDouble.empty();
        }
        OptionalDouble sma20 = laggedSma(bars, index, 20);
        OptionalDouble sma50 = laggedSma(bars, index, 50);
        OptionalDouble sma200 = laggedSma(bars, index, 200);
        OptionalDouble sma20Last = laggedSma(bars, index - 1, 20);
        OptionalDouble sma50Last = laggedSma(bars, index - 1, 50);
        OptionalDouble sma200Last = laggedSma(bars, index - 1, 200);
        OptionalDouble sma20SecondLast = laggedSma(bars, index - 2, 20);
        OptionalDouble sma50SecondLast = laggedSma(bars, index - 2, 50);
        OptionalDouble sma200SecondLast = laggedSma(bars, index - 2, 200);
        if (sma20.isEmpty() || sma50.isEmpty() || sma200.isEmpty()
                || sma20Last.isEmpty() || sma50Last.isEmpty() || sma200Last.isEmpty()
                || sma20SecondLast.isEmpty() || sma50SecondLast.isEmpty() || sma200SecondLast.isEmpty()) {
            return OptionalDouble.empty();
        }

        double close = close(bars.get(index));
        double score = 0.0d;
        double sma20Value = sma20.getAsDouble();
        double sma50Value = sma50.getAsDouble();
        double sma200Value = sma200.getAsDouble();
        double sma20SecondDiff = sma20Value - sma20SecondLast.getAsDouble();
        double sma50SecondDiff = sma50Value - sma50SecondLast.getAsDouble();
        double sma200SecondDiff = sma200Value - sma200SecondLast.getAsDouble();

        if (close > sma20Value && sma20Value > sma50Value && sma50Value > sma200Value) {
            score += 100.0d;
        }
        if (close < sma20Value && sma20Value < sma50Value && sma50Value < sma200Value) {
            score -= 100.0d;
        }
        score += ternary(close, sma200Value, 50.0d);
        score += slopeScore(sma200SecondDiff, 50.0d);
        score += ternary(close, sma50Value, 12.5d);
        score += slopeScore(sma50SecondDiff, 12.5d);
        score += ternary(close, sma20Value, 5.0d);
        score += slopeScore(sma20SecondDiff, 5.0d);
        score += ternary(sma20Value, sma200Value, 50.0d);
        score += ternary(sma50Value, sma200Value, 25.0d);
        score += ternary(sma20Value, sma50Value, 12.5d);
        return OptionalDouble.of(score);
    }

    static OptionalDouble trendAverage(List<BarEvent> bars, int index, int lookback) {
        if (lookback < 1 || index - lookback + 1 < TREND_MINIMUM_INDEX) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - lookback + 1; candidate <= index; candidate++) {
            OptionalDouble score = trendScore(bars, candidate);
            if (score.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += score.getAsDouble();
        }
        return OptionalDouble.of(total / lookback);
    }

    static OptionalDouble closedTrendScore(List<BarEvent> bars, int index) {
        if (index < TREND_MINIMUM_INDEX) {
            return OptionalDouble.empty();
        }
        OptionalDouble sma20 = closedBarSma(bars, index, 20);
        OptionalDouble sma50 = closedBarSma(bars, index, 50);
        OptionalDouble sma200 = closedBarSma(bars, index, 200);
        OptionalDouble sma20Last = closedBarSma(bars, index - 1, 20);
        OptionalDouble sma50Last = closedBarSma(bars, index - 1, 50);
        OptionalDouble sma200Last = closedBarSma(bars, index - 1, 200);
        OptionalDouble sma20SecondLast = closedBarSma(bars, index - 2, 20);
        OptionalDouble sma50SecondLast = closedBarSma(bars, index - 2, 50);
        OptionalDouble sma200SecondLast = closedBarSma(bars, index - 2, 200);
        if (sma20.isEmpty() || sma50.isEmpty() || sma200.isEmpty()
                || sma20Last.isEmpty() || sma50Last.isEmpty() || sma200Last.isEmpty()
                || sma20SecondLast.isEmpty() || sma50SecondLast.isEmpty() || sma200SecondLast.isEmpty()) {
            return OptionalDouble.empty();
        }

        double close = close(bars.get(index));
        double score = 0.0d;
        double sma20Value = sma20.getAsDouble();
        double sma50Value = sma50.getAsDouble();
        double sma200Value = sma200.getAsDouble();
        double sma20SecondDiff = sma20Value - sma20SecondLast.getAsDouble();
        double sma50SecondDiff = sma50Value - sma50SecondLast.getAsDouble();
        double sma200SecondDiff = sma200Value - sma200SecondLast.getAsDouble();

        if (close > sma20Value && sma20Value > sma50Value && sma50Value > sma200Value) {
            score += 100.0d;
        }
        if (close < sma20Value && sma20Value < sma50Value && sma50Value < sma200Value) {
            score -= 100.0d;
        }
        score += ternary(close, sma200Value, 50.0d);
        score += slopeScore(sma200SecondDiff, 50.0d);
        score += ternary(close, sma50Value, 12.5d);
        score += slopeScore(sma50SecondDiff, 12.5d);
        score += ternary(close, sma20Value, 5.0d);
        score += slopeScore(sma20SecondDiff, 5.0d);
        score += ternary(sma20Value, sma200Value, 50.0d);
        score += ternary(sma50Value, sma200Value, 25.0d);
        score += ternary(sma20Value, sma50Value, 12.5d);
        return OptionalDouble.of(score);
    }

    static OptionalDouble closedTrendAverage(List<BarEvent> bars, int index, int lookback) {
        if (lookback < 1 || index - lookback + 1 < TREND_MINIMUM_INDEX) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - lookback + 1; candidate <= index; candidate++) {
            OptionalDouble score = closedTrendScore(bars, candidate);
            if (score.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += score.getAsDouble();
        }
        return OptionalDouble.of(total / lookback);
    }

    static Optional<MultiIndicatorState> multiIndicatorState(List<BarEvent> bars, int macdFastPeriod,
                                                              int macdSlowPeriod, int macdSignalPeriod) {
        int index = bars.size() - 1;
        if (index < 60) {
            return Optional.empty();
        }
        Optional<IchimokuSnapshot> ichimoku = ichimoku(bars);
        if (ichimoku.isEmpty()) {
            return Optional.empty();
        }
        MacdSeries macd = laggedMacd(bars, macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
        StochRsiSeries stoch = laggedStochRsi(bars, 14, 14, 3, 3);
        double[] psar = psarSeries(bars);
        if (index < 2 || missing(macd.histogram(), index) || missing(macd.histogram(), index - 1)
                || missing(macd.histogram(), index - 2) || missing(macd.signal(), index)
                || missing(psar, index) || missing(psar, index - 1)) {
            return Optional.empty();
        }
        return Optional.of(new MultiIndicatorState(
                ichimoku.get().presentSpanA(),
                ichimoku.get().presentSpanB(),
                macd.histogram()[index],
                macd.histogram()[index - 1],
                macd.histogram()[index - 2],
                macd.signal()[index],
                stoch.k()[index],
                stoch.k()[index - 1],
                stoch.d()[index],
                stoch.d()[index - 1],
                psar[index],
                psar[index - 1]
        ));
    }

    static MultiIndicatorTracker multiIndicatorTracker(int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod) {
        return new MultiIndicatorTracker(macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
    }

    private static OptionalDouble laggedSma(List<BarEvent> bars, int index, int period) {
        if (index < period) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - period; candidate < index; candidate++) {
            total += close(bars.get(candidate));
        }
        return OptionalDouble.of(total / period);
    }

    private static OptionalDouble closedBarSma(List<BarEvent> bars, int index, int period) {
        if (index < period - 1) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (int candidate = index - period + 1; candidate <= index; candidate++) {
            total += close(bars.get(candidate));
        }
        return OptionalDouble.of(total / period);
    }

    private static MacdSeries laggedMacd(List<BarEvent> bars, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] closes = closes(bars);
        double[] fast = emaSeries(closes, fastPeriod);
        double[] slow = emaSeries(closes, slowPeriod);
        double[] macd = new double[closes.length];
        Arrays.fill(macd, Double.NaN);
        for (int index = 0; index < closes.length; index++) {
            if (!Double.isNaN(fast[index]) && !Double.isNaN(slow[index])) {
                macd[index] = fast[index] - slow[index];
            }
        }
        double[] signal = macdSignalSeries(macd, signalPeriod);
        double[] histogram = new double[closes.length];
        Arrays.fill(histogram, Double.NaN);
        for (int index = 0; index < closes.length; index++) {
            if (!Double.isNaN(macd[index]) && !Double.isNaN(signal[index])) {
                histogram[index] = macd[index] - signal[index];
            }
        }
        return new MacdSeries(signal, histogram);
    }

    private static StochRsiSeries laggedStochRsi(List<BarEvent> bars, int rsiPeriod, int stochasticPeriod,
                                                 int kPeriod, int dPeriod) {
        double[] closes = closes(bars);
        double[] rsi = rsiSeries(closes, rsiPeriod);
        double[] stochRsi = new double[closes.length];
        Arrays.fill(stochRsi, Double.NaN);
        for (int index = rsiPeriod + stochasticPeriod; index < closes.length; index++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int candidate = index - stochasticPeriod + 1; candidate <= index; candidate++) {
                if (!Double.isNaN(rsi[candidate])) {
                    min = Math.min(min, rsi[candidate]);
                    max = Math.max(max, rsi[candidate]);
                }
            }
            if (max > min && Double.isFinite(min)) {
                stochRsi[index] = ((rsi[index] - min) / (max - min)) * 100.0d;
            }
        }
        double[] k = smaSeries(stochRsi, kPeriod);
        return new StochRsiSeries(k, smaSeries(k, dPeriod));
    }

    private static double[] rsiSeries(double[] closes, int period) {
        double[] rsi = new double[closes.length];
        Arrays.fill(rsi, Double.NaN);
        for (int index = period; index < closes.length; index++) {
            double gain = 0.0d;
            double loss = 0.0d;
            for (int candidate = index - period + 1; candidate <= index; candidate++) {
                double change = closes[candidate] - closes[candidate - 1];
                if (change > 0.0d) {
                    gain += change;
                } else {
                    loss += Math.abs(change);
                }
            }
            if (gain == 0.0d && loss == 0.0d) {
                rsi[index] = 50.0d;
            } else if (loss == 0.0d) {
                rsi[index] = 100.0d;
            } else if (gain == 0.0d) {
                rsi[index] = 0.0d;
            } else {
                double relativeStrength = (gain / period) / (loss / period);
                rsi[index] = 100.0d - (100.0d / (1.0d + relativeStrength));
            }
        }
        return rsi;
    }

    private static double[] smaSeries(double[] values, int period) {
        double[] series = new double[values.length];
        Arrays.fill(series, Double.NaN);
        for (int index = period - 1; index < values.length; index++) {
            double total = 0.0d;
            for (int candidate = index - period + 1; candidate <= index; candidate++) {
                if (Double.isNaN(values[candidate])) {
                    total = Double.NaN;
                    break;
                }
                total += values[candidate];
            }
            if (!Double.isNaN(total)) {
                series[index] = total / period;
            }
        }
        return series;
    }

    private static double[] psarSeries(List<BarEvent> bars) {
        double[] series = new double[bars.size()];
        Arrays.fill(series, Double.NaN);
        if (bars.size() < 2) {
            return series;
        }
        boolean uptrend = close(bars.get(1)) >= close(bars.get(0));
        double acceleration = 0.02d;
        double extreme = uptrend
                ? Math.max(high(bars.get(0)), high(bars.get(1)))
                : Math.min(low(bars.get(0)), low(bars.get(1)));
        double sar = uptrend
                ? Math.min(low(bars.get(0)), low(bars.get(1)))
                : Math.max(high(bars.get(0)), high(bars.get(1)));
        series[1] = sar;
        for (int index = 2; index < bars.size(); index++) {
            sar = sar + acceleration * (extreme - sar);
            if (uptrend) {
                sar = Math.min(sar, Math.min(low(bars.get(index - 1)), low(bars.get(index - 2))));
                if (low(bars.get(index)) < sar) {
                    uptrend = false;
                    sar = extreme;
                    extreme = low(bars.get(index));
                    acceleration = 0.02d;
                } else if (high(bars.get(index)) > extreme) {
                    extreme = high(bars.get(index));
                    acceleration = Math.min(0.2d, acceleration + 0.02d);
                }
            } else {
                sar = Math.max(sar, Math.max(high(bars.get(index - 1)), high(bars.get(index - 2))));
                if (high(bars.get(index)) > sar) {
                    uptrend = true;
                    sar = extreme;
                    extreme = high(bars.get(index));
                    acceleration = 0.02d;
                } else if (low(bars.get(index)) < extreme) {
                    extreme = low(bars.get(index));
                    acceleration = Math.min(0.2d, acceleration + 0.02d);
                }
            }
            series[index] = sar;
        }
        return series;
    }

    private static double[] emaSeries(double[] values, int period) {
        double[] series = new double[values.length];
        Arrays.fill(series, Double.NaN);
        if (values.length < period) {
            return series;
        }
        double seed = 0.0d;
        for (int index = 0; index < period; index++) {
            seed += values[index];
        }
        double ema = seed / period;
        series[period - 1] = ema;
        double multiplier = 2.0d / (period + 1.0d);
        for (int index = period; index < values.length; index++) {
            ema = (values[index] * multiplier) + (ema * (1.0d - multiplier));
            series[index] = ema;
        }
        return series;
    }

    private static double[] macdSignalSeries(double[] macd, int signalPeriod) {
        double[] signal = new double[macd.length];
        Arrays.fill(signal, Double.NaN);
        int validCount = 0;
        double seed = 0.0d;
        double ema = Double.NaN;
        double multiplier = 2.0d / (signalPeriod + 1.0d);
        for (int index = 0; index < macd.length; index++) {
            if (Double.isNaN(macd[index])) {
                continue;
            }
            validCount++;
            if (validCount <= signalPeriod) {
                seed += macd[index];
                if (validCount == signalPeriod) {
                    ema = seed / signalPeriod;
                    signal[index] = ema;
                }
            } else {
                ema = (macd[index] * multiplier) + (ema * (1.0d - multiplier));
                signal[index] = ema;
            }
        }
        return signal;
    }

    private static double[] lagged(double[] values) {
        double[] lagged = new double[values.length];
        Arrays.fill(lagged, Double.NaN);
        for (int index = 1; index < values.length; index++) {
            lagged[index] = values[index - 1];
        }
        return lagged;
    }

    private static double midpoint(List<BarEvent> bars, int endIndex, int period) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (int index = endIndex - period + 1; index <= endIndex; index++) {
            high = Math.max(high, high(bars.get(index)));
            low = Math.min(low, low(bars.get(index)));
        }
        return (high + low) / 2.0d;
    }

    private static double[] closes(List<BarEvent> bars) {
        double[] closes = new double[bars.size()];
        for (int index = 0; index < bars.size(); index++) {
            closes[index] = close(bars.get(index));
        }
        return closes;
    }

    private static double ternary(double left, double right, double weight) {
        if (left > right) {
            return weight;
        }
        if (left < right) {
            return -weight;
        }
        return 0.0d;
    }

    private static double slopeScore(double diff, double weight) {
        if (diff > 0.0d) {
            return weight;
        }
        if (diff < 0.0d) {
            return -weight;
        }
        return 0.0d;
    }

    private static boolean missing(double[] values, int index) {
        return index < 0 || index >= values.length || Double.isNaN(values[index]);
    }

    private static double close(BarEvent bar) {
        return bar.ohlcv().close().doubleValue();
    }

    private static double high(BarEvent bar) {
        return bar.ohlcv().high().doubleValue();
    }

    private static double low(BarEvent bar) {
        return bar.ohlcv().low().doubleValue();
    }

    private static double volume(BarEvent bar) {
        return bar.ohlcv().volume().doubleValue();
    }

    record IchimokuSnapshot(double conversionLine, double baseLine, double presentSpanA, double presentSpanB,
                            double futureSpanA, double futureSpanB) {
    }

    record MultiIndicatorState(double presentSpanA, double presentSpanB, double macdHistogram, double previousMacdHistogram,
                               double secondPreviousMacdHistogram, double macdSignal, double stochK,
                               double previousStochK, double stochD, double previousStochD,
                               double psar, double previousPsar) {
    }

    static final class MultiIndicatorTracker {
        private static final int STOCH_RSI_PERIOD = 14;
        private static final int STOCHASTIC_PERIOD = 14;
        private static final int STOCH_K_PERIOD = 3;
        private static final int STOCH_D_PERIOD = 3;

        private final RollingMidpoint conversion = new RollingMidpoint(ICHIMOKU_CONVERSION_PERIOD);
        private final RollingMidpoint base = new RollingMidpoint(ICHIMOKU_BASE_PERIOD);
        private final RollingMidpoint spanB = new RollingMidpoint(ICHIMOKU_SPAN_B_PERIOD);
        private final ArrayDeque<Double> displacedSpanA = new ArrayDeque<>();
        private final ArrayDeque<Double> displacedSpanB = new ArrayDeque<>();
        private final RollingIndicators.Macd macd;
        private final LaggedStochRsi stochRsi = new LaggedStochRsi(
                STOCH_RSI_PERIOD,
                STOCHASTIC_PERIOD,
                STOCH_K_PERIOD,
                STOCH_D_PERIOD
        );
        private final RollingPsar psar = new RollingPsar();

        private int index = -1;
        private double macdHistogram = Double.NaN;
        private double previousMacdHistogram = Double.NaN;
        private double secondPreviousMacdHistogram = Double.NaN;
        private double macdSignal = Double.NaN;

        private MultiIndicatorTracker(int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod) {
            this.macd = new RollingIndicators.Macd(macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
        }

        Optional<MultiIndicatorState> update(BarEvent bar) {
            index++;
            OptionalDouble currentConversion = conversion.update(bar);
            OptionalDouble currentBase = base.update(bar);
            OptionalDouble currentSpanA = currentConversion.isPresent() && currentBase.isPresent()
                    ? OptionalDouble.of((currentConversion.getAsDouble() + currentBase.getAsDouble()) / 2.0d)
                    : OptionalDouble.empty();
            double presentSpanA = displacedSpanA(currentSpanA);
            double presentSpanB = displacedSpanB(spanB.update(bar));
            advanceMacd(bar);
            StochRsiPoint stochPoint = stochRsi.update(index, close(bar));
            Optional<PsarPoint> psarPoint = psar.update(bar);

            if (index < 60
                    || !finite(presentSpanA)
                    || !finite(presentSpanB)
                    || !finite(macdHistogram)
                    || !finite(previousMacdHistogram)
                    || !finite(secondPreviousMacdHistogram)
                    || !finite(macdSignal)
                    || psarPoint.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MultiIndicatorState(
                    presentSpanA,
                    presentSpanB,
                    macdHistogram,
                    previousMacdHistogram,
                    secondPreviousMacdHistogram,
                    macdSignal,
                    stochPoint.k(),
                    stochPoint.previousK(),
                    stochPoint.d(),
                    stochPoint.previousD(),
                    psarPoint.get().current(),
                    psarPoint.get().previous()
            ));
        }

        private double displacedSpanA(OptionalDouble currentSpanA) {
            displacedSpanA.addLast(currentSpanA.orElse(Double.NaN));
            if (displacedSpanA.size() <= ICHIMOKU_DISPLACEMENT) {
                return Double.NaN;
            }
            return displacedSpanA.removeFirst();
        }

        private double displacedSpanB(OptionalDouble currentSpanB) {
            displacedSpanB.addLast(currentSpanB.orElse(Double.NaN));
            if (displacedSpanB.size() <= ICHIMOKU_DISPLACEMENT) {
                return Double.NaN;
            }
            return displacedSpanB.removeFirst();
        }

        private void advanceMacd(BarEvent bar) {
            macd.update(close(bar));
            double currentHistogram = Double.NaN;
            double currentSignal = Double.NaN;
            Optional<RollingIndicators.MacdPoint> current = macd.current();
            if (current.isPresent()) {
                currentSignal = current.get().signal();
                currentHistogram = current.get().macd() - current.get().signal();
            }
            secondPreviousMacdHistogram = previousMacdHistogram;
            previousMacdHistogram = macdHistogram;
            macdHistogram = currentHistogram;
            macdSignal = currentSignal;
        }
    }

    private static final class LaggedStochRsi {
        private final int rsiPeriod;
        private final int stochasticPeriod;
        private final RollingIndicators.SimpleRsi rsi;
        private final RollingIndicators.SimpleMovingAverage kAverage;
        private final RollingIndicators.SimpleMovingAverage dAverage;
        private final ArrayDeque<Double> rsiWindow = new ArrayDeque<>();

        private double k = Double.NaN;
        private double previousK = Double.NaN;
        private double d = Double.NaN;
        private double previousD = Double.NaN;

        private LaggedStochRsi(int rsiPeriod, int stochasticPeriod, int kPeriod, int dPeriod) {
            this.rsiPeriod = rsiPeriod;
            this.stochasticPeriod = stochasticPeriod;
            this.rsi = new RollingIndicators.SimpleRsi(rsiPeriod);
            this.kAverage = new RollingIndicators.SimpleMovingAverage(kPeriod);
            this.dAverage = new RollingIndicators.SimpleMovingAverage(dPeriod);
        }

        private StochRsiPoint update(int index, double close) {
            double stochRsi = Double.NaN;
            OptionalDouble rsiValue = rsi.update(close);
            if (rsiValue.isPresent()) {
                rsiWindow.addLast(rsiValue.getAsDouble());
                if (rsiWindow.size() > stochasticPeriod) {
                    rsiWindow.removeFirst();
                }
                if (index >= rsiPeriod + stochasticPeriod && rsiWindow.size() == stochasticPeriod) {
                    double min = Double.POSITIVE_INFINITY;
                    double max = Double.NEGATIVE_INFINITY;
                    for (double value : rsiWindow) {
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                    if (max > min && Double.isFinite(min)) {
                        stochRsi = ((rsiValue.getAsDouble() - min) / (max - min)) * 100.0d;
                    }
                }
            }

            OptionalDouble rawK = kAverage.update(stochRsi);
            OptionalDouble rawD = dAverage.update(rawK.orElse(Double.NaN));
            previousK = k;
            previousD = d;
            k = rawK.orElse(Double.NaN);
            d = rawD.orElse(Double.NaN);
            return new StochRsiPoint(k, previousK, d, previousD);
        }
    }

    private static final class RollingPsar {
        private BarEvent first;
        private BarEvent previous;
        private BarEvent secondPrevious;
        private int index = -1;
        private boolean uptrend;
        private double acceleration;
        private double extreme;
        private double sar;
        private double current = Double.NaN;

        private Optional<PsarPoint> update(BarEvent bar) {
            index++;
            if (index == 0) {
                first = bar;
                previous = bar;
                return Optional.empty();
            }
            if (index == 1) {
                uptrend = close(bar) >= close(first);
                acceleration = 0.02d;
                extreme = uptrend
                        ? Math.max(high(first), high(bar))
                        : Math.min(low(first), low(bar));
                sar = uptrend
                        ? Math.min(low(first), low(bar))
                        : Math.max(high(first), high(bar));
                current = sar;
                secondPrevious = first;
                previous = bar;
                return Optional.empty();
            }

            double prior = current;
            sar = sar + acceleration * (extreme - sar);
            if (uptrend) {
                sar = Math.min(sar, Math.min(low(previous), low(secondPrevious)));
                if (low(bar) < sar) {
                    uptrend = false;
                    sar = extreme;
                    extreme = low(bar);
                    acceleration = 0.02d;
                } else if (high(bar) > extreme) {
                    extreme = high(bar);
                    acceleration = Math.min(0.2d, acceleration + 0.02d);
                }
            } else {
                sar = Math.max(sar, Math.max(high(previous), high(secondPrevious)));
                if (high(bar) > sar) {
                    uptrend = true;
                    sar = extreme;
                    extreme = high(bar);
                    acceleration = 0.02d;
                } else if (low(bar) < extreme) {
                    extreme = low(bar);
                    acceleration = Math.min(0.2d, acceleration + 0.02d);
                }
            }
            current = sar;
            secondPrevious = previous;
            previous = bar;
            return Optional.of(new PsarPoint(current, prior));
        }
    }

    private static final class RollingMidpoint {
        private final int period;
        private final ArrayDeque<BarEvent> window = new ArrayDeque<>();

        private RollingMidpoint(int period) {
            this.period = period;
        }

        private OptionalDouble update(BarEvent bar) {
            window.addLast(bar);
            if (window.size() > period) {
                window.removeFirst();
            }
            if (window.size() < period) {
                return OptionalDouble.empty();
            }
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (BarEvent candidate : window) {
                high = Math.max(high, high(candidate));
                low = Math.min(low, low(candidate));
            }
            return OptionalDouble.of((high + low) / 2.0d);
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private record StochRsiPoint(double k, double previousK, double d, double previousD) {
    }

    private record PsarPoint(double current, double previous) {
    }

    private record MacdSeries(double[] signal, double[] histogram) {
    }

    private record StochRsiSeries(double[] k, double[] d) {
    }
}
