package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Catalog.Visibility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One placed (or picked-up with silk touch) waystone, sharestone, portstone or warp plate. */
public final class Waystone {
    public static final int AUX_SLOTS = 5;

    public final UUID id;
    public String name;
    public Kind kind;
    public String variant;
    /** null while the block is picked up (silk touch) and not placed. */
    public String world;
    public int x, y, z;
    /** Yaw the front of the block faces (0, 90, 180, 270). */
    public float facing;
    public UUID owner;
    public Visibility visibility = Visibility.ACTIVATION;
    public boolean generated;
    /** Auxiliary/modifier slots. For warp plates slot 0 is the main shard slot. */
    public final List<ItemStack> slots = new ArrayList<>();
    /** Round-robin pointer for warp plates. */
    public int roundRobin;

    public Waystone(UUID id) {
        this.id = id;
        for (int i = 0; i < AUX_SLOTS; i++) slots.add(null);
    }

    public boolean isPlaced() {
        return world != null && Bukkit.getWorld(world) != null;
    }

    public World bukkitWorld() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    public Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    /** Centre of the bottom block. */
    public Location centre() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
    }

    public boolean isGlobalFor(Kind k) {
        return k == Kind.SHARESTONE || visibility == Visibility.GLOBAL;
    }

    public String dimensionLabel() {
        World w = bukkitWorld();
        if (w == null) return "Unknown";
        return switch (w.getEnvironment()) {
            case NETHER -> "The Nether";
            case THE_END -> "The End";
            default -> w.getName().equals("world") ? "Overworld" : w.getName();
        };
    }

    public String blockKey() {
        return key(world, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
