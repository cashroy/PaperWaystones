package dev.cashroy.waystones;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/** Static data: every block kind, variant and material colour the plugin knows about. */
public final class Catalog {
    private Catalog() {}

    public enum Kind { WAYSTONE, SHARESTONE, PORTSTONE, WARP_PLATE }

    public enum Visibility { ACTIVATION, TEAM, GLOBAL }

    /** A waystone style: id, display name and the brick used to craft it. */
    public record Style(String id, String display, Material craftMaterial) {}

    /** A sharestone/portstone/warp stone network colour. */
    public record Colour(String id, String display, Material craftMaterial, TextColor text) {}

    public static final Map<String, Style> STYLES = new LinkedHashMap<>();
    public static final Map<String, Colour> COLOURS = new LinkedHashMap<>();

    static {
        style("stone", "Waystone", Material.STONE_BRICKS);
        style("sandy", "Sandy Waystone", Material.CHISELED_SANDSTONE);
        style("mossy", "Mossy Waystone", Material.MOSSY_STONE_BRICKS);
        style("deepslate", "Deepslate Waystone", Material.DEEPSLATE);
        style("end_stone", "Ender Waystone", Material.END_STONE_BRICKS);
        style("blackstone", "Black Waystone", Material.BLACKSTONE);
        style("nether", "Nether Waystone", Material.RED_NETHER_BRICKS);
        style("purpur", "Purpur Waystone", Material.PURPUR_BLOCK);
        style("prismarine", "Prismarine Waystone", Material.PRISMARINE);
        style("muddy", "Muddy Waystone", Material.MUD_BRICKS);

        colour("amethyst", "Amethyst", Material.AMETHYST_SHARD, TextColor.color(0xB48CF0));
        colour("copper", "Copper", Material.COPPER_INGOT, TextColor.color(0xE07A4F));
        colour("diamond", "Diamond", Material.DIAMOND, TextColor.color(0x5FE3DA));
        colour("emerald", "Emerald", Material.EMERALD, TextColor.color(0x41D46A));
        colour("gold", "Gold", Material.GOLD_INGOT, TextColor.color(0xF5C842));
        colour("lapis", "Lapis", Material.LAPIS_LAZULI, TextColor.color(0x3D6BE0));
        colour("prismarine", "Prismarine", Material.PRISMARINE_SHARD, TextColor.color(0x6FC2B0));
        colour("redstone", "Redstone", Material.REDSTONE, NamedTextColor.RED);
    }

    private static void style(String id, String display, Material m) {
        STYLES.put(id, new Style(id, display, m));
    }

    private static void colour(String id, String display, Material m, TextColor c) {
        COLOURS.put(id, new Colour(id, display, m, c));
    }

    /** Item id used for the placeable item of a block kind + variant. */
    public static String blockItemId(Kind kind, String variant) {
        return switch (kind) {
            case WAYSTONE -> "waystone/" + variant;
            case SHARESTONE -> "sharestone/" + variant;
            case PORTSTONE -> "portstone/" + variant;
            case WARP_PLATE -> "warp_plate";
        };
    }

    /** True if this block kind is two blocks tall. */
    public static boolean isTall(Kind kind) {
        return kind != Kind.WARP_PLATE;
    }

    public static String displayName(Kind kind, String variant) {
        return switch (kind) {
            case WAYSTONE -> STYLES.containsKey(variant) ? STYLES.get(variant).display() : "Waystone";
            case SHARESTONE -> COLOURS.containsKey(variant) ? COLOURS.get(variant).display() + " Sharestone" : "Sharestone";
            case PORTSTONE -> "plain".equals(variant) || !COLOURS.containsKey(variant)
                    ? "Portstone" : COLOURS.get(variant).display() + " Portstone";
            case WARP_PLATE -> "Warp Plate";
        };
    }
}
