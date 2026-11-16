package cn.mcmod.sakura.block.crops;

import net.minecraft.world.level.block.Block;

public class CropStandBlock extends Block {
    public static final com.mojang.serialization.MapCodec<CropStandBlock> CODEC = simpleCodec(CropStandBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.Block> codec() {
        return CODEC;
    }


    public CropStandBlock(Properties property) {
        super(property);
    }

}
