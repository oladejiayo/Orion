package com.orion.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts sensitive fields from log data maps to prevent credential/token leakage.
 * <p>
 * Default sensitive patterns: password, token, accessToken, refreshToken, secret,
 * authorization, apiKey, credential. Custom patterns can be added.
 * All matching is case-insensitive.
 */
public final class SensitiveDataRedactor {

    /** The replacement string for redacted values. */
    public static final String REDACTED = "[REDACTED]";

    /** Default set of field name patterns considered sensitive. */
    private static final Set<String> DEFAULT_SENSITIVE_PATTERNS = Set.of(
            "password", "token", "accesstoken", "refreshtoken",
            "secret", "authorization", "apikey", "credential"
    );

    private final Set<String> sensitivePatterns;
    private final Pattern compiledPattern;

    /**
     * Creates a redactor with the default sensitive field patterns.
     */
    public SensitiveDataRedactor() {
        this(DEFAULT_SENSITIVE_PATTERNS);
    }

    /**
     * Creates a redactor with custom sensitive field patterns (case-insensitive).
     *
     * @param patterns field name patterns to treat as sensitive
     */
    public SensitiveDataRedactor(Set<String> patterns) {
        this.sensitivePatterns = Set.copyOf(patterns);
        // Build a single regex pattern for efficient matching
        String regex = String.join("|", sensitivePatterns.stream()
                .map(Pattern::quote)
                .toList());
        this.compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * Returns a new map with sensitive field values replaced by {@value #REDACTED}.
     * Non-sensitive fields are copied as-is. Null input returns an empty map.
     *
     * @param data the log data map (keys are field names, values are arbitrary)
     * @return a new map with sensitive values redacted
     */
    public Map<String, Object> redact(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>(data.size());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (isSensitive(key)) {
                result.put(key, REDACTED);
            } else {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    /**
     * Checks whether a field name matches any sensitive pattern (case-insensitive).
     *
     * @param fieldName the field name to check
     * @return true if the field name contains a sensitive pattern
     */
    public boolean isSensitive(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return compiledPattern.matcher(fieldName).find();
    }

    /**
     * Returns the set of sensitive patterns this redactor uses.
     */
    public Set<String> sensitivePatterns() {
        return sensitivePatterns;
    }
}
