package me.daoge.allaymap.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.daoge.allaymap.render.RenderQueue;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.world.ChunkLoadEvent;
import org.allaymc.api.eventbus.event.block.BlockPlaceEvent;

/**
 * Listens for world events and marks affected regions as dirty for re-rendering.
 */
@Slf4j
@RequiredArgsConstructor
public class WorldEventListener {

    private final RenderQueue renderQueue;

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        var dimension = event.getDimension();
        // Only mark dirty if this region hasn't been rendered yet
        // This avoids re-rendering when chunks are unloaded and reloaded
        renderQueue.markChunkDirtyIfNew(dimension, chunk.getX(), chunk.getZ());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        var pos = event.getBlock().getPosition();
        var dimension = event.getBlock().getDimension();
        renderQueue.markBlockDirty(dimension, pos.x(), pos.z());
    }
}
