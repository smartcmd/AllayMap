package me.daoge.allaymap.render;

import lombok.Getter;
import me.daoge.allaymap.AllayMap;
import org.allaymc.api.server.Server;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.Dimension;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages map tiles with chunk-based rendering and multi-zoom support.
 */
public class MapTileManager {

    private static final int CHUNK_TILE_SIZE = 16;
    private final Logger logger;
    private final Path tilesDirectory;
    private final MapRenderer renderer;
    @Getter
    private final RenderQueue renderQueue;
    private final Map<String, CompletableFuture<BufferedImage>> tileTasks;

    public MapTileManager(Path dataDir) {
        var plugin = AllayMap.getInstance();
        this.logger = plugin.getPluginLogger();
        this.tilesDirectory = dataDir.resolve("tiles");
        this.renderer = new MapRenderer();
        this.renderQueue = new RenderQueue();
        this.tileTasks = new ConcurrentHashMap<>();

        try {
            Files.createDirectories(tilesDirectory);
        } catch (IOException e) {
            this.logger.error("Failed to create tiles directory", e);
        }

        // Schedule periodic render task
        var updateInterval = plugin.getConfig().updateInterval();
        Server.getInstance().getScheduler().scheduleRepeating(plugin, () -> {
            processDirtyChunks();
            return true; // Continue running
        }, updateInterval * 20);
        this.logger.info("Scheduled map render task every {} seconds", updateInterval);
    }

    /**
     * Get the tile size for a specific zoom level.
     * Tile size doubles each zoom level: 16, 32, 64, 128, 256, 512.
     */
    private static int getTileSize(int zoom) {
        return CHUNK_TILE_SIZE << zoom;
    }

    /**
     * Process all dirty chunks in the render queue.
     * Should be called periodically by a scheduled task.
     */
    private void processDirtyChunks() {
        for (var world : Server.getInstance().getWorldPool().getWorlds().values()) {
            for (var dimension : world.getDimensions().values()) {
                Set<Long> dirtyChunks = renderQueue.pollDirtyChunks(dimension);
                if (dirtyChunks.isEmpty()) {
                    continue;
                }

                logger.debug("Processing {} dirty chunks in {}", dirtyChunks.size(), world.getName());

                for (long chunkKey : dirtyChunks) {
                    int chunkX = HashUtils.getXFromHashXZ(chunkKey);
                    int chunkZ = HashUtils.getZFromHashXZ(chunkKey);

                    // Render and save the chunk tile (zoom 0)
                    renderAndSaveChunk(dimension, chunkX, chunkZ);
                }
            }
        }
    }

    /**
     * Render a chunk and save it to disk.
     */
    private void renderAndSaveChunk(Dimension dimension, int chunkX, int chunkZ) {
        String dimensionName = getDimensionName(dimension);
        String key = "render:" + getTilePath(dimensionName, 0, chunkX, chunkZ);

        tileTasks.computeIfAbsent(key, k -> {
            CompletableFuture<BufferedImage> future = renderer.renderChunk(dimension, chunkX, chunkZ)
                    .thenApply(image -> {
                        saveTile(dimensionName, 0, chunkX, chunkZ, image);
                        return image;
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to render chunk ({}, {})", chunkX, chunkZ, e);
                        return null;
                    });

            future.whenComplete((result, error) -> tileTasks.remove(key));
            return future;
        });
    }

    public int getTileTaskCount() {
        return tileTasks.size();
    }

    /**
     * Get a tile for a specific position and zoom level. This method should be thread-safe.
     */
    public CompletableFuture<BufferedImage> getTile(Dimension dimension, int tileX, int tileZ, int zoom) {
        if (zoom > 0) {
            return generateZoomedTile(dimension, tileX, tileZ, zoom);
        }

        var tilePath = getTilePath(getDimensionName(dimension), 0, tileX, tileZ);

        // First check if there's an ongoing render task for this tile
        var renderKey = "render:" + tilePath;
        var renderTask = tileTasks.get(renderKey);
        if (renderTask != null) {
            return renderTask;
        }

        // Use computeIfAbsent for atomic load task creation
        var loadKey = "load:" + tilePath;
        return tileTasks.computeIfAbsent(loadKey, k -> {
            CompletableFuture<BufferedImage> future;
            if (Files.exists(tilePath)) {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return ImageIO.read(tilePath.toFile());
                    } catch (IOException e) {
                        logger.warn("Failed to load cached tile {}", tilePath, e);
                        return createEmptyTile(0);
                    }
                }, Server.getInstance().getVirtualThreadPool());
            } else {
                future = CompletableFuture.completedFuture(createEmptyTile());
            }

            future.whenComplete((result, error) -> tileTasks.remove(loadKey));
            return future;
        });
    }

    /**
     * Generate a zoomed-out tile by combining 4 tiles from a lower zoom level.
     * Generated on-the-fly without caching.
     */
    private CompletableFuture<BufferedImage> generateZoomedTile(Dimension dimension, int tileX, int tileZ, int zoom) {
        int resultSize = getTileSize(zoom);
        int sourceZoom = zoom - 1;
        int x2 = tileX * 2;
        int z2 = tileZ * 2;

        CompletableFuture<BufferedImage> tile00 = getTile(dimension, x2, z2, sourceZoom);
        CompletableFuture<BufferedImage> tile10 = getTile(dimension, x2 + 1, z2, sourceZoom);
        CompletableFuture<BufferedImage> tile01 = getTile(dimension, x2, z2 + 1, sourceZoom);
        CompletableFuture<BufferedImage> tile11 = getTile(dimension, x2 + 1, z2 + 1, sourceZoom);

        return CompletableFuture.allOf(tile00, tile10, tile01, tile11)
                .thenApply(v -> {
                    BufferedImage result = new BufferedImage(resultSize, resultSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = result.createGraphics();

                    int halfResult = resultSize / 2;
                    try {
                        BufferedImage img00 = tile00.join();
                        BufferedImage img10 = tile10.join();
                        BufferedImage img01 = tile01.join();
                        BufferedImage img11 = tile11.join();

                        // Draw each source tile scaled to quarter of result size
                        g.drawImage(img00, 0, 0, halfResult, halfResult, 0, 0, img00.getWidth(), img00.getHeight(), null);
                        g.drawImage(img10, halfResult, 0, resultSize, halfResult, 0, 0, img10.getWidth(), img10.getHeight(), null);
                        g.drawImage(img01, 0, halfResult, halfResult, resultSize, 0, 0, img01.getWidth(), img01.getHeight(), null);
                        g.drawImage(img11, halfResult, halfResult, resultSize, resultSize, 0, 0, img11.getWidth(), img11.getHeight(), null);
                    } finally {
                        g.dispose();
                    }

                    return result;
                })
                .exceptionally(e -> {
                    logger.error("Failed to generate zoom {} tile ({}, {})", zoom, tileX, tileZ, e);
                    return createEmptyTile(zoom);
                });
    }

    /**
     * Save a tile to disk.
     */
    private void saveTile(String dimensionName, int zoom, int tileX, int tileZ, BufferedImage image) {
        if (image == null) return;

        Path tilePath = getTilePath(dimensionName, zoom, tileX, tileZ);
        try {
            Files.createDirectories(tilePath.getParent());
            ImageIO.write(image, "png", tilePath.toFile());
        } catch (IOException e) {
            logger.error("Failed to save tile {}", tilePath, e);
        }
    }

    /**
     * Get the path for a tile.
     */
    private Path getTilePath(String dimensionName, int zoom, int tileX, int tileZ) {
        return tilesDirectory.resolve(dimensionName).resolve(String.valueOf(zoom)).resolve(tileX + "_" + tileZ + ".png");
    }

    /**
     * Get dimension name for path.
     */
    private String getDimensionName(Dimension dimension) {
        return dimension.getWorld().getName() + "_" + dimension.getDimensionInfo().toString();
    }

    /**
     * Create an empty tile for zoom level 0 (16x16).
     */
    private BufferedImage createEmptyTile() {
        return createEmptyTile(0);
    }

    /**
     * Create an empty tile for a specific zoom level.
     */
    private BufferedImage createEmptyTile(int zoom) {
        int size = getTileSize(zoom);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int color = new Color(0x1a1a2e).getRGB();
        for (int i = 0; i < size * size; i++) {
            image.setRGB(i % size, i / size, color);
        }
        return image;
    }
}
