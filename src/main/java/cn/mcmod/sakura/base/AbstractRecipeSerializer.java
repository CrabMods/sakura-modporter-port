package cn.mcmod.sakura.base;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.fluids.FluidStack;

public class AbstractRecipeSerializer<T extends AbstractRecipe> implements RecipeSerializer<T> {
    private final Class<T> recipeClass;
    private final Gson gson;
    private final MapCodec<T> mapCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec;

    public AbstractRecipeSerializer(Class<T> recipeClass) {
        this.recipeClass = recipeClass;
        this.gson = createGson();
        this.mapCodec = createMapCodec();
        this.streamCodec = createStreamCodec();
    }

    private Gson createGson() {
        return new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeHierarchyAdapter(Ingredient.class, new IngredientAdapter())
                .registerTypeHierarchyAdapter(ItemStack.class, new ItemStackAdapter())
                .registerTypeHierarchyAdapter(FluidStack.class, new FluidStackAdapter())
                .registerTypeHierarchyAdapter(FluidIngredient.class, new FluidIngredientAdapter())
                .registerTypeHierarchyAdapter(ChanceResult.class, new ChanceResultAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<Ingredient>>(){}.getType(), new IngredientListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ItemStack>>(){}.getType(), new ItemStackListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ChanceResult>>(){}.getType(), new ChanceResultListAdapter())
                .create();
    }

    private MapCodec<T> createMapCodec() {
        return new MapCodec<>() {
            @Override
            public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
                JsonObject json = toJson(input);
                for (var entry : json.entrySet()) {
                    prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
                }
                return prefix;
            }

            @Override
            public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
                try {
                    JsonObject json = new JsonObject();
                    input.entries().forEach(pair -> {
                        String key = ops.getStringValue(pair.getFirst()).result().orElse("");
                        if (!key.isEmpty()) {
                            json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                        }
                    });
                    return DataResult.success(fromJson(json));
                } catch (Exception e) {
                    return DataResult.error(() -> "Failed to decode recipe: " + e.getMessage());
                }
            }

            @Override
            public <O> Stream<O> keys(DynamicOps<O> ops) {
                return Stream.empty();
            }
        };
    }

    private StreamCodec<RegistryFriendlyByteBuf, T> createStreamCodec() {
        return StreamCodec.of(
                (buf, recipe) -> buf.writeUtf(gson.toJson(recipe)),
                buf -> fromJson(com.google.gson.JsonParser.parseString(buf.readUtf(32767)).getAsJsonObject())
        );
    }

    @Override
    public MapCodec<T> codec() {
        return mapCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }

    public T fromJson(JsonObject json) {
        return gson.fromJson(json, recipeClass);
    }

    public JsonObject toJson(T recipe) {
        return gson.toJsonTree(recipe).getAsJsonObject();
    }

    private static class IngredientAdapter implements JsonSerializer<Ingredient>, JsonDeserializer<Ingredient> {
        @Override
        public JsonElement serialize(Ingredient src, Type typeOfSrc, JsonSerializationContext context) {
            return Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public Ingredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Ingredient.CODEC.decode(JsonOps.INSTANCE, json).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(Ingredient.EMPTY);
        }
    }

    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return decodeItemStack(json, "ItemStack");
        }
    }

    private static ItemStack decodeItemStack(JsonElement json, String contextName) throws JsonParseException {
        if (json == null || json.isJsonNull()) return ItemStack.EMPTY;
        JsonElement normalized = normalizeLegacyItemStackJson(json);
        var decoded = ItemStack.CODEC.decode(JsonOps.INSTANCE, normalized);
        return decoded.result()
                .map(com.mojang.datafixers.util.Pair::getFirst)
                .orElseThrow(() -> new JsonParseException(
                        "Failed to decode " + contextName + ": " + decoded.error().map(Object::toString).orElse("unknown codec error")));
    }

    private static JsonElement normalizeLegacyItemStackJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return JsonNull.INSTANCE;
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            JsonObject normalized = new JsonObject();
            normalized.addProperty("id", json.getAsString());
            return normalized;
        }
        if (!json.isJsonObject()) return json;
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("item") || obj.has("id")) return json;

        JsonObject normalized = new JsonObject();
        normalized.add("id", obj.get("item"));
        copyIfPresent(obj, normalized, "count");
        copyIfPresent(obj, normalized, "components");
        if (obj.has("nbt")) {
            JsonObject components = normalized.has("components") && normalized.get("components").isJsonObject()
                    ? normalized.getAsJsonObject("components")
                    : new JsonObject();
            components.add("minecraft:custom_data", obj.get("nbt"));
            normalized.add("components", components);
        }
        return normalized;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key));
        }
    }

    private static class FluidStackAdapter implements JsonSerializer<FluidStack>, JsonDeserializer<FluidStack> {
        @Override
        public JsonElement serialize(FluidStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return FluidStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public FluidStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidStack.EMPTY;
            JsonElement normalized = normalizeLegacyFluidStackJson(json);
            if (normalized == null || normalized.isJsonNull()) return FluidStack.EMPTY;
            return FluidStack.CODEC.decode(JsonOps.INSTANCE, normalized).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(FluidStack.EMPTY);
        }
    }

    private static JsonElement normalizeLegacyFluidStackJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return JsonNull.INSTANCE;
        if (!json.isJsonObject()) return json;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("fluid")
                && obj.get("fluid").isJsonPrimitive()
                && "minecraft:empty".equals(obj.get("fluid").getAsString())) {
            return JsonNull.INSTANCE;
        }
        if (!obj.has("fluid") || obj.has("id")) return json;

        JsonObject normalized = new JsonObject();
        normalized.add("id", obj.get("fluid"));
        copyIfPresent(obj, normalized, "amount");
        copyIfPresent(obj, normalized, "components");
        if (obj.has("nbt")) {
            JsonObject components = normalized.has("components") && normalized.get("components").isJsonObject()
                    ? normalized.getAsJsonObject("components")
                    : new JsonObject();
            components.add("minecraft:custom_data", obj.get("nbt"));
            normalized.add("components", components);
        }
        return normalized;
    }

    private static class FluidIngredientAdapter implements JsonSerializer<FluidIngredient>, JsonDeserializer<FluidIngredient> {
        @Override
        public JsonElement serialize(FluidIngredient src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == FluidIngredient.EMPTY) return JsonNull.INSTANCE;
            return src.serialize();
        }

        @Override
        public FluidIngredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidIngredient.EMPTY;
            return FluidIngredient.deserialize(json);
        }
    }

    private static class ChanceResultAdapter implements JsonSerializer<ChanceResult>, JsonDeserializer<ChanceResult> {
        @Override
        public JsonElement serialize(ChanceResult src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject json = new JsonObject();
            json.add("item", ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src.stack()).result().orElse(JsonNull.INSTANCE));
            json.addProperty("chance", src.chance());
            return json;
        }

        @Override
        public ChanceResult deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || !json.isJsonObject()) return ChanceResult.EMPTY;
            JsonObject obj = json.getAsJsonObject();
            ItemStack stack = decodeItemStack(chanceResultStackJson(obj), "ChanceResult.item");
            float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1.0F;
            return new ChanceResult(stack, chance);
        }
    }

    private static JsonElement chanceResultStackJson(JsonObject obj) {
        if (obj.has("stack")) return obj.get("stack");
        if (!obj.has("item")) return JsonNull.INSTANCE;
        JsonElement item = obj.get("item");
        if (item != null && item.isJsonObject()) return item;

        JsonObject stack = new JsonObject();
        stack.add("item", item);
        copyIfPresent(obj, stack, "count");
        copyIfPresent(obj, stack, "components");
        copyIfPresent(obj, stack, "nbt");
        return stack;
    }

    private static class IngredientListAdapter implements JsonSerializer<NonNullList<Ingredient>>, JsonDeserializer<NonNullList<Ingredient>> {
        @Override
        public JsonElement serialize(NonNullList<Ingredient> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (Ingredient ingredient : src) {
                array.add(context.serialize(ingredient));
            }
            return array;
        }

        @Override
        public NonNullList<Ingredient> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<Ingredient> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    Ingredient ingredient = context.deserialize(element, Ingredient.class);
                    list.add(ingredient != null ? ingredient : Ingredient.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ItemStackListAdapter implements JsonSerializer<NonNullList<ItemStack>>, JsonDeserializer<NonNullList<ItemStack>> {
        @Override
        public JsonElement serialize(NonNullList<ItemStack> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ItemStack stack : src) {
                array.add(context.serialize(stack));
            }
            return array;
        }

        @Override
        public NonNullList<ItemStack> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ItemStack> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ItemStack stack = context.deserialize(element, ItemStack.class);
                    list.add(stack != null ? stack : ItemStack.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ChanceResultListAdapter implements JsonSerializer<NonNullList<ChanceResult>>, JsonDeserializer<NonNullList<ChanceResult>> {
        @Override
        public JsonElement serialize(NonNullList<ChanceResult> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ChanceResult result : src) {
                array.add(context.serialize(result));
            }
            return array;
        }

        @Override
        public NonNullList<ChanceResult> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ChanceResult> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ChanceResult result = context.deserialize(element, ChanceResult.class);
                    list.add(result != null ? result : ChanceResult.EMPTY);
                }
            }
            return list;
        }
    }
}