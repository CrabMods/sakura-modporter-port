package com.modporter.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ReceiptSupport {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private ReceiptSupport() {
    }

    static Map<String, Object> artifact(Path path) throws IOException {
        Path realPath = path.toRealPath();
        if (!Files.isRegularFile(realPath)) {
            throw new IllegalStateException(
                "Evidence artifact is not a regular file: " + realPath);
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("path", realPath.toString());
        artifact.put("sha256", Hashing.sha256(realPath));
        artifact.put("bytes", Files.size(realPath));
        return artifact;
    }

    static void verifyArtifact(Map<String, Object> artifact)
            throws IOException {
        Path path = Path.of((String) artifact.get("path"));
        Map<String, Object> observed = artifact(path);
        String expectedSha256 = (String) artifact.get("sha256");
        long expectedBytes = ((Number) artifact.get("bytes")).longValue();
        if (!expectedSha256.equals(observed.get("sha256")) ||
                expectedBytes != ((Number) observed.get("bytes"))
                    .longValue()) {
            throw new IllegalStateException(
                "Evidence artifact changed after capture: " + path);
        }
    }

    static Map<String, Object> copyImmutableArtifact(
        Path source,
        Path destination
    ) throws IOException {
        Map<String, Object> sourceArtifact = artifact(source);
        Path absolute = destination.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        if (Files.exists(absolute)) {
            throw new IllegalStateException(
                "Immutable evidence already exists: " + absolute);
        }
        Path temporary = absolute.resolveSibling(
            absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(source, temporary);
            Map<String, Object> temporaryArtifact =
                artifact(temporary);
            if (!sourceArtifact.get("sha256").equals(
                    temporaryArtifact.get("sha256")) ||
                    !sourceArtifact.get("bytes").equals(
                        temporaryArtifact.get("bytes"))) {
                throw new IllegalStateException(
                    "Immutable evidence copy mismatch: " + source
                        + " -> " + destination);
            }
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return artifact(absolute);
    }

    static void writeImmutableJson(Path path, Object value)
            throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        if (Files.exists(absolute)) {
            throw new IllegalStateException(
                "Immutable evidence already exists: " + absolute);
        }

        Path temporary = absolute.resolveSibling(
            absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        String json = GSON.toJson(value) + "\n";
        Files.writeString(
            temporary,
            json,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        try {
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String now() {
        return Instant.now().toString();
    }
}

