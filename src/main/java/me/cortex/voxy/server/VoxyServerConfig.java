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
    public boolean syncLodsToPlayers = true;
    public int serviceThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    public int syncPollTimeoutMs = 100;
    public int chunkModificationQueuePollingRateMs = 500;

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
