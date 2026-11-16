package cn.mcmod.sakura.base;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

public record ChanceResult(ItemStack stack, float chance) {
    public static final ChanceResult EMPTY = new ChanceResult(ItemStack.EMPTY, 0.0F);

    public ItemStack rollOutput(RandomSource random, int fortuneLevel) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        float adjustedChance = Math.min(1.0F, chance + Math.max(0, fortuneLevel) * 0.1F);
        return random.nextFloat() < adjustedChance ? stack.copy() : ItemStack.EMPTY;
    }
}