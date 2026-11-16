package cn.mcmod.sakura.base;

import com.google.gson.annotations.Expose;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * Project-local replacement for MMLib recipe base helpers removed in MMLib 1.21.1.
 */
public abstract class AbstractRecipe implements Recipe<RecipeWrapper> {
    public String group = "";
    @Expose
    public float experience;
    @Expose
    public int recipeTime;

    public float getExperience() {
        return experience;
    }

    public int getRecipeTime() {
        return recipeTime;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public abstract ItemStack getResultItem(HolderLookup.Provider registries);

    @Override
    public boolean isSpecial() {
        return true;
    }
}