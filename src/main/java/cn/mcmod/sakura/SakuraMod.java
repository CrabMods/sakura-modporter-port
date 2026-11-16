package cn.mcmod.sakura;

import cn.mcmod.sakura.fluid.FluidTypeRegistry;
import cn.mcmod.sakura.item.CreativeModeTabRegistry;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import cn.mcmod.sakura.block.BlockItemRegistry;
import cn.mcmod.sakura.block.BlockRegistry;
import cn.mcmod.sakura.block.entity.BlockEntityRegistry;
import cn.mcmod.sakura.client.particle.ParticleRegistry;
import cn.mcmod.sakura.container.ContainerRegistry;
import cn.mcmod.sakura.fluid.BucketItemRegistry;
import cn.mcmod.sakura.fluid.FluidBlockRegistry;
import cn.mcmod.sakura.fluid.FluidRegistry;
import cn.mcmod.sakura.item.ComposterRegistry;
import cn.mcmod.sakura.item.FoodRegistry;
import cn.mcmod.sakura.item.ItemRegistry;
import cn.mcmod.sakura.loot_modifier.LootModifiterRegistry;
import cn.mcmod.sakura.recipes.RecipeTypeRegistry;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;
import cn.mcmod.sakura.block.entity.ChoppingBoardBlockEntity;
import cn.mcmod.sakura.block.entity.CookingPotBlockEntity;
import cn.mcmod.sakura.block.entity.DistillerBlockEntity;
import cn.mcmod.sakura.block.entity.FermenterBlockEntity;
import cn.mcmod.sakura.block.entity.ObonBlockEntity;
import cn.mcmod.sakura.block.entity.StoneMortarBlockEntity;







@Mod(SakuraMod.MODID)
public class SakuraMod {

    public static final String MODID = "sakura";

    private static final Logger LOGGER = LogUtils.getLogger();

    public static Item.Properties defaultItemProperties() {
        return new Item.Properties();
    }

    @SuppressWarnings("this-escape")
    public SakuraMod(ModContainer modContainer) {
        IEventBus modEventBus = modContainer.getEventBus();
        modEventBus.addListener(this::setup);
        BlockRegistry.BLOCKS.register(modEventBus);
        BlockItemRegistry.ITEMS.register(modEventBus);
        BlockEntityRegistry.BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(StoneMortarBlockEntity::registerCapabilities);
        modEventBus.addListener(ObonBlockEntity::registerCapabilities);
        modEventBus.addListener(FermenterBlockEntity::registerCapabilities);
        modEventBus.addListener(DistillerBlockEntity::registerCapabilities);
        modEventBus.addListener(CookingPotBlockEntity::registerCapabilities);
        modEventBus.addListener(ChoppingBoardBlockEntity::registerCapabilities);
        ItemRegistry.ITEMS.register(modEventBus);
        FoodRegistry.ITEMS.register(modEventBus);
        FluidRegistry.FLUIDS.register(modEventBus);
        FluidBlockRegistry.BLOCKS.register(modEventBus);
        FluidTypeRegistry.FLUID_TYPES.register(modEventBus);
        BucketItemRegistry.ITEMS.register(modEventBus);
        modEventBus.addListener(cn.mcmod.sakura.fluid.BucketItemRegistry::onAddCreativeModeTab);
        ParticleRegistry.PARTICLE_TYPES.register(modEventBus);
        ContainerRegistry.CONTAINER_TYPES.register(modEventBus);
        LootModifiterRegistry.GLM.register(modEventBus);
        RecipeTypeRegistry.RECIPE_TYPES.register(modEventBus);
        RecipeTypeRegistry.RECIPE_SERIALIZERS.register(modEventBus);
        CreativeModeTabRegistry.TABS.register(modEventBus);
        modEventBus.addListener(cn.mcmod.sakura.data.DataGen::dataGen);
        if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {
            modEventBus.addListener(cn.mcmod.sakura.client.layers.LayerRegistry::registerLayers);
        }
        if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {
            modEventBus.addListener(cn.mcmod.sakura.client.ClientEvents::onParticleFactoryRegistration);
        }
        if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {
            modEventBus.addListener(cn.mcmod.sakura.client.ClientEvents::clientStuff);
        }
        modContainer.registerConfig(ModConfig.Type.COMMON, SakuraConfig.COMMON_CONFIG);
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ComposterRegistry.registerCompost();
        });
    }

    public static Logger getLogger() {
        return LOGGER;
    }
}
