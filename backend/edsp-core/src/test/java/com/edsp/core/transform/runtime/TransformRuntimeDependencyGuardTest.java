package com.edsp.core.transform.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformRuntimeDependencyGuardTest {
    private static final Path CORE_MAIN = locateCoreMain();

    @Test
    void coreBusinessEntrypointsDoNotDependOnStandardEventTransformEngine() throws IOException {
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(CORE_MAIN)) {
            paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !isAllowedEngineBridge(path))
                .forEach(path -> inspect(path, violations));
        }

        assertTrue(
            violations.isEmpty(),
            "Only TransformRuntimeConfig and transform/runtime may depend on edsp-transform engine: " + violations
        );
    }

    private static boolean isAllowedEngineBridge(Path path) {
        var relative = CORE_MAIN.relativize(path).toString().replace('\\', '/');
        return relative.equals("config/TransformConfig.java")
            || relative.equals("config/TransformRuntimeConfig.java")
            || relative.startsWith("transform/runtime/");
    }

    private static void inspect(Path path, List<String> violations) {
        try {
            var content = Files.readString(path);
            if (content.contains("com.edsp.transform.standardevent")
                || content.contains("StandardEventTransformService")) {
                violations.add(CORE_MAIN.relativize(path).toString());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect " + path, ex);
        }
    }

    private static Path locateCoreMain() {
        var modulePath = Path.of("src/main/java/com/edsp/core");
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        return Path.of("edsp-core/src/main/java/com/edsp/core");
    }
}
