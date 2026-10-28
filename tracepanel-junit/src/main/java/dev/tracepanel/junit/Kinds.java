package dev.tracepanel.junit;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** What the agent finds (its schema/trace.schema.json): the kinds a test can fail on, as every SDK names them. */
final class Kinds {
    static final List<String> KINDS = List.of(
            "n+1",
            "duplicates",
            "slow-sql",
            "slow-http",
            "slow-call",
            "error",
            "long-transaction",
            "in-transaction",
            "htmx",
            "queue-wait",
            "blocking-call",
            "large-result");
    /** What fails a test unless the settings say otherwise. */
    static final List<String> DEFAULT_FAIL_ON = List.of("n+1", "duplicates");

    private Kinds() {}

    /** The kinds named; an unknown kind is a typo, and an error. */
    static Set<String> of(String[] names, String where) {
        Set<String> unknown = new TreeSet<>();
        for (String name : names) if (!KINDS.contains(name)) unknown.add(name);
        if (!unknown.isEmpty()) {
            throw new ExtensionConfigurationException(
                    where + ": unknown kind " + String.join(", ", unknown) + " (the kinds: " + String.join(", ", KINDS) + ")");
        }
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    /** The kinds a setting names, apart by commas or spaces: "n+1, duplicates". */
    static Set<String> parse(String value, String where) {
        String trimmed = value.trim();
        return of(trimmed.isEmpty() ? new String[0] : trimmed.split("[\\s,]+"), where);
    }
}
