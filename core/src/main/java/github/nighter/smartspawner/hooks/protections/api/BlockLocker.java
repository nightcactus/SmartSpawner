package github.nighter.smartspawner.hooks.protections.api;

import nl.rutgerkok.blocklocker.BlockLockerAPIv2;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BlockLocker {

    public static boolean hasAccess(@NotNull Player player, @NotNull Location location) {
        return BlockLockerAPIv2.isAllowed(player,location.getBlock(),true);
    }

    public static boolean hasOwnerAccess(@NotNull Player player, @NotNull Location location) {
        if (!BlockLockerAPIv2.isProtected(location.getBlock())) return true;
        return BlockLockerAPIv2.getOwner(location.getBlock()).get().getUniqueId().equals(player.getUniqueId());
    }
}
