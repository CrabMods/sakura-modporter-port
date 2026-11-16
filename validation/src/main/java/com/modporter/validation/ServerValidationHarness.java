package com.modporter.validation;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = ExactJarValidationMod.MOD_ID)
public final class ServerValidationHarness {
    private static final List<String> REQUESTED = List.of(
        "target-jar-origin",
        "dedicated-server-ready-save-stop"
    );

    private static ValidationConfig config;
    private static ScenarioLedger ledger;
    private static ExactJarVerifier.Evidence targetEvidence;
    private static long ticks;
    private static long stopRequestedAtTick = -1;
    private static int worldLoadCount;
    private static boolean started;
    private static boolean stopRequested;
    private static boolean overworldSavedAfterStop;
    private static boolean sealed;

    private ServerValidationHarness() {
    }

    @SubscribeEvent
    public static synchronized void onLevelLoad(LevelEvent.Load event) {
        if (!serverLane() ||
                !(event.getLevel() instanceof ServerLevel level) ||
                !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        worldLoadCount++;
    }

    @SubscribeEvent
    public static synchronized void onServerStarted(
            ServerStartedEvent event) {
        if (!serverLane()) {
            return;
        }
        try {
            config = ValidationConfig.load("server");
            ledger = new ScenarioLedger(REQUESTED);
            if (worldLoadCount != 1) {
                throw new IllegalStateException(
                    "Expected exactly one dedicated-server overworld load, observed "
                        + worldLoadCount);
            }
            ledger.start("target-jar-origin", ticks);
            targetEvidence = ExactJarVerifier.verify(config);
            ledger.pass(
                ticks,
                true,
                List.of(ReceiptSupport.artifact(
                    config.expectedJarPath())),
                Map.of("modListOwningFileMatched", true)
            );
            ExactJarValidationMod.LOGGER.info(
                "[ExactJarValidation] PASS target-jar-origin");

            ledger.start(
                "dedicated-server-ready-save-stop", ticks);
            started = true;
        } catch (Throwable throwable) {
            failAndStop(event.getServer(), throwable);
        }
    }

    @SubscribeEvent
    public static synchronized void onServerTick(
            ServerTickEvent.Post event) {
        if (!serverLane() || !started || stopRequested || sealed) {
            return;
        }
        ticks++;
        if (ticks < 20) {
            return;
        }

        stopRequested = true;
        stopRequestedAtTick = ticks;
        ExactJarValidationMod.LOGGER.info(
            "[ExactJarValidation] dedicated server ready tick {}; requesting controlled stop",
            ticks);
        event.getServer().halt(false);
    }

    @SubscribeEvent
    public static synchronized void onLevelSave(LevelEvent.Save event) {
        if (!serverLane() || !stopRequested ||
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
        if (!serverLane() || sealed || ledger == null) {
            return;
        }
        try {
            if (!stopRequested) {
                throw new IllegalStateException(
                    "Dedicated server stopped before the controlled stop request");
            }
            if (!overworldSavedAfterStop) {
                throw new IllegalStateException(
                    "No overworld LevelEvent.Save was observed after the stop request");
            }
            if (!Files.isRegularFile(config.serverWorldLevelDat())) {
                throw new IllegalStateException(
                    "Dedicated server level.dat is missing: "
                        + config.serverWorldLevelDat());
            }
            Map<String, Object> levelDatArtifact =
                ReceiptSupport.copyImmutableArtifact(
                    config.serverWorldLevelDat(),
                    config.serverWorldEvidencePath()
                );

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("readyTick", 20);
            details.put("observedOverworldLoads", worldLoadCount);
            details.put("stopRequestedAtTick", stopRequestedAtTick);
            details.put("nativeOverworldSaveObserved", true);
            details.put("serverStoppedEventObserved", true);
            ledger.pass(
                ticks,
                true,
                List.of(levelDatArtifact),
                details
            );
            ExactJarValidationMod.LOGGER.info(
                "[ExactJarValidation] PASS dedicated-server-ready-save-stop");

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("controlledStopRequested", true);
            extra.put("nativeOverworldSaveObserved", true);
            extra.put("serverStoppedEventObserved", true);
            extra.put("naturalExitExpected", true);
            ReceiptSupport.writeImmutableJson(
                config.receiptPath(),
                ledger.receipt(
                    config, worldLoadCount, targetEvidence, extra)
            );
            sealed = true;
            ExactJarValidationMod.LOGGER.info(
                "[ExactJarValidation] server receipt sealed: {}",
                config.receiptPath());
        } catch (Throwable throwable) {
            failAfterStop(throwable);
        }
    }

    private static void failAndStop(
        net.minecraft.server.MinecraftServer server,
        Throwable throwable
    ) {
        try {
            if (config == null) {
                config = ValidationConfig.load("server");
            }
            if (ledger == null) {
                ledger = new ScenarioLedger(REQUESTED);
            }
            if (ledger.activeId() == null) {
                ledger.startNext(ticks);
            }
            ledger.fail(
                ticks,
                true,
                throwable.toString(),
                List.of(),
                Map.of()
            );
            sealFailure(throwable);
        } catch (Throwable receiptFailure) {
            throwable.addSuppressed(receiptFailure);
        }
        ExactJarValidationMod.LOGGER.error(
            "[ExactJarValidation] FAIL dedicated server validation",
            throwable);
        server.halt(false);
    }

    private static void failAfterStop(Throwable throwable) {
        try {
            if (ledger.activeId() != null) {
                ledger.fail(
                    ticks,
                    false,
                    throwable.toString(),
                    List.of(),
                    Map.of(
                        "stopRequestedAtTick", stopRequestedAtTick,
                        "nativeOverworldSaveObserved",
                        overworldSavedAfterStop
                    )
                );
            }
            sealFailure(throwable);
        } catch (Throwable receiptFailure) {
            throwable.addSuppressed(receiptFailure);
        }
        ExactJarValidationMod.LOGGER.error(
            "[ExactJarValidation] FAIL dedicated server shutdown validation",
            throwable);
    }

    private static void sealFailure(Throwable throwable) throws Exception {
        if (sealed) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("controlledStopRequested", stopRequested);
        extra.put("nativeOverworldSaveObserved", overworldSavedAfterStop);
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
    }

    private static boolean serverLane() {
        return "server".equals(
            System.getProperty("exactJarValidation.lane"));
    }
}

