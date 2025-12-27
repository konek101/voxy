package me.cortex.voxy.client.network;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionDataPacket;
import me.cortex.voxy.server.network.LodSectionDeletePacket;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
import me.cortex.voxy.server.network.LodSectionUploadPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

/**
 * Client-side network handler for receiving LOD data from the server.
 */
public class ClientLodNetworkHandler {
    private static boolean initialized = false;
    
    // Constants for world readiness polling
    private static final int WORLD_READY_MAX_ATTEMPTS = 100;
    private static final int WORLD_READY_POLL_INTERVAL_MS = 100;

    public static void init() {
        if (initialized) return;
        initialized = true;

        // Register S2C packet handlers
        ClientPlayNetworking.registerGlobalReceiver(LodSectionDataPacket.ID, (client, handler, buf, responseSender) -> {
            var packet = new LodSectionDataPacket(buf);
            client.execute(() -> handleSectionData(packet));
        });

        ClientPlayNetworking.registerGlobalReceiver(LodSectionDeletePacket.ID, (client, handler, buf, responseSender) -> {
            var packet = new LodSectionDeletePacket(buf);
            client.execute(() -> handleSectionDelete(packet));
        });

        // When joining a server, request LOD data when world is ready
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Schedule the request with a check loop to ensure world info is ready
            // Using a separate thread to avoid blocking the render thread
            new Thread(() -> {
                try {
                    // Wait for the world to be ready by checking state instead of using a magic delay
                    for (int i = 0; i < WORLD_READY_MAX_ATTEMPTS; i++) {
                        var level = Minecraft.getInstance().level;
                        var identifier = level != null ? WorldIdentifier.of(level) : null;
                        var instance = VoxyCommon.getInstance();
                        
                        if (level != null && identifier != null && instance != null) {
                            // World is ready, execute on client thread
                            client.execute(ClientLodNetworkHandler::requestLodData);
                            return;
                        }
                        
                        Thread.sleep(WORLD_READY_POLL_INTERVAL_MS);
                    }
                    Logger.warn("Timeout waiting for world to be ready for LOD request");
                } catch (InterruptedException e) {
                    // Ignore - we're shutting down
                }
            }, "Voxy-LOD-Request-Delay").start();
        });

        Logger.info("Client LOD network handler initialized");
    }

    private static void handleSectionData(LodSectionDataPacket packet) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            Logger.warn("Cannot handle LOD section data: VoxyCommon instance is null");
            return;
        }

        // Find the world engine for this world
        var level = Minecraft.getInstance().level;
        if (level == null) {
            Logger.warn("Cannot handle LOD section data: client level is null");
            return;
        }

        var identifier = WorldIdentifier.of(level);
        if (identifier == null) {
            Logger.warn("Cannot handle LOD section data: world identifier is null");
            return;
        }
        if (!identifier.getWorldId().equals(packet.worldId)) {
            // World ID mismatch - not an error, just a different world
            return;
        }

        var engine = instance.getOrCreate(identifier);
        if (engine == null) {
            Logger.warn("Cannot handle LOD section data: failed to get or create world engine");
            return;
        }

        // Deserialize and load the section
        try {
            // Create a memory buffer from the compressed data
            var data = new MemoryBuffer(packet.compressedData.length);
            MemoryUtil.memByteBuffer(data.address, (int) data.size).put(packet.compressedData);
            
            var section = engine.acquire(packet.sectionKey);
            if (section != null) {
                try {
                    if (SaveLoadSystem3.deserialize(section, data)) {
                        engine.markDirty(section);
                    }
                } finally {
                    section.release();
                }
            }
            data.free();
        } catch (Exception e) {
            Logger.error("Error handling LOD section data from server", e);
        }
    }

    private static void handleSectionDelete(LodSectionDeletePacket packet) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            Logger.warn("Cannot handle LOD section delete: VoxyCommon instance is null");
            return;
        }

        var level = Minecraft.getInstance().level;
        if (level == null) {
            Logger.warn("Cannot handle LOD section delete: client level is null");
            return;
        }

        var identifier = WorldIdentifier.of(level);
        if (identifier == null) {
            Logger.warn("Cannot handle LOD section delete: world identifier is null");
            return;
        }
        if (!identifier.getWorldId().equals(packet.worldId)) {
            // World ID mismatch - not an error, just a different world
            return;
        }

        var engine = instance.getNullable(identifier);
        if (engine == null) {
            // Engine not created yet - not an error during initial sync
            return;
        }

        // Delete the section from storage
        try {
            engine.storage.deleteSectionData(packet.sectionKey);
        } catch (Exception e) {
            Logger.error("Error handling LOD section delete from server", e);
        }
    }

    /**
     * Request LOD data from the server for the current world.
     */
    public static void requestLodData() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            Logger.warn("Cannot request LOD data: client level is null");
            return;
        }

        var identifier = WorldIdentifier.of(level);
        if (identifier == null) {
            Logger.warn("Cannot request LOD data: world identifier is null");
            return;
        }

        if (!ClientPlayNetworking.canSend(LodSectionRequestPacket.ID)) {
            // Server doesn't support LOD sync - not an error
            return;
        }

        var buf = PacketByteBufs.create();
        new LodSectionRequestPacket(identifier.getWorldId()).write(buf);
        ClientPlayNetworking.send(LodSectionRequestPacket.ID, buf);

        Logger.info("Requested LOD data from server for world: " + identifier.getWorldId());
    }
    
    /**
     * Upload a locally generated LOD section to the server.
     * Only uploads if uploadLodsToServer is enabled in client config and server accepts uploads.
     */
    public static void uploadLodSection(WorldSection section, WorldIdentifier worldId) {
        if (!VoxyConfig.CONFIG.uploadLodsToServer) {
            return; // Client not configured to upload LODs
        }
        
        if (!ClientPlayNetworking.canSend(LodSectionUploadPacket.ID)) {
            return; // Server doesn't support LOD uploads
        }
        
        try {
            var serializedData = SaveLoadSystem3.serialize(section);
            byte[] data = new byte[(int) serializedData.size];
            MemoryUtil.memByteBuffer(serializedData.address, (int) serializedData.size).get(data);
            serializedData.free();
            
            var buf = PacketByteBufs.create();
            new LodSectionUploadPacket(section.key, data, worldId.getWorldId()).write(buf);
            ClientPlayNetworking.send(LodSectionUploadPacket.ID, buf);
        } catch (Exception e) {
            Logger.error("Error uploading LOD section to server", e);
        }
    }
    
    /**
     * Check if the client should generate LODs locally.
     * Returns false if the client is configured to rely on server LODs.
     */
    public static boolean shouldGenerateLods() {
        return !VoxyConfig.CONFIG.useServerLods;
    }
}
