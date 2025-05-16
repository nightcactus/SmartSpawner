package github.nighter.smartspawner.hooks.protections;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.hooks.protections.api.*;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class CheckBreakBlock {
    // Check if player can break a block
    public static boolean CanPlayerBreakBlock(@NotNull final Player player, @NotNull Location location) {

        if(player.isOp() || player.hasPermission("*")) return true;

        if (SmartSpawner.hasBlockLocker && !BlockLocker.hasOwnerAccess(player, location)) return false;
        if (SmartSpawner.hasGriefPrevention && !GriefPrevention.canPlayerBreakClaimBlock(player, location)) return false;
        if (SmartSpawner.hasWorldGuard && !WorldGuard.canPlayerBreakBlockInRegion(player, location)) return false;
        if (SmartSpawner.hasLands && !Lands.canPlayerBreakClaimBlock(player, location)) return false;
        if (SmartSpawner.hasTowny && !Towny.canPlayerInteractSpawner(player, location)) return false;
        if (SmartSpawner.hasSimpleClaimSystem && !SimpleClaimSystem.canPlayerBreakClaimBlock(player, location)) return false;
        return true;
    }

}
