package me.daoge.allaymap.render;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.Dimension;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunks that need to be re-rendered. Uses a set to deduplicate render requests.
 */
@Slf4j
public class RenderQueue {

    // Map of dimension -> set of dirty chunk coordinates (chunkX << 32 | chunkZ)
    private final Map<Dimension, Set<Long>> dirtyChunks = new ConcurrentHashMap<>();

    /**
     * Mark a chunk as dirty (needs re-rendering).
     */
    public void markChunkDirty(Dimension dimension, int chunkX, int chunkZ) {
        long key = HashUtils.hashXZ(chunkX, chunkZ);
        getDirtyChunkSet(dimension).add(key);
    }

    /**
     * Get or create the dirty chunk set for a dimension.
     * Always returns a valid set that is safe to add to.
     */
    private Set<Long> getDirtyChunkSet(Dimension dimension) {
        return this.dirtyChunks.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Mark a block position as dirty.
     */
    public void markBlockDirty(Dimension dimension, int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        markChunkDirty(dimension, chunkX, chunkZ);
    }

    /**
     * Get and clear all dirty chunks for a dimension.
     * This method is thread-safe - uses copy-and-clear to avoid losing concurrent adds.
     */
    public Set<Long> pollDirtyChunks(Dimension dimension) {
        Set<Long> chunks = this.dirtyChunks.get(dimension);
        if (chunks == null || chunks.isEmpty()) {
            return Set.of();
        }

        // Copy current contents and clear atomically per-element
        // Using HashSet for the copy since we just need to iterate over it
        Set<Long> result = new HashSet<>();
        for (Long key : chunks) {
            if (chunks.remove(key)) {
                result.add(key);
            }
        }

        return result;
    }

    public void removeDimension(Dimension dimension) {
        this.dirtyChunks.remove(dimension);
    }
}
