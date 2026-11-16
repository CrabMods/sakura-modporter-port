package com.modporter.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

final class ExactJarVerifier {
    private ExactJarVerifier() {
    }

    static Evidence verify(ValidationConfig config) throws IOException {
        Path expectedJar = config.expectedJarPath().toRealPath();
        if (!Files.isRegularFile(expectedJar) ||
                !expectedJar.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".jar")) {
            throw new IllegalStateException(
                "Expected target is not a regular JAR: " + expectedJar);
        }

        Path modsDirectory = config.runtimeDirectory().resolve("mods")
            .toRealPath();
        List<Path> installedJars;
        try (Stream<Path> files = Files.list(modsDirectory)) {
            installedJars = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar"))
                .map(path -> {
                    try {
                        return path.toRealPath();
                    } catch (IOException exception) {
                        throw new UncheckedPathException(exception);
                    }
                })
                .toList();
        } catch (UncheckedPathException exception) {
            throw exception.cause;
        }
        if (installedJars.size() != 1 ||
                !installedJars.getFirst().equals(expectedJar)) {
            throw new IllegalStateException(
                "Runtime mods directory must contain only the expected target JAR; found "
                    + installedJars);
        }

        IModFileInfo fileInfo = ModList.get().getModFileById(
            config.expectedModId());
        if (fileInfo == null) {
            throw new IllegalStateException(
                "Target mod is not loaded: " + config.expectedModId());
        }
        Path loadedPath = fileInfo.getFile().getFilePath().toRealPath();
        if (!Files.isRegularFile(loadedPath) || !loadedPath.equals(expectedJar)) {
            throw new IllegalStateException(
                "Target ModList container did not originate from the staged JAR; expected "
                    + expectedJar + ", loaded " + loadedPath);
        }

        String observedSha256 = Hashing.sha256(loadedPath);
        if (!observedSha256.equals(config.expectedJarSha256())) {
            throw new IllegalStateException(
                "Loaded target JAR SHA-256 mismatch; expected "
                    + config.expectedJarSha256() + ", observed "
                    + observedSha256);
        }

        return new Evidence(
            config.expectedModId(),
            expectedJar.toString(),
            loadedPath.toString(),
            observedSha256,
            Files.size(loadedPath),
            installedJars.size(),
            runtimeModJarInventory()
        );
    }

    private static List<Map<String, Object>> runtimeModJarInventory() {
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (var mod : ModList.get().getMods()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("modId", mod.getModId());
            entry.put("version", mod.getVersion().toString());
            entry.put("regularJar", false);
            try {
                Path path = mod.getOwningFile().getFile().getFilePath()
                    .toRealPath();
                entry.put("path", path.toString());
                if (Files.isRegularFile(path) && path.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    entry.put("sha256", Hashing.sha256(path));
                    entry.put("bytes", Files.size(path));
                    entry.put("regularJar", true);
                }
            } catch (IOException | RuntimeException exception) {
                entry.put("artifactUnavailable", exception.getClass().getSimpleName());
            }
            inventory.add(entry);
        }
        inventory.sort((left, right) -> ((String) left.get("modId"))
            .compareTo((String) right.get("modId")));
        return List.copyOf(inventory);
    }

    record Evidence(
        String modId,
        String expectedJarPath,
        String loadedJarPath,
        String jarSha256,
        long jarBytes,
        int modsJarCount,
        List<Map<String, Object>> runtimeModJarInventory
    ) {
        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("modId", modId);
            value.put("expectedJarPath", expectedJarPath);
            value.put("loadedJarPath", loadedJarPath);
            value.put("jarSha256", jarSha256);
            value.put("jarBytes", jarBytes);
            value.put("modsJarCount", modsJarCount);
            value.put("originIsExpectedRegularJar", true);
            value.put("runtimeModJarInventory", runtimeModJarInventory);
            return value;
        }
    }

    private static final class UncheckedPathException
            extends RuntimeException {
        private final IOException cause;

        private UncheckedPathException(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}

