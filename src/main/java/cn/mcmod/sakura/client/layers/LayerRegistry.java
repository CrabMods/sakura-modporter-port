package cn.mcmod.sakura.client.layers;

import cn.mcmod.sakura.SakuraMod;
import cn.mcmod.sakura.client.render.StoneMortarRenderer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.fml.common.Mod;
public class LayerRegistry {

    public static final ModelLayerLocation STONE_MORTAR = register("stone_mortar");

    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(STONE_MORTAR, StoneMortarRenderer::createLayer);
    }

    private static ModelLayerLocation register(String path) {
        return register(path, "main");
    }

    private static ModelLayerLocation register(String path, String part) {
        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(SakuraMod.MODID, path), part);
    }
}
