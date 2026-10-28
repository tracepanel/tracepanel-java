package dev.tracepanel.core;

import java.math.BigInteger;
import java.util.Locale;

/**
 * W3C trace context (traceparent), as every SDK writes it: a call out carries 00-(trace id)-(span id)-01, the trace
 * id being the one the request came in with, else this trace's own ULID's 128 bits in hex, and the span id the last
 * 64 bits of the call's span's ULID. The app it reaches links its trace to the call by them.
 */
public final class W3c {
    public static final String HEADER = "traceparent";
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private W3c() {}

    // version 00 (and any later one, read as 00): trace id, parent span id, flags; all zeros is invalid.
    private static final java.util.regex.Pattern TRACEPARENT =
            java.util.regex.Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    /** A received traceparent as its trace's attributes, the W3C trace and the caller's span; none if invalid. */
    public static java.util.Map<String, Object> incoming(String header) {
        java.util.regex.Matcher match = TRACEPARENT.matcher(header == null ? "" : header.trim().toLowerCase(Locale.ROOT));
        if (!match.find() || match.group(1).equals("ff") || match.group(2).equals("0".repeat(32))
                || match.group(3).equals("0".repeat(16))) {
            return java.util.Map.of();
        }
        return java.util.Map.of("w3c.trace_id", match.group(2), "w3c.parent_span_id", match.group(3));
    }

    /** The traceparent a call made in `trace` carries, whose span is `spanId`. */
    public static String outgoing(Trace trace, String spanId) {
        return "00-" + traceId(trace) + "-" + spanId(spanId) + "-01";
    }

    /** The W3C trace this trace is part of: the one it was called in, else its own id, kept on it from now on. */
    public static String traceId(Trace trace) {
        Object known = trace.attr("w3c.trace_id");
        if (known instanceof String id) return id;
        String own = traceHex(trace.id());
        trace.attr("w3c.trace_id", own);
        return own;
    }

    /** A trace's W3C id from its ULID: all 128 bits, in hex. */
    static String traceHex(String ulid) {
        return hex(bits(ulid), 32);
    }

    /** A span's W3C id: the last 64 bits of its ULID, in hex. */
    public static String spanId(String ulid) {
        return hex(bits(ulid).and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)), 16);
    }

    private static BigInteger bits(String ulid) {
        BigInteger value = BigInteger.ZERO;
        for (char c : ulid.toCharArray()) value = value.shiftLeft(5).or(BigInteger.valueOf(CROCKFORD.indexOf(c)));
        return value;
    }

    private static String hex(BigInteger value, int width) {
        String digits = value.toString(16).toLowerCase(Locale.ROOT);
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }
}
