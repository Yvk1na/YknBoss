package cn.yknboss.listener;

import cn.yknboss.YknBossPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.World;

public final class BoneDragonArenaListener implements Listener {
    private final YknBossPlugin plugin;

    public BoneDragonArenaListener(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null
                && (!plugin.arenaManager().allowTeleport(event.getPlayer(), event.getFrom(), event.getTo())
                || !plugin.endsunArenaManager().allowTeleport(event.getPlayer(), event.getFrom(), event.getTo())
                || !plugin.endWitchArenaManager().allowTeleport(event.getPlayer(), event.getFrom(), event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.arenaManager().handleJoin(event.getPlayer());
        plugin.endsunArenaManager().handleJoin(event.getPlayer());
        plugin.endWitchArenaManager().handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.arenaManager().handleQuit(event.getPlayer());
        plugin.endsunArenaManager().handleQuit(event.getPlayer());
        plugin.endWitchArenaManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        boolean spectating = plugin.arenaManager().isSpectating(event.getPlayer())
                || plugin.endsunArenaManager().isSpectating(event.getPlayer())
                || plugin.endWitchArenaManager().isSpectating(event.getPlayer());
        boolean bypass = plugin.arenaManager().canBypassSpectatorGameModeLock(event.getPlayer())
                || plugin.endsunArenaManager().canBypassSpectatorGameModeLock(event.getPlayer())
                || plugin.endWitchArenaManager().canBypassSpectatorGameModeLock(event.getPlayer());
        if (!spectating || bypass
                || event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        event.setCancelled(true);
        String message = plugin.endWitchArenaManager().isSpectating(event.getPlayer())
                ? "witch-arena.spectate.gamemode-locked"
                : plugin.endsunArenaManager().isSpectating(event.getPlayer())
                ? "endsun-arena.spectate.gamemode-locked" : "arena.spectate.gamemode-locked";
        plugin.messages().send(event.getPlayer(), message);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.arenaManager().handlePlayerDeath(event.getEntity())
                && !plugin.endsunArenaManager().handlePlayerDeath(event.getEntity())
                && !plugin.endWitchArenaManager().handlePlayerDeath(event.getEntity())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.arenaManager().handlePlayerRespawn(event);
        plugin.endsunArenaManager().handlePlayerRespawn(event);
        plugin.endWitchArenaManager().handlePlayerRespawn(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon
                && plugin.isArenaWorld(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (plugin.arenaManager().handleMonumentInteract(event.getPlayer(), event.getClickedBlock())
                || plugin.endsunArenaManager().handleMonumentInteract(event.getPlayer(), event.getClickedBlock())
                || plugin.endWitchArenaManager().handleMonumentInteract(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isArenaWorld(event.getBlock().getWorld())) return;
        if (plugin.arenaManager().isArenaWorld(event.getBlock().getWorld())
                && plugin.boneDragonManager().breakPrisonBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), arenaMessage(event.getBlock().getWorld(), "block-break-denied"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamageByPlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !plugin.isArenaWorld(victim.getWorld())) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isArenaWorld(player.getWorld())) return;
        event.setCancelled(true);
        plugin.messages().send(player, arenaMessage(player.getWorld(), "item-drop-denied"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.isArenaWorld(event.getBlockPlaced().getWorld())) return;
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), arenaMessage(event.getBlockPlaced().getWorld(), "block-place-denied"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.isArenaWorld(event.getLocation().getWorld())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (plugin.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private String arenaMessage(World world, String suffix) {
        if (plugin.endWitchArenaManager().isArenaWorld(world)) return "witch-arena." + suffix;
        if (plugin.endsunArenaManager().isArenaWorld(world)) return "endsun-arena." + suffix;
        return "arena." + suffix;
    }
}
