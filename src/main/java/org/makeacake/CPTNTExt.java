package org.makeacake;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CPTNTExt extends JavaPlugin implements Listener {

    private static record BlockPos(UUID world, int x, int y, int z) {
        static BlockPos of(Location loc) {
            return new BlockPos(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
        static BlockPos of(Block b) {
            return new BlockPos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Cache<BlockPos, String> locationCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .concurrencyLevel(4)
            .maximumSize(200_000)
            .recordStats()
            .build();

    private final Cache<Entity, String> entityCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .concurrencyLevel(4)
            .maximumSize(50_000)
            .recordStats()
            .build();

    private CoreProtectAPI api;

    @Override
    public void onEnable() {
        getLogger().info("CPTNTExt enabling...");
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        Plugin depend = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (depend == null) {
            getLogger().severe("CoreProtect not found, disabling.");
            getPluginLoader().disablePlugin(this);
            return;
        }
        api = ((CoreProtect) depend).getAPI();
        getLogger().info("CPTNTExt enabled, CoreProtect API loaded: " + (api != null));
    }

    /* ---------- HELPERS ---------- */

    private void putLocation(Location loc, String reason) {
        if (loc == null || reason == null) return;
        locationCache.put(BlockPos.of(loc), reason);
    }

    private String getLocationReason(Location loc) {
        if (loc == null) return null;
        return locationCache.getIfPresent(BlockPos.of(loc));
    }

    private void putBlock(Block b, String reason) {
        if (b == null || reason == null) return;
        locationCache.put(BlockPos.of(b), reason);
    }

    private void putEntity(Entity e, String reason) {
        if (e == null || reason == null) return;
        entityCache.put(e, reason);
    }

    private String getEntityReason(Entity e) {
        if (e == null) return null;
        return entityCache.getIfPresent(e);
    }

    private void logRemovalForBlocks(String reason, List<Block> blocks) {
        if (reason == null || blocks == null) return;
        for (Block block : blocks) {
            api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
            putBlock(block, reason);
        }
    }

    /* ---------- EVENTS ---------- */

    // Bed / Respawn anchor interaction
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractBedOrRespawnAnchorExplosion(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clickedBlock = e.getClickedBlock();
        if (clickedBlock == null) return;
        Location locationHead = clickedBlock.getLocation();

        if (clickedBlock.getBlockData() instanceof Bed bed) {
            Location locationFoot = locationHead.clone().subtract(bed.getFacing().getDirection());
            if (bed.getPart() == Bed.Part.FOOT) {
                locationHead.add(bed.getFacing().getDirection());
            }
            String reason = "#bed-" + e.getPlayer().getName();
            putLocation(locationHead, reason);
            putLocation(locationFoot, reason);
        }

        if (clickedBlock.getBlockData() instanceof RespawnAnchor) {
            putBlock(clickedBlock, "#respawnanchor-" + e.getPlayer().getName());
        }
    }

    // Creeper ignite (player right-click creeper)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractCreeper(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Creeper creeper)) return;
        putEntity(creeper, "#ignitecreeper-" + e.getPlayer().getName());
    }

    // Block explode
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "block-explosion");
        if (!section.getBoolean("enable", true)) return;

        Block origin = e.getBlock();
        String cause = getLocationReason(origin.getLocation());
        if (cause == null) {
            if (section.getBoolean("disable-unknown", true)) {
                e.blockList().clear();
                Util.broadcastNearPlayers(origin.getLocation(), section.getString("alert"));
                return;
            } else {
                return;
            }
        }
        for (Block block : e.blockList()) {
            api.logRemoval(cause, block.getLocation(), block.getType(), block.getBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlaceOnHanging(BlockPlaceEvent event) {
        putBlock(event.getBlock(), event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        putBlock(event.getBlock(), event.getPlayer().getName());
    }

    // ItemFrame interaction / add / rotate
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onClickItemFrame(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame itemFrame)) return;
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) return;

        api.logInteraction(e.getPlayer().getName(), itemFrame.getLocation());

        if (itemFrame.getItem().getType().isAir()) {
            ItemStack mainItem = e.getPlayer().getInventory().getItemInMainHand();
            ItemStack offItem = e.getPlayer().getInventory().getItemInOffHand();
            ItemStack putIn = mainItem.getType().isAir() ? offItem : mainItem;
            if (!putIn.getType().isAir()) {
                api.logPlacement("#additem-" + e.getPlayer().getName(), itemFrame.getLocation(), putIn.getType(), null);
                return;
            }
        }
        api.logRemoval("#rotate-" + e.getPlayer().getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
        api.logPlacement("#rotate-" + e.getPlayer().getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
    }

    // Projectile launch
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        ProjectileSource projectileSource = e.getEntity().getShooter();
        if (projectileSource == null) return;

        StringBuilder source = new StringBuilder();
        if (!(projectileSource instanceof Player)) source.append("#");
        source.append(e.getEntity().getName()).append("-");

        if (projectileSource instanceof Entity ent) {
            if (ent instanceof Mob mob && mob.getTarget() != null) {
                source.append(mob.getTarget().getName());
            } else {
                source.append(ent.getName());
            }
            putEntity(ent, source.toString());
        } else if (projectileSource instanceof Block block) {
            putBlock(block, source.toString());
        } else {
            source.append(projectileSource.getClass().getSimpleName());
        }

        putEntity(e.getEntity(), source.toString());
    }

    // TNT primed spawn
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onIgniteTNT(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed tntPrimed)) return;
        Entity source = tntPrimed.getSource();
        if (source != null) {
            String sourceFromEntity = getEntityReason(source);
            if (sourceFromEntity != null) {
                putEntity(tntPrimed, sourceFromEntity);
            }
            if (source.getType() == EntityType.PLAYER) {
                putEntity(tntPrimed, source.getName());
                return;
            }
        }

        BlockPos pos = BlockPos.of(e.getEntity().getLocation().clone().subtract(0.5, 0, 0.5));
        String locReason = locationCache.getIfPresent(pos);
        if (locReason != null) {
            putEntity(tntPrimed, locReason);
        }
    }

    // HangingBreak
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingBreak(HangingBreakEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "hanging");
        if (!section.getBoolean("enable", true)) return;
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS || e.getCause() == HangingBreakEvent.RemoveCause.DEFAULT) return;

        Block hangingPosBlock = e.getEntity().getLocation().getBlock();
        String reason = getLocationReason(hangingPosBlock.getLocation());
        if (reason != null) {
            Material mat = Material.matchMaterial(e.getEntity().getType().name());
            if (mat != null) {
                api.logRemoval("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation(), mat, null);
            } else {
                api.logInteraction("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation());
            }
        }
    }

    // EnderCrystal rigged by entity
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEndCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) return;
        if (e.getDamager() instanceof Player player) {
            putEntity(e.getEntity(), player.getName());
        } else {
            String src = getEntityReason(e.getDamager());
            if (src != null) putEntity(e.getEntity(), src);
            else if (e.getDamager() instanceof Projectile projectile) {
                if (projectile.getShooter() instanceof Player shooter) {
                    putEntity(e.getEntity(), shooter.getName());
                }
            }
        }
    }

    // Hanging hit by entity (item frames & paintings treated separately)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Hanging)) return;
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) return;

        if (e.getEntity() instanceof ItemFrame itemFrame) {
            if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) return;
            if (e.getDamager() instanceof Player player) {
                putEntity(e.getEntity(), player.getName());
                api.logInteraction(player.getName(), itemFrame.getLocation());
                api.logRemoval(player.getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
            } else {
                String cause = getEntityReason(e.getDamager());
                if (cause != null) {
                    String reason = "#" + e.getDamager().getName() + "-" + cause;
                    putEntity(e.getEntity(), reason);
                    api.logRemoval(reason, itemFrame.getLocation(), itemFrame.getItem().getType(), null);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPaintingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Painting painting)) return;
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "painting");
        if (!section.getBoolean("enable", true)) return;

        if (painting.isInvulnerable()) return;

        if (e.getDamager() instanceof Player player) {
            api.logInteraction(player.getName(), painting.getLocation());
            // Для картины нет item-type -> логируем взаимодействие и удаление как interaction/removal с generic
            api.logInteraction(player.getName(), painting.getLocation());
        } else {
            String reason = getEntityReason(e.getDamager());
            if (reason != null) {
                api.logInteraction("#" + e.getDamager().getName() + "-" + reason, painting.getLocation());
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.setCancelled(true);
                    e.setDamage(0.0d);
                    Util.broadcastNearPlayers(e.getEntity().getLocation(), section.getString("alert"));
                }
            }
        }
    }

    // Entity hit by projectile
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityHitByProjectile(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile projectile)) return;
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Player player) {
            putEntity(e.getEntity(), player.getName());
            return;
        }
        String reason = getEntityReason(e.getDamager());
        if (reason != null) {
            putEntity(e.getEntity(), reason);
        } else {
            putEntity(e.getEntity(), e.getDamager().getName());
        }
    }

    // Block ignite
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (e.getIgnitingEntity() != null) {
            if (e.getIgnitingEntity().getType() == EntityType.PLAYER && e.getPlayer() != null) {
                putLocation(e.getBlock().getLocation(), e.getPlayer().getName());
                return;
            }
            String sourceFromEntity = getEntityReason(e.getIgnitingEntity());
            if (sourceFromEntity != null) {
                putLocation(e.getBlock().getLocation(), sourceFromEntity);
                return;
            } else if (e.getIgnitingEntity() instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player player) {
                    putLocation(e.getBlock().getLocation(), player.getName());
                    return;
                }
            }
        }
        if (e.getIgnitingBlock() != null) {
            String sourceFromLoc = getLocationReason(e.getIgnitingBlock().getLocation());
            if (sourceFromLoc != null) {
                putLocation(e.getBlock().getLocation(), sourceFromLoc);
                return;
            }
        }

        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) return;
        if (section.getBoolean("disable-unknown", true)) e.setCancelled(true);
    }

    // Block burn
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBurn(BlockBurnEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) return;
        if (e.getIgnitingBlock() != null) {
            String source = getLocationReason(e.getIgnitingBlock().getLocation());
            if (source != null) {
                putLocation(e.getBlock().getLocation(), source);
                api.logRemoval("#fire-" + source, e.getBlock().getLocation(), e.getBlock().getType(), e.getBlock().getBlockData());
            } else if (section.getBoolean("disable-unknown", true)) {
                e.setCancelled(true);
                Util.broadcastNearPlayers(e.getIgnitingBlock().getLocation(), section.getString("alert"));
            }
        }
    }

    // Projectile hits bomb-like entities
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBombHit(ProjectileHitEvent e) {
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        if (hit instanceof ExplosiveMinecart || e.getEntityType() == EntityType.END_CRYSTAL) {
            ProjectileSource shooter = e.getEntity().getShooter();
            if (shooter instanceof Player player) {
                String src = getEntityReason(e.getEntity());
                if (src != null) putEntity(hit, src);
                else putEntity(hit, player.getName());
            }
        }
    }

    // Explosions
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        List<Block> blockList = e.blockList();
        if (blockList.isEmpty()) return;

        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "entity-explosion");
        if (!section.getBoolean("enable", true)) return;

        String track = getEntityReason(entity);

        // TNT or EnderCrystal
        if (entity instanceof TNTPrimed || entity instanceof EnderCrystal) {
            if (track != null) {
                String reason = "#" + e.getEntityType().name().toLowerCase(Locale.ROOT) + "-" + track;
                logRemovalForBlocks(reason, blockList);
                entityCache.invalidate(entity);
            } else {
                if (!section.getBoolean("disable-unknown", true)) return;
                e.blockList().clear();
                entity.remove();
                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            return;
        }

        // Creeper
        if (entity instanceof Creeper creeper) {
            if (track != null) {
                for (Block b : blockList) api.logRemoval(track, b.getLocation(), b.getType(), b.getBlockData());
            } else {
                LivingEntity target = creeper.getTarget();
                if (target != null) {
                    String reason = "#creeper-" + target.getName();
                    for (Block b : blockList) {
                        api.logRemoval(reason, b.getLocation(), b.getType(), b.getBlockData());
                        putBlock(b, reason);
                    }
                } else {
                    if (!section.getBoolean("disable-unknown")) return;
                    e.blockList().clear();
                    entity.remove();
                    Util.broadcastNearPlayers(e.getLocation(), section.getString("alert"));
                }
            }
            return;
        }

        // Fireball
        if (entity instanceof Fireball) {
            if (track != null) {
                String reason = "#fireball-" + track;
                logRemovalForBlocks(reason, blockList);
                entityCache.invalidate(entity);
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.blockList().clear();
                    entity.remove();
                    Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            return;
        }

        // ExplosiveMinecart
        if (entity instanceof ExplosiveMinecart) {
            BlockPos corner = BlockPos.of(entity.getLocation().clone().subtract(0.5, 0, 0.5));
            String locReason = locationCache.getIfPresent(corner);
            if (locReason != null) {
                String reason = "#tntminecart-" + locReason;
                logRemovalForBlocks(reason, blockList);
            } else if (track != null) {
                String reason = "#tntminecart-" + track;
                logRemovalForBlocks(reason, blockList);
                entityCache.invalidate(entity);
            } else if (section.getBoolean("disable-unknown")) {
                e.blockList().clear();
                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            return;
        }

        if ((track == null || track.isEmpty()) && entity instanceof Mob mob && mob.getTarget() != null) {
            track = mob.getTarget().getName();
        }

        if ((track == null || track.isEmpty()) && entity.getLastDamageCause() instanceof EntityDamageByEntityEvent edbe) {
            track = "#" + entity.getName() + "-" + edbe.getDamager().getName();
        }

        if (track != null && !track.isEmpty()) {
            for (Block b : e.blockList()) {
                api.logRemoval(track, b.getLocation(), b.getType(), b.getBlockData());
            }
        } else if (section.getBoolean("disable-unknown")) {
            e.blockList().clear();
            e.getEntity().remove();
            Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
        }
    }
}
