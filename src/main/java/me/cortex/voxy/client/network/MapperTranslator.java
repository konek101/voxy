package me.cortex.voxy.client.network;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.server.network.MapperSyncPacket;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles translation of block state IDs between server and client Mappers.
 * 
 * The server and client independently assign internal IDs to block states.
 * When receiving LOD data from the server, we need to translate the server's
 * block state IDs to the client's IDs so the data can be properly rendered.
 * 
 * This class maintains a per-world translation table that maps server IDs to client IDs.
 */
public class MapperTranslator {
    
    // Translation tables per world: server block ID -> client block ID
    private static final ConcurrentHashMap<String, Int2IntOpenHashMap> blockIdTranslations = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Int2IntOpenHashMap> biomeIdTranslations = new ConcurrentHashMap<>();
    // Store serialized block state data for later resolution if needed
    private static final ConcurrentHashMap<String, List<byte[]>> serverBlockStateData = new ConcurrentHashMap<>();
    
    /**
     * Process a MapperSyncPacket and build translation tables for the world.
     * 
     * @param packet The mapper sync packet from the server
     * @param engine The client's world engine (used to access the client's Mapper)
     */
    public static void processMapperSync(MapperSyncPacket packet, WorldEngine engine) {
        if (engine == null) {
            Logger.warn("Cannot process mapper sync: world engine is null");
            return;
        }
        
        var clientMapper = engine.getMapper();
        
        // Store the serialized block state data for later resolution
        List<byte[]> blockStateDataList = new ArrayList<>(packet.blockStateMappings);
        serverBlockStateData.put(packet.worldId, blockStateDataList);
        
        // Build block state translation table
        var blockTranslation = new Int2IntOpenHashMap(packet.blockStateMappings.size());
        blockTranslation.defaultReturnValue(-1);
        
        int serverBlockId = 0;
        for (byte[] serializedEntry : packet.blockStateMappings) {
            try {
                // Deserialize the server's StateEntry to get the BlockState
                boolean[] dummy = new boolean[1];
                var serverEntry = Mapper.StateEntry.deserialize(serverBlockId, serializedEntry, dummy);
                
                // Get or create the client's ID for this BlockState
                int clientBlockId = clientMapper.getIdForBlockState(serverEntry.state);
                
                blockTranslation.put(serverBlockId, clientBlockId);
            } catch (Exception e) {
                Logger.error("Failed to deserialize server block state entry " + serverBlockId, e);
                // Map to air as fallback
                blockTranslation.put(serverBlockId, 0);
            }
            serverBlockId++;
        }
        
        // Build biome translation table
        var biomeTranslation = new Int2IntOpenHashMap(packet.biomeMappings.size());
        biomeTranslation.defaultReturnValue(-1);
        
        int serverBiomeId = 0;
        for (byte[] serializedEntry : packet.biomeMappings) {
            try {
                // Deserialize the server's BiomeEntry
                var serverEntry = Mapper.BiomeEntry.deserialize(serverBiomeId, serializedEntry);
                
                // For biomes, we need to look up or register with the client's mapper
                // Since Mapper.getIdForBiome requires a Holder<Biome>, we'll store by string name
                // and translate during section processing
                // For now, just use the same ID (biome IDs are typically consistent)
                biomeTranslation.put(serverBiomeId, serverBiomeId);
            } catch (Exception e) {
                Logger.error("Failed to deserialize server biome entry " + serverBiomeId, e);
                biomeTranslation.put(serverBiomeId, 0);
            }
            serverBiomeId++;
        }
        
        blockIdTranslations.put(packet.worldId, blockTranslation);
        biomeIdTranslations.put(packet.worldId, biomeTranslation);
        
        Logger.info("Processed mapper sync for world " + packet.worldId + 
                   ": " + blockTranslation.size() + " block states, " + biomeTranslation.size() + " biomes");
    }
    
    /**
     * Check if we have translation tables for a world.
     */
    public static boolean hasTranslation(String worldId) {
        return blockIdTranslations.containsKey(worldId);
    }
    
    /**
     * Translate raw section data buffer from server IDs to client IDs.
     * This is used during deserialization to translate before loading into a section.
     * 
     * @param worldId The world ID
     * @param data The raw section data buffer
     * @param clientMapper The client's Mapper for bounds validation
     * @return true if translation was successful
     */
    public static boolean translateSectionData(String worldId, MemoryBuffer data, Mapper clientMapper) {
        var blockTranslation = blockIdTranslations.get(worldId);
        
        if (blockTranslation == null) {
            Logger.warn("No block translation table for world " + worldId);
            return false;
        }
        
        // The data format is:
        // 8 bytes: section key
        // 8 bytes: metadata (bottom 2 bytes = LUT size, next byte = non-empty children)
        // 32K*2 bytes: block indices into LUT
        // N*8 bytes: LUT entries (the long block IDs)
        
        long ptr = data.address;
        ptr += 8; // Skip section key
        long metadata = MemoryUtil.memGetLong(ptr);
        ptr += 8; // Skip metadata
        
        int lutSize = (int) (metadata & 0xFFFF);
        long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;
        
        // Translate each LUT entry
        for (int i = 0; i < lutSize; i++) {
            long lutPtr = lutBasePtr + i * 8L;
            long oldId = MemoryUtil.memGetLong(lutPtr);
            
            int serverBlockId = Mapper.getBlockId(oldId);
            int serverBiomeId = Mapper.getBiomeId(oldId);
            int light = Mapper.getLightId(oldId);
            
            int clientBlockId = blockTranslation.getOrDefault(serverBlockId, -1);
            
            // Always validate the client block ID is within current bounds
            // The Mapper state may have changed since the translation table was built
            int currentMaxBlockId = clientMapper.getBlockStateCount();
            
            if (clientBlockId == -1 || clientBlockId >= currentMaxBlockId) {
                // Translation doesn't exist or is out of bounds - try to resolve via the stored BlockState
                var blockStateData = serverBlockStateData.get(worldId);
                if (blockStateData != null && serverBlockId < blockStateData.size()) {
                    byte[] stateBytes = blockStateData.get(serverBlockId);
                    if (stateBytes != null) {
                        try {
                            boolean[] dummy = new boolean[1];
                            var serverEntry = Mapper.StateEntry.deserialize(serverBlockId, stateBytes, dummy);
                            // Get or create the client ID for this BlockState
                            // This will return an existing ID or create a new one
                            clientBlockId = clientMapper.getIdForBlockState(serverEntry.state);
                            // Update the translation table for future use
                            blockTranslation.put(serverBlockId, clientBlockId);
                        } catch (Exception e) {
                            Logger.error("Failed to resolve block state " + serverBlockId + " during translation", e);
                            clientBlockId = 0; // Map to air
                        }
                    } else {
                        Logger.warn("No state data for server block ID " + serverBlockId + ", mapping to air");
                        clientBlockId = 0; // Map unknown to air
                    }
                } else {
                    Logger.warn("Server block ID " + serverBlockId + " out of range for stored state data (size: " + 
                        (serverBlockStateData.get(worldId) != null ? serverBlockStateData.get(worldId).size() : 0) + "), mapping to air");
                    clientBlockId = 0; // Map unknown to air
                }
            }
            
            // Final validation - if we still have an invalid ID, use air
            if (clientBlockId < 0 || clientBlockId >= clientMapper.getBlockStateCount()) {
                Logger.warn("Client block ID " + clientBlockId + " still out of bounds after resolution (max: " + 
                    clientMapper.getBlockStateCount() + "), mapping to air");
                clientBlockId = 0;
            }
            
            long newId = Mapper.composeMappingId((byte) light, clientBlockId, serverBiomeId);
            MemoryUtil.memPutLong(lutPtr, newId);
        }
        
        return true;
    }
    
    /**
     * Clear translation tables for a world (e.g., when disconnecting).
     */
    public static void clearTranslation(String worldId) {
        blockIdTranslations.remove(worldId);
        biomeIdTranslations.remove(worldId);
        serverBlockStateData.remove(worldId);
    }
    
    /**
     * Clear all translation tables.
     */
    public static void clearAll() {
        blockIdTranslations.clear();
        biomeIdTranslations.clear();
        serverBlockStateData.clear();
    }
}
