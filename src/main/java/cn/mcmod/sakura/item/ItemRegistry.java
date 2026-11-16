package cn.mcmod.sakura.item;

import java.util.Map;
import java.util.function.Supplier;

import cn.mcmod.sakura.SakuraMod;
import cn.mcmod.sakura.block.BlockRegistry;
import cn.mcmod.sakura.item.enums.SakuraNormalItemSet;
import cn.mcmod_mmf.mmlib.item.ItemFoodSeeds;
import cn.mcmod_mmf.mmlib.item.info.FoodInfo;
import cn.mcmod_mmf.mmlib.registry.ItemRegistryUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
public class ItemRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SakuraMod.MODID);

    public static final DeferredHolder<Item, Item> RICE_SEEDS = register("rice_seeds", RiceSeedsItem::new);

    public static final DeferredHolder<Item, Item> ONION_SEEDS = register("onion_seeds",
            () -> seed(BlockRegistry.ONION_CROP.get()));
    public static final DeferredHolder<Item, Item> RADISH_SEEDS = register("radish_seeds",
            () -> seed(BlockRegistry.RADISH_CROP.get()));
    public static final DeferredHolder<Item, Item> CABBAGE_SEEDS = register("cabbage_seeds",
            () -> seed(BlockRegistry.CABBAGE_CROP.get()));
    public static final DeferredHolder<Item, Item> RAPESEEDS = register("rapeseeds",
            () -> seed(BlockRegistry.RAPESEED_CROP.get()));
    public static final DeferredHolder<Item, Item> RED_BEAN = register("red_bean",
            () -> seed(BlockRegistry.REDBEAN_CROP.get()));
    public static final DeferredHolder<Item, Item> SOYBEAN = register("soybean",
            () -> seed(BlockRegistry.SOYBEAN_CROP.get()));
    public static final DeferredHolder<Item, Item> BUCKWHEAT = register("buckwheat",
            () -> seed(BlockRegistry.BUCKWHEAT_CROP.get()));

    public static final DeferredHolder<Item, Item> EGGPLANT_SEEDS = register("eggplant_seeds",
            () -> seed(BlockRegistry.EGGPLANT_CROP.get()));
    public static final DeferredHolder<Item, Item> TOMATO_SEEDS = register("tomato_seeds",
            () -> seed(BlockRegistry.TOMATO_CROP.get()));

    public static final DeferredHolder<Item, ItemFoodSeeds> TARO = register("taro",
            () -> seed(BlockRegistry.TARO_CROP.get(),
                    FoodInfo.builder().name("taro").amountAndCalories(2, 0.2F).water(0F).nutrients(2F, 2F, 0F, 0F, 0F)
                            .decayModifier(2F).heatCapacity(1F).cookingTemp(480F).build()));

    public static final Map<SakuraNormalItemSet, DeferredHolder<Item, Item>> MATERIALS = ItemRegistryUtil
            .mapOfKeys(SakuraNormalItemSet.class, material -> register(material.getName(), ItemRegistry::normalItem));
    
    public static final DeferredHolder<Item, Item> IRON_FISH_KNIFE = register("knife_fish", ()->new KnifeItem(Tiers.IRON, 1F, -2.0F, SakuraMod.defaultItemProperties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> IRON_NOODLE_KNIFE = register("knife_noodle", ()->new KnifeItem(Tiers.IRON, 2F, -3.0F, SakuraMod.defaultItemProperties().stacksTo(1)));
    
    private static Item normalItem() {
        return new Item(SakuraMod.defaultItemProperties());
    }

    private static ItemNameBlockItem seed(Block block) {
        return new ItemNameBlockItem(block, SakuraMod.defaultItemProperties());
    }

    private static ItemFoodSeeds seed(Block block, FoodInfo info) {
        return new ItemFoodSeeds(block, SakuraMod.defaultItemProperties(), info);
    }
@SuppressWarnings("unchecked")
private static <V extends Item> DeferredHolder<Item, V> register(String name, Supplier<V> item) {
        return (DeferredHolder<Item, V>) (DeferredHolder<?, ?>) ITEMS.register(name, item);
    }
}
