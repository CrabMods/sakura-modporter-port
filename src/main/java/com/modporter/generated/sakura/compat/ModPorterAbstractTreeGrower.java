package com.modporter.generated.sakura.compat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public abstract class ModPorterAbstractTreeGrower extends TreeGrower {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    protected ModPorterAbstractTreeGrower() {
        super("modporter_legacy_tree_" + NEXT_ID.getAndIncrement(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    protected abstract ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers);

    protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredMegaFeature(RandomSource random) {
        return null;
    }

    @Override
    public boolean growTree(ServerLevel level, ChunkGenerator chunkGenerator, BlockPos pos, BlockState state, RandomSource random) {
        ResourceKey<ConfiguredFeature<?, ?>> megaFeature = this.getConfiguredMegaFeature(random);
        if (megaFeature != null) {
            Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                    .registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .getHolder(megaFeature)
                    .orElse(null);
            var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
            holder = event.getFeature();
            if (event.isCanceled()) return false;
            if (holder != null) {
                for (int x = 0; x >= -1; x--) {
                    for (int z = 0; z >= -1; z--) {
                        if (isTwoByTwoSapling(state, level, pos, x, z)) {
                            ConfiguredFeature<?, ?> configuredFeature = holder.value();
                            BlockState air = Blocks.AIR.defaultBlockState();
                            level.setBlock(pos.offset(x, 0, z), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), air, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), air, 4);
                            if (configuredFeature.place(level, chunkGenerator, random, pos.offset(x, 0, z))) {
                                return true;
                            }
                            level.setBlock(pos.offset(x, 0, z), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), state, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), state, 4);
                            return false;
                        }
                    }
                }
            }
        }

        ResourceKey<ConfiguredFeature<?, ?>> feature = this.getConfiguredFeature(random, this.hasFlowers(level, pos));
        if (feature == null) return false;
        Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolder(feature)
                .orElse(null);
        var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
        holder = event.getFeature();
        if (event.isCanceled() || holder == null) return false;

        ConfiguredFeature<?, ?> configuredFeature = holder.value();
        BlockState fluidState = level.getFluidState(pos).createLegacyBlock();
        level.setBlock(pos, fluidState, 4);
        if (configuredFeature.place(level, chunkGenerator, random, pos)) {
            if (level.getBlockState(pos) == fluidState) {
                level.sendBlockUpdated(pos, state, fluidState, 2);
            }
            return true;
        }
        level.setBlock(pos, state, 4);
        return false;
    }

    private static boolean isTwoByTwoSapling(BlockState state, BlockGetter level, BlockPos pos, int xOffset, int zOffset) {
        Block block = state.getBlock();
        return level.getBlockState(pos.offset(xOffset, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset, 0, zOffset + 1)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset + 1)).is(block);
    }

    private boolean hasFlowers(LevelAccessor level, BlockPos pos) {
        for (BlockPos candidate : BlockPos.MutableBlockPos.betweenClosed(pos.below().north(2).west(2), pos.above().south(2).east(2))) {
            if (level.getBlockState(candidate).is(BlockTags.FLOWERS)) {
                return true;
            }
        }
        return false;
    }
}