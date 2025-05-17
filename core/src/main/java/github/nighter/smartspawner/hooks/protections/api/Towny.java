package github.nighter.smartspawner.hooks.protections.api;

import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class Towny {

    public static boolean canPlayerInteractSpawner(@NotNull Player player, @NotNull Location location) {
        return PlayerCacheUtil.getCachePermission(player,location, Material.SPAWNER, TownyPermission.ActionType.SWITCH);
    }

    public static boolean canPlayerBreakSpawner(@NotNull Player player, @NotNull Location location) {
        return PlayerCacheUtil.getCachePermission(player,location, Material.SPAWNER, TownyPermission.ActionType.BUILD) &&
                PlayerCacheUtil.getCachePermission(player,location, Material.SPAWNER, TownyPermission.ActionType.DESTROY);
    }

}