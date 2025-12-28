package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.LodSectionDataPacket;
import me.cortex.voxy.server.network.LodSectionDeletePacket;
import me.cortex.voxy.server.network.LodSectionRequestPacket;
import me.cortex.voxy.server.network.MapperSyncPacket;
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

    // maxBlockId tracks the highest block ID in the section data (for ensuring MapperSync coverage)
    private record SectionUpdate(WorldIdentifier worldId, long sectionKey, byte[] data, boolean isDelete, int maxBlockId) {}
    
    private static class PlayerSyncState {
        final ServerPlayer player;
        volatile boolean initialSyncComplete = false;
        volatile int lastSyncedBlockStateCount = 0;
        volatile int lastSyncedBiomeCount = 0;
        
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
                // Use blocking poll with configurable timeout to avoid busy waiting
                SectionUpdate update = this.pendingUpdates.poll(VoxyServerConfig.CONFIG.syncPollTimeoutMs, TimeUnit.MILLISECONDS);
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
        
        // Get the world engine to check mapper state
        var engine = this.instance.getNullable(update.worldId);

        for (ServerPlayer player : PlayerLookup.all(server)) {
            if (!ServerPlayNetworking.canSend(player, LodSectionDataPacket.ID)) {
                continue; // Player doesn't have the mod installed
            }
            
            // Only send to players who have synced (have a state) or skip if no state
            var state = this.playerStates.get(player);
            if (state == null) {
                continue; // Player hasn't requested LOD sync yet
            }
            
            try {
                // Check if we need to send a mapper sync before sending section data
                // We need to ensure the mapper sync covers all block IDs in the section data
                if (engine != null && !update.isDelete) {
                    var mapper = engine.getMapper();
                    int currentBlockCount = mapper.getBlockStateCount();
                    int currentBiomeCount = mapper.getBiomeEntries().length;
                    
                    // Sync if mapper has grown OR if section contains block IDs we haven't synced
                    // The section's maxBlockId + 1 is the minimum number of entries needed
                    int requiredBlockCount = update.maxBlockId + 1;
                    
                    if (currentBlockCount > state.lastSyncedBlockStateCount || 
                        currentBiomeCount > state.lastSyncedBiomeCount ||
                        requiredBlockCount > state.lastSyncedBlockStateCount) {
                        // Mapper has new entries or section needs entries we haven't synced
                        sendMapperSync(player, engine, update.worldId.getWorldId());
                        state.lastSyncedBlockStateCount = currentBlockCount;
                        state.lastSyncedBiomeCount = currentBiomeCount;
                    }
                }
                
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
        if (!this.running) return;
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        var state = this.playerStates.get(player);
        if (state == null) {
            state = new PlayerSyncState(player);
            this.playerStates.put(player, state);
        }

        Logger.info("Player " + player.getName().getString() + " requested LOD data for world: " + packet.worldId);
        
        // Start sending existing LOD data to the player in a background thread
        final PlayerSyncState finalState = state;
        Thread syncThread = new Thread(() -> sendExistingLodsToPlayer(finalState, packet.worldId), "Voxy-LOD-Sync-" + player.getName().getString());
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void sendExistingLodsToPlayer(PlayerSyncState state, String worldId) {
        var player = state.player;
        if (!this.running) return;
        if (!player.isAlive() || player.hasDisconnected()) return;
        if (!ServerPlayNetworking.canSend(player, LodSectionDataPacket.ID)) return;

        // Find the world engine for this world ID
        // We iterate through server levels to find matching world
        var server = this.instance.getServer();
        if (server == null) return;

        for (var level : server.getAllLevels()) {
            if (!this.running) break;
            
            var identifier = WorldIdentifier.of(level);
            if (identifier == null || !identifier.getWorldId().equals(worldId)) continue;

            var engine = this.instance.getNullable(identifier);
            if (engine == null) continue;
            
            // Send mapper sync FIRST so client can translate block state IDs
            sendMapperSync(player, engine, worldId);
            
            // Track the synced mapper state
            var mapper = engine.getMapper();
            state.lastSyncedBlockStateCount = mapper.getBlockStateCount();
            state.lastSyncedBiomeCount = mapper.getBiomeEntries().length;

            // Iterate through all stored sections and send them
            engine.storage.iterateStoredSectionPositions(sectionKey -> {
                // Early exit if shutdown is requested or player is no longer valid
                if (!this.running) return;
                if (!player.isAlive() || player.hasDisconnected()) return;

                try {
                    // Check if mapper has grown during sync, and resync if needed
                    int currentBlockCount = mapper.getBlockStateCount();
                    int currentBiomeCount = mapper.getBiomeEntries().length;
                    if (currentBlockCount > state.lastSyncedBlockStateCount || 
                        currentBiomeCount > state.lastSyncedBiomeCount) {
                        sendMapperSync(player, engine, worldId);
                        state.lastSyncedBlockStateCount = currentBlockCount;
                        state.lastSyncedBiomeCount = currentBiomeCount;
                    }
                    
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
     * Send the mapper sync packet to a player.
     * This must be sent before any LOD section data so the client can translate block state IDs.
     */
    private void sendMapperSync(ServerPlayer player, me.cortex.voxy.common.world.WorldEngine engine, String worldId) {
        try {
            var packet = MapperSyncPacket.fromMapper(worldId, engine.getMapper());
            var buf = PacketByteBufs.create();
            packet.write(buf);
            ServerPlayNetworking.send(player, MapperSyncPacket.ID, buf);
            Logger.info("Sent mapper sync to player " + player.getName().getString() + " for world " + worldId + 
                       " (" + packet.blockStateMappings.size() + " block states, " + packet.biomeMappings.size() + " biomes)");
        } catch (Exception e) {
            Logger.error("Error sending mapper sync to player", e);
        }
    }

    /**
     * Called when a section is modified and needs to be synced to players.
     */
    public void enqueueSectionUpdate(WorldIdentifier worldId, WorldSection section) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        
        try {
            // Calculate the maximum block ID in this section before serializing
            int maxBlockId = 0;
            for (long state : section._unsafeGetRawDataArray()) {
                int blockId = Mapper.getBlockId(state);
                if (blockId > maxBlockId) {
                    maxBlockId = blockId;
                }
            }
            
            var serializedData = SaveLoadSystem3.serialize(section);
            byte[] data = new byte[(int) serializedData.size];
            MemoryUtil.memByteBuffer(serializedData.address, (int) serializedData.size).get(data);
            serializedData.free();
            
            this.pendingUpdates.add(new SectionUpdate(worldId, section.key, data, false, maxBlockId));
        } catch (Exception e) {
            Logger.error("Error serializing section for sync", e);
        }
    }

    /**
     * Called when a section is deleted.
     */
    public void enqueueSectionDelete(WorldIdentifier worldId, long sectionKey) {
        if (!VoxyServerConfig.CONFIG.syncLodsToPlayers) return;
        this.pendingUpdates.add(new SectionUpdate(worldId, sectionKey, null, true, 0));
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
