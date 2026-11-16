package cn.mcmod.sakura.fluid;

import cn.mcmod.sakura.SakuraMod;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.level.block.state.BlockBehaviour;
public class FluidBlockRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, SakuraMod.MODID);
    public static final DeferredHolder<Block, LiquidBlock> FOOD_OIL_BLOCK = BLOCKS.register("food_oil", () ->
        new LiquidBlock(FluidRegistry.FOOD_OIL.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));

    public static final DeferredHolder<Block, LiquidBlock> DOBUROKU_BLOCK = BLOCKS.register("doburoku", () ->
        new LiquidBlock(FluidRegistry.DOBUROKU.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> SAKE_BLOCK = BLOCKS.register("sake", () ->
        new LiquidBlock(FluidRegistry.SAKE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> SHOUCHU_BLOCK = BLOCKS.register("shouchu", () ->
        new LiquidBlock(FluidRegistry.SHOUCHU.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    
    public static final DeferredHolder<Block, LiquidBlock> BEER_BLOCK = BLOCKS.register("beer", () ->
        new LiquidBlock(FluidRegistry.BEER.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> WHISKEY_BLOCK = BLOCKS.register("whiskey", () ->
        new LiquidBlock(FluidRegistry.WHISKEY.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> RED_WINE_BLOCK = BLOCKS.register("red_wine", () ->
        new LiquidBlock(FluidRegistry.RED_WINE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> WHITE_WINE_BLOCK = BLOCKS.register("white_wine", () ->
        new LiquidBlock(FluidRegistry.WHITE_WINE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> BRANDY_BLOCK = BLOCKS.register("brandy", () ->
        new LiquidBlock(FluidRegistry.BRANDY.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> RUM_BLOCK = BLOCKS.register("rum", () ->
        new LiquidBlock(FluidRegistry.RUM.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));
    public static final DeferredHolder<Block, LiquidBlock> CHAMPAGNE_BLOCK = BLOCKS.register("champagne", () ->
        new LiquidBlock(FluidRegistry.CHAMPAGNE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)));

}
