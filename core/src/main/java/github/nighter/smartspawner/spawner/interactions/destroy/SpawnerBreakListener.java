package github.nighter.smartspawner.spawner.interactions.destroy;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.events.SpawnerPlayerBreakEvent;
import github.nighter.smartspawner.extras.HopperHandler;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.hooks.protections.CheckBreakBlock;
import github.nighter.smartspawner.spawner.properties.SpawnerManager;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.spawner.item.SpawnerItemFactory;
import github.nighter.smartspawner.spawner.utils.SpawnerFileHandler;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * Handles spawner break interactions, including permissions checking,
 * silk touch requirements, and drop processing.
 */
public class SpawnerBreakListener implements Listener {
    private static final int MAX_STACK_SIZE = 64;

    private final SmartSpawner plugin;
    private final MessageService messageService;
    private final SpawnerManager spawnerManager;
    private final HopperHandler hopperHandler;
    private final SpawnerItemFactory spawnerItemFactory;
    private final SpawnerFileHandler spawnerFileHandler;

    public SpawnerBreakListener(SmartSpawner plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
        this.spawnerManager = plugin.getSpawnerManager();
        this.hopperHandler = plugin.getHopperHandler();
        this.spawnerItemFactory = plugin.getSpawnerItemFactory();
        this.spawnerFileHandler = plugin.getSpawnerFileHandler();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerBreak(BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final Location location = block.getLocation();

        // Quick return if not a spawner
        if (block.getType() != Material.SPAWNER) {
            return;
        }

        // Protection plugin integration
        if (!CheckBreakBlock.CanPlayerBreakBlock(player, location)) {
            event.setCancelled(true);
            return;
        }

        // Protection plugin integration
        if (!CheckBreakBlock.hasProtectedBlockOwnerAccess(player, location)) {
            event.setCancelled(true);
            return;
        }

        // Feature toggle check
        if (!plugin.getConfig().getBoolean("spawner_break.enabled", true)) {
            event.setCancelled(true);
            return;
        }

        // Get spawner data and check if it's a natural spawner
        final SpawnerData spawner = spawnerManager.getSpawnerByLocation(location);

        // Check if natural spawner interaction is disabled
        if (!plugin.getConfig().getBoolean("natural_spawner.breakable", true)) {
            if (spawner == null) {
                // Handle like vanilla - break with no drops
                block.setType(Material.AIR);
                event.setCancelled(true);
                messageService.sendMessage(player, "natural_spawner_break_blocked");
                return;
            }
        }

        // Permission check
        if (!player.hasPermission("smartspawner.break")) {
            event.setCancelled(true);
            messageService.sendMessage(player, "spawner_break_no_permission");
            return;
        }

        // Handle spawner based on type
        if (spawner != null) {
            handleSpawnerBreak(block, spawner, player);
            // Clean up associated tasks
            plugin.getRangeChecker().stopSpawnerTask(spawner);
        } else {
            // Fallback to vanilla spawner handling
            CreatureSpawner creatureSpawner = (CreatureSpawner) block.getState();
            if(callAPIEvent(player, block.getLocation(), 1)) {
                event.setCancelled(true);
                return;
            }
            handleVanillaSpawnerBreak(block, creatureSpawner, player);
        }

        // Cancel vanilla event as we handle it ourselves
        event.setCancelled(true);

        // Clean up associated hopper if present
        cleanupAssociatedHopper(block);
    }

    private void handleSpawnerBreak(Block block, SpawnerData spawner, Player player) {
        Location location = block.getLocation();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!validateBreakConditions(player, tool, spawner)) {
            return;
        }

        plugin.getSpawnerGuiViewManager().closeAllViewersInventory(spawner);

        // Process drops based on crouching state
        SpawnerBreakResult result = processDrops(player, location, spawner, player.isSneaking(), block);

        if (result.isSuccess()) {
            // Handle tool durability
            if (player.getGameMode() != GameMode.CREATIVE) {
                reduceDurability(tool, player, result.getDurabilityLoss());
            }
        }
    }

    private void handleVanillaSpawnerBreak(Block block, CreatureSpawner creatureSpawner, Player player) {
        Location location = block.getLocation();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!validateBreakConditions(player, tool, null)) {
            return;
        }

        // Get entity type and create appropriate item using the factory
        EntityType entityType = creatureSpawner.getSpawnedType();
        ItemStack spawnerItem = spawnerItemFactory.createSpawnerItem(entityType);

        // Check if direct to inventory is enabled
        boolean directToInventory = plugin.getConfig().getBoolean("spawner_break.direct_to_inventory", false);

        // Drop item or add to inventory based on configuration
        World world = location.getWorld();
        if (world != null) {
            block.setType(Material.AIR);

            if (directToInventory) {
                // Add directly to inventory
                giveSpawnersToPlayer(player, 1, spawnerItem);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            } else {
                // Drop naturally in the world
                world.dropItemNaturally(location, spawnerItem);
            }

            reduceDurability(tool, player, plugin.getConfig().getInt("spawner_break.durability_loss", 1));
        }
    }

    private boolean validateBreakConditions(Player player, ItemStack tool, SpawnerData spawner) {
        // Skip validation for creative mode players
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }

        if (!player.hasPermission("smartspawner.break")) {
            messageService.sendMessage(player, "spawner_break_no_permission");
            return false;
        }

        if (!isValidTool(tool)) {
            messageService.sendMessage(player, "spawner_break_required_tools");
            return false;
        }

        // Silk touch validation
        if (plugin.getConfig().getBoolean("spawner_break.silk_touch.required", true)) {
            int requiredLevel = plugin.getConfig().getInt("spawner_break.silk_touch.level", 1);
            if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) < requiredLevel) {
                messageService.sendMessage(player, "spawner_break_silk_touch_required");
                return false;
            }
        }

        return true;
    }

    private SpawnerBreakResult processDrops(Player player, Location location, SpawnerData spawner, boolean isCrouching, Block spawnerBlock) {
        final int currentStackSize = spawner.getStackSize();
        final int durabilityLoss = plugin.getConfig().getInt("spawner_break.durability_loss", 1);

        World world = location.getWorld();
        if (world == null) {
            return new SpawnerBreakResult(false, 0, durabilityLoss);
        }

        // Create template item for spawner drops
        EntityType entityType = spawner.getEntityType();
        ItemStack template = spawnerItemFactory.createSpawnerItem(entityType);

        int dropAmount;

        if (isCrouching) {
            // Crouching behavior: Drop up to MAX_STACK_SIZE (64)
            if (currentStackSize <= MAX_STACK_SIZE) {
                // If stack is 64 or less, drop all and remove spawner
                dropAmount = currentStackSize;
                if(callAPIEvent(player, location, dropAmount)) return new SpawnerBreakResult(false, dropAmount, 0);

            } else {
                // If stack is more than 64, drop 64 and reduce stack
                dropAmount = MAX_STACK_SIZE;
                if(callAPIEvent(player, location, dropAmount)) return new SpawnerBreakResult(false, dropAmount, 0);
                spawner.setStackSize(currentStackSize - MAX_STACK_SIZE);
            }
        } else {
            // Normal behavior: Drop 1 spawner
            dropAmount = 1;
            if(callAPIEvent(player, location, dropAmount)) return new SpawnerBreakResult(false, dropAmount, 0);
            spawner.decreaseStackSizeByOne();
        }

        if(dropAmount == currentStackSize) {
            cleanupSpawner(spawnerBlock, spawner);
        } else {
            spawnerManager.markSpawnerModified(spawner.getSpawnerId());
        }

        // Check if direct to inventory is enabled
        boolean directToInventory = plugin.getConfig().getBoolean("spawner_break.direct_to_inventory", false);

        if (directToInventory) {
            // Add directly to inventory
            giveSpawnersToPlayer(player, dropAmount, template);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
        } else {
            // Drop the items individually (no batch operations)
            template.setAmount(dropAmount);
            world.dropItemNaturally(location, template.clone());
        }

        return new SpawnerBreakResult(true, dropAmount, durabilityLoss);
    }

    private boolean callAPIEvent(Player player, Location location, int dropAmount) {
        if(SpawnerPlayerBreakEvent.getHandlerList().getRegisteredListeners().length != 0) {
            SpawnerPlayerBreakEvent e = new SpawnerPlayerBreakEvent(player, location, dropAmount);
            Bukkit.getPluginManager().callEvent(e);
            return e.isCancelled();
        }
        return false;
    }

    private void reduceDurability(ItemStack tool, Player player, int durabilityLoss) {
        if (tool.getType().getMaxDurability() == 0) {
            return;
        }

        ItemMeta meta = tool.getItemMeta();
        if (meta instanceof Damageable) {
            Damageable damageable = (Damageable) meta;
            int currentDurability = damageable.getDamage();
            int newDurability = currentDurability + durabilityLoss;

            if (newDurability >= tool.getType().getMaxDurability()) {
                // Tool breaks
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDurability);
                tool.setItemMeta(meta);
            }
        }
    }

    private void cleanupSpawner(Block block, SpawnerData spawner) {
        // Stop the spawner and remove the block
        spawner.setSpawnerStop(true);
        block.setType(Material.AIR);

        // Get ID before removing from manager
        String spawnerId = spawner.getSpawnerId();

        // Remove from memory first
        spawnerManager.removeSpawner(spawnerId);

        // Mark for deletion instead of immediately deleting
        spawnerFileHandler.markSpawnerDeleted(spawnerId);
    }

    private void cleanupAssociatedHopper(Block block) {
        Block blockBelow = block.getRelative(BlockFace.DOWN);
        if (blockBelow.getType() == Material.HOPPER && hopperHandler != null) {
            hopperHandler.stopHopperTask(blockBelow.getLocation());
        }
    }

    private boolean isValidTool(ItemStack tool) {
        if (tool == null) {
            return false;
        }
        return plugin.getConfig().getStringList("spawner_break.required_tools").contains(tool.getType().name());
    }

    // Method to add spawners to player inventory similar to SpawnerStackerHandler
    private void giveSpawnersToPlayer(Player player, int amount, ItemStack template) {
        final int MAX_STACK_SIZE = 64;

        // Create a new spawner item with proper amount
        ItemStack itemToGive = template.clone();
        itemToGive.setAmount(Math.min(amount, MAX_STACK_SIZE));

        // Try to add to inventory
        Map<Integer, ItemStack> failedItems = player.getInventory().addItem(itemToGive);

        // Drop any items that couldn't fit
        if (!failedItems.isEmpty()) {
            for (ItemStack failedItem : failedItems.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), failedItem);
            }
            messageService.sendMessage(player, "inventory_full_items_dropped");
        }

        // Update inventory
        player.updateInventory();
    }

    private static class SpawnerBreakResult {
        @Getter private final boolean success;
        private final int droppedAmount;
        private final int baseDurabilityLoss;

        public SpawnerBreakResult(boolean success, int droppedAmount, int baseDurabilityLoss) {
            this.success = success;
            this.droppedAmount = droppedAmount;
            this.baseDurabilityLoss = baseDurabilityLoss;
        }

        public int getDurabilityLoss() {
            return droppedAmount * baseDurabilityLoss;
        }
    }

    @EventHandler
    public void onSpawnerDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // Quick return if not a spawner
        if (block.getType() != Material.SPAWNER) {
            return;
        }

        // Skip feedback in creative mode
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType() == Material.AIR) {
            return;
        }

        // Warn about breaking
        SpawnerData spawner = spawnerManager.getSpawnerByLocation(block.getLocation());
        if (spawner != null) {
            messageService.sendMessage(player, "spawner_break_warning");
        }

        // Provide appropriate feedback based on tool and permissions
        if (isValidTool(tool)) {
            if (plugin.getConfig().getBoolean("spawner_break.silk_touch.required", true)) {
                int requiredLevel = plugin.getConfig().getInt("spawner_break.silk_touch.level", 1);
                if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) < requiredLevel) {
                    messageService.sendMessage(player, "spawner_break_silk_touch_required");
                    return;
                }
            }

            if (!player.hasPermission("smartspawner.break")) {
                messageService.sendMessage(player, "spawner_break_no_permission");
            }

        } else {
            // Inform about required tools
            messageService.sendMessage(player, "spawner_break_required_tools");
        }
    }
}