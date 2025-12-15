package me.daoge.allaymap.render;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.data.BlockTags;
import org.allaymc.api.block.data.TintMethod;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.biome.BiomeType;
import org.allaymc.api.world.biome.BiomeTypes;
import org.allaymc.api.world.chunk.Chunk;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * MapRenderer handles the rendering of chunks to images following vanilla map style.
 * Based on AllayMC's ItemFilledMapBaseComponentImpl rendering algorithm.
 */
@Slf4j
public class MapRenderer {

    public static final int CHUNK_TILE_SIZE = 16; // 1 pixel per block
    public static final int CHUNK_SIZE = 16;
    public static final int SEA_LEVEL = 62;

    // Color for unloaded/missing chunks
    private static final Color UNLOADED_CHUNK_COLOR = new Color(0x1a1a2e); // Dark blue-gray
    private static final Color BIRCH_FOLIAGE = new Color(0x80a755);
    private static final Color EVERGREEN_FOLIAGE = new Color(0x619961);
    private static final Color DRY_FOLIAGE_SPECIAL_A = new Color(0x7b5334);
    private static final Color DRY_FOLIAGE_SPECIAL_B = new Color(0xa0a69c);
    private static final Color SWAMP_BIOME_FOLIAGE = new Color(0x6a7039);
    private static final Color SWAMP_BIOME_GRASS_A = new Color(0x6a7039);
    private static final Color BIOME_SWAMP_GRASS_B = new Color(0x4c763c);
    private static final Color MANGROVE_SWAMP_BIOME_FOLIAGE = new Color(0x8db127);
    private static final Color ROOFED_FOREST_BIOME_GRASS = new Color(0x507a32);
    private static final Color MESA_BIOME_GRASS = new Color(0x90814d);
    private static final Color MESA_BIOME_FOLIAGE = new Color(0x9e814d);
    private static final Color CHERRY_GROVE_BIOME_PLANT = new Color(0xb6db61);
    private static final Color PALE_GARDEN_BIOME_PLANT = new Color(0x878d76);

    // Colormaps for biome-based coloring (volatile for thread-safe lazy initialization)
    private static volatile BufferedImage FOLIAGE_COLORMAP;
    private static volatile BufferedImage DRY_FOLIAGE_COLORMAP;
    private static volatile BufferedImage GRASS_COLORMAP;

    /**
     * Initialize colormaps from resources
     */
    public static void initColormaps() {
        FOLIAGE_COLORMAP = readColormap("colormap/foliage.png");
        DRY_FOLIAGE_COLORMAP = readColormap("colormap/dry_foliage.png");
        GRASS_COLORMAP = readColormap("colormap/grass.png");
        log.info("Colormaps loaded successfully");
    }

    @SneakyThrows
    private static BufferedImage readColormap(String file) {
        return ImageIO.read(Objects.requireNonNull(MapRenderer.class.getClassLoader().getResource(file)));
    }

    /**
     * Render a single chunk to a 16x16 tile image (1 pixel per block).
     */
    public CompletableFuture<BufferedImage> renderChunk(Dimension dimension, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            Chunk chunk = dimension.getChunkManager().getChunk(chunkX, chunkZ);
            if (chunk == null) {
                return createEmptyChunkTile();
            }

            int startX = chunkX << 4;
            int startZ = chunkZ << 4;
            int[] pixels = new int[CHUNK_SIZE * CHUNK_SIZE];
            int[] lastY = new int[CHUNK_SIZE];

            // First pass: get initial heights for z=0
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int worldX = startX + x;
                int worldZ = startZ - 1;
                HeightResult hr = getTopBlockHeight(dimension, worldX, worldZ);
                lastY[x] = hr != null ? hr.y : SEA_LEVEL;
            }

            // Main rendering pass
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    int worldX = startX + x;
                    int worldZ = startZ + z;

                    Color color = getMapColor(dimension, worldX, worldZ, lastY[x]);

                    HeightResult hr = getTopBlockHeight(dimension, worldX, worldZ);
                    if (hr != null) {
                        lastY[x] = hr.y;
                    }

                    pixels[z * CHUNK_SIZE + x] = color.getRGB();
                }
            }

            BufferedImage image = new BufferedImage(CHUNK_TILE_SIZE, CHUNK_TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, CHUNK_TILE_SIZE, CHUNK_TILE_SIZE, pixels, 0, CHUNK_TILE_SIZE);
            return image;
        }, Server.getInstance().getVirtualThreadPool()).exceptionally(e -> {
            log.error("Error rendering chunk ({}, {})", chunkX, chunkZ, e);
            return createEmptyChunkTile();
        });
    }

    /**
     * Create an empty 16x16 tile (for unloaded chunks)
     */
    public BufferedImage createEmptyChunkTile() {
        BufferedImage image = new BufferedImage(CHUNK_TILE_SIZE, CHUNK_TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        int color = UNLOADED_CHUNK_COLOR.getRGB();
        for (int i = 0; i < CHUNK_TILE_SIZE * CHUNK_TILE_SIZE; i++) {
            image.setRGB(i % CHUNK_TILE_SIZE, i / CHUNK_TILE_SIZE, color);
        }
        return image;
    }

    /**
     * Get the map color for a specific position
     */
    private Color getMapColor(Dimension dimension, int x, int z, int lastY) {
        HeightResult result = getTopBlockHeight(dimension, x, z);
        if (result == null) {
            return UNLOADED_CHUNK_COLOR;
        }

        BlockState blockState = result.state;
        int y = result.y;
        BiomeType biome = dimension.getBiome(x, y, z);

        Color color = computeMapColor(blockState, biome);

        // Check if block is underwater
        if (dimension.getBlockState(x, y + 1, z).getBlockType().hasBlockTag(BlockTags.WATER)) {
            color = applyWaterTint(color, y, biome);
        }

        // Apply shading based on height difference
        if (y < lastY) {
            color = darker(color, 0.85);
        } else if (y > lastY) {
            color = brighter(color, 0.85);
        }

        return color;
    }

    /**
     * Find the top renderable block at a position.
     * Only uses already-loaded chunks, does NOT trigger chunk loading.
     */
    private HeightResult getTopBlockHeight(Dimension dimension, int x, int z) {
        // Only get the chunk if it's already loaded - do NOT load it
        Chunk chunk = dimension.getChunkManager().getChunk(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }

        int chunkX = x & 0xF;
        int chunkZ = z & 0xF;
        int height = chunk.getHeight(chunkX, chunkZ);
        int minHeight = dimension.getDimensionInfo().minHeight();

        while (height >= minHeight) {
            BlockState state = chunk.getBlockState(chunkX, height, chunkZ);
            var mapColor = state.getBlockStateData().mapColor();
            var tintMethod = state.getBlockStateData().tintMethod();

            // Skip water and fully transparent blocks
            if (tintMethod == TintMethod.WATER || (mapColor.getAlpha() == 0 && tintMethod == TintMethod.NONE)) {
                height--;
                continue;
            }

            return new HeightResult(height, state);
        }

        return null;
    }

    /**
     * Compute the map color for a block state
     */
    private Color computeMapColor(BlockState state, BiomeType biome) {
        var data = state.getBlockStateData();
        var tintMethod = data.tintMethod();

        return switch (tintMethod) {
            case NONE, STEM -> data.mapColor();
            case RED_STONE_WIRE -> Color.RED;
            case DEFAULT_FOLIAGE, BIRCH_FOLIAGE, EVERGREEN_FOLIAGE, DRY_FOLIAGE, GRASS -> getPlantColor(tintMethod, biome);
            case WATER -> data.mapColor(); // Water handled separately
        };
    }

    /**
     * Get plant color based on tint method and biome
     */
    private Color getPlantColor(TintMethod tintMethod, BiomeType biome) {
        if (tintMethod == TintMethod.BIRCH_FOLIAGE) {
            return BIRCH_FOLIAGE;
        }

        if (tintMethod == TintMethod.EVERGREEN_FOLIAGE) {
            return EVERGREEN_FOLIAGE;
        }

        // Special biome handling
        if (biome == BiomeTypes.SWAMPLAND || biome == BiomeTypes.SWAMPLAND_MUTATED || biome == BiomeTypes.MANGROVE_SWAMP) {
            if (tintMethod == TintMethod.DRY_FOLIAGE) {
                return DRY_FOLIAGE_SPECIAL_A;
            }
            if (tintMethod == TintMethod.GRASS) {
                return SWAMP_BIOME_GRASS_A;
            }
            return biome == BiomeTypes.MANGROVE_SWAMP ? MANGROVE_SWAMP_BIOME_FOLIAGE : SWAMP_BIOME_FOLIAGE;
        }

        if (biome == BiomeTypes.ROOFED_FOREST || biome == BiomeTypes.ROOFED_FOREST_MUTATED) {
            if (tintMethod == TintMethod.GRASS) {
                return ROOFED_FOREST_BIOME_GRASS;
            }
            if (tintMethod == TintMethod.DRY_FOLIAGE) {
                return DRY_FOLIAGE_SPECIAL_A;
            }
        }

        if (biome == BiomeTypes.MESA || biome == BiomeTypes.MESA_BRYCE ||
            biome == BiomeTypes.MESA_PLATEAU || biome == BiomeTypes.MESA_PLATEAU_MUTATED ||
            biome == BiomeTypes.MESA_PLATEAU_STONE || biome == BiomeTypes.MESA_PLATEAU_STONE_MUTATED) {
            if (tintMethod == TintMethod.GRASS) {
                return MESA_BIOME_GRASS;
            }
            return MESA_BIOME_FOLIAGE;
        }

        if (biome == BiomeTypes.CHERRY_GROVE) {
            return CHERRY_GROVE_BIOME_PLANT;
        }

        if (biome == BiomeTypes.PALE_GARDEN) {
            if (tintMethod == TintMethod.DRY_FOLIAGE) {
                return DRY_FOLIAGE_SPECIAL_B;
            }
            return PALE_GARDEN_BIOME_PLANT;
        }

        // Use colormap for other biomes
        float adjTemperature = Math.clamp(biome.getBiomeData().temperature(), 0, 1);
        float adjDownfall = Math.clamp(biome.getBiomeData().downfall(), 0, 1) * adjTemperature;
        BufferedImage colormap = switch (tintMethod) {
            case DRY_FOLIAGE -> DRY_FOLIAGE_COLORMAP;
            case GRASS -> GRASS_COLORMAP;
            default -> FOLIAGE_COLORMAP;
        };

        int px = (int) ((1 - adjTemperature) * 255);
        int py = (int) ((1 - adjDownfall) * 255);
        return new Color(colormap.getRGB(Math.min(px, 255), Math.min(py, 255)));
    }

    /**
     * Apply water tinting to underwater blocks
     */
    private Color applyWaterTint(Color baseColor, int y, BiomeType biome) {
        Color waterColor = biome.getBiomeData().mapWaterColor();

        int finalRed = baseColor.getRed();
        int finalGreen = baseColor.getGreen();
        int finalBlue = baseColor.getBlue();

        if (y < SEA_LEVEL) {
            int depth = SEA_LEVEL - y;
            if (depth > 15) {
                return waterColor;
            }

            float ratio = Math.max(depth / 15f, 0.5f);
            finalRed += (int) ((waterColor.getRed() - finalRed) * ratio);
            finalGreen += (int) ((waterColor.getGreen() - finalGreen) * ratio);
        } else {
            finalRed += (int) ((waterColor.getRed() - finalRed) * 0.5f);
            finalGreen += (int) ((waterColor.getGreen() - finalGreen) * 0.5f);
        }

        return new Color(
            Math.clamp(finalRed, 0, 255),
            Math.clamp(finalGreen, 0, 255),
            Math.clamp(finalBlue, 0, 255)
        );
    }

    /**
     * Make a color brighter
     */
    private static Color brighter(Color source, double factor) {
        int r = source.getRed();
        int g = source.getGreen();
        int b = source.getBlue();
        int alpha = source.getAlpha();

        int i = (int) (1.0 / (1.0 - factor));
        if (r == 0 && g == 0 && b == 0) {
            return new Color(i, i, i, alpha);
        }
        if (r > 0 && r < i) r = i;
        if (g > 0 && g < i) g = i;
        if (b > 0 && b < i) b = i;

        return new Color(
            Math.min((int) (r / factor), 255),
            Math.min((int) (g / factor), 255),
            Math.min((int) (b / factor), 255),
            alpha
        );
    }

    /**
     * Make a color darker
     */
    private static Color darker(Color source, double factor) {
        return new Color(
            Math.max((int) (source.getRed() * factor), 0),
            Math.max((int) (source.getGreen() * factor), 0),
            Math.max((int) (source.getBlue() * factor), 0),
            source.getAlpha()
        );
    }

    /**
     * Helper record to store height search results
     */
    private record HeightResult(int y, BlockState state) {}
}
