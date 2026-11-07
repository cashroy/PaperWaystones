package dev.cashroy.waystones;

import dev.cashroy.waystones.Teleporter.Context;
import dev.cashroy.waystones.Teleporter.Destination;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

/** Temporary portals opened with a Portal Scroll. */
public final class Portals {

    private static final class Portal {
        UUID id;
        Location block;      // bottom block of the portal
        Location front;      // where "Return to Portal" drops you
        Destination dest;
        long expiresAt;
        UUID display;
    }

    private final WaystonesPlugin plugin;
    private final Map<UUID, Portal> portals = new HashMap<>();
    /** entity -> server tick until which it ignores portals (prevents ping-pong) */
    private final Map<UUID, Integer> immune = new HashMap<>();

    public Portals(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    public Location returnLocation(UUID portalId) {
        Portal p = portals.get(portalId);
        return p == null ? null : p.front.clone();
    }

    /** Called from the selection menu after the player picked where the portal should lead. */
    public void open(Context ctx, Destination dest) {
        Player player = ctx.player();
        ItemStack held = player.getInventory().getItem(ctx.hand());
        if (!plugin.items().is(held, "portal_scroll")) {
            plugin.teleporter().fail(player, "You need to keep holding the Portal Scroll.");
            return;
        }
        if (plugin.teleporter().targetLocation(dest) == null) {
            plugin.teleporter().fail(player, "That destination no longer exists.");
            return;
        }
        Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        // Snap to the nearest axis so the portal lines up with the block grid.
        Vector axis = Math.abs(dir.getX()) > Math.abs(dir.getZ())
                ? new Vector(Math.signum(dir.getX()), 0, 0) : new Vector(0, 0, Math.signum(dir.getZ()));

        Block spot = null;
        for (int dist : new int[]{2, 3, 1}) {
            Block b = player.getLocation().add(axis.clone().multiply(dist)).getBlock();
            for (int dy : new int[]{0, 1, -1}) {
                Block c = b.getRelative(0, dy, 0);
                if (c.isPassable() && !c.isLiquid() && c.getRelative(0, 1, 0).isPassable()
                        && c.getRelative(0, -1, 0).getType().isSolid()) {
                    spot = c;
                    break;
                }
            }
            if (spot != null) break;
        }
        if (spot == null) {
            plugin.teleporter().fail(player, "There isn't enough open space on solid ground in front of you.");
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItem(ctx.hand(), held.getAmount() <= 0 ? null : held);
        }

        Portal p = new Portal();
        p.id = UUID.randomUUID();
        p.block = spot.getLocation();
        Block frontBlock = spot.getRelative(-axis.getBlockX(), 0, -axis.getBlockZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-axis.getX(), axis.getZ()));
        p.front = new Location(spot.getWorld(), frontBlock.getX() + 0.5, frontBlock.getY(), frontBlock.getZ() + 0.5, yaw + 180, 0);
        p.dest = dest;
        p.expiresAt = System.currentTimeMillis() + plugin.cfg().portalMinutes * 60_000L;

        Location model = new Location(spot.getWorld(), spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, yaw, 0);
        ItemDisplay d = spot.getWorld().spawn(model, ItemDisplay.class, disp -> {
            ItemStack shown = new ItemStack(Material.PAPER);
            ItemMeta meta = shown.getItemMeta();
            meta.setItemModel(Items.modelKey("portal"));
            shown.setItemMeta(meta);
            disp.setItemStack(shown);
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.addScoreboardTag(Structures.TAG);
        });
        p.display = d.getUniqueId();
        portals.put(p.id, p);
        spot.getWorld().playSound(model, Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.4f);
        immune.put(player.getUniqueId(), Bukkit.getCurrentTick() + 20);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        immune.values().removeIf(t -> t < tick);
        Iterator<Portal> it = portals.values().iterator();
        while (it.hasNext()) {
            Portal p = it.next();
            World w = p.block.getWorld();
            if (now > p.expiresAt || w == null) {
                close(p);
                it.remove();
                for (Store.PlayerData pd : plugin.store().allPlayers().values()) {
                    if (p.id.equals(pd.returnPortal)) pd.returnPortal = null;
                }
                continue;
            }
            if (!w.isChunkLoaded(p.block.getBlockX() >> 4, p.block.getBlockZ() >> 4)) continue;
            Location c = p.block.clone().add(0.5, 1, 0.5);
            w.spawnParticle(Particle.PORTAL, c, 6, 0.3, 0.7, 0.3, 0.3);
            Location to = plugin.teleporter().targetLocation(p.dest);
            if (to == null) continue;
            BoundingBox box = new BoundingBox(p.block.getX() + 0.15, p.block.getY(), p.block.getZ() + 0.15,
                    p.block.getX() + 0.85, p.block.getY() + 2, p.block.getZ() + 0.85);
            for (Entity e : w.getNearbyEntities(box)) {
                if (e instanceof Display || e instanceof Interaction || immune.containsKey(e.getUniqueId())) continue;
                if (plugin.cfg().denyList.contains(e.getType())) continue;
                immune.put(e.getUniqueId(), tick + 60);
                if (e instanceof Player pl) {
                    plugin.store().player(pl.getUniqueId()).returnPortal = p.id;
                }
                plugin.teleporter().warpEntity(e, to, List.of());
            }
        }
    }

    private void close(Portal p) {
        Entity e = Bukkit.getEntity(p.display);
        if (e != null) {
            e.getWorld().playSound(e.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 1.2f);
            e.remove();
        }
    }

    public void closeAll() {
        for (Portal p : portals.values()) close(p);
        portals.clear();
    }

    /** Marks an entity as just-arrived so it doesn't immediately re-trigger something. */
    public void markImmune(Entity e, int ticks) {
        immune.put(e.getUniqueId(), Bukkit.getCurrentTick() + ticks);
    }
}
