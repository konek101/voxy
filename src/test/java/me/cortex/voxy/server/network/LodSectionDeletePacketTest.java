package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LodSectionDeletePacket serialization and deserialization.
 */
class LodSectionDeletePacketTest {

    @Test
    void testPacketCreation() {
        long sectionKey = 123456789L;
        String worldId = "minecraft:overworld";

        LodSectionDeletePacket packet = new LodSectionDeletePacket(sectionKey, worldId);

        assertEquals(sectionKey, packet.sectionKey);
        assertEquals(worldId, packet.worldId);
    }

    @Test
    void testPacketSerialization() {
        long sectionKey = 987654321L;
        String worldId = "minecraft:the_nether";

        LodSectionDeletePacket original = new LodSectionDeletePacket(sectionKey, worldId);
        
        // Serialize
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        // Deserialize
        LodSectionDeletePacket deserialized = new LodSectionDeletePacket(buf);
        
        assertEquals(original.sectionKey, deserialized.sectionKey);
        assertEquals(original.worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketWithMinMaxValues() {
        // Test with minimum values
        LodSectionDeletePacket min = new LodSectionDeletePacket(Long.MIN_VALUE, "");
        FriendlyByteBuf bufMin = new FriendlyByteBuf(Unpooled.buffer());
        min.write(bufMin);
        LodSectionDeletePacket deserializedMin = new LodSectionDeletePacket(bufMin);
        assertEquals(Long.MIN_VALUE, deserializedMin.sectionKey);
        assertEquals("", deserializedMin.worldId);
        bufMin.release();

        // Test with maximum values
        LodSectionDeletePacket max = new LodSectionDeletePacket(Long.MAX_VALUE, "minecraft:the_end");
        FriendlyByteBuf bufMax = new FriendlyByteBuf(Unpooled.buffer());
        max.write(bufMax);
        LodSectionDeletePacket deserializedMax = new LodSectionDeletePacket(bufMax);
        assertEquals(Long.MAX_VALUE, deserializedMax.sectionKey);
        assertEquals("minecraft:the_end", deserializedMax.worldId);
        bufMax.release();
    }

    @Test
    void testPacketResourceLocationId() {
        assertNotNull(LodSectionDeletePacket.ID);
        assertEquals("voxy", LodSectionDeletePacket.ID.getNamespace());
        assertEquals("lod_section_delete", LodSectionDeletePacket.ID.getPath());
    }
}
