package dev.cashroy.waystones;

import dev.cashroy.waystones.Catalog.Colour;
import dev.cashroy.waystones.Catalog.Kind;
import dev.cashroy.waystones.Catalog.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Creates and recognises every custom item, and registers crafting recipes. */
public final class Items {

    public static final String NS = "waystones";

    private final WaystonesPlugin plugin;
    public final NamespacedKey keyItem;
    public final NamespacedKey keyTarget;
    public final NamespacedKey keyStored;
    public final NamespacedKey keyBond;
    public final NamespacedKey keyPartner;
    public final NamespacedKey keyPartnerName;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public Items(WaystonesPlugin plugin) {
        this.plugin = plugin;
        keyItem = new NamespacedKey(plugin, "item");
        keyTarget = new NamespacedKey(plugin, "target");
        keyStored = new NamespacedKey(plugin, "stored");
        keyBond = new NamespacedKey(plugin, "bond");
        keyPartner = new NamespacedKey(plugin, "partner");
        keyPartnerName = new NamespacedKey(plugin, "partner_name");
    }

    /** Every item id the plugin can create (used by /waystones give). */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>();
        for (String s : Catalog.STYLES.keySet()) ids.add("waystone/" + s);
        for (String c : Catalog.COLOURS.keySet()) ids.add("sharestone/" + c);
        ids.add("portstone/plain");
        for (String c : Catalog.COLOURS.keySet()) ids.add("portstone/" + c);
        ids.add("warp_plate");
        ids.add("warp_stone");
        for (String c : Catalog.COLOURS.keySet()) ids.add("warp_stone/" + c);
        ids.addAll(List.of("return_scroll", "warp_scroll", "unbound_scroll", "portal_scroll",
                "twinbound_feather", "dormant_shard", "epitaph"));
        return ids;
    }

    // ── creation ─────────────────────────────────────────────

    public ItemStack create(String id) {
        if (id.startsWith("warp_stone")) return warpStone(id);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, id);
        meta.setItemModel(modelKey(id));
        meta.itemName(plain(nameOf(id), colourOf(id)));
        List<Component> lore = new ArrayList<>();
        for (String line : loreOf(id)) lore.add(plain(line, NamedTextColor.GRAY));
        meta.lore(lore);
        if (id.equals("twinbound_feather")) meta.setMaxStackSize(1);
        if (id.equals("bound_scroll") || id.equals("attuned_shard")) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack warpStone(String id) {
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, id);
        meta.setItemModel(modelKey(id));
        meta.itemName(plain(nameOf(id), colourOf(id)));
        List<Component> lore = new ArrayList<>();
        for (String line : loreOf(id)) lore.add(plain(line, NamedTextColor.GRAY));
        meta.lore(lore);
        if (meta instanceof Damageable d) {
            d.setMaxDamage(Math.max(1, plugin.cfg().warpStoneMaxDurability));
            d.setDamage(0);
        }
        meta.setEnchantmentGlintOverride(false);
        item.setItemMeta(meta);
        return item;
    }

    /** A placeable item that remembers an existing waystone (silk touch). */
    public ItemStack createStored(Waystone w) {
        ItemStack item = create(Catalog.blockItemId(w.kind, w.variant));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyStored, PersistentDataType.STRING, w.id.toString());
        meta.setMaxStackSize(1);
        List<Component> lore = new ArrayList<>(Objects.requireNonNullElse(meta.lore(), List.of()));
        lore.add(0, plain("\"" + w.name + "\"", NamedTextColor.AQUA));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack boundScroll(Waystone target) {
        ItemStack item = create("bound_scroll");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyTarget, PersistentDataType.STRING, target.id.toString());
        meta.itemName(plain("Bound Scroll", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(plain("Bound to: " + target.name, NamedTextColor.AQUA),
                plain("Right-click to warp there.", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack attunedShard(Waystone plate) {
        ItemStack item = create("attuned_shard");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyTarget, PersistentDataType.STRING, plate.id.toString());
        meta.lore(List.of(plain("Attuned to: " + plate.name, NamedTextColor.AQUA),
                plain("Put it in another warp plate to link it here.", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    // ── recognition ──────────────────────────────────────────

    public String idOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyItem, PersistentDataType.STRING);
    }

    public boolean is(ItemStack item, String id) {
        return id.equals(idOf(item));
    }

    public UUID uuidTag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isBlock(String id) {
        return id != null && (id.startsWith("waystone/") || id.startsWith("sharestone/")
                || id.startsWith("portstone/") || id.equals("warp_plate"));
    }

    public static Kind kindOf(String blockId) {
        if (blockId.startsWith("waystone/")) return Kind.WAYSTONE;
        if (blockId.startsWith("sharestone/")) return Kind.SHARESTONE;
        if (blockId.startsWith("portstone/")) return Kind.PORTSTONE;
        return Kind.WARP_PLATE;
    }

    public static String variantOf(String blockId) {
        int i = blockId.indexOf('/');
        return i < 0 ? "default" : blockId.substring(i + 1);
    }

    /** Colour scope of a scoped warp stone, or null for a plain one. */
    public static String warpStoneColour(String id) {
        return id != null && id.startsWith("warp_stone/") ? id.substring("warp_stone/".length()) : null;
    }

    // ── names and models ─────────────────────────────────────

    public static NamespacedKey modelKey(String id) {
        return new NamespacedKey(NS, id.replace('/', '_'));
    }

    /** Item model used to show a waystone of this kind/variant (in menus and in the world). */
    public static NamespacedKey blockModel(Kind kind, String variant) {
        return modelKey(Catalog.blockItemId(kind, variant));
    }

    public static String nameOf(String id) {
        if (isBlock(id)) return Catalog.displayName(kindOf(id), variantOf(id));
        String c = warpStoneColour(id);
        if (c != null && Catalog.COLOURS.containsKey(c)) return Catalog.COLOURS.get(c).display() + " Warp Stone";
        return switch (id) {
            case "warp_stone" -> "Warp Stone";
            case "return_scroll" -> "Return Scroll";
            case "warp_scroll" -> "Warp Scroll";
            case "unbound_scroll" -> "Unbound Scroll";
            case "bound_scroll" -> "Bound Scroll";
            case "portal_scroll" -> "Portal Scroll";
            case "twinbound_feather" -> "Twinbound Feather";
            case "dormant_shard" -> "Dormant Shard";
            case "attuned_shard" -> "Attuned Shard";
            case "epitaph" -> "Epitaph";
            default -> id;
        };
    }

    private static TextColor colourOf(String id) {
        if (id.startsWith("sharestone/") || id.startsWith("portstone/") || id.startsWith("warp_stone/")) {
            Colour c = Catalog.COLOURS.get(variantOf(id));
            if (c != null) return c.text();
        }
        return switch (id) {
            case "warp_stone", "warp_scroll", "portal_scroll", "attuned_shard" -> NamedTextColor.LIGHT_PURPLE;
            case "epitaph" -> NamedTextColor.DARK_AQUA;
            case "twinbound_feather" -> NamedTextColor.GOLD;
            default -> NamedTextColor.WHITE;
        };
    }

    private static List<String> loreOf(String id) {
        if (id.startsWith("waystone/")) return List.of("Right-click to activate.", "Right-click again to travel.", "Shift-right-click to edit.");
        if (id.startsWith("sharestone/")) return List.of("Links to every sharestone", "of the same material.");
        if (id.equals("portstone/plain")) return List.of("One-way: warp to any of", "your activated waystones.");
        if (id.startsWith("portstone/")) return List.of("One-way: warp to sharestones", "of the same material.");
        if (id.startsWith("warp_stone/")) return List.of("Right-click to warp to a", "sharestone of this material.");
        return switch (id) {
            case "warp_plate" -> List.of("Stand on it to warp to", "a linked warp plate.");
            case "warp_stone" -> List.of("Right-click to warp to any", "of your activated waystones.");
            case "return_scroll" -> List.of("Returns you to the nearest", "activated waystone. Single use.");
            case "warp_scroll" -> List.of("Warp to any activated", "waystone. Single use.");
            case "unbound_scroll" -> List.of("Use on a waystone, sharestone", "or warp plate to bind it.");
            case "portal_scroll" -> List.of("Opens a temporary portal", "to a waystone of your choice.");
            case "twinbound_feather" -> List.of("Hold right-click facing another", "feather holder to bind them.");
            case "dormant_shard" -> List.of("Put it in a warp plate", "to attune it.");
            case "attuned_shard" -> List.of("Links warp plates.");
            case "epitaph" -> List.of("Keep it in your inventory.", "When you die, it leaves a", "Fleeting Memorial to warp back to.");
            default -> List.of();
        };
    }

    public static Component plain(String text, TextColor colour) {
        return Component.text(text).color(colour).decoration(TextDecoration.ITALIC, false);
    }

    // ── recipes ──────────────────────────────────────────────

    public void registerRecipes() {
        // Any plain warp stone counts, even a worn one (on servers that support predicate choices).
        RecipeChoice warp = choice(it -> "warp_stone".equals(idOf(it)), create("warp_stone"));

        for (Style s : Catalog.STYLES.values()) {
            ShapedRecipe r = shaped("waystone/" + s.id(), 1, " B ", "BWB", "BBB");
            r.setIngredient('B', s.craftMaterial());
            r.setIngredient('W', warp);
            add(r);
        }
        // Mossy from a plain waystone + vines or moss
        RecipeChoice plainStone = choice(
                it -> "waystone/stone".equals(idOf(it)) && uuidTag(it, keyStored) == null, create("waystone/stone"));
        ShapelessRecipe mossyVine = shapeless("waystone/mossy", "mossy_vine");
        mossyVine.addIngredient(plainStone);
        mossyVine.addIngredient(3, Material.VINE);
        add(mossyVine);
        ShapelessRecipe mossyMoss = shapeless("waystone/mossy", "mossy_moss");
        mossyMoss.addIngredient(plainStone);
        mossyMoss.addIngredient(3, Material.MOSS_BLOCK);
        add(mossyMoss);

        for (Colour c : Catalog.COLOURS.values()) {
            ShapedRecipe share = shaped("sharestone/" + c.id(), 1, "BCB", "BWB", "BCB");
            share.setIngredient('B', Material.STONE_BRICKS);
            share.setIngredient('C', c.craftMaterial());
            share.setIngredient('W', warp);
            add(share);

            ShapedRecipe port = shaped("portstone/" + c.id(), 1, "CLB", "LWB", "CLB");
            port.setIngredient('C', c.craftMaterial());
            port.setIngredient('L', Material.STONE_BRICK_SLAB);
            port.setIngredient('B', Material.STONE_BRICKS);
            port.setIngredient('W', warp);
            add(port);

            ShapelessRecipe scoped = shapeless("warp_stone/" + c.id(), "warp_stone_" + c.id());
            scoped.addIngredient(warp);
            scoped.addIngredient(4, c.craftMaterial());
            add(scoped);
        }

        ShapedRecipe port = shaped("portstone/plain", 1, "LBL", " W ", "BLB");
        port.setIngredient('L', Material.STONE_BRICK_SLAB);
        port.setIngredient('B', Material.STONE_BRICKS);
        port.setIngredient('W', warp);
        add(port);

        ShapedRecipe plate = shaped("warp_plate", 1, "BAB", "AFA", "BAB");
        plate.setIngredient('B', Material.STONE_BRICKS);
        plate.setIngredient('A', Material.AMETHYST_SHARD);
        plate.setIngredient('F', Material.FLINT);
        add(plate);

        ShapedRecipe stone = shaped("warp_stone", 1, "APA", "PGP", "APA");
        stone.setIngredient('A', Material.AMETHYST_SHARD);
        stone.setIngredient('P', Material.ENDER_PEARL);
        stone.setIngredient('G', Material.GOLD_INGOT);
        add(stone);

        ShapedRecipe ret = shaped("return_scroll", 3, "G G", "PIP", " P ");
        ret.setIngredient('G', Material.GOLD_NUGGET);
        ret.setIngredient('P', Material.PAPER);
        ret.setIngredient('I', Material.INK_SAC);
        add(ret);

        ShapedRecipe warpScroll = shaped("warp_scroll", 3, "GPG", "PIP", "GAG");
        warpScroll.setIngredient('G', Material.GOLD_NUGGET);
        warpScroll.setIngredient('P', Material.PAPER);
        warpScroll.setIngredient('I', Material.INK_SAC);
        warpScroll.setIngredient('A', Material.AMETHYST_SHARD);
        add(warpScroll);

        ShapedRecipe unbound = shaped("unbound_scroll", 3, "G G", "PFP", " P ");
        unbound.setIngredient('G', Material.GOLD_NUGGET);
        unbound.setIngredient('P', Material.PAPER);
        unbound.setIngredient('F', Material.FEATHER);
        add(unbound);

        ShapedRecipe portal = shaped("portal_scroll", 3, "AIA", "PEP", "APA");
        portal.setIngredient('A', Material.AMETHYST_SHARD);
        portal.setIngredient('I', Material.INK_SAC);
        portal.setIngredient('P', Material.PAPER);
        portal.setIngredient('E', Material.ENDER_PEARL);
        add(portal);

        ShapelessRecipe feather = shapeless("twinbound_feather", "twinbound_feather");
        feather.addIngredient(Material.FEATHER);
        feather.addIngredient(Material.INK_SAC);
        feather.addIngredient(Material.AMETHYST_SHARD);
        feather.addIngredient(Material.GOLD_NUGGET);
        add(feather);

        ShapedRecipe dormant = shaped("dormant_shard", 1, " A ", "AFA", " A ");
        dormant.setIngredient('A', Material.AMETHYST_SHARD);
        dormant.setIngredient('F', Material.FLINT);
        add(dormant);

        ShapedRecipe epitaph = shaped("epitaph", 1, "NAN", "NDN", "NAN");
        epitaph.setIngredient('N', Material.GOLD_NUGGET);
        epitaph.setIngredient('A', Material.AMETHYST_SHARD);
        epitaph.setIngredient('D', Material.DEEPSLATE);
        add(epitaph);
    }

    /**
     * RecipeChoice.predicateChoice only exists on Paper 26.2+. Use it there (so worn warp stones still
     * work as ingredients); on older servers fall back to an exact match of a fresh item.
     */
    @SuppressWarnings("removal")
    private static RecipeChoice choice(java.util.function.Predicate<ItemStack> test, ItemStack example) {
        try {
            java.lang.reflect.Method m = RecipeChoice.class.getMethod("predicateChoice",
                    java.util.function.Predicate.class, ItemStack.class);
            return (RecipeChoice) m.invoke(null, test, example);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new RecipeChoice.ExactChoice(example);
        }
    }

    private ShapedRecipe shaped(String resultId, int amount, String... rows) {
        ItemStack result = create(resultId);
        result.setAmount(amount);
        NamespacedKey key = new NamespacedKey(plugin, resultId.replace('/', '_'));
        ShapedRecipe r = new ShapedRecipe(key, result);
        r.shape(rows);
        return r;
    }

    private ShapelessRecipe shapeless(String resultId, String keyName) {
        return new ShapelessRecipe(new NamespacedKey(plugin, keyName), create(resultId));
    }

    private void add(org.bukkit.inventory.Recipe r) {
        if (r instanceof org.bukkit.Keyed k) {
            Bukkit.removeRecipe(k.getKey());
            recipeKeys.add(k.getKey());
        }
        Bukkit.addRecipe(r);
    }

    public List<NamespacedKey> recipeKeys() {
        return recipeKeys;
    }

    public void unregisterRecipes() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
    }

    /** True if any of these ingredients is one of our items (so vanilla recipes can't eat them). */
    public boolean containsCustom(ItemStack[] matrix) {
        for (ItemStack it : matrix) if (idOf(it) != null) return true;
        return false;
    }

    public PersistentDataContainer pdc(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer();
    }
}
