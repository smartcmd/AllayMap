package me.daoge.allaymap.render;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.Dimension;

import java.util.Collections;
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
     */
    public Set<Long> pollDirtyChunks(Dimension dimension) {
        var result = this.dirtyChunks.put(dimension, ConcurrentHashMap.newKeySet());
        return result == null ? Collections.emptySet() : result;
    }

    /**
     * Removes all tracked dirty chunks for the specified dimension.
     */
    public void removeDimension(Dimension dimension) {
        this.dirtyChunks.remove(dimension);
    }

    /**
     * Returns the number of dimensions that currently have dirty chunks
     * needing to be re-rendered.
     */
    public int getDirtyChunkCount() {
        return this.dirtyChunks.size();
    }
}
