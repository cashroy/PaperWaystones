package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Catalog.Visibility;
import dev.cashroy.waystones.Store.PlayerData;
import dev.cashroy.waystones.Teleporter.Context;
import dev.cashroy.waystones.Teleporter.DestType;
import dev.cashroy.waystones.Teleporter.Destination;
import dev.cashroy.waystones.Teleporter.Source;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;

import java.util.*;

/** Chest-style menus: the destination picker, the waystone editor and the warp plate editor. */
public final class Menus implements Listener {

    public enum Purpose { WARP, PORTAL }

    private static final int PAGE_SIZE = 45;
    private static final int AUX_START = 13;

    private abstract static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class SelectHolder extends Holder {
        Context ctx;
        Purpose purpose;
        List<Destination> entries;
        int page;
    }

    private static final class EditHolder extends Holder {
        Waystone w;
        boolean hasAux;
    }

    private final WaystonesPlugin plugin;
    /** waystone id -> player currently editing its slots (prevents item duplication). */
    private final Map<UUID, UUID> editing = new HashMap<>();

    public Menus(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    // ── who can see / manage what ────────────────────────────

    public boolean canSee(Player p, Waystone w) {
        if (!w.isPlaced()) return false;
        if (w.kind == Kind.SHARESTONE) return true;
        if (w.kind != Kind.WAYSTONE) return false;
        if (w.visibility == Visibility.GLOBAL) return true;
        PlayerData pd = plugin.store().player(p.getUniqueId());
        if (pd.activated.contains(w.id)) return true;
        if (w.visibility == Visibility.TEAM && w.owner != null) {
            if (w.owner.equals(p.getUniqueId())) return true;
            Team mine = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
            OfflinePlayer owner = Bukkit.getOfflinePlayer(w.owner);
            Team theirs = owner.getName() == null ? null
                    : Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(owner.getName());
            return mine != null && mine.equals(theirs);
        }
        return false;
    }

    public boolean canManage(Player p, Waystone w) {
        if (p.hasPermission("waystones.admin")) return true;
        if (w.visibility == Visibility.GLOBAL && w.kind == Kind.WAYSTONE) {
            return plugin.cfg().everyoneManagesGlobal || plugin.cfg().defaultVisibility == Visibility.GLOBAL;
        }
        return w.owner == null || w.owner.equals(p.getUniqueId());
    }

    // ── destination lists ────────────────────────────────────

    public List<Destination> destinations(Player p, Context ctx) {
        List<Destination> out = new ArrayList<>();
        String colour = null;
        boolean sharestones = false;
        boolean extras = true;

        switch (ctx.source()) {
            case SHARESTONE -> { sharestones = true; colour = ctx.from().variant; extras = false; }
            case PORTSTONE -> {
                if (!"plain".equals(ctx.from().variant)) { sharestones = true; colour = ctx.from().variant; }
                extras = false;
            }
            case WARP_STONE -> {
                colour = Items.warpStoneColour(ctx.itemId());
                if (colour != null) { sharestones = true; extras = false; }
            }
            case PORTAL_SCROLL -> extras = false;
            default -> { }
        }

        for (Waystone w : plugin.store().all()) {
            if (!w.isPlaced() || (ctx.from() != null && w.id.equals(ctx.from().id))) continue;
            if (sharestones) {
                if (w.kind == Kind.SHARESTONE && w.variant.equals(colour)) out.add(Destination.of(w));
            } else if (w.kind == Kind.WAYSTONE && canSee(p, w)) {
                out.add(Destination.of(w));
            }
        }

        PlayerData pd = plugin.store().player(p.getUniqueId());
        Location here = p.getLocation();
        out.sort(Comparator
                .comparing((Destination d) -> !pd.favourites.contains(d.waystone().id))
                .thenComparing(d -> !Objects.equals(d.waystone().world, here.getWorld().getName()))
                .thenComparing(d -> d.waystone().dimensionLabel())
                .thenComparing(d -> d.waystone().name.toLowerCase(Locale.ROOT)));

        if (extras) {
            List<Destination> special = new ArrayList<>();
            if (pd.memorial != null && pd.memorial.getWorld() != null) {
                special.add(new Destination(DestType.MEMORIAL, "Fleeting Memorial", null, pd.memorial, null));
            }
            Location portal = pd.returnPortal == null ? null : plugin.portals().returnLocation(pd.returnPortal);
            if (portal != null) {
                special.add(new Destination(DestType.RETURN_PORTAL, "Return to Portal", null, portal, null));
            }
            special.addAll(plugin.twinbound().partnerDestinations(p));
            out.addAll(0, special);
        }
        return out;
    }

    // ── selection menu ───────────────────────────────────────

    public void openSelection(Player p, Context ctx, Purpose purpose) {
        List<Destination> list = destinations(p, ctx);
        if (list.isEmpty()) {
            plugin.teleporter().fail(p, ctx.source() == Source.SHARESTONE || ctx.source() == Source.PORTSTONE
                    && ctx.from() != null && !"plain".equals(ctx.from().variant)
                    ? "There are no other sharestones of this material yet."
                    : "You haven't activated any other waystones yet.");
            return;
        }
        SelectHolder h = new SelectHolder();
        h.ctx = ctx;
        h.purpose = purpose;
        h.entries = list;
        h.page = 0;
        render(p, h);
    }

    /** Opens the picker with a ready-made list (used by /waystones gui). */
    public void openCustom(Player p, Context ctx, List<Destination> list) {
        if (list.isEmpty()) {
            plugin.teleporter().fail(p, "No waystones to show.");
            return;
        }
        SelectHolder h = new SelectHolder();
        h.ctx = ctx;
        h.purpose = Purpose.WARP;
        h.entries = list;
        h.page = 0;
        render(p, h);
    }

    private void render(Player p, SelectHolder h) {
        String title = h.purpose == Purpose.PORTAL ? "Open a portal to…"
                : h.ctx.from() != null ? h.ctx.from().name : "Choose a destination";
        Inventory inv = Bukkit.createInventory(h, 54, Component.text(title));
        h.inv = inv;
        PlayerData pd = plugin.store().player(p.getUniqueId());
        int start = h.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < h.entries.size(); i++) {
            inv.setItem(i, icon(p, h.ctx, h.entries.get(start + i), pd));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, filler());
        if (h.page > 0) inv.setItem(45, button(Material.ARROW, "« Previous page"));
        if (start + PAGE_SIZE < h.entries.size()) inv.setItem(53, button(Material.ARROW, "Next page »"));
        int pages = (h.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        inv.setItem(49, button(Material.COMPASS, "Page " + (h.page + 1) + " / " + pages,
                "Click a destination to warp.", "Shift-click to toggle ★ favourite."));
        p.openInventory(inv);
    }

    private ItemStack icon(Player p, Context ctx, Destination d, PlayerData pd) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        switch (d.type()) {
            case WAYSTONE -> {
                Waystone w = d.waystone();
                boolean fav = pd.favourites.contains(w.id);
                meta.setItemModel(Items.blockModel(w.kind, w.variant));
                meta.itemName(Items.plain((fav ? "★ " : "") + w.name, fav ? NamedTextColor.GOLD : NamedTextColor.WHITE));
                lore.add(Items.plain(w.dimensionLabel(), NamedTextColor.DARK_AQUA));
                Location to = w.centre();
                if (to != null && to.getWorld().equals(p.getWorld())) {
                    lore.add(Items.plain((int) p.getLocation().distance(to) + " blocks away", NamedTextColor.GRAY));
                }
                if (w.visibility == Visibility.GLOBAL && w.kind == Kind.WAYSTONE) lore.add(Items.plain("Global", NamedTextColor.AQUA));
                if (fav) meta.setEnchantmentGlintOverride(true);
            }
            case MEMORIAL -> {
                meta.setItemModel(Items.modelKey("epitaph"));
                meta.itemName(Items.plain("Fleeting Memorial", NamedTextColor.DARK_AQUA));
                lore.add(Items.plain("Where you last died. Disappears once used.", NamedTextColor.GRAY));
            }
            case RETURN_PORTAL -> {
                meta.setItemModel(Items.modelKey("portal_scroll"));
                meta.itemName(Items.plain("Return to Portal", NamedTextColor.LIGHT_PURPLE));
                lore.add(Items.plain("Back to the portal you came through.", NamedTextColor.GRAY));
            }
            case PLAYER -> {
                meta.setItemModel(Items.modelKey("twinbound_feather"));
                meta.itemName(Items.plain(d.label(), NamedTextColor.GOLD));
                lore.add(Items.plain("Twinbound Feather partner", NamedTextColor.GRAY));
            }
        }
        if (h(ctx)) {
            int cost = plugin.teleporter().xpCost(ctx, d);
            lore.add(cost > 0 ? Items.plain("Cost: " + cost + " XP", NamedTextColor.GREEN)
                    : Items.plain("Free", NamedTextColor.GREEN));
        }
        if (d.type() == DestType.WAYSTONE) lore.add(Items.plain("Shift-click: favourite", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private boolean h(Context ctx) {
        return plugin.cfg().xpCosts && (ctx.source() == Source.WAYSTONE || ctx.source() == Source.WARP_STONE);
    }

    // ── edit menu (waystones, sharestones, portstones, warp plates) ──

    public void openEditor(Player p, Waystone w) {
        if (!canManage(p, w)) {
            plugin.teleporter().fail(p, "You can't edit this " + Catalog.displayName(w.kind, w.variant).toLowerCase(Locale.ROOT) + ".");
            return;
        }
        boolean aux = w.kind == Kind.WAYSTONE || w.kind == Kind.WARP_PLATE;
        if (aux) {
            UUID other = editing.get(w.id);
            if (other != null && !other.equals(p.getUniqueId()) && Bukkit.getPlayer(other) != null) {
                plugin.teleporter().fail(p, "Someone else is editing this right now.");
                return;
            }
            editing.put(w.id, p.getUniqueId());
        }
        EditHolder h = new EditHolder();
        h.w = w;
        h.hasAux = aux;
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Edit: " + w.name));
        h.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, filler());
        inv.setItem(10, button(Material.NAME_TAG, "Rename", "Current: " + w.name));
        if (w.kind == Kind.WAYSTONE) {
            inv.setItem(11, visibilityButton(w));
        }
        if (w.kind == Kind.WARP_PLATE) {
            inv.setItem(11, button(Material.AMETHYST_SHARD, "Warp Plate",
                    "Put Attuned Shards here to link", "this plate to other plates.",
                    "Dormant Shards become Attuned", "Shards for THIS plate.",
                    "Other items act as modifiers."));
        }
        if (aux) {
            inv.setItem(4, button(Material.BOOK, "Modifier slots ↓",
                    "Blaze powder, ink sac, feather,", "magma cream, milk, diamond…"));
            for (int i = 0; i < Waystone.AUX_SLOTS; i++) {
                ItemStack it = w.slots.get(i);
                inv.setItem(AUX_START + i, it == null ? null : it.clone());
            }
        }
        p.openInventory(inv);
    }

    private ItemStack visibilityButton(Waystone w) {
        return switch (w.visibility) {
            case ACTIVATION -> button(Material.ENDER_EYE, "Visibility: Activation", "Players must activate it.", "Click to change.");
            case TEAM -> button(Material.WHITE_BANNER, "Visibility: Team", "Shared with the owner's team.", "Click to change.");
            case GLOBAL -> button(Material.BEACON, "Visibility: Global", "Available to everyone.", "Click to change.");
        };
    }

    private boolean isAux(int raw) {
        return raw >= AUX_START && raw < AUX_START + Waystone.AUX_SLOTS;
    }

    // ── events ───────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (holder instanceof SelectHolder h) {
            e.setCancelled(true);
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= 54) return;
            if (raw == 45 && h.page > 0) { h.page--; render(p, h); return; }
            if (raw == 53 && (h.page + 1) * PAGE_SIZE < h.entries.size()) { h.page++; render(p, h); return; }
            if (raw >= PAGE_SIZE) return;
            int index = h.page * PAGE_SIZE + raw;
            if (index >= h.entries.size()) return;
            Destination d = h.entries.get(index);
            if (e.isShiftClick() && d.type() == DestType.WAYSTONE) {
                PlayerData pd = plugin.store().player(p.getUniqueId());
                if (!pd.favourites.remove(d.waystone().id)) pd.favourites.add(d.waystone().id);
                plugin.store().markDirty();
                if (h.ctx.source() != Source.COMMAND) h.entries = destinations(p, h.ctx);
                render(p, h);
                return;
            }
            p.closeInventory();
            if (h.purpose == Purpose.PORTAL) plugin.portals().open(h.ctx, d);
            else plugin.teleporter().warp(h.ctx, d);
            return;
        }

        if (holder instanceof EditHolder h) {
            int raw = e.getRawSlot();
            boolean top = raw >= 0 && raw < 27;
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || (e.isShiftClick() && !top)) {
                e.setCancelled(true);
                return;
            }
            if (!top) return;
            if (h.hasAux && isAux(raw)) return; // normal item handling in modifier slots
            e.setCancelled(true);
            if (raw == 10) {
                p.closeInventory();
                plugin.listeners().promptRename(p, h.w);
            } else if (raw == 11 && h.w.kind == Kind.WAYSTONE) {
                Visibility next = switch (h.w.visibility) {
                    case ACTIVATION -> Visibility.TEAM;
                    case TEAM -> Visibility.GLOBAL;
                    case GLOBAL -> Visibility.ACTIVATION;
                };
                if (next == Visibility.GLOBAL && !p.hasPermission("waystones.admin") && p.getGameMode() != GameMode.CREATIVE) {
                    next = Visibility.ACTIVATION;
                    p.sendMessage(Component.text("Only admins or creative-mode players can make a waystone global.").color(NamedTextColor.GRAY));
                }
                h.w.visibility = next;
                plugin.store().markDirty();
                e.getInventory().setItem(11, visibilityButton(h.w));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (holder instanceof SelectHolder) { e.setCancelled(true); return; }
        if (holder instanceof EditHolder h) {
            for (int raw : e.getRawSlots()) {
                if (raw < 27 && !(h.hasAux && isAux(raw))) { e.setCancelled(true); return; }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof EditHolder h) || !h.hasAux) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        editing.remove(h.w.id);
        Inventory inv = e.getInventory();
        for (int i = 0; i < Waystone.AUX_SLOTS; i++) {
            ItemStack it = inv.getItem(AUX_START + i);
            if (h.w.kind == Kind.WARP_PLATE && plugin.items().is(it, "dormant_shard")) {
                ItemStack attuned = plugin.items().attunedShard(h.w);
                attuned.setAmount(it.getAmount());
                give(p, attuned);
                it = null;
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            }
            h.w.slots.set(i, it == null || it.isEmpty() ? null : it.clone());
        }
        plugin.store().markDirty();
    }

    // ── helpers ──────────────────────────────────────────────

    public static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Items.plain(name, NamedTextColor.YELLOW));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Items.plain(s, NamedTextColor.GRAY));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }
}
