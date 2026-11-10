package cn.yknboss.loot;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.loot.RaidLootService.Category;
import cn.yknboss.loot.RaidLootService.Entry;
import cn.yknboss.loot.RaidLootService.Tier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RaidLootServiceTest {
    @TempDir Path directory;
    private YamlConfiguration config;
    private YknBossPlugin plugin;
    private PluginManager manager;
    private Plugin anvil;
    private AnvilItems factory;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;

    // The public interface models the optional plugin's reflection contract, with no Anvil
    // dependency.
    public interface AnvilItems {
        ItemStack createItem(String id, int amount);
    }

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        config.set("raid-loot.guarantee-one-base", false);
        plugin = mock(YknBossPlugin.class);
        logger = mock(Logger.class);
        when(plugin.getName()).thenReturn("YknBoss");
        when(plugin.namespace()).thenReturn("yknboss");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        manager = mock(PluginManager.class);
        anvil = mock(Plugin.class, withSettings().extraInterfaces(AnvilItems.class));
        factory = (AnvilItems) anvil;
        when(anvil.isEnabled()).thenReturn(true);
        when(manager.getPlugin("YknAnvil")).thenReturn(anvil);
        when(factory.createItem(anyString(), eq(1)))
                .thenAnswer(call -> new Stack(call.getArgument(0)));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) bukkit.close();
    }

    @Test
    void reflectsOneItemTemplatesCachesThemAndClonesEveryReward() {
        var template = new Stack("kept-model-and-tags");
        when(factory.createItem("LuCkStOnE", 1)).thenReturn(template);
        var service = new RaidLootService(plugin);
        var table = List.of(new Entry("YkNaNvIl:LuCkStOnE", Category.FORGE, 100, 3, 3, 0));
        var first = service.roll("bone-dragon", table, Tier.SILVER, null).getFirst();
        var second = service.roll("bone-dragon", table, Tier.SILVER, null).getFirst();
        assertNotSame(template, first);
        assertNotSame(first, second);
        assertEquals("kept-model-and-tags", ((Stack) first).id);
        assertEquals(3, first.getAmount());
        first.setAmount(42);
        assertEquals(3, second.getAmount());
        assertEquals(1, template.getAmount());
        verify(factory, times(1)).createItem("LuCkStOnE", 1);
        service.reload();
        service.roll("bone-dragon", table, Tier.SILVER, null);
        verify(factory, times(2)).createItem("LuCkStOnE", 1);
        verifyNoInteractions(logger);
    }

    @Test
    void absentDisabledUnknownOrBrokenSourcesWarnOnceAndAreSkipped() {
        for (String failure :
                List.of("absent", "disabled", "unknown", "method-missing", "exception")) {
            clearInvocations(logger, factory);
            when(manager.getPlugin("YknAnvil")).thenReturn(anvil);
            when(anvil.isEnabled()).thenReturn(true);
            when(factory.createItem("charm", 1)).thenReturn(new Stack("charm"));
            switch (failure) {
                case "absent" -> when(manager.getPlugin("YknAnvil")).thenReturn(null);
                case "disabled" -> when(anvil.isEnabled()).thenReturn(false);
                case "unknown" -> when(factory.createItem("charm", 1)).thenReturn(null);
                case "method-missing" ->
                        when(manager.getPlugin("YknAnvil")).thenReturn(mock(Plugin.class));
                case "exception" ->
                        when(factory.createItem("charm", 1))
                                .thenThrow(new IllegalArgumentException("bad item"));
                default -> throw new AssertionError(failure);
            }
            if (failure.equals("method-missing")) {
                when(manager.getPlugin("YknAnvil").isEnabled()).thenReturn(true);
            }
            var service = new RaidLootService(plugin);
            var table = List.of(entry("charm", Category.FORGE, 100));
            assertTrue(service.roll("bone-dragon", table, Tier.SILVER, null).isEmpty(), failure);
            assertTrue(service.roll("bone-dragon", table, Tier.SILVER, null).isEmpty(), failure);
            verify(logger, times(1)).warning(anyString());
            if (failure.equals("absent")
                    || failure.equals("disabled")
                    || failure.equals("method-missing")) {
                verify(factory, never()).createItem(anyString(), anyInt());
            }
        }
    }

    @Test
    void unavailableSourceCanRecoverWithoutReloadAndDoesNotBlockOtherRewards() {
        when(factory.createItem("charm", 1)).thenReturn(null);
        var service = new RaidLootService(plugin);
        var table =
                List.of(entry("charm", Category.FORGE, 100), entry("scroll", Category.FORGE, 100));
        assertEquals(List.of("scroll"), ids(service.roll("end-witch", table, Tier.GOLD, null)));
        when(factory.createItem("charm", 1)).thenReturn(new Stack("charm"));
        assertEquals(
                List.of("charm", "scroll"), ids(service.roll("end-witch", table, Tier.GOLD, null)));
        verify(logger, times(1)).warning(anyString());
    }

    @Test
    void fourSlotsKeepCoreThenRareThenLowestChanceForgeBeforeBase() {
        config.set("raid-loot.multipliers.silver.forge", 100);
        var service = new RaidLootService(plugin);
        var table =
                List.of(
                        entry("base", Category.BASE, 100),
                        entry("luckstone", Category.FORGE, 25),
                        entry("charm", Category.FORGE, 5),
                        entry("scroll", Category.FORGE, 3),
                        entry("rare", Category.RARE, 100),
                        entry("core", Category.CORE, 100));
        assertEquals(
                List.of(Category.CORE, Category.RARE, Category.FORGE, Category.BASE),
                List.of(Category.values()));
        assertEquals(
                List.of("core", "rare", "scroll", "charm"),
                ids(service.roll("bone-dragon", table, Tier.SILVER, null)));
    }

    @Test
    void guaranteeOnlyDrawsBaseAndDoesNotAddBaseWhenForgeAlreadyHit() {
        config.set("raid-loot.guarantee-one-base", true);
        var service = new RaidLootService(plugin);
        var forge = entry("luckstone", Category.FORGE, 0);
        var base = entry("base", Category.BASE, 0);
        assertEquals(
                List.of("base"),
                ids(service.roll("bone-dragon", List.of(forge, base), Tier.SILVER, null)));
        assertTrue(service.roll("bone-dragon", List.of(forge), Tier.SILVER, null).isEmpty());
        assertEquals(
                List.of("charm"),
                ids(
                        service.roll(
                                "bone-dragon",
                                List.of(base, entry("charm", Category.FORGE, 100)),
                                Tier.SILVER,
                                null)));
    }

    @Test
    void oldConfigsUseOneForMissingForgeMultiplierEvenWithBundledDefaults() {
        var defaults = new YamlConfiguration();
        for (String tier : List.of("copper", "silver", "gold")) {
            defaults.set("raid-loot.multipliers." + tier + ".forge", 0);
        }
        config.setDefaults(defaults);
        var service = new RaidLootService(plugin);
        var table = List.of(entry("scroll", Category.FORGE, 100));
        for (Tier tier : Tier.values()) {
            assertEquals(
                    List.of("scroll"),
                    ids(service.roll("end-witch", table, tier, null)),
                    tier.name());
        }
        config.set("raid-loot.multipliers.copper.forge", 0);
        service.reload();
        assertTrue(service.roll("end-witch", table, Tier.COPPER, null).isEmpty());
    }

    @Test
    void shippedConfigHasAllNineForgeDropsAndQualityMultipliersWithoutPity() throws Exception {
        config = resource("/config.yml");
        when(plugin.getConfig()).thenReturn(config);
        var service = new RaidLootService(plugin);
        var chances =
                Map.of(
                        "bone-dragon",
                        List.of(25.0, 5.0, 3.0),
                        "endsun-guard",
                        List.of(30.0, 6.0, 4.0),
                        "end-witch",
                        List.of(35.0, 7.0, 5.0));
        for (var raid : chances.entrySet()) {
            var entries =
                    config.getMapList("raid-loot.tables." + raid.getKey()).stream()
                            .map(raw -> service.parseEntry(raid.getKey(), raw))
                            .toList();
            assertEquals(10, entries.size());
            assertEquals(5, entries.stream().filter(e -> e.category() == Category.BASE).count());
            var forge = entries.stream().filter(e -> e.category() == Category.FORGE).toList();
            assertEquals(
                    List.of("yknanvil:luckstone", "yknanvil:charm", "yknanvil:scroll"),
                    forge.stream().map(Entry::item).toList());
            assertEquals(raid.getValue(), forge.stream().map(Entry::chance).toList());
            for (Entry entry : forge) {
                assertEquals(1, entry.min());
                assertEquals(1, entry.max());
                assertEquals(0, entry.pity());
            }
        }
        assertEquals(.5, config.getDouble("raid-loot.multipliers.copper.forge"));
        assertEquals(1, config.getDouble("raid-loot.multipliers.silver.forge"));
        assertEquals(1.5, config.getDouble("raid-loot.multipliers.gold.forge"));
        var descriptor = resource("/plugin.yml");
        assertTrue(descriptor.getStringList("softdepend").contains("YknAnvil"));
        assertEquals("2.0.19", descriptor.getString("version"));
        assertEquals(
                Category.FORGE,
                service.parseEntry("test", Map.of("item", "yknanvil:scroll", "category", "FORGE"))
                        .category());
        assertEquals(Category.BASE, service.parseEntry("test", Map.of("item", "PAPER")).category());
    }

    private static Entry entry(String id, Category category, double chance) {
        return new Entry("yknanvil:" + id, category, chance, 1, 1, 0);
    }

    private static List<String> ids(List<ItemStack> items) {
        return items.stream().map(item -> ((Stack) item).id).toList();
    }

    private static YamlConfiguration resource(String name) throws Exception {
        try (var input = RaidLootServiceTest.class.getResourceAsStream(name);
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }

    private static final class Stack extends ItemStack {
        // Only isAir() is needed; a real Material queries the server's registry in Paper 1.21.10.
        private static final Material MATERIAL = mock(Material.class);
        final String id;
        private int amount = 1;

        Stack(String id) {
            this.id = id;
        }

        @Override
        public Material getType() {
            return MATERIAL;
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public Stack clone() {
            var copy = new Stack(id);
            copy.amount = amount;
            return copy;
        }
    }
}
