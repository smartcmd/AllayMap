package me.daoge.allaymap.listener;

import lombok.RequiredArgsConstructor;
import me.daoge.allaymap.render.RenderQueue;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.block.BlockPlaceEvent;
import org.allaymc.api.eventbus.event.world.ChunkLoadEvent;
import org.allaymc.api.eventbus.event.world.WorldUnloadEvent;

/**
 * Listens for world events and marks affected regions as dirty for re-rendering.
 */
@RequiredArgsConstructor
public class WorldEventListener {

    private final RenderQueue renderQueue;

    @EventHandler
    private void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        var dimension = event.getDimension();
        // Only mark dirty if this region hasn't been rendered yet
        // This avoids re-rendering when chunks are unloaded and reloaded
        renderQueue.markChunkDirtyIfNew(dimension, chunk.getX(), chunk.getZ());
    }

    @EventHandler
    private void onBlockPlace(BlockPlaceEvent event) {
        var pos = event.getBlock().getPosition();
        var dimension = event.getBlock().getDimension();
        renderQueue.markBlockDirty(dimension, pos.x(), pos.z());
    }

    @EventHandler
    private void onWorldUnload(WorldUnloadEvent event) {
        var world = event.getWorld();
        for (var dimension : world.getDimensions().values()) {
            renderQueue.removeDimension(dimension);
        }
    }
}
