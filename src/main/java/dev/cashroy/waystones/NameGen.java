package dev.cashroy.waystones;

import org.bukkit.block.Biome;

import java.util.*;

/** Generates fantasy-ish place names for found waystones. */
public final class NameGen {
    private static final String[] START = {"Al", "Bel", "Cor", "Dun", "El", "Fen", "Gal", "Hal", "Ith", "Kel", "Lor", "Mor",
            "Nor", "Os", "Pell", "Quen", "Ros", "Sil", "Thal", "Ul", "Vel", "Wen", "Yar", "Zan", "Ash", "Bram", "Cael", "Drav"};
    private static final String[] MIDDLE = {"a", "e", "i", "o", "u", "ae", "ia", "or", "en", "ar", "il", "ys", ""};
    private static final String[] END = {"dor", "mar", "wyn", "ford", "holm", "reach", "vale", "moor", "crest", "fall",
            "gate", "haven", "mere", "spire", "wick", "ridge", "stead", "thorn", "watch", "brook"};

    private final WaystonesPlugin plugin;
    private final Random random = new Random();
    private int presetIndex;

    public NameGen(WaystonesPlugin plugin) {
        this.plugin = plugin;
    }

    public String generate(Biome biome) {
        Settings s = plugin.cfg();
        List<String> presets = s.namePresets == null ? List.of() : s.namePresets;
        Set<String> used = new HashSet<>();
        for (Waystone w : plugin.store().all()) used.add(w.name.toLowerCase(Locale.ROOT));

        switch (s.nameMode) {
            case "PRESET_ONLY", "PRESET_FIRST" -> {
                for (int i = 0; i < presets.size(); i++) {
                    String candidate = presets.get((presetIndex + i) % presets.size());
                    if (!used.contains(candidate.toLowerCase(Locale.ROOT))) {
                        presetIndex = (presetIndex + i + 1) % presets.size();
                        return candidate;
                    }
                }
                if (s.nameMode.equals("PRESET_ONLY") && !presets.isEmpty()) {
                    return presets.get(random.nextInt(presets.size()));
                }
            }
            case "MIXED" -> {
                if (!presets.isEmpty() && random.nextBoolean()) return presets.get(random.nextInt(presets.size()));
            }
            default -> { }
        }

        for (int tries = 0; tries < 20; tries++) {
            String name = s.nameTemplate
                    .replace("{Random}", random())
                    .replace("{MrPork}", random())
                    .replace("{Biome}", biomeName(biome));
            if (!used.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        return random() + " " + (random.nextInt(90) + 10);
    }

    private String random() {
        String n = START[random.nextInt(START.length)] + MIDDLE[random.nextInt(MIDDLE.length)] + END[random.nextInt(END.length)];
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String biomeName(Biome biome) {
        if (biome == null) return "Wilds";
        String key = biome.getKey().getKey();
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
