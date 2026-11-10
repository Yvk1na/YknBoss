package cn.yknboss.loot;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.loot.RaidLootService.ChestSession;
import cn.yknboss.loot.RaidLootService.PendingLoot;
import cn.yknboss.loot.RaidLootService.Tier;
import cn.yknboss.message.MessageService;
import cn.yknboss.model.BetterModelSupport;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaidLootPresentationTest {
    @TempDir Path directory;
    private YknBossPlugin plugin;
    private RaidLootService service;
    private RaidLootSounds sounds;
    private MockedStatic<Bukkit> bukkit;
    private World world;
    private ChestSession session;
    private Inventory inventory;
    private InventoryView view;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(YknBossPlugin.class);
        when(plugin.getName()).thenReturn("YknBoss");
        when(plugin.namespace()).thenReturn("yknboss");
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.messages()).thenReturn(new MessageService(plugin));
        bukkit = mockStatic(Bukkit.class);
        service = spy(new RaidLootService(plugin));
        sounds = mock(RaidLootSounds.class);
        field("sounds").set(service, sounds);
        inventory = mock(Inventory.class);
        view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        bukkit.when(
                        () ->
                                Bukkit.createInventory(
                                        any(InventoryHolder.class), eq(27), any(Component.class)))
                .thenReturn(inventory);
        doNothing().when(service).render(any(), anyList());
        world = mock(World.class);
        session = new ChestSession(UUID.randomUUID(), Tier.COPPER);
        session.anchor = mock(Entity.class);
        when(session.anchor.isValid()).thenReturn(true);
        when(session.anchor.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        session.model = mock(BetterModelSupport.Instance.class);
        sessions().put(session.sessionKey, session);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void actualGuiOpeningTriggersOnceAcrossPlayersAndRepeatedClicks() {
        var first = player();
        var second = player();
        service.open(first, session);
        service.open(first, session);
        service.open(second, session);
        assertTrue(session.opened);
        verify(session.model).playOnceAndHold("open");
        verify(sounds).open(eq(world), any(Location.class));
        assertEquals(2, particleCalls()); // Copper burst: sparks and flame, once each.
    }

    @Test
    void ineligibleClaimedRewardlessCancelledOrReplacedGuiDoesNotOpenChest() {
        var player = player();
        var rewards = session.rewards.remove(player.getUniqueId());
        service.open(player, session);
        session.ineligible.add(player.getUniqueId());
        service.open(player, session);
        session.claimed.add(player.getUniqueId());
        service.open(player, session);
        session.rewards.put(player.getUniqueId(), rewards);
        when(player.openInventory(inventory)).thenReturn(null);
        service.open(player, session);
        when(player.openInventory(inventory)).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        service.open(player, session);
        assertFalse(session.opened);
        verifyNoInteractions(sounds, session.model);
        assertEquals(0, particleCalls());
    }

    @Test
    void vanillaFallbackStillOpensOnceWithSoundAndBurst() {
        session.model = null;
        var player = player();
        service.open(player, session);
        service.open(player, session);
        assertTrue(session.opened);
        verify(sounds).open(eq(world), any(Location.class));
        assertEquals(2, particleCalls());
    }

    @Test
    void particlesRequireOpenChestNearbyPlayerAndFiveTicksAndStopOnRemoval() throws Exception {
        var player = player();
        when(world.getPlayers()).thenReturn(List.of(player));
        for (int i = 0; i < 5; i++) service.tick();
        assertEquals(0, particleCalls());
        session.opened = true;
        when(player.getLocation()).thenReturn(new Location(world, 33, 0, 0));
        for (int i = 0; i < 5; i++) service.tick();
        assertEquals(0, particleCalls());
        when(player.getLocation()).thenReturn(new Location(world, 0, 1, 0));
        for (int i = 0; i < 4; i++) service.tick();
        assertEquals(0, particleCalls());
        service.tick();
        assertEquals(1, particleCalls());
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        session.rewards.clear();
        var model = session.model;
        service.close(session.sessionKey);
        for (int i = 0; i < 20; i++) service.tick();
        assertEquals(1, particleCalls());
        verify(model).close();
        verify(model, never()).playOnceAndHold(anyString());
        assertTrue(sessions().isEmpty());
    }

    @Test
    void normalClaimAndOnlineAutoDeliveryPlayOnlyRecipientsQuality() throws Exception {
        var player = player();
        var holder = new RaidLootService.LootHolder(session.sessionKey);
        service.claim(player, session, holder);
        service.claim(player, session, holder);
        verify(sounds).claim(player, Tier.COPPER);
        assertTrue(session.claimed.contains(player.getUniqueId()));
        var other = new ChestSession(UUID.randomUUID(), Tier.GOLD);
        other.rewards.put(player.getUniqueId(), List.of(stack()));
        sessions().put(other.sessionKey, other);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        service.close(other.sessionKey);
        verify(sounds).claim(player, Tier.GOLD);
        verify(sounds, never()).open(any(), any());
    }

    @Test
    void pendingBatchesPreserveQualityAndLegacyItemsRemainDeliverable() throws Exception {
        ItemStack item = stack();
        for (Tier tier : Tier.values()) {
            var batch = new PendingLoot(tier, List.of(item));
            assertEquals(List.of(batch), PendingLoot.read(List.of(batch.serialize())));
        }
        assertEquals(Tier.SILVER, PendingLoot.read(List.of(item)).getFirst().tier());
        assertEquals(
                Tier.SILVER,
                PendingLoot.read(List.of(Map.of("items", List.of(item), "tier", "old")))
                        .getFirst()
                        .tier());
        var player = player();
        pending()
                .put(
                        player.getUniqueId(),
                        new ArrayList<>(
                                List.of(
                                        new PendingLoot(Tier.COPPER, List.of(item)),
                                        new PendingLoot(Tier.GOLD, List.of(item)))));
        var scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        service.onJoin(new PlayerJoinEvent(player, Component.empty()));
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), task.capture(), eq(60L));
        task.getValue().run();
        task.getValue().run();
        verify(sounds).claim(player, Tier.COPPER);
        verify(sounds).claim(player, Tier.GOLD);
        assertTrue(pending().isEmpty());
    }

    private Player player() {
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.openInventory(inventory)).thenReturn(view);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.getLocation()).thenReturn(new Location(world, 0, 1, 0));
        var bag = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(bag);
        when(bag.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        session.rewards.put(id, List.of(stack()));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        return player;
    }

    private static ItemStack stack() {
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(mock(Material.class));
        when(item.clone()).thenReturn(item);
        return item;
    }

    private long particleCalls() {
        return mockingDetails(world).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("spawnParticle"))
                .count();
    }

    private static Field field(String name) throws Exception {
        var field = RaidLootService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ChestSession> sessions() throws Exception {
        return (Map<UUID, ChestSession>) field("sessions").get(service);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, List<PendingLoot>> pending() throws Exception {
        return (Map<UUID, List<PendingLoot>>) field("pending").get(service);
    }
}
