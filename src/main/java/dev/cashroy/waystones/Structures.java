package dev.cashroy.waystones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * The "custom block" illusion: barrier blocks for collision, an item display showing the
 * resource-pack model, an interaction entity to catch clicks, and a floating name.
 */
public final class Structures {
    public static final String TAG = "waystones";

    private final WaystonesPlugin plugin;
    public final NamespacedKey keyEntity;

    public Structures(WaystonesPlugin plugin) {
        this.plugin = plugin;
        this.keyEntity = new NamespacedKey(plugin, "waystone_id");
    }

    public void spawn(Waystone w) {
        World world = w.bukkitWorld();
        if (world == null) return;
        boolean tall = Catalog.isTall(w.kind);

        if (tall) {
            world.getBlockAt(w.x, w.y, w.z).setType(Material.BARRIER, false);
            world.getBlockAt(w.x, w.y + 1, w.z).setType(Material.BARRIER, false);
        }

        Location model = new Location(world, w.x + 0.5, w.y + 0.5, w.z + 0.5, w.facing, 0);
        world.spawn(model, ItemDisplay.class, d -> {
            ItemStack shown = new ItemStack(Material.PAPER);
            ItemMeta meta = shown.getItemMeta();
            meta.setItemModel(Items.blockModel(w.kind, w.variant));
            shown.setItemMeta(meta);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setViewRange(2.0f);
            tag(d, w.id);
        });

        Location hit = new Location(world, w.x + 0.5, w.y - 0.01, w.z + 0.5);
        world.spawn(hit, Interaction.class, i -> {
            i.setInteractionWidth(1.02f);
            i.setInteractionHeight(tall ? 2.03f : 0.2f);
            i.setResponsive(true);
            tag(i, w.id);
        });

        Location label = new Location(world, w.x + 0.5, w.y + (tall ? 2.3 : 0.45), w.z + 0.5);
        world.spawn(label, TextDisplay.class, t -> {
            t.text(nameComponent(w));
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setViewRange(0.4f);
            if (!tall) {
                t.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.Quaternionf()));
            }
            tag(t, w.id);
        });
    }

    private Component nameComponent(Waystone w) {
        NamedTextColor c = switch (w.kind) {
            case SHARESTONE -> NamedTextColor.AQUA;
            case PORTSTONE -> NamedTextColor.GRAY;
            case WARP_PLATE -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
        return Component.text(w.name).color(c);
    }

    private void tag(Entity e, UUID id) {
        e.getPersistentDataContainer().set(keyEntity, PersistentDataType.STRING, id.toString());
        e.addScoreboardTag(TAG);
        e.setPersistent(true);
        e.setInvulnerable(true);
    }

    public UUID idOf(Entity e) {
        if (!e.getScoreboardTags().contains(TAG)) return null;
        String s = e.getPersistentDataContainer().get(keyEntity, PersistentDataType.STRING);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void despawn(Waystone w) {
        World world = w.bukkitWorld();
        if (world == null) return;
        Location c = new Location(world, w.x + 0.5, w.y + 1, w.z + 0.5);
        for (Entity e : world.getNearbyEntities(c, 1.5, 2.5, 1.5)) {
            if (w.id.equals(idOf(e))) e.remove();
        }
        if (Catalog.isTall(w.kind)) {
            for (int dy = 0; dy < 2; dy++) {
                Block b = world.getBlockAt(w.x, w.y + dy, w.z);
                if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
            }
        }
    }

    /** Re-spawns visuals (used after rename or a broken/missing entity). */
    public void refresh(Waystone w) {
        despawn(w);
        spawn(w);
    }

    public void updateName(Waystone w) {
        World world = w.bukkitWorld();
        if (world == null) return;
        Location c = new Location(world, w.x + 0.5, w.y + 1, w.z + 0.5);
        for (Entity e : world.getNearbyEntities(c, 1.5, 2.5, 1.5)) {
            if (e instanceof TextDisplay t && w.id.equals(idOf(e))) t.text(nameComponent(w));
        }
    }

    /** Where someone arriving at this waystone should stand. */
    public Location arrival(Waystone w) {
        World world = w.bukkitWorld();
        if (world == null) return null;
        if (w.kind == Catalog.Kind.WARP_PLATE) {
            return new Location(world, w.x + 0.5, w.y + 0.1, w.z + 0.5);
        }
        float[] yaws = {w.facing, w.facing + 90, w.facing + 180, w.facing + 270};
        for (float yaw : yaws) {
            double rad = Math.toRadians(yaw);
            int dx = (int) Math.round(-Math.sin(rad));
            int dz = (int) Math.round(Math.cos(rad));
            Block feet = world.getBlockAt(w.x + dx, w.y, w.z + dz);
            if (safe(feet)) return new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, yaw, 0);
            Block up = feet.getRelative(0, 1, 0);
            if (safe(up)) return new Location(world, up.getX() + 0.5, up.getY(), up.getZ() + 0.5, yaw, 0);
        }
        return new Location(world, w.x + 0.5, w.y + 2, w.z + 0.5, w.facing, 0);
    }

    private boolean safe(Block feet) {
        Block below = feet.getRelative(0, -1, 0);
        return feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                && !feet.isLiquid() && (below.getType().isSolid() || below.isLiquid());
    }

    /** Re-creates missing visuals for every placed waystone in a freshly loaded chunk. */
    public void repairChunk(Chunk chunk) {
        String world = chunk.getWorld().getName();
        for (Waystone w : plugin.store().all()) {
            if (!world.equals(w.world) || (w.x >> 4) != chunk.getX() || (w.z >> 4) != chunk.getZ()) continue;
            boolean found = false;
            for (Entity e : chunk.getEntities()) {
                if (e instanceof ItemDisplay && w.id.equals(idOf(e))) { found = true; break; }
            }
            if (!found) spawn(w);
        }
    }
}
