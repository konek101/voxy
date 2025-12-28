package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MapperSyncPacket serialization and deserialization.
 */
class MapperSyncPacketTest {

    @Test
    void testPacketCreation() {
        List<byte[]> blockStates = new ArrayList<>();
        blockStates.add(new byte[]{1, 2, 3});
        blockStates.add(new byte[]{4, 5, 6});
        
        List<byte[]> biomes = new ArrayList<>();
        biomes.add(new byte[]{7, 8, 9});
        
        var packet = new MapperSyncPacket("test_world", blockStates, biomes);
        
        assertEquals("test_world", packet.worldId);
        assertEquals(2, packet.blockStateMappings.size());
        assertEquals(1, packet.biomeMappings.size());
        assertArrayEquals(new byte[]{1, 2, 3}, packet.blockStateMappings.get(0));
        assertArrayEquals(new byte[]{4, 5, 6}, packet.blockStateMappings.get(1));
        assertArrayEquals(new byte[]{7, 8, 9}, packet.biomeMappings.get(0));
    }

    @Test
    void testSerializationRoundtrip() {
        List<byte[]> blockStates = new ArrayList<>();
        blockStates.add(new byte[]{10, 20, 30, 40, 50});
        blockStates.add(new byte[]{100, (byte)200});
        
        List<byte[]> biomes = new ArrayList<>();
        biomes.add(new byte[]{1});
        biomes.add(new byte[]{2});
        biomes.add(new byte[]{3});
        
        var original = new MapperSyncPacket("minecraft:overworld", blockStates, biomes);
        
        // Serialize
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        // Deserialize
        var deserialized = new MapperSyncPacket(buf);
        
        assertEquals(original.worldId, deserialized.worldId);
        assertEquals(original.blockStateMappings.size(), deserialized.blockStateMappings.size());
        assertEquals(original.biomeMappings.size(), deserialized.biomeMappings.size());
        
        for (int i = 0; i < original.blockStateMappings.size(); i++) {
            assertArrayEquals(original.blockStateMappings.get(i), deserialized.blockStateMappings.get(i));
        }
        
        for (int i = 0; i < original.biomeMappings.size(); i++) {
            assertArrayEquals(original.biomeMappings.get(i), deserialized.biomeMappings.get(i));
        }
    }

    @Test
    void testEmptyMappings() {
        var packet = new MapperSyncPacket("empty_world", new ArrayList<>(), new ArrayList<>());
        
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.write(buf);
        
        var deserialized = new MapperSyncPacket(buf);
        
        assertEquals("empty_world", deserialized.worldId);
        assertTrue(deserialized.blockStateMappings.isEmpty());
        assertTrue(deserialized.biomeMappings.isEmpty());
    }

    @Test
    void testLargeMappings() {
        List<byte[]> blockStates = new ArrayList<>();
        // Simulate many block state mappings
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[100]; // Each entry is 100 bytes
            Arrays.fill(data, (byte) i);
            blockStates.add(data);
        }
        
        List<byte[]> biomes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            byte[] data = new byte[20];
            Arrays.fill(data, (byte) (i * 2));
            biomes.add(data);
        }
        
        var original = new MapperSyncPacket("large_world", blockStates, biomes);
        
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        var deserialized = new MapperSyncPacket(buf);
        
        assertEquals(1000, deserialized.blockStateMappings.size());
        assertEquals(50, deserialized.biomeMappings.size());
        
        // Verify a few entries
        assertArrayEquals(original.blockStateMappings.get(0), deserialized.blockStateMappings.get(0));
        assertArrayEquals(original.blockStateMappings.get(500), deserialized.blockStateMappings.get(500));
        assertArrayEquals(original.blockStateMappings.get(999), deserialized.blockStateMappings.get(999));
    }

    @Test
    void testResourceLocationId() {
        assertNotNull(MapperSyncPacket.ID);
        assertEquals("voxy", MapperSyncPacket.ID.getNamespace());
        assertEquals("mapper_sync", MapperSyncPacket.ID.getPath());
    }
}
