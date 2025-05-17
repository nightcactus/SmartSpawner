package github.nighter.smartspawner.spawner.gui.main;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.holders.SpawnerMenuHolder;
import github.nighter.smartspawner.hooks.protections.CheckBreakBlock;
import github.nighter.smartspawner.hooks.protections.CheckStackBlock;
import github.nighter.smartspawner.language.LanguageManager;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.nms.ParticleWrapper;
import github.nighter.smartspawner.spawner.gui.stacker.SpawnerStackerUI;
import github.nighter.smartspawner.spawner.gui.storage.SpawnerStorageUI;
import github.nighter.smartspawner.spawner.gui.synchronization.SpawnerGuiViewManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handles all click interactions within the spawner menu interface.
 * Processes various actions based on clicked items and manages related UIs.
 */
public class SpawnerMenuAction implements Listener {
    private static final Set<Material> SPAWNER_INFO_MATERIALS = EnumSet.of(
            Material.PLAYER_HEAD,
            Material.SPAWNER,
            Material.ZOMBIE_HEAD,
            Material.SKELETON_SKULL,
            Material.WITHER_SKELETON_SKULL,
            Material.CREEPER_HEAD,
            Material.PIGLIN_HEAD
    );
    private final SmartSpawner plugin;
    private final SpawnerMenuUI spawnerMenuUI;
    private final SpawnerStackerUI spawnerStackerUI;
    private final SpawnerStorageUI spawnerStorageUI;
    private final SpawnerGuiViewManager spawnerGuiViewManager;
    private final LanguageManager languageManager;
    private final MessageService messageService;

    // Add cooldown system properties
    private final Map<UUID, Long> sellCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInfoClickTime = new ConcurrentHashMap<>();

    // Cache for cooldown configuration to avoid repeated config lookups
    private boolean cooldownEnabled = true;
    private long cooldownDurationMs = 3000; // Default 3s in milliseconds
    private long lastConfigReloadTime = 0;
    private static final long CONFIG_CACHE_TTL = 60000; // Cache config for 60 seconds

    public SpawnerMenuAction(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerMenuUI = plugin.getSpawnerMenuUI();
        this.spawnerStackerUI = plugin.getSpawnerStackerUI();
        this.spawnerStorageUI = plugin.getSpawnerStorageUI();
        this.spawnerGuiViewManager = plugin.getSpawnerGuiViewManager();
        this.languageManager = plugin.getLanguageManager();
        this.messageService = plugin.getMessageService();

        // Initialize cooldown settings from config
        updateCooldownSettings();
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof SpawnerMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);
        SpawnerData spawner = holder.getSpawnerData();

        // Verify click was in the actual menu and not player inventory
        if (event.getClickedInventory() == null ||
                !(event.getClickedInventory().getHolder() instanceof SpawnerMenuHolder)) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        Material itemType = clickedItem.getType();

        if (itemType == Material.CHEST) {
            handleChestClick(player, spawner);
        } else if (SPAWNER_INFO_MATERIALS.contains(itemType)) {
            handleSpawnerInfoClick(player, spawner, event.getClick());
        } else if (itemType == Material.EXPERIENCE_BOTTLE) {
            handleExpBottleClick(player, spawner, false);
        }
    }

    public void handleChestClick(Player player, SpawnerData spawner) {
        String title = languageManager.getGuiTitle("gui_title_storage");
        Inventory pageInventory = spawnerStorageUI.createInventory(spawner, title, 1, -1);

        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        player.closeInventory();
        player.openInventory(pageInventory);
    }

    private void handleSpawnerInfoClick(Player player, SpawnerData spawner, ClickType clickType) {
        if (isClickTooFrequent(player)) {
            return;
        }

        // Determine which mode we're in based on shop integration
        boolean hasShopIntegration = plugin.hasShopIntegration() && player.hasPermission("smartspawner.sellall");

        // Handle clicks based on shop integration mode
        if (hasShopIntegration) {
            // Standard mode: Left click for selling/XP, right click for stacker
            if (clickType == ClickType.LEFT) {
                // Collect EXP and sell items in storage
                boolean hasExp = handleExpBottleClick(player, spawner, true);
                boolean soldItems = handleSellAllItems(player, spawner);

                if (soldItems && spawner.getIsAtCapacity()) {
                    spawner.setIsAtCapacity(false);
                }
            } else if (clickType == ClickType.RIGHT) {
                // Check stacker permission
                if (!player.hasPermission("smartspawner.stack")) {
                    messageService.sendMessage(player, "no_permission");
                    return;
                }

                if (!CheckStackBlock.CanPlayerPlaceBlock(player, spawner.getSpawnerLocation())) {
                    messageService.sendMessage(player, "spawner_protected");
                    return;
                }

                if (!CheckBreakBlock.hasProtectedBlockOwnerAccess(player, spawner.getSpawnerLocation())) {
                    messageService.sendMessage(player, "spawner_protected");
                    return;
                }

                // Open stacker GUI
                spawnerStackerUI.openStackerGui(player, spawner);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            }
        } else {
            // Check stacker permission
            if (!player.hasPermission("smartspawner.stack")) {
                messageService.sendMessage(player, "no_permission");
                return;
            }

            if (!CheckStackBlock.CanPlayerPlaceBlock(player, spawner.getSpawnerLocation())) {
                messageService.sendMessage(player, "spawner_protected");
                return;
            }

            if (!CheckBreakBlock.hasProtectedBlockOwnerAccess(player, spawner.getSpawnerLocation())) {
                messageService.sendMessage(player, "spawner_protected");
                return;
            }

            // Open stacker GUI
            spawnerStackerUI.openStackerGui(player, spawner);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }

    private boolean isClickTooFrequent(Player player) {
        long now = System.currentTimeMillis();
        long last = lastInfoClickTime.getOrDefault(player.getUniqueId(), 0L);
        lastInfoClickTime.put(player.getUniqueId(), now);
        return (now - last) < 300; // 300ms threshold
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastInfoClickTime.remove(event.getPlayer().getUniqueId());
        sellCooldowns.remove(event.getPlayer().getUniqueId());
    }

    private boolean handleSellAllItems(Player player, SpawnerData spawner) {
        if (!plugin.hasShopIntegration()) return false;

        // Permission check
        if (!player.hasPermission("smartspawner.sellall")) {
            messageService.sendMessage(player, "no_permission");
            return false;
        }

        // Check if cooldowns are up to date
        checkConfigReload();

        // Skip cooldown check if disabled
        if (!cooldownEnabled) {
            return plugin.getShopIntegration().sellAllItems(player, spawner);
        }

        // Anti-spam cooldown check
        long remainingTime = getRemainingCooldownTime(player);
        if (remainingTime > 0) {
            sendCooldownMessage(player, remainingTime);
            return false;
        }

        // Update cooldown timestamp before processing
        updateCooldown(player);

        // Clean up old cooldowns periodically (e.g., every 10th call)
        if (Math.random() < 0.1) {
            clearOldCooldowns();
        }

        // Process the sale through shop integration
        return plugin.getShopIntegration().sellAllItems(player, spawner);
    }

    /**
     * Check if config needs to be reloaded and update the cached settings if needed
     */
    private void checkConfigReload() {
        long now = System.currentTimeMillis();
        // Only update settings if our cache has expired
        if (now - lastConfigReloadTime > CONFIG_CACHE_TTL) {
            updateCooldownSettings();
            lastConfigReloadTime = now;
        }
    }

    /**
     * Update cached cooldown configuration settings
     */
    public void updateCooldownSettings() {
        // Read enabled setting
        cooldownEnabled = plugin.getConfig().getBoolean("sell_cooldown.enabled", true);

        // Read duration and convert from ticks to milliseconds
        long durationTicks = plugin.getTimeFromConfig("sell_cooldown.duration", "3s");
        cooldownDurationMs = durationTicks * 50; // 1 tick = 50ms
    }

    /**
     * Calculate and return the remaining cooldown time in milliseconds
     * @param player The player to check
     * @return Remaining cooldown time in milliseconds, 0 if no cooldown
     */
    private long getRemainingCooldownTime(Player player) {
        if (!cooldownEnabled || cooldownDurationMs <= 0) {
            return 0;
        }

        long lastSellTime = sellCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastSellTime;

        return elapsed >= cooldownDurationMs ? 0 : cooldownDurationMs - elapsed;
    }

    /**
     * Send a cooldown message to the player with the remaining time
     * @param player The player to send the message to
     * @param remainingTimeMs The remaining cooldown time in milliseconds
     */
    private void sendCooldownMessage(Player player, long remainingTimeMs) {
        // Convert remaining time to seconds (rounded up)
        int remainingSeconds = (int) Math.ceil(remainingTimeMs / 1000.0);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("seconds", String.valueOf(remainingSeconds));
        placeholders.put("time", formatRemainingTime(remainingTimeMs));

        messageService.sendMessage(player, "shop.sell_cooldown", placeholders);
    }

    /**
     * Format the remaining time in a human-readable format
     * @param milliseconds The time in milliseconds
     * @return A formatted string representing the time
     */
    private String formatRemainingTime(long milliseconds) {
        // For very short times, show in milliseconds
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }

        // For times under a minute, show in seconds with decimal precision
        if (milliseconds < 60000) {
            return String.format("%.1fs", milliseconds / 1000.0);
        }

        // For longer times, format as minutes and seconds
        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(minutes);

        return String.format("%dm %ds", minutes, seconds);
    }

    private void updateCooldown(Player player) {
        sellCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void clearOldCooldowns() {
        if (!cooldownEnabled || cooldownDurationMs <= 0) {
            // If cooldown is disabled, clear all entries
            sellCooldowns.clear();
            return;
        }

        long currentTime = System.currentTimeMillis();
        long expirationThreshold = cooldownDurationMs * 2; // Keep entries for twice the cooldown duration

        sellCooldowns.entrySet().removeIf(entry ->
                (currentTime - entry.getValue()) > expirationThreshold);
    }

    public boolean isSellCooldownActive(Player player) {
        return getRemainingCooldownTime(player) > 0;
    }

    public void updateSellCooldown(Player player) {
        updateCooldown(player);
    }

    /**
     * Get the formatted remaining cooldown time for a player
     * @param player The player to check
     * @return Formatted time string or null if no active cooldown
     */
    public String getRemainingCooldownTimeFormatted(Player player) {
        long remaining = getRemainingCooldownTime(player);
        return remaining > 0 ? formatRemainingTime(remaining) : null;
    }

    public boolean handleExpBottleClick(Player player, SpawnerData spawner, boolean isSell) {
        if (isClickTooFrequent(player) && !isSell) {
            return false;
        }

        int exp = spawner.getSpawnerExp();

        if (exp <= 0 && !isSell) {
            messageService.sendMessage(player, "no_exp");
            return false;

        }

        int initialExp = exp;
        int expUsedForMending = 0;

        // Apply mending first if enabled
        if (plugin.getConfig().getBoolean("spawner_properties.default.allow_exp_mending")) {
            expUsedForMending = applyMendingFromExp(player, exp);
            exp -= expUsedForMending;
        }

        // Give remaining exp to player
        if (exp > 0) {
            player.giveExp(exp);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }

        // Reset spawner exp and update menu
        spawner.setSpawnerExp(0);
        spawnerMenuUI.openSpawnerMenu(player, spawner, true);

        // Update all viewers instead of just current player
        spawnerGuiViewManager.updateSpawnerMenuViewers(spawner);

        // Update spawner capacity status
        if (spawner.getSpawnerExp() < spawner.getMaxStoredExp()) {
            if (spawner.getIsAtCapacity()) {
                spawner.setIsAtCapacity(false);
            }
        }

        // Send appropriate message based on exp distribution
        sendExpCollectionMessage(player, initialExp, expUsedForMending);

        return true;
    }

    private int applyMendingFromExp(Player player, int availableExp) {
        if (availableExp <= 0) {
            return 0;
        }

        int expUsed = 0;
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> itemsToCheck = Arrays.asList(
                inventory.getItemInMainHand(),
                inventory.getItemInOffHand(),
                inventory.getHelmet(),
                inventory.getChestplate(),
                inventory.getLeggings(),
                inventory.getBoots()
        );

        for (ItemStack item : itemsToCheck) {
            if (availableExp <= 0) {
                break;
            }

            if (item == null || item.getType() == Material.AIR ||
                    !item.getEnchantments().containsKey(Enchantment.MENDING)) {
                continue;
            }

            if (!(item.getItemMeta() instanceof Damageable damageable) || damageable.getDamage() <= 0) {
                continue;
            }

            // Calculate repair amount based on available exp
            int damage = damageable.getDamage();
            int durabilityToRepair = Math.min(damage, availableExp * 2);
            int expNeeded = (durabilityToRepair + 1) / 2; // Round up for partial repairs

            if (expNeeded <= 0) {
                continue;
            }

            // Apply repair and track exp usage
            int actualExpUsed = Math.min(expNeeded, availableExp);
            int actualRepair = actualExpUsed * 2;

            // Ensure damage value does not go negative
            int newDamage = Math.max(0, damage - actualRepair);

            Damageable meta = (Damageable) item.getItemMeta();
            meta.setDamage(newDamage);
            item.setItemMeta(meta);

            availableExp -= actualExpUsed;
            expUsed += actualExpUsed;

            // Visual and sound effects for mending
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f);
            player.spawnParticle(ParticleWrapper.VILLAGER_HAPPY, player.getLocation().add(0, 1, 0), 5);
        }

        return expUsed;
    }

    private void sendExpCollectionMessage(Player player, int totalExp, int mendingExp) {
        Map<String, String> placeholders = new HashMap<>();

        if (mendingExp > 0) {
            int remainingExp = totalExp - mendingExp;
            placeholders.put("exp_mending", languageManager.formatNumber(mendingExp));
            placeholders.put("exp", languageManager.formatNumber(remainingExp));
            messageService.sendMessage(player, "exp_collected_with_mending", placeholders);
        } else {
            if (totalExp > 0) {
                placeholders.put("exp", plugin.getLanguageManager().formatNumber(totalExp));
                messageService.sendMessage(player, "exp_collected", placeholders);
            }
        }
    }
}