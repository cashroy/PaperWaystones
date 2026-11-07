package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Catalog.Visibility;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Loads and saves all waystones and per-player data as YAML. */
public final class Store {

    public static final class PlayerData {
        public final Set<UUID> activated = new HashSet<>();
        public final Set<UUID> favourites = new HashSet<>();
        /** cooldown id -> epoch millis when it ends */
        public final Map<String, Long> cooldowns = new HashMap<>();
        /** Fleeting memorial from an epitaph (death location). */
        public Location memorial;
        /** Portal-scroll portal this player last walked through. */
        public UUID returnPortal;
    }

    private final WaystonesPlugin plugin;
    private final File waystoneFile;
    private final File playerFile;

    private final Map<UUID, Waystone> waystones = new LinkedHashMap<>();
    private final Map<String, Waystone> byBlock = new HashMap<>();
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private boolean dirty;

    public Store(WaystonesPlugin plugin) {
        this.plugin = plugin;
        this.waystoneFile = new File(plugin.getDataFolder(), "waystones.yml");
        this.playerFile = new File(plugin.getDataFolder(), "players.yml");
    }

    // ── access ───────────────────────────────────────────────

    public Collection<Waystone> all() {
        return waystones.values();
    }

    public Waystone get(UUID id) {
        return id == null ? null : waystones.get(id);
    }

    /** Waystone occupying this block (either half of a tall one). */
    public Waystone at(String world, int x, int y, int z) {
        Waystone w = byBlock.get(Waystone.key(world, x, y, z));
        if (w != null) return w;
        Waystone below = byBlock.get(Waystone.key(world, x, y - 1, z));
        if (below != null && Catalog.isTall(below.kind)) return below;
        return null;
    }

    public Waystone at(Location l) {
        return at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public Waystone findByName(String name) {
        for (Waystone w : waystones.values()) {
            if (w.isPlaced() && w.kind == Kind.WAYSTONE && w.name.equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    public void add(Waystone w) {
        waystones.put(w.id, w);
        reindex(w, null);
        markDirty();
    }

    /** Call after moving a waystone. oldKey may be null. */
    public void reindex(Waystone w, String oldKey) {
        if (oldKey != null) byBlock.remove(oldKey);
        if (w.world != null) byBlock.put(w.blockKey(), w);
        markDirty();
    }

    public void unplace(Waystone w) {
        if (w.world != null) byBlock.remove(w.blockKey());
        w.world = null;
        markDirty();
    }

    public void remove(Waystone w) {
        unplace(w);
        waystones.remove(w.id);
        for (PlayerData pd : players.values()) {
            pd.activated.remove(w.id);
            pd.favourites.remove(w.id);
        }
        markDirty();
    }

    public PlayerData player(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> new PlayerData());
    }

    public Map<UUID, PlayerData> allPlayers() {
        return players;
    }

    public void markDirty() {
        dirty = true;
    }

    // ── persistence ──────────────────────────────────────────

    public void load() {
        waystones.clear();
        byBlock.clear();
        players.clear();

        YamlConfiguration wy = YamlConfiguration.loadConfiguration(waystoneFile);
        ConfigurationSection root = wy.getConfigurationSection("waystones");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    Waystone w = new Waystone(UUID.fromString(key));
                    w.name = s.getString("name", "Waystone");
                    w.kind = Kind.valueOf(s.getString("kind", "WAYSTONE"));
                    w.variant = s.getString("variant", "stone");
                    w.world = s.getString("world");
                    w.x = s.getInt("x");
                    w.y = s.getInt("y");
                    w.z = s.getInt("z");
                    w.facing = (float) s.getDouble("facing");
                    String owner = s.getString("owner");
                    w.owner = owner == null ? null : UUID.fromString(owner);
                    w.visibility = Visibility.valueOf(s.getString("visibility", "ACTIVATION"));
                    w.generated = s.getBoolean("generated");
                    w.roundRobin = s.getInt("round-robin");
                    for (int i = 0; i < Waystone.AUX_SLOTS; i++) {
                        w.slots.set(i, s.getItemStack("slots." + i));
                    }
                    waystones.put(w.id, w);
                    if (w.world != null) byBlock.put(w.blockKey(), w);
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping broken waystone entry " + key + ": " + e.getMessage());
                }
            }
        }

        YamlConfiguration py = YamlConfiguration.loadConfiguration(playerFile);
        ConfigurationSection proot = py.getConfigurationSection("players");
        if (proot != null) {
            for (String key : proot.getKeys(false)) {
                ConfigurationSection s = proot.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    PlayerData pd = new PlayerData();
                    for (String id : s.getStringList("activated")) pd.activated.add(UUID.fromString(id));
                    for (String id : s.getStringList("favourites")) pd.favourites.add(UUID.fromString(id));
                    ConfigurationSection cd = s.getConfigurationSection("cooldowns");
                    if (cd != null) for (String c : cd.getKeys(false)) pd.cooldowns.put(c, cd.getLong(c));
                    pd.memorial = s.getLocation("memorial");
                    players.put(UUID.fromString(key), pd);
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping broken player entry " + key + ": " + e.getMessage());
                }
            }
        }
        dirty = false;
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration wy = new YamlConfiguration();
        for (Waystone w : waystones.values()) {
            String p = "waystones." + w.id + ".";
            wy.set(p + "name", w.name);
            wy.set(p + "kind", w.kind.name());
            wy.set(p + "variant", w.variant);
            wy.set(p + "world", w.world);
            wy.set(p + "x", w.x);
            wy.set(p + "y", w.y);
            wy.set(p + "z", w.z);
            wy.set(p + "facing", (double) w.facing);
            wy.set(p + "owner", w.owner == null ? null : w.owner.toString());
            wy.set(p + "visibility", w.visibility.name());
            wy.set(p + "generated", w.generated);
            wy.set(p + "round-robin", w.roundRobin);
            for (int i = 0; i < Waystone.AUX_SLOTS; i++) {
                ItemStack it = w.slots.get(i);
                if (it != null && !it.isEmpty()) wy.set(p + "slots." + i, it);
            }
        }
        YamlConfiguration py = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            String p = "players." + e.getKey() + ".";
            PlayerData pd = e.getValue();
            py.set(p + "activated", pd.activated.stream().map(UUID::toString).toList());
            py.set(p + "favourites", pd.favourites.stream().map(UUID::toString).toList());
            for (Map.Entry<String, Long> c : pd.cooldowns.entrySet()) {
                if (c.getValue() > now) py.set(p + "cooldowns." + c.getKey(), c.getValue());
            }
            if (pd.memorial != null) py.set(p + "memorial", pd.memorial);
        }
        try {
            plugin.getDataFolder().mkdirs();
            wy.save(waystoneFile);
            py.save(playerFile);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save waystone data: " + ex.getMessage());
        }
    }
}
