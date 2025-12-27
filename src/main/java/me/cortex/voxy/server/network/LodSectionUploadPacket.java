package me.cortex.voxy.server.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server packet for uploading LOD section data from client to server.
 * This allows clients to generate LODs locally and contribute them to the server's LOD database.
 */
public class LodSectionUploadPacket {
    public static final ResourceLocation ID = new ResourceLocation("voxy", "lod_section_upload");

    public final long sectionKey;
    public final byte[] compressedData;
    public final String worldId;

    public LodSectionUploadPacket(long sectionKey, byte[] compressedData, String worldId) {
        this.sectionKey = sectionKey;
        this.compressedData = compressedData;
        this.worldId = worldId;
    }

    public LodSectionUploadPacket(FriendlyByteBuf buf) {
        this.sectionKey = buf.readLong();
        int dataLength = buf.readVarInt();
        this.compressedData = new byte[dataLength];
        buf.readBytes(this.compressedData);
        this.worldId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(this.sectionKey);
        buf.writeVarInt(this.compressedData.length);
        buf.writeBytes(this.compressedData);
        buf.writeUtf(this.worldId);
    }
}
