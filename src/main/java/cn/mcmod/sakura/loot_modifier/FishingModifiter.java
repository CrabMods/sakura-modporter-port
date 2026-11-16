package cn.mcmod.sakura.loot_modifier;

import cn.mcmod.sakura.item.FoodRegistry;
import cn.mcmod.sakura.item.enums.SakuraFoodSet;
import cn.mcmod.sakura.loot_modifier.SeedsDrop.SeedDropModifier;

import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import com.mojang.serialization.MapCodec;


public class FishingModifiter extends LootModifier{

    protected FishingModifiter(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }
	public static final MapCodec<SeedDropModifier> CODEC = RecordCodecBuilder
			.mapCodec(inst -> codecStart(inst).apply(inst, SeedDropModifier::new));
    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        generatedLoot.add(new ItemStack(FoodRegistry.FOODSET.get(SakuraFoodSet.SHRIMP).get()));
        return generatedLoot;
    }


    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
