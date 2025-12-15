# AllayMap

A minimalistic and lightweight real-time world map viewer for [Allay](https://github.com/AllayMC/Allay) servers, using vanilla map rendering style.

![img.png](img.png)

## Features

- Real-time map rendering with vanilla Minecraft map colors
- Multi-zoom support (6 zoom levels)
- Biome-aware coloring (grass, foliage, water tinting)
- Player tracking with live position updates
- Multi-world and multi-dimension support
- Lightweight web interface
- Automatic chunk rendering on exploration

## Installation

1. Download the latest release from the releases page
2. Place the JAR file in your Allay server's `plugins` folder
3. Start or restart your server
4. Access the map at `http://localhost:8080` (default port)

## Configuration

Configuration file is located at `plugins/AllayMap/config.yml`:

```yaml
# Port number for the web interface
# The map will be accessible at http://localhost:<port>
# Make sure this port is not used by other applications
# Valid range: 1-65535 (ports below 1024 may require admin privileges)
# Default: 8080
http-port: 8080

# Interval in seconds between processing dirty (modified) chunks
# Lower values = faster map updates but higher CPU usage
# Higher values = slower map updates but lower CPU usage
# Recommended: 30-120 seconds depending on server performance
# Default: 60
update-interval: 60
```

### Configuration Options

| Option            | Default | Description                                                                                 |
|-------------------|---------|---------------------------------------------------------------------------------------------|
| `http-port`       | 8080    | Port for the web interface. The map will be accessible at `http://localhost:<port>`         |
| `update-interval` | 60      | Interval in seconds between processing modified chunks. Lower = faster updates but more CPU |

## Usage

- Open `http://localhost:8080` in your browser
- Use mouse wheel to zoom in/out
- Click and drag to pan the map
- Select different worlds/dimensions from the dropdown
- Click on a player name to center the map on their location

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
