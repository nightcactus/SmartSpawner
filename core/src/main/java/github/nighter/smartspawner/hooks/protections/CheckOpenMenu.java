package github.nighter.smartspawner.hooks.protections;


import github.nighter.smartspawner.hooks.protections.api.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import github.nighter.smartspawner.SmartSpawner;

public class CheckOpenMenu {
    // Check if player can open a menu
    public static boolean CanPlayerOpenMenu(@NotNull final Player player, @NotNull Location location) {

        if(player.isOp() || player.hasPermission("*")) return true;

        if (SmartSpawner.hasGriefPrevention && !GriefPrevention.canPlayerOpenMenuOnClaim(player, location)) return false;
        if (SmartSpawner.hasWorldGuard && !WorldGuard.canPlayerInteractInRegion(player, location)) return false;
        if (SmartSpawner.hasLands && !Lands.CanPlayerInteractContainer(player, location)) return false;
        if (SmartSpawner.hasTowny && !Towny.canPlayerInteractSpawner(player, location)) return false;
        if (SmartSpawner.hasSuperiorSkyblock2 && SuperiorSkyblock2.canPlayerOpenMenu(player, location)) return false;
        if (SmartSpawner.hasBentoBox && !BentoBoxAPI.canPlayerOpenMenu(player, location)) return false;
        if (SmartSpawner.hasSimpleClaimSystem && !SimpleClaimSystem.canPlayerOpenMenuOnClaim(player, location)) return false;

        return true;
    }

    public static boolean hasProtectedBlockAccess(@NotNull final Player player, @NotNull Location location) {

        if(player.isOp() || player.hasPermission("*")) return true;

        if (SmartSpawner.hasBlockLocker && !BlockLocker.hasAccess(player, location)) return false;

        return true;
    }

}
