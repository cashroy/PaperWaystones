package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Settings.Village;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;

import java.util.*;

/** Places waystones in the wild and in villages as new chunks generate. */
public final class WorldGen implements Listener {

    private static final Set<Structure> VILLAGES = Set.of(Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT,
            Structure.VILLAGE_SAVANNA, Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA);

    private final WaystonesPlugin plugin;

    public WorldGen(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPopulate(ChunkPopulateEvent e) {
        Chunk chunk = e.getChunk();
        World world = chunk.getWorld();
        Settings s = plugin.cfg();
        int cx = chunk.getX(), cz = chunk.getZ();

        // Wild waystones: one per N×N chunk cell, at a seeded random chunk inside the cell.
        int n = s.wildSpacing;
        if (n > 0 && s.wildWorlds.contains(world.getName())) {
            int cellX = Math.floorDiv(cx, n), cellZ = Math.floorDiv(cz, n);
            Random r = new Random(world.getSeed() ^ (cellX * 341873128712L) ^ (cellZ * 132897987541L) ^ 0x57A7L);
            if (cx == cellX * n + r.nextInt(n) && cz == cellZ * n + r.nextInt(n)) {
                int x = (cx << 4) + 4 + r.nextInt(8);
                int z = (cz << 4) + 4 + r.nextInt(8);
                Bukkit.getScheduler().runTask(plugin, () -> placeWild(world, x, z, r.nextInt(4) * 90f));
            }
        }

        // Village waystones
        if (s.villages != Village.DISABLED) {
            for (GeneratedStructure gs : chunk.getStructures()) {
                if (!VILLAGES.contains(gs.getStructure())) continue;
                BoundingBox bb = gs.getBoundingBox();
                int centreX = (int) Math.floor(bb.getCenterX()), centreZ = (int) Math.floor(bb.getCenterZ());
                if ((centreX >> 4) != cx || (centreZ >> 4) != cz) continue;
                long h = world.getSeed() * 31 + centreX * 73428767L + centreZ * 912931L;
                double roll = new Random(h).nextDouble();
                if (roll > (s.villages == Village.FREQUENT ? 0.9 : 0.5)) continue;
                boolean desert = gs.getStructure() == Structure.VILLAGE_DESERT;
                Bukkit.getScheduler().runTaskLater(plugin, () -> placeVillage(world, centreX, centreZ, desert), 2L);
            }
        }
    }

    private void placeWild(World world, int x, int z, float facing) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        Integer y = surface(world, x, z);
        if (y == null) return;
        Biome biome = world.getBiome(x, y, z);
        String style = style(world, biome, plugin.cfg().wildStyle);
        build(world, x, y, z, style, facing, true);
    }

    private void placeVillage(World world, int cx, int cz, boolean desert) {
        // Search outward from the village centre for a clear spot.
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = cx + dx, z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Integer y = surface(world, x, z);
                    if (y == null || !clear(world, x, y, z)) continue;
                    String style = desert ? "sandy" : style(world, world.getBiome(x, y, z), "BIOME");
                    build(world, x, y, z, style, 0f, false);
                    return;
                }
            }
        }
    }

    private boolean clear(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block ground = w.getBlockAt(x + dx, y - 1, z + dz);
            if (!ground.getType().isSolid() || ground.getType().name().contains("LOG") || ground.getType().name().contains("PLANKS")) return false;
            for (int dy = 0; dy < 3; dy++) if (!w.getBlockAt(x + dx, y + dy, z + dz).isPassable()) return false;
        }
        return true;
    }

    /** Y of the first free block above the ground, or null if there's no sensible ground. */
    private Integer surface(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = 32; y < 110; y++) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType().isSolid() && b.getRelative(0, 1, 0).isEmpty() && b.getRelative(0, 2, 0).isEmpty()
                        && b.getRelative(0, 3, 0).isEmpty()) return y + 1;
            }
            return null;
        }
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() + 1) return null;
        Block top = world.getBlockAt(x, y, z);
        if (top.isLiquid() || !top.getType().isSolid()) return null;
        return y + 1;
    }

    private String style(World world, Biome biome, String mode) {
        if (!"BIOME".equals(mode)) {
            return switch (mode) {
                case "MOSSY" -> "mossy";
                case "SANDY" -> "sandy";
                case "BLACKSTONE" -> "blackstone";
                case "DEEPSLATE" -> "deepslate";
                case "END_STONE" -> "end_stone";
                default -> "stone";
            };
        }
        if (world.getEnvironment() == World.Environment.NETHER) return "blackstone";
        if (world.getEnvironment() == World.Environment.THE_END) return "end_stone";
        String key = biome == null ? "" : biome.getKey().getKey();
        if (key.contains("desert") || key.contains("badlands") || key.contains("beach")) return "sandy";
        if (key.contains("jungle") || key.contains("swamp") || key.contains("lush") || key.contains("dark_forest")) return "mossy";
        if (key.contains("mangrove")) return "muddy";
        if (key.contains("deep_dark") || key.contains("dripstone")) return "deepslate";
        return "stone";
    }

    private void build(World world, int x, int y, int z, String style, float facing, boolean wild) {
        if (plugin.store().at(world.getName(), x, y, z) != null) return;
        Material floor = switch (style) {
            case "sandy" -> Material.SMOOTH_SANDSTONE;
            case "mossy" -> Material.MOSSY_STONE_BRICKS;
            case "blackstone" -> Material.POLISHED_BLACKSTONE_BRICKS;
            case "end_stone" -> Material.END_STONE_BRICKS;
            case "deepslate" -> Material.DEEPSLATE_BRICKS;
            case "muddy" -> Material.MUD_BRICKS;
            default -> Material.STONE_BRICKS;
        };
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (wild || (dx == 0 && dz == 0)) world.getBlockAt(x + dx, y - 1, z + dz).setType(floor, false);
            for (int dy = 0; dy < 3; dy++) {
                Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                if (!b.isEmpty() && b.isPassable()) b.setType(Material.AIR, false);
            }
        }
        Waystone w = new Waystone(UUID.randomUUID());
        w.kind = Kind.WAYSTONE;
        w.variant = style;
        w.world = world.getName();
        w.x = x;
        w.y = y;
        w.z = z;
        w.facing = facing;
        w.generated = true;
        w.visibility = plugin.cfg().defaultVisibility;
        w.name = plugin.nameGen().generate(world.getBiome(x, y, z));
        plugin.store().add(w);
        plugin.structures().spawn(w);
    }
}
