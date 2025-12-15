package me.daoge.allaymap.render;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.world.Dimension;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunks that need to be re-rendered.
 * Uses a set to deduplicate render requests.
 */
@Slf4j
public class RenderQueue {

    // Map of dimension -> set of dirty chunk coordinates (chunkX << 32 | chunkZ)
    private final Map<Dimension, Set<Long>> dirtyChunks = new ConcurrentHashMap<>();

    // Track chunks that have already been rendered (to avoid re-rendering unchanged reloaded chunks)
    private final Map<Dimension, Set<Long>> renderedChunks = new ConcurrentHashMap<>();

    /**
     * Mark a chunk as dirty (needs re-rendering).
     * Used for block changes - always marks dirty.
     */
    public void markChunkDirty(Dimension dimension, int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        getDirtyChunkSet(dimension).add(key);
    }

    /**
     * Mark a chunk as dirty only if it hasn't been rendered yet.
     * Used for chunk load events - only renders newly explored areas.
     * This method is thread-safe.
     */
    public void markChunkDirtyIfNew(Dimension dimension, int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

        // Use computeIfAbsent on renderedChunks to atomically check and potentially mark dirty
        // If the key is already in renderedChunks, we don't need to mark dirty
        Set<Long> rendered = renderedChunks.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet());

        // putIfAbsent semantics: only mark dirty if not already rendered
        // We use a trick: try to add to a temporary tracking, but actual logic uses contains
        if (!rendered.contains(key)) {
            getDirtyChunkSet(dimension).add(key);
        }
    }

    /**
     * Get or create the dirty chunk set for a dimension.
     * Always returns a valid set that is safe to add to.
     */
    private Set<Long> getDirtyChunkSet(Dimension dimension) {
        return dirtyChunks.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Mark a chunk as rendered (used to track already-rendered chunks).
     */
    public void markChunkRendered(Dimension dimension, int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        renderedChunks.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet()).add(key);
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
        Set<Long> chunks = dirtyChunks.get(dimension);
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

    /**
     * Check if there are any dirty chunks for a dimension.
     */
    public boolean hasDirtyChunks(Dimension dimension) {
        Set<Long> chunks = dirtyChunks.get(dimension);
        return chunks != null && !chunks.isEmpty();
    }

    /**
     * Get total number of dirty chunks across all dimensions.
     */
    public int getTotalDirtyChunks() {
        return dirtyChunks.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Extract chunkX from a chunk key.
     */
    public static int getChunkX(long key) {
        return (int) (key >> 32);
    }

    /**
     * Extract chunkZ from a chunk key.
     */
    public static int getChunkZ(long key) {
        return (int) key;
    }

    /**
     * Clear all dirty chunks and rendered tracking.
     */
    public void clear() {
        dirtyChunks.clear();
        renderedChunks.clear();
    }
}
