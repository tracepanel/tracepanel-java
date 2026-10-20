package dev.tracepanel.integrations;

import dev.tracepanel.core.Secrets;
import java.util.Locale;
import java.util.Map;

/** A log record's own fields (the MDC), as the other SDKs record a record's extra fields. */
final class LogFields {
    private LogFields() {}

    /** Each field as extra.<name>, named as the Python SDK names the fields of `extra=`, a secret's value masked. */
    static void put(Map<String, Object> attrs, Map<String, String> fields) {
        if (fields == null) return;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            attrs.put("extra." + (name.isEmpty() ? "value" : name),
                    Secrets.secret(field.getKey()) ? Secrets.MASK : field.getValue());
        }
    }
}
