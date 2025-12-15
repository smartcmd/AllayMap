package me.daoge.allaymap.render;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;

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
 *
 * Zoom levels (tile sizes double each level, no compression):
 * - zoom 0: 1 chunk = 1 tile (16x16 pixels)
 * - zoom 1: 2x2 chunks = 1 tile (32x32 pixels)
 * - zoom 2: 4x4 chunks = 1 tile (64x64 pixels)
 * - zoom 3: 8x8 chunks = 1 tile (128x128 pixels)
 * - zoom 4: 16x16 chunks = 1 tile (256x256 pixels)
 * - zoom 5: 32x32 chunks = 1 tile (512x512 pixels)
 */
@Slf4j
public class MapTileManager {

    public static final int CHUNK_TILE_SIZE = 16; // Each chunk tile is 16x16 pixels
    public static final int MAX_ZOOM = 5;  // Max zoom level (32x32 chunks per tile at zoom 5)

    /**
     * Get the tile size for a specific zoom level.
     * Tile size doubles each zoom level: 16, 32, 64, 128, 256, 512.
     */
    public static int getTileSize(int zoom) {
        return CHUNK_TILE_SIZE << zoom;
    }

    @Getter
    private final Path tilesDirectory;
    private final MapRenderer renderer;
    @Getter
    private final RenderQueue renderQueue;
    private final Map<String, CompletableFuture<BufferedImage>> renderingTasks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<BufferedImage>> tileLoadingTasks = new ConcurrentHashMap<>();

    public MapTileManager(Path dataDir) {
        this.tilesDirectory = dataDir.resolve("tiles");
        this.renderer = new MapRenderer();
        this.renderQueue = new RenderQueue();

        try {
            Files.createDirectories(tilesDirectory);
        } catch (IOException e) {
            log.error("Failed to create tiles directory", e);
        }
    }

    /**
     * Process all dirty chunks in the render queue.
     * Should be called periodically by a scheduled task.
     */
    public void processDirtyChunks() {
        for (var world : Server.getInstance().getWorldPool().getWorlds().values()) {
            for (var dimension : world.getDimensions().values()) {
                Set<Long> dirtyChunks = renderQueue.pollDirtyChunks(dimension);
                if (dirtyChunks.isEmpty()) continue;

                log.info("Processing {} dirty chunks in {}", dirtyChunks.size(), world.getName());

                for (long chunkKey : dirtyChunks) {
                    int chunkX = RenderQueue.getChunkX(chunkKey);
                    int chunkZ = RenderQueue.getChunkZ(chunkKey);

                    // Render and save the chunk tile (zoom 0)
                    renderAndSaveChunk(dimension, chunkX, chunkZ);
                }
            }
        }
    }

    /**
     * Render a chunk and save it to disk.
     * This method is thread-safe and ensures each chunk is only rendered once at a time.
     */
    private void renderAndSaveChunk(Dimension dimension, int chunkX, int chunkZ) {
        String dimensionName = getDimensionName(dimension);
        String key = dimensionName + "/0/" + chunkX + "_" + chunkZ;

        renderingTasks.computeIfAbsent(key, k -> {
            CompletableFuture<BufferedImage> future = renderer.renderChunk(dimension, chunkX, chunkZ)
                .thenApply(image -> {
                    // Save zoom level 0 (chunk tile)
                    saveTile(dimensionName, 0, chunkX, chunkZ, image);

                    // Mark this chunk as rendered
                    renderQueue.markChunkRendered(dimension, chunkX, chunkZ);

                    return image;
                })
                .exceptionally(e -> {
                    log.error("Failed to render chunk ({}, {})", chunkX, chunkZ, e);
                    return null;
                });

            // Remove from map when completed to allow future re-renders
            return future.whenComplete((result, error) -> renderingTasks.remove(key));
        });
    }

    /**
     * Get a tile for a specific position and zoom level.
     * At zoom 0, coordinates are chunk coordinates and tiles are cached.
     * At higher zoom levels, tiles are generated on-the-fly from lower zoom tiles.
     */
    public CompletableFuture<BufferedImage> getTile(Dimension dimension, int tileX, int tileZ, int zoom) {
        String dimensionName = getDimensionName(dimension);

        // For zoom > 0, always generate on-the-fly (no caching)
        if (zoom > 0) {
            return generateZoomedTile(dimension, tileX, tileZ, zoom);
        }

        // For zoom 0, check if currently rendering first
        String key = dimensionName + "/0/" + tileX + "_" + tileZ;

        // Check if this chunk is currently being rendered
        CompletableFuture<BufferedImage> rendering = renderingTasks.get(key);
        if (rendering != null) {
            return rendering;
        }

        // Check if already loading
        CompletableFuture<BufferedImage> existing = tileLoadingTasks.get(key);
        if (existing != null) {
            return existing;
        }

        // Try to load from disk
        Path tilePath = getTilePath(dimensionName, 0, tileX, tileZ);
        CompletableFuture<BufferedImage> future;

        if (Files.exists(tilePath)) {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ImageIO.read(tilePath.toFile());
                } catch (IOException e) {
                    log.warn("Failed to load cached tile {}", tilePath, e);
                    return createEmptyTile(0);
                }
            }, Server.getInstance().getVirtualThreadPool());
        } else {
            future = CompletableFuture.completedFuture(createEmptyTile());
        }

        // Remove from cache when completed
        future = future.whenComplete((result, error) -> tileLoadingTasks.remove(key));

        CompletableFuture<BufferedImage> previous = tileLoadingTasks.putIfAbsent(key, future);
        return previous != null ? previous : future;
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
                log.error("Failed to generate zoom {} tile ({}, {})", zoom, tileX, tileZ, e);
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
            log.error("Failed to save tile {}", tilePath, e);
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

    /**
     * Clear all cached tiles and reset rendered tracking.
     * Waits for all in-progress tasks to complete before clearing.
     */
    public void clearCache() {
        // Wait for all rendering tasks to complete
        CompletableFuture<?>[] renderingFutures = renderingTasks.values().toArray(new CompletableFuture[0]);
        CompletableFuture<?>[] loadingFutures = tileLoadingTasks.values().toArray(new CompletableFuture[0]);

        if (renderingFutures.length > 0 || loadingFutures.length > 0) {
            log.info("Waiting for {} rendering and {} loading tasks to complete before clearing cache",
                    renderingFutures.length, loadingFutures.length);
            try {
                CompletableFuture.allOf(renderingFutures).join();
                CompletableFuture.allOf(loadingFutures).join();
            } catch (Exception e) {
                log.warn("Some tasks failed while waiting for completion", e);
            }
        }

        renderQueue.clear();

        try {
            if (Files.exists(tilesDirectory)) {
                try (var paths = Files.walk(tilesDirectory)) {
                    paths.sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete {}", path);
                            }
                        });
                }
            }
            Files.createDirectories(tilesDirectory);
        } catch (IOException e) {
            log.error("Failed to clear tile cache", e);
        }
    }
}
