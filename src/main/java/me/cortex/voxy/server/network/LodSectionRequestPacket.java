package me.cortex.voxy.server.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from client to server to request LOD data for a specific world.
 */
public class LodSectionRequestPacket {
    public static final ResourceLocation ID = new ResourceLocation("voxy", "lod_section_request");

    public final String worldId;

    public LodSectionRequestPacket(String worldId) {
        this.worldId = worldId;
    }

    public LodSectionRequestPacket(FriendlyByteBuf buf) {
        this.worldId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(this.worldId);
    }
}
