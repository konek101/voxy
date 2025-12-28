package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LodSectionUploadPacket serialization and deserialization.
 */
public class LodSectionUploadPacketTest {

    @Test
    void testPacketCreation() {
        byte[] testData = {1, 2, 3, 4, 5};
        LodSectionUploadPacket packet = new LodSectionUploadPacket(12345L, testData, "test:world");
        
        assertEquals(12345L, packet.sectionKey);
        assertArrayEquals(testData, packet.compressedData);
        assertEquals("test:world", packet.worldId);
    }

    @Test
    void testPacketSerializationRoundtrip() {
        byte[] testData = {10, 20, 30, 40, 50};
        LodSectionUploadPacket original = new LodSectionUploadPacket(67890L, testData, "minecraft:overworld");
        
        // Serialize
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        // Deserialize
        LodSectionUploadPacket deserialized = new LodSectionUploadPacket(buf);
        
        assertEquals(original.sectionKey, deserialized.sectionKey);
        assertArrayEquals(original.compressedData, deserialized.compressedData);
        assertEquals(original.worldId, deserialized.worldId);
    }

    @Test
    void testEmptyData() {
        byte[] emptyData = {};
        LodSectionUploadPacket packet = new LodSectionUploadPacket(0L, emptyData, "empty:world");
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.write(buf);
        
        LodSectionUploadPacket deserialized = new LodSectionUploadPacket(buf);
        
        assertEquals(0L, deserialized.sectionKey);
        assertEquals(0, deserialized.compressedData.length);
        assertEquals("empty:world", deserialized.worldId);
    }

    @Test
    void testLargeData() {
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        
        LodSectionUploadPacket packet = new LodSectionUploadPacket(Long.MAX_VALUE, largeData, "large:world");
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.write(buf);
        
        LodSectionUploadPacket deserialized = new LodSectionUploadPacket(buf);
        
        assertEquals(Long.MAX_VALUE, deserialized.sectionKey);
        assertArrayEquals(largeData, deserialized.compressedData);
        assertEquals("large:world", deserialized.worldId);
    }

    @Test
    void testResourceLocationId() {
        assertNotNull(LodSectionUploadPacket.ID);
        assertEquals("voxy", LodSectionUploadPacket.ID.getNamespace());
        assertEquals("lod_section_upload", LodSectionUploadPacket.ID.getPath());
    }
}
