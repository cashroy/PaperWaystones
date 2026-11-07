package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Visibility;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;

/** Typed snapshot of config.yml. */
public final class Settings {
    public enum TransportMobs { ENABLED, SAME_DIMENSION, DISABLED }
    public enum Village { DISABLED, REGULAR, FREQUENT }

    public Visibility defaultVisibility;
    public boolean everyoneManagesGlobal;
    public boolean ownerOnlyBreak;

    public boolean xpCosts;
    public double xpPerBlock;
    public int minXp, maxXp, interdimensionalXp;

    public boolean durability;
    public int warpStoneMaxDurability;
    public int warpStoneDamage;

    public boolean cooldowns;
    public int cdWaystone, cdWarpStone, cdButton, cdScrolls;

    public int chargeTicks;
    public TransportMobs pets, leashed;
    public final Set<EntityType> denyList = EnumSet.noneOf(EntityType.class);
    public boolean modifiers;
    public int plateDelay;
    public int portalMinutes;

    public String buttonMode;

    public int wildSpacing;
    public String wildStyle;
    public final Set<String> wildWorlds = new HashSet<>();
    public Village villages;
    public String nameMode;
    public String nameTemplate;
    public List<String> namePresets;

    public boolean packEnabled;
    public int packPort;
    public String packHost;
    public String packExternalUrl;
    public boolean packRequired;
    public String packPrompt;

    public static Settings load(FileConfiguration c) {
        Settings s = new Settings();
        s.defaultVisibility = enumOr(Visibility.class, c.getString("rules.default-visibility"), Visibility.ACTIVATION);
        s.everyoneManagesGlobal = c.getBoolean("rules.allow-everyone-to-manage-global", false);
        s.ownerOnlyBreak = c.getBoolean("rules.owner-only-break", true);

        s.xpCosts = c.getBoolean("costs.enable-xp-costs", true);
        s.xpPerBlock = c.getDouble("costs.xp-per-block", 0.01);
        s.minXp = c.getInt("costs.min-xp-cost", 0);
        s.maxXp = c.getInt("costs.max-xp-cost", 27);
        s.interdimensionalXp = c.getInt("costs.interdimensional-xp-cost", 27);

        s.durability = c.getBoolean("durability.enable", true);
        s.warpStoneMaxDurability = c.getInt("durability.warp-stone-max-durability", 400);
        s.warpStoneDamage = c.getInt("durability.warp-stone-damage-per-use", 80);

        s.cooldowns = c.getBoolean("cooldowns.enable", true);
        s.cdWaystone = c.getInt("cooldowns.waystone", 0);
        s.cdWarpStone = c.getInt("cooldowns.warp-stone", 0);
        s.cdButton = c.getInt("cooldowns.inventory-button", 300);
        s.cdScrolls = c.getInt("cooldowns.scrolls", 0);

        s.chargeTicks = Math.max(0, c.getInt("warp.item-charge-ticks", 32));
        s.pets = enumOr(TransportMobs.class, c.getString("warp.transport-pets"), TransportMobs.DISABLED);
        s.leashed = enumOr(TransportMobs.class, c.getString("warp.transport-leashed"), TransportMobs.ENABLED);
        for (String e : c.getStringList("warp.entity-deny-list")) {
            EntityType t = enumOr(EntityType.class, e.replace("minecraft:", ""), null);
            if (t != null) s.denyList.add(t);
        }
        s.modifiers = c.getBoolean("warp.enable-modifiers", true);
        s.plateDelay = Math.max(1, c.getInt("warp.warp-plate-delay-ticks", 40));
        s.portalMinutes = Math.max(1, c.getInt("warp.portal-lifetime-minutes", 5));

        s.buttonMode = c.getString("inventory-button.mode", "any");

        s.wildSpacing = c.getInt("world-gen.chunks-between-wild-waystones", 25);
        s.wildStyle = c.getString("world-gen.wild-waystone-style", "BIOME").toUpperCase(Locale.ROOT);
        s.wildWorlds.addAll(c.getStringList("world-gen.wild-waystone-worlds"));
        s.villages = enumOr(Village.class, c.getString("world-gen.spawn-in-villages"), Village.REGULAR);
        s.nameMode = c.getString("world-gen.name-generation-mode", "PRESET_FIRST").toUpperCase(Locale.ROOT);
        s.nameTemplate = c.getString("world-gen.name-generation-template", "{Random}");
        s.namePresets = c.getStringList("world-gen.name-generation-presets");

        s.packEnabled = c.getBoolean("resource-pack.enabled", true);
        s.packPort = c.getInt("resource-pack.port", 8164);
        s.packHost = c.getString("resource-pack.public-host", "");
        s.packExternalUrl = c.getString("resource-pack.external-url", "");
        s.packRequired = c.getBoolean("resource-pack.required", true);
        s.packPrompt = c.getString("resource-pack.prompt", "");
        return s;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
