package cn.yknboss.loot;

import cn.yknboss.loot.RaidLootService.Tier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidLootSoundsTest {
    @Test
    void missingKeysUseExactDefaultsAndShippedConfigAgrees() throws Exception {
        var logger = mock(Logger.class);
        var defaults = new RaidLootSounds(new YamlConfiguration(), logger, new HashSet<>());
        assertEquals(
                List.of(
                        new RaidLootSounds.Cue("BLOCK_CHEST_OPEN", 1, .8f),
                        new RaidLootSounds.Cue("BLOCK_ENDER_CHEST_OPEN", .6f, 1.2f)),
                defaults.open);
        assertEquals(
                List.of(
                        new RaidLootSounds.Cue("ENTITY_ITEM_PICKUP", 1, 1),
                        new RaidLootSounds.Cue("BLOCK_NOTE_BLOCK_BELL", .8f, 1.4f)),
                defaults.claim.get(Tier.COPPER));
        assertEquals(
                List.of(
                        new RaidLootSounds.Cue("BLOCK_AMETHYST_BLOCK_CHIME", 1, 1.2f),
                        new RaidLootSounds.Cue("ENTITY_PLAYER_LEVELUP", .7f, 1.4f)),
                defaults.claim.get(Tier.SILVER));
        assertEquals(
                List.of(
                        new RaidLootSounds.Cue("UI_TOAST_CHALLENGE_COMPLETE", .8f, 1),
                        new RaidLootSounds.Cue("ENTITY_FIREWORK_ROCKET_TWINKLE", 1, 1)),
                defaults.claim.get(Tier.GOLD));
        try (var input = getClass().getResourceAsStream("/config.yml");
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var yaml = new YamlConfiguration();
            yaml.load(reader);
            var shipped = new RaidLootSounds(yaml, logger, new HashSet<>());
            assertEquals(defaults.open, shipped.open);
            assertEquals(defaults.claim, shipped.claim);
        }
        verifyNoInteractions(logger);
    }

    @Test
    void parsesCaseNumbersOptionalFieldsAndExplicitSilence() {
        var config = new YamlConfiguration();
        config.set(
                "raid-loot.sounds.open",
                List.of(
                        Map.of("sound", " block_chest_open ", "volume", 0.25, "pitch", 2),
                        Map.of("sound", "ENTITY_ITEM_PICKUP")));
        config.set("raid-loot.sounds.claim.gold", List.of());
        var sounds = new RaidLootSounds(config, mock(Logger.class), new HashSet<>());
        assertEquals(
                List.of(
                        new RaidLootSounds.Cue("BLOCK_CHEST_OPEN", .25f, 2),
                        new RaidLootSounds.Cue("ENTITY_ITEM_PICKUP", 1, 1)),
                sounds.open);
        assertTrue(sounds.claim.get(Tier.GOLD).isEmpty());
        assertEquals(2, sounds.claim.get(Tier.SILVER).size());
    }

    @Test
    void invalidNamesWarnOnceAcrossListsAndBadEntriesAreSkipped() {
        var config = new YamlConfiguration();
        var invalid = Map.of("sound", "NOT_A_SOUND");
        config.set(
                "raid-loot.sounds.open",
                List.of(
                        invalid,
                        invalid,
                        Map.of("sound", "BLOCK_CHEST_OPEN", "volume", -1),
                        Map.of("sound", "ENTITY_ITEM_PICKUP", "pitch", Double.NaN),
                        Map.of("volume", 1),
                        Map.of("sound", "BLOCK_NOTE_BLOCK_BELL", "volume", "loud")));
        config.set("raid-loot.sounds.claim.gold", List.of(invalid));
        var logger = mock(Logger.class);
        var warned = new HashSet<String>();
        var sounds = new RaidLootSounds(config, logger, warned);
        assertTrue(sounds.open.isEmpty());
        assertTrue(sounds.claim.get(Tier.GOLD).isEmpty());
        verify(logger, times(5)).warning(anyString());
        new RaidLootSounds(config, logger, warned);
        verify(logger, times(5)).warning(anyString());
    }

    @Test
    void malformedListFallsBackAndBundledDefaultsDoNotOverrideOldConfigFallback() {
        var config = new YamlConfiguration();
        var bundled = new YamlConfiguration();
        bundled.set("raid-loot.sounds.open", List.of());
        config.setDefaults(bundled);
        assertEquals(
                2, new RaidLootSounds(config, mock(Logger.class), new HashSet<>()).open.size());
        config.set("raid-loot.sounds.open", "invalid");
        var logger = mock(Logger.class);
        assertEquals(2, new RaidLootSounds(config, logger, new HashSet<>()).open.size());
        verify(logger).warning(anyString());
    }
}
