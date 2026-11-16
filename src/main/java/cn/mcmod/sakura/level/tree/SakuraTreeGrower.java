package cn.mcmod.sakura.level.tree;

import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import com.modporter.generated.sakura.compat.ModPorterAbstractTreeGrower;


public class SakuraTreeGrower extends ModPorterAbstractTreeGrower {

	@Override
	protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean pHasFlowers) {
        if (random.nextInt(10) == 0) {
            return SakuraTreeFeatures.FANCY_SAKURA_KEY;
        } else {
            return SakuraTreeFeatures.SAKURA_KEY;
        }
	}

}
