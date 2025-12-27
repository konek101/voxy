package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.VoxyServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {
    @Shadow public abstract net.minecraft.world.level.Level getLevel();

    /**
     * Intercept when a chunk is first set as loaded (full chunk).
     * This triggers LOD generation on the server.
     */
    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void voxy$onChunkLoaded(boolean loaded, CallbackInfo ci) {
        if (loaded) {
            var level = this.getLevel();
            if (level instanceof ServerLevel serverLevel) {
                VoxyServer.onChunkGenerated(serverLevel, (LevelChunk)(Object)this);
            }
        }
    }
}
