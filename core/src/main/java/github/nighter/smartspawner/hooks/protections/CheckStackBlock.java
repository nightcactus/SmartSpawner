package github.nighter.smartspawner.hooks.protections;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.hooks.protections.api.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class CheckStackBlock {
    // Check if player can place a block
    public static boolean CanPlayerPlaceBlock(@NotNull final Player player, @NotNull Location location) {

        if(player.isOp() || player.hasPermission("*")) return true;

        if (SmartSpawner.hasGriefPrevention && !GriefPrevention.canPlayerStackClaimBlock(player, location)) return false;
        if (SmartSpawner.hasWorldGuard && !WorldGuard.canPlayerStackBlockInRegion(player, location)) return false;
        if (SmartSpawner.hasLands && !Lands.canPlayerStackClaimBlock(player, location)) return false;
        if (SmartSpawner.hasTowny && !Towny.canPlayerInteractSpawner(player, location)) return false;
        if (SmartSpawner.hasSuperiorSkyblock2 && SuperiorSkyblock2.canPlayerStackBlock(player, location)) return false;
        if (SmartSpawner.hasBentoBox && !BentoBoxAPI.canPlayerStackBlock(player, location)) return false;
        if (SmartSpawner.hasSimpleClaimSystem && !SimpleClaimSystem.canPlayerStackClaimBlock(player, location)) return false;

        return true;
    }
}
