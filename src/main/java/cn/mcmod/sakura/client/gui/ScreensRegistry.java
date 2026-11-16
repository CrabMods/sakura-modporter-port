package cn.mcmod.sakura.client.gui;

import cn.mcmod.sakura.SakuraMod;
import cn.mcmod.sakura.container.ContainerRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;


@EventBusSubscriber(modid = SakuraMod.MODID, value = Dist.CLIENT)
public class ScreensRegistry {
    @SubscribeEvent
    public static void screenRegistry(final RegisterMenuScreensEvent event) {
            event.register(ContainerRegistry.STONE_MORTAR.get(), StoneMortarScreen::new);
            event.register(ContainerRegistry.COOKING_POT.get(), CookingPotScreen::new);
            event.register(ContainerRegistry.FERMENTER.get(), FermenterScreen::new);
            event.register(ContainerRegistry.DISTILLER.get(), DistillerScreen::new);
    }
}
