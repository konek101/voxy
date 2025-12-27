package me.cortex.voxy.server;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyServerConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyServerConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean generateLodsOnChunkGeneration = true;
    public boolean generateLodsOnChunkModification = true;
    // WARNING: Syncing LODs to players is currently experimental and may cause client crashes
    // due to block state ID mismatches between server and client Mappers.
    // Only enable if all clients have matching mod configurations.
    public boolean syncLodsToPlayers = false;
    public boolean acceptLodsFromClients = true;
    public int serviceThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    public int syncPollTimeoutMs = 100;
    public int chunkModificationQueuePollingRateMs = 500;
    
    // Server load thresholds for offloading LOD generation to clients
    public boolean offloadToClientsWhenBusy = true;
    public int maxPendingLodsBeforeOffload = 1000;
    public float serverTickMsThreshold = 45.0f; // offload if server tick takes longer than this

    private static VoxyServerConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var conf = GSON.fromJson(reader, VoxyServerConfig.class);
                if (conf != null) {
                    conf.save();
                    return conf;
                } else {
                    Logger.error("Failed to load voxy server config, resetting");
                }
            } catch (IOException e) {
                Logger.error("Could not parse server config", e);
            }
        }
        var config = new VoxyServerConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(getConfigPath().getParent());
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write server config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("voxy-server-config.json");
    }
}
