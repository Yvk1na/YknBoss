package cn.yknboss.loot;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.model.BetterModelSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 通关宝箱：Boss 被击败后在场地中央生成一个按通关时长分级的宝箱，
 * 每名合格成员各自掷骰，右键宝箱在 GUI 中领取自己的奖励。
 */
public final class RaidLootService implements Listener {
    private static final int GUI_SIZE = 27;
    private static final int[] REWARD_SLOTS = {10, 11, 12, 13};
    private static final int CLAIM_SLOT = 15;
    private static final int CLOSE_SLOT = 16;
    private static final String MODEL_PREFIX = "yknboss_chest_";

    enum Tier {
        COPPER("copper", 0.75, 0.5, 0.5),
        SILVER("silver", 1.0, 1.0, 1.0),
        GOLD("gold", 1.25, 1.25, 1.5);

        private final String key;
        private final double defaultBase;
        private final double defaultCore;
        private final double defaultRare;

        Tier(String key, double defaultBase, double defaultCore, double defaultRare) {
            this.key = key;
            this.defaultBase = defaultBase;
            this.defaultCore = defaultCore;
            this.defaultRare = defaultRare;
        }
    }

    enum Category {
        CORE, RARE, FORGE, BASE
    }

    record Entry(String item, Category category, double chance, int min, int max, int pity) { }

    private record Multipliers(double base, double core, double rare, double forge) {
        private double of(Category category) {
            return switch (category) {
                case CORE -> core;
                case RARE -> rare;
                case FORGE -> forge;
                case BASE -> base;
            };
        }
    }

    private record Rolled(Entry entry, ItemStack item) { }

    private final YknBossPlugin plugin;
    private final NamespacedKey chestKey;
    private final File pendingFile;
    private final Map<UUID, ChestSession> sessions = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private final Map<String, ItemStack> templates = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<String, List<Entry>> tables = new HashMap<>();
    private final EnumMap<Tier, Multipliers> multipliers = new EnumMap<>(Tier.class);

    private boolean enabled;
    private double minimumFairShare;
    private boolean guaranteeOneBase;
    private float interactionWidth;
    private float interactionHeight;
    private double offsetY;

    public RaidLootService(YknBossPlugin plugin) {
        this.plugin = plugin;
        this.chestKey = new NamespacedKey(plugin, "raid_loot_chest");
        this.pendingFile = new File(plugin.getDataFolder(), "pending-loot.yml");
        reload();
        loadPending();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("raid-loot.enabled", true);
        minimumFairShare = Math.max(0.0, config.getDouble("raid-loot.minimum-fair-share", 0.3));
        guaranteeOneBase = config.getBoolean("raid-loot.guarantee-one-base", true);
        interactionWidth = (float) Math.max(0.1, config.getDouble("raid-loot.chest.interaction-width", 1.7));
        interactionHeight = (float) Math.max(0.1, config.getDouble("raid-loot.chest.interaction-height", 1.6));
        offsetY = config.getDouble("raid-loot.chest.offset-y", 0.0);

        templates.clear();
        warned.clear();
        tables.clear();
        ConfigurationSection tableSection = config.getConfigurationSection("raid-loot.tables");
        if (tableSection != null) {
            for (String raid : tableSection.getKeys(false)) {
                List<Entry> entries = new ArrayList<>();
                for (Map<?, ?> raw : tableSection.getMapList(raid)) {
                    Entry entry = parseEntry(raid, raw);
                    if (entry != null) entries.add(entry);
                }
                tables.put(raid, List.copyOf(entries));
            }
        }
        for (Tier tier : Tier.values()) {
            String path = "raid-loot.multipliers." + tier.key + ".";
            multipliers.put(tier, new Multipliers(
                    Math.max(0.0, config.getDouble(path + "base", tier.defaultBase)),
                    Math.max(0.0, config.getDouble(path + "core", tier.defaultCore)),
                    Math.max(0.0, config.getDouble(path + "rare", tier.defaultRare)),
                    Math.max(0.0, config.getDouble(path + "forge", 1.0))));
        }
    }

    Entry parseEntry(String raid, Map<?, ?> raw) {
        Object item = raw.get("item");
        if (!(item instanceof String itemKey) || itemKey.isBlank()) {
            plugin.getLogger().warning("raid-loot.tables." + raid + " 中有掉落项缺少 item，已忽略。");
            return null;
        }
        String categoryName = String.valueOf(raw.containsKey("category") ? raw.get("category") : "base")
                .toLowerCase(Locale.ROOT);
        Category category = switch (categoryName) {
            case "core" -> Category.CORE;
            case "rare" -> Category.RARE;
            case "forge" -> Category.FORGE;
            default -> Category.BASE;
        };
        double chance = Math.max(0.0, Math.min(100.0, number(raw.get("chance"), 0.0)));
        int min = Math.max(1, (int) number(raw.get("min"), 1.0));
        int max = Math.max(min, (int) number(raw.get("max"), min));
        int pity = Math.max(0, (int) number(raw.get("pity"), 0.0));
        return new Entry(itemKey.trim(), category, chance, min, max, pity);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * Boss 被击败时调用。clearTicks 为从 Boss 出现到被击杀所用的 tick 数。
     */
    public void spawnChest(String raid, UUID sessionKey, Location focus, int clearTicks,
                           Set<UUID> eligibleParticipants, Map<UUID, Double> damageContributions) {
        if (!enabled || sessionKey == null || focus == null || focus.getWorld() == null) return;
        if (sessions.containsKey(sessionKey)) return;
        String raidKey = raid.replace('_', '-');
        List<Entry> table = tables.getOrDefault(raidKey, List.of());
        if (table.isEmpty() || eligibleParticipants.isEmpty()) return;

        double totalDamage = 0.0;
        for (UUID playerId : eligibleParticipants) totalDamage += damageOf(damageContributions, playerId);
        if (totalDamage <= 0.0) return;
        double threshold = totalDamage / eligibleParticipants.size() * minimumFairShare;

        int clearSeconds = Math.max(0, clearTicks) / 20;
        Tier tier = tierFor(raidKey, clearSeconds);
        ChestSession session = new ChestSession(sessionKey, tier);
        for (UUID playerId : eligibleParticipants) {
            if (damageOf(damageContributions, playerId) + 0.0001 < threshold) {
                session.ineligible.add(playerId);
                continue;
            }
            List<ItemStack> rewards = roll(raidKey, table, tier, Bukkit.getPlayer(playerId));
            if (!rewards.isEmpty()) session.rewards.put(playerId, rewards);
        }

        World world = focus.getWorld();
        Location ground = groundBelow(focus);
        Interaction anchor = world.spawn(ground, Interaction.class, entity -> {
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setInteractionWidth(interactionWidth);
            entity.setInteractionHeight(interactionHeight);
            entity.setResponsive(true);
            entity.getPersistentDataContainer().set(chestKey, PersistentDataType.STRING, sessionKey.toString());
        });
        session.anchor = anchor;
        attachVisual(session, anchor, ground);
        sessions.put(sessionKey, session);

        world.spawnParticle(Particle.END_ROD, ground.clone().add(0.0, 1.0, 0.0), 60, 0.8, 0.8, 0.8, 0.04);
        world.playSound(ground, Sound.BLOCK_ENDER_CHEST_OPEN, 2.0f, 0.8f);
        world.playSound(ground, Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.2f);
        Component message = plugin.messages().component("loot.chest-appeared",
                "time", formatDuration(clearSeconds),
                "quality", plugin.messages().raw("loot.quality." + tier.key));
        for (Player player : world.getPlayers()) player.sendMessage(message);
    }

    private void attachVisual(ChestSession session, Interaction anchor, Location ground) {
        String modelId = MODEL_PREFIX + session.tier.key;
        BetterModelSupport betterModel = plugin.betterModel();
        if (betterModel.available() && betterModel.hasModel(modelId)) {
            try {
                session.model = betterModel.attachStatic(modelId, anchor, 1.0f);
                return;
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning("无法附加通关宝箱模型 " + modelId + "：" + exception.getMessage());
            }
        } else if (warned.add("model:" + modelId)) {
            plugin.getLogger().warning("BetterModel 未加载通关宝箱模型 " + modelId
                    + "，已改用原版箱子外观；请安装 YknBoss-BetterModel-Pack 后执行 /bettermodel reload。");
        }
        session.fallbackDisplay = ground.getWorld().spawn(ground.clone().add(0.0, 0.75, 0.0), ItemDisplay.class,
                display -> {
                    display.setPersistent(false);
                    display.setItemStack(new ItemStack(Material.CHEST));
                    display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                            new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf()));
                });
    }

    private Location groundBelow(Location focus) {
        World world = focus.getWorld();
        int x = focus.getBlockX();
        int z = focus.getBlockZ();
        int top = focus.getBlockY();
        for (int y = top; y >= top - 16 && y > world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return new Location(world, focus.getX(), y + 1.0 + offsetY, focus.getZ(), 0.0f, 0.0f);
            }
        }
        return new Location(world, focus.getX(), focus.getY() - 4.0 + offsetY, focus.getZ(), 0.0f, 0.0f);
    }

    private Tier tierFor(String raidKey, int clearSeconds) {
        FileConfiguration config = plugin.getConfig();
        int gold = config.getInt("raid-loot.time-limits." + raidKey + ".gold", 330);
        int silver = config.getInt("raid-loot.time-limits." + raidKey + ".silver", 450);
        if (clearSeconds <= gold) return Tier.GOLD;
        return clearSeconds <= silver ? Tier.SILVER : Tier.COPPER;
    }

    List<ItemStack> roll(String raidKey, List<Entry> table, Tier tier, Player player) {
        Multipliers multiplier = multipliers.get(tier);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Rolled> hits = new ArrayList<>();
        for (Entry entry : table) {
            double chance = Math.min(100.0, entry.chance() * multiplier.of(entry.category()));
            boolean hit = random.nextDouble(100.0) < chance;
            if (entry.pity() > 0 && player != null) {
                PersistentDataContainer data = player.getPersistentDataContainer();
                NamespacedKey pityKey = pityKey(raidKey, entry.item());
                int misses = data.getOrDefault(pityKey, PersistentDataType.INTEGER, 0);
                if (!hit && misses >= entry.pity()) hit = true;
                data.set(pityKey, PersistentDataType.INTEGER, hit ? 0 : misses + 1);
            }
            if (!hit) continue;
            ItemStack item = create(entry.item(), random.nextInt(entry.min(), entry.max() + 1));
            if (item != null) hits.add(new Rolled(entry, item));
        }
        if (hits.isEmpty() && guaranteeOneBase) {
            List<Entry> bases = table.stream().filter(entry -> entry.category() == Category.BASE).toList();
            if (!bases.isEmpty()) {
                Entry fallback = bases.get(random.nextInt(bases.size()));
                ItemStack item = create(fallback.item(), fallback.min());
                if (item != null) hits.add(new Rolled(fallback, item));
            }
        }
        if (hits.size() > REWARD_SLOTS.length) {
            // 奖励格只有四个：核心 → 稀有 → 锻造道具 → 基础素材；同类优先低概率物品。
            hits.sort(Comparator.<Rolled, Category>comparing(rolled -> rolled.entry().category())
                    .thenComparingDouble(rolled -> rolled.entry().chance()));
            hits = new ArrayList<>(hits.subList(0, REWARD_SLOTS.length));
        }
        List<ItemStack> result = new ArrayList<>(hits.size());
        for (Rolled rolled : hits) result.add(rolled.item());
        return result;
    }

    private NamespacedKey pityKey(String raidKey, String itemKey) {
        String sanitized = (raidKey + "_" + itemKey).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return new NamespacedKey(plugin, "loot_pity_" + sanitized);
    }

    private static double damageOf(Map<UUID, Double> contributions, UUID playerId) {
        Double value = contributions.get(playerId);
        return value == null ? 0.0 : Math.max(0.0, value);
    }

    // ---- 物品解析 ----

    private ItemStack create(String itemKey, int amount) {
        ItemStack template = templates.get(itemKey);
        if (template == null) {
            try {
                template = resolve(itemKey);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                template = null;
                if (warned.add("item:" + itemKey)) {
                    plugin.getLogger().warning("无法生成通关奖励 " + itemKey + "：" + exception);
                }
            }
            if (template == null || template.getType().isAir()) {
                if (warned.add("item:" + itemKey)) {
                    plugin.getLogger().warning("通关奖励 " + itemKey + " 不存在或其来源插件未启用，本次已跳过。");
                }
                return null;
            }
            templates.put(itemKey, template);
        }
        ItemStack item = template.clone();
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return item;
    }

    private ItemStack resolve(String itemKey) throws ReflectiveOperationException {
        int separator = itemKey.indexOf(':');
        String source = separator < 0 ? "vanilla" : itemKey.substring(0, separator).toLowerCase(Locale.ROOT);
        String id = separator < 0 ? itemKey : itemKey.substring(separator + 1);
        return switch (source) {
            case "mythic" -> resolveMythic(id);
            case "eco" -> resolveEco(id);
            case "yknanvil" -> resolveAnvil(id);
            default -> {
                Material material = Material.matchMaterial(id);
                yield material == null || !material.isItem() ? null : new ItemStack(material);
            }
        };
    }

    private ItemStack resolveAnvil(String id) throws ReflectiveOperationException {
        Plugin anvil = Bukkit.getPluginManager().getPlugin("YknAnvil");
        if (anvil == null || !anvil.isEnabled()) return null;
        Object item = anvil.getClass().getMethod("createItem", String.class, int.class)
                .invoke(anvil, id, 1);
        return item instanceof ItemStack stack ? stack : null;
    }

    private ItemStack resolveMythic(String id) throws ReflectiveOperationException {
        Plugin mythicMobs = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythicMobs == null || !mythicMobs.isEnabled()) return null;
        ClassLoader loader = mythicMobs.getClass().getClassLoader();
        Class<?> mythicBukkitType = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, loader);
        Object mythicBukkit = mythicBukkitType.getMethod("inst").invoke(null);
        Object itemManager = mythicBukkitType.getMethod("getItemManager").invoke(mythicBukkit);
        Class<?> managerType = Class.forName("io.lumine.mythic.api.items.ItemManager", true, loader);
        Object found = managerType.getMethod("getItem", String.class).invoke(itemManager, id);
        if (!(found instanceof Optional<?> optional) || optional.isEmpty()) return null;
        Object mythicItem = optional.get();
        Object abstractStack = mythicItem.getClass().getMethod("generateItemStack", int.class)
                .invoke(mythicItem, 1);
        Class<?> abstractStackType = Class.forName("io.lumine.mythic.api.adapters.AbstractItemStack", true, loader);
        Class<?> adapterType = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader);
        Object adapted = adapterType.getMethod("adapt", abstractStackType).invoke(null, abstractStack);
        return adapted instanceof ItemStack stack ? stack.clone() : null;
    }

    private ItemStack resolveEco(String lookup) throws ReflectiveOperationException {
        Plugin eco = Bukkit.getPluginManager().getPlugin("eco");
        if (eco == null || !eco.isEnabled()) return null;
        ClassLoader loader = eco.getClass().getClassLoader();
        Class<?> itemsType = Class.forName("com.willfp.eco.core.items.Items", true, loader);
        Object testable = itemsType.getMethod("lookup", String.class).invoke(null, lookup);
        if (testable == null) return null;
        Class<?> testableType = Class.forName("com.willfp.eco.core.items.TestableItem", true, loader);
        Object item = testableType.getMethod("getItem").invoke(testable);
        return item instanceof ItemStack stack ? stack.clone() : null;
    }

    // ---- 宝箱交互与 GUI ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        String raw = event.getRightClicked().getPersistentDataContainer().get(chestKey, PersistentDataType.STRING);
        if (raw == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        ChestSession session = null;
        try {
            session = sessions.get(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            // 损坏的标记按无效宝箱处理。
        }
        if (session == null) return;
        open(event.getPlayer(), session);
    }

    private void open(Player player, ChestSession session) {
        UUID playerId = player.getUniqueId();
        if (!session.rewards.containsKey(playerId)) {
            String key = session.claimed.contains(playerId) ? "loot.already-claimed"
                    : session.ineligible.contains(playerId) ? "loot.not-eligible" : "loot.no-reward";
            plugin.messages().send(player, key);
            return;
        }
        LootHolder holder = new LootHolder(session.sessionKey);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, plugin.messages().component(
                "loot.gui-title", "quality", plugin.messages().plain("loot.quality." + session.tier.key)));
        holder.inventory = inventory;
        render(inventory, session.rewards.get(playerId));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);
    }

    private void render(Inventory inventory, List<ItemStack> rewards) {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setHideTooltip(true);
        filler.setItemMeta(fillerMeta);
        for (int slot = 0; slot < GUI_SIZE; slot++) inventory.setItem(slot, filler);
        for (int index = 0; index < REWARD_SLOTS.length; index++) {
            inventory.setItem(REWARD_SLOTS[index],
                    rewards != null && index < rewards.size() ? rewards.get(index).clone() : null);
        }
        inventory.setItem(CLAIM_SLOT, button(Material.EMERALD, "loot.claim"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "loot.close"));
    }

    private ItemStack button(Material material, String messagePath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component(messagePath + ".name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().list(messagePath + ".lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof LootHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getSlot() == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getSlot() != CLAIM_SLOT) return;
        ChestSession session = sessions.get(holder.sessionKey);
        if (session == null) {
            player.closeInventory();
            return;
        }
        claim(player, session, holder);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LootHolder) event.setCancelled(true);
    }

    private void claim(Player player, ChestSession session, LootHolder holder) {
        UUID playerId = player.getUniqueId();
        List<ItemStack> rewards = session.rewards.get(playerId);
        if (rewards == null) {
            player.closeInventory();
            plugin.messages().send(player, "loot.already-claimed");
            return;
        }
        List<ItemStack> leftovers = addToInventory(player, rewards);
        if (leftovers.isEmpty()) {
            session.rewards.remove(playerId);
            session.claimed.add(playerId);
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            plugin.messages().send(player, "loot.claimed");
            return;
        }
        session.rewards.put(playerId, leftovers);
        render(holder.inventory, leftovers);
        plugin.messages().send(player, "loot.inventory-full");
    }

    private static List<ItemStack> addToInventory(Player player, List<ItemStack> items) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) {
            leftovers.addAll(player.getInventory().addItem(item.clone()).values());
        }
        return leftovers;
    }

    // ---- 结算与补发 ----

    /**
     * 副本会话结束、玩家已返回原位置后调用：未领取的奖励自动发放，离线成员留待上线补发。
     */
    public void close(UUID sessionKey) {
        if (sessionKey == null) return;
        ChestSession session = sessions.remove(sessionKey);
        if (session == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof LootHolder holder
                    && holder.sessionKey.equals(sessionKey)) {
                player.closeInventory();
            }
        }
        boolean pendingChanged = false;
        for (Map.Entry<UUID, List<ItemStack>> entry : session.rewards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                deliver(player, entry.getValue(), "loot.auto-granted");
            } else {
                pending.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
                pendingChanged = true;
            }
        }
        session.rewards.clear();
        session.removeVisuals();
        if (pendingChanged) savePending();
    }

    private void deliver(Player player, List<ItemStack> items, String messageKey) {
        List<ItemStack> leftovers = addToInventory(player, items);
        plugin.messages().send(player, messageKey);
        if (leftovers.isEmpty()) return;
        for (ItemStack leftover : leftovers) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        plugin.messages().send(player, "loot.overflow-dropped");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!pending.containsKey(playerId)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            List<ItemStack> items = pending.remove(playerId);
            if (items == null || items.isEmpty()) return;
            savePending();
            deliver(player, items, "loot.pending-delivered");
        }, 60L);
    }

    public void shutdown() {
        boolean pendingChanged = false;
        for (ChestSession session : List.copyOf(sessions.values())) {
            for (Map.Entry<UUID, List<ItemStack>> entry : session.rewards.entrySet()) {
                pending.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
                pendingChanged = true;
            }
            session.rewards.clear();
            session.removeVisuals();
        }
        sessions.clear();
        if (pendingChanged) savePending();
    }

    private void loadPending() {
        if (!pendingFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            List<?> stored = players.getList(key);
            if (stored == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (Object value : stored) {
                if (value instanceof ItemStack item && !item.getType().isAir()) items.add(item);
            }
            if (items.isEmpty()) continue;
            try {
                pending.put(UUID.fromString(key), items);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("pending-loot.yml 中存在无效的玩家 UUID：" + key);
            }
        }
    }

    private void savePending() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> entry : pending.entrySet()) {
            if (!entry.getValue().isEmpty()) yaml.set("players." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(pendingFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("保存待补发通关奖励失败：" + exception.getMessage());
        }
    }

    private static String formatDuration(int totalSeconds) {
        int clamped = Math.max(0, totalSeconds);
        return String.format(Locale.ROOT, "%d:%02d", clamped / 60, clamped % 60);
    }

    private static final class LootHolder implements InventoryHolder {
        private final UUID sessionKey;
        private Inventory inventory;

        private LootHolder(UUID sessionKey) {
            this.sessionKey = sessionKey;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ChestSession {
        private final UUID sessionKey;
        private final Tier tier;
        private final Map<UUID, List<ItemStack>> rewards = new HashMap<>();
        private final Set<UUID> claimed = new HashSet<>();
        private final Set<UUID> ineligible = new HashSet<>();
        private Entity anchor;
        private Entity fallbackDisplay;
        private BetterModelSupport.Instance model;

        private ChestSession(UUID sessionKey, Tier tier) {
            this.sessionKey = sessionKey;
            this.tier = tier;
        }

        private void removeVisuals() {
            if (model != null) {
                try {
                    model.close();
                } catch (RuntimeException | LinkageError ignored) {
                    // 模型追踪器已随实体失效时无需额外处理。
                }
                model = null;
            }
            if (fallbackDisplay != null && fallbackDisplay.isValid()) fallbackDisplay.remove();
            if (anchor != null && anchor.isValid()) anchor.remove();
            fallbackDisplay = null;
            anchor = null;
        }
    }
}
