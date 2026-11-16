package cn.mcmod.sakura.data.client;

import cn.mcmod.sakura.block.BlockRegistry;
import cn.mcmod_mmf.mmlib.data.AbstractBlockStateProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;










public class SakuraBlockStateProvider extends AbstractBlockStateProvider {

    public SakuraBlockStateProvider(PackOutput packOutput, String modid, ExistingFileHelper exFileHelper) {
        super(packOutput, modid, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlock(BlockRegistry.SAKURA_LEAVES.get());
        simpleBlock(BlockRegistry.SAKURA_PLANK.get());
        simpleBlock(BlockRegistry.MAPLE_PLANK.get());
        simpleBlock(BlockRegistry.BAMBOO_PLANK.get());
        simpleBlock(BlockRegistry.STRAW_BLOCK.get());
        simpleBlock(BlockRegistry.MAPLE_LEAVES_RED.get());
        simpleBlock(BlockRegistry.MAPLE_LEAVES_YELLOW.get());
        simpleBlock(BlockRegistry.MAPLE_LEAVES_GREEN.get());
        simpleBlock(BlockRegistry.MAPLE_LEAVES_ORANGE.get());
        log(BlockRegistry.SAKURA_LOG::get);
        log(BlockRegistry.STRIPPED_SAKURA_LOG::get);
        log(BlockRegistry.MAPLE_LOG::get);
        log(BlockRegistry.STRIPPED_MAPLE_LOG::get);
        log(BlockRegistry.BAMBOO_BLOCK::get);
        log(BlockRegistry.BAMBOO_BLOCK_SUNBURNT::get);
        log(BlockRegistry.BAMBOO_CHARCOAL_BLOCK::get);
        horizontalBlock(BlockRegistry.FERMENTER.get(), models().getExistingFile(ResourceLocation.parse("sakura:block/fermenter")));
        crossBlock(BlockRegistry.SAKURA_SAPLING::get);
        crossBlock(BlockRegistry.MAPLE_SAPLING_RED::get);
        crossBlock(BlockRegistry.MAPLE_SAPLING_YELLOW::get);
        crossBlock(BlockRegistry.MAPLE_SAPLING_GREEN::get);
        crossBlock(BlockRegistry.MAPLE_SAPLING_ORANGE::get);
        stageBlock(BlockRegistry.BUCKWHEAT_CROP::get, BlockStateProperties.AGE_7);
        stageBlock(BlockRegistry.RAPESEED_CROP::get, BlockStateProperties.AGE_7);
        stageBlock(BlockRegistry.REDBEAN_CROP::get, BlockStateProperties.AGE_3);
        stageBlock(BlockRegistry.TARO_CROP::get, BlockStateProperties.AGE_3);
        horizontalBlock(BlockRegistry.TATAMI.get(), texture("tatami"), texture("tatami"), texture("tatami"));
        horizontalBlock(BlockRegistry.TATAMI_WAXED.get(), texture("tatami"), texture("tatami"), texture("tatami"));
        facingSlabBlock(BlockRegistry.TATAMI_SLAB_WAXED::get, texture("tatami"), texture("tatami"), texture("tatami"));
        horizontalBlock(BlockRegistry.TATAMI_SUNBURNT.get(), texture("tatami_tan"), texture("tatami_tan"), texture("tatami_tan"));
        facingSlabBlock(BlockRegistry.TATAMI_SLAB::get, texture("tatami"), texture("tatami"), texture("tatami"));
        facingSlabBlock(BlockRegistry.TATAMI_SLAB_SUNBURNT::get, texture("tatami_tan"), texture("tatami_tan"), texture("tatami_tan"));
    }


    private void log(Supplier<? extends RotatedPillarBlock> block) {
        logBlock(block.get());
    }

    private void stageBlock(Supplier<? extends Block> blockSupplier, IntegerProperty ageProperty, Property<?>... ignored) {
        Block block = blockSupplier.get();
        getVariantBuilder(block).forAllStates(state -> {
            int age = state.getValue(ageProperty);
            String modelName = name(block) + "_stage" + age;
            return ConfiguredModel.builder()
                    .modelFile(models().crop(modelName, texture(modelName)))
                    .build();
        });
    }

    private void facingSlabBlock(Supplier<? extends SlabBlock> slabSupplier, ResourceLocation side, ResourceLocation bottom, ResourceLocation top) {
        SlabBlock slab = slabSupplier.get();
        ModelFile bottomModel = models().slab(name(slab), side, bottom, top);
        ModelFile topModel = models().slabTop(name(slab) + "_top", side, bottom, top);
        ModelFile doubleModel = models().cubeBottomTop(name(slab) + "_double", side, bottom, top);
        getVariantBuilder(slab).forAllStates(state -> {
            SlabType type = state.getValue(SlabBlock.TYPE);
            ModelFile model = type == SlabType.DOUBLE ? doubleModel : type == SlabType.TOP ? topModel : bottomModel;
            int yRot = ((int) state.getValue(cn.mcmod_mmf.mmlib.block.FacingSlab.FACING).toYRot() + 180) % 360;
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationY(yRot)
                    .build();
        });
    }
}
