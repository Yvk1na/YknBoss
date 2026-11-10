package cn.yknboss.loot;

import cn.yknboss.loot.RaidLootService.Tier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/** Configured vanilla sound combinations; missing keys keep old installations audible. */
final class RaidLootSounds {
    record Cue(String sound, float volume, float pitch) {
        @SuppressWarnings({"deprecation", "removal"})
        Sound type() {
            return Sound.valueOf(sound);
        }
    }

    private static final List<Cue> DEFAULT_OPEN =
            List.of(
                    new Cue("BLOCK_CHEST_OPEN", 1, .8f),
                    new Cue("BLOCK_ENDER_CHEST_OPEN", .6f, 1.2f));
    private static final Map<Tier, List<Cue>> DEFAULT_CLAIM =
            Map.of(
                    Tier.COPPER,
                            List.of(
                                    new Cue("ENTITY_ITEM_PICKUP", 1, 1),
                                    new Cue("BLOCK_NOTE_BLOCK_BELL", .8f, 1.4f)),
                    Tier.SILVER,
                            List.of(
                                    new Cue("BLOCK_AMETHYST_BLOCK_CHIME", 1, 1.2f),
                                    new Cue("ENTITY_PLAYER_LEVELUP", .7f, 1.4f)),
                    Tier.GOLD,
                            List.of(
                                    new Cue("UI_TOAST_CHALLENGE_COMPLETE", .8f, 1),
                                    new Cue("ENTITY_FIREWORK_ROCKET_TWINKLE", 1, 1)));

    final List<Cue> open;
    final Map<Tier, List<Cue>> claim = new EnumMap<>(Tier.class);

    RaidLootSounds(FileConfiguration config, Logger logger, Set<String> warned) {
        open = parse(config, "raid-loot.sounds.open", DEFAULT_OPEN, logger, warned);
        for (Tier tier : Tier.values()) {
            claim.put(
                    tier,
                    parse(
                            config,
                            "raid-loot.sounds.claim." + tier.name().toLowerCase(Locale.ROOT),
                            DEFAULT_CLAIM.get(tier),
                            logger,
                            warned));
        }
    }

    private static List<Cue> parse(
            FileConfiguration config,
            String path,
            List<Cue> defaults,
            Logger logger,
            Set<String> warned) {
        if (!config.contains(path, true)) return defaults;
        if (!(config.get(path) instanceof List<?> raw)) {
            warn(logger, warned, path, "音效列表 " + path + " 格式无效，使用默认值。");
            return defaults;
        }
        List<Cue> result = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("sound") instanceof String name)) {
                warn(logger, warned, path + ":entry", "音效列表 " + path + " 有缺少 sound 的项目，已跳过。");
                continue;
            }
            String sound = name.trim().toUpperCase(Locale.ROOT);
            try {
                // Looking up the public constant validates enum-style names without initializing
                // Paper's sound registry. Resolve the actual value only when playing on the server.
                if (Sound.class.getField(sound).getType() != Sound.class)
                    throw new NoSuchFieldException(sound);
            } catch (NoSuchFieldException exception) {
                warn(logger, warned, "name:" + sound, "无效的通关宝箱音效 " + name + "，已跳过。");
                continue;
            }
            float volume = number(map, "volume", 1), pitch = number(map, "pitch", 1);
            if (!Float.isFinite(volume) || volume < 0 || !Float.isFinite(pitch) || pitch <= 0) {
                warn(logger, warned, path + ":" + sound, "通关宝箱音效 " + name + " 的音量或音调无效，已跳过。");
                continue;
            }
            result.add(new Cue(sound, volume, pitch));
        }
        return List.copyOf(result);
    }

    private static float number(Map<?, ?> map, String key, float fallback) {
        if (!map.containsKey(key)) return fallback;
        return map.get(key) instanceof Number n ? n.floatValue() : Float.NaN;
    }

    private static void warn(Logger logger, Set<String> warned, String key, String message) {
        if (warned.add("sound:" + key)) logger.warning(message);
    }

    void open(World world, Location location) {
        for (Cue cue : open) world.playSound(location, cue.type(), cue.volume(), cue.pitch());
    }

    void claim(Player player, Tier tier) {
        for (Cue cue : claim.get(tier))
            player.playSound(player.getLocation(), cue.type(), cue.volume(), cue.pitch());
    }
}
