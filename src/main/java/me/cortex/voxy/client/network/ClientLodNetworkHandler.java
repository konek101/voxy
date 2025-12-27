package me.cortex.voxy.client.network;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionDataPacket;
import me.cortex.voxy.server.network.LodSectionDeletePacket;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
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
                    int maxAttempts = 100; // Up to ~10 seconds with 100ms intervals
                    for (int i = 0; i < maxAttempts; i++) {
                        var level = Minecraft.getInstance().level;
                        var identifier = level != null ? WorldIdentifier.of(level) : null;
                        var instance = VoxyCommon.getInstance();
                        
                        if (level != null && identifier != null && instance != null) {
                            // World is ready, execute on client thread
                            client.execute(ClientLodNetworkHandler::requestLodData);
                            return;
                        }
                        
                        Thread.sleep(100);
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
}
