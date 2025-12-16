package me.daoge.allaymap;

import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import lombok.Getter;
import me.daoge.allaymap.httpd.MapHttpServer;
import me.daoge.allaymap.listener.WorldEventListener;
import me.daoge.allaymap.render.MapTileManager;
import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AllayMap - A minimalistic and lightweight world map viewer for Allay servers.
 *
 * @author daoge_cmd
 */
@Getter
public class AllayMap extends Plugin {

    @Getter
    private static AllayMap instance;

    {
        instance = this;
    }

    private AllayMapConfig config;
    private MapTileManager tileManager;
    private MapHttpServer httpServer;

    @Override
    public void onLoad() {
        this.pluginLogger.info("AllayMap loading...");

        // Load configuration
        Path dataDir = getPluginContainer().dataFolder();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            this.pluginLogger.error("Failed to create data directory", e);
        }

        this.config = ConfigManager.create(AllayMapConfig.class, cfg -> {
            cfg.withConfigurer(new YamlSnakeYamlConfigurer());
            cfg.withBindFile(dataDir.resolve("config.yml"));
            cfg.withRemoveOrphans(true);
            cfg.saveDefaults();
            cfg.load(true);
        });
    }

    @Override
    public void onEnable() {
        // Initialize tile manager
        this.tileManager = new MapTileManager(getPluginContainer().dataFolder());

        // Register world event listener for chunk/block changes
        Server.getInstance().getEventBus().registerListener(new WorldEventListener(this.tileManager.getRenderQueue()));

        // Start HTTP server
        this.httpServer = new MapHttpServer(this, this.tileManager, this.config.httpPort());
        try {
            this.httpServer.start();
            this.pluginLogger.info("AllayMap web interface available at http://localhost:{}", this.config.httpPort());
        } catch (IOException e) {
            this.pluginLogger.error("Failed to start HTTP server on port {}", this.config.httpPort(), e);
            this.pluginLogger.info("Try changing the port in config.yml");
        }

        // Register commands
        registerCommands();

        this.pluginLogger.info("AllayMap enabled successfully!");
    }

    private void registerCommands() {
        // TODO: Add commands for manual rendering, cache clearing, etc.
        // For now, the web interface handles everything
    }

    @Override
    public void onDisable() {
        this.httpServer.stop();
        this.pluginLogger.info("AllayMap disabled");
    }
}
