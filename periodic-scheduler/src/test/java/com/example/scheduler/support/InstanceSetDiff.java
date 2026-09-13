package com.example.scheduler.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares an expected instance set with the actual one and renders a full report —
 * every expected and actual entry plus the differences in both directions. Used by the
 * future-instance-set verification tests: they never check a single "next run", they
 * compare whole sets over long windows.
 */
public final class InstanceSetDiff {

    private InstanceSetDiff() {
    }

    /** One comparable instance entry (status, UTC time, local time, offset, skip reason). */
    public record Entry(String status, String scheduledAtUtc, String localTime, String offset, String skipReason) {

        public String canonical() {
            return pad(status, 8) + " | " + pad(scheduledAtUtc, 20) + " | " + pad(localTime, 16)
                    + " | " + pad(offset, 6) + " | " + skipReason;
        }
    }

    public static Entry planned(String utc, String localTime, String offset) {
        return new Entry("PLANNED", normInstant(utc), normLocal(localTime), offset, "-");
    }

    public static Entry skippedDstGap(String localTime) {
        return new Entry("SKIPPED", "-", normLocal(localTime), "-", "DST_GAP");
    }

    public static Entry paused(String utc, String localTime, String offset) {
        return new Entry("SKIPPED", normInstant(utc), normLocal(localTime), offset, "PAUSED");
    }

    public static Entry ofJson(JsonNode n) {
        return new Entry(
                n.get("status").asText(),
                n.get("scheduledAtUtc").isNull() ? "-" : Instant.parse(n.get("scheduledAtUtc").asText()).toString(),
                LocalDateTime.parse(n.get("localTime").asText()).toString(),
                n.get("offset").isNull() ? "-" : n.get("offset").asText(),
                n.get("skipReason").isNull() ? "-" : n.get("skipReason").asText());
    }

    /** Asserts multiset equality; always prints the full expected-vs-actual report first. */
    public static void assertMatch(String scenario, String window, List<Entry> expected, List<Entry> actual) {
        List<String> expectedOnly = diff(expected, actual);
        List<String> actualOnly = diff(actual, expected);
        String report = report(scenario, window, expected, actual, expectedOnly, actualOnly);
        System.out.println(report);
        if (!expectedOnly.isEmpty() || !actualOnly.isEmpty()) {
            throw new AssertionError("instance set mismatch in scenario '" + scenario + "'\n" + report);
        }
    }

    private static List<String> diff(List<Entry> a, List<Entry> b) {
        Map<String, Integer> available = new HashMap<>();
        for (Entry e : b) {
            available.merge(e.canonical(), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (Entry e : a) {
            String key = e.canonical();
            if (available.getOrDefault(key, 0) > 0) {
                available.merge(key, -1, Integer::sum);
            } else {
                out.add(key);
            }
        }
        return out;
    }

    private static String report(String scenario, String window, List<Entry> expected, List<Entry> actual,
                                 List<String> expectedOnly, List<String> actualOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== SCENARIO: ").append(scenario).append(" =====\n");
        sb.append("window: ").append(window).append('\n');
        sb.append("legend: STATUS | scheduledAtUtc | localTime(original zone) | offset | skipReason\n");
        sb.append("expected: ").append(expected.size()).append(" entries, actual: ").append(actual.size()).append(" entries\n");
        sb.append("--- expected ---\n");
        expected.forEach(e -> sb.append("  ").append(e.canonical()).append('\n'));
        sb.append("--- actual ---\n");
        actual.forEach(e -> sb.append("  ").append(e.canonical()).append('\n'));
        sb.append("--- differences (expected vs actual) ---\n");
        if (expectedOnly.isEmpty() && actualOnly.isEmpty()) {
            sb.append("  (none)\n");
        }
        expectedOnly.forEach(d -> sb.append("  EXPECTED-ONLY: ").append(d).append('\n'));
        actualOnly.forEach(d -> sb.append("  ACTUAL-ONLY:   ").append(d).append('\n'));
        sb.append("=====\n");
        return sb.toString();
    }

    private static String normInstant(String iso) {
        return Instant.parse(iso).toString();
    }

    private static String normLocal(String local) {
        return LocalDateTime.parse(local).toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
