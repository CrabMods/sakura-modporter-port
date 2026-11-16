package cn.mcmod.sakura.fluid;

import cn.mcmod.sakura.SakuraMod;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.item.Item;

public class BucketItemRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SakuraMod.MODID);
    public static final DeferredHolder<Item, Item> FOOD_OIL_BUCKET = ITEMS.register("food_oil_bucket", () -> 
        new BucketItem(FluidRegistry.FOOD_OIL.get(), new Item.Properties().craftRemainder(Items.BUCKET)));

    public static final DeferredHolder<Item, Item> DOBUROKU_BUCKET = ITEMS.register("doburoku_bucket", () -> 
        new BucketItem(FluidRegistry.DOBUROKU.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> SAKE_BUCKET = ITEMS.register("sake_bucket", () -> 
        new BucketItem(FluidRegistry.SAKE.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> SHOUCHU_BUCKET = ITEMS.register("shouchu_bucket", () -> 
        new BucketItem(FluidRegistry.SHOUCHU.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    
    public static final DeferredHolder<Item, Item> BEER_BUCKET = ITEMS.register("beer_bucket", () -> 
        new BucketItem(FluidRegistry.BEER.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    
    public static final DeferredHolder<Item, Item> WHISKEY_BUCKET = ITEMS.register("whiskey_bucket", () -> 
        new BucketItem(FluidRegistry.WHISKEY.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> RED_WINE_BUCKET = ITEMS.register("red_wine_bucket", () -> 
        new BucketItem(FluidRegistry.RED_WINE.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> WHITE_WINE_BUCKET = ITEMS.register("white_wine_bucket", () -> 
        new BucketItem(FluidRegistry.WHITE_WINE.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> CHAMPAGNE_BUCKET = ITEMS.register("champagne_bucket", () -> 
        new BucketItem(FluidRegistry.CHAMPAGNE.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    
    public static final DeferredHolder<Item, Item> RUM_BUCKET = ITEMS.register("rum_bucket", () -> 
        new BucketItem(FluidRegistry.RUM.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    public static final DeferredHolder<Item, Item> BRANDY_BUCKET = ITEMS.register("brandy_bucket", () -> 
        new BucketItem(FluidRegistry.BRANDY.get(), new Item.Properties().craftRemainder(Items.BUCKET)));
    
    
    public static void onAddCreativeModeTab(BuildCreativeModeTabContentsEvent event){
        if(event.getTabKey()== CreativeModeTabs.INGREDIENTS){
            ITEMS.getEntries().forEach(entry -> event.accept(entry.get()));
        }
    }
}
