package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionDataPacket;
import me.cortex.voxy.server.network.LodSectionDeletePacket;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Service for synchronizing LOD sections between server and all connected players.
 * Handles streaming LOD updates to players and sending existing LODs to new players.
 */
public class ServerLodSyncService {
    private final VoxyServerInstance instance;
    private final ConcurrentHashMap<ServerPlayer, PlayerSyncState> playerStates = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private final Thread syncThread;
    private final BlockingQueue<SectionUpdate> pendingUpdates = new LinkedBlockingQueue<>();

    private record SectionUpdate(WorldIdentifier worldId, long sectionKey, byte[] data, boolean isDelete) {}
    
    private static class PlayerSyncState {
        final ServerPlayer player;
        volatile boolean initialSyncComplete = false;
        
        PlayerSyncState(ServerPlayer player) {
            this.player = player;
        }
    }

    public ServerLodSyncService(VoxyServerInstance instance) {
        this.instance = instance;
        this.syncThread = new Thread(this::syncLoop, "Voxy-Server-LOD-Sync");
        this.syncThread.setDaemon(true);
        this.syncThread.start();
    }

    private void syncLoop() {
        while (this.running) {
            try {
                // Use blocking poll with timeout to avoid busy waiting
                SectionUpdate update = this.pendingUpdates.poll(100, TimeUnit.MILLISECONDS);
                if (update != null) {
                    broadcastUpdate(update);
                }
            } catch (InterruptedException e) {
                // Exit loop on interrupt
                break;
            } catch (Exception e) {
                Logger.error("Error in LOD sync loop", e);
            }
        }
    }

    private void broadcastUpdate(SectionUpdate update) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        var server = this.instance.getServer();
        if (server == null) return;

        for (ServerPlayer player : PlayerLookup.all(server)) {
            if (!ServerPlayNetworking.canSend(player, LodSectionDataPacket.ID)) {
                continue; // Player doesn't have the mod installed
            }
            
            try {
                if (update.isDelete) {
                    sendDeletePacket(player, update.sectionKey, update.worldId.getWorldId());
                } else {
                    sendDataPacket(player, update.sectionKey, update.data, update.worldId.getWorldId());
                }
            } catch (Exception e) {
                Logger.error("Failed to send LOD update to player " + player.getName().getString(), e);
            }
        }
    }

    private void sendDataPacket(ServerPlayer player, long sectionKey, byte[] data, String worldId) {
        var buf = PacketByteBufs.create();
        new LodSectionDataPacket(sectionKey, data, worldId).write(buf);
        ServerPlayNetworking.send(player, LodSectionDataPacket.ID, buf);
    }

    private void sendDeletePacket(ServerPlayer player, long sectionKey, String worldId) {
        var buf = PacketByteBufs.create();
        new LodSectionDeletePacket(sectionKey, worldId).write(buf);
        ServerPlayNetworking.send(player, LodSectionDeletePacket.ID, buf);
    }

    /**
     * Called when a player joins the server.
     * Registers the player and initiates sending of existing LOD data.
     */
    public void onPlayerJoin(ServerPlayer player) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        var state = new PlayerSyncState(player);
        this.playerStates.put(player, state);
        Logger.info("Player " + player.getName().getString() + " joined, will sync LODs when ready");
    }

    /**
     * Called when a player disconnects.
     */
    public void onPlayerLeave(ServerPlayer player) {
        this.playerStates.remove(player);
    }

    /**
     * Handle a request from a client to receive LOD data for a specific world.
     */
    public void handleLodRequest(ServerPlayer player, LodSectionRequestPacket packet) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        var state = this.playerStates.get(player);
        if (state == null) {
            state = new PlayerSyncState(player);
            this.playerStates.put(player, state);
        }

        Logger.info("Player " + player.getName().getString() + " requested LOD data for world: " + packet.worldId);
        
        // Start sending existing LOD data to the player in a background thread
        final PlayerSyncState finalState = state;
        this.instance.getThreadPool().serviceManager.execute(() -> sendExistingLodsToPlayer(finalState, packet.worldId));
    }

    private void sendExistingLodsToPlayer(PlayerSyncState state, String worldId) {
        var player = state.player;
        if (!player.isAlive() || player.hasDisconnected()) return;
        if (!ServerPlayNetworking.canSend(player, LodSectionDataPacket.ID)) return;

        // Find the world engine for this world ID
        // We iterate through server levels to find matching world
        var server = this.instance.getServer();
        if (server == null) return;

        for (var level : server.getAllLevels()) {
            var identifier = WorldIdentifier.of(level);
            if (identifier == null || !identifier.getWorldId().equals(worldId)) continue;

            var engine = this.instance.getNullable(identifier);
            if (engine == null) continue;

            // Iterate through all stored sections and send them
            engine.storage.iterateStoredSectionPositions(sectionKey -> {
                // Early exit if player is no longer valid
                if (!player.isAlive() || player.hasDisconnected()) return;

                try {
                    var section = engine.acquireIfExists(sectionKey);
                    if (section != null) {
                        try {
                            var serializedData = SaveLoadSystem3.serialize(section);
                            byte[] data = new byte[(int) serializedData.size];
                            MemoryUtil.memByteBuffer(serializedData.address, (int) serializedData.size).get(data);
                            serializedData.free();
                            
                            sendDataPacket(player, sectionKey, data, worldId);
                        } finally {
                            section.release();
                        }
                    }
                } catch (Exception e) {
                    Logger.error("Error sending LOD section to player", e);
                }
            });
            break;
        }

        state.initialSyncComplete = true;
        Logger.info("Completed initial LOD sync for player " + player.getName().getString());
    }

    /**
     * Called when a section is modified and needs to be synced to players.
     */
    public void enqueueSectionUpdate(WorldIdentifier worldId, WorldSection section) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        try {
            var serializedData = SaveLoadSystem3.serialize(section);
            byte[] data = new byte[(int) serializedData.size];
            MemoryUtil.memByteBuffer(serializedData.address, (int) serializedData.size).get(data);
            serializedData.free();
            
            this.pendingUpdates.add(new SectionUpdate(worldId, section.key, data, false));
        } catch (Exception e) {
            Logger.error("Error serializing section for sync", e);
        }
    }

    /**
     * Called when a section is deleted.
     */
    public void enqueueSectionDelete(WorldIdentifier worldId, long sectionKey) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        this.pendingUpdates.add(new SectionUpdate(worldId, sectionKey, null, true));
    }

    public void shutdown() {
        this.running = false;
        this.syncThread.interrupt();
        try {
            this.syncThread.join(5000);
        } catch (InterruptedException e) {
            Logger.error("Interrupted while waiting for sync thread to stop");
        }
        this.playerStates.clear();
        this.pendingUpdates.clear();
    }
}
