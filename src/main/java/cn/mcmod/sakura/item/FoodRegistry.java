package cn.mcmod.sakura.item;

import java.util.Map;
import java.util.function.Supplier;

import cn.mcmod.sakura.SakuraMod;
import cn.mcmod.sakura.item.enums.SakuraCuisineSet;
import cn.mcmod.sakura.item.enums.SakuraFoodSet;
import cn.mcmod_mmf.mmlib.item.ItemFoodBase;
import cn.mcmod_mmf.mmlib.item.info.FoodInfo;
import cn.mcmod_mmf.mmlib.registry.ItemRegistryUtil;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
public class FoodRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SakuraMod.MODID);

    public static final Map<SakuraFoodSet, DeferredHolder<Item, ItemFoodBase>> FOODSET = ItemRegistryUtil.mapOfKeys(
            SakuraFoodSet.class, info -> register(info.getFoodInfo().getName(), () -> normalFood(info.getFoodInfo())));

    public static final Map<SakuraCuisineSet, DeferredHolder<Item, ItemFoodBase>> CUISINES = ItemRegistryUtil.mapOfKeys(
            SakuraCuisineSet.class,
            info -> register(info.getFoodInfo().getName(), () -> normalFood(info.getFoodInfo(), info.getContainer().get())));

    private static ItemFoodBase normalFood(FoodInfo info) {
        return new ItemFoodBase(SakuraMod.defaultItemProperties(), info);
    }

    private static ItemFoodBase normalFood(FoodInfo info, Item container) {
        if(container == null)
            return normalFood(info);
        return new ItemFoodBase(SakuraMod.defaultItemProperties().craftRemainder(container), info);
    }
@SuppressWarnings("unchecked")
private static <V extends Item> DeferredHolder<Item, V> register(String name, Supplier<V> item) {
        return (DeferredHolder<Item, V>) (DeferredHolder<?, ?>) ITEMS.register(name, item);
    }
}
