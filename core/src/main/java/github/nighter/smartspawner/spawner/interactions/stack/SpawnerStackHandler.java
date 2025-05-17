package github.nighter.smartspawner.spawner.interactions.stack;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.events.SpawnerStackEvent;
import github.nighter.smartspawner.hooks.protections.CheckBreakBlock;
import github.nighter.smartspawner.hooks.protections.CheckOpenMenu;
import github.nighter.smartspawner.hooks.protections.CheckStackBlock;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.nms.ParticleWrapper;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.language.LanguageManager;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.utils.SpawnerTypeChecker;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpawnerStackHandler {
    private static final Pattern ECONOMY_SHOP_GUI_PATTERN = Pattern.compile("§9§l([A-Za-z]+(?: [A-Za-z]+)?) §rSpawner");
    private static final long STACK_COOLDOWN = 250L; // 250ms cooldown between stacks

    // Cache of compiled regex patterns for entity name extraction
    private Pattern cachedEntityNamePattern = null;
    private final SmartSpawner plugin;
    private final LanguageManager languageManager;
    private final MessageService messageService;
    private final Map<UUID, Long> lastStackTime;
    private final Map<Location, UUID> stackLocks;

    public SpawnerStackHandler(SmartSpawner plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.messageService = plugin.getMessageService();
        this.lastStackTime = new ConcurrentHashMap<>();
        this.stackLocks = new ConcurrentHashMap<>();

        // Start cleanup task
        startCleanupTask();
    }

    private void startCleanupTask() {
        Scheduler.runTaskTimer(() -> {
            long now = System.currentTimeMillis();
            // Clear entries older than 10 seconds
            lastStackTime.entrySet().removeIf(entry -> now - entry.getValue() > 10000);
            // Remove locks for offline players
            stackLocks.entrySet().removeIf(entry -> plugin.getServer().getPlayer(entry.getValue()) == null);
        }, 200L, 200L); // Run every 10 seconds
    }

    public void handleSpawnerStacking(Player player, Block block, SpawnerData spawnerData, ItemStack itemInHand) {
        // Anti-spam check
        if (isOnCooldown(player)) {
            return;
        }

        // Try to acquire lock
        if (!acquireStackLock(player, block.getLocation())) {
            return;
        }

        try {
            handleSpawnerStack(player, spawnerData, itemInHand, player.isSneaking());
        } finally {
            releaseStackLock(block.getLocation());
            updateLastStackTime(player);
        }
    }

    private boolean isOnCooldown(Player player) {
        long lastTime = lastStackTime.getOrDefault(player.getUniqueId(), 0L);
        return System.currentTimeMillis() - lastTime < STACK_COOLDOWN;
    }

    private void updateLastStackTime(Player player) {
        lastStackTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean acquireStackLock(Player player, Location location) {
        return stackLocks.putIfAbsent(location, player.getUniqueId()) == null;
    }

    private void releaseStackLock(Location location) {
        stackLocks.remove(location);
    }

    public boolean handleSpawnerStack(Player player, SpawnerData targetSpawner, ItemStack itemInHand, boolean stackAll) {
        // Basic validation checks
        if (itemInHand.getType() != Material.SPAWNER) {
            return false;
        }

        Location location = targetSpawner.getSpawnerLocation();
        if (!hasStackPermissions(player, location)) {
            return false;
        }

        // Check if either the target or the item is a vanilla spawner
        if (SpawnerTypeChecker.isVanillaSpawner(itemInHand)) {
            messageService.sendMessage(player, "spawner_invalid");
            return false;
        }

        // Always check the entity type directly without caching
        Optional<EntityType> handEntityTypeOpt = getEntityTypeFromItem(itemInHand);
        if (!handEntityTypeOpt.isPresent()) {
            messageService.sendMessage(player, "spawner_invalid");
            return false;
        }

        EntityType handEntityType = handEntityTypeOpt.get();
        EntityType targetEntityType = targetSpawner.getEntityType();

        // Verify types match
        if (handEntityType != targetEntityType) {
            messageService.sendMessage(player, "spawner_different");
            return false;
        }

        // Verify stack limits
        int maxStackSize = targetSpawner.getMaxStackSize();
        int currentStack = targetSpawner.getStackSize();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("max", String.valueOf(maxStackSize));
        if (currentStack >= maxStackSize) {
            messageService.sendMessage(player, "spawner_stack_full", placeholders);
            return false;
        }

        return processStackAddition(player, targetSpawner, itemInHand, stackAll, currentStack, maxStackSize);
    }

    private boolean hasStackPermissions(Player player, Location location) {
        if (!CheckStackBlock.CanPlayerPlaceBlock(player, location)) {
            messageService.sendMessage(player, "spawner_protected");
            return false;
        }

        if (!CheckBreakBlock.hasProtectedBlockOwnerAccess(player, location)) {
            messageService.sendMessage(player, "spawner_protected");
            return false;
        }

        if (!player.hasPermission("smartspawner.stack")) {
            messageService.sendMessage(player, "no_permission");
            return false;
        }

        return true;
    }

    /**
     * Optimized method to extract entity type from a spawner item
     */
    public Optional<EntityType> getEntityTypeFromItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }

        // First try to get entity type from block state (most reliable)
        if (meta instanceof BlockStateMeta) {
            BlockStateMeta blockMeta = (BlockStateMeta) meta;
            if (blockMeta.hasBlockState() && blockMeta.getBlockState() instanceof CreatureSpawner) {
                CreatureSpawner handSpawner = (CreatureSpawner) blockMeta.getBlockState();
                EntityType entityType = handSpawner.getSpawnedType();
                if (entityType != null) {
                    return Optional.of(entityType);
                }
            }
        }

        // If no display name, we can't continue with name parsing
        if (!meta.hasDisplayName()) {
            return Optional.empty();
        }

        String displayName = meta.getDisplayName();

        // Try EconomyShopGUI format
        Matcher matcher = ECONOMY_SHOP_GUI_PATTERN.matcher(displayName);
        if (matcher.matches()) {
            String entityName = matcher.group(1).replace(" ", "_").toUpperCase();
            try {
                return Optional.of(EntityType.valueOf(entityName));
            } catch (IllegalArgumentException ignored) {
                // Fall through to next method
            }
        }

        // Try custom format from language config
        return tryLanguageManagerFormat(displayName);
    }

    /**
     * Optimized method to extract entity type from custom display name formats
     */
    private Optional<EntityType> tryLanguageManagerFormat(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return Optional.empty();
        }

        // Get compile and cache the regex pattern only once
        if (cachedEntityNamePattern == null) {
            String namePattern = languageManager.getItemName("custom_item.spawner.name");
            if (namePattern == null || namePattern.isEmpty()) {
                return Optional.empty();
            }

            // Strip color codes and create regex pattern
            String strippedPattern = stripAllColorCodes(namePattern);

            // Create a more robust pattern that can handle various entity name formats
            String patternRegex = strippedPattern
                    .replace("%ᴇɴᴛɪᴛʏ%", "([\\p{L}\\p{N}_\\s]+)")  // Match multilingual characters, numbers, underscores and spaces
                    .replace("%entity%", "([\\p{L}\\p{N}_\\s]+)");

            // Escape regex special characters except for the capture group
            patternRegex = escapeRegexExceptGroups(patternRegex);

            // Add word boundary before "spawner" to improve matching
            patternRegex = patternRegex.replace("spawner", "\\b(?i)spawner");

            cachedEntityNamePattern = Pattern.compile(patternRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
        }

        // Strip color codes from the display name
        String strippedDisplayName = stripAllColorCodes(displayName);
        if (strippedDisplayName.isEmpty()) {
            return Optional.empty();
        }

        // Match against our pattern
        Matcher matcher = cachedEntityNamePattern.matcher(strippedDisplayName);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return Optional.empty();
        }

        // Extract and normalize the entity name
        String entityName = matcher.group(1).trim();

        // Handle a common case with small/fancy caps
        entityName = normalizeEntityName(entityName);

        // Convert to EntityType format
        String entityTypeKey = entityName.toUpperCase().replace(" ", "_");

        try {
            return Optional.of(EntityType.valueOf(entityTypeKey));
        } catch (IllegalArgumentException e) {
            // Try alternate formats before giving up
            return tryAlternateEntityNames(entityName);
        }
    }

    /**
     * Helper method to normalize entity names with special characters
     */
    private String normalizeEntityName(String entityName) {
        // Handle special cases like "ᴀʟʟᴀʏ" to "ALLAY"
        // Map common special unicode characters to regular alphabet
        return entityName
                .replace('ᴀ', 'A').replace('ʙ', 'B').replace('ᴄ', 'C')
                .replace('ᴅ', 'D').replace('ᴇ', 'E').replace('ꜰ', 'F')
                .replace('ɢ', 'G').replace('ʜ', 'H').replace('ɪ', 'I')
                .replace('ᴊ', 'J').replace('ᴋ', 'K').replace('ʟ', 'L')
                .replace('ᴍ', 'M').replace('ɴ', 'N').replace('ᴏ', 'O')
                .replace('ᴘ', 'P').replace('ǫ', 'Q').replace('ʀ', 'R')
                .replace('ꜱ', 'S').replace('ᴛ', 'T').replace('ᴜ', 'U')
                .replace('ᴠ', 'V').replace('ᴡ', 'W').replace('x', 'X')
                .replace('ʏ', 'Y').replace('ᴢ', 'Z');
    }

    /**
     * Try alternate entity name formats for special cases
     */
    private Optional<EntityType> tryAlternateEntityNames(String entityName) {
        // Common name variations
        Map<String, String> nameVariations = new HashMap<>();
        nameVariations.put("CAVE_SPIDER", "CAVESPIDER");
        nameVariations.put("PIGLIN_BRUTE", "PIGLINBRUTE");
        nameVariations.put("IRON_GOLEM", "IRONGOLEM");
        nameVariations.put("SNOW_GOLEM", "SNOWGOLEM");
        nameVariations.put("MOOSHROOM", "MUSHROOM_COW");
        nameVariations.put("WITHER_SKELETON", "WITHERSKELETON");
        nameVariations.put("ZOMBIE_VILLAGER", "ZOMBIEVILLAGER");

        // Try direct lookup first
        String normalizedName = entityName.toUpperCase().replace(" ", "_");

        // Try variations
        for (Map.Entry<String, String> entry : nameVariations.entrySet()) {
            if (normalizedName.equals(entry.getKey()) || normalizedName.equals(entry.getValue())) {
                try {
                    return Optional.of(EntityType.valueOf(entry.getKey()));
                } catch (IllegalArgumentException ignored) {
                    try {
                        return Optional.of(EntityType.valueOf(entry.getValue()));
                    } catch (IllegalArgumentException ignored2) {
                        // Keep trying
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Escape regex special characters except for capture groups
     */
    private String escapeRegexExceptGroups(String pattern) {
        StringBuilder result = new StringBuilder();
        boolean inGroup = false;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '[') {
                inGroup = true;
                result.append(c);
            } else if (c == ')' && inGroup) {
                inGroup = false;
                result.append(c);
            } else if (inGroup) {
                result.append(c);
            } else {
                // Escape regex special characters
                if ("[](){}.*+?^$|\\".indexOf(c) != -1) {
                    result.append('\\');
                }
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Strip all color codes from a string
     */
    private String stripAllColorCodes(String text) {
        if (text == null) return "";

        // First remove hex color codes with format &#rrggbb or §x§r§g§b...
        String noHexColors = text.replaceAll("(?:&#[a-fA-F0-9]{6}|§x(?:§[a-fA-F0-9]){6})", "");

        // Then remove legacy color codes
        return ChatColor.stripColor(noHexColors);
    }

    private boolean processStackAddition(Player player, SpawnerData targetSpawner, ItemStack itemInHand,
                                         boolean stackAll, int currentStack, int maxStackSize) {
        int itemAmount = itemInHand.getAmount();
        int spaceLeft = maxStackSize - currentStack;

        int amountToStack = stackAll ? Math.min(spaceLeft, itemAmount) : 1;
        int newStack = currentStack + amountToStack;

        if(SpawnerStackEvent.getHandlerList().getRegisteredListeners().length != 0) {
            SpawnerStackEvent e = new SpawnerStackEvent(player, targetSpawner.getSpawnerLocation(), currentStack, newStack);
            Bukkit.getPluginManager().callEvent(e);
            if (e.isCancelled()) return false;
        }

        // Update spawner data
        targetSpawner.setStackSize(newStack);
        if (targetSpawner.getIsAtCapacity()) {
            targetSpawner.setIsAtCapacity(false);
        }

        // Update player's inventory
        updatePlayerInventory(player, itemInHand, amountToStack);

        // Visual feedback
        showStackAnimation(targetSpawner, newStack, player);

        return true;
    }

    private void updatePlayerInventory(Player player, ItemStack itemInHand, int amountUsed) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            int remainingAmount = itemInHand.getAmount() - amountUsed;

            if (remainingAmount <= 0) {
                player.getInventory().setItemInMainHand(null);
            } else {
                itemInHand.setAmount(remainingAmount);
            }
        }
    }

    private void showStackAnimation(SpawnerData spawner, int newStack, Player player) {
        if (plugin.getConfig().getBoolean("particle.spawner_stack", true)) {
            Location loc = spawner.getSpawnerLocation();
            World world = loc.getWorld();

            if (world != null) {
                // Use location-based scheduling for particle effects
                Scheduler.runLocationTask(loc, () -> {
                    world.spawnParticle(
                            ParticleWrapper.VILLAGER_HAPPY,
                            loc.clone().add(0.5, 0.5, 0.5),
                            10, 0.3, 0.3, 0.3, 0
                    );
                });
            }
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.valueOf(newStack));
        messageService.sendMessage(player, "spawner_stack_success", placeholders);
    }
}