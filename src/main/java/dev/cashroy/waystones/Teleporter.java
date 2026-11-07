package dev.cashroy.waystones;

import dev.cashroy.waystones.Settings.TransportMobs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Everything about actually moving players: costs, cooldowns, companions, modifiers, effects. */
public final class Teleporter {

    public enum Source {
        WAYSTONE, SHARESTONE, PORTSTONE, WARP_STONE, WARP_SCROLL, RETURN_SCROLL,
        BOUND_SCROLL, PORTAL_SCROLL, PORTAL, INVENTORY_BUTTON, WARP_PLATE, COMMAND
    }

    public enum DestType { WAYSTONE, MEMORIAL, RETURN_PORTAL, PLAYER }

    public record Destination(DestType type, String label, Waystone waystone, Location location, UUID playerId) {
        public static Destination of(Waystone w) {
            return new Destination(DestType.WAYSTONE, w.name, w, null, null);
        }
    }

    /** Where a warp request came from, and the item that triggered it (if any). */
    public record Context(Player player, Source source, Waystone from, String itemId, EquipmentSlot hand) {}

    private static final Set<PotionEffectType> NEGATIVE = Set.of(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.BLINDNESS,
            PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA,
            PotionEffectType.HUNGER, PotionEffectType.WEAKNESS, PotionEffectType.DARKNESS,
            PotionEffectType.LEVITATION, PotionEffectType.UNLUCK);

    private final WaystonesPlugin plugin;

    public Teleporter(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    // ── resolving destinations ───────────────────────────────

    public Location targetLocation(Destination d) {
        return switch (d.type()) {
            case WAYSTONE -> d.waystone().isPlaced() ? plugin.structures().arrival(d.waystone()) : null;
            case MEMORIAL, RETURN_PORTAL -> d.location();
            case PLAYER -> {
                Player p = Bukkit.getPlayer(d.playerId());
                yield p == null ? null : p.getLocation();
            }
        };
    }

    // ── costs ────────────────────────────────────────────────

    public int xpCost(Context ctx, Destination dest) {
        Settings s = plugin.cfg();
        if (!s.xpCosts) return 0;
        if (ctx.player().getGameMode() == GameMode.CREATIVE || ctx.player().getGameMode() == GameMode.SPECTATOR) return 0;
        boolean usesXp = (ctx.source() == Source.WAYSTONE || ctx.source() == Source.WARP_STONE)
                && dest.type() != DestType.MEMORIAL;
        if (!usesXp) return 0;
        Location to = targetLocation(dest);
        if (to == null) return 0;
        Location from = ctx.player().getLocation();
        double cost;
        if (!Objects.equals(to.getWorld(), from.getWorld())) {
            cost = s.interdimensionalXp;
        } else {
            cost = from.distance(to) * s.xpPerBlock;
        }
        cost = Math.max(s.minXp, Math.min(s.maxXp, cost));
        return (int) Math.ceil(cost);
    }

    private String cooldownKey(Source src) {
        return switch (src) {
            case WAYSTONE, SHARESTONE, PORTSTONE -> "waystone";
            case WARP_STONE -> "warp_stone";
            case INVENTORY_BUTTON -> "inventory_button";
            case WARP_SCROLL, RETURN_SCROLL, BOUND_SCROLL, PORTAL_SCROLL -> "scrolls";
            default -> null;
        };
    }

    private int cooldownSeconds(Source src) {
        Settings s = plugin.cfg();
        return switch (src) {
            case WAYSTONE, SHARESTONE, PORTSTONE -> s.cdWaystone;
            case WARP_STONE -> s.cdWarpStone;
            case INVENTORY_BUTTON -> s.cdButton;
            case WARP_SCROLL, RETURN_SCROLL, BOUND_SCROLL, PORTAL_SCROLL -> s.cdScrolls;
            default -> 0;
        };
    }

    /** Seconds left on the cooldown for this source, 0 if ready. */
    public long cooldownLeft(Player p, Source src) {
        if (!plugin.cfg().cooldowns || p.hasPermission("waystones.admin") && p.getGameMode() == GameMode.CREATIVE) return 0;
        String key = cooldownKey(src);
        if (key == null) return 0;
        Long until = plugin.store().player(p.getUniqueId()).cooldowns.get(key);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    private void startCooldown(Player p, Source src) {
        String key = cooldownKey(src);
        int secs = cooldownSeconds(src);
        if (key == null || secs <= 0 || !plugin.cfg().cooldowns) return;
        plugin.store().player(p.getUniqueId()).cooldowns.put(key, System.currentTimeMillis() + secs * 1000L);
        plugin.store().markDirty();
    }

    // ── the warp itself ──────────────────────────────────────

    /** Attempts the warp. Returns true if the player was moved. */
    public boolean warp(Context ctx, Destination dest) {
        Player p = ctx.player();
        Location to = targetLocation(dest);
        if (to == null) {
            fail(p, "That destination no longer exists.");
            return false;
        }

        long cd = cooldownLeft(p, ctx.source());
        if (cd > 0) {
            fail(p, "You need to wait " + formatSeconds(cd) + " before warping like that again.");
            return false;
        }

        int cost = xpCost(ctx, dest);
        if (cost > 0 && p.calculateTotalExperiencePoints() < cost) {
            fail(p, "You need " + cost + " experience points to warp there.");
            return false;
        }

        // The triggering item must still be in hand.
        ItemStack item = null;
        if (ctx.itemId() != null && ctx.hand() != null) {
            item = p.getInventory().getItem(ctx.hand());
            if (!ctx.itemId().equals(plugin.items().idOf(item))) {
                fail(p, "You need to keep holding the " + Items.nameOf(ctx.itemId()) + ".");
                return false;
            }
        }

        // Pay
        if (cost > 0) p.setExperienceLevelAndProgress(p.calculateTotalExperiencePoints() - cost);
        if (item != null) consume(p, ctx, item);
        startCooldown(p, ctx.source());

        // Companions are gathered before the player leaves.
        boolean crossDim = !Objects.equals(p.getWorld(), to.getWorld());
        List<Entity> companions = companions(p, crossDim);

        Location from = p.getLocation();
        effects(from);
        List<ItemStack> mods = ctx.from() != null && plugin.cfg().modifiers ? ctx.from().slots : List.of();

        p.teleportAsync(to).thenAccept(ok -> {
            if (!ok) return;
            effects(to);
            applyModifiers(p, mods);
            for (Entity e : companions) {
                if (e.isValid()) e.teleportAsync(to);
            }
            if (dest.type() == DestType.MEMORIAL) {
                plugin.store().player(p.getUniqueId()).memorial = null;
                plugin.store().markDirty();
            }
        });
        return true;
    }

    /** Teleports a non-player entity (warp plates, portals). */
    public void warpEntity(Entity e, Location to, List<ItemStack> modifiers) {
        if (plugin.cfg().denyList.contains(e.getType())) return;
        Location from = e.getLocation();
        if (e instanceof Player) effects(from);
        Location dest = to.clone();
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());
        e.teleportAsync(dest).thenAccept(ok -> {
            if (!ok) return;
            if (e instanceof Player) effects(dest);
            else dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 0.5, 0), 20, 0.3, 0.4, 0.3, 0.2);
            if (e instanceof LivingEntity le && plugin.cfg().modifiers) applyModifiers(le, modifiers);
        });
    }

    private void consume(Player p, Context ctx, ItemStack item) {
        String id = ctx.itemId();
        if (id.startsWith("warp_stone")) {
            if (!plugin.cfg().durability || p.getGameMode() == GameMode.CREATIVE) return;
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof Damageable d) {
                int max = d.hasMaxDamage() ? d.getMaxDamage() : plugin.cfg().warpStoneMaxDurability;
                int dmg = d.getDamage() + plugin.cfg().warpStoneDamage;
                if (dmg >= max) {
                    p.getInventory().setItem(ctx.hand(), null);
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                    return;
                }
                d.setDamage(dmg);
                item.setItemMeta(meta);
                p.getInventory().setItem(ctx.hand(), item);
            }
            return;
        }
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (id.endsWith("_scroll")) {
            item.setAmount(item.getAmount() - 1);
            p.getInventory().setItem(ctx.hand(), item.getAmount() <= 0 ? null : item);
        }
    }

    private List<Entity> companions(Player p, boolean crossDim) {
        Settings s = plugin.cfg();
        List<Entity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(10, 6, 10)) {
            if (s.denyList.contains(e.getType())) continue;
            if (e instanceof LivingEntity le && le.isLeashed()) {
                Entity holder;
                try {
                    holder = le.getLeashHolder();
                } catch (IllegalStateException ex) {
                    continue;
                }
                if (p.equals(holder) && allowed(s.leashed, crossDim)) {
                    out.add(e);
                    continue;
                }
            }
            if (e instanceof Tameable t && t.isTamed() && p.getUniqueId().equals(t.getOwnerUniqueId())
                    && allowed(s.pets, crossDim)) {
                if (e instanceof Sittable sit && sit.isSitting()) continue;
                out.add(e);
            }
        }
        return out;
    }

    private boolean allowed(TransportMobs mode, boolean crossDim) {
        return mode == TransportMobs.ENABLED || (mode == TransportMobs.SAME_DIMENSION && !crossDim);
    }

    // ── modifiers ────────────────────────────────────────────

    /** Applies warp-modifier items (blaze powder, ink sac, milk...) to whoever just warped. */
    public void applyModifiers(LivingEntity e, List<ItemStack> slots) {
        if (slots == null || slots.isEmpty()) return;
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack it : slots) {
            if (it == null || it.isEmpty() || plugin.items().idOf(it) != null) continue;
            counts.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        if (counts.isEmpty()) return;
        int potency = 1 + Math.min(4, counts.getOrDefault(Material.DIAMOND, 0));

        if (counts.containsKey(Material.MILK_BUCKET) || counts.containsKey(Material.HONEY_BLOCK)) {
            for (PotionEffect pe : new ArrayList<>(e.getActivePotionEffects())) {
                if (NEGATIVE.contains(pe.getType())) e.removePotionEffect(pe.getType());
            }
            e.setFireTicks(0);
        }
        int blaze = counts.getOrDefault(Material.BLAZE_POWDER, 0);
        if (blaze > 0) e.setFireTicks(Math.max(e.getFireTicks(), 20 * blaze * potency));
        effect(e, PotionEffectType.POISON, counts.getOrDefault(Material.POISONOUS_POTATO, 0), potency);
        effect(e, PotionEffectType.BLINDNESS, counts.getOrDefault(Material.INK_SAC, 0), potency);
        effect(e, PotionEffectType.WITHER, counts.getOrDefault(Material.WITHER_ROSE, 0), potency);
        effect(e, PotionEffectType.SLOW_FALLING, counts.getOrDefault(Material.FEATHER, 0), potency);
        effect(e, PotionEffectType.FIRE_RESISTANCE, counts.getOrDefault(Material.MAGMA_CREAM, 0), potency);
    }

    private void effect(LivingEntity e, PotionEffectType type, int count, int potency) {
        if (count <= 0) return;
        e.addPotionEffect(new PotionEffect(type, 20 * count * potency, 0));
    }

    // ── feedback ─────────────────────────────────────────────

    public void effects(Location l) {
        World w = l.getWorld();
        w.spawnParticle(Particle.PORTAL, l.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.4);
        w.spawnParticle(Particle.REVERSE_PORTAL, l.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        w.playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.1f);
    }

    public void fail(Player p, String msg) {
        p.sendActionBar(Component.text(msg).color(NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
    }

    public static String formatSeconds(long s) {
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }
}
