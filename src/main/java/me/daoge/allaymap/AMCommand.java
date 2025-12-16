package me.daoge.allaymap;

import org.allaymc.api.command.Command;
import org.allaymc.api.command.tree.CommandTree;
import org.allaymc.api.utils.TextFormat;

/**
 * @author daoge_cmd
 */
public class AMCommand extends Command {

    public AMCommand() {
        super("allaymap", "AllayMap main command", "allaymap.command");
        this.aliases.add("amap");
    }

    @Override
    public void prepareCommandTree(CommandTree tree) {
        tree.getRoot()
                .key("status")
                .permission("allaymap.command.status")
                .exec(context -> {
                    var tileManager = AllayMap.getInstance().getTileManager();
                    context.addOutput("Tile Tasks: " + TextFormat.GREEN + tileManager.getTileTaskCount());
                    context.addOutput("Dirty Chunks: " + TextFormat.GREEN + tileManager.getRenderQueue().getDirtyChunkCount());
                    return context.success();
                });
    }
}
