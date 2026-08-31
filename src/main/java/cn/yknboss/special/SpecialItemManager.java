package cn.yknboss.special;

import cn.yknboss.YknBossPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SpecialItemManager implements Listener {
    public static final String RAINBOW_ID = "rainbow";
    public static final String INFERNAL_GRIMOIRE_ID = "infernal_grimoire";
    public static final String ANCIENT_CODEX_ID = "ancient_codex";
    private static final int CATALOG_SIZE = 27;
    private static final int CATALOG_RAINBOW_SLOT = 11;
    private static final int CATALOG_ANCIENT_CODEX_SLOT = 13;
    private static final int CATALOG_INFERNAL_GRIMOIRE_SLOT = 15;
    private static final int DETAIL_PREVIEW_SLOT = 13;
    private static final int DETAIL_OBTAIN_SLOT = 11;
    private static final int DETAIL_BACK_SLOT = 15;
    private static final NamespacedKey RAINBOW_MODEL = new NamespacedKey("yknboss", RAINBOW_ID);
    private static final NamespacedKey INFERNAL_GRIMOIRE_MODEL =
            new NamespacedKey("yknboss", INFERNAL_GRIMOIRE_ID);
    private static final NamespacedKey ANCIENT_CODEX_MODEL =
            new NamespacedKey("yknboss", ANCIENT_CODEX_ID);
    private static final NamespacedKey INFERNAL_GRIMOIRE_COMPANION_OWNER =
            new NamespacedKey("yknboss", "infernal_grimoire_companion_owner");
    private static final NamespacedKey ANCIENT_CODEX_COMPANION_OWNER =
            new NamespacedKey("yknboss", "ancient_codex_companion_owner");
    private static final NamespacedKey RAINBOW_HEALTH = new NamespacedKey("yknboss", "rainbow_head_health");
    private static final double RAINBOW_MAX_HEALTH_BONUS = 100.0;
    private static final double COMPANION_LEFT_OFFSET = 1.0;
    private static final double COMPANION_FORWARD_OFFSET = 0.18;
    private static final double COMPANION_VERTICAL_OFFSET = -0.38;

    private final YknBossPlugin plugin;
    private final Map<UUID, FloatingGrimoire> floatingGrimoires = new HashMap<>();
    private final Map<UUID, FloatingCodex> floatingCodices = new HashMap<>();
    private final Map<UUID, String> spectatorSuspendedCompanions = new HashMap<>();

    public SpecialItemManager(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    public void openCatalog(Player player) {
        CatalogHolder holder = new CatalogHolder();
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE,
                plugin.messages().component("special.gui.catalog-title"));
        holder.bind(inventory);
        inventory.setItem(CATALOG_RAINBOW_SLOT, catalogEntry(createRainbow(1)));
        inventory.setItem(CATALOG_ANCIENT_CODEX_SLOT, catalogEntry(createAncientCodex(1)));
        inventory.setItem(CATALOG_INFERNAL_GRIMOIRE_SLOT, catalogEntry(createInfernalGrimoire(1)));
        player.openInventory(inventory);
    }

    private void openDetail(Player player, String specialItemId) {
        DetailHolder holder = new DetailHolder(specialItemId);
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE,
                plugin.messages().component("special.gui.detail-title",
                        "item", plugin.messages().plain(itemMessagePath(specialItemId, "name"))));
        holder.bind(inventory);
        inventory.setItem(DETAIL_PREVIEW_SLOT, createSpecialItem(specialItemId, 1));
        inventory.setItem(DETAIL_OBTAIN_SLOT, button(Material.LIME_STAINED_GLASS_PANE,
                "special.gui.obtain.name", "special.gui.obtain.lore"));
        inventory.setItem(DETAIL_BACK_SLOT, button(Material.ARROW,
                "special.gui.back.name", "special.gui.back.lore"));
        player.openInventory(inventory);
    }

    public ItemStack createRainbow(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(RAINBOW_MODEL);
        var equippable = meta.getEquippable();
        equippable.setSlot(EquipmentSlot.HEAD);
        equippable.setEquipSound(Sound.ITEM_ARMOR_EQUIP_GENERIC);
        equippable.setSwappable(true);
        equippable.setDispensable(true);
        equippable.setDamageOnHurt(false);
        equippable.setEquipOnInteract(true);
        meta.setEquippable(equippable);
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
                RAINBOW_HEALTH,
                RAINBOW_MAX_HEALTH_BONUS,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.HEAD));
        meta.displayName(plugin.messages().component("special.rainbow.name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().list("special.rainbow.lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(plugin.specialItemKey(), PersistentDataType.STRING, RAINBOW_ID);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createInfernalGrimoire(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, clampAmount(amount));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(INFERNAL_GRIMOIRE_MODEL);
        meta.displayName(plugin.messages().component("special.infernal-grimoire.name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().list("special.infernal-grimoire.lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.getPersistentDataContainer().set(
                plugin.specialItemKey(), PersistentDataType.STRING, INFERNAL_GRIMOIRE_ID);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAncientCodex(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, clampAmount(amount));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(ANCIENT_CODEX_MODEL);
        meta.displayName(plugin.messages().component("special.ancient-codex.name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().list("special.ancient-codex.lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(
                plugin.specialItemKey(), PersistentDataType.STRING, ANCIENT_CODEX_ID);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRainbow(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return RAINBOW_ID.equals(item.getItemMeta().getPersistentDataContainer()
                .get(plugin.specialItemKey(), PersistentDataType.STRING));
    }

    public boolean isInfernalGrimoire(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return INFERNAL_GRIMOIRE_ID.equals(item.getItemMeta().getPersistentDataContainer()
                .get(plugin.specialItemKey(), PersistentDataType.STRING));
    }

    public boolean isAncientCodex(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return ANCIENT_CODEX_ID.equals(item.getItemMeta().getPersistentDataContainer()
                .get(plugin.specialItemKey(), PersistentDataType.STRING));
    }

    public void tick() {
        reconcileSpectatorVisibility();

        Iterator<Map.Entry<UUID, FloatingGrimoire>> iterator = floatingGrimoires.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FloatingGrimoire> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            FloatingGrimoire companion = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead()
                    || !hasStoredInfernalGrimoire(player)) {
                companion.remove();
                iterator.remove();
                continue;
            }

            companion.age++;
            Location target = companionLocation(player, companion.age);
            if (!companion.validIn(target.getWorld())) {
                companion.remove();
                companion.display = spawnFloatingGrimoire(player, target);
            } else {
                companion.display.teleport(target);
            }
            spawnCompanionParticles(target, companion.age);
        }

        Iterator<Map.Entry<UUID, FloatingCodex>> codexIterator = floatingCodices.entrySet().iterator();
        while (codexIterator.hasNext()) {
            Map.Entry<UUID, FloatingCodex> entry = codexIterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            FloatingCodex companion = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead()
                    || !hasStoredSpecialItem(player, ANCIENT_CODEX_ID)) {
                companion.remove();
                codexIterator.remove();
                continue;
            }

            companion.age++;
            Location target = companionLocation(player, companion.age);
            if (!companion.validIn(target.getWorld())) {
                companion.remove();
                companion = spawnFloatingCodex(player, target);
                if (companion == null) {
                    codexIterator.remove();
                    plugin.messages().send(player, "special.ancient-codex.companion-model-unavailable");
                    continue;
                }
                entry.setValue(companion);
            }
            companion.update(target);
            spawnAncientCodexParticles(target, companion.age);
        }
    }

    public void shutdown() {
        floatingGrimoires.values().forEach(FloatingGrimoire::remove);
        floatingGrimoires.clear();
        floatingCodices.values().forEach(FloatingCodex::remove);
        floatingCodices.clear();
        spectatorSuspendedCompanions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMagicBookUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        boolean infernal = isInfernalGrimoire(event.getItem());
        boolean ancient = isAncientCodex(event.getItem());
        if (!infernal && !ancient) return;

        Player player = event.getPlayer();
        String specialItemId = infernal ? INFERNAL_GRIMOIRE_ID : ANCIENT_CODEX_ID;
        String messageRoot = infernal
                ? "special.infernal-grimoire."
                : "special.ancient-codex.";
        if (!stowSpecialItem(player, hand, specialItemId)) {
            plugin.messages().send(player, messageRoot + "companion-no-space");
            return;
        }

        UUID playerId = player.getUniqueId();
        if (isCompanionActive(playerId, specialItemId)) {
            deactivateCompanion(playerId, specialItemId);
            plugin.messages().send(player, messageRoot + "companion-disabled");
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 0.75f);
            return;
        }

        String previousCompanionId = infernal ? ANCIENT_CODEX_ID : INFERNAL_GRIMOIRE_ID;
        deactivateCompanion(playerId, previousCompanionId);
        if (isSpectatingArena(player)) {
            spectatorSuspendedCompanions.put(playerId, specialItemId);
            plugin.messages().send(player, messageRoot + "companion-enabled");
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 0.8f);
            return;
        }

        Location target = companionLocation(player, 0L);
        if (infernal) {
            ItemDisplay display = spawnFloatingGrimoire(player, target);
            floatingGrimoires.put(playerId, new FloatingGrimoire(display));
        } else {
            FloatingCodex companion = spawnFloatingCodex(player, target);
            if (companion == null) {
                plugin.messages().send(player, messageRoot + "companion-model-unavailable");
                return;
            }
            floatingCodices.put(playerId, companion);
        }
        plugin.messages().send(player, messageRoot + "companion-enabled");
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 0.8f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeFloatingGrimoire(playerId);
        removeFloatingCodex(playerId);
        spectatorSuspendedCompanions.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeFloatingGrimoire(playerId);
        removeFloatingCodex(playerId);
        spectatorSuspendedCompanions.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof CatalogHolder) && !(holder instanceof DetailHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (holder instanceof CatalogHolder) {
            if (!event.isLeftClick()) return;
            if (slot == CATALOG_RAINBOW_SLOT) openDetail(player, RAINBOW_ID);
            else if (slot == CATALOG_ANCIENT_CODEX_SLOT) openDetail(player, ANCIENT_CODEX_ID);
            else if (slot == CATALOG_INFERNAL_GRIMOIRE_SLOT) {
                openDetail(player, INFERNAL_GRIMOIRE_ID);
            }
            return;
        }
        if (slot == DETAIL_BACK_SLOT && event.isLeftClick()) {
            openCatalog(player);
            return;
        }
        if (slot != DETAIL_OBTAIN_SLOT || !event.isLeftClick()) return;
        int amount = event.isShiftClick() ? 64 : 1;
        String specialItemId = ((DetailHolder) holder).specialItemId;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                createSpecialItem(specialItemId, amount));
        for (ItemStack remaining : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
        }
        plugin.messages().send(player, itemMessagePath(specialItemId, "received"),
                "amount", amount,
                "item", plugin.messages().plain(itemMessagePath(specialItemId, "name")));
        if (!overflow.isEmpty()) plugin.messages().send(player, "special.overflow-dropped");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof CatalogHolder || holder instanceof DetailHolder) event.setCancelled(true);
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component(nameKey).decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().list(loreKey).stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack catalogEntry(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.lore(withExtraLore(meta.lore(), plugin.messages().list("special.gui.catalog-entry-lore")));
        item.setItemMeta(meta);
        return item;
    }

    private boolean stowSpecialItem(Player player, EquipmentSlot hand, String specialItemId) {
        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isSpecialItem(held, specialItemId)) return false;

        int destination = -1;
        // 主储存区按视觉顺序从右下角开始：35..27，再到上一行最右侧 26..18，最后 17..9。
        for (int slot = 35; slot >= 9; slot--) {
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || current.getType().isAir()) {
                destination = slot;
                break;
            }
        }
        if (destination < 0) return false;

        ItemStack stored = held.clone();
        if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
        else player.getInventory().setItemInMainHand(null);
        player.getInventory().setItem(destination, stored);
        return true;
    }

    private boolean hasStoredInfernalGrimoire(Player player) {
        return hasStoredSpecialItem(player, INFERNAL_GRIMOIRE_ID);
    }

    private boolean hasStoredSpecialItem(Player player, String specialItemId) {
        for (int slot = 9; slot <= 35; slot++) {
            if (isSpecialItem(player.getInventory().getItem(slot), specialItemId)) return true;
        }
        return false;
    }

    private boolean isSpecialItem(ItemStack item, String specialItemId) {
        return switch (specialItemId) {
            case INFERNAL_GRIMOIRE_ID -> isInfernalGrimoire(item);
            case ANCIENT_CODEX_ID -> isAncientCodex(item);
            default -> false;
        };
    }

    private ItemDisplay spawnFloatingGrimoire(Player player, Location target) {
        return target.getWorld().spawn(target, ItemDisplay.class, display -> {
            display.setItemStack(createInfernalGrimoire(1));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setDisplayWidth(1.5f);
            display.setDisplayHeight(1.5f);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
            display.setShadowRadius(0.0f);
            display.setShadowStrength(0.0f);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setPersistent(false);
            display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf().rotationX((float) (Math.PI / 2.0)),
                    new Vector3f(0.85f, 0.85f, 0.85f),
                    new Quaternionf()));
            display.getPersistentDataContainer().set(
                    INFERNAL_GRIMOIRE_COMPANION_OWNER,
                    PersistentDataType.STRING,
                    player.getUniqueId().toString());
        });
    }

    private FloatingCodex spawnFloatingCodex(Player player, Location target) {
        ItemDisplay book = null;
        try {
            book = spawnCodexDisplay(player, target, createAncientCodex(1));
            FloatingCodex companion = new FloatingCodex(book);
            companion.update(target);
            return companion;
        } catch (RuntimeException exception) {
            removeDisplay(book);
            plugin.getLogger().warning("无法生成上古法典悬浮书本: " + exception.getMessage());
            return null;
        }
    }

    private ItemDisplay spawnCodexDisplay(Player player, Location target, ItemStack item) {
        return target.getWorld().spawn(target, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setDisplayWidth(2.5f);
            display.setDisplayHeight(2.5f);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
            display.setShadowRadius(0.0f);
            display.setShadowStrength(0.0f);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setPersistent(false);
            display.getPersistentDataContainer().set(
                    ANCIENT_CODEX_COMPANION_OWNER,
                    PersistentDataType.STRING,
                    player.getUniqueId().toString());
        });
    }

    private static void removeDisplay(ItemDisplay display) {
        if (display != null && display.isValid()) display.remove();
    }

    private static Location companionLocation(Player player, long age) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().setY(0.0);
        if (forward.lengthSquared() < 0.0001) forward.setZ(1.0);
        forward.normalize();
        Vector left = new Vector(forward.getZ(), 0.0, -forward.getX());
        double bob = Math.sin(age * 0.16) * 0.075;
        Location target = eye.clone()
                .add(left.multiply(COMPANION_LEFT_OFFSET))
                .add(forward.multiply(COMPANION_FORWARD_OFFSET))
                .add(0.0, COMPANION_VERTICAL_OFFSET + bob, 0.0);
        target.setYaw(player.getLocation().getYaw() + 180.0f + (float) Math.sin(age * 0.09) * 4.0f);
        target.setPitch(0.0f);
        return target;
    }

    private static void spawnCompanionParticles(Location center, long age) {
        World world = center.getWorld();
        if (age % 3L == 0L) {
            world.spawnParticle(Particle.FLAME, center, 2, 0.22, 0.16, 0.22, 0.004);
        }
        if (age % 10L == 0L) {
            world.spawnParticle(Particle.LAVA, center, 1, 0.18, 0.12, 0.18, 0.0);
        }
    }

    /**
     * Emits the canonical Ancient Codex rune particles. Boss companions use this
     * same entry point so their timing, spread and particle motion cannot drift
     * from the player-side special item effect.
     */
    public static void spawnAncientCodexParticles(Location center, long age) {
        if (age % 3L != 0L) return;
        center.getWorld().spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 0.12, 0.0),
                4, 0.38, 0.24, 0.38, 0.16);
    }

    private void removeFloatingGrimoire(UUID playerId) {
        FloatingGrimoire removed = floatingGrimoires.remove(playerId);
        if (removed != null) removed.remove();
    }

    private void removeFloatingCodex(UUID playerId) {
        FloatingCodex removed = floatingCodices.remove(playerId);
        if (removed != null) removed.remove();
    }

    private void reconcileSpectatorVisibility() {
        for (UUID playerId : List.copyOf(floatingGrimoires.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !isSpectatingArena(player)) continue;
            removeFloatingGrimoire(playerId);
            spectatorSuspendedCompanions.put(playerId, INFERNAL_GRIMOIRE_ID);
        }

        for (UUID playerId : List.copyOf(floatingCodices.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !isSpectatingArena(player)) continue;
            removeFloatingCodex(playerId);
            spectatorSuspendedCompanions.put(playerId, ANCIENT_CODEX_ID);
        }

        Iterator<Map.Entry<UUID, String>> iterator =
                spectatorSuspendedCompanions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, String> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            if (isSpectatingArena(player)) continue;

            String suspendedCompanionId = entry.getValue();
            iterator.remove();
            Location target = companionLocation(player, 0L);
            if (INFERNAL_GRIMOIRE_ID.equals(suspendedCompanionId)
                    && hasStoredSpecialItem(player, INFERNAL_GRIMOIRE_ID)) {
                floatingGrimoires.put(player.getUniqueId(),
                        new FloatingGrimoire(spawnFloatingGrimoire(player, target)));
            }
            if (ANCIENT_CODEX_ID.equals(suspendedCompanionId)
                    && hasStoredSpecialItem(player, ANCIENT_CODEX_ID)) {
                FloatingCodex companion = spawnFloatingCodex(player, target);
                if (companion != null) floatingCodices.put(player.getUniqueId(), companion);
            }
        }
    }

    private boolean isCompanionActive(UUID playerId, String specialItemId) {
        if (specialItemId.equals(spectatorSuspendedCompanions.get(playerId))) return true;
        return switch (specialItemId) {
            case INFERNAL_GRIMOIRE_ID -> floatingGrimoires.containsKey(playerId);
            case ANCIENT_CODEX_ID -> floatingCodices.containsKey(playerId);
            default -> false;
        };
    }

    private void deactivateCompanion(UUID playerId, String specialItemId) {
        if (INFERNAL_GRIMOIRE_ID.equals(specialItemId)) removeFloatingGrimoire(playerId);
        else if (ANCIENT_CODEX_ID.equals(specialItemId)) removeFloatingCodex(playerId);
        spectatorSuspendedCompanions.remove(playerId, specialItemId);
    }

    private boolean isSpectatingArena(Player player) {
        return (plugin.arenaManager() != null && plugin.arenaManager().isSpectating(player))
                || (plugin.endsunArenaManager() != null && plugin.endsunArenaManager().isSpectating(player))
                || (plugin.endWitchArenaManager() != null && plugin.endWitchArenaManager().isSpectating(player));
    }

    private ItemStack createSpecialItem(String specialItemId, int amount) {
        return switch (specialItemId) {
            case RAINBOW_ID -> createRainbow(amount);
            case INFERNAL_GRIMOIRE_ID -> createInfernalGrimoire(amount);
            case ANCIENT_CODEX_ID -> createAncientCodex(amount);
            default -> throw new IllegalArgumentException("未知特殊物品: " + specialItemId);
        };
    }

    private static String itemMessagePath(String specialItemId, String suffix) {
        return switch (specialItemId) {
            case RAINBOW_ID -> "special.rainbow." + suffix;
            case INFERNAL_GRIMOIRE_ID -> "special.infernal-grimoire." + suffix;
            case ANCIENT_CODEX_ID -> "special.ancient-codex." + suffix;
            default -> throw new IllegalArgumentException("未知特殊物品: " + specialItemId);
        };
    }

    private static int clampAmount(int amount) {
        return Math.max(1, Math.min(64, amount));
    }

    private static List<Component> withExtraLore(List<Component> original, List<Component> extra) {
        java.util.ArrayList<Component> result = new java.util.ArrayList<>();
        if (original != null) result.addAll(original);
        result.addAll(extra);
        return result.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList();
    }

    private abstract static class GuiHolder implements InventoryHolder {
        private Inventory inventory;

        final void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class CatalogHolder extends GuiHolder { }

    private static final class DetailHolder extends GuiHolder {
        private final String specialItemId;

        private DetailHolder(String specialItemId) {
            this.specialItemId = specialItemId;
        }
    }

    private static final class FloatingGrimoire {
        private ItemDisplay display;
        private long age;

        private FloatingGrimoire(ItemDisplay display) {
            this.display = display;
        }

        private boolean validIn(World world) {
            return display != null && display.isValid() && display.getWorld().equals(world);
        }

        private void remove() {
            if (display != null && display.isValid()) display.remove();
        }
    }

    private static final class FloatingCodex {
        private final ItemDisplay book;
        private long age;

        private FloatingCodex(ItemDisplay book) {
            this.book = book;
        }

        private boolean validIn(World world) {
            return validDisplay(book, world);
        }

        private void update(Location location) {
            book.teleport(location);

            float sway = (float) Math.sin(age * 0.045) * 0.035f;
            book.setTransformation(transformation(
                    new Vector3f(0.0f, -0.06f, 0.0f),
                    new Quaternionf().rotationY(sway),
                    0.92f));
        }

        private void remove() {
            removeDisplay(book);
        }

        private static boolean validDisplay(ItemDisplay display, World world) {
            return display != null && display.isValid() && display.getWorld().equals(world);
        }

        private static Transformation transformation(Vector3f translation, Quaternionf rotation, float scale) {
            return new Transformation(
                    translation,
                    rotation,
                    new Vector3f(scale, scale, scale),
                    new Quaternionf());
        }
    }
}
