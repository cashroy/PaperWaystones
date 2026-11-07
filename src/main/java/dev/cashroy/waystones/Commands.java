package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Store.PlayerData;
import dev.cashroy.waystones.Teleporter.Context;
import dev.cashroy.waystones.Teleporter.Destination;
import dev.cashroy.waystones.Teleporter.Source;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** /waystones (admin) and /waystone (the "inventory button"). */
public final class Commands implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "activate", "forget", "place", "list", "count", "gui",
            "twinbound", "cooldown", "give", "reload");

    private final WaystonesPlugin plugin;

    public Commands(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("waystone")) return button(sender);
        if (!sender.hasPermission("waystones.admin")) {
            msg(sender, "You don't have permission.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) return help(sender);
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "activate" -> {
                    Player t = player(args, 1);
                    Waystone w = waystoneAt(sender, args, 2);
                    plugin.store().player(t.getUniqueId()).activated.add(w.id);
                    plugin.store().markDirty();
                    msg(sender, "Activated " + w.name + " for " + t.getName() + ".", NamedTextColor.GREEN);
                }
                case "forget" -> {
                    Player t = player(args, 1);
                    PlayerData pd = plugin.store().player(t.getUniqueId());
                    if (args.length > 2 && args[2].equalsIgnoreCase("all")) {
                        pd.activated.clear();
                        msg(sender, t.getName() + " forgot every waystone.", NamedTextColor.GREEN);
                    } else {
                        Waystone w = waystoneAt(sender, args, 2);
                        pd.activated.remove(w.id);
                        msg(sender, t.getName() + " forgot " + w.name + ".", NamedTextColor.GREEN);
                    }
                    plugin.store().markDirty();
                }
                case "place" -> place(sender, args);
                case "list" -> list(sender, args);
                case "count" -> {
                    OfflinePlayer t = offline(args, 1);
                    msg(sender, t.getName() + " has placed or activated " + owned(t.getUniqueId(), true).size() + " waystones.", NamedTextColor.AQUA);
                }
                case "gui" -> {
                    if (!(sender instanceof Player viewer)) throw new IllegalArgumentException("Only players can open menus.");
                    OfflinePlayer t = offline(args, 1);
                    List<Destination> list = new ArrayList<>();
                    for (Waystone w : owned(t.getUniqueId(), true)) list.add(Destination.of(w));
                    plugin.menus().openCustom(viewer, new Context(viewer, Source.COMMAND, null, null, null), list);
                }
                case "twinbound" -> {
                    Player a = player(args, 1), b = player(args, 2);
                    if (plugin.twinbound().bind(a, b, true)) msg(sender, "Bound " + a.getName() + " and " + b.getName() + ".", NamedTextColor.GREEN);
                    else msg(sender, "Couldn't create feathers (full inventory?).", NamedTextColor.RED);
                }
                case "cooldown" -> {
                    Player t = player(args, 1);
                    if (args.length < 4 || !args[2].equalsIgnoreCase("reset")) throw new IllegalArgumentException("/waystones cooldown <player> reset <all|id>");
                    PlayerData pd = plugin.store().player(t.getUniqueId());
                    if (args[3].equalsIgnoreCase("all")) pd.cooldowns.clear();
                    else pd.cooldowns.remove(args[3]);
                    plugin.store().markDirty();
                    msg(sender, "Cooldowns reset for " + t.getName() + ".", NamedTextColor.GREEN);
                }
                case "give" -> {
                    Player t = player(args, 1);
                    if (args.length < 3 || !Items.allIds().contains(args[2])) throw new IllegalArgumentException("Unknown item. Use tab-complete.");
                    ItemStack it = plugin.items().create(args[2]);
                    if (args.length > 3) it.setAmount(Math.max(1, Math.min(64, Integer.parseInt(args[3]))));
                    Menus.give(t, it);
                    msg(sender, "Gave " + Items.nameOf(args[2]) + " to " + t.getName() + ".", NamedTextColor.GREEN);
                }
                case "reload" -> {
                    plugin.reloadAll();
                    msg(sender, "Waystones config reloaded.", NamedTextColor.GREEN);
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException e) {
            msg(sender, e.getMessage(), NamedTextColor.RED);
        }
        return true;
    }

    private boolean help(CommandSender s) {
        msg(s, "Waystones commands:", NamedTextColor.GOLD);
        for (String line : List.of(
                "/waystones activate <player> <x> <y> <z>",
                "/waystones forget <player> <x> <y> <z> | all",
                "/waystones place <x> <y> <z> <type> <name>",
                "/waystones list <player> [all]",
                "/waystones count <player>",
                "/waystones gui <player>",
                "/waystones twinbound <player1> <player2>",
                "/waystones cooldown <player> reset <all|waystone|warp_stone|inventory_button|scrolls>",
                "/waystones give <player> <item> [amount]",
                "/waystones reload",
                "/waystone — quick warp (inventory button)")) {
            msg(s, line, NamedTextColor.YELLOW);
        }
        return true;
    }

    // ── /waystone (inventory button) ─────────────────────────

    private boolean button(CommandSender sender) {
        if (!(sender instanceof Player p)) return true;
        if (!p.hasPermission("waystones.button")) {
            msg(p, "You don't have permission.", NamedTextColor.RED);
            return true;
        }
        String mode = plugin.cfg().buttonMode == null ? "none" : plugin.cfg().buttonMode.trim();
        if (mode.isEmpty() || mode.equalsIgnoreCase("none")) {
            msg(p, "Quick warping is disabled on this server.", NamedTextColor.GRAY);
            return true;
        }
        long cd = plugin.teleporter().cooldownLeft(p, Source.INVENTORY_BUTTON);
        if (cd > 0) {
            plugin.teleporter().fail(p, "You can quick-warp again in " + Teleporter.formatSeconds(cd) + ".");
            return true;
        }
        Context ctx = new Context(p, Source.INVENTORY_BUTTON, null, null, null);
        if (mode.equalsIgnoreCase("any")) {
            plugin.menus().openSelection(p, ctx, Menus.Purpose.WARP);
        } else if (mode.equalsIgnoreCase("nearest")) {
            Waystone w = plugin.listeners().nearestActivated(p);
            if (w == null) plugin.teleporter().fail(p, "You haven't activated any waystones yet.");
            else plugin.teleporter().warp(ctx, Destination.of(w));
        } else {
            Waystone w = plugin.store().findByName(mode);
            if (w == null) plugin.teleporter().fail(p, "The waystone \"" + mode + "\" doesn't exist.");
            else plugin.teleporter().warp(ctx, Destination.of(w));
        }
        return true;
    }

    // ── helpers ──────────────────────────────────────────────

    private void place(CommandSender sender, String[] args) {
        if (args.length < 6) throw new IllegalArgumentException("/waystones place <x> <y> <z> <type> <name>");
        World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        Location base = sender instanceof Player p ? p.getLocation() : world.getSpawnLocation();
        int x = coord(args[1], base.getBlockX()), y = coord(args[2], base.getBlockY()), z = coord(args[3], base.getBlockZ());
        String type = args[4];
        if (!Items.isBlock(type) || !Items.allIds().contains(type)) {
            throw new IllegalArgumentException("Type must be one of: waystone/<style>, sharestone/<colour>, portstone/<plain|colour>, warp_plate");
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
        if (plugin.store().at(world.getName(), x, y, z) != null) throw new IllegalArgumentException("There's already one there.");
        Waystone w = new Waystone(UUID.randomUUID());
        w.kind = Items.kindOf(type);
        w.variant = Items.variantOf(type);
        w.name = name;
        w.world = world.getName();
        w.x = x;
        w.y = y;
        w.z = z;
        w.facing = 0;
        w.visibility = w.kind == Kind.SHARESTONE ? Catalog.Visibility.GLOBAL : plugin.cfg().defaultVisibility;
        world.getBlockAt(x, y, z).setType(Material.AIR, false);
        if (Catalog.isTall(w.kind)) world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        plugin.store().add(w);
        plugin.structures().spawn(w);
        msg(sender, "Placed " + Catalog.displayName(w.kind, w.variant) + " \"" + name + "\".", NamedTextColor.GREEN);
    }

    private void list(CommandSender sender, String[] args) {
        OfflinePlayer t = offline(args, 1);
        boolean all = args.length > 2 && args[2].equalsIgnoreCase("all");
        List<Waystone> list = owned(t.getUniqueId(), all);
        msg(sender, (all ? "Placed or activated by " : "Placed by ") + t.getName() + " (" + list.size() + "):", NamedTextColor.GOLD);
        for (Waystone w : list) {
            msg(sender, " • " + w.name + " — " + w.dimensionLabel() + " " + w.x + ", " + w.y + ", " + w.z, NamedTextColor.GRAY);
        }
    }

    private List<Waystone> owned(UUID player, boolean includeActivated) {
        PlayerData pd = plugin.store().player(player);
        List<Waystone> out = new ArrayList<>();
        for (Waystone w : plugin.store().all()) {
            if (!w.isPlaced() || w.kind != Kind.WAYSTONE) continue;
            if (player.equals(w.owner) || (includeActivated && pd.activated.contains(w.id))) out.add(w);
        }
        return out;
    }

    private Waystone waystoneAt(CommandSender sender, String[] args, int i) {
        if (args.length < i + 3) throw new IllegalArgumentException("Missing coordinates.");
        World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        Location base = sender instanceof Player p ? p.getLocation() : world.getSpawnLocation();
        Waystone w = plugin.store().at(world.getName(), coord(args[i], base.getBlockX()),
                coord(args[i + 1], base.getBlockY()), coord(args[i + 2], base.getBlockZ()));
        if (w == null) throw new IllegalArgumentException("There's no waystone at those coordinates.");
        return w;
    }

    private static int coord(String s, int base) {
        try {
            if (s.startsWith("~")) return base + (s.length() > 1 ? Integer.parseInt(s.substring(1)) : 0);
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad coordinate: " + s);
        }
    }

    private static Player player(String[] args, int i) {
        if (args.length <= i) throw new IllegalArgumentException("Missing player name.");
        Player p = Bukkit.getPlayerExact(args[i]);
        if (p == null) throw new IllegalArgumentException("Player " + args[i] + " isn't online.");
        return p;
    }

    private static OfflinePlayer offline(String[] args, int i) {
        if (args.length <= i) throw new IllegalArgumentException("Missing player name.");
        Player online = Bukkit.getPlayerExact(args[i]);
        if (online != null) return online;
        OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(args[i]);
        if (p == null) throw new IllegalArgumentException("Unknown player " + args[i] + ".");
        return p;
    }

    private static void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text).color(c));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("waystone") || !sender.hasPermission("waystones.admin")) return List.of();
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 2 && !sub.equals("place") && !sub.equals("reload")) return filter(players, args[1]);
        if (sub.equals("twinbound") && args.length == 3) return filter(players, args[2]);
        if (sub.equals("give") && args.length == 3) return filter(Items.allIds(), args[2]);
        if (sub.equals("place") && args.length == 5) {
            return filter(Items.allIds().stream().filter(Items::isBlock).toList(), args[4]);
        }
        if (sub.equals("list") && args.length == 3) return filter(List.of("all"), args[2]);
        if (sub.equals("forget") && args.length == 3) return filter(List.of("all", "~"), args[2]);
        if (sub.equals("cooldown") && args.length == 3) return filter(List.of("reset"), args[2]);
        if (sub.equals("cooldown") && args.length == 4) {
            return filter(List.of("all", "waystone", "warp_stone", "inventory_button", "scrolls"), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
