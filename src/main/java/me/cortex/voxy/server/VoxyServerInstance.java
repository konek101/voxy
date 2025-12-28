package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyServerInstance extends VoxyInstance {
    private final SectionStorageConfig storageConfig;
    private final Path basePath;
    private final MinecraftServer server;
    private final ServerLodSyncService syncService;

    public VoxyServerInstance(MinecraftServer server) {
        super();
        this.server = server;
        this.basePath = getBasePath(server);
        this.storageConfig = getCreateStorageConfig(this.basePath);
        this.syncService = new ServerLodSyncService(this);
        this.setNumThreads(VoxyServerConfig.CONFIG.serviceThreads);
        Logger.info("VoxyServerInstance created with base path: " + this.basePath);
    }

    public ServerLodSyncService getSyncService() {
        return this.syncService;
    }

    public SectionSavingService getSavingService() {
        return this.savingService;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    public static SectionStorageConfig getCreateStorageConfig(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var json = path.resolve("config.json");
        Config config = null;
        if (Files.exists(json)) {
            try {
                config = Serialization.GSON.fromJson(Files.readString(json), Config.class);
                if (config == null) {
                    Logger.error("Server config deserialization null, reverting to default");
                } else {
                    if (config.sectionStorageConfig == null) {
                        Logger.error("Server config section storage null, reverting to default");
                        config = null;
                    }
                }
            } catch (Exception e) {
                Logger.error("Failed to load the server storage configuration file, resetting it to default", e);
            }
        }

        if (config == null) {
            config = DEFAULT_STORAGE_CONFIG;
        }
        try {
            Files.writeString(json, Serialization.GSON.toJson(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the server config, aborting!", e);
        }
        return config.sectionStorageConfig;
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public void shutdown() {
        Logger.info("Shutting down VoxyServerInstance");
        this.syncService.shutdown();
        super.shutdown();
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();

        var baseDB = new RocksDBStorageBackend.Config();

        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;
        config.sectionStorageConfig = serializer;

        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
    }
}
