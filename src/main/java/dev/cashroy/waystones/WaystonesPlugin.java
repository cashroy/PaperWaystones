package dev.cashroy.waystones;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WaystonesPlugin extends JavaPlugin {

    private Settings settings;
    private Store store;
    private Items items;
    private Structures structures;
    private Teleporter teleporter;
    private Menus menus;
    private Portals portals;
    private Plates plates;
    private Twinbound twinbound;
    private NameGen nameGen;
    private PackServer pack;
    private Listeners listeners;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());

        store = new Store(this);
        store.load();
        items = new Items(this);
        structures = new Structures(this);
        teleporter = new Teleporter(this);
        menus = new Menus(this);
        portals = new Portals(this);
        plates = new Plates(this);
        twinbound = new Twinbound(this);
        nameGen = new NameGen(this);
        pack = new PackServer(this);
        listeners = new Listeners(this);

        items.registerRecipes();
        pack.start();

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(listeners, this);
        pm.registerEvents(menus, this);
        pm.registerEvents(new WorldGen(this), this);

        Commands commands = new Commands(this);
        for (String name : new String[]{"waystones", "waystone"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(commands);
                c.setTabCompleter(commands);
            }
        }

        Bukkit.getScheduler().runTaskTimer(this, plates::tick, 20L, Plates.PERIOD);
        Bukkit.getScheduler().runTaskTimer(this, portals::tick, 20L, 4L);
        Bukkit.getScheduler().runTaskTimer(this, store::saveIfDirty, 20L * 60, 20L * 60);

        // Send the pack to anyone already online (e.g. after /reload).
        for (Player p : Bukkit.getOnlinePlayers()) pack.send(p);
        getLogger().info("Loaded " + store.all().size() + " waystones.");
    }

    @Override
    public void onDisable() {
        if (portals != null) portals.closeAll();
        if (pack != null) pack.stop();
        if (items != null) items.unregisterRecipes();
        if (store != null) store.save();
    }

    public void reloadAll() {
        reloadConfig();
        settings = Settings.load(getConfig());
        pack.stop();
        pack.start();
    }

    public Settings cfg() { return settings; }
    public Store store() { return store; }
    public Items items() { return items; }
    public Structures structures() { return structures; }
    public Teleporter teleporter() { return teleporter; }
    public Menus menus() { return menus; }
    public Portals portals() { return portals; }
    public Twinbound twinbound() { return twinbound; }
    public NameGen nameGen() { return nameGen; }
    public PackServer pack() { return pack; }
    public Listeners listeners() { return listeners; }
}
