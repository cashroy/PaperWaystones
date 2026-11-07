package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Warp plates: stand (or drop items / walk mobs) on one to be sent to a linked plate. */
public final class Plates {
    public static final int PERIOD = 2;

    private final WaystonesPlugin plugin;
    /** entity -> {plate it's charging on, ticks charged} */
    private final Map<UUID, Object[]> charging = new HashMap<>();
    /** entity -> {plate it arrived on, tick it arrived}; that plate is ignored until it steps off */
    private final Map<UUID, Object[]> arrived = new HashMap<>();

    public Plates(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        Set<UUID> present = new HashSet<>();
        for (Waystone plate : plugin.store().all()) {
            if (plate.kind != Kind.WARP_PLATE || !plate.isPlaced()) continue;
            World w = plate.bukkitWorld();
            if (!w.isChunkLoaded(plate.x >> 4, plate.z >> 4)) continue;

            BoundingBox box = new BoundingBox(plate.x, plate.y, plate.z, plate.x + 1, plate.y + 0.6, plate.z + 1);
            Collection<Entity> on = w.getNearbyEntities(box, e -> !(e instanceof Display) && !(e instanceof Interaction)
                    && !(e instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR));
            if (on.isEmpty()) continue;

            List<Waystone> targets = targets(plate);
            boolean blocked = has(plate, Material.REDSTONE_TORCH) && powered(plate);
            int delay = delay(plate);

            for (Entity e : on) {
                UUID id = e.getUniqueId();
                present.add(id);
                Object[] arr = arrived.get(id);
                if (arr != null && plate.id.equals(arr[0])) continue;
                if (targets.isEmpty() || blocked || plugin.cfg().denyList.contains(e.getType())) continue;

                Object[] state = charging.get(id);
                if (state == null || !plate.id.equals(state[0])) {
                    state = new Object[]{plate.id, 0};
                    charging.put(id, state);
                }
                int ticks = (int) state[1] + PERIOD;
                state[1] = ticks;
                w.spawnParticle(Particle.PORTAL, e.getLocation().add(0, 0.2, 0), 4, 0.25, 0.1, 0.25, 0.3);

                if (ticks >= delay) {
                    charging.remove(id);
                    Waystone target = pick(plate, targets);
                    Location to = plugin.structures().arrival(target);
                    if (to == null) continue;
                    arrived.put(id, new Object[]{target.id, Bukkit.getCurrentTick()});
                    plugin.teleporter().warpEntity(e, to, plate.slots);
                }
            }
        }
        charging.keySet().retainAll(present);
        int now = Bukkit.getCurrentTick();
        // Forget the arrival lock once the entity has stepped off (allowing time for the async teleport).
        arrived.entrySet().removeIf(en -> !present.contains(en.getKey()) && now - (int) en.getValue()[1] > 20);
    }

    private List<Waystone> targets(Waystone plate) {
        List<Waystone> out = new ArrayList<>();
        for (ItemStack it : plate.slots) {
            if (!plugin.items().is(it, "attuned_shard")) continue;
            Waystone t = plugin.store().get(plugin.items().uuidTag(it, plugin.items().keyTarget));
            if (t != null && t.isPlaced() && t.kind == Kind.WARP_PLATE && !t.id.equals(plate.id)) out.add(t);
        }
        return out;
    }

    private Waystone pick(Waystone plate, List<Waystone> targets) {
        if (targets.size() == 1) return targets.get(0);
        if (has(plate, Material.QUARTZ)) {
            plate.roundRobin = (plate.roundRobin + 1) % targets.size();
            plugin.store().markDirty();
            return targets.get(plate.roundRobin);
        }
        return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
    }

    private int delay(Waystone plate) {
        double d = plugin.cfg().plateDelay;
        if (plugin.cfg().modifiers) {
            d *= Math.pow(0.7, count(plate, Material.AMETHYST_SHARD));
            d *= Math.pow(1.4, count(plate, Material.SLIME_BALL));
        }
        return (int) Math.max(PERIOD, Math.round(d));
    }

    private boolean powered(Waystone plate) {
        Block b = plate.block();
        return b != null && (b.isBlockIndirectlyPowered() || b.getRelative(0, -1, 0).isBlockIndirectlyPowered());
    }

    private boolean has(Waystone plate, Material m) {
        return count(plate, m) > 0;
    }

    private int count(Waystone plate, Material m) {
        int n = 0;
        for (ItemStack it : plate.slots) {
            if (it != null && it.getType() == m && plugin.items().idOf(it) == null) n += it.getAmount();
        }
        return Math.min(n, 8);
    }
}
