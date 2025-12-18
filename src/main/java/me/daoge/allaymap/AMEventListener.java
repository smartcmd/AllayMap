package me.daoge.allaymap;

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
public class AMEventListener {

    private final RenderQueue renderQueue;

    @EventHandler
    private void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        var dimension = event.getDimension();
        renderQueue.markChunkDirty(dimension, chunk.getX(), chunk.getZ());
        // Shadow in z+1 chunk should be recalculated
        renderQueue.markChunkDirty(dimension, chunk.getX(), chunk.getZ() + 1);
    }

    @EventHandler(priority = Integer.MIN_VALUE)
    private void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }

        var pos = event.getBlock().getPosition();
        var dimension = event.getBlock().getDimension();
        renderQueue.markBlockDirty(dimension, pos.x(), pos.z());
        // Shadow in z+1 chunk should be recalculated
        renderQueue.markBlockDirty(dimension, pos.x(), pos.z() + 1);
    }

    @EventHandler(priority = Integer.MIN_VALUE)
    private void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) {
            return;
        }

        var world = event.getWorld();
        for (var dimension : world.getDimensions().values()) {
            renderQueue.removeDimension(dimension);
        }
    }
}
