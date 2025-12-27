package me.cortex.voxy.server.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from server to client when a LOD section is deleted/cleared.
 */
public class LodSectionDeletePacket {
    public static final ResourceLocation ID = new ResourceLocation("voxy", "lod_section_delete");

    public final long sectionKey;
    public final String worldId;

    public LodSectionDeletePacket(long sectionKey, String worldId) {
        this.sectionKey = sectionKey;
        this.worldId = worldId;
    }

    public LodSectionDeletePacket(FriendlyByteBuf buf) {
        this.sectionKey = buf.readLong();
        this.worldId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(this.sectionKey);
        buf.writeUtf(this.worldId);
    }
}
