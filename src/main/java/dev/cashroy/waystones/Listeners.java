package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Catalog.Visibility;
import dev.cashroy.waystones.Store.PlayerData;
import dev.cashroy.waystones.Teleporter.Context;
import dev.cashroy.waystones.Teleporter.Destination;
import dev.cashroy.waystones.Teleporter.Source;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Placing, using and breaking the blocks; using the items; epitaphs; renaming. */
public final class Listeners implements Listener {

    private record Pending(UUID waystone, long until) {}

    private final WaystonesPlugin plugin;
    private final Map<UUID, Pending> renames = new HashMap<>();
    private final Map<UUID, BukkitTask> charging = new HashMap<>();
    /** Debounce: main-hand interaction already handled this tick. */
    private final Map<UUID, Integer> handled = new HashMap<>();

    public Listeners(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    // ── clicking blocks and air ──────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;

        // Right-clicking one of our barrier blocks directly (if the interaction entity was missed).
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.BARRIER) {
            Waystone w = plugin.store().at(e.getClickedBlock().getLocation());
            if (w != null) {
                e.setCancelled(true);
                if (hand == EquipmentSlot.HAND) use(p, w);
                return;
            }
        }

        ItemStack item = e.getItem();
        String id = plugin.items().idOf(item);
        if (id == null) return;
        if (hand == EquipmentSlot.OFF_HAND && plugin.items().idOf(p.getInventory().getItemInMainHand()) != null) return;
        e.setCancelled(true);

        if (Items.isBlock(id)) {
            if (a == Action.RIGHT_CLICK_BLOCK) place(p, item, hand, e.getClickedBlock(), e.getBlockFace());
            return;
        }
        if (id.equals("twinbound_feather")) {
            plugin.twinbound().wave(p);
            return;
        }
        if (id.startsWith("warp_stone") || id.endsWith("_scroll")) {
            if (id.equals("unbound_scroll")) return;
            startCharge(p, id, hand);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction)) return;
        UUID id = plugin.structures().idOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Waystone w = plugin.store().get(id);
        if (w == null) {
            e.getRightClicked().remove(); // orphan
            return;
        }
        use(e.getPlayer(), w);
    }

    private void use(Player p, Waystone w) {
        int tick = Bukkit.getCurrentTick();
        Integer last = handled.put(p.getUniqueId(), tick);
        if (last != null && last == tick) return;

        ItemStack held = p.getInventory().getItemInMainHand();

        if (plugin.items().is(held, "unbound_scroll") && w.kind != Kind.PORTSTONE) {
            if (w.kind == Kind.WAYSTONE && !plugin.menus().canSee(p, w)) activate(p, w);
            if (p.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
            Menus.give(p, plugin.items().boundScroll(w));
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
            p.sendActionBar(Component.text("Scroll bound to " + w.name).color(NamedTextColor.LIGHT_PURPLE));
            return;
        }

        if (p.isSneaking() || w.kind == Kind.WARP_PLATE) {
            plugin.menus().openEditor(p, w);
            return;
        }

        switch (w.kind) {
            case WAYSTONE -> {
                PlayerData pd = plugin.store().player(p.getUniqueId());
                if (!pd.activated.contains(w.id)) {
                    activate(p, w);
                    return;
                }
                plugin.menus().openSelection(p, new Context(p, Source.WAYSTONE, w, null, null), Menus.Purpose.WARP);
            }
            case SHARESTONE -> plugin.menus().openSelection(p, new Context(p, Source.SHARESTONE, w, null, null), Menus.Purpose.WARP);
            case PORTSTONE -> plugin.menus().openSelection(p, new Context(p, Source.PORTSTONE, w, null, null), Menus.Purpose.WARP);
            default -> { }
        }
    }

    public void activate(Player p, Waystone w) {
        PlayerData pd = plugin.store().player(p.getUniqueId());
        if (!pd.activated.add(w.id)) return;
        plugin.store().markDirty();
        Location c = w.centre().add(0, 1, 0);
        c.getWorld().spawnParticle(Particle.ENCHANT, c, 80, 0.4, 0.8, 0.4, 1);
        c.getWorld().spawnParticle(Particle.END_ROD, c, 12, 0.3, 0.7, 0.3, 0.02);
        p.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
        p.showTitle(net.kyori.adventure.title.Title.title(
                Component.text(w.name).color(NamedTextColor.AQUA),
                Component.text("Waystone activated").color(NamedTextColor.GRAY)));
    }

    // ── placing ──────────────────────────────────────────────

    private void place(Player p, ItemStack item, EquipmentSlot hand, Block clicked, BlockFace face) {
        if (clicked == null) return;
        String id = plugin.items().idOf(item);
        Kind kind = Items.kindOf(id);
        Block target = clicked.isReplaceable() && plugin.store().at(clicked.getLocation()) == null ? clicked : clicked.getRelative(face);
        boolean tall = Catalog.isTall(kind);
        if (!fits(target) || (tall && !fits(target.getRelative(0, 1, 0)))) {
            plugin.teleporter().fail(p, "There isn't enough room here.");
            return;
        }
        if (kind == Kind.WARP_PLATE && !target.getRelative(0, -1, 0).getType().isSolid()) {
            plugin.teleporter().fail(p, "Warp plates need solid ground.");
            return;
        }
        if (!target.getWorld().getNearbyEntities(target.getBoundingBox().expand(0, tall ? 1 : 0, 0),
                ent -> ent instanceof org.bukkit.entity.LivingEntity).isEmpty()) {
            plugin.teleporter().fail(p, "Something is in the way.");
            return;
        }

        UUID storedId = plugin.items().uuidTag(item, plugin.items().keyStored);
        Waystone w = storedId == null ? null : plugin.store().get(storedId);
        boolean fresh = w == null || w.isPlaced();
        if (fresh) {
            w = new Waystone(UUID.randomUUID());
            w.kind = kind;
            w.variant = Items.variantOf(id);
            w.owner = p.getUniqueId();
            w.visibility = kind == Kind.SHARESTONE ? Visibility.GLOBAL : plugin.cfg().defaultVisibility;
            w.name = kind == Kind.WAYSTONE ? plugin.nameGen().generate(target.getBiome())
                    : Catalog.displayName(kind, w.variant);
        }
        w.world = target.getWorld().getName();
        w.x = target.getX();
        w.y = target.getY();
        w.z = target.getZ();
        w.facing = snap(p.getLocation().getYaw() + 180f);

        target.setType(Material.AIR, false);
        if (tall) target.getRelative(0, 1, 0).setType(Material.AIR, false);

        if (fresh) plugin.store().add(w);
        else plugin.store().reindex(w, null);
        plugin.structures().spawn(w);

        if (p.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
            p.getInventory().setItem(hand, item.getAmount() <= 0 ? null : item);
        }
        p.swingHand(hand);
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 0.9f);

        if (kind == Kind.WAYSTONE) activate(p, w);
        if (kind == Kind.WARP_PLATE && fresh) {
            Menus.give(p, plugin.items().attunedShard(w));
            p.sendMessage(Component.text("The warp plate popped out an Attuned Shard — put it in another warp plate to link the two.")
                    .color(NamedTextColor.LIGHT_PURPLE));
        }
        if (fresh && kind != Kind.PORTSTONE) promptRename(p, w);
    }

    private boolean fits(Block b) {
        return (b.isEmpty() || b.isReplaceable()) && !b.isLiquid() && plugin.store().at(b.getLocation()) == null;
    }

    private static float snap(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        return Math.round(y / 90f) % 4 * 90f;
    }

    // ── breaking ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!(e.getAttacked() instanceof Interaction)) return;
        UUID id = plugin.structures().idOf(e.getAttacked());
        if (id == null) return;
        e.setCancelled(true);
        Waystone w = plugin.store().get(id);
        if (w == null) {
            e.getAttacked().remove();
            return;
        }
        breakBlock(e.getPlayer(), w);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.BARRIER) return;
        Waystone w = plugin.store().at(e.getBlock().getLocation());
        if (w == null) return;
        e.setCancelled(true);
        breakBlock(e.getPlayer(), w);
    }

    private void breakBlock(Player p, Waystone w) {
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        if (plugin.cfg().ownerOnlyBreak && w.owner != null && !w.owner.equals(p.getUniqueId())
                && !p.hasPermission("waystones.break.any")) {
            plugin.teleporter().fail(p, "Only the owner can break this.");
            return;
        }
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean pickaxe = Tag.ITEMS_PICKAXES.isTagged(tool.getType());
        if (!creative && !pickaxe) {
            plugin.teleporter().fail(p, "You need a pickaxe to break this.");
            return;
        }
        boolean silk = pickaxe && tool.containsEnchantment(Enchantment.SILK_TOUCH);
        Location drop = w.centre().add(0, 0.5, 0);
        World world = drop.getWorld();

        plugin.structures().despawn(w);
        world.spawnParticle(Particle.BLOCK, drop, 40, 0.3, 0.6, 0.3, Material.STONE_BRICKS.createBlockData());
        world.playSound(drop, Sound.BLOCK_STONE_BREAK, 1f, 0.8f);

        if (silk && w.kind != Kind.WARP_PLATE) {
            if (!creative) world.dropItemNaturally(drop, plugin.items().createStored(w));
            plugin.store().unplace(w);
        } else {
            if (!creative) {
                world.dropItemNaturally(drop, plugin.items().create(Catalog.blockItemId(w.kind, w.variant)));
                for (ItemStack it : w.slots) if (it != null && !it.isEmpty()) world.dropItemNaturally(drop, it);
            }
            plugin.store().remove(w);
        }
        if (!creative && tool.getType().getMaxDurability() > 0) tool.damage(1, p);
    }

    // ── charging items (scrolls, warp stones) ────────────────

    private void startCharge(Player p, String id, EquipmentSlot hand) {
        if (charging.containsKey(p.getUniqueId())) return;
        int total = plugin.cfg().chargeTicks;
        if (total <= 0) {
            finishCharge(p, id, hand);
            return;
        }
        Location start = p.getLocation();
        p.playSound(start, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.4f);
        int[] t = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            boolean still = p.isOnline() && p.getWorld().equals(start.getWorld())
                    && p.getLocation().distanceSquared(start) < 0.36
                    && id.equals(plugin.items().idOf(p.getInventory().getItem(hand)));
            if (!still) {
                stopCharge(p);
                if (p.isOnline()) p.sendActionBar(Component.text("Warp cancelled").color(NamedTextColor.GRAY));
                return;
            }
            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 10, 0.4, 0.6, 0.4, 0.4);
            int bars = Math.min(20, t[0] * 20 / total);
            p.sendActionBar(Component.text("■".repeat(bars)).color(NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("■".repeat(20 - bars)).color(NamedTextColor.DARK_GRAY)));
            if (t[0] >= total) {
                stopCharge(p);
                finishCharge(p, id, hand);
            }
        }, 2L, 2L);
        charging.put(p.getUniqueId(), task);
    }

    private void stopCharge(Player p) {
        BukkitTask t = charging.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    private void finishCharge(Player p, String id, EquipmentSlot hand) {
        if (id.startsWith("warp_stone")) {
            plugin.menus().openSelection(p, new Context(p, Source.WARP_STONE, null, id, hand), Menus.Purpose.WARP);
            return;
        }
        switch (id) {
            case "warp_scroll" -> plugin.menus().openSelection(p, new Context(p, Source.WARP_SCROLL, null, id, hand), Menus.Purpose.WARP);
            case "portal_scroll" -> plugin.menus().openSelection(p, new Context(p, Source.PORTAL_SCROLL, null, id, hand), Menus.Purpose.PORTAL);
            case "return_scroll" -> {
                Waystone nearest = nearestActivated(p);
                if (nearest == null) {
                    plugin.teleporter().fail(p, "You haven't activated any waystones yet.");
                    return;
                }
                plugin.teleporter().warp(new Context(p, Source.RETURN_SCROLL, null, id, hand), Destination.of(nearest));
            }
            case "bound_scroll" -> {
                ItemStack it = p.getInventory().getItem(hand);
                Waystone target = plugin.store().get(plugin.items().uuidTag(it, plugin.items().keyTarget));
                if (target == null || !target.isPlaced()) {
                    plugin.teleporter().fail(p, "The place this scroll was bound to is gone.");
                    return;
                }
                plugin.teleporter().warp(new Context(p, Source.BOUND_SCROLL, null, id, hand), Destination.of(target));
            }
            default -> { }
        }
    }

    public Waystone nearestActivated(Player p) {
        Waystone best = null;
        double bestDist = Double.MAX_VALUE;
        for (Waystone w : plugin.store().all()) {
            if (w.kind != Kind.WAYSTONE || !plugin.menus().canSee(p, w)) continue;
            Location c = w.centre();
            double d = c.getWorld().equals(p.getWorld()) ? c.distanceSquared(p.getLocation()) : Double.MAX_VALUE / 2;
            if (d < bestDist) {
                bestDist = d;
                best = w;
            }
        }
        return best;
    }

    // ── renaming via chat ────────────────────────────────────

    public void promptRename(Player p, Waystone w) {
        renames.put(p.getUniqueId(), new Pending(w.id, System.currentTimeMillis() + 60_000));
        p.sendMessage(Component.text("Type a name for ").color(NamedTextColor.GRAY)
                .append(Component.text(w.name).color(NamedTextColor.AQUA))
                .append(Component.text(" in chat, or type ").color(NamedTextColor.GRAY))
                .append(Component.text("cancel").color(NamedTextColor.YELLOW))
                .append(Component.text(" to keep it.").color(NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pending = renames.get(e.getPlayer().getUniqueId());
        if (pending == null) return;
        if (pending.until() < System.currentTimeMillis()) {
            renames.remove(e.getPlayer().getUniqueId());
            return;
        }
        e.setCancelled(true);
        renames.remove(e.getPlayer().getUniqueId());
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = e.getPlayer();
            Waystone w = plugin.store().get(pending.waystone());
            if (w == null) return;
            if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
                p.sendMessage(Component.text("Kept the name " + w.name + ".").color(NamedTextColor.GRAY));
                return;
            }
            String name = text.length() > 32 ? text.substring(0, 32) : text;
            w.name = name;
            plugin.store().markDirty();
            plugin.structures().updateName(w);
            p.sendMessage(Component.text("Renamed to " + name + ".").color(NamedTextColor.GREEN));
        });
    }

    // ── epitaph ──────────────────────────────────────────────

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        boolean found = false;
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (plugin.items().is(drop, "epitaph")) {
                if (drop.getAmount() > 1) drop.setAmount(drop.getAmount() - 1);
                else it.remove();
                found = true;
                break;
            }
        }
        if (!found && e.getKeepInventory()) {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (plugin.items().is(s, "epitaph")) {
                    s.setAmount(s.getAmount() - 1);
                    p.getInventory().setItem(i, s.getAmount() <= 0 ? null : s);
                    found = true;
                    break;
                }
            }
        }
        if (!found) return;
        plugin.store().player(p.getUniqueId()).memorial = p.getLocation().clone();
        plugin.store().markDirty();
        p.sendMessage(Component.text("Your Epitaph left a Fleeting Memorial where you fell. Pick it from any waystone menu.")
                .color(NamedTextColor.DARK_AQUA));
    }

    // ── misc ─────────────────────────────────────────────────

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r == null) return;
        boolean ours = r instanceof Keyed k && plugin.items().recipeKeys().contains(k.getKey());
        if (!ours && plugin.items().containsCustom(e.getInventory().getMatrix())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.pack().send(e.getPlayer());
        e.getPlayer().discoverRecipes(plugin.items().recipeKeys());
        PlayerData pd = plugin.store().player(e.getPlayer().getUniqueId());
        for (Waystone w : plugin.store().all()) {
            if (e.getPlayer().getUniqueId().equals(w.owner) && w.kind == Kind.WAYSTONE) pd.activated.add(w.id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stopCharge(e.getPlayer());
        renames.remove(e.getPlayer().getUniqueId());
        handled.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        plugin.structures().repairChunk(e.getChunk());
    }

    /** Stops players right-clicking a feather etc. on a block from doing vanilla things. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityInteractWithItem(PlayerInteractAtEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (clicked instanceof Interaction && plugin.structures().idOf(clicked) != null) e.setCancelled(true);
    }
}
