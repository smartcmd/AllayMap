package me.daoge.allaymap;

import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.daoge.allaymap.httpd.MapHttpServer;
import me.daoge.allaymap.listener.WorldEventListener;
import me.daoge.allaymap.render.MapRenderer;
import me.daoge.allaymap.render.MapTileManager;
import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AllayMap - A minimalistic and lightweight world map viewer for Allay servers.
 * Uses vanilla map rendering style similar to squaremap.
 *
 * @author daoge_cmd
 */
@Slf4j
public class AllayMap extends Plugin {

    @Getter
    private static AllayMap instance;

    @Getter
    private AllayMapConfig config;

    @Getter
    private MapTileManager tileManager;

    @Getter
    private MapHttpServer httpServer;

    @Override
    public void onLoad() {
        instance = this;
        log.info("AllayMap loading...");

        // Load configuration
        Path dataDir = getPluginContainer().dataFolder();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directory", e);
        }

        config = ConfigManager.create(AllayMapConfig.class, cfg -> {
            cfg.withConfigurer(new YamlSnakeYamlConfigurer());
            cfg.withBindFile(dataDir.resolve("config.yml"));
            cfg.withRemoveOrphans(true);
            cfg.saveDefaults();
            cfg.load(true);
        });
    }

    @Override
    public void onEnable() {
        // Initialize colormap resources
        MapRenderer.initColormaps();

        // Initialize tile manager
        tileManager = new MapTileManager(getPluginContainer().dataFolder());

        // Register world event listener for chunk/block changes
        WorldEventListener eventListener = new WorldEventListener(tileManager.getRenderQueue());
        Server.getInstance().getEventBus().registerListener(eventListener);
        log.info("Registered world event listener for map updates");

        // Schedule periodic render task
        int updateIntervalTicks = config.updateInterval() * 20; // Convert seconds to ticks
        Server.getInstance().getScheduler().scheduleRepeating(this, () -> {
            tileManager.processDirtyChunks();
            return true; // Continue running
        }, updateIntervalTicks);
        log.info("Scheduled map render task every {} seconds", config.updateInterval());

        // Start HTTP server
        httpServer = new MapHttpServer(this, tileManager, config.httpPort());
        try {
            httpServer.start();
            log.info("AllayMap web interface available at http://localhost:{}", config.httpPort());
        } catch (IOException e) {
            log.error("Failed to start HTTP server on port {}", config.httpPort(), e);
            log.info("Try changing the port in config.yml");
            return;
        }

        // Register commands
        registerCommands();

        log.info("AllayMap enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop();
        }
        log.info("AllayMap disabled");
    }

    private void registerCommands() {
        // TODO: Add commands for manual rendering, cache clearing, etc.
        // For now, the web interface handles everything
    }

    @Override
    public boolean isReloadable() {
        return true;
    }

    @Override
    public void reload() {
        log.info("Reloading AllayMap...");

        // Reload config
        config.load(true);

        // Clear tile cache
        if (tileManager != null) {
            tileManager.clearCache();
        }

        log.info("AllayMap reloaded");
    }
}
