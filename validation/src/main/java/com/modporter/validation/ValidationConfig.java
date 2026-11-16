package com.modporter.validation;

import java.nio.file.Path;
import java.util.Locale;

record ValidationConfig(
    String lane,
    String sessionId,
    String expectedModId,
    String expectedJarSha256,
    Path expectedJarPath,
    String expectedContentManifestSha256,
    Path expectedContentManifestPath,
    Path runtimeDirectory,
    Path receiptPath,
    String worldName,
    int maxClientTicks
) {
    static ValidationConfig load(String expectedLane) {
        String lane = requiredProperty("exactJarValidation.lane");
        if (!expectedLane.equals(lane)) {
            throw new IllegalStateException(
                "Expected validation lane " + expectedLane + " but got " + lane);
        }

        String sessionId = System.getenv("MINECRAFT_MOD_TEST_SESSION_ID");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                "MINECRAFT_MOD_TEST_SESSION_ID is required");
        }

        String jarSha256 = requiredSha256(
            "exactJarValidation.expectedJarSha256");
        String manifestSha256 = requiredSha256(
            "exactJarValidation.expectedContentManifestSha256");

        int maxTicks = Integer.parseInt(
            System.getProperty("exactJarValidation.maxClientTicks", "2400"));
        if (maxTicks < 200) {
            throw new IllegalStateException(
                "exactJarValidation.maxClientTicks must be at least 200");
        }

        return new ValidationConfig(
            lane,
            sessionId,
            requiredProperty("exactJarValidation.expectedModId"),
            jarSha256,
            absolutePath("exactJarValidation.expectedJarPath"),
            manifestSha256,
            absolutePath(
                "exactJarValidation.expectedContentManifestPath"),
            absolutePath("exactJarValidation.runtimeDirectory"),
            absolutePath("exactJarValidation.receiptPath"),
            System.getProperty(
                "exactJarValidation.worldName", "exact_jar_validation"),
            maxTicks
        );
    }

    Path creativeTracePath() {
        return evidenceDirectory().resolve(
            "client-creative-render-trace.json");
    }

    Path screenshotDirectory() {
        return evidenceDirectory().resolve("screenshots");
    }

    Path expectedContentManifestEvidencePath() {
        return evidenceDirectory().resolve(
            "expected-content-manifest.json");
    }

    Path clientWorldEvidencePath() {
        return evidenceDirectory().resolve("client-world-level.dat");
    }

    Path serverWorldEvidencePath() {
        return evidenceDirectory().resolve("server-world-level.dat");
    }

    Path clientWorldLevelDat() {
        return runtimeDirectory.resolve("saves").resolve(worldName)
            .resolve("level.dat");
    }

    Path serverWorldLevelDat() {
        return runtimeDirectory.resolve(
            "exact_jar_validation_server/level.dat");
    }

    private Path evidenceDirectory() {
        Path parent = receiptPath.getParent();
        if (parent == null) {
            throw new IllegalStateException(
                "Harness receipt must have an evidence parent: "
                    + receiptPath);
        }
        return parent;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required system property is blank: " + name);
        }
        return value.trim();
    }

    private static String requiredSha256(String name) {
        String sha256 = requiredProperty(name).toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                "Required SHA-256 is invalid: " + name + "=" + sha256);
        }
        return sha256;
    }

    private static Path absolutePath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!path.isAbsolute()) {
            throw new IllegalStateException(
                "Required path must be absolute: " + name + "=" + path);
        }
        return path.normalize();
    }
}
