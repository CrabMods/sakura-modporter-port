package com.modporter.validation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class ExactStackSnapshot {
    static final String EMPTY_EVIDENCE = "<empty>";
    private static final Gson JSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    private static final ExactStackSnapshot EMPTY =
        new ExactStackSnapshot(
            ItemStack.EMPTY,
            null,
            EMPTY_EVIDENCE,
            0,
            Map.of("empty", true)
        );

    private final ItemStack stack;
    private final ResourceLocation itemId;
    private final String evidence;
    private final int bucketHash;
    private final Map<String, Object> evidenceRecord;

    private ExactStackSnapshot(
        ItemStack stack,
        ResourceLocation itemId,
        String evidence,
        int bucketHash,
        Map<String, Object> evidenceRecord
    ) {
        this.stack = stack;
        this.itemId = itemId;
        this.evidence = evidence;
        this.bucketHash = bucketHash;
        this.evidenceRecord = evidenceRecord;
    }

    static ExactStackSnapshot capture(
        ItemStack source,
        HolderLookup.Provider registries
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(registries, "registries");
        if (source.isEmpty()) {
            return EMPTY;
        }

        ItemStack copy = source.copy();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(
            copy.getItem());
        if (itemId == null) {
            throw new IllegalStateException(
                "Cannot snapshot an unregistered item stack: " + copy);
        }

        DataComponentPatch patch = copy.getComponentsPatch();
        List<String> transientPatchComponents = patch.entrySet().stream()
            .filter(entry -> entry.getKey().isTransient())
            .map(entry -> componentTypeId(entry.getKey()))
            .sorted()
            .toList();
        List<String> transientEffectiveComponents = copy.getComponents()
            .keySet().stream()
            .filter(component -> component.isTransient())
            .map(ExactStackSnapshot::componentTypeId)
            .sorted()
            .toList();
        List<String> transientComponents = new ArrayList<>(
            transientPatchComponents);
        for (String component : transientEffectiveComponents) {
            if (!transientComponents.contains(component)) {
                transientComponents.add(component);
            }
        }
        transientComponents.sort(Comparator.naturalOrder());
        DataComponentPatch persistentPatch = patch.forget(
            component -> component.isTransient());
        DynamicOps<JsonElement> ops =
            registries.createSerializationContext(JsonOps.INSTANCE);
        JsonElement encodedPatch = DataComponentPatch.CODEC
            .encodeStart(ops, persistentPatch)
            .getOrThrow(message -> new IllegalStateException(
                "Failed to serialize persistent component patch for "
                    + itemId + ": " + message));
        String canonicalPatch = canonicalJson(encodedPatch);
        String patchSha256 = Hashing.sha256Utf8(canonicalPatch);
        int bucketHash = 31 * ItemStack.hashItemAndComponents(copy)
            + copy.getCount();
        String evidence = itemId + "#" + patchSha256 + "x"
            + copy.getCount();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("itemId", itemId.toString());
        record.put("count", copy.getCount());
        record.put("persistentComponentPatch",
            JsonParser.parseString(canonicalPatch));
        record.put("persistentComponentPatchCanonicalJson",
            canonicalPatch);
        record.put("persistentComponentPatchSha256",
            patchSha256);
        record.put("transientPatchComponents",
            transientPatchComponents);
        record.put("transientEffectiveComponents",
            transientEffectiveComponents);
        record.put("transientComponents", transientComponents);
        record.put("portableEvidenceComplete",
            transientComponents.isEmpty());
        record.put("persistentCodecShaRole",
            "portable evidence only; never used as stack identity");
        record.put("transientComponentPolicy",
            transientComponents.isEmpty()
                ? "none-present"
                : "all observed transient patch/effective component IDs are listed; values remain non-codec evidence and native exact equality remains authoritative");
        record.put("nativeBucketHashUnsigned",
            Integer.toUnsignedString(bucketHash));
        record.put("nativeBucketHashRole",
            "hash bucket only; equality always rechecks native item, components, and count");
        record.put("nativeExactEquality",
            "count+ItemStack.isSameItemSameComponents");
        record.put("evidenceFingerprint", evidence);
        return new ExactStackSnapshot(
            copy, itemId, evidence, bucketHash, record);
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    ResourceLocation itemId() {
        return itemId;
    }

    String evidence() {
        return evidence;
    }

    Map<String, Object> evidenceRecord() {
        return new LinkedHashMap<>(evidenceRecord);
    }

    ItemStack copyStack() {
        return stack.copy();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExactStackSnapshot snapshot)) {
            return false;
        }
        return stack.getCount() == snapshot.stack.getCount()
            && ItemStack.isSameItemSameComponents(
                stack, snapshot.stack);
    }

    @Override
    public int hashCode() {
        return bucketHash;
    }

    @Override
    public String toString() {
        return evidence;
    }

    private static String componentTypeId(
        net.minecraft.core.component.DataComponentType<?> component
    ) {
        ResourceLocation id =
            BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component);
        return id == null ? component.toString() : id.toString();
    }

    private static String canonicalJson(JsonElement element) {
        if (element == null || element instanceof JsonNull) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            return JSON.toJson(element);
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(canonicalJson(array.get(index)));
            }
            return builder.append(']').toString();
        }
        JsonObject object = element.getAsJsonObject();
        List<String> names = new ArrayList<>(object.keySet());
        names.sort(Comparator.naturalOrder());
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            String name = names.get(index);
            builder.append(JSON.toJson(name));
            builder.append(':');
            builder.append(canonicalJson(object.get(name)));
        }
        return builder.append('}').toString();
    }
}
