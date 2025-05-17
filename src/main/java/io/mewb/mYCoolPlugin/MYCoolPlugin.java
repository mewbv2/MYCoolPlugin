package io.mewb.mYCoolPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MYCoolPlugin extends JavaPlugin implements CommandExecutor {

    // Fields for the AOE lock effect
    private final Set<UUID> lockedPlayerIds = new HashSet<>();
    private final Map<UUID, Location> playerLockLocations = new HashMap<>();
    private final Map<UUID, Long> playerUnlockTimes = new HashMap<>();
    private LockEffectTask currentLockTask = null;

    @Override
    public void onEnable() {
        if (this.getCommand("farout") != null) {
            this.getCommand("farout").setExecutor(this);
        } else {
            getLogger().warning("Command 'farout' not found! Check plugin.yml.");
        }
        // Register the new command
        if (this.getCommand("aoelock") != null) {
            this.getCommand("aoelock").setExecutor(this);
        } else {
            getLogger().warning("Command 'aoelock' not found! Check plugin.yml.");
        }
        getLogger().info("MYCoolPlugin has been enabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("farout")) {
            // ... (farout command logic from previous example)
            Player furthestPlayer = null;
            double maxDistanceSquared = -1.0;

            for (Player currentPlayer : Bukkit.getOnlinePlayers()) {
                Location playerLocation = currentPlayer.getLocation();
                double playerX = playerLocation.getX();
                double playerZ = playerLocation.getZ();
                double distanceSquared = (playerX * playerX) + (playerZ * playerZ);

                if (furthestPlayer == null || distanceSquared > maxDistanceSquared) {
                    maxDistanceSquared = distanceSquared;
                    furthestPlayer = currentPlayer;
                }
            }

            if (furthestPlayer != null) {
                double actualDistance = Math.sqrt(maxDistanceSquared);
                sender.sendMessage("The furthest player from (0,0) in the XZ plane is " +
                                   furthestPlayer.getName() +
                                   ", at a distance of " + String.format("%.2f", actualDistance) + " blocks.");
            } else {
                sender.sendMessage("No players are currently online.");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("aoelock")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be run by a player.");
                return true;
            }
            Player caster = (Player) sender;
            Location center = caster.getLocation();
            double launchPower = 1.0; // How high to launch them
            double lockHeightOffset = 1.5; // How many blocks above their original spot to lock them
            long durationMillis = 10 * 1000; // 10 seconds
            double radiusSquared = 5 * 5; // 5 block radius (squared for efficiency)
            boolean playerAffected = false;

            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(caster.getUniqueId())) {
                    continue; // Don't affect the caster
                }

                if (target.getWorld().equals(center.getWorld()) &&
                    target.getLocation().distanceSquared(center) <= radiusSquared) {

                    // Store initial location for lock point calculation
                    Location initialTargetLoc = target.getLocation().clone();

                    // Launch the player
                    target.setVelocity(target.getVelocity().clone().setY(launchPower));

                    // Calculate and store lock location (slightly above their starting point)
                    Location targetLockLocation = new Location(
                            initialTargetLoc.getWorld(),
                            initialTargetLoc.getX(),
                            initialTargetLoc.getY() + lockHeightOffset, // Lock them above their original spot
                            initialTargetLoc.getZ(),
                            initialTargetLoc.getYaw(),
                            initialTargetLoc.getPitch()
                    );

                    lockedPlayerIds.add(target.getUniqueId());
                    playerLockLocations.put(target.getUniqueId(), targetLockLocation);
                    playerUnlockTimes.put(target.getUniqueId(), System.currentTimeMillis() + durationMillis);
                    playerAffected = true;
                    target.sendMessage("You've been caught in an AOE lock!");
                }
            }

            if (playerAffected) {
                caster.sendMessage("AOE lock initiated!");
                // Start or ensure the task is running
                if (currentLockTask == null || currentLockTask.isCancelled()) {
                    currentLockTask = new LockEffectTask();
                    currentLockTask.runTaskTimer(this, 0L, 1L); // Run every tick
                }
            } else {
                caster.sendMessage("No players in range to lock.");
            }
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (currentLockTask != null && !currentLockTask.isCancelled()) {
            currentLockTask.cancel();
        }
        lockedPlayerIds.clear();
        playerLockLocations.clear();
        playerUnlockTimes.clear();
        getLogger().info("MYCoolPlugin has been disabled.");
    }

    // Inner class for the lock effect task
    private class LockEffectTask extends BukkitRunnable {
        @Override
        public void run() {
            if (lockedPlayerIds.isEmpty()) {
                this.cancel(); // No more players to manage, cancel task
                currentLockTask = null;
                return;
            }

            long currentTime = System.currentTimeMillis();
            Iterator<UUID> iterator = lockedPlayerIds.iterator();

            while (iterator.hasNext()) {
                UUID playerId = iterator.next();
                Player player = Bukkit.getPlayer(playerId);

                if (player == null || !player.isOnline() || currentTime >= playerUnlockTimes.getOrDefault(playerId, 0L)) {
                    // Player logged off or lock expired
                    if (player != null && player.isOnline()) {
                        player.setFallDistance(0f); // Reset fall distance to prevent damage
                        player.sendMessage("You are no longer locked.");
                    }
                    iterator.remove(); // Remove from active set
                    playerLockLocations.remove(playerId);
                    playerUnlockTimes.remove(playerId);
                    continue;
                }

                Location lockedLoc = playerLockLocations.get(playerId);
                if (lockedLoc != null) {
                    // Teleport to maintain position and reset velocity to prevent drifting/gravity
                    player.teleport(lockedLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    player.setVelocity(new Vector(0, 0, 0));

                    // Spawn particles around the player
                    // center particles slightly above feet
                    Location particleLoc = lockedLoc.clone().add(0, 0.5, 0);
                    player.getWorld().spawnParticle(Particle.ENCHANT, particleLoc, 15, 0.4, 0.6, 0.4, 0.01);
                }
            }

            // If iteration made the set empty, cancel
            if (lockedPlayerIds.isEmpty()) {
                this.cancel();
                currentLockTask = null;
            }
        }
    }
}