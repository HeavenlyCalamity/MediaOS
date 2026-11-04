package com.mediaos.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTest {
    private static final Pattern REF = Pattern.compile("#/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+");

    @Test
    @SuppressWarnings("unchecked")
    void parsesOpenApiAndResolvesEveryLocalReference() throws Exception {
        Path path = Path.of("..", "..", "packages", "contracts", "openapi.yaml");
        assertTrue(Files.isRegularFile(path), "OpenAPI contract must exist at " + path.toAbsolutePath());
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(path)) {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertNotNull(paths.get("/api/v1/content-items/{id}/production-jobs"));
        assertNotNull(paths.get("/api/v1/production-jobs/{id}"));
        assertNotNull(paths.get("/api/v1/providers"));
        String serialized = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
        Matcher matcher = REF.matcher(serialized);
        while (matcher.find()) {
            Object target = document;
            for (String segment : matcher.group().substring(2).split("/")) {
                assertTrue(target instanceof Map<?, ?>, "unresolvable OpenAPI reference " + matcher.group());
                target = ((Map<String, Object>) target).get(segment);
            }
            assertNotNull(target, "unresolvable OpenAPI reference " + matcher.group());
        }
    }
}
