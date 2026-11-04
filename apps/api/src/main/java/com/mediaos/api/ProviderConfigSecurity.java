package com.mediaos.api;

import java.util.Map;
import java.util.regex.Pattern;

/** Prevents profile/provider configuration stored in API responses or job inputs from carrying credential fields. */
final class ProviderConfigSecurity {
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(api[_-]?key|secret|password|credential|access[_-]?token|refresh[_-]?token|authorization).*");
    private static final Pattern KNOWN_KEY_VALUE = Pattern.compile("(?i).*(?:sk-[a-z0-9_-]{16,}|bearer\\s+[a-z0-9._~-]{16,}).*");
    private ProviderConfigSecurity() {}

    static void rejectSecretFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SECRET_FIELD.matcher(key).matches())
                    throw new IllegalArgumentException("provider configuration cannot store credential or secret fields; use local environment variables");
                rejectSecretFields(entry.getValue());
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) rejectSecretFields(item);
        } else if (value instanceof String text && KNOWN_KEY_VALUE.matcher(text).matches()) {
            throw new IllegalArgumentException("provider configuration cannot contain credential values; use local environment variables");
        }
    }
}
