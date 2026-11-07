package dev.cashroy.waystones;

import dev.cashroy.waystones.Teleporter.DestType;
import dev.cashroy.waystones.Teleporter.Destination;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;

/** Twinbound Feathers: two players wave feathers at each other to link them. */
public final class Twinbound {
    private static final int NEEDED = 12;

    private final WaystonesPlugin plugin;
    private final Map<UUID, Integer> lastWave = new HashMap<>();
    private final Map<String, int[]> progress = new HashMap<>(); // pair -> {count, lastTick}

    public Twinbound(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called every time a player right-clicks (or holds right-click) with a feather. */
    public void wave(Player p) {
        int tick = Bukkit.getCurrentTick();
        lastWave.put(p.getUniqueId(), tick);
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) return;
        look.normalize();

        for (Player other : p.getWorld().getPlayers()) {
            if (other.equals(p) || other.getLocation().distanceSquared(p.getLocation()) > 9) continue;
            Integer theirs = lastWave.get(other.getUniqueId());
            if (theirs == null || tick - theirs > 8 || featherSlot(other) == null) continue;
            Vector toOther = other.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            Vector otherLook = other.getEyeLocation().getDirection().setY(0);
            if (toOther.lengthSquared() < 1e-4 || otherLook.lengthSquared() < 1e-4) continue;
            toOther.normalize();
            otherLook.normalize();
            if (look.dot(toOther) < 0.5 || otherLook.dot(toOther.clone().multiply(-1)) < 0.5) continue;

            String key = pairKey(p.getUniqueId(), other.getUniqueId());
            int[] pr = progress.computeIfAbsent(key, k -> new int[]{0, tick});
            if (tick - pr[1] > 20) pr[0] = 0; // they stopped; start over
            if (pr[1] == tick && pr[0] > 0) continue;
            pr[0]++;
            pr[1] = tick;

            Location tipA = eye.clone().add(look.clone().multiply(0.7)).subtract(0, 0.3, 0);
            Location tipB = other.getEyeLocation().add(otherLook.clone().multiply(0.7)).subtract(0, 0.3, 0);
            p.getWorld().spawnParticle(Particle.HEART, tipA, 1, 0.1, 0.1, 0.1, 0);
            p.getWorld().spawnParticle(Particle.HEART, tipB, 1, 0.1, 0.1, 0.1, 0);

            if (pr[0] >= NEEDED) {
                progress.remove(key);
                bind(p, other, false);
            }
            return;
        }
    }

    /** Links the two players' feathers. If create is true, missing feathers are created. */
    public boolean bind(Player a, Player b, boolean create) {
        int ia = featherIndex(a);
        int ib = featherIndex(b);
        if (ia < 0 && create) { Menus.give(a, plugin.items().create("twinbound_feather")); ia = featherIndex(a); }
        if (ib < 0 && create) { Menus.give(b, plugin.items().create("twinbound_feather")); ib = featherIndex(b); }
        if (ia < 0 || ib < 0) return false;
        String bond = UUID.randomUUID().toString();
        tag(a, ia, bond, b);
        tag(b, ib, bond, a);
        for (Player p : List.of(a, b)) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        }
        a.sendMessage(Component.text("Your Twinbound Feather is now bound to " + b.getName() + ".").color(NamedTextColor.GOLD));
        b.sendMessage(Component.text("Your Twinbound Feather is now bound to " + a.getName() + ".").color(NamedTextColor.GOLD));
        return true;
    }

    private void tag(Player owner, int slot, String bond, Player partner) {
        ItemStack feather = owner.getInventory().getItem(slot);
        if (feather == null) return;
        ItemMeta meta = feather.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.items().keyBond, PersistentDataType.STRING, bond);
        pdc.set(plugin.items().keyPartner, PersistentDataType.STRING, partner.getUniqueId().toString());
        pdc.set(plugin.items().keyPartnerName, PersistentDataType.STRING, partner.getName());
        meta.lore(List.of(Items.plain("Bound to " + partner.getName(), NamedTextColor.AQUA),
                Items.plain("Warp to them from any waystone", NamedTextColor.GRAY),
                Items.plain("while you both carry your feathers.", NamedTextColor.GRAY)));
        meta.setEnchantmentGlintOverride(true);
        feather.setItemMeta(meta);
        owner.getInventory().setItem(slot, feather);
    }

    private EquipmentSlot featherSlot(Player p) {
        if (plugin.items().is(p.getInventory().getItemInMainHand(), "twinbound_feather")) return EquipmentSlot.HAND;
        if (plugin.items().is(p.getInventory().getItemInOffHand(), "twinbound_feather")) return EquipmentSlot.OFF_HAND;
        return null;
    }

    /** Inventory index of the feather to bind: main hand, then off hand (40), then anywhere. */
    private int featherIndex(Player p) {
        int held = p.getInventory().getHeldItemSlot();
        if (plugin.items().is(p.getInventory().getItem(held), "twinbound_feather")) return held;
        if (plugin.items().is(p.getInventory().getItem(40), "twinbound_feather")) return 40;
        for (int i = 0; i < 36; i++) {
            if (plugin.items().is(p.getInventory().getItem(i), "twinbound_feather")) return i;
        }
        return -1;
    }

    private String bondOf(ItemStack it) {
        return it.getItemMeta().getPersistentDataContainer().get(plugin.items().keyBond, PersistentDataType.STRING);
    }

    /** Partners this player can warp to right now. */
    public List<Destination> partnerDestinations(Player p) {
        List<Destination> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ItemStack it : p.getInventory().getContents()) {
            if (!plugin.items().is(it, "twinbound_feather")) continue;
            String bond = bondOf(it);
            UUID partnerId = plugin.items().uuidTag(it, plugin.items().keyPartner);
            if (bond == null || partnerId == null || !seen.add(partnerId)) continue;
            Player partner = Bukkit.getPlayer(partnerId);
            if (partner == null) continue;
            boolean carries = false;
            for (ItemStack o : partner.getInventory().getContents()) {
                if (plugin.items().is(o, "twinbound_feather") && bond.equals(bondOf(o))) { carries = true; break; }
            }
            if (carries) out.add(new Destination(DestType.PLAYER, partner.getName(), null, null, partnerId));
        }
        return out;
    }

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }
}
