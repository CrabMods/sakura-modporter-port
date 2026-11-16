package com.modporter.validation;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(
    modid = ExactJarValidationMod.MOD_ID,
    value = Dist.CLIENT
)
public final class ClientValidationHarness {
    private static final String EMPTY = "<empty>";
    private static final List<String> REQUESTED = List.of(
        "target-jar-origin",
        "client-world-ready",
        "creative-inventory-rendered",
        "mod-creative-tabs-all-rows-rendered"
    );

    private static ValidationConfig config;
    private static ScenarioLedger ledger;
    private static ExactJarVerifier.Evidence targetEvidence;
    private static ExpectedContentManifest expectedContent;
    private static Map<String, Object> expectedContentManifestArtifact;
    private static Phase phase = Phase.VERIFY_JAR;
    private static long clientTick;
    private static int titleTicks;
    private static int readyTicks;
    private static int cleanupTicks;
    private static int worldLoadCount;
    private static int renderFrameCount;
    private static long firstCreativeRenderTick = -1;
    private static long stopRequestedAtTick = -1;
    private static boolean stopRequested;
    private static boolean overworldSavedAfterStop;
    private static boolean sealed;
    private static boolean screenshotMouseParked;
    private static boolean screenshotMouseRestored;
    private static double screenshotMouseOriginalX;
    private static double screenshotMouseOriginalY;
    private static int nativeMouseCallbackCount;

    private static List<CreativeModeTab> targetTabs = List.of();
    private static int targetTabIndex;
    private static String currentTabId = "";
    private static List<ExactStackSnapshot> currentExpectedStacks =
        List.of();
    private static List<ExactStackSnapshot> stableMenuStacks = List.of();
    private static int stableMenuTicks;
    private static int currentRow;
    private static int maxRow;
    private static long rowScrollSetAtTick;
    private static List<ExactStackSnapshot> baselinePlayerInventory =
        List.of();
    private static ExactStackSnapshot baselineClientCarried;
    private static int baselineSelectedSlot = -1;
    private static String baselineClientMenuClass = "";
    private static ServerPlayerState baselineServerState;
    private static ServerPlayerState cleanupFenceStartServerState;
    private static ServerPlayerState cleanupServerState;
    private static Map<String, Object> baselineClientStateEvidence;
    private static Map<String, Object> cleanupFenceStartClientStateEvidence;
    private static Map<String, Object> cleanupClientStateEvidence;
    private static CompletableFuture<ServerPlayerState> pendingServerState;
    private static long cleanupFenceStartClientTick = -1;
    private static final Set<ExactStackSnapshot> observedStacks =
        new HashSet<>();
    private static List<ResourceLocation> registeredTargetItemIds =
        List.of();
    private static final Map<ResourceLocation, ExactStackSnapshot>
        expectedDefaultStacks = new LinkedHashMap<>();
    private static final Set<ExactStackSnapshot> creativeTargetStacks =
        new LinkedHashSet<>();
    private static final Set<ExactStackSnapshot> observedTargetStacks =
        new LinkedHashSet<>();
    private static final Set<ExactStackSnapshot> iconEvidenceStacks =
        new LinkedHashSet<>();
    private static final Set<ExactStackSnapshot> iconExceptionStacks =
        new LinkedHashSet<>();
    private static final List<ExactStackSnapshot> stackEvidenceVariants =
        new ArrayList<>();
    private static final Map<String, Map<String, Object>>
        stackEvidence = new LinkedHashMap<>();
    private static int occupiedSlotCount;
    private static int occupiedSlotIconEvidenceCount;
    private static int occupiedSlotIconExceptionCount;
    private static int targetSlotCount;
    private static int targetSlotIconEvidenceCount;
    private static int targetSlotIconExceptionCount;
    private static final List<Map<String, Object>> tabTrace =
        new ArrayList<>();
    private static final List<Map<String, Object>> currentRowTrace =
        new ArrayList<>();
    private static final List<Map<String, Object>> screenshotArtifacts =
        new ArrayList<>();
    private static final List<Map<String, Object>>
        currentTabScreenshotArtifacts = new ArrayList<>();
    private static int expectedScreenshotCount;
    private static Map<String, Object> creativeTraceArtifact;

    private ClientValidationHarness() {
    }

    @SubscribeEvent
    public static synchronized void onClientTick(
            ClientTickEvent.Post event) {
        if (!clientLane() || sealed) {
            return;
        }
        clientTick++;
        try {
            ensureInitialized();
            if (clientTick > config.maxClientTicks() &&
                    phase != Phase.STOP_REQUESTED) {
                throw new IllegalStateException(
                    "Client validation timed out at tick " + clientTick
                        + " in phase " + phase);
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (phaseRequiresLoadedWorld()) {
                requireLoadedWorld(minecraft);
            }
            switch (phase) {
                case VERIFY_JAR -> verifyJar();
                case WAIT_FOR_TITLE -> waitForTitle(minecraft);
                case WAIT_FOR_WORLD -> waitForWorld(minecraft);
                case WAIT_FOR_SERVER_BASELINE ->
                    finishServerBaseline(minecraft);
                case OPEN_CREATIVE_SCREEN -> openCreativeScreen(minecraft);
                case DISCOVER_TABS -> discoverTabs(minecraft);
                case SELECT_TAB -> selectTab(minecraft);
                case WAIT_FOR_MENU_STABILITY ->
                    waitForMenuStability(minecraft);
                case SET_ROW -> setRow(minecraft);
                case CLEANUP -> finishCleanup(minecraft);
                case WAIT_FOR_SERVER_CLEANUP_FENCE_START ->
                    finishServerCleanupFenceStart(minecraft);
                case WAIT_FOR_SERVER_CLEANUP ->
                    finishServerCleanup(minecraft);
                case WAIT_FOR_FIRST_RENDER, WAIT_FOR_ROW_RENDER,
                     STOP_REQUESTED, FAILED -> {
                }
            }
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    @SubscribeEvent
    public static synchronized void onScreenRendered(
            ScreenEvent.Render.Post event) {
        if (!clientLane() || sealed ||
                !(event.getScreen()
                    instanceof CreativeModeInventoryScreen screen)) {
            return;
        }
        try {
            renderFrameCount++;
            requireLoadedWorld(Minecraft.getInstance());
            if (phase == Phase.WAIT_FOR_FIRST_RENDER) {
                if (Minecraft.getInstance().options.keyInventory.consumeClick()) {
                    throw new IllegalStateException(
                        "Inventory binding was not consumed by the native client input path");
                }
                firstCreativeRenderTick = clientTick;
                ledger.pass(
                    clientTick,
                    true,
                    List.of(),
                    Map.of(
                        "screenClass", screen.getClass().getName(),
                        "openedThroughInventoryBinding", true,
                        "renderPostObserved", true,
                        "renderTick", clientTick
                    )
                );
                ExactJarValidationMod.LOGGER.info(
                    "[ExactJarValidation] PASS creative-inventory-rendered");
                ledger.start(
                    "mod-creative-tabs-all-rows-rendered", clientTick);
                phase = Phase.DISCOVER_TABS;
            } else if (phase == Phase.WAIT_FOR_ROW_RENDER) {
                if (clientTick <= rowScrollSetAtTick) {
                    return;
                }
                event.getGuiGraphics().flush();
                if (!screenshotMouseParked
                        || Math.abs(Minecraft.getInstance().mouseHandler.xpos()) > 0.01
                        || Math.abs(Minecraft.getInstance().mouseHandler.ypos()) > 0.01) {
                    throw new IllegalStateException("Screenshot hover position was not parked outside the creative grid");
                }
                observeRenderedRow(screen);
            }
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        // openWorld waits for the integrated server while the client tick owns
        // the harness monitor. Server-level events must never acquire it.
        if (!(event.getLevel() instanceof ClientLevel)) {
            return;
        }
        synchronized (ClientValidationHarness.class) {
            if (!clientLane() || sealed) {
                return;
            }
            worldLoadCount++;
            if (worldLoadCount > 1) {
                fail(new IllegalStateException(
                    "More than one client world load was observed: "
                        + worldLoadCount));
            }
        }
    }

    @SubscribeEvent
    public static synchronized void onLevelSave(LevelEvent.Save event) {
        if (!clientLane() || !stopRequested ||
                !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (Level.OVERWORLD.equals(level.dimension())) {
            overworldSavedAfterStop = true;
        }
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(
            ServerStoppedEvent event) {
        if (!clientLane() || sealed || phase != Phase.STOP_REQUESTED) {
            return;
        }
        try {
            if (worldLoadCount != 1) {
                throw new IllegalStateException(
                    "Expected exactly one client world load, observed "
                        + worldLoadCount);
            }
            if (!overworldSavedAfterStop) {
                throw new IllegalStateException(
                    "No integrated-server overworld save was observed after the client stop request");
            }
            if (!Files.isRegularFile(config.clientWorldLevelDat())) {
                throw new IllegalStateException(
                    "Client world level.dat is missing: "
                        + config.clientWorldLevelDat());
            }
            if (creativeTraceArtifact == null) {
                throw new IllegalStateException(
                    "Creative render trace was not sealed before shutdown");
            }
            if (screenshotArtifacts.size() != expectedScreenshotCount) {
                throw new IllegalStateException(
                    "Expected " + expectedScreenshotCount
                        + " creative screenshots, observed "
                        + screenshotArtifacts.size());
            }
            if (expectedContentManifestArtifact == null) {
                throw new IllegalStateException(
                    "Expected-content manifest artifact was not sealed");
            }
            ReceiptSupport.verifyArtifact(
                expectedContentManifestArtifact);
            ReceiptSupport.verifyArtifact(creativeTraceArtifact);
            for (Map<String, Object> screenshot : screenshotArtifacts) {
                ReceiptSupport.verifyArtifact(screenshot);
            }
            Map<String, Object> levelDatArtifact =
                ReceiptSupport.copyImmutableArtifact(
                    config.clientWorldLevelDat(),
                    config.clientWorldEvidencePath()
                );

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("tabCount", targetTabs.size());
            details.put("renderFrameCount", renderFrameCount);
            details.put("firstCreativeRenderTick",
                firstCreativeRenderTick);
            details.put("screenClosed", true);
            details.put("cleanupFenceTicks", cleanupTicks);
            details.put("stopRequestedAtTick", stopRequestedAtTick);
            details.put("nativeOverworldSaveObserved", true);
            details.put("integratedServerStoppedEventObserved", true);
            details.put("screenshotCount", screenshotArtifacts.size());
            details.put("expectedRegisteredItemCount",
                expectedContent.expectedItemIds().size());
            details.put("creativeTargetStackCount",
                creativeTargetStacks.size());
            details.put("occupiedSlotCount", occupiedSlotCount);
            details.put("occupiedSlotIconEvidenceCount",
                occupiedSlotIconEvidenceCount);
            details.put("occupiedSlotIconExceptionCount",
                occupiedSlotIconExceptionCount);
            details.put("targetSlotCount", targetSlotCount);
            details.put("targetSlotIconEvidenceCount",
                targetSlotIconEvidenceCount);
            details.put("targetSlotIconExceptionCount",
                targetSlotIconExceptionCount);
            details.put("nativeStackEquality",
                "count+ItemStack.isSameItemSameComponents");
            details.put("integratedServerStateBaseline",
                baselineServerState.evidence());
            details.put("integratedServerCleanupFenceStart",
                cleanupFenceStartServerState.evidence());
            details.put("integratedServerStateCleanup",
                cleanupServerState.evidence());
            details.put("serverPlayerStateRestored", true);
            List<Map<String, Object>> artifacts = new ArrayList<>();
            artifacts.add(expectedContentManifestArtifact);
            artifacts.add(creativeTraceArtifact);
            artifacts.addAll(screenshotArtifacts);
            artifacts.add(levelDatArtifact);
            ledger.pass(
                clientTick,
                true,
                artifacts,
                details
            );
            ExactJarValidationMod.LOGGER.info(
                "[ExactJarValidation] PASS mod-creative-tabs-all-rows-rendered");

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("naturalExitRequested", true);
            extra.put("nativeOverworldSaveObserved", true);
            extra.put("integratedServerStoppedEventObserved", true);
            extra.put("renderFrameCount", renderFrameCount);
            extra.put("creativeTabCount", targetTabs.size());
            extra.put("screenshotCount", screenshotArtifacts.size());
            extra.put("expectedRegisteredItemCount",
                expectedContent.expectedItemIds().size());
            extra.put("creativeTargetStackCount",
                creativeTargetStacks.size());
            extra.put("occupiedSlotCount", occupiedSlotCount);
            extra.put("occupiedSlotIconEvidenceCount",
                occupiedSlotIconEvidenceCount);
            extra.put("occupiedSlotIconExceptionCount",
                occupiedSlotIconExceptionCount);
            extra.put("targetSlotCount", targetSlotCount);
            extra.put("targetSlotIconEvidenceCount",
                targetSlotIconEvidenceCount);
            extra.put("targetSlotIconExceptionCount",
                targetSlotIconExceptionCount);
            extra.put("nativeStackEquality",
                "count+ItemStack.isSameItemSameComponents");
            extra.put("serverPlayerStateRestored", true);
            extra.put("serverCleanupFenceTicks",
                cleanupServerState.serverTick()
                    - cleanupFenceStartServerState.serverTick());
            ReceiptSupport.writeImmutableJson(
                config.receiptPath(),
                ledger.receipt(
                    config,
                    worldLoadCount,
                    targetEvidence,
                    extra
                )
            );
            sealed = true;
            ExactJarValidationMod.LOGGER.info(
                "[ExactJarValidation] client receipt sealed: {}",
                config.receiptPath());
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private static void ensureInitialized() {
        if (config != null) {
            return;
        }
        config = ValidationConfig.load("client");
        ledger = new ScenarioLedger(REQUESTED);
    }

    private static void verifyJar() throws Exception {
        ledger.start("target-jar-origin", clientTick);
        targetEvidence = ExactJarVerifier.verify(config);
        expectedContent = ExpectedContentManifest.load(config);
        expectedContentManifestArtifact =
            ReceiptSupport.copyImmutableArtifact(
                config.expectedContentManifestPath(),
                config.expectedContentManifestEvidencePath()
            );
        expectedContentManifestArtifact.put(
            "kind", "expected-content-manifest");
        expectedContentManifestArtifact.put(
            "sourceAnchor", expectedContent.sourceAnchor());
        expectedContentManifestArtifact.put(
            "expectedRegisteredItemCount",
            expectedContent.expectedItemIds().size());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modListOwningFileMatched", true);
        details.put("expectedContentManifestMatched", true);
        details.put("expectedRegisteredItemCount",
            expectedContent.expectedItemIds().size());
        ledger.pass(
            clientTick,
            true,
            List.of(
                ReceiptSupport.artifact(config.expectedJarPath()),
                expectedContentManifestArtifact
            ),
            details
        );
        ExactJarValidationMod.LOGGER.info(
            "[ExactJarValidation] PASS target-jar-origin");
        ledger.start("client-world-ready", clientTick);
        phase = Phase.WAIT_FOR_TITLE;
    }

    private static void waitForTitle(Minecraft minecraft) {
        if (!(minecraft.screen instanceof TitleScreen)) {
            titleTicks = 0;
            return;
        }
        titleTicks++;
        if (titleTicks < 20) {
            return;
        }

        ExactJarValidationMod.LOGGER.info(
            "[ExactJarValidation] Opening owned validation world {}",
            config.worldName());
        minecraft.createWorldOpenFlows().openWorld(
            config.worldName(),
            () -> fail(new IllegalStateException(
                "Minecraft declined the owned validation world load"))
        );
        phase = Phase.WAIT_FOR_WORLD;
    }

    private static void waitForWorld(Minecraft minecraft) {
        boolean ready = minecraft.level != null &&
            minecraft.player != null &&
            minecraft.gameMode != null &&
            minecraft.gameMode.hasInfiniteItems() &&
            minecraft.screen == null;
        if (!ready) {
            readyTicks = 0;
            return;
        }
        readyTicks++;
        if (readyTicks < 20) {
            return;
        }

        if (worldLoadCount != 1) {
            throw new IllegalStateException(
                "Expected exactly one client world load before readiness, observed "
                    + worldLoadCount);
        }
        HolderLookup.Provider registries = minecraft.level.registryAccess();
        baselinePlayerInventory = playerInventorySnapshots(
            minecraft.player, registries);
        baselineClientCarried = captureStack(
            minecraft.player.containerMenu.getCarried(), registries);
        baselineSelectedSlot = minecraft.player.getInventory().selected;
        baselineClientMenuClass = minecraft.player.containerMenu
            .getClass().getName();
        if (minecraft.player.containerMenu !=
                minecraft.player.inventoryMenu) {
            throw new IllegalStateException(
                "Client player did not begin from the inventory menu");
        }
        baselineClientStateEvidence = clientStateEvidence(
            minecraft, baselinePlayerInventory, baselineClientCarried);
        requestServerState(minecraft, "baseline");
        phase = Phase.WAIT_FOR_SERVER_BASELINE;
    }

    private static void finishServerBaseline(Minecraft minecraft) {
        ServerPlayerState observed = completedServerState("baseline");
        if (observed == null) {
            return;
        }
        if (!observed.inventoryMenuActive()) {
            throw new IllegalStateException(
                "Integrated-server player did not begin from the inventory menu");
        }
        baselineServerState = observed;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("creativeMode", true);
        details.put("readyFenceTicks", readyTicks);
        details.put("worldName", config.worldName());
        details.put("observedClientWorldLoads", worldLoadCount);
        details.put("clientStateBaseline",
            baselineClientStateEvidence);
        details.put("integratedServerStateBaseline",
            baselineServerState.evidence());
        details.put("serverThreadBaselineCaptured", true);
        details.put("harnessOwnedWorldEffects",
            zeroOwnedWorldEffects());
        ledger.pass(
            clientTick,
            true,
            List.of(),
            details
        );
        ExactJarValidationMod.LOGGER.info(
            "[ExactJarValidation] PASS client-world-ready");
        ledger.start("creative-inventory-rendered", clientTick);
        phase = Phase.OPEN_CREATIVE_SCREEN;
    }

    private static void openCreativeScreen(Minecraft minecraft) {
        if (minecraft.screen != null) {
            throw new IllegalStateException(
                "A screen was already open before the inventory binding request");
        }
        KeyMapping.click(minecraft.options.keyInventory.getKey());
        phase = Phase.WAIT_FOR_FIRST_RENDER;
    }

    private static void discoverTabs(Minecraft minecraft) {
        requireCreativeScreen(minecraft);
        screenshotMouseOriginalX = minecraft.mouseHandler.xpos();
        screenshotMouseOriginalY = minecraft.mouseHandler.ypos();
        screenshotMouseParked = true;
        routeNativeMouseMove(minecraft, 0.0, 0.0);
        String modId = config.expectedModId();
        List<ResourceLocation> expectedIds =
            expectedContent.expectedItemIds();
        List<ResourceLocation> actualIds = BuiltInRegistries.ITEM.keySet()
            .stream()
            .filter(key -> modId.equals(key.getNamespace()))
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .toList();
        if (!actualIds.equals(expectedIds)) {
            Set<ResourceLocation> missing = new LinkedHashSet<>(expectedIds);
            missing.removeAll(actualIds);
            Set<ResourceLocation> unexpected = new LinkedHashSet<>(actualIds);
            unexpected.removeAll(expectedIds);
            throw new IllegalStateException(
                "Target registered-item set does not match pinned source manifest; missing="
                    + resourceLocationStrings(missing) + ", unexpected="
                    + resourceLocationStrings(unexpected));
        }
        if (actualIds.isEmpty()) {
            throw new IllegalStateException(
                "Pinned expected registered-item set was unexpectedly empty");
        }
        registeredTargetItemIds = List.copyOf(actualIds);

        HolderLookup.Provider registries = minecraft.level.registryAccess();
        expectedDefaultStacks.clear();
        for (ResourceLocation itemId : expectedIds) {
            ItemStack defaultStack = BuiltInRegistries.ITEM.get(itemId)
                .getDefaultInstance();
            ResourceLocation observedItemId = defaultStack.isEmpty()
                ? null
                : BuiltInRegistries.ITEM.getKey(defaultStack.getItem());
            if (!itemId.equals(observedItemId)) {
                throw new IllegalStateException(
                    "Registered item has no matching nonempty default stack: "
                        + itemId);
            }
            expectedDefaultStacks.put(
                itemId, captureStack(defaultStack, registries));
        }

        targetTabs = CreativeModeTabRegistry.getSortedCreativeModeTabs()
            .stream()
            .filter(CreativeModeTab::hasAnyItems)
            .filter(tab -> tabContainsNamespace(tab, modId))
            .toList();
        if (targetTabs.isEmpty()) {
            throw new IllegalStateException(
                "Pinned target items have no non-empty relevant creative tabs for namespace "
                    + modId);
        }

        creativeTargetStacks.clear();
        for (CreativeModeTab tab : targetTabs) {
            for (ItemStack stack : tab.getDisplayItems()) {
                ExactStackSnapshot snapshot = captureStack(
                    stack, registries);
                if (isTargetStack(snapshot)) {
                    creativeTargetStacks.add(snapshot);
                }
            }
        }
        Set<ExactStackSnapshot> missingDefaults =
            new LinkedHashSet<>(expectedDefaultStacks.values());
        missingDefaults.removeAll(creativeTargetStacks);
        if (!missingDefaults.isEmpty()) {
            throw new IllegalStateException(
                "Relevant creative tabs omit " + missingDefaults.size()
                    + " pinned registered default stacks: "
                    + sortedEvidence(missingDefaults));
        }

        targetTabIndex = 0;
        phase = Phase.SELECT_TAB;
    }

    private static void selectTab(Minecraft minecraft) {
        CreativeModeInventoryScreen screen =
            requireCreativeScreen(minecraft);
        CreativeModeTab tab = targetTabs.get(targetTabIndex);
        List<CreativeModeTab> sortedTabs =
            CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                .filter(CreativeModeTab::hasAnyItems)
                .toList();
        int tabIndex = sortedTabs.indexOf(tab);
        if (tabIndex < 0) {
            throw new IllegalStateException(
                "Target creative tab disappeared before selection: "
                    + tabId(tab));
        }

        int pageStart = (tabIndex / 10) * 10;
        int pageEnd = Math.min(pageStart + 10, sortedTabs.size());
        CreativeTabsScreenPage page = new CreativeTabsScreenPage(
            new ArrayList<>(sortedTabs.subList(pageStart, pageEnd)));
        screen.setCurrentPage(page);

        int tabX = tab.isAlignedRight()
            ? screen.getXSize() - 27 * (7 - page.getColumn(tab)) + 1
            : 27 * page.getColumn(tab);
        int tabY = page.isTop(tab) ? -32 : screen.getYSize();
        double mouseX = screen.getGuiLeft() + tabX + 13.0D;
        double mouseY = screen.getGuiTop() + tabY + 16.0D;
        boolean pressed = screen.mouseClicked(mouseX, mouseY, 0);
        boolean released = screen.mouseReleased(mouseX, mouseY, 0);
        if (!pressed || !released) {
            throw new IllegalStateException(
                "Creative tab screen input path did not handle selection for "
                    + tabId(tab));
        }

        currentTabId = tabId(tab);
        currentExpectedStacks = snapshots(
            tab.getDisplayItems(), minecraft.level.registryAccess());
        stableMenuStacks = List.of();
        stableMenuTicks = 0;
        observedStacks.clear();
        currentRowTrace.clear();
        currentTabScreenshotArtifacts.clear();
        phase = Phase.WAIT_FOR_MENU_STABILITY;
    }

    private static void waitForMenuStability(Minecraft minecraft) {
        CreativeModeInventoryScreen screen =
            requireCreativeScreen(minecraft);
        List<ExactStackSnapshot> current = snapshots(
            screen.getMenu().items, minecraft.level.registryAccess());
        if (!current.equals(stableMenuStacks)) {
            stableMenuStacks = current;
            stableMenuTicks = 0;
            return;
        }
        stableMenuTicks++;
        if (stableMenuTicks < 2) {
            return;
        }
        if (!current.equals(currentExpectedStacks)) {
            throw new IllegalStateException(
                "Selected creative tab menu does not match display items for "
                    + currentTabId);
        }

        int totalRows = Math.max(1, (currentExpectedStacks.size() + 8) / 9);
        maxRow = Math.max(0, totalRows - 5);
        currentRow = 0;
        phase = Phase.SET_ROW;
    }

    private static void setRow(Minecraft minecraft) {
        CreativeModeInventoryScreen screen =
            requireCreativeScreen(minecraft);
        if (currentRow > 0 && !screen.mouseScrolled(
                screen.width / 2.0, screen.height / 2.0, 0.0, -1.0)) {
            throw new IllegalStateException(
                "Native creative-screen scroll input was not accepted");
        }
        rowScrollSetAtTick = clientTick;
        phase = Phase.WAIT_FOR_ROW_RENDER;
    }

    private static void observeRenderedRow(
        CreativeModeInventoryScreen screen
    ) throws Exception {
        List<ExactStackSnapshot> expectedVisible =
            expectedVisibleRow(currentRow);
        List<ExactStackSnapshot> actualVisible =
            actualVisibleSlots(screen);
        if (!actualVisible.equals(expectedVisible)) {
            throw new IllegalStateException(
                "Rendered creative slots do not match expected row "
                    + currentRow + "/" + maxRow + " for " + currentTabId);
        }

        for (ExactStackSnapshot snapshot : actualVisible) {
            if (!snapshot.isEmpty()) {
                observedStacks.add(snapshot);
                if (isTargetStack(snapshot)) {
                    observedTargetStacks.add(snapshot);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row", currentRow);
        row.put("maxRow", maxRow);
        row.put("scrollSetAtTick", rowScrollSetAtTick);
        row.put("renderObservedAtTick", clientTick);
        row.put("tickFence", clientTick - rowScrollSetAtTick);
        row.put("renderPostObserved", true);
        row.put("scrollInputPath", currentRow == 0
            ? "native-tab-selection" : "screen.mouseScrolled");
        List<String> visibleEvidence = stackEvidenceStrings(
            actualVisible);
        row.put("visibleStacks", visibleEvidence);
        row.put(
            "visibleStacksSha256",
            Hashing.sha256Utf8(String.join("\n", visibleEvidence))
        );
        ScreenshotCapture screenshot =
            captureRenderedRowScreenshot(screen);
        row.put("screenshot", screenshot.artifact());
        row.put("occupiedSlotIconEvidence",
            screenshot.occupiedSlotEvidence());
        row.put("targetSlotIconEvidence",
            screenshot.targetSlotEvidence());
        currentTabScreenshotArtifacts.add(screenshot.artifact());
        screenshotArtifacts.add(screenshot.artifact());
        currentRowTrace.add(row);
        currentRow++;

        if (currentRow <= maxRow) {
            phase = Phase.SET_ROW;
            return;
        }

        Set<ExactStackSnapshot> expectedDistinct = new HashSet<>(
            currentExpectedStacks);
        expectedDistinct.removeIf(ExactStackSnapshot::isEmpty);
        if (!observedStacks.containsAll(expectedDistinct)) {
            Set<ExactStackSnapshot> missing = new HashSet<>(
                expectedDistinct);
            missing.removeAll(observedStacks);
            throw new IllegalStateException(
                "Rendered rows missed " + missing.size()
                    + " distinct target stacks in " + currentTabId);
        }

        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("tabId", currentTabId);
        tab.put("expectedStackCount", currentExpectedStacks.size());
        tab.put("expectedDistinctStackCount", expectedDistinct.size());
        tab.put("rowCount", maxRow + 1);
        tab.put("rows", new ArrayList<>(currentRowTrace));
        tab.put("screenshots",
            new ArrayList<>(currentTabScreenshotArtifacts));
        tabTrace.add(tab);
        expectedScreenshotCount += maxRow + 1;

        targetTabIndex++;
        if (targetTabIndex >= targetTabs.size()) {
            verifyCompleteTargetCoverage();
            beginCleanup(Minecraft.getInstance());
        } else {
            phase = Phase.SELECT_TAB;
        }
    }

    private static void beginCleanup(Minecraft minecraft) {
        if (minecraft.screen
                instanceof CreativeModeInventoryScreen screen) {
            resetCreativeScroll(screen);
            restoreScreenshotMouse(minecraft);
            screen.onClose();
        } else {
            throw new IllegalStateException(
                "Creative inventory screen was absent at cleanup");
        }
        cleanupTicks = 0;
        phase = Phase.CLEANUP;
    }

    private static void finishCleanup(Minecraft minecraft)
            throws Exception {
        if (!screenshotMouseRestored) {
            throw new IllegalStateException("Creative screenshot mouse state was not restored");
        }
        if (minecraft.screen != null) {
            throw new IllegalStateException(
                "Creative inventory screen remained open during cleanup");
        }
        if (minecraft.options.keyInventory.isDown() ||
                minecraft.options.keyInventory.consumeClick()) {
            throw new IllegalStateException(
                "Inventory input was not reset during cleanup");
        }
        if (minecraft.player.containerMenu !=
                minecraft.player.inventoryMenu) {
            throw new IllegalStateException(
                "Native creative-screen close did not restore the player inventory menu");
        }
        HolderLookup.Provider registries = minecraft.level.registryAccess();
        List<ExactStackSnapshot> currentInventory =
            playerInventorySnapshots(minecraft.player, registries);
        if (!currentInventory.equals(baselinePlayerInventory)) {
            throw new IllegalStateException(
                "Creative browse changed the client player inventory baseline");
        }
        ExactStackSnapshot currentCarried = captureStack(
            minecraft.player.containerMenu.getCarried(), registries);
        if (!currentCarried.equals(baselineClientCarried)) {
            throw new IllegalStateException(
                "Creative browse changed the client carried-stack baseline");
        }
        if (!minecraft.player.containerMenu.getClass().getName().equals(
                baselineClientMenuClass)) {
            throw new IllegalStateException(
                "Creative browse changed the client menu-class baseline");
        }
        if (minecraft.player.getInventory().selected !=
                baselineSelectedSlot) {
            throw new IllegalStateException(
                "Creative browse changed the selected hotbar slot");
        }
        cleanupTicks++;
        if (cleanupTicks < 2) {
            return;
        }

        cleanupFenceStartClientStateEvidence = clientStateEvidence(
            minecraft, currentInventory, currentCarried);
        requestServerState(minecraft, "cleanup-fence-start");
        phase = Phase.WAIT_FOR_SERVER_CLEANUP_FENCE_START;
    }

    private static void finishServerCleanupFenceStart(
        Minecraft minecraft
    ) {
        ServerPlayerState observed = completedServerState(
            "cleanup-fence-start");
        if (observed == null) {
            return;
        }
        cleanupFenceStartServerState = observed;
        cleanupFenceStartClientTick = clientTick;
        requestServerState(minecraft, "cleanup-final");
        phase = Phase.WAIT_FOR_SERVER_CLEANUP;
    }

    private static void finishServerCleanup(Minecraft minecraft)
            throws Exception {
        if (clientTick <= cleanupFenceStartClientTick) {
            return;
        }
        ServerPlayerState observed = completedServerState(
            "cleanup-final");
        if (observed == null) {
            return;
        }
        if (observed.serverTick() <=
                cleanupFenceStartServerState.serverTick()) {
            requestServerState(minecraft, "cleanup-final");
            return;
        }
        cleanupServerState = observed;
        if (!baselineServerState.exactStateEquals(cleanupServerState)) {
            throw new IllegalStateException(
                "Creative browse changed integrated-server player state; baseline="
                    + baselineServerState.evidence() + ", cleanup="
                    + cleanupServerState.evidence());
        }
        cleanupClientStateEvidence = requireClientStateRestored(
            minecraft, "final-server-fence");
        sealCreativeTraceAndStop(minecraft);
    }

    private static void sealCreativeTraceAndStop(
        Minecraft minecraft
    ) throws Exception {
        verifyCompleteTargetCoverage();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schemaVersion", 3);
        trace.put("sessionId", config.sessionId());
        trace.put("targetModId", config.expectedModId());
        trace.put("expectedContentManifest",
            expectedContentManifestArtifact);
        trace.put("sourceAnchor", expectedContent.sourceAnchor());
        trace.put("expectedRegisteredItemIds",
            expectedContent.expectedItemIdStrings());
        trace.put("observedRegisteredItemIds",
            resourceLocationStrings(registeredTargetItemIds));
        trace.put("registeredItemSetExactMatch", true);
        trace.put("nativeStackEquality",
            "count+ItemStack.isSameItemSameComponents");
        trace.put("nativeStackBucketHashRole",
            "hash-only; every bucket match is followed by native exact equality");
        trace.put("stackEvidence", stackEvidenceRecords());
        trace.put("expectedDefaultStackEvidenceIds",
            expectedDefaultStackEvidence());
        trace.put("creativeTargetStackEvidenceIds",
            sortedEvidence(creativeTargetStacks));
        trace.put("observedTargetStackEvidenceIds",
            sortedEvidence(observedTargetStacks));
        trace.put("iconEvidenceStackEvidenceIds",
            sortedEvidence(iconEvidenceStacks));
        trace.put("iconExceptionStackEvidenceIds",
            sortedEvidence(iconExceptionStacks));
        trace.put("occupiedSlotCount", occupiedSlotCount);
        trace.put("occupiedSlotIconEvidenceCount",
            occupiedSlotIconEvidenceCount);
        trace.put("occupiedSlotIconExceptionCount",
            occupiedSlotIconExceptionCount);
        trace.put("occupiedSlotCoverageExact",
            occupiedSlotCount == occupiedSlotIconEvidenceCount
                + occupiedSlotIconExceptionCount);
        trace.put("targetSlotCount", targetSlotCount);
        trace.put("targetSlotIconEvidenceCount",
            targetSlotIconEvidenceCount);
        trace.put("targetSlotIconExceptionCount",
            targetSlotIconExceptionCount);
        trace.put("targetSlotCoverageExact",
            targetSlotCount == targetSlotIconEvidenceCount
                + targetSlotIconExceptionCount);
        trace.put("clientStateBaseline", baselineClientStateEvidence);
        trace.put("clientCleanupFenceStart",
            cleanupFenceStartClientStateEvidence);
        trace.put("clientStateCleanup", cleanupClientStateEvidence);
        trace.put("integratedServerStateBaseline",
            baselineServerState.evidence());
        trace.put("integratedServerCleanupFenceStart",
            cleanupFenceStartServerState.evidence());
        trace.put("integratedServerStateCleanup",
            cleanupServerState.evidence());
        trace.put("clientCleanupFenceTicks", cleanupTicks);
        trace.put("serverCleanupFenceTicks",
            cleanupServerState.serverTick()
                - cleanupFenceStartServerState.serverTick());
        trace.put("serverPlayerStateRestored", true);
        trace.put("harnessOwnedWorldEffects",
            zeroOwnedWorldEffects());
        trace.put("screenClass",
            CreativeModeInventoryScreen.class.getName());
        trace.put("firstCreativeRenderTick",
            firstCreativeRenderTick);
        trace.put("renderFrameCount", renderFrameCount);
        trace.put("tabs", new ArrayList<>(tabTrace));
        trace.put("screenshotCount", screenshotArtifacts.size());
        trace.put("screenshots", new ArrayList<>(screenshotArtifacts));
        trace.put("screenClosed", true);
        trace.put("nativeScreenCloseObserved", true);
        trace.put("mousePositionRestored", screenshotMouseRestored);
        trace.put("nativeMouseCallbackCount", nativeMouseCallbackCount);
        trace.put("inventoryBaselineRestored", true);
        trace.put("cleanupFenceTicks", cleanupTicks);
        ReceiptSupport.writeImmutableJson(
            config.creativeTracePath(), trace);
        creativeTraceArtifact = ReceiptSupport.artifact(
            config.creativeTracePath());

        stopRequested = true;
        stopRequestedAtTick = clientTick;
        phase = Phase.STOP_REQUESTED;
        ExactJarValidationMod.LOGGER.info(
            "[ExactJarValidation] Creative browse complete; requesting natural client exit at tick {}",
            clientTick);
        minecraft.stop();
    }

    private static void resetCreativeScroll(
            CreativeModeInventoryScreen screen) {
        screen.mouseScrolled(
            screen.width / 2.0, screen.height / 2.0,
            0.0, Math.max(1, maxRow + 1));
    }

    private static void routeNativeMouseMove(Minecraft minecraft, double x, double y) {
        try {
            var callback = minecraft.mouseHandler.getClass().getDeclaredMethod(
                "onMove", long.class, double.class, double.class);
            callback.setAccessible(true);
            callback.invoke(minecraft.mouseHandler, minecraft.getWindow().getWindow(), x, y);
            nativeMouseCallbackCount++;
            if (Math.abs(minecraft.mouseHandler.xpos() - x) > 0.01
                    || Math.abs(minecraft.mouseHandler.ypos() - y) > 0.01) {
                throw new IllegalStateException("Native mouse callback did not accept the owned UI position");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot route the owned UI mouse callback", exception);
        }
    }

    private static void restoreScreenshotMouse(Minecraft minecraft) {
        if (screenshotMouseParked) {
            requireCreativeScreen(minecraft);
            routeNativeMouseMove(minecraft, screenshotMouseOriginalX, screenshotMouseOriginalY);
            screenshotMouseParked = false;
            screenshotMouseRestored = true;
        }
    }

    private static CreativeModeInventoryScreen requireCreativeScreen(
        Minecraft minecraft
    ) {
        if (!(minecraft.screen
                instanceof CreativeModeInventoryScreen screen)) {
            throw new IllegalStateException(
                "Creative inventory screen closed unexpectedly in phase "
                    + phase);
        }
        return screen;
    }

    private static List<ExactStackSnapshot> expectedVisibleRow(
        int row
    ) {
        List<ExactStackSnapshot> expected = new ArrayList<>(45);
        int firstIndex = row * 9;
        for (int offset = 0; offset < 45; offset++) {
            int index = firstIndex + offset;
            expected.add(index < currentExpectedStacks.size()
                ? currentExpectedStacks.get(index)
                : captureStack(ItemStack.EMPTY,
                    Minecraft.getInstance().level.registryAccess()));
        }
        return expected;
    }

    private static List<ExactStackSnapshot> actualVisibleSlots(
        CreativeModeInventoryScreen screen
    ) {
        List<ExactStackSnapshot> actual = new ArrayList<>(45);
        HolderLookup.Provider registries = Minecraft.getInstance()
            .level.registryAccess();
        for (int slot = 0; slot < 45; slot++) {
            actual.add(captureStack(
                slot < screen.getMenu().slots.size()
                    ? screen.getMenu().getSlot(slot).getItem()
                    : ItemStack.EMPTY,
                registries));
        }
        return actual;
    }

    private static ScreenshotCapture captureRenderedRowScreenshot(
        CreativeModeInventoryScreen screen
    ) throws Exception {
        boolean first = currentRow == 0;
        boolean last = currentRow == maxRow;
        String role = first && last
            ? "first-final"
            : first ? "first" : last ? "final" : "row";
        String safeTabId = currentTabId.replaceAll(
            "[^A-Za-z0-9._-]", "_");
        if (safeTabId.length() > 80) {
            safeTabId = safeTabId.substring(0, 80);
        }
        String fileName = String.format(
            Locale.ROOT,
            "%03d-%s-row-%03d-%s.png",
            targetTabIndex,
            safeTabId,
            currentRow,
            role
        );
        Path destination = config.screenshotDirectory()
            .resolve(fileName).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            throw new IllegalStateException(
                "Immutable screenshot already exists: " + destination);
        }
        Path temporary = destination.resolveSibling(
            destination.getFileName() + "." + UUID.randomUUID()
                + ".tmp");
        Minecraft minecraft = Minecraft.getInstance();
        int width;
        int height;
        ScreenshotInspection inspection;
        try {
            try (NativeImage image = Screenshot.takeScreenshot(
                    minecraft.getMainRenderTarget())) {
                width = image.getWidth();
                height = image.getHeight();
                image.writeToFile(temporary);
            }
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            inspection = inspectDecodedPng(
                destination,
                screen,
                width,
                height,
                minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height
            );
        } finally {
            Files.deleteIfExists(temporary);
        }

        Map<String, Object> artifact =
            ReceiptSupport.artifact(destination);
        artifact.put("kind", "creative-row-screenshot");
        artifact.put("guiDrawCommandsFlushed", true);
        artifact.put("mouseHoverOutsideGrid", true);
        artifact.put("tabId", currentTabId);
        artifact.put("row", currentRow);
        artifact.put("maxRow", maxRow);
        List<String> roles = new ArrayList<>();
        roles.add("row");
        if (first) {
            roles.add("first");
        }
        if (last) {
            roles.add("final");
        }
        artifact.put("roles", roles);
        artifact.put("renderObservedAtTick", clientTick);
        artifact.put("width", width);
        artifact.put("height", height);
        artifact.put("decodedPng", inspection.decodedImage());
        artifact.put("occupiedSlotCount",
            inspection.occupiedSlotCount());
        artifact.put("occupiedSlotIconEvidenceCount",
            inspection.occupiedSlotIconEvidenceCount());
        artifact.put("occupiedSlotIconExceptionCount",
            inspection.occupiedSlotIconExceptionCount());
        artifact.put("targetSlotCount",
            inspection.targetSlotCount());
        artifact.put("targetSlotIconEvidenceCount",
            inspection.targetSlotIconEvidenceCount());
        artifact.put("targetSlotIconExceptionCount",
            inspection.targetSlotIconExceptionCount());
        return new ScreenshotCapture(
            artifact,
            inspection.occupiedSlotEvidence(),
            inspection.targetSlotEvidence()
        );
    }

    private static ScreenshotInspection inspectDecodedPng(
        Path screenshot,
        CreativeModeInventoryScreen screen,
        int capturedWidth,
        int capturedHeight,
        int renderTargetWidth,
        int renderTargetHeight
    ) throws Exception {
        try (var input = Files.newInputStream(screenshot);
                NativeImage image = NativeImage.read(input)) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0
                    || width != capturedWidth
                    || height != capturedHeight
                    || width != renderTargetWidth
                    || height != renderTargetHeight) {
                throw new IllegalStateException(
                    "Decoded creative screenshot dimensions do not match the render target: decoded="
                        + width + "x" + height + ", captured="
                        + capturedWidth + "x" + capturedHeight
                        + ", renderTarget=" + renderTargetWidth + "x"
                        + renderTargetHeight);
            }
            if (screen.width <= 0 || screen.height <= 0) {
                throw new IllegalStateException(
                    "Creative screen has invalid logical dimensions: "
                        + screen.width + "x" + screen.height);
            }

            Set<Integer> imageRgb = new HashSet<>();
            long nonTransparentPixels = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgba = image.getPixelRGBA(x, y);
                    if ((rgba >>> 24) != 0) {
                        nonTransparentPixels++;
                    }
                    imageRgb.add(rgb(rgba));
                }
            }
            if (nonTransparentPixels == 0 || imageRgb.size() < 2) {
                throw new IllegalStateException(
                    "Decoded creative screenshot has no meaningful visual content: "
                        + screenshot);
            }

            List<Map<String, Object>> occupiedSlotEvidence =
                new ArrayList<>();
            List<Map<String, Object>> targetSlotEvidence =
                new ArrayList<>();
            int screenshotOccupiedSlots = 0;
            int screenshotOccupiedIconEvidence = 0;
            int screenshotOccupiedIconExceptions = 0;
            int screenshotTargetSlots = 0;
            int screenshotTargetIconEvidence = 0;
            int screenshotTargetIconExceptions = 0;
            int slotCount = Math.min(
                45, screen.getMenu().slots.size());
            HolderLookup.Provider registries = Minecraft.getInstance()
                .level.registryAccess();
            for (int slotIndex = 0; slotIndex < slotCount;
                    slotIndex++) {
                ItemStack stack = screen.getMenu().getSlot(slotIndex)
                    .getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                ExactStackSnapshot snapshot = captureStack(
                    stack, registries);
                ResourceLocation itemId = snapshot.itemId();
                String evidenceId = stackEvidenceId(snapshot);
                boolean targetStack = isTargetStack(snapshot);
                screenshotOccupiedSlots++;
                occupiedSlotCount++;
                if (targetStack) {
                    screenshotTargetSlots++;
                    targetSlotCount++;
                }
                var slot = screen.getMenu().getSlot(slotIndex);
                int left = clamp(
                    (int) Math.floor(
                        (screen.getGuiLeft() + slot.x)
                            * (double) width / screen.width),
                    0,
                    width
                );
                int top = clamp(
                    (int) Math.floor(
                        (screen.getGuiTop() + slot.y)
                            * (double) height / screen.height),
                    0,
                    height
                );
                int right = clamp(
                    (int) Math.ceil(
                        (screen.getGuiLeft() + slot.x + 16)
                            * (double) width / screen.width),
                    0,
                    width
                );
                int bottom = clamp(
                    (int) Math.ceil(
                        (screen.getGuiTop() + slot.y + 16)
                            * (double) height / screen.height),
                    0,
                    height
                );
                if (right <= left || bottom <= top) {
                    throw new IllegalStateException(
                        "Occupied creative slot mapped outside the decoded screenshot: "
                            + itemId + " slot=" + slotIndex);
                }

                Map<String, Object> evidence = analyzeIconCrop(
                    image, left, top, right, bottom);
                evidence.put("slotIndex", slotIndex);
                evidence.put("itemId", itemId.toString());
                evidence.put("stackEvidenceId", evidenceId);
                evidence.put("targetStack", targetStack);
                evidence.put("pixelBounds", Map.of(
                    "left", left,
                    "top", top,
                    "rightExclusive", right,
                    "bottomExclusive", bottom
                ));
                boolean nonBlankIcon =
                    (Boolean) evidence.get("nonBlankIcon");
                if (nonBlankIcon) {
                    screenshotOccupiedIconEvidence++;
                    occupiedSlotIconEvidenceCount++;
                    if (targetStack) {
                        iconEvidenceStacks.add(snapshot);
                        screenshotTargetIconEvidence++;
                        targetSlotIconEvidenceCount++;
                    }
                    evidence.put("coverage", "decoded-nonblank-icon");
                } else if (targetStack &&
                        expectedContent.isIconEvidenceException(itemId)) {
                    iconExceptionStacks.add(snapshot);
                    screenshotOccupiedIconExceptions++;
                    occupiedSlotIconExceptionCount++;
                    screenshotTargetIconExceptions++;
                    targetSlotIconExceptionCount++;
                    evidence.put("coverage", "source-backed-exception");
                    evidence.put("sourceBackedException",
                        expectedContent.iconEvidenceException(itemId));
                } else {
                    Map<String, Object> failedImage = ReceiptSupport.artifact(screenshot);
                    failedImage.put("kind", "failed-creative-row-screenshot");
                    screenshotArtifacts.add(failedImage);
                    ReceiptSupport.writeImmutableJson(
                        screenshot.resolveSibling(screenshot.getFileName() + ".failure.json"),
                        Map.of("image", failedImage, "slot", evidence));
                    throw new IllegalStateException(
                        "Decoded screenshot lacks nonblank icon evidence for occupied slot "
                            + itemId + " evidenceId=" + evidenceId
                            + " targetStack=" + targetStack + " tab="
                            + currentTabId + " row=" + currentRow
                            + " slot=" + slotIndex);
                }
                occupiedSlotEvidence.add(evidence);
                if (targetStack) {
                    targetSlotEvidence.add(evidence);
                }
            }
            if (screenshotOccupiedSlots !=
                    screenshotOccupiedIconEvidence
                        + screenshotOccupiedIconExceptions) {
                throw new IllegalStateException(
                    "Occupied creative-slot icon accounting is incomplete for "
                        + currentTabId + " row=" + currentRow);
            }
            if (screenshotTargetSlots != screenshotTargetIconEvidence
                    + screenshotTargetIconExceptions) {
                throw new IllegalStateException(
                    "Target creative-slot icon accounting is incomplete for "
                        + currentTabId + " row=" + currentRow);
            }

            Map<String, Object> decodedImage = new LinkedHashMap<>();
            decodedImage.put("width", width);
            decodedImage.put("height", height);
            decodedImage.put("logicalScreenWidth", screen.width);
            decodedImage.put("logicalScreenHeight", screen.height);
            decodedImage.put("nonTransparentPixelCount",
                nonTransparentPixels);
            decodedImage.put("distinctRgbCount", imageRgb.size());
            decodedImage.put("fullyDecoded", true);
            return new ScreenshotInspection(
                decodedImage,
                List.copyOf(occupiedSlotEvidence),
                List.copyOf(targetSlotEvidence),
                screenshotOccupiedSlots,
                screenshotOccupiedIconEvidence,
                screenshotOccupiedIconExceptions,
                screenshotTargetSlots,
                screenshotTargetIconEvidence,
                screenshotTargetIconExceptions
            );
        }
    }

    private static Map<String, Object> analyzeIconCrop(
        NativeImage image,
        int left,
        int top,
        int right,
        int bottom
    ) {
        Map<Integer, Integer> frequencies = new LinkedHashMap<>();
        int nonTransparentPixels = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int rgba = image.getPixelRGBA(x, y);
                if ((rgba >>> 24) != 0) {
                    nonTransparentPixels++;
                }
                int rgb = rgb(rgba);
                frequencies.merge(rgb, 1, Integer::sum);
            }
        }
        int dominantRgb = frequencies.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElseThrow()
            .getKey();
        int variedPixelCount = 0;
        int maxDistanceSquared = 0;
        int distanceThresholdSquared = 24 * 24;
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            int distanceSquared = rgbDistanceSquared(
                dominantRgb, entry.getKey());
            maxDistanceSquared = Math.max(
                maxDistanceSquared, distanceSquared);
            if (distanceSquared >= distanceThresholdSquared) {
                variedPixelCount += entry.getValue();
            }
        }
        int pixelCount = (right - left) * (bottom - top);
        int requiredVariedPixelCount = Math.max(6, pixelCount / 64);
        boolean nonBlankIcon = nonTransparentPixels > 0
            && frequencies.size() >= 2
            && variedPixelCount >= requiredVariedPixelCount;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pixelCount", pixelCount);
        evidence.put("nonTransparentPixelCount", nonTransparentPixels);
        evidence.put("distinctRgbCount", frequencies.size());
        evidence.put("dominantRgb", String.format(
            Locale.ROOT, "%06x", dominantRgb));
        evidence.put("pixelsBeyondDominantRgbThreshold",
            variedPixelCount);
        evidence.put("requiredPixelsBeyondThreshold",
            requiredVariedPixelCount);
        evidence.put("rgbDistanceThreshold", 24);
        evidence.put("maxRgbDistance", Math.sqrt(maxDistanceSquared));
        evidence.put("nonBlankIcon", nonBlankIcon);
        return evidence;
    }

    private static int rgb(int rgba) {
        int red = rgba & 0xff;
        int green = (rgba >>> 8) & 0xff;
        int blue = (rgba >>> 16) & 0xff;
        return (red << 16) | (green << 8) | blue;
    }

    private static int rgbDistanceSquared(int left, int right) {
        int red = ((left >>> 16) & 0xff) - ((right >>> 16) & 0xff);
        int green = ((left >>> 8) & 0xff) - ((right >>> 8) & 0xff);
        int blue = (left & 0xff) - (right & 0xff);
        return red * red + green * green + blue * blue;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record ScreenshotCapture(
        Map<String, Object> artifact,
        List<Map<String, Object>> occupiedSlotEvidence,
        List<Map<String, Object>> targetSlotEvidence
    ) {
    }

    private record ScreenshotInspection(
        Map<String, Object> decodedImage,
        List<Map<String, Object>> occupiedSlotEvidence,
        List<Map<String, Object>> targetSlotEvidence,
        int occupiedSlotCount,
        int occupiedSlotIconEvidenceCount,
        int occupiedSlotIconExceptionCount,
        int targetSlotCount,
        int targetSlotIconEvidenceCount,
        int targetSlotIconExceptionCount
    ) {
    }

    private static ExactStackSnapshot captureStack(
        ItemStack stack,
        HolderLookup.Provider registries
    ) {
        ExactStackSnapshot snapshot = ExactStackSnapshot.capture(
            stack, registries);
        registerStackEvidence(snapshot);
        return snapshot;
    }

    private static List<ExactStackSnapshot> snapshots(
        Iterable<ItemStack> stacks,
        HolderLookup.Provider registries
    ) {
        List<ExactStackSnapshot> values = new ArrayList<>();
        for (ItemStack stack : stacks) {
            values.add(captureStack(stack, registries));
        }
        return List.copyOf(values);
    }

    private static List<ExactStackSnapshot> playerInventorySnapshots(
        Player player,
        HolderLookup.Provider registries
    ) {
        Inventory inventory = player.getInventory();
        List<ExactStackSnapshot> values = new ArrayList<>(
            inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            values.add(captureStack(inventory.getItem(slot), registries));
        }
        return List.copyOf(values);
    }

    private static void registerStackEvidence(
        ExactStackSnapshot snapshot
    ) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ExactStackSnapshot observed : stackEvidenceVariants) {
            if (observed.equals(snapshot)) {
                return;
            }
        }

        stackEvidenceVariants.add(snapshot);
        String evidenceId = String.format(
            Locale.ROOT, "stack-%04d", stackEvidenceVariants.size());
        Map<String, Object> record = snapshot.evidenceRecord();
        record.put("evidenceId", evidenceId);
        record.put("nativeVariantOrdinal", stackEvidenceVariants.size());
        stackEvidence.put(evidenceId, record);
        refreshPortableFingerprintCollisions(snapshot.evidence());
    }

    private static void refreshPortableFingerprintCollisions(
        String portableFingerprint
    ) {
        List<String> matchingIds = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry :
                stackEvidence.entrySet()) {
            if (portableFingerprint.equals(
                    entry.getValue().get("evidenceFingerprint"))) {
                matchingIds.add(entry.getKey());
            }
        }
        boolean collision = matchingIds.size() > 1;
        for (String evidenceId : matchingIds) {
            Map<String, Object> record = stackEvidence.get(evidenceId);
            record.put("portableFingerprintCollision", collision);
            record.put("nativeVariantsSharingPortableFingerprint",
                List.copyOf(matchingIds));
        }
    }

    private static String stackEvidenceId(
        ExactStackSnapshot snapshot
    ) {
        if (snapshot.isEmpty()) {
            return EMPTY;
        }
        registerStackEvidence(snapshot);
        for (int index = 0; index < stackEvidenceVariants.size(); index++) {
            if (stackEvidenceVariants.get(index).equals(snapshot)) {
                return String.format(
                    Locale.ROOT, "stack-%04d", index + 1);
            }
        }
        throw new IllegalStateException(
            "Native stack evidence registration failed for " + snapshot);
    }

    private static Map<String, Map<String, Object>>
            stackEvidenceRecords() {
        Map<String, Map<String, Object>> records = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry :
                stackEvidence.entrySet()) {
            records.put(entry.getKey(),
                new LinkedHashMap<>(entry.getValue()));
        }
        return records;
    }

    private static List<String> stackEvidenceStrings(
        Collection<ExactStackSnapshot> values
    ) {
        List<String> evidenceIds = new ArrayList<>(values.size());
        for (ExactStackSnapshot value : values) {
            evidenceIds.add(stackEvidenceId(value));
        }
        return List.copyOf(evidenceIds);
    }

    private static List<String> sortedEvidence(
        Collection<ExactStackSnapshot> values
    ) {
        return values.stream()
            .map(ClientValidationHarness::stackEvidenceId)
            .sorted()
            .toList();
    }

    private static Map<String, String> expectedDefaultStackEvidence() {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ExactStackSnapshot> entry :
                expectedDefaultStacks.entrySet()) {
            evidence.put(entry.getKey().toString(),
                stackEvidenceId(entry.getValue()));
        }
        return evidence;
    }

    private static void verifyCompleteTargetCoverage() {
        Set<ExactStackSnapshot> missingRendered = new LinkedHashSet<>(
            creativeTargetStacks);
        missingRendered.removeAll(observedTargetStacks);
        if (!missingRendered.isEmpty()) {
            throw new IllegalStateException(
                "Native creative row traversal missed "
                    + missingRendered.size()
                    + " current target stack variants: "
                    + sortedEvidence(missingRendered));
        }

        Set<ExactStackSnapshot> requiredIconCoverage =
            new LinkedHashSet<>(expectedDefaultStacks.values());
        requiredIconCoverage.addAll(creativeTargetStacks);
        Set<ExactStackSnapshot> coveredIcons = new LinkedHashSet<>(
            iconEvidenceStacks);
        coveredIcons.addAll(iconExceptionStacks);
        Set<ExactStackSnapshot> missingIconCoverage =
            new LinkedHashSet<>(requiredIconCoverage);
        missingIconCoverage.removeAll(coveredIcons);
        if (!missingIconCoverage.isEmpty()) {
            throw new IllegalStateException(
                "Decoded screenshots lack icon evidence or a source-backed exception for "
                    + missingIconCoverage.size() + " target stacks: "
                    + sortedEvidence(missingIconCoverage));
        }
        if (occupiedSlotCount <= 0 || targetSlotCount <= 0) {
            throw new IllegalStateException(
                "Creative traversal produced no occupied or target slot evidence");
        }
        if (occupiedSlotCount != occupiedSlotIconEvidenceCount
                + occupiedSlotIconExceptionCount) {
            throw new IllegalStateException(
                "Global occupied-slot icon accounting is incomplete");
        }
        if (targetSlotCount != targetSlotIconEvidenceCount
                + targetSlotIconExceptionCount) {
            throw new IllegalStateException(
                "Global target-slot icon accounting is incomplete");
        }
    }

    private static Map<String, Object> clientStateEvidence(
        Minecraft minecraft,
        List<ExactStackSnapshot> inventory,
        ExactStackSnapshot carried
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<String> inventoryEvidence = stackEvidenceStrings(inventory);
        evidence.put("playerUuid", minecraft.player.getUUID().toString());
        evidence.put("inventoryStackEvidenceIds", inventoryEvidence);
        evidence.put("inventoryStackEvidenceIdsSha256",
            Hashing.sha256Utf8(String.join("\n", inventoryEvidence)));
        evidence.put("inventorySlotCount", inventory.size());
        evidence.put("selectedSlot",
            minecraft.player.getInventory().selected);
        evidence.put("carriedStackEvidenceId",
            stackEvidenceId(carried));
        evidence.put("menuClass",
            minecraft.player.containerMenu.getClass().getName());
        evidence.put("inventoryMenuActive",
            minecraft.player.containerMenu == minecraft.player.inventoryMenu);
        evidence.put("clientTick", clientTick);
        return evidence;
    }

    private static Map<String, Object> requireClientStateRestored(
        Minecraft minecraft,
        String fenceLabel
    ) {
        if (minecraft.screen != null) {
            throw new IllegalStateException(
                "Client screen reopened before " + fenceLabel);
        }
        if (minecraft.options.keyInventory.isDown()
                || minecraft.options.keyInventory.consumeClick()) {
            throw new IllegalStateException(
                "Inventory input was not reset before " + fenceLabel);
        }
        if (minecraft.player.containerMenu !=
                minecraft.player.inventoryMenu) {
            throw new IllegalStateException(
                "Player inventory menu was not restored before "
                    + fenceLabel);
        }
        HolderLookup.Provider registries = minecraft.level.registryAccess();
        List<ExactStackSnapshot> inventory = playerInventorySnapshots(
            minecraft.player, registries);
        ExactStackSnapshot carried = captureStack(
            minecraft.player.containerMenu.getCarried(), registries);
        if (!inventory.equals(baselinePlayerInventory)
                || !carried.equals(baselineClientCarried)
                || minecraft.player.getInventory().selected
                    != baselineSelectedSlot
                || !minecraft.player.containerMenu.getClass().getName()
                    .equals(baselineClientMenuClass)) {
            throw new IllegalStateException(
                "Client player state differs from baseline before "
                    + fenceLabel);
        }
        Map<String, Object> evidence = clientStateEvidence(
            minecraft, inventory, carried);
        evidence.put("fenceLabel", fenceLabel);
        return evidence;
    }

    private static void requestServerState(
        Minecraft minecraft,
        String label
    ) {
        if (pendingServerState != null) {
            throw new IllegalStateException(
                "Integrated-server state request already pending before "
                    + label);
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            throw new IllegalStateException(
                "Integrated server or client player is unavailable for "
                    + label);
        }
        UUID playerUuid = minecraft.player.getUUID();
        CompletableFuture<ServerPlayerState> future =
            new CompletableFuture<>();
        pendingServerState = future;
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList()
                    .getPlayer(playerUuid);
                if (player == null) {
                    throw new IllegalStateException(
                        "Integrated-server player is unavailable for "
                            + label + ": " + playerUuid);
                }
                future.complete(ServerPlayerState.capture(
                    player, server, label));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
    }

    private static ServerPlayerState completedServerState(
        String label
    ) {
        CompletableFuture<ServerPlayerState> future = pendingServerState;
        if (future == null) {
            throw new IllegalStateException(
                "No integrated-server state request exists for " + label);
        }
        if (!future.isDone()) {
            return null;
        }
        pendingServerState = null;
        try {
            ServerPlayerState state = future.join();
            if (!label.equals(state.captureLabel())) {
                throw new IllegalStateException(
                    "Integrated-server state label mismatch: requested="
                        + label + ", observed=" + state.captureLabel());
            }
            state.registerEvidence();
            return state;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null
                ? exception : exception.getCause();
            throw new IllegalStateException(
                "Integrated-server state request failed for " + label,
                cause);
        }
    }

    private static Map<String, Object> zeroOwnedWorldEffects() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("entitiesCreated", 0);
        evidence.put("blocksChanged", 0);
        evidence.put("itemsGranted", 0);
        evidence.put("commandsExecuted", 0);
        evidence.put("slotClicksSent", 0);
        evidence.put("scope",
            "Harness only opens, traverses, screenshots, and closes the native creative inventory");
        return evidence;
    }

    private static boolean isTargetStack(
        ExactStackSnapshot snapshot
    ) {
        return !snapshot.isEmpty()
            && config.expectedModId().equals(
                snapshot.itemId().getNamespace());
    }

    private record ServerPlayerState(
        UUID playerUuid,
        List<ExactStackSnapshot> inventory,
        int selectedSlot,
        ExactStackSnapshot carried,
        String menuClass,
        boolean inventoryMenuActive,
        int serverTick,
        String captureLabel
    ) {
        private static ServerPlayerState capture(
            ServerPlayer player,
            MinecraftServer server,
            String label
        ) {
            HolderLookup.Provider registries = server.registryAccess();
            Inventory playerInventory = player.getInventory();
            List<ExactStackSnapshot> inventory = new ArrayList<>(
                playerInventory.getContainerSize());
            for (int slot = 0;
                    slot < playerInventory.getContainerSize(); slot++) {
                inventory.add(ExactStackSnapshot.capture(
                    playerInventory.getItem(slot), registries));
            }
            return new ServerPlayerState(
                player.getUUID(),
                List.copyOf(inventory),
                playerInventory.selected,
                ExactStackSnapshot.capture(
                    player.containerMenu.getCarried(), registries),
                player.containerMenu.getClass().getName(),
                player.containerMenu == player.inventoryMenu,
                server.getTickCount(),
                label
            );
        }

        private void registerEvidence() {
            for (ExactStackSnapshot snapshot : inventory) {
                registerStackEvidence(snapshot);
            }
            registerStackEvidence(carried);
        }

        private boolean exactStateEquals(ServerPlayerState other) {
            return other != null
                && playerUuid.equals(other.playerUuid)
                && inventory.equals(other.inventory)
                && selectedSlot == other.selectedSlot
                && carried.equals(other.carried)
                && menuClass.equals(other.menuClass)
                && inventoryMenuActive == other.inventoryMenuActive;
        }

        private Map<String, Object> evidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            List<String> inventoryEvidence =
                stackEvidenceStrings(inventory);
            evidence.put("captureLabel", captureLabel);
            evidence.put("playerUuid", playerUuid.toString());
            evidence.put("inventoryStackEvidenceIds",
                inventoryEvidence);
            evidence.put("inventoryStackEvidenceIdsSha256",
                Hashing.sha256Utf8(String.join("\n", inventoryEvidence)));
            evidence.put("inventorySlotCount", inventory.size());
            evidence.put("selectedSlot", selectedSlot);
            evidence.put("carriedStackEvidenceId",
                stackEvidenceId(carried));
            evidence.put("menuClass", menuClass);
            evidence.put("inventoryMenuActive", inventoryMenuActive);
            evidence.put("serverTick", serverTick);
            evidence.put("nativeExactStateEquality",
                "UUID+ordered inventory count/components+selected slot+carried count/components+menu class+inventory-menu identity");
            return evidence;
        }
    }

    private static List<String> resourceLocationStrings(
        Collection<ResourceLocation> values
    ) {
        return values.stream()
            .map(ResourceLocation::toString)
            .sorted()
            .toList();
    }

    private static boolean tabContainsNamespace(
        CreativeModeTab tab,
        String namespace
    ) {
        ResourceLocation name = CreativeModeTabRegistry.getName(tab);
        if (name != null && namespace.equals(name.getNamespace())) {
            return true;
        }
        for (ItemStack stack : tab.getDisplayItems()) {
            if (!stack.isEmpty()) {
                ResourceLocation itemName =
                    BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (namespace.equals(itemName.getNamespace())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String tabId(CreativeModeTab tab) {
        ResourceLocation name = CreativeModeTabRegistry.getName(tab);
        return name == null
            ? tab.getDisplayName().getString()
            : name.toString();
    }

    private static void fail(Throwable throwable) {
        if (sealed || phase == Phase.FAILED) {
            return;
        }
        Phase failedPhase = phase;
        phase = Phase.FAILED;
        boolean cleanupPassed = true;
        List<Map<String, Object>> artifacts =
            new ArrayList<>(screenshotArtifacts);
        if (expectedContentManifestArtifact != null) {
            artifacts.add(expectedContentManifestArtifact);
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen
                    instanceof CreativeModeInventoryScreen screen) {
                resetCreativeScroll(screen);
                restoreScreenshotMouse(minecraft);
                screen.onClose();
            } else {
                minecraft.setScreen(null);
            }
        } catch (Throwable cleanupFailure) {
            cleanupPassed = false;
            throwable.addSuppressed(cleanupFailure);
        }

        try {
            if (config == null) {
                config = ValidationConfig.load("client");
            }
            if (ledger == null) {
                ledger = new ScenarioLedger(REQUESTED);
            }
            if (ledger.activeId() == null) {
                ledger.startNext(clientTick);
            }
            if (!Files.exists(config.creativeTracePath()) &&
                    (!tabTrace.isEmpty() ||
                        !currentRowTrace.isEmpty())) {
                Map<String, Object> partialTrace =
                    new LinkedHashMap<>();
                partialTrace.put("schemaVersion", 3);
                partialTrace.put("sessionId", config.sessionId());
                partialTrace.put("targetModId",
                    config.expectedModId());
                if (expectedContent != null) {
                    partialTrace.put("sourceAnchor",
                        expectedContent.sourceAnchor());
                    partialTrace.put("expectedRegisteredItemIds",
                        expectedContent.expectedItemIdStrings());
                }
                partialTrace.put("nativeStackEquality",
                    "count+ItemStack.isSameItemSameComponents");
                partialTrace.put("stackEvidence",
                    stackEvidenceRecords());
                if (baselineClientStateEvidence != null) {
                    partialTrace.put("clientStateBaseline",
                        baselineClientStateEvidence);
                }
                if (cleanupFenceStartClientStateEvidence != null) {
                    partialTrace.put("clientCleanupFenceStart",
                        cleanupFenceStartClientStateEvidence);
                }
                if (cleanupClientStateEvidence != null) {
                    partialTrace.put("clientStateCleanup",
                        cleanupClientStateEvidence);
                }
                if (baselineServerState != null) {
                    partialTrace.put("integratedServerStateBaseline",
                        baselineServerState.evidence());
                }
                if (cleanupFenceStartServerState != null) {
                    partialTrace.put("integratedServerCleanupFenceStart",
                        cleanupFenceStartServerState.evidence());
                }
                if (cleanupServerState != null) {
                    partialTrace.put("integratedServerStateCleanup",
                        cleanupServerState.evidence());
                }
                partialTrace.put("harnessOwnedWorldEffects",
                    zeroOwnedWorldEffects());
                partialTrace.put("tabs",
                    new ArrayList<>(tabTrace));
                partialTrace.put("partialRows",
                    new ArrayList<>(currentRowTrace));
                partialTrace.put("failure",
                    throwable.toString());
                ReceiptSupport.writeImmutableJson(
                    config.creativeTracePath(), partialTrace);
                artifacts.add(ReceiptSupport.artifact(
                    config.creativeTracePath()));
            }
            ledger.fail(
                clientTick,
                cleanupPassed,
                throwable.toString(),
                artifacts,
                Map.of(
                    "phase", failedPhase.name(),
                    "renderFrameCount", renderFrameCount
                )
            );
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("naturalExitRequested", true);
            extra.put("nativeOverworldSaveObserved",
                overworldSavedAfterStop);
            extra.put("failure", throwable.toString());
            ReceiptSupport.writeImmutableJson(
                config.receiptPath(),
                ledger.receipt(
                    config,
                    worldLoadCount,
                    targetEvidence,
                    extra
                )
            );
            sealed = true;
        } catch (Throwable receiptFailure) {
            throwable.addSuppressed(receiptFailure);
        }

        ExactJarValidationMod.LOGGER.error(
            "[ExactJarValidation] FAIL client validation",
            throwable);
        Minecraft.getInstance().stop();
    }

    private static boolean clientLane() {
        return "client".equals(
            System.getProperty("exactJarValidation.lane"));
    }

    private static boolean phaseRequiresLoadedWorld() {
        return switch (phase) {
            case WAIT_FOR_SERVER_BASELINE, OPEN_CREATIVE_SCREEN,
                 WAIT_FOR_FIRST_RENDER, DISCOVER_TABS, SELECT_TAB,
                 WAIT_FOR_MENU_STABILITY, SET_ROW, WAIT_FOR_ROW_RENDER,
                 CLEANUP, WAIT_FOR_SERVER_CLEANUP_FENCE_START,
                 WAIT_FOR_SERVER_CLEANUP -> true;
            default -> false;
        };
    }

    private static void requireLoadedWorld(Minecraft minecraft) {
        if (minecraft.level == null ||
                minecraft.player == null ||
                minecraft.gameMode == null ||
                !minecraft.gameMode.hasInfiniteItems() ||
                !minecraft.hasSingleplayerServer()) {
            throw new IllegalStateException(
                "Owned creative client world became unhealthy in phase "
                    + phase);
        }
        if (worldLoadCount != 1) {
            throw new IllegalStateException(
                "Expected one loaded client world in phase " + phase
                    + ", observed " + worldLoadCount);
        }
    }

    private enum Phase {
        VERIFY_JAR,
        WAIT_FOR_TITLE,
        WAIT_FOR_WORLD,
        WAIT_FOR_SERVER_BASELINE,
        OPEN_CREATIVE_SCREEN,
        WAIT_FOR_FIRST_RENDER,
        DISCOVER_TABS,
        SELECT_TAB,
        WAIT_FOR_MENU_STABILITY,
        SET_ROW,
        WAIT_FOR_ROW_RENDER,
        CLEANUP,
        WAIT_FOR_SERVER_CLEANUP_FENCE_START,
        WAIT_FOR_SERVER_CLEANUP,
        STOP_REQUESTED,
        FAILED
    }
}

