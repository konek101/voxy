package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LodSectionDataPacket serialization and deserialization.
 */
class LodSectionDataPacketTest {

    @Test
    void testPacketCreation() {
        long sectionKey = 123456789L;
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        String worldId = "minecraft:overworld";

        LodSectionDataPacket packet = new LodSectionDataPacket(sectionKey, data, worldId);

        assertEquals(sectionKey, packet.sectionKey);
        assertArrayEquals(data, packet.compressedData);
        assertEquals(worldId, packet.worldId);
    }

    @Test
    void testPacketSerialization() {
        long sectionKey = 987654321L;
        byte[] data = new byte[]{10, 20, 30, 40, 50};
        String worldId = "minecraft:the_nether";

        LodSectionDataPacket original = new LodSectionDataPacket(sectionKey, data, worldId);
        
        // Serialize
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        // Deserialize
        LodSectionDataPacket deserialized = new LodSectionDataPacket(buf);
        
        assertEquals(original.sectionKey, deserialized.sectionKey);
        assertArrayEquals(original.compressedData, deserialized.compressedData);
        assertEquals(original.worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketWithEmptyData() {
        long sectionKey = 0L;
        byte[] data = new byte[0];
        String worldId = "";

        LodSectionDataPacket original = new LodSectionDataPacket(sectionKey, data, worldId);
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        LodSectionDataPacket deserialized = new LodSectionDataPacket(buf);
        
        assertEquals(original.sectionKey, deserialized.sectionKey);
        assertArrayEquals(original.compressedData, deserialized.compressedData);
        assertEquals(original.worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketWithLargeData() {
        long sectionKey = Long.MAX_VALUE;
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        String worldId = "minecraft:the_end";

        LodSectionDataPacket original = new LodSectionDataPacket(sectionKey, data, worldId);
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        LodSectionDataPacket deserialized = new LodSectionDataPacket(buf);
        
        assertEquals(original.sectionKey, deserialized.sectionKey);
        assertArrayEquals(original.compressedData, deserialized.compressedData);
        assertEquals(original.worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketResourceLocationId() {
        assertNotNull(LodSectionDataPacket.ID);
        assertEquals("voxy", LodSectionDataPacket.ID.getNamespace());
        assertEquals("lod_section_data", LodSectionDataPacket.ID.getPath());
    }
}
