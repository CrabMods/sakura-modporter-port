package cn.mcmod.sakura.data.builder;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import cn.mcmod.sakura.recipes.CookingPotRecipe;
import cn.mcmod.sakura.recipes.RecipeTypeRegistry;
import cn.mcmod.sakura.base.FluidIngredient;
import net.minecraft.core.NonNullList;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.ItemLike;

public class CookingPotRecipeBuilder {
    private final ItemStack result;
    private final NonNullList<Ingredient> ingredients = NonNullList.create();
    private final FluidIngredient fluid;
    private final float experience;
    private final int recipeTime;

    private CookingPotRecipeBuilder(FluidIngredient fluid, ItemLike resultItem, int count, float exp, int time) {
        this.result = new ItemStack(resultItem.asItem(), count);
        this.fluid = fluid;
        this.experience = exp;
        this.recipeTime = time;
    }

    public static CookingPotRecipeBuilder cooking(FluidIngredient fluid, ItemLike resultItem) {
        return new CookingPotRecipeBuilder(fluid, resultItem, 1, 0F, 200);
    }

    public static CookingPotRecipeBuilder cooking(FluidIngredient fluid, ItemLike resultItem, int count) {
        return new CookingPotRecipeBuilder(fluid, resultItem, count, 0F, 200);
    }

    public static CookingPotRecipeBuilder cooking(FluidIngredient fluid, ItemLike resultItem, float exp, int time) {
        return new CookingPotRecipeBuilder(fluid, resultItem, 1, exp, time);
    }

    public static CookingPotRecipeBuilder cooking(FluidIngredient fluid, ItemLike resultItem, int count, float exp,
            int time) {
        return new CookingPotRecipeBuilder(fluid, resultItem, count, exp, time);
    }

    public CookingPotRecipeBuilder requires(TagKey<Item> tag) {
        return this.requires(Ingredient.of(tag));
    }

    public CookingPotRecipeBuilder requires(ItemLike item) {
        return this.requires(item, 1);
    }

    public CookingPotRecipeBuilder requires(ItemLike item, int count) {
        for (int i = 0; i < count; ++i) {
            this.requires(Ingredient.of(item));
        }
        return this;
    }

    public CookingPotRecipeBuilder requires(Ingredient ingre) {
        return this.requires(ingre, 1);
    }

    public CookingPotRecipeBuilder requires(Ingredient ingre, int count) {
        for (int i = 0; i < count; ++i) {
            this.ingredients.add(ingre);
        }
        return this;
    }

    public void save(RecipeOutput consumer, ResourceLocation id) {
        CookingPotRecipeBuilder.Result result = new CookingPotRecipeBuilder.Result(id, this.fluid, this.result, this.ingredients,
                this.experience, this.recipeTime);
        consumer.accept(result.getId(), result.recipe, null);
    }

    public static class Result {
        private final ResourceLocation id;
        private final CookingPotRecipe recipe = new CookingPotRecipe();

        public Result(ResourceLocation id, FluidIngredient fluid, ItemStack result, NonNullList<Ingredient> ingredients,
                float exp, int time) {
            this.id = id;
            recipe.output = result;
            recipe.fluidInput = fluid;
            recipe.inputItems = ingredients;
            recipe.experience = exp;
            recipe.recipeTime = time;
        }

        public void serializeRecipeData(JsonObject json) {
            JsonObject recipeJson = RecipeTypeRegistry.COOKING_RECIPE_SERIALIZER.get().toJson(recipe);
            json.add("ingredients", recipeJson.get("ingredients"));
            json.add("fluid", recipeJson.get("fluid"));
            json.add("result", recipeJson.get("result"));
            json.add("experience", recipeJson.get("experience"));
            json.add("recipeTime", recipeJson.get("recipeTime"));
        }

        public RecipeSerializer<?> getType() {
            return RecipeTypeRegistry.COOKING_RECIPE_SERIALIZER.get();
        }

        public ResourceLocation getId() {
            return this.id;
        }

        @Nullable
        public JsonObject serializeAdvancement() {
            return null;
        }

        @Nullable
        public ResourceLocation getAdvancementId() {
            return null;
        }
    }

}
