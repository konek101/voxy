package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public class VoxyServer implements DedicatedServerModInitializer {
    private static VoxyServerInstance serverInstance;

    @Override
    public void onInitializeServer() {
        Logger.info("Initializing Voxy Server");
        
        // Load server config
        VoxyServerConfig.CONFIG.getClass(); // Force load config
        
        if (!VoxyServerConfig.CONFIG.enabled) {
            Logger.info("Voxy Server is disabled in config");
            return;
        }

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

        // Register player connection events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (serverInstance != null) {
                serverInstance.getSyncService().onPlayerJoin(handler.getPlayer());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (serverInstance != null) {
                serverInstance.getSyncService().onPlayerLeave(handler.getPlayer());
            }
        });

        // Register packet handlers for C2S packets
        ServerPlayNetworking.registerGlobalReceiver(LodSectionRequestPacket.ID, (server, player, handler, buf, responseSender) -> {
            var packet = new LodSectionRequestPacket(buf);
            server.execute(() -> {
                if (serverInstance != null) {
                    serverInstance.getSyncService().handleLodRequest(player, packet);
                }
            });
        });

        Logger.info("Voxy Server initialized");
    }

    private void onServerStarting(MinecraftServer server) {
        Logger.info("Voxy Server starting");
        
        // Set up the instance factory for server
        VoxyCommon.setInstanceFactory(() -> new VoxyServerInstance(server));
        VoxyCommon.createInstance();
        
        serverInstance = (VoxyServerInstance) VoxyCommon.getInstance();
    }

    private void onServerStopped(MinecraftServer server) {
        Logger.info("Voxy Server stopping");
        VoxyCommon.shutdownInstance();
        serverInstance = null;
    }

    /**
     * Get the current server instance.
     */
    public static VoxyServerInstance getServerInstance() {
        return serverInstance;
    }

    /**
     * Called when a chunk is generated or modified on the server.
     * Generates LOD data for the chunk if enabled in config.
     */
    public static void onChunkGenerated(ServerLevel level, LevelChunk chunk) {
        if (serverInstance == null) return;
        if (!VoxyServerConfig.CONFIG.generateLodsOnChunkGeneration) return;

        ingestChunk(level, chunk);
    }

    /**
     * Called when a chunk is modified on the server.
     */
    public static void onChunkModified(ServerLevel level, LevelChunk chunk) {
        if (serverInstance == null) return;
        if (!VoxyServerConfig.CONFIG.generateLodsOnChunkModification) return;

        ingestChunk(level, chunk);
    }

    private static void ingestChunk(ServerLevel level, LevelChunk chunk) {
        var worldId = WorldIdentifier.of(level);
        if (worldId == null) return;

        var engine = serverInstance.getOrCreate(worldId);
        if (engine == null) return;

        // Set up the save callback to also sync to players
        engine.setSaveCallback((eng, section) -> {
            serverInstance.getSavingService().enqueueSave(eng, section);
            serverInstance.getSyncService().enqueueSectionUpdate(worldId, section);
        });

        // Ingest the chunk
        serverInstance.getIngestService().enqueueIngest(engine, chunk);
    }
}
