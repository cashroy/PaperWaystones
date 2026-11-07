package dev.cashroy.waystones;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Serves the bundled resource pack over a tiny HTTP server and sends it to players on join.
 * The pack ships inside the jar; put plugins/Waystones/custom-pack.zip there to use your own edits instead.
 */
public final class PackServer {
    private static final UUID PACK_ID = UUID.fromString("5b7a7e0c-9c1e-4c55-9d7e-77a7e57a7e01");

    private final WaystonesPlugin plugin;
    private HttpServer server;
    private String url;
    private byte[] sha1;
    private byte[] bytes;

    public PackServer(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Settings s = plugin.cfg();
        if (!s.packEnabled) return;
        try {
            // Drop your own edited pack in as custom-pack.zip to override the bundled one.
            File custom = new File(plugin.getDataFolder(), "custom-pack.zip");
            File file = custom.exists() ? custom : new File(plugin.getDataFolder(), "waystones-pack.zip");
            if (!custom.exists()) {
                plugin.getDataFolder().mkdirs();
                try (InputStream in = plugin.getResource("waystones-pack.zip")) {
                    if (in == null) {
                        plugin.getLogger().severe("waystones-pack.zip is missing from the jar!");
                        return;
                    }
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            bytes = Files.readAllBytes(file.toPath());
            sha1 = MessageDigest.getInstance("SHA-1").digest(bytes);
            String hash = HexFormat.of().formatHex(sha1);

            if (s.packExternalUrl != null && !s.packExternalUrl.isBlank()) {
                url = s.packExternalUrl.trim();
                plugin.getLogger().info("Using external resource pack URL " + url + " (sha1 " + hash + ")");
                return;
            }

            server = HttpServer.create(new InetSocketAddress(s.packPort), 0);
            server.createContext("/", exchange -> {
                try {
                    if (!exchange.getRequestURI().getPath().startsWith("/waystones-pack")) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();

            String host = s.packHost == null || s.packHost.isBlank() ? Bukkit.getIp() : s.packHost.trim();
            if (host == null || host.isBlank()) host = guessLanAddress();
            // The hash in the path makes clients re-download after you edit the pack.
            url = "http://" + host + ":" + s.packPort + "/waystones-pack-" + hash.substring(0, 8) + ".zip";
            plugin.getLogger().info("Serving the resource pack at " + url
                    + " — set resource-pack.public-host in config.yml if players can't reach that address.");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not start the resource pack server: " + e.getMessage());
            url = null;
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    public void send(Player p) {
        if (url == null) return;
        Settings s = plugin.cfg();
        String prompt = s.packPrompt == null || s.packPrompt.isBlank() ? null : s.packPrompt;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // addResourcePack layers our pack on top of the server's and other plugins' packs.
            // (setResourcePack would replace all of them.) Re-adding the same ID only updates ours.
            if (p.isOnline()) p.addResourcePack(PACK_ID, url, sha1, prompt, s.packRequired);
        }, 20L);
    }

    private static String guessLanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return "localhost";
    }
}
