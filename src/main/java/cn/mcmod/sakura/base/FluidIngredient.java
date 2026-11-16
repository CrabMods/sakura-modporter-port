package cn.mcmod.sakura.base;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

public abstract class FluidIngredient implements Predicate<FluidStack> {
    public static final FluidIngredient EMPTY = new EmptyFluidIngredient();

    protected int amountRequired;
    private List<FluidStack> matchingFluidStacks;

    public static FluidIngredient fromTag(TagKey<Fluid> tag, int amount) {
        return new TagFluidIngredient(tag, amount);
    }

    public static FluidIngredient fromFluid(Fluid fluid, int amount) {
        return new SingleFluidIngredient(fluid, amount);
    }

    public static FluidIngredient fromFluid(Supplier<? extends Fluid> fluid, int amount) {
        return fromFluid(fluid.get(), amount);
    }

    public static FluidIngredient fromFluidStack(FluidStack stack) {
        return stack == null || stack.isEmpty() ? EMPTY : fromFluid(stack.getFluid(), stack.getAmount());
    }

    public int getAmount() {
        return amountRequired;
    }

    public int getRequiredAmount() {
        return amountRequired;
    }

    public List<FluidStack> getMatchingFluidStacks() {
        if (matchingFluidStacks == null) {
            matchingFluidStacks = determineMatchingFluidStacks();
        }
        return matchingFluidStacks;
    }

    public FluidStack[] getStacks() {
        return getMatchingFluidStacks().toArray(FluidStack[]::new);
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        writeInternal(json);
        json.addProperty("amount", amountRequired);
        return json;
    }

    public static boolean isFluidIngredient(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject obj = json.getAsJsonObject();
        return obj.has("fluid") || obj.has("tag") || obj.has("fluidTag") || obj.has("null_fluid");
    }

    public static FluidIngredient deserialize(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("null_fluid")) return EMPTY;
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1000;
        if (obj.has("tag") || obj.has("fluidTag")) {
            ResourceLocation tagId = ResourceLocation.parse(obj.has("tag") ? obj.get("tag").getAsString() : obj.get("fluidTag").getAsString());
            return fromTag(TagKey.create(Registries.FLUID, tagId), amount);
        }
        if (obj.has("fluid")) {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(obj.get("fluid").getAsString()));
            return fromFluid(fluid, amount);
        }
        return EMPTY;
    }

    @Override
    public boolean test(FluidStack stack) {
        return stack != null && stack.getAmount() >= amountRequired && testInternal(stack);
    }

    protected abstract boolean testInternal(FluidStack stack);
    protected abstract List<FluidStack> determineMatchingFluidStacks();
    protected abstract void writeInternal(JsonObject json);

    private static final class EmptyFluidIngredient extends FluidIngredient {
        private EmptyFluidIngredient() {
            this.amountRequired = 0;
        }

        @Override
        public boolean test(FluidStack stack) {
            return false;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return false;
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.emptyList();
        }

        @Override
        protected void writeInternal(JsonObject json) {
        }
    }

    private static final class SingleFluidIngredient extends FluidIngredient {
        private final Fluid fluid;

        private SingleFluidIngredient(Fluid fluid, int amount) {
            this.fluid = fluid;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().isSame(fluid);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.singletonList(new FluidStack(fluid, amountRequired));
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
    }

    private static final class TagFluidIngredient extends FluidIngredient {
        private final TagKey<Fluid> tag;

        private TagFluidIngredient(TagKey<Fluid> tag, int amount) {
            this.tag = tag;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().is(tag);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            List<FluidStack> stacks = new ArrayList<>();
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> stacks.add(new FluidStack(holder.value(), amountRequired)))
            );
            return stacks;
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("tag", tag.location().toString());
        }
    }
}