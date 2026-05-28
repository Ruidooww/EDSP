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
    private static final List<String> TRANSFORM_ENGINE_REFERENCES = List.of(
        "com.edsp.transform.standardevent",
        "StandardEventTransformService"
    );
    private static final List<String> TRANSFORM_SERVICE_REFERENCES = List.of(
        "com.edsp.transformservice",
        "com.edsp.transform.service"
    );
    private static final List<String> ALLOWED_ENGINE_BRIDGES = List.of(
        "config/TransformConfig.java",
        "config/TransformRuntimeConfig.java",
        "transform/runtime/LocalTransformRuntimeClient.java",
        "transform/runtime/TransformContractSupport.java"
    );

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
            "Only explicit transform engine bridge files may depend on edsp-transform engine: " + violations
        );
    }

    @Test
    void coreMainCodeDoesNotDependOnTransformServiceModule() throws IOException {
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(CORE_MAIN)) {
            paths
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> inspectForTransformServiceModule(path, violations));
        }

        assertTrue(
            violations.isEmpty(),
            "edsp-core main code must not reference transform-service Java packages: " + violations
        );
    }

    @Test
    void corePomDoesNotDependOnTransformServiceModule() throws IOException {
        var pom = locateCorePom();
        var content = Files.readString(pom);

        assertTrue(
            !content.contains("<artifactId>edsp-transform-service</artifactId>"),
            "edsp-core must not depend on the edsp-transform-service Maven module"
        );
    }

    @Test
    void shadowPrecheckServiceDoesNotDependOnTransformRuntimeClient() throws IOException {
        var precheckService = CORE_MAIN.resolve("service/IngestionPlanPrecheckService.java");
        var content = Files.readString(precheckService);

        assertTrue(
            !content.contains("TransformRuntimeClient"),
            "Shadow Precheck must remain dry-run schema validation and must not inject TransformRuntimeClient"
        );
        assertTrue(
            !content.contains(".transform("),
            "Shadow Precheck must not call a real transform runtime"
        );
    }

    private static boolean isAllowedEngineBridge(Path path) {
        var relative = CORE_MAIN.relativize(path).toString().replace('\\', '/');
        return ALLOWED_ENGINE_BRIDGES.contains(relative);
    }

    private static void inspect(Path path, List<String> violations) {
        try {
            var content = Files.readString(path);
            if (containsAny(content, TRANSFORM_ENGINE_REFERENCES)) {
                violations.add(CORE_MAIN.relativize(path).toString());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect " + path, ex);
        }
    }

    private static void inspectForTransformServiceModule(Path path, List<String> violations) {
        try {
            var content = Files.readString(path);
            if (containsAny(content, TRANSFORM_SERVICE_REFERENCES)) {
                violations.add(CORE_MAIN.relativize(path).toString());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect " + path, ex);
        }
    }

    private static boolean containsAny(String content, List<String> references) {
        return references.stream().anyMatch(content::contains);
    }

    private static Path locateCoreMain() {
        var modulePath = Path.of("src/main/java/com/edsp/core");
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        return Path.of("edsp-core/src/main/java/com/edsp/core");
    }

    private static Path locateCorePom() {
        for (var candidate : List.of(
            Path.of("pom.xml"),
            Path.of("edsp-core/pom.xml"),
            Path.of("backend/edsp-core/pom.xml")
        )) {
            if (Files.exists(candidate) && Files.exists(candidate.getParent() == null
                ? Path.of("src/main/java/com/edsp/core")
                : candidate.getParent().resolve("src/main/java/com/edsp/core"))) {
                return candidate;
            }
        }
        return Path.of("edsp-core/pom.xml");
    }
}
