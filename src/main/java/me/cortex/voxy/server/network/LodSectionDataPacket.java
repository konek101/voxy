package me.cortex.voxy.server.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from server to client containing LOD section data.
 * The data format follows the SaveLoadSystem3 serialization format.
 */
public class LodSectionDataPacket {
    public static final ResourceLocation ID = new ResourceLocation("voxy", "lod_section_data");

    public final long sectionKey;
    public final byte[] compressedData;
    public final String worldId;

    public LodSectionDataPacket(long sectionKey, byte[] compressedData, String worldId) {
        this.sectionKey = sectionKey;
        this.compressedData = compressedData;
        this.worldId = worldId;
    }

    public LodSectionDataPacket(FriendlyByteBuf buf) {
        this.sectionKey = buf.readLong();
        this.compressedData = buf.readByteArray();
        this.worldId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(this.sectionKey);
        buf.writeByteArray(this.compressedData);
        buf.writeUtf(this.worldId);
    }
}
