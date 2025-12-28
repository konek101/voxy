package me.cortex.voxy.server.network;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet sent from server to client to synchronize the Mapper's block state and biome mappings.
 * This allows the client to properly decode LOD data serialized by the server.
 * 
 * The packet contains:
 * - Block state mappings: id -> serialized StateEntry data
 * - Biome mappings: id -> serialized BiomeEntry data
 * 
 * The client uses these mappings to translate server-side block state IDs to client-side IDs.
 */
public class MapperSyncPacket {
    public static final ResourceLocation ID = new ResourceLocation("voxy", "mapper_sync");
    
    public final String worldId;
    public final List<byte[]> blockStateMappings;
    public final List<byte[]> biomeMappings;
    
    public MapperSyncPacket(String worldId, List<byte[]> blockStateMappings, List<byte[]> biomeMappings) {
        this.worldId = worldId;
        this.blockStateMappings = blockStateMappings;
        this.biomeMappings = biomeMappings;
    }
    
    public MapperSyncPacket(FriendlyByteBuf buf) {
        this.worldId = buf.readUtf();
        
        int blockStateCount = buf.readVarInt();
        this.blockStateMappings = new ArrayList<>(blockStateCount);
        for (int i = 0; i < blockStateCount; i++) {
            this.blockStateMappings.add(buf.readByteArray());
        }
        
        int biomeCount = buf.readVarInt();
        this.biomeMappings = new ArrayList<>(biomeCount);
        for (int i = 0; i < biomeCount; i++) {
            this.biomeMappings.add(buf.readByteArray());
        }
    }
    
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(this.worldId);
        
        buf.writeVarInt(this.blockStateMappings.size());
        for (byte[] mapping : this.blockStateMappings) {
            buf.writeByteArray(mapping);
        }
        
        buf.writeVarInt(this.biomeMappings.size());
        for (byte[] mapping : this.biomeMappings) {
            buf.writeByteArray(mapping);
        }
    }
    
    /**
     * Create a MapperSyncPacket from a Mapper's current state.
     */
    public static MapperSyncPacket fromMapper(String worldId, Mapper mapper) {
        var stateEntries = mapper.getStateEntries();
        var biomeEntries = mapper.getBiomeEntries();
        
        List<byte[]> blockStateMappings = new ArrayList<>(stateEntries.length);
        for (var entry : stateEntries) {
            blockStateMappings.add(entry.serialize());
        }
        
        List<byte[]> biomeMappings = new ArrayList<>(biomeEntries.length);
        for (var entry : biomeEntries) {
            biomeMappings.add(entry.serialize());
        }
        
        return new MapperSyncPacket(worldId, blockStateMappings, biomeMappings);
    }
}
