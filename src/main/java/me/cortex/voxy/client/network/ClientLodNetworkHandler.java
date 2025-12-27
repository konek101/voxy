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

        // When joining a server, request LOD data after a delay
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Schedule the request with a delay to ensure world info is ready
            // Using a separate thread to avoid blocking the render thread
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Wait for world to be ready
                    // Execute on client thread
                    client.execute(ClientLodNetworkHandler::requestLodData);
                } catch (InterruptedException e) {
                    // Ignore - we're shutting down
                }
            }, "Voxy-LOD-Request-Delay").start();
        });

        Logger.info("Client LOD network handler initialized");
    }

    private static void handleSectionData(LodSectionDataPacket packet) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) return;

        // Find the world engine for this world
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        var identifier = WorldIdentifier.of(level);
        if (identifier == null || !identifier.getWorldId().equals(packet.worldId)) {
            // Try to find by world ID if current world doesn't match
            return;
        }

        var engine = instance.getOrCreate(identifier);
        if (engine == null) return;

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
        if (instance == null) return;

        var level = Minecraft.getInstance().level;
        if (level == null) return;

        var identifier = WorldIdentifier.of(level);
        if (identifier == null || !identifier.getWorldId().equals(packet.worldId)) {
            return;
        }

        var engine = instance.getNullable(identifier);
        if (engine == null) return;

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
        if (level == null) return;

        var identifier = WorldIdentifier.of(level);
        if (identifier == null) return;

        if (!ClientPlayNetworking.canSend(LodSectionRequestPacket.ID)) {
            // Server doesn't support LOD sync
            return;
        }

        var buf = PacketByteBufs.create();
        new LodSectionRequestPacket(identifier.getWorldId()).write(buf);
        ClientPlayNetworking.send(LodSectionRequestPacket.ID, buf);

        Logger.info("Requested LOD data from server for world: " + identifier.getWorldId());
    }
}
