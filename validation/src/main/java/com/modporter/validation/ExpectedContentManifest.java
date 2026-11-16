package com.modporter.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

final class ExpectedContentManifest {
    private final Path path;
    private final String sha256;
    private final String targetModId;
    private final String sourceRepository;
    private final String sourceCommit;
    private final String sourceDerivation;
    private final List<Map<String, String>> sourceFiles;
    private final List<ResourceLocation> expectedItemIds;
    private final Map<ResourceLocation, Map<String, String>>
        iconEvidenceExceptions;

    private ExpectedContentManifest(
        Path path,
        String sha256,
        String targetModId,
        String sourceRepository,
        String sourceCommit,
        String sourceDerivation,
        List<Map<String, String>> sourceFiles,
        List<ResourceLocation> expectedItemIds,
        Map<ResourceLocation, Map<String, String>> iconEvidenceExceptions
    ) {
        this.path = path;
        this.sha256 = sha256;
        this.targetModId = targetModId;
        this.sourceRepository = sourceRepository;
        this.sourceCommit = sourceCommit;
        this.sourceDerivation = sourceDerivation;
        this.sourceFiles = List.copyOf(sourceFiles);
        this.expectedItemIds = List.copyOf(expectedItemIds);
        this.iconEvidenceExceptions = Collections.unmodifiableMap(
            new LinkedHashMap<>(iconEvidenceExceptions));
    }

    static ExpectedContentManifest load(ValidationConfig config)
            throws IOException {
        Path path = config.expectedContentManifestPath().toRealPath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                "Expected-content manifest is not a regular file: " + path);
        }
        String sha256 = Hashing.sha256(path);
        if (!sha256.equals(config.expectedContentManifestSha256())) {
            throw new IllegalStateException(
                "Expected-content manifest SHA-256 mismatch; expected "
                    + config.expectedContentManifestSha256() + ", observed "
                    + sha256);
        }

        JsonObject root;
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException(
                    "Expected-content manifest root must be an object");
            }
            root = parsed.getAsJsonObject();
        }
        if (requiredInt(root, "schemaVersion") != 1) {
            throw new IllegalStateException(
                "Unsupported expected-content manifest schema");
        }
        String targetModId = requiredString(root, "targetModId");
        if (!targetModId.equals(config.expectedModId())) {
            throw new IllegalStateException(
                "Expected-content manifest targetModId mismatch: "
                    + targetModId + " != " + config.expectedModId());
        }

        JsonObject source = requiredObject(root, "source");
        String repository = requiredString(source, "repository");
        String commit = requiredString(source, "commit")
            .toLowerCase(Locale.ROOT);
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException(
                "Expected-content source commit must be 40 lowercase hexadecimal characters");
        }
        String derivation = requiredString(source, "derivation");
        JsonArray sourceFileArray = requiredArray(source, "files");
        if (sourceFileArray.size() == 0) {
            throw new IllegalStateException(
                "Expected-content manifest must pin at least one source file");
        }
        List<Map<String, String>> sourceFiles = new ArrayList<>();
        Set<String> sourcePaths = new LinkedHashSet<>();
        Map<String, String> sourceHashes = new LinkedHashMap<>();
        for (JsonElement element : sourceFileArray) {
            if (!element.isJsonObject()) {
                throw new IllegalStateException(
                    "Expected-content source file entry must be an object");
            }
            JsonObject file = element.getAsJsonObject();
            String sourcePath = requiredString(file, "path");
            if (sourcePath.contains("\\") || sourcePath.startsWith("/")
                    || Path.of(sourcePath).isAbsolute()) {
                throw new IllegalStateException(
                    "Unsafe expected-content source path: " + sourcePath);
            }
            for (String segment : sourcePath.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".")
                        || segment.equals("..")) {
                    throw new IllegalStateException(
                        "Unsafe expected-content source path: " + sourcePath);
                }
            }
            String sourceSha256 = requiredString(file, "sha256")
                .toLowerCase(Locale.ROOT);
            if (!sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException(
                    "Invalid expected-content source SHA-256: " + sourcePath);
            }
            if (!sourcePaths.add(sourcePath)) {
                throw new IllegalStateException(
                    "Duplicate expected-content source path: " + sourcePath);
            }
            sourceHashes.put(sourcePath, sourceSha256);
            sourceFiles.add(Map.of(
                "path", sourcePath,
                "sha256", sourceSha256));
        }

        JsonArray expectedArray = requiredArray(
            root, "expectedRegisteredItemIds");
        if (expectedArray.size() == 0) {
            throw new IllegalStateException(
                "Expected-content manifest must contain registered item IDs");
        }
        List<ResourceLocation> expectedIds = new ArrayList<>();
        Set<ResourceLocation> uniqueIds = new HashSet<>();
        List<String> observedOrder = new ArrayList<>();
        for (JsonElement element : expectedArray) {
            if (!element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException(
                    "Expected registered item ID must be a string");
            }
            String raw = element.getAsString();
            ResourceLocation itemId = ResourceLocation.tryParse(raw);
            if (itemId == null
                    || !targetModId.equals(itemId.getNamespace())) {
                throw new IllegalStateException(
                    "Expected item ID must use namespace " + targetModId
                        + ": " + raw);
            }
            if (!uniqueIds.add(itemId)) {
                throw new IllegalStateException(
                    "Duplicate expected registered item ID: " + itemId);
            }
            expectedIds.add(itemId);
            observedOrder.add(itemId.toString());
        }
        List<String> sortedOrder = new ArrayList<>(observedOrder);
        Collections.sort(sortedOrder);
        if (!observedOrder.equals(sortedOrder)) {
            throw new IllegalStateException(
                "Expected registered item IDs must be sorted");
        }

        JsonArray exceptionArray = requiredArray(
            root, "iconEvidenceExceptions");
        Map<ResourceLocation, Map<String, String>> exceptions =
            new LinkedHashMap<>();
        for (JsonElement element : exceptionArray) {
            if (!element.isJsonObject()) {
                throw new IllegalStateException(
                    "Icon-evidence exception must be an object");
            }
            JsonObject exception = element.getAsJsonObject();
            ResourceLocation itemId = ResourceLocation.tryParse(
                requiredString(exception, "itemId"));
            if (itemId == null || !uniqueIds.contains(itemId)) {
                throw new IllegalStateException(
                    "Icon-evidence exception references an unexpected item: "
                        + itemId);
            }
            String reason = requiredString(exception, "reason");
            String sourcePath = requiredString(exception, "sourcePath");
            String sourceSha256 = requiredString(
                exception, "sourceSha256").toLowerCase(Locale.ROOT);
            if (!sourceSha256.equals(sourceHashes.get(sourcePath))) {
                throw new IllegalStateException(
                    "Icon-evidence exception lacks a pinned source-file hash for "
                        + itemId);
            }
            if (exceptions.put(itemId, Map.of(
                    "reason", reason,
                    "sourcePath", sourcePath,
                    "sourceSha256", sourceSha256)) != null) {
                throw new IllegalStateException(
                    "Duplicate icon-evidence exception: " + itemId);
            }
        }

        return new ExpectedContentManifest(
            path,
            sha256,
            targetModId,
            repository,
            commit,
            derivation,
            sourceFiles,
            expectedIds,
            exceptions
        );
    }

    List<ResourceLocation> expectedItemIds() {
        return expectedItemIds;
    }

    List<String> expectedItemIdStrings() {
        return expectedItemIds.stream().map(ResourceLocation::toString)
            .toList();
    }

    boolean isIconEvidenceException(ResourceLocation itemId) {
        return iconEvidenceExceptions.containsKey(itemId);
    }

    Map<String, String> iconEvidenceException(ResourceLocation itemId) {
        return iconEvidenceExceptions.get(itemId);
    }

    Map<String, Object> evidence() throws IOException {
        Map<String, Object> evidence = ReceiptSupport.artifact(path);
        evidence.put("kind", "expected-content-manifest");
        evidence.put("targetModId", targetModId);
        evidence.put("expectedRegisteredItemCount", expectedItemIds.size());
        evidence.put("iconEvidenceExceptionCount",
            iconEvidenceExceptions.size());
        evidence.put("sourceRepository", sourceRepository);
        evidence.put("sourceCommit", sourceCommit);
        evidence.put("sourceDerivation", sourceDerivation);
        evidence.put("sourceFiles", sourceFiles);
        return evidence;
    }

    Map<String, Object> sourceAnchor() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("repository", sourceRepository);
        source.put("commit", sourceCommit);
        source.put("derivation", sourceDerivation);
        source.put("files", sourceFiles);
        source.put("manifestSha256", sha256);
        return source;
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException(
                "Expected-content field must be an object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalStateException(
                "Expected-content field must be an array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalStateException(
                "Expected-content field must be a nonblank string: " + name);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalStateException(
                "Expected-content field must be an integer: " + name);
        }
        return Integer.parseInt(value.getAsString());
    }
}
