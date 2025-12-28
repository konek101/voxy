package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
import me.cortex.voxy.server.network.LodSectionUploadPacket;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class VoxyServer implements DedicatedServerModInitializer {
    private static VoxyServerInstance serverInstance;
    private static MinecraftServer currentServer;
    
    // Queue of chunks that need LOD regeneration due to block modifications
    // Using ConcurrentHashMap as a set to deduplicate chunks that were modified multiple times
    private static final ConcurrentHashMap<ChunkKey, Boolean> pendingChunkModifications = new ConcurrentHashMap<>();
    private static volatile boolean chunkProcessorRunning = false;
    private static Thread chunkProcessorThread;
    
    // Server load tracking
    private static final AtomicLong lastTickTimeNanos = new AtomicLong(0);
    private static volatile boolean serverBusy = false;
    
    private record ChunkKey(ServerLevel level, int chunkX, int chunkZ) {}
    
    /**
     * Check if the server is too busy to generate LODs locally.
     * When busy, clients should be asked to generate and upload LODs instead.
     */
    public static boolean isServerBusy() {
        if (!VoxyServerConfig.CONFIG.offloadToClientsWhenBusy) {
            return false;
        }
        
        // Check pending LOD count
        if (serverInstance != null) {
            var ingestService = serverInstance.getIngestService();
            if (ingestService.getTaskCount() > VoxyServerConfig.CONFIG.maxPendingLodsBeforeOffload) {
                return true;
            }
        }
        
        // Check server tick time
        float tickTimeMs = lastTickTimeNanos.get() / 1_000_000.0f;
        return tickTimeMs > VoxyServerConfig.CONFIG.serverTickMsThreshold;
    }
    
    /**
     * Update the server tick time for load tracking.
     * Should be called from a mixin at the end of each server tick.
     */
    public static void updateTickTime(long tickTimeNanos) {
        lastTickTimeNanos.set(tickTimeNanos);
        serverBusy = isServerBusy();
    }

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
        
        // Register handler for client LOD uploads
        ServerPlayNetworking.registerGlobalReceiver(LodSectionUploadPacket.ID, (server, player, handler, buf, responseSender) -> {
            if (!VoxyServerConfig.CONFIG.acceptLodsFromClients) {
                return; // Server not accepting client LODs
            }
            
            var packet = new LodSectionUploadPacket(buf);
            server.execute(() -> handleClientLodUpload(packet));
        });

        Logger.info("Voxy Server initialized");
    }
    
    private static void handleClientLodUpload(LodSectionUploadPacket packet) {
        if (serverInstance == null) return;
        if (currentServer == null) return;
        
        // Find the matching world
        for (var level : currentServer.getAllLevels()) {
            var identifier = WorldIdentifier.of(level);
            if (identifier == null || !identifier.getWorldId().equals(packet.worldId)) continue;
            
            var engine = serverInstance.getOrCreate(identifier);
            if (engine == null) continue;
            
            MemoryBuffer data = null;
            try {
                // Create a memory buffer from the compressed data
                data = new MemoryBuffer(packet.compressedData.length);
                MemoryUtil.memByteBuffer(data.address, (int) data.size).put(packet.compressedData);
                
                var section = engine.acquire(packet.sectionKey);
                if (section != null) {
                    try {
                        if (SaveLoadSystem3.deserialize(section, data)) {
                            engine.markDirty(section);
                            // Also sync to other players
                            serverInstance.getSyncService().enqueueSectionUpdate(identifier, section);
                        }
                    } finally {
                        section.release();
                    }
                }
            } catch (Exception e) {
                Logger.error("Error handling client LOD upload", e);
            } finally {
                if (data != null) {
                    data.free();
                }
            }
            break;
        }
    }

    private void onServerStarting(MinecraftServer server) {
        Logger.info("Voxy Server starting");
        
        currentServer = server;
        
        // Set up the instance factory for server
        VoxyCommon.setInstanceFactory(() -> new VoxyServerInstance(server));
        VoxyCommon.createInstance();
        
        serverInstance = (VoxyServerInstance) VoxyCommon.getInstance();
        
        // Start chunk modification processor thread
        startChunkProcessor();
    }

    private void onServerStopped(MinecraftServer server) {
        Logger.info("Voxy Server stopping");
        stopChunkProcessor();
        VoxyCommon.shutdownInstance();
        serverInstance = null;
        currentServer = null;
    }
    
    private static void startChunkProcessor() {
        chunkProcessorRunning = true;
        chunkProcessorThread = new Thread(() -> {
            while (chunkProcessorRunning) {
                try {
                    Thread.sleep(VoxyServerConfig.CONFIG.chunkModificationQueuePollingRateMs);
                    processQueuedChunks();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Logger.error("Error in chunk processor thread", e);
                }
            }
        }, "Voxy-Chunk-Processor");
        chunkProcessorThread.setDaemon(true);
        chunkProcessorThread.start();
    }
    
    private static void stopChunkProcessor() {
        chunkProcessorRunning = false;
        if (chunkProcessorThread != null) {
            chunkProcessorThread.interrupt();
            try {
                chunkProcessorThread.join(5000);
            } catch (InterruptedException e) {
                Logger.error("Interrupted while waiting for chunk processor to stop");
            }
        }
        pendingChunkModifications.clear();
    }
    
    private static void processQueuedChunks() {
        if (serverInstance == null) return;
        
        // Process all pending chunks using iterator to avoid race conditions
        var iterator = pendingChunkModifications.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            
            var key = entry.getKey();
            var chunk = key.level.getChunkSource().getChunkNow(key.chunkX, key.chunkZ);
            if (chunk != null) {
                ingestChunk(key.level, chunk);
            }
        }
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
     * When server is busy and offloading is enabled, skips local generation
     * and waits for clients to upload LODs.
     * 
     * NOTE: Chunk ingestion is deferred to avoid querying the lighting engine
     * synchronously during chunk load, which can cause ArrayIndexOutOfBoundsException.
     */
    public static void onChunkGenerated(ServerLevel level, LevelChunk chunk) {
        if (serverInstance == null) return;
        if (!VoxyServerConfig.CONFIG.generateLodsOnChunkGeneration) return;
        
        // Skip if server is busy and offloading to clients is enabled
        if (serverBusy && VoxyServerConfig.CONFIG.offloadToClientsWhenBusy) {
            // Server is busy, let clients handle LOD generation
            return;
        }

        // Queue the chunk for processing instead of immediate processing
        // This defers the lighting engine access to after chunk is fully loaded
        pendingChunkModifications.put(new ChunkKey(level, chunk.getPos().x, chunk.getPos().z), Boolean.TRUE);
    }

    /**
     * Called when a chunk is modified on the server.
     * Queues the chunk for LOD regeneration to avoid lag from frequent block changes.
     * When server is busy and offloading is enabled, skips local generation.
     */
    public static void onChunkModified(ServerLevel level, LevelChunk chunk) {
        if (serverInstance == null) return;
        if (!VoxyServerConfig.CONFIG.generateLodsOnChunkModification) return;
        
        // Skip if server is busy and offloading to clients is enabled
        if (serverBusy && VoxyServerConfig.CONFIG.offloadToClientsWhenBusy) {
            // Server is busy, let clients handle LOD generation
            return;
        }

        // Queue the chunk for processing instead of immediate processing
        pendingChunkModifications.put(new ChunkKey(level, chunk.getPos().x, chunk.getPos().z), Boolean.TRUE);
    }

    private static void ingestChunk(ServerLevel level, LevelChunk chunk) {
        var worldId = WorldIdentifier.of(level);
        if (worldId == null) {
            Logger.warn("Cannot ingest chunk: world identifier is null");
            return;
        }

        var engine = serverInstance.getOrCreate(worldId);
        if (engine == null) {
            Logger.warn("Cannot ingest chunk: failed to get or create world engine");
            return;
        }

        // Mark the engine as active to prevent idle shutdown while processing
        engine.markActive();

        // Set up the save callback to also sync to players
        engine.setSaveCallback((eng, section) -> {
            serverInstance.getSavingService().enqueueSave(eng, section);
            serverInstance.getSyncService().enqueueSectionUpdate(worldId, section);
        });

        // Ingest the chunk
        serverInstance.getIngestService().enqueueIngest(engine, chunk);
    }
}
