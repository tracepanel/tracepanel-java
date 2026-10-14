package dev.tracepanel.core;

import java.security.SecureRandom;

/** Trace and span ids as every SDK makes them: ULIDs, 48 bits of milliseconds then 80 random bits, in Crockford's base32. */
public final class Ulid {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String next() {
        return of(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextInt(1 << 16));
    }

    static String of(long millis, long randomHigh, int randomLow) {
        char[] out = new char[26];
        long time = millis;
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits: 64 then 16, read 5 at a time from the end.
        long low = randomHigh;
        int extra = randomLow;
        for (int i = 25; i >= 10; i--) {
            out[i] = CROCKFORD[(int) (low & 31)];
            low = (low >>> 5) | ((long) (extra & 31) << 59);
            extra >>>= 5;
        }
        return new String(out);
    }
}
