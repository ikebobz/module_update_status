package org.facility.updater;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic-ish version comparison: "1.4.10" > "1.4.9", "2.0.0" > "2.0.0-RC1",
 * leading "v" ignored, missing parts treated as 0 ("1.4" == "1.4.0").
 */
public final class Version implements Comparable<Version> {

    private final String raw;
    private final List<Long> core = new ArrayList<Long>();
    private final List<String> qualifier = new ArrayList<String>();

    public Version(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Empty version");
        this.raw = raw.trim();
        String v = this.raw;
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int plus = v.indexOf('+');           // build metadata is ignored
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        String main = dash >= 0 ? v.substring(0, dash) : v;
        String qual = dash >= 0 ? v.substring(dash + 1) : "";
        for (String p : main.split("\\.")) {
            core.add(parseLong(p));
        }
        if (!qual.isEmpty()) {
            for (String q : qual.split("[.\\-_]")) qualifier.add(q.toLowerCase());
        }
    }

    private static long parseLong(String p) {
        StringBuilder digits = new StringBuilder();
        for (char c : p.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c); else break;
        }
        return digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
    }

    public static Version of(String s) { return new Version(s); }

    public boolean isNewerThan(Version o) { return compareTo(o) > 0; }

    @Override
    public int compareTo(Version o) {
        int n = Math.max(core.size(), o.core.size());
        for (int k = 0; k < n; k++) {
            long a = k < core.size() ? core.get(k) : 0;
            long b = k < o.core.size() ? o.core.get(k) : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        // A release (no qualifier) is newer than any pre-release of the same number.
        if (qualifier.isEmpty() && !o.qualifier.isEmpty()) return 1;
        if (!qualifier.isEmpty() && o.qualifier.isEmpty()) return -1;
        int m = Math.max(qualifier.size(), o.qualifier.size());
        for (int k = 0; k < m; k++) {
            if (k >= qualifier.size()) return -1;
            if (k >= o.qualifier.size()) return 1;
            String a = qualifier.get(k), b = o.qualifier.get(k);
            boolean an = a.matches("\\d+"), bn = b.matches("\\d+");
            int c;
            if (an && bn) c = Long.compare(Long.parseLong(a), Long.parseLong(b));
            else if (an) c = -1;
            else if (bn) c = 1;
            else c = a.compareTo(b);
            if (c != 0) return c;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) { return o instanceof Version && compareTo((Version) o) == 0; }

    @Override
    public int hashCode() { return core.hashCode(); }

    @Override
    public String toString() { return raw; }
}
