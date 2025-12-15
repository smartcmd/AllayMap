package me.daoge.allaymap.httpd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.daoge.allaymap.AllayMap;
import me.daoge.allaymap.render.MapTileManager;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.World;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for serving map tiles and web interface.
 */
@Slf4j
public class MapHttpServer {

    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
    }

    @Getter
    private final int port;
    private final AllayMap plugin;
    private final MapTileManager tileManager;
    private HttpServer server;

    public MapHttpServer(AllayMap plugin, MapTileManager tileManager, int port) {
        this.plugin = plugin;
        this.tileManager = tileManager;
        this.port = port;
    }

    /**
     * Start the HTTP server
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Tile endpoint: /tiles/{world}_{dim}/{zoom}/{x}_{z}.png
        server.createContext("/tiles", new TileHandler());

        // API endpoints
        server.createContext("/api/worlds", new WorldsApiHandler());
        server.createContext("/api/players", new PlayersApiHandler());

        // Static files (web interface)
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        log.info("AllayMap web server started on port {}", port);
    }

    /**
     * Stop the HTTP server
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("AllayMap web server stopped");
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] content = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Read a resource file from the JAR
     */
    private byte[] readResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return is.readAllBytes();
        }
    }

    /**
     * Handler for tile requests
     */
    private class TileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            log.debug("Tile request: {}", path);

            // Expected format: /tiles/{world}_{dim}/{zoom}/{x}_{z}.png
            String[] parts = path.split("/");
            if (parts.length < 5) {
                sendError(exchange, 400, "Invalid tile path");
                return;
            }

            try {
                String worldDim = parts[2];
                int zoom = Integer.parseInt(parts[3]);
                String coords = parts[4].replace(".png", "");
                String[] coordParts = coords.split("_");
                int regionX = Integer.parseInt(coordParts[0]);
                int regionZ = Integer.parseInt(coordParts[1]);

                log.debug("Parsed tile request: world={}, zoom={}, x={}, z={}", worldDim, zoom, regionX, regionZ);

                // Parse world and dimension (format: worldName_dimId)
                // World name may contain underscores, so find the last underscore
                int lastUnderscore = worldDim.lastIndexOf('_');
                if (lastUnderscore == -1) {
                    sendError(exchange, 400, "Invalid world format");
                    return;
                }
                String worldName = worldDim.substring(0, lastUnderscore);
                int dimId = Integer.parseInt(worldDim.substring(lastUnderscore + 1));

                // Find the dimension
                World world = Server.getInstance().getWorldPool().getWorld(worldName);
                if (world == null) {
                    sendError(exchange, 404, "World not found: " + worldName);
                    return;
                }

                Dimension dimension = world.getDimension(dimId);
                if (dimension == null) {
                    sendError(exchange, 404, "Dimension not found: " + dimId);
                    return;
                }

                // Get or render the tile
                BufferedImage tile = tileManager.getTile(dimension, regionX, regionZ, zoom).join();

                // Send the image (16x16, frontend will scale it)
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody()) {
                    ImageIO.write(tile, "png", os);
                }

            } catch (NumberFormatException e) {
                sendError(exchange, 400, "Invalid coordinates");
            } catch (Exception e) {
                log.debug("Error serving tile", e);
                sendError(exchange, 500, "Internal server error");
            }
        }
    }

    /**
     * Handler for worlds API
     */
    private class WorldsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"worlds\":[");

            boolean first = true;
            for (World world : Server.getInstance().getWorldPool().getWorlds().values()) {
                for (Dimension dim : world.getDimensions().values()) {
                    if (!first) json.append(",");
                    first = false;

                    var spawnPoint = world.getSpawnPoint();
                    json.append("{");
                    json.append("\"name\":\"").append(escapeJson(world.getName())).append("\",");
                    json.append("\"dimension\":").append(dim.getDimensionInfo().dimensionId()).append(",");
                    json.append("\"dimensionName\":\"").append(escapeJson(dim.getDimensionInfo().toString())).append("\",");
                    json.append("\"id\":\"").append(escapeJson(world.getName())).append("_").append(dim.getDimensionInfo().dimensionId()).append("\",");
                    json.append("\"spawn\":{\"x\":").append((int) spawnPoint.x()).append(",\"z\":").append((int) spawnPoint.z()).append("}");
                    json.append("}");
                }
            }

            json.append("]}");

            sendJson(exchange, json.toString());
        }
    }

    /**
     * Handler for players API
     */
    private class PlayersApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"players\":[");

            boolean first = true;
            for (var player : Server.getInstance().getPlayerManager().getPlayers().values()) {
                var entity = player.getControlledEntity();
                if (entity == null) continue;

                if (!first) json.append(",");
                first = false;

                var loc = entity.getLocation();
                var world = loc.dimension().getWorld().getName();
                var dim = loc.dimension().getDimensionInfo().dimensionId();

                json.append("{");
                json.append("\"name\":\"").append(escapeJson(player.getOriginName())).append("\",");
                json.append("\"uuid\":\"").append(player.getLoginData().getUuid()).append("\",");
                json.append("\"world\":\"").append(escapeJson(world)).append("_").append(dim).append("\",");
                json.append("\"x\":").append((int) loc.x()).append(",");
                json.append("\"y\":").append((int) loc.y()).append(",");
                json.append("\"z\":").append((int) loc.z());
                json.append("}");
            }

            json.append("]}");

            sendJson(exchange, json.toString());
        }
    }

    /**
     * Handler for static files (web interface)
     */
    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            // Security: prevent directory traversal
            if (path.contains("..")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            // Remove leading slash for resource path
            String resourcePath = "web" + path;

            // First, check if custom file exists in plugin's data folder
            Path customWebDir = plugin.getPluginContainer().dataFolder().resolve("web");
            Path customFilePath = customWebDir.resolve(path.substring(1));

            byte[] content;
            if (Files.exists(customFilePath) && !Files.isDirectory(customFilePath)) {
                // Serve custom file from data folder
                content = Files.readAllBytes(customFilePath);
            } else {
                // Try to load from JAR resources
                try {
                    content = readResource(resourcePath);
                } catch (FileNotFoundException e) {
                    sendError(exchange, 404, "Not Found");
                    return;
                }
            }

            // Determine content type
            String extension = getExtension(path);
            String contentType = MIME_TYPES.getOrDefault(extension, "application/octet-stream");
            if (extension.equals("html")) {
                contentType = "text/html; charset=utf-8";
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }
}
