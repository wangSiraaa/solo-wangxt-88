package com.example.scheduler.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed cron expression, evaluated against the wall clock of a named time zone.
 *
 * <p>Supported subset (documented, everything else is rejected with a clear error):
 * <ul>
 *   <li>6 fields: {@code second minute hour day-of-month month day-of-week}. A 5-field
 *       expression is accepted and means "at second 0".</li>
 *   <li>Field syntax: {@code *}, lists {@code a,b,c}, ranges {@code a-b}, steps
 *       {@code *&#47;n}, {@code a-b/n}, {@code a/n} (meaning a..max/n).</li>
 *   <li>Month names JAN..DEC and day-of-week names MON..SUN, case-insensitive.</li>
 *   <li>Day-of-week numbering follows Unix cron: 0 and 7 are Sunday, 1 is Monday.</li>
 *   <li>{@code ?} in day-of-month / day-of-week means "no restriction".</li>
 *   <li>{@code L} alone in day-of-month means the last day of the month.</li>
 *   <li>If both day-of-month and day-of-week are restricted they are OR-ed
 *       (POSIX cron semantics).</li>
 * </ul>
 *
 * <p>Expressions that would fire more than {@link #MAX_FIRE_TIMES_PER_DAY} times per day are
 * rejected: high-frequency triggers belong to {@code FIXED_INTERVAL} schedules.
 */
public final class CronSpec {

    public static final int MAX_FIRE_TIMES_PER_DAY = 10_000;

    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

    private static final Map<String, Integer> DOW_NAMES = Map.of(
            "MON", 1, "TUE", 2, "WED", 3, "THU", 4, "FRI", 5, "SAT", 6, "SUN", 7);

    private final String source;
    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet dayOfMonth;      // bits 1..31
    private final boolean lastDayOfMonth;
    private final BitSet months;          // bits 1..12
    private final EnumSet<DayOfWeek> daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final int[] secondsOfDay;     // sorted, distinct

    private CronSpec(String source, BitSet seconds, BitSet minutes, BitSet hours,
                     BitSet dayOfMonth, boolean lastDayOfMonth, BitSet months,
                     EnumSet<DayOfWeek> daysOfWeek, boolean domRestricted, boolean dowRestricted) {
        this.source = source;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.dayOfMonth = dayOfMonth;
        this.lastDayOfMonth = lastDayOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
        List<Integer> combos = new ArrayList<>();
        for (int h = hours.nextSetBit(0); h >= 0; h = hours.nextSetBit(h + 1)) {
            for (int m = minutes.nextSetBit(0); m >= 0; m = minutes.nextSetBit(m + 1)) {
                for (int s = seconds.nextSetBit(0); s >= 0; s = seconds.nextSetBit(s + 1)) {
                    combos.add(h * 3600 + m * 60 + s);
                }
            }
        }
        if (combos.size() > MAX_FIRE_TIMES_PER_DAY) {
            throw new IllegalArgumentException("cron expression '" + source + "' fires " + combos.size()
                    + " times per day (limit " + MAX_FIRE_TIMES_PER_DAY
                    + "); use a FIXED_INTERVAL schedule for high-frequency triggers");
        }
        this.secondsOfDay = combos.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    public static CronSpec parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression must not be blank");
        }
        String[] f = expression.trim().split("\\s+");
        if (f.length == 5) {
            String[] g = new String[6];
            g[0] = "0";
            System.arraycopy(f, 0, g, 1, 5);
            f = g;
        }
        if (f.length != 6) {
            throw new IllegalArgumentException(
                    "cron expression must have 5 or 6 fields, got " + f.length + ": '" + expression + "'");
        }

        BitSet seconds = parseField(f[0], 0, 59, null, false, "second");
        BitSet minutes = parseField(f[1], 0, 59, null, false, "minute");
        BitSet hours = parseField(f[2], 0, 23, null, false, "hour");

        boolean lastDayOfMonth = false;
        BitSet dayOfMonth;
        boolean domRestricted;
        if (f[3].equals("L")) {
            lastDayOfMonth = true;
            dayOfMonth = new BitSet(32);
            domRestricted = true;
        } else {
            dayOfMonth = parseField(f[3], 1, 31, null, true, "day-of-month");
            domRestricted = isRestricted(f[3]);
        }

        BitSet months = parseField(f[4], 1, 12, MONTH_NAMES, false, "month");

        BitSet dowBits = parseField(f[5], 0, 7, DOW_NAMES, true, "day-of-week");
        boolean dowRestricted = isRestricted(f[5]);
        EnumSet<DayOfWeek> daysOfWeek = EnumSet.noneOf(DayOfWeek.class);
        for (int i = dowBits.nextSetBit(0); i >= 0; i = dowBits.nextSetBit(i + 1)) {
            daysOfWeek.add(i == 0 || i == 7 ? DayOfWeek.SUNDAY : DayOfWeek.of(i));
        }

        return new CronSpec(expression.trim(), seconds, minutes, hours, dayOfMonth, lastDayOfMonth,
                months, daysOfWeek, domRestricted, dowRestricted);
    }

    private static boolean isRestricted(String field) {
        return !(field.equals("*") || field.equals("?"));
    }

    private static BitSet parseField(String field, int min, int max, Map<String, Integer> names,
                                     boolean allowQuestion, String label) {
        BitSet bits = new BitSet(max + 1);
        for (String token : field.split(",")) {
            if (token.isEmpty()) {
                throw new IllegalArgumentException("empty token in " + label + " field of '" + field + "'");
            }
            String base = token;
            int step = 1;
            int slash = token.indexOf('/');
            if (slash >= 0) {
                base = token.substring(0, slash);
                String stepText = token.substring(slash + 1);
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid step '" + stepText + "' in " + label + " field");
                }
                if (step < 1) {
                    throw new IllegalArgumentException("step must be >= 1 in " + label + " field");
                }
                if (base.isEmpty()) {
                    throw new IllegalArgumentException("missing step base in " + label + " field: '" + token + "'");
                }
            }
            final int lo;
            final int hi;
            if (base.equals("*") || (allowQuestion && base.equals("?"))) {
                lo = min;
                hi = max;
            } else if (base.contains("-")) {
                String[] ab = base.split("-", 2);
                lo = valueOf(ab[0], names, label);
                hi = valueOf(ab[1], names, label);
                if (lo > hi) {
                    throw new IllegalArgumentException("range start > end in " + label + " field: '" + base + "'");
                }
            } else {
                lo = valueOf(base, names, label);
                hi = slash >= 0 ? max : lo;
            }
            if (lo < min || hi > max) {
                throw new IllegalArgumentException(
                        label + " value out of range [" + min + ".." + max + "]: '" + base + "'");
            }
            for (int i = lo; i <= hi; i += step) {
                bits.set(i);
            }
        }
        if (bits.isEmpty()) {
            throw new IllegalArgumentException(label + " field selects nothing: '" + field + "'");
        }
        return bits;
    }

    private static int valueOf(String token, Map<String, Integer> names, String label) {
        if (names != null) {
            Integer v = names.get(token.toUpperCase(Locale.ROOT));
            if (v != null) {
                return v;
            }
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid value '" + token + "' in " + label + " field");
        }
    }

    /** Does this calendar day carry occurrences? */
    public boolean matchesDay(LocalDate date) {
        if (!months.get(date.getMonthValue())) {
            return false;
        }
        boolean domMatch = lastDayOfMonth
                ? date.getDayOfMonth() == date.lengthOfMonth()
                : dayOfMonth.get(date.getDayOfMonth());
        boolean dowMatch = daysOfWeek.contains(date.getDayOfWeek());
        if (domRestricted && dowRestricted) {
            // POSIX cron: restricted day-of-month OR restricted day-of-week.
            return domMatch || dowMatch;
        }
        return domMatch && dowMatch;
    }

    /** Sorted distinct seconds-of-day at which the expression fires on a matching day. */
    public int[] secondsOfDay() {
        return secondsOfDay;
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }
}
