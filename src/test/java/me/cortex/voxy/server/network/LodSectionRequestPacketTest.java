package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LodSectionRequestPacket serialization and deserialization.
 */
class LodSectionRequestPacketTest {

    @Test
    void testPacketCreation() {
        String worldId = "minecraft:overworld";

        LodSectionRequestPacket packet = new LodSectionRequestPacket(worldId);

        assertEquals(worldId, packet.worldId);
    }

    @Test
    void testPacketSerialization() {
        String worldId = "minecraft:the_nether";

        LodSectionRequestPacket original = new LodSectionRequestPacket(worldId);
        
        // Serialize
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        // Deserialize
        LodSectionRequestPacket deserialized = new LodSectionRequestPacket(buf);
        
        assertEquals(original.worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketWithEmptyWorldId() {
        String worldId = "";

        LodSectionRequestPacket original = new LodSectionRequestPacket(worldId);
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        LodSectionRequestPacket deserialized = new LodSectionRequestPacket(buf);
        
        assertEquals("", deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketWithLongWorldId() {
        // Test with a very long world ID (like a custom dimension)
        String worldId = "mymod:custom_dimension_with_a_very_long_name_for_testing_purposes";

        LodSectionRequestPacket original = new LodSectionRequestPacket(worldId);
        
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        
        LodSectionRequestPacket deserialized = new LodSectionRequestPacket(buf);
        
        assertEquals(worldId, deserialized.worldId);
        
        buf.release();
    }

    @Test
    void testPacketResourceLocationId() {
        assertNotNull(LodSectionRequestPacket.ID);
        assertEquals("voxy", LodSectionRequestPacket.ID.getNamespace());
        assertEquals("lod_section_request", LodSectionRequestPacket.ID.getPath());
    }

    @Test
    void testMultipleSerializationRoundtrips() {
        String[] worldIds = {
            "minecraft:overworld",
            "minecraft:the_nether",
            "minecraft:the_end",
            "custom:dimension_1",
            "another:world"
        };

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

        for (String worldId : worldIds) {
            buf.clear();
            LodSectionRequestPacket original = new LodSectionRequestPacket(worldId);
            original.write(buf);
            LodSectionRequestPacket deserialized = new LodSectionRequestPacket(buf);
            assertEquals(worldId, deserialized.worldId, "Failed for worldId: " + worldId);
        }
        
        buf.release();
    }
}
