package me.daoge.allaymap;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.CustomKey;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Configuration for AllayMap plugin using okaeri-configs
 */
@Getter
@Accessors(fluent = true)
public class AMConfig extends OkaeriConfig {

    @Comment("Port number for the web interface")
    @Comment("The map will be accessible at http://localhost:<port>")
    @Comment("Make sure this port is not used by other applications")
    @Comment("Valid range: 1-65535 (ports below 1024 may require admin privileges)")
    @Comment("Default: 8080")
    @CustomKey("http-port")
    private int httpPort = 8080;

    @Comment("Interval in seconds between processing dirty (modified) chunks")
    @Comment("Lower values = faster map updates but higher CPU usage")
    @Comment("Higher values = slower map updates but lower CPU usage")
    @Comment("Default: 10")
    @CustomKey("update-interval")
    private int updateInterval = 10;

    @Comment("Whether to render blocks underwater")
    @Comment("If disabled (false), water surfaces will show biome water color directly")
    @Comment("If enabled (true), underwater blocks will be visible with water tint applied")
    @Comment("Disabling this can improve map readability in ocean/river areas")
    @Comment("Default: false")
    @CustomKey("render-underwater-blocks")
    private boolean renderUnderwaterBlocks = false;
}
