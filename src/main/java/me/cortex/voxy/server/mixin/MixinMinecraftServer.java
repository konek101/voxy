package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.VoxyServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to track server tick time for load-based LOD generation offloading.
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
    private long voxy$tickStartTime;
    
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void voxy$onTickStart(CallbackInfo ci) {
        voxy$tickStartTime = System.nanoTime();
    }
    
    @Inject(method = "tickServer", at = @At("RETURN"))
    private void voxy$onTickEnd(CallbackInfo ci) {
        long tickTime = System.nanoTime() - voxy$tickStartTime;
        VoxyServer.updateTickTime(tickTime);
    }
}
