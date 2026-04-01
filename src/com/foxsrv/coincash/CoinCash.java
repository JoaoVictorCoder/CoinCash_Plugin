package com.foxsrv.coincash;

import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;
import com.foxsrv.coincard.CoinCardPlugin.TransferCallback;
import com.foxsrv.coincard.CoinCardPlugin.BalanceCallback;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class CoinCash extends JavaPlugin implements Listener {

    // ====================================================
    // CONSTANTS & FORMATTING
    // ====================================================
    private static final DecimalFormat COIN_FORMAT;
    private static final int TICKS_PER_SECOND = 20;
    
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("0.########", symbols);
        COIN_FORMAT.setRoundingMode(RoundingMode.DOWN);
        COIN_FORMAT.setMinimumFractionDigits(0);
        COIN_FORMAT.setMaximumFractionDigits(8);
    }

    // ====================================================
    // NBT KEYS
    // ====================================================
    private NamespacedKey noteItemKey;
    private NamespacedKey noteIdKey;
    private NamespacedKey noteWorthKey;
    private NamespacedKey noteNameKey;
    private NamespacedKey guiNoteIdKey;

    // ====================================================
    // CONFIGURATION
    // ====================================================
    private File configFile;
    private FileConfiguration config;
    private File notesFile;
    private NotesData notesData;
    private String serverCardId;
    private long cooldownTicks;
    private int queueIntervalMs;

    // ====================================================
    // COINCARD API
    // ====================================================
    private CoinCardAPI coinCardAPI;

    // ====================================================
    // TRANSACTION QUEUE
    // ====================================================
    private final Queue<WithdrawTransaction> withdrawQueue = new ConcurrentLinkedQueue<>();
    private final Queue<DepositTransaction> depositQueue = new ConcurrentLinkedQueue<>();
    
    private final Map<String, PendingWithdraw> pendingWithdraws = new ConcurrentHashMap<>();
    private final Map<String, PendingDeposit> pendingDeposits = new ConcurrentHashMap<>();
    
    private BukkitTask queueProcessorTask;
    private final AtomicLong lastProcessTime = new AtomicLong(0);

    // ====================================================
    // PLAYER SESSIONS & COOLDOWNS
    // ====================================================
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerClickCooldown = new ConcurrentHashMap<>();
    private long clickCooldownMs;

    // ====================================================
    // CACHE
    // ====================================================
    private final Map<UUID, Boolean> cardCheckCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cardCheckTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 5 * 60 * 1000; // 5 minutos

    // ====================================================
    // ON ENABLE / DISABLE
    // ====================================================
    @Override
    public void onEnable() {
        getLogger().info("=== Iniciando CoinCash v" + getDescription().getVersion() + " ===");
        
        try {
            // Initialize NBT keys
            noteItemKey = new NamespacedKey(this, "note_item");
            noteIdKey = new NamespacedKey(this, "note_id");
            noteWorthKey = new NamespacedKey(this, "note_worth");
            noteNameKey = new NamespacedKey(this, "note_name");
            guiNoteIdKey = new NamespacedKey(this, "gui_note_id");
            getLogger().info("NBT keys initialized");

            // Check CoinCard dependency
            if (!setupCoinCardAPI()) {
                getLogger().severe("CoinCard plugin not found! Disabling CoinCash...");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("CoinCard API connected successfully");

            // Create data folder
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
                getLogger().info("Created data folder: " + getDataFolder().getPath());
            }

            // Create config file manually
            createConfig();
            
            // Load configuration
            loadConfig();
            getLogger().info("Configuration loaded. ServerCard: " + (serverCardId.isEmpty() ? "NOT SET" : serverCardId));

            notesFile = new File(getDataFolder(), "notes.dat");
            loadNotesData();

            // Register events and commands
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("Events registered");
            
            // Register command executor
            CashCommand cashCommand = new CashCommand();
            getCommand("cash").setExecutor(cashCommand);
            getCommand("cash").setTabCompleter(cashCommand);
            getLogger().info("Command /cash registered");

            // Start queue processor
            startQueueProcessor();
            getLogger().info("Queue processor started");

            getLogger().info("=== CoinCash v" + getDescription().getVersion() + " enabled successfully! ===");
            getLogger().info("Loaded " + (notesData != null ? notesData.notes.size() : 0) + " notes from database.");
            
        } catch (Exception e) {
            getLogger().severe("FATAL ERROR ENABLING PLUGIN: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling CoinCash...");
        
        if (queueProcessorTask != null) {
            queueProcessorTask.cancel();
            getLogger().info("Queue processor stopped");
        }
        
        if (notesData != null) {
            saveNotesData();
        }
        
        cardCheckCache.clear();
        cardCheckTimestamp.clear();
        playerClickCooldown.clear();
        playerSessions.clear();
        
        getLogger().info("CoinCash disabled.");
    }

    // ====================================================
    // CREATE CONFIG MANUALLY
    // ====================================================
    private void createConfig() {
        configFile = new File(getDataFolder(), "config.yml");
        
        if (!configFile.exists()) {
            getLogger().info("config.yml not found, creating default configuration...");
            
            try {
                configFile.createNewFile();
                
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write("# Server Coin Card ID to do transactions from users to withdraw or deposit coins\n");
                    writer.write("ServerCard: \"e1301fadfc35\"\n");
                    writer.write("\n");
                    writer.write("# Cooldown between player actions in ticks (20 ticks = 1 second)\n");
                    writer.write("Cooldown: 20\n");
                    writer.write("\n");
                    writer.write("# Queue interval in milliseconds between each transaction\n");
                    writer.write("# Higher values prevent rate limiting but increase wait time\n");
                    writer.write("QueueIntervalMs: 1010\n");
                    writer.write("\n");
                    writer.write("# The cash notes list to add or remove notes and their worth\n");
                    writer.write("# These will be loaded into notes.dat on first startup\n");
                    writer.write("Note List:\n");
                    writer.write("  1:\n");
                    writer.write("    name: \"&eBit Note\"\n");
                    writer.write("    item: \"PAPER\"\n");
                    writer.write("    model: 1\n");
                    writer.write("    lore:\n");
                    writer.write("      1: \"\"\n");
                    writer.write("      2: \"&71 SAT\"\n");
                    writer.write("      3: \"\"\n");
                    writer.write("    worth: \"0.00000001\"\n");
                    
                    writer.flush();
                }
                
                getLogger().info("Default config.yml created successfully!");
                
            } catch (IOException e) {
                getLogger().severe("Could not create config.yml: " + e.getMessage());
            }
        }
    }

    // ====================================================
    // COINCARD API SETUP
    // ====================================================
    private boolean setupCoinCardAPI() {
        try {
            RegisteredServiceProvider<CoinCardAPI> provider = 
                getServer().getServicesManager().getRegistration(CoinCardAPI.class);

            if (provider == null) {
                getLogger().severe("CoinCardAPI provider not found!");
                return false;
            }

            coinCardAPI = provider.getProvider();
            
            if (coinCardAPI == null) {
                getLogger().severe("CoinCardAPI is null!");
                return false;
            }
            
            // Test the API by getting server card (optional)
            try {
                String serverCard = coinCardAPI.getServerCard();
                getLogger().info("CoinCard server card: " + (serverCard != null ? serverCard : "not set"));
            } catch (Exception e) {
                getLogger().warning("Could not get server card from CoinCard: " + e.getMessage());
            }
            
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to setup CoinCard API: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ====================================================
    // CONFIGURATION
    // ====================================================
    private void loadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);

        // Add defaults if not present
        config.addDefault("ServerCard", "");
        config.addDefault("Cooldown", 20);
        config.addDefault("QueueIntervalMs", 1010);
        
        // Save defaults to file
        config.options().copyDefaults(true);
        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Could not save config.yml: " + e.getMessage());
        }

        serverCardId = config.getString("ServerCard", "");
        cooldownTicks = config.getLong("Cooldown", 20);
        queueIntervalMs = config.getInt("QueueIntervalMs", 1010);
        clickCooldownMs = cooldownTicks * 50; // Convert ticks to milliseconds
        
        getLogger().info("Config values: ServerCard=" + serverCardId + 
                        ", Cooldown=" + cooldownTicks + 
                        ", QueueInterval=" + queueIntervalMs);
    }

    // ====================================================
    // SERIALIZATION METHODS
    // ====================================================
    private String serializeItemStack(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "";
        
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeObject(item);
            dataOutput.close();
            
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Failed to serialize item: " + e.getMessage());
            return "";
        }
    }
    
    private ItemStack deserializeItemStack(String data) {
        if (data == null || data.isEmpty()) return null;
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            
            return item;
        } catch (IOException | ClassNotFoundException e) {
            getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }

    // ====================================================
    // NOTES DATA MANAGEMENT (YAML BASED)
    // ====================================================
    private void loadNotesData() {
        notesData = new NotesData();
        
        if (notesFile.exists()) {
            FileConfiguration notesConfig = YamlConfiguration.loadConfiguration(notesFile);
            
            if (notesConfig.contains("notes")) {
                for (String key : notesConfig.getConfigurationSection("notes").getKeys(false)) {
                    try {
                        String path = "notes." + key;
                        String id = notesConfig.getString(path + ".id");
                        String itemData = notesConfig.getString(path + ".item");
                        BigDecimal worth = new BigDecimal(notesConfig.getString(path + ".worth", "0"));
                        String name = ChatColor.translateAlternateColorCodes('&', notesConfig.getString(path + ".name", "Unknown Note"));
                        long createdAt = notesConfig.getLong(path + ".createdAt", System.currentTimeMillis());
                        
                        ItemStack item = deserializeItemStack(itemData);
                        if (item != null) {
                            // Reapply NBT tags
                            item = markAsNoteItem(item, id, worth, name);
                            
                            Note note = new Note(id, item, worth, name, createdAt);
                            notesData.notes.put(id, note);
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to load note " + key + ": " + e.getMessage());
                    }
                }
                getLogger().info("Loaded " + notesData.notes.size() + " notes from notes.dat");
            } else {
                getLogger().info("No notes found in notes.dat, loading defaults");
                loadDefaultNotes();
            }
        } else {
            getLogger().info("notes.dat not found, loading default notes from config");
            loadDefaultNotes();
        }
    }

    private void saveNotesData() {
        try {
            FileConfiguration notesConfig = new YamlConfiguration();
            
            for (Map.Entry<String, Note> entry : notesData.notes.entrySet()) {
                Note note = entry.getValue();
                String path = "notes." + entry.getKey();
                
                notesConfig.set(path + ".id", note.id);
                notesConfig.set(path + ".item", serializeItemStack(note.item));
                notesConfig.set(path + ".worth", note.worth.toPlainString());
                notesConfig.set(path + ".name", note.name.replace(ChatColor.COLOR_CHAR, '&'));
                notesConfig.set(path + ".createdAt", note.createdAt);
            }
            
            notesConfig.save(notesFile);
            getLogger().info("Saved " + notesData.notes.size() + " notes to notes.dat");
            
        } catch (IOException e) {
            getLogger().severe("Failed to save notes.dat: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadDefaultNotes() {
        if (config.contains("Note List")) {
            try {
                for (String key : config.getConfigurationSection("Note List").getKeys(false)) {
                    try {
                        String path = "Note List." + key;
                        String name = ChatColor.translateAlternateColorCodes('&', config.getString(path + ".name", "&eBit Note"));
                        String materialName = config.getString(path + ".item", "PAPER");
                        Material material = Material.getMaterial(materialName);
                        if (material == null) {
                            material = Material.PAPER;
                            getLogger().warning("Invalid material for note " + key + ": " + materialName + ", using PAPER");
                        }
                        
                        int model = config.getInt(path + ".model", 1);
                        String worthStr = config.getString(path + ".worth", "0.00000001");
                        BigDecimal worth = new BigDecimal(worthStr);
                        
                        List<String> lore = new ArrayList<>();
                        if (config.contains(path + ".lore")) {
                            for (String loreKey : config.getConfigurationSection(path + ".lore").getKeys(false)) {
                                String line = config.getString(path + ".lore." + loreKey);
                                if (line != null && !line.isEmpty()) {
                                    lore.add(ChatColor.translateAlternateColorCodes('&', line));
                                }
                            }
                        }
                        
                        if (lore.isEmpty()) {
                            lore.add("");
                            lore.add(ChatColor.GRAY + formatCoin(worth) + " coins");
                            lore.add("");
                        }
                        
                        ItemStack item = createNoteItem(material, name, lore, model);
                        String noteId = UUID.randomUUID().toString();
                        
                        registerNote(noteId, item, worth, name);
                        getLogger().info("Loaded default note: " + name + " worth " + formatCoin(worth));
                        
                    } catch (Exception e) {
                        getLogger().warning("Failed to load default note " + key + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Failed to load Note List from config: " + e.getMessage());
            }
        } else {
            getLogger().info("No Note List found in config, creating example notes");
            createExampleNotes();
        }
    }
    
    private void createExampleNotes() {
        try {
            // Create example notes
            ItemStack note1 = createNoteItem(Material.PAPER, ChatColor.YELLOW + "Bit Note", 
                Arrays.asList("", ChatColor.GRAY + "1 SAT", ""), 1);
            registerNote(UUID.randomUUID().toString(), note1, new BigDecimal("0.00000001"), "Bit Note");
            
            ItemStack note2 = createNoteItem(Material.PAPER, ChatColor.GOLD + "Gold Note", 
                Arrays.asList("", ChatColor.GRAY + "100 Coins", ""), 2);
            registerNote(UUID.randomUUID().toString(), note2, new BigDecimal("100"), "Gold Note");
            
            ItemStack note3 = createNoteItem(Material.PAPER, ChatColor.AQUA + "Diamond Note", 
                Arrays.asList("", ChatColor.GRAY + "1000 Coins", ""), 3);
            registerNote(UUID.randomUUID().toString(), note3, new BigDecimal("1000"), "Diamond Note");
            
            getLogger().info("Created 3 example notes");
        } catch (Exception e) {
            getLogger().warning("Failed to create example notes: " + e.getMessage());
        }
    }

    // ====================================================
    // NOTE ITEM CREATION AND MARKING
    // ====================================================
    private ItemStack createNoteItem(Material material, String name, List<String> lore, int customModelData) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        
        meta.setDisplayName(name);
        
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }
        
        meta.setCustomModelData(customModelData);
        item.setItemMeta(meta);
        
        return item;
    }

    private ItemStack markAsNoteItem(ItemStack item, String noteId, BigDecimal worth, String noteName) {
        if (item == null || item.getType() == Material.AIR) return item;
        
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        
        container.set(noteItemKey, PersistentDataType.BOOLEAN, true);
        container.set(noteIdKey, PersistentDataType.STRING, noteId);
        container.set(noteWorthKey, PersistentDataType.STRING, worth.toPlainString());
        container.set(noteNameKey, PersistentDataType.STRING, noteName);
        
        item.setItemMeta(meta);
        return item;
    }

    private boolean isNoteItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(noteItemKey, PersistentDataType.BOOLEAN);
    }

    private String getNoteId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container.has(noteIdKey, PersistentDataType.STRING)) {
            return container.get(noteIdKey, PersistentDataType.STRING);
        }
        return null;
    }

    private BigDecimal getNoteWorth(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return BigDecimal.ZERO;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container.has(noteWorthKey, PersistentDataType.STRING)) {
            try {
                return new BigDecimal(container.get(noteWorthKey, PersistentDataType.STRING));
            } catch (Exception e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private String getNoteName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "Unknown Note";
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container.has(noteNameKey, PersistentDataType.STRING)) {
            return container.get(noteNameKey, PersistentDataType.STRING);
        }
        return meta.hasDisplayName() ? meta.getDisplayName() : "Unknown Note";
    }

    // ====================================================
    // MÉTODO PARA PEGAR ID DA GUI
    // ====================================================
    private String getNoteIdFromGuiItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();

        if (container.has(guiNoteIdKey, PersistentDataType.STRING)) {
            return container.get(guiNoteIdKey, PersistentDataType.STRING);
        }

        return null;
    }

    // ====================================================
    // NOTE REGISTRATION
    // ====================================================
    private boolean registerNote(String id, ItemStack item, BigDecimal worth, String name) {
        if (notesData.notes.containsKey(id)) {
            return false;
        }
        
        ItemStack clonedItem = item.clone();
        clonedItem = markAsNoteItem(clonedItem, id, worth, name);
        
        Note note = new Note(id, clonedItem, worth, name, System.currentTimeMillis());
        notesData.notes.put(id, note);
        saveNotesData();
        
        return true;
    }

    private boolean registerNoteFromItem(ItemStack item, BigDecimal worth, String name) {
        String id = UUID.randomUUID().toString();
        return registerNote(id, item, worth, name);
    }

    private boolean removeNote(String id) {
        if (notesData.notes.remove(id) != null) {
            saveNotesData();
            return true;
        }
        return false;
    }

    private List<Note> getAllNotes() {
        List<Note> notes = new ArrayList<>(notesData.notes.values());
        // Sort notes by worth from smallest to largest
        notes.sort(Comparator.comparing(note -> note.worth));
        return notes;
    }

    // ====================================================
    // COINCARD API HELPERS
    // ====================================================
    private boolean hasPlayerCard(UUID uuid) {
        if (uuid == null) return false;
        
        try {
            Long cachedTime = cardCheckTimestamp.get(uuid);
            if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CACHE_DURATION) {
                Boolean cached = cardCheckCache.get(uuid);
                if (cached != null) return cached;
            }
            
            boolean hasCard = coinCardAPI.hasCard(uuid);
            cardCheckCache.put(uuid, hasCard);
            cardCheckTimestamp.put(uuid, System.currentTimeMillis());
            
            return hasCard;
        } catch (Exception e) {
            getLogger().warning("Error checking player card for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    private String getPlayerCardId(UUID uuid) {
        if (uuid == null) return null;
        
        try {
            return coinCardAPI.getPlayerCard(uuid);
        } catch (Exception e) {
            getLogger().warning("Error getting player card for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private void checkPlayerBalance(String cardId, BalanceCheckCallback callback) {
        if (cardId == null || cardId.isEmpty()) {
            callback.onFailure("Invalid card ID");
            return;
        }
        
        coinCardAPI.getBalance(cardId, new BalanceCallback() {
            @Override
            public void onResult(double balance, String error) {
                if (error != null && !error.isEmpty()) {
                    callback.onFailure(error);
                } else {
                    callback.onSuccess(BigDecimal.valueOf(balance));
                }
            }
        });
    }

    private void transferFromPlayerToServer(String playerCard, BigDecimal amount, 
                                           WithdrawTransaction transaction, PendingWithdraw withdraw) {
        if (serverCardId == null || serverCardId.isEmpty() || playerCard == null || amount == null) {
            handleFailedWithdraw(transaction, withdraw, "Invalid transfer parameters");
            return;
        }

        double amountDouble = amount.doubleValue();

        coinCardAPI.transfer(playerCard, serverCardId, amountDouble, new TransferCallback() {
            @Override
            public void onSuccess(String txId, double amount) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleSuccessfulWithdraw(transaction, withdraw, txId, BigDecimal.valueOf(amount));
                    }
                }.runTask(CoinCash.this);
            }

            @Override
            public void onFailure(String error) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleFailedWithdraw(transaction, withdraw, error);
                    }
                }.runTask(CoinCash.this);
            }
        });
    }

    private void transferFromServerToPlayer(String playerCard, BigDecimal amount, 
                                           DepositTransaction transaction, PendingDeposit deposit) {
        if (serverCardId == null || serverCardId.isEmpty() || playerCard == null || amount == null) {
            handleFailedDeposit(transaction, deposit, "Invalid transfer parameters");
            return;
        }

        double amountDouble = amount.doubleValue();

        coinCardAPI.transfer(serverCardId, playerCard, amountDouble, new TransferCallback() {
            @Override
            public void onSuccess(String txId, double amount) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleSuccessfulDeposit(transaction, deposit, txId, BigDecimal.valueOf(amount));
                    }
                }.runTask(CoinCash.this);
            }

            @Override
            public void onFailure(String error) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        handleFailedDeposit(transaction, deposit, error);
                    }
                }.runTask(CoinCash.this);
            }
        });
    }

    // ====================================================
    // QUEUE PROCESSOR
    // ====================================================
    private void startQueueProcessor() {
        queueProcessorTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    processQueue();
                } catch (Exception e) {
                    getLogger().warning("Error in queue processor: " + e.getMessage());
                }
            }
        }.runTaskTimer(this, 20L, Math.max(1, queueIntervalMs / 50));
    }

    private void processQueue() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime.get() < queueIntervalMs) {
            return;
        }

        // Process withdrawals first (higher priority)
        WithdrawTransaction withdrawTransaction = withdrawQueue.poll();
        if (withdrawTransaction != null) {
            lastProcessTime.set(now);
            PendingWithdraw withdraw = pendingWithdraws.get(withdrawTransaction.id);
            if (withdraw != null) {
                transferFromPlayerToServer(withdrawTransaction.playerCard, withdrawTransaction.amount, 
                                         withdrawTransaction, withdraw);
            }
            return;
        }

        // Then process deposits
        DepositTransaction depositTransaction = depositQueue.poll();
        if (depositTransaction != null) {
            lastProcessTime.set(now);
            PendingDeposit deposit = pendingDeposits.get(depositTransaction.id);
            if (deposit != null) {
                transferFromServerToPlayer(depositTransaction.playerCard, depositTransaction.amount, 
                                         depositTransaction, deposit);
            }
        }
    }

    private void handleSuccessfulWithdraw(WithdrawTransaction transaction, PendingWithdraw withdraw, 
                                         String txId, BigDecimal actualAmount) {
        if (transaction == null || withdraw == null) {
            getLogger().warning("handleSuccessfulWithdraw called with null parameters");
            return;
        }

        withdraw.completed = true;
        withdraw.txId = txId;

        Player player = withdraw.playerUuid != null ? Bukkit.getPlayer(withdraw.playerUuid) : null;
        
        if (player != null && player.isOnline() && withdraw.items != null) {
            // Give notes to player
            for (ItemStack item : withdraw.items) {
                if (item != null) {
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                    if (!leftover.isEmpty()) {
                        for (ItemStack drop : leftover.values()) {
                            if (drop != null) {
                                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                            }
                        }
                    }
                }
            }
            
            player.sendMessage(ChatColor.GREEN + "✓ WITHDRAW SUCCESSFUL!");
            player.sendMessage(ChatColor.GRAY + "Amount: " + ChatColor.YELLOW + formatCoin(transaction.amount));
            player.sendMessage(ChatColor.GRAY + "Notes: " + ChatColor.WHITE + withdraw.items.size());
            player.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE + 
                (txId != null ? txId.substring(0, 8) + "..." : "unknown"));
        }

        pendingWithdraws.remove(transaction.id);
        
        // Update player's session if they're still online
        if (player != null) {
            PlayerSession session = playerSessions.get(player.getUniqueId());
            if (session != null) {
                session.lastWithdrawAmount = transaction.amount;
            }
        }
    }

    private void handleFailedWithdraw(WithdrawTransaction transaction, PendingWithdraw withdraw, String error) {
        if (transaction == null || withdraw == null) {
            getLogger().warning("handleFailedWithdraw called with null parameters");
            return;
        }

        Player player = withdraw.playerUuid != null ? Bukkit.getPlayer(withdraw.playerUuid) : null;
        
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "✗ WITHDRAW FAILED!");
            player.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.RED + (error != null ? error : "Unknown error"));
            player.sendMessage(ChatColor.YELLOW + "You were not charged for this transaction.");
        }

        pendingWithdraws.remove(transaction.id);
    }

    private void handleSuccessfulDeposit(DepositTransaction transaction, PendingDeposit deposit, 
                                         String txId, BigDecimal actualAmount) {
        if (transaction == null || deposit == null) {
            getLogger().warning("handleSuccessfulDeposit called with null parameters");
            return;
        }

        deposit.completed = true;
        deposit.txId = txId;

        Player player = deposit.playerUuid != null ? Bukkit.getPlayer(deposit.playerUuid) : null;
        
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.GREEN + "✓ DEPOSIT SUCCESSFUL!");
            player.sendMessage(ChatColor.GRAY + "Amount: " + ChatColor.YELLOW + formatCoin(transaction.amount));
            player.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE + 
                (txId != null ? txId.substring(0, 8) + "..." : "unknown"));
            player.sendMessage(ChatColor.GREEN + "The coins have been credited to your card!");
        }

        pendingDeposits.remove(transaction.id);
        
        // Update player's session if they're still online
        if (player != null) {
            PlayerSession session = playerSessions.get(player.getUniqueId());
            if (session != null) {
                session.lastDepositAmount = transaction.amount;
            }
        }
    }

    private void handleFailedDeposit(DepositTransaction transaction, PendingDeposit deposit, String error) {
        if (transaction == null || deposit == null) {
            getLogger().warning("handleFailedDeposit called with null parameters");
            return;
        }

        Player player = deposit.playerUuid != null ? Bukkit.getPlayer(deposit.playerUuid) : null;
        
        // Return items to player
        if (player != null && player.isOnline() && deposit.items != null) {
            for (ItemStack item : deposit.items) {
                if (item != null) {
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                    if (!leftover.isEmpty()) {
                        for (ItemStack drop : leftover.values()) {
                            if (drop != null) {
                                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                            }
                        }
                    }
                }
            }
            
            player.sendMessage(ChatColor.RED + "✗ DEPOSIT FAILED!");
            player.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.RED + (error != null ? error : "Unknown error"));
            player.sendMessage(ChatColor.YELLOW + "Your items have been returned to your inventory.");
        }

        pendingDeposits.remove(transaction.id);
    }

    // ====================================================
    // WITHDRAW PROCESSING
    // ====================================================
    private void processWithdraw(Player player, Map<String, Integer> selectedNotes, Inventory withdrawInventory) {
        if (player == null || selectedNotes == null || selectedNotes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No notes selected!");
            return;
        }

        getLogger().info("Processing withdraw for " + player.getName() + " with selections: " + selectedNotes.toString());

        // Check cooldown
        if (!checkClickCooldown(player)) {
            player.sendMessage(ChatColor.RED + "Please wait before making another withdraw!");
            return;
        }

        // Check if player has card
        if (!hasPlayerCard(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            player.closeInventory();
            return;
        }

        String playerCardId = getPlayerCardId(player.getUniqueId());
        if (playerCardId == null || playerCardId.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Could not retrieve your card ID!");
            return;
        }

        List<ItemStack> noteItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        // Build list of notes to withdraw
        for (Map.Entry<String, Integer> entry : selectedNotes.entrySet()) {
            String noteId = entry.getKey();
            int quantity = entry.getValue();
            
            Note note = notesData.notes.get(noteId);
            if (note == null || note.item == null) {
                getLogger().warning("Note not found for ID: " + noteId);
                continue;
            }
            
            for (int i = 0; i < quantity; i++) {
                ItemStack noteItem = note.item.clone();
                noteItem.setAmount(1);
                noteItems.add(noteItem);
            }
            
            BigDecimal noteTotal = note.worth.multiply(BigDecimal.valueOf(quantity));
            totalCost = totalCost.add(noteTotal);
            
            getLogger().info("Added " + quantity + " of note " + noteId + " worth " + note.worth + " each, total for this note: " + noteTotal);
        }

        if (noteItems.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No valid notes selected!");
            return;
        }

        // Truncate to 8 decimal places
        totalCost = totalCost.setScale(8, RoundingMode.DOWN);
        
        getLogger().info("Total cost for withdraw: " + totalCost + " with " + noteItems.size() + " items");

        // Create final copies for the callback
        final BigDecimal finalTotalCost = totalCost;
        final List<ItemStack> finalNoteItems = new ArrayList<>(noteItems);
        final String finalPlayerCardId = playerCardId;
        final UUID finalPlayerUuid = player.getUniqueId();
        final Inventory finalWithdrawInventory = withdrawInventory;
        final Player finalPlayer = player;
        final Map<String, Integer> finalSelectedNotes = new HashMap<>(selectedNotes);

        // Check player balance
        checkPlayerBalance(playerCardId, new BalanceCheckCallback() {
            @Override
            public void onSuccess(BigDecimal balance) {
                getLogger().info("Balance check successful for " + finalPlayer.getName() + ": " + balance + " needed: " + finalTotalCost);
                
                if (balance.compareTo(finalTotalCost) < 0) {
                    finalPlayer.sendMessage(ChatColor.RED + "Insufficient balance! You need " + 
                            formatCoin(finalTotalCost) + " but have " + formatCoin(balance));
                    return;
                }

                // Create transaction
                String transactionId = UUID.randomUUID().toString();
                WithdrawTransaction transaction = new WithdrawTransaction(
                    transactionId,
                    finalPlayerCardId,
                    finalTotalCost
                );

                PendingWithdraw withdraw = new PendingWithdraw(
                    transactionId,
                    finalPlayerUuid,
                    finalNoteItems,
                    finalTotalCost
                );

                pendingWithdraws.put(transactionId, withdraw);
                withdrawQueue.add(transaction);

                finalPlayer.sendMessage(ChatColor.GREEN + "✓ WITHDRAW QUEUED!");
                finalPlayer.sendMessage(ChatColor.GRAY + "Amount: " + ChatColor.YELLOW + formatCoin(finalTotalCost));
                finalPlayer.sendMessage(ChatColor.GRAY + "Notes: " + ChatColor.WHITE + finalNoteItems.size());
                finalPlayer.sendMessage(ChatColor.GRAY + "Your withdraw has been added to the queue.");
                finalPlayer.sendMessage(ChatColor.GRAY + "You will receive your notes in a few moments.");

                // Clear selections from player session
                PlayerSession session = playerSessions.get(finalPlayerUuid);
                if (session != null) {
                    session.withdrawSelections.clear();
                    getLogger().info("Cleared selections for player " + finalPlayer.getName());
                }

                // Clear selections from inventory display
                for (int i = 0; i < 45; i++) {
                    finalWithdrawInventory.setItem(i, null);
                }

                finalPlayer.closeInventory();
            }

            @Override
            public void onFailure(String error) {
                getLogger().warning("Balance check failed for " + finalPlayer.getName() + ": " + error);
                finalPlayer.sendMessage(ChatColor.RED + "Failed to check balance: " + (error != null ? error : "Unknown error"));
            }
        });
    }

    // ====================================================
    // DEPOSIT PROCESSING
    // ====================================================
    private void processDeposit(Player player, Inventory depositInventory) {
        if (player == null || depositInventory == null) return;

        // Check cooldown
        if (!checkClickCooldown(player)) {
            player.sendMessage(ChatColor.RED + "Please wait before making another deposit!");
            return;
        }

        // Check if player has card
        if (!hasPlayerCard(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            player.closeInventory();
            return;
        }

        String playerCardId = getPlayerCardId(player.getUniqueId());
        if (playerCardId == null || playerCardId.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Could not retrieve your card ID!");
            return;
        }

        List<ItemStack> noteItems = new ArrayList<>();
        List<ItemStack> nonNoteItems = new ArrayList<>();
        BigDecimal totalWorth = BigDecimal.ZERO;

        // Scan deposit inventory (first 5 rows = 45 slots)
        for (int i = 0; i < 45; i++) {
            ItemStack item = depositInventory.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;

            if (isNoteItem(item)) {
                BigDecimal worth = getNoteWorth(item);
                if (worth.compareTo(BigDecimal.ZERO) > 0) {
                    noteItems.add(item.clone());
                    totalWorth = totalWorth.add(worth.multiply(BigDecimal.valueOf(item.getAmount())));
                } else {
                    // Invalid note, add to non-note items to return
                    nonNoteItems.add(item.clone());
                }
            } else {
                // Non-note item, add to non-note items to return
                nonNoteItems.add(item.clone());
            }
        }

        // Return non-note items to player inventory
        for (ItemStack item : nonNoteItems) {
            if (item != null) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                if (!leftover.isEmpty()) {
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }
                }
            }
        }

        if (noteItems.isEmpty()) {
            if (!nonNoteItems.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "No valid notes found. Non-note items have been returned to your inventory.");
            } else {
                player.sendMessage(ChatColor.RED + "No items found in deposit menu!");
            }
            player.closeInventory();
            return;
        }

        if (totalWorth.compareTo(BigDecimal.ZERO) <= 0) {
            player.sendMessage(ChatColor.RED + "Invalid total amount!");
            // Return note items
            for (ItemStack item : noteItems) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                if (!leftover.isEmpty()) {
                    for (ItemStack drop : leftover.values()) {
                        if (drop != null) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }
                }
            }
            player.closeInventory();
            return;
        }

        // Truncate to 8 decimal places
        totalWorth = totalWorth.setScale(8, RoundingMode.DOWN);

        // Create final copies
        final BigDecimal finalTotalWorth = totalWorth;
        final List<ItemStack> finalNoteItems = new ArrayList<>(noteItems);
        final String finalPlayerCardId = playerCardId;
        final UUID finalPlayerUuid = player.getUniqueId();

        // Create transaction
        String transactionId = UUID.randomUUID().toString();
        DepositTransaction transaction = new DepositTransaction(
            transactionId,
            finalPlayerCardId,
            finalTotalWorth
        );

        PendingDeposit deposit = new PendingDeposit(
            transactionId,
            finalPlayerUuid,
            finalNoteItems,
            finalTotalWorth
        );

        pendingDeposits.put(transactionId, deposit);
        depositQueue.add(transaction);

        player.sendMessage(ChatColor.GREEN + "✓ DEPOSIT QUEUED!");
        player.sendMessage(ChatColor.GRAY + "Amount: " + ChatColor.YELLOW + formatCoin(finalTotalWorth));
        player.sendMessage(ChatColor.GRAY + "Your deposit has been added to the queue.");
        player.sendMessage(ChatColor.GRAY + "You will receive your coins in a few moments.");

        // Clear deposit inventory
        for (int i = 0; i < 45; i++) {
            depositInventory.setItem(i, null);
        }

        player.closeInventory();
    }

    // ====================================================
    // COOLDOWN CHECK
    // ====================================================
    private boolean checkClickCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long lastClick = playerClickCooldown.get(player.getUniqueId());
        
        if (lastClick != null && (now - lastClick) < clickCooldownMs) {
            return false;
        }
        
        playerClickCooldown.put(player.getUniqueId(), now);
        return true;
    }

    // ====================================================
    // GUI BUILDERS
    // ====================================================
    private Inventory buildMainMenu(Player player) {
        CashInventoryHolder holder = new CashInventoryHolder(
            CashInventoryHolder.Type.MAIN_MENU, 
            null, 
            0, 
            player.getUniqueId()
        );
        
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.BLUE + "CoinCash");

        // Withdraw button (Gold Block)
        ItemStack withdraw = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta withdrawMeta = withdraw.getItemMeta();
        withdrawMeta.setDisplayName(ChatColor.GOLD + "Withdraw");
        withdrawMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Convert coins to physical notes",
            ChatColor.GRAY + "Click to withdraw coins from your card"
        ));
        withdraw.setItemMeta(withdrawMeta);
        inv.setItem(11, withdraw);

        // Deposit button (Emerald Block)
        ItemStack deposit = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta depositMeta = deposit.getItemMeta();
        depositMeta.setDisplayName(ChatColor.GREEN + "Deposit");
        depositMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Convert physical notes to coins",
            ChatColor.GRAY + "Click to deposit notes to your card"
        ));
        deposit.setItemMeta(depositMeta);
        inv.setItem(15, deposit);

        // Fill with gray glass
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }

        return inv;
    }

    private Inventory buildWithdrawMenu(Player player, int page) {
        List<Note> allNotes = getAllNotes(); // Already sorted by worth
        int itemsPerPage = 45;
        int totalPages = (int) Math.ceil(allNotes.size() / (double) itemsPerPage);
        page = Math.max(0, Math.min(page, totalPages - 1));

        CashInventoryHolder holder = new CashInventoryHolder(
            CashInventoryHolder.Type.WITHDRAW_MENU, 
            null, 
            page, 
            player.getUniqueId()
        );
        
        Inventory inv = Bukkit.createInventory(holder, 54, 
            ChatColor.GOLD + "Withdraw Notes - Page " + (page + 1) + "/" + Math.max(1, totalPages));

        // Preencher com o método de atualização
        updateWithdrawMenu(player, inv, page);

        return inv;
    }

    // ====================================================
    // MÉTODO PARA ATUALIZAR GUI - CORRIGIDO SEM BURACOS
    // ====================================================
    private void updateWithdrawMenu(Player player, Inventory inv, int page) {
        List<Note> allNotes = getAllNotes(); // Already sorted by worth
        int itemsPerPage = 45;

        PlayerSession session = playerSessions.computeIfAbsent(player.getUniqueId(),
            k -> new PlayerSession(player.getUniqueId()));

        Map<String, Integer> selections = session.withdrawSelections;

        int start = page * itemsPerPage;
        int end = Math.min(start + itemsPerPage, allNotes.size());

        // Limpar slots de 0 a 44 (top 5 rows)
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, null);
        }

        int slot = 0;

        // Preencher notas nos slots de 0 a 44 sequencialmente
        for (int i = start; i < end; i++) {
            Note note = allNotes.get(i);
            if (note == null || note.item == null) continue;

            ItemStack item = note.item.clone();
            item.setAmount(1);

            int selected = selections.getOrDefault(note.id, 0);

            ItemMeta meta = item.getItemMeta();
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            if (lore == null) lore = new ArrayList<>();

            // Adicionar informações de seleção
            lore.add("");
            lore.add(ChatColor.GOLD + "Worth per note: " + ChatColor.WHITE + formatCoin(note.worth));
            lore.add(ChatColor.GOLD + "Selected: " + (selected > 0 ? ChatColor.GREEN : ChatColor.RED) + selected);
            if (selected > 0) {
                BigDecimal total = note.worth.multiply(BigDecimal.valueOf(selected));
                lore.add(ChatColor.GOLD + "Total: " + ChatColor.YELLOW + formatCoin(total));
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Left Click: +1");
            lore.add(ChatColor.GRAY + "Right Click: -1");
            lore.add(ChatColor.GRAY + "Shift+Left: +10");
            lore.add(ChatColor.GRAY + "Shift+Right: -10");

            meta.setLore(lore);

            // Salvar ID no NBT
            meta.getPersistentDataContainer().set(guiNoteIdKey, PersistentDataType.STRING, note.id);

            item.setItemMeta(meta);

            inv.setItem(slot, item);
            slot++;

            if (slot >= 45) break;
        }

        // Preencher slots vazios de 0 a 44 com vidro
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < 45; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }

        // Atualizar botões de navegação
        updateNavigationButtons(player, inv, page);
    }

    private void updateNavigationButtons(Player player, Inventory inv, int page) {
        List<Note> allNotes = getAllNotes();
        int itemsPerPage = 45;
        int totalPages = (int) Math.ceil(allNotes.size() / (double) itemsPerPage);

        // Fill bottom row slots 45-53 with filler initially
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        
        for (int i = 45; i <= 53; i++) {
            inv.setItem(i, filler);
        }

        // Back button (Redstone Block) at slot 45
        ItemStack back = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        // Withdraw button (Gold Block) at slot 49
        ItemStack withdrawBtn = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta withdrawMeta = withdrawBtn.getItemMeta();
        withdrawMeta.setDisplayName(ChatColor.GOLD + "WITHDRAW SELECTED NOTES");
        
        // Calculate total selected and total value
        PlayerSession session = playerSessions.get(player.getUniqueId());
        Map<String, Integer> selections = session != null ? session.withdrawSelections : new HashMap<>();
        
        int totalSelected = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        
        for (Map.Entry<String, Integer> entry : selections.entrySet()) {
            Note note = notesData.notes.get(entry.getKey());
            if (note != null) {
                totalSelected += entry.getValue();
                totalValue = totalValue.add(note.worth.multiply(BigDecimal.valueOf(entry.getValue())));
            }
        }
        totalValue = totalValue.setScale(8, RoundingMode.DOWN);
        
        withdrawMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Total notes: " + ChatColor.WHITE + totalSelected,
            ChatColor.GRAY + "Total value: " + ChatColor.YELLOW + formatCoin(totalValue),
            "",
            ChatColor.GREEN + "Click to withdraw all selected notes"
        ));
        withdrawBtn.setItemMeta(withdrawMeta);
        inv.setItem(49, withdrawBtn);

        // Page navigation - Previous page at slot 47
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName(ChatColor.GREEN + "Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(47, prev);
        }

        // Page navigation - Next page at slot 51
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName(ChatColor.GREEN + "Next Page");
            next.setItemMeta(nextMeta);
            inv.setItem(51, next);
        }
        
        // Page info at slot 48
        ItemStack pageInfo = new ItemStack(Material.PAPER);
        ItemMeta pageInfoMeta = pageInfo.getItemMeta();
        pageInfoMeta.setDisplayName(ChatColor.AQUA + "Page " + (page + 1) + "/" + Math.max(1, totalPages));
        pageInfo.setItemMeta(pageInfoMeta);
        inv.setItem(48, pageInfo);
    }

    private Inventory buildDepositMenu(Player player) {
        CashInventoryHolder holder = new CashInventoryHolder(
            CashInventoryHolder.Type.DEPOSIT_MENU, 
            null, 
            0, 
            player.getUniqueId()
        );
        
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.GREEN + "Deposit Notes");

        // Fill bottom row slots 45-53 with filler initially
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        
        for (int i = 45; i <= 53; i++) {
            inv.setItem(i, filler);
        }

        // Deposit button (Gold Block) in the center of last row (slot 49)
        ItemStack depositBtn = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta depositMeta = depositBtn.getItemMeta();
        depositMeta.setDisplayName(ChatColor.GOLD + "DEPOSIT");
        depositMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Place notes in the top 5 rows",
            ChatColor.GRAY + "Click here to deposit all valid notes",
            ChatColor.GRAY + "Non-note items will be returned to your inventory"
        ));
        depositBtn.setItemMeta(depositMeta);
        inv.setItem(49, depositBtn);

        // Back button (Redstone Block) at slot 45
        ItemStack back = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        // Top 5 rows are empty for players to place items
        return inv;
    }

    // ====================================================
    // EVENT LISTENERS
    // ====================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof CashInventoryHolder holder)) return;
        
        if (!holder.getViewerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "This inventory does not belong to you!");
            return;
        }

        CashInventoryHolder.Type type = holder.getType();
        int slot = event.getSlot();
        int page = holder.getPage();

        // For deposit menu, allow free movement in top 5 rows
        if (type == CashInventoryHolder.Type.DEPOSIT_MENU) {
            if (slot >= 45) {
                // Bottom row - handle button clicks
                event.setCancelled(true);
                
                if (slot == 45) { // Back button
                    player.openInventory(buildMainMenu(player));
                } else if (slot == 49) { // Deposit button
                    processDeposit(player, inv);
                }
            } else {
                // Top 5 rows - check if the item being placed is a note
                ItemStack currentItem = event.getCurrentItem();
                ItemStack cursorItem = event.getCursor();
                ItemStack hotbarItem = null;
                
                if (event.getHotbarButton() != -1) {
                    hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                }
                
                // Check if any item involved is not a note
                boolean hasNonNote = false;
                
                if (currentItem != null && !isNoteItem(currentItem) && currentItem.getType() != Material.AIR) {
                    hasNonNote = true;
                }
                
                if (cursorItem != null && !isNoteItem(cursorItem) && cursorItem.getType() != Material.AIR) {
                    hasNonNote = true;
                }
                
                if (hotbarItem != null && !isNoteItem(hotbarItem) && hotbarItem.getType() != Material.AIR) {
                    hasNonNote = true;
                }
                
                // If trying to place a non-note item, cancel the event and return the item
                if (hasNonNote) {
                    event.setCancelled(true);
                    
                    // Return the item to the player's inventory or cursor
                    if (cursorItem != null && !isNoteItem(cursorItem) && cursorItem.getType() != Material.AIR) {
                        player.getInventory().addItem(cursorItem.clone());
                        player.setItemOnCursor(null);
                        player.sendMessage(ChatColor.RED + "Only registered notes can be placed in the deposit menu!");
                    }
                    
                    if (hotbarItem != null && !isNoteItem(hotbarItem) && hotbarItem.getType() != Material.AIR) {
                        // The item will remain in the hotbar
                        player.sendMessage(ChatColor.RED + "Only registered notes can be placed in the deposit menu!");
                    }
                } else {
                    // Allow placement of notes
                    return;
                }
            }
            return;
        }

        // For other menus, cancel all clicks
        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        switch (type) {
            case MAIN_MENU:
                if (slot == 11) { // Withdraw
                    if (!hasPlayerCard(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
                        player.closeInventory();
                        return;
                    }
                    player.openInventory(buildWithdrawMenu(player, 0));
                } else if (slot == 15) { // Deposit
                    if (!hasPlayerCard(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
                        player.closeInventory();
                        return;
                    }
                    player.openInventory(buildDepositMenu(player));
                }
                break;

            case WITHDRAW_MENU:
                handleWithdrawSelection(player, holder, slot, event.getCurrentItem(), event.isLeftClick(), event.isRightClick(), event.isShiftClick());
                break;
        }
    }

    private void handleWithdrawSelection(Player player, CashInventoryHolder holder, int slot, 
                                         ItemStack clickedItem, boolean leftClick, boolean rightClick, boolean shiftClick) {
        int page = holder.getPage();
        Inventory inv = player.getOpenInventory().getTopInventory();

        // Handle navigation and action buttons
        if (slot == 45) { // Back button
            player.openInventory(buildMainMenu(player));
            return;
        }

        if (slot == 49) { // Withdraw button
            PlayerSession session = playerSessions.get(player.getUniqueId());
            if (session != null && !session.withdrawSelections.isEmpty()) {
                getLogger().info("Withdraw button clicked, selections: " + session.withdrawSelections.toString());
                processWithdraw(player, new HashMap<>(session.withdrawSelections), inv);
            } else {
                player.sendMessage(ChatColor.RED + "No notes selected!");
                getLogger().info("Withdraw button clicked but no selections for " + player.getName());
            }
            return;
        }

        if (slot == 47 && page > 0) { // Previous page
            player.openInventory(buildWithdrawMenu(player, page - 1));
            return;
        }

        if (slot == 51) { // Next page
            player.openInventory(buildWithdrawMenu(player, page + 1));
            return;
        }

        if (slot >= 45) return; // Other bottom row slots

        // Extract ID using NBT
        String noteId = getNoteIdFromGuiItem(clickedItem);
        if (noteId == null) {
            getLogger().warning("Could not extract note ID from clicked item");
            return;
        }
        
        getLogger().info("CLICK NOTE ID: " + noteId);

        Note note = notesData.notes.get(noteId);
        if (note == null) {
            player.sendMessage(ChatColor.RED + "This note is no longer available!");
            player.openInventory(buildWithdrawMenu(player, page));
            return;
        }

        PlayerSession session = playerSessions.computeIfAbsent(player.getUniqueId(), 
            k -> new PlayerSession(player.getUniqueId()));
        
        int current = session.withdrawSelections.getOrDefault(noteId, 0);
        int change = 0;

        if (leftClick) {
            if (shiftClick) {
                change = 10; // +10 with shift+left
            } else {
                change = 1; // +1 with left click
            }
        } else if (rightClick) {
            if (shiftClick) {
                change = -10; // -10 with shift+right
            } else {
                change = -1; // -1 with right click
            }
        }

        int newValue = Math.max(0, current + change);
        
        if (newValue > 0) {
            session.withdrawSelections.put(noteId, newValue);
            getLogger().info("Updated selection for " + player.getName() + " - Note: " + noteId + " from " + current + " to " + newValue);
        } else {
            session.withdrawSelections.remove(noteId);
            getLogger().info("Removed selection for " + player.getName() + " - Note: " + noteId);
        }
        
        getLogger().info("SELECTIONS: " + session.withdrawSelections);

        // Update the menu without recreating it
        updateWithdrawMenu(player, inv, page);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CashInventoryHolder) {
            CashInventoryHolder holder = (CashInventoryHolder) event.getInventory().getHolder();
            
            // Allow drag in deposit menu's top 5 rows
            if (holder.getType() == CashInventoryHolder.Type.DEPOSIT_MENU) {
                // Check if any of the dragged items are not notes
                for (ItemStack item : event.getNewItems().values()) {
                    if (item != null && !isNoteItem(item) && item.getType() != Material.AIR) {
                        event.setCancelled(true);
                        ((Player) event.getWhoClicked()).sendMessage(ChatColor.RED + "Only registered notes can be placed in the deposit menu!");
                        return;
                    }
                }
                
                // Check if any slot in the bottom row is being targeted
                for (Integer slot : event.getRawSlots()) {
                    if (slot >= 45) {
                        event.setCancelled(true);
                        return;
                    }
                }
                return;
            }
            
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof CashInventoryHolder) {
            CashInventoryHolder holder = (CashInventoryHolder) event.getInventory().getHolder();
            
            // For deposit menu, return any items left behind
            if (holder.getType() == CashInventoryHolder.Type.DEPOSIT_MENU) {
                Player player = (Player) event.getPlayer();
                Inventory inv = event.getInventory();
                
                // Usar um mapa para rastrear itens por slot e evitar duplicação
                Map<Integer, ItemStack> itemsToReturn = new HashMap<>();
                
                for (int i = 0; i < 45; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.getType() != Material.AIR) {
                        // Guardar o item original do slot
                        itemsToReturn.put(i, item.clone());
                        // Limpar o slot imediatamente para evitar duplicação
                        inv.setItem(i, null);
                    }
                }
                
                // Devolver os itens ao jogador
                for (ItemStack item : itemsToReturn.values()) {
                    if (item != null) {
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                        if (!leftover.isEmpty()) {
                            for (ItemStack drop : leftover.values()) {
                                if (drop != null) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                                }
                            }
                        }
                    }
                }
            }
            
            // NÃO limpar seleções ao fechar - mantém para próxima vez
            // NÃO remover sessão ao fechar
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Clear cache for this player
        cardCheckCache.remove(event.getPlayer().getUniqueId());
        cardCheckTimestamp.remove(event.getPlayer().getUniqueId());
        playerClickCooldown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Limpar apenas ao sair do servidor
        playerSessions.remove(event.getPlayer().getUniqueId());
        playerClickCooldown.remove(event.getPlayer().getUniqueId());
    }

    // ====================================================
    // COMMAND HANDLER
    // ====================================================
    public class CashCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            
            try {
                // Console command: /cash open <player>
                if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
                    if (!sender.hasPermission("coincash.admin") && !sender.isOp()) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                        return true;
                    }
                    
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Player not found or not online!");
                        return true;
                    }
                    
                    openCashMenu(target);
                    sender.sendMessage(ChatColor.GREEN + "Opened CoinCash menu for " + target.getName());
                    return true;
                }

                // Player command
                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be used by players.");
                    return true;
                }

                Player player = (Player) sender;

                if (args.length == 0) {
                    openCashMenu(player);
                    return true;
                }

                switch (args[0].toLowerCase()) {
                    case "reload":
                        if (!player.hasPermission("coincash.admin") && !player.isOp()) {
                            player.sendMessage(ChatColor.RED + "You don't have permission!");
                            return true;
                        }
                        loadConfig();
                        loadNotesData();
                        player.sendMessage(ChatColor.GREEN + "CoinCash configuration reloaded!");
                        break;

                    case "register":
                        if (!player.hasPermission("coincash.admin") && !player.isOp()) {
                            player.sendMessage(ChatColor.RED + "You don't have permission!");
                            return true;
                        }
                        handleRegisterCommand(player, args);
                        break;

                    case "remove":
                        if (!player.hasPermission("coincash.admin") && !player.isOp()) {
                            player.sendMessage(ChatColor.RED + "You don't have permission!");
                            return true;
                        }
                        handleRemoveCommand(player, args);
                        break;

                    case "list":
                        if (!player.hasPermission("coincash.admin") && !player.isOp()) {
                            player.sendMessage(ChatColor.RED + "You don't have permission!");
                            return true;
                        }
                        handleListCommand(player);
                        break;

                    default:
                        player.sendMessage(ChatColor.RED + "Unknown command. Use:");
                        player.sendMessage(ChatColor.YELLOW + "/cash - Open the menu");
                        player.sendMessage(ChatColor.YELLOW + "/cash register <worth> [name] - Register held item as note");
                        player.sendMessage(ChatColor.YELLOW + "/cash remove <note> - Remove a note");
                        player.sendMessage(ChatColor.YELLOW + "/cash list - List all notes");
                        player.sendMessage(ChatColor.YELLOW + "/cash reload - Reload config");
                        player.sendMessage(ChatColor.YELLOW + "/cash open <player> - Open menu for another player");
                        break;
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "An error occurred while executing the command.");
                getLogger().severe("Error in /cash command: " + e.getMessage());
                e.printStackTrace();
            }

            return true;
        }

        private void openCashMenu(Player player) {
            if (!hasPlayerCard(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
                return;
            }
            player.openInventory(buildMainMenu(player));
        }

        private void handleRegisterCommand(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /cash register <worth> [name]");
                return;
            }

            ItemStack heldItem = player.getInventory().getItemInMainHand();
            if (heldItem == null || heldItem.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must hold an item to register!");
                return;
            }

            BigDecimal worth;
            try {
                worth = new BigDecimal(args[1]);
                worth = worth.setScale(8, RoundingMode.DOWN);
                if (worth.compareTo(BigDecimal.ZERO) <= 0) {
                    player.sendMessage(ChatColor.RED + "Worth must be positive!");
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid worth amount! Use numbers like 0.00000001 or 1.5");
                return;
            }

            String name;
            if (args.length >= 3) {
                name = ChatColor.translateAlternateColorCodes('&', args[2]);
            } else {
                ItemMeta meta = heldItem.getItemMeta();
                name = meta.hasDisplayName() ? meta.getDisplayName() : 
                       heldItem.getType().toString().replace('_', ' ').toLowerCase();
            }

            ItemStack itemToRegister = heldItem.clone();
            itemToRegister.setAmount(1);

            if (registerNoteFromItem(itemToRegister, worth, name)) {
                player.sendMessage(ChatColor.GREEN + "Note registered successfully!");
                player.sendMessage(ChatColor.GRAY + "Name: " + ChatColor.WHITE + name);
                player.sendMessage(ChatColor.GRAY + "Worth: " + ChatColor.YELLOW + formatCoin(worth));
                player.sendMessage(ChatColor.GRAY + "Total notes: " + ChatColor.WHITE + notesData.notes.size());
            } else {
                player.sendMessage(ChatColor.RED + "Failed to register note!");
            }
        }

        private void handleRemoveCommand(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /cash remove <note_id>");
                return;
            }

            String noteId = args[1];
            
            String fullId = null;
            for (String id : notesData.notes.keySet()) {
                if (id.startsWith(noteId) || id.contains(noteId)) {
                    fullId = id;
                    break;
                }
            }

            if (fullId == null) {
                player.sendMessage(ChatColor.RED + "Note not found!");
                return;
            }

            Note removed = notesData.notes.get(fullId);
            if (removeNote(fullId)) {
                player.sendMessage(ChatColor.GREEN + "Note removed successfully!");
                player.sendMessage(ChatColor.GRAY + "Name: " + ChatColor.WHITE + removed.name);
                player.sendMessage(ChatColor.GRAY + "Worth: " + ChatColor.YELLOW + formatCoin(removed.worth));
                player.sendMessage(ChatColor.GRAY + "ID: " + ChatColor.WHITE + fullId);
            } else {
                player.sendMessage(ChatColor.RED + "Failed to remove note!");
            }
        }

        private void handleListCommand(Player player) {
            List<Note> notes = getAllNotes();
            
            player.sendMessage(ChatColor.GOLD + "=== Registered Notes (" + notes.size() + ") ===");
            
            if (notes.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "No notes registered.");
                return;
            }

            for (Note note : notes) {
                player.sendMessage(ChatColor.YELLOW + "ID: " + ChatColor.WHITE + note.id.substring(0, 8) + "...");
                player.sendMessage(ChatColor.GRAY + "  Name: " + ChatColor.WHITE + note.name);
                player.sendMessage(ChatColor.GRAY + "  Worth: " + ChatColor.YELLOW + formatCoin(note.worth));
                player.sendMessage(ChatColor.GRAY + "  Material: " + ChatColor.WHITE + note.item.getType().toString());
                player.sendMessage("");
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                completions.add("open");
                completions.add("reload");
                completions.add("register");
                completions.add("remove");
                completions.add("list");
                
                return completions.stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("open")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                
                if (args[0].equalsIgnoreCase("remove")) {
                    return notesData.notes.keySet().stream()
                            .map(id -> id.length() > 8 ? id.substring(0, 8) + "..." : id)
                            .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }

                if (args[0].equalsIgnoreCase("register")) {
                    completions.add("0.00000001");
                    completions.add("0.000001");
                    completions.add("0.0001");
                    completions.add("0.01");
                    completions.add("1.0");
                    completions.add("10.0");
                    completions.add("100.0");
                    
                    return completions.stream()
                            .filter(s -> s.startsWith(args[1]))
                            .collect(Collectors.toList());
                }
            }

            if (args.length == 3 && args[0].equalsIgnoreCase("register")) {
                completions.add("\"&eNote Name\"");
                completions.add("\"&6Gold Note\"");
                completions.add("\"&bDiamond Note\"");
                
                return completions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }

            return completions;
        }
    }

    // ====================================================
    // UTILITY METHODS
    // ====================================================
    private String formatCoin(BigDecimal amount) {
        if (amount == null) return "0";
        String formatted = COIN_FORMAT.format(amount);
        if (!formatted.contains(".")) {
            formatted += ".0";
        }
        return formatted;
    }

    // ====================================================
    // DATA CLASSES
    // ====================================================
    private static class NotesData {
        Map<String, Note> notes = new ConcurrentHashMap<>();
    }

    private static class Note {
        String id;
        ItemStack item;
        BigDecimal worth;
        String name;
        long createdAt;

        Note(String id, ItemStack item, BigDecimal worth, String name, long createdAt) {
            this.id = id;
            this.item = item;
            this.worth = worth;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    private static class WithdrawTransaction {
        String id;
        String playerCard;
        BigDecimal amount;

        WithdrawTransaction(String id, String playerCard, BigDecimal amount) {
            this.id = id;
            this.playerCard = playerCard;
            this.amount = amount;
        }
    }

    private static class DepositTransaction {
        String id;
        String playerCard;
        BigDecimal amount;

        DepositTransaction(String id, String playerCard, BigDecimal amount) {
            this.id = id;
            this.playerCard = playerCard;
            this.amount = amount;
        }
    }

    private static class PendingWithdraw {
        String transactionId;
        UUID playerUuid;
        List<ItemStack> items;
        BigDecimal amount;
        boolean completed;
        String txId;

        PendingWithdraw(String transactionId, UUID playerUuid, List<ItemStack> items, BigDecimal amount) {
            this.transactionId = transactionId;
            this.playerUuid = playerUuid;
            this.items = items != null ? items : new ArrayList<>();
            this.amount = amount;
            this.completed = false;
        }
    }

    private static class PendingDeposit {
        String transactionId;
        UUID playerUuid;
        List<ItemStack> items;
        BigDecimal amount;
        boolean completed;
        String txId;

        PendingDeposit(String transactionId, UUID playerUuid, List<ItemStack> items, BigDecimal amount) {
            this.transactionId = transactionId;
            this.playerUuid = playerUuid;
            this.items = items != null ? items : new ArrayList<>();
            this.amount = amount;
            this.completed = false;
        }
    }

    private static class PlayerSession {
        UUID uuid;
        long lastAction;
        BigDecimal lastWithdrawAmount;
        BigDecimal lastDepositAmount;
        Map<String, Integer> withdrawSelections;
        Map<String, Object> data = new HashMap<>();

        PlayerSession(UUID uuid) {
            this.uuid = uuid;
            this.lastAction = System.currentTimeMillis();
            this.lastWithdrawAmount = BigDecimal.ZERO;
            this.lastDepositAmount = BigDecimal.ZERO;
            this.withdrawSelections = new HashMap<>();
        }
    }

    // ====================================================
    // BALANCE CHECK CALLBACK INTERFACE
    // ====================================================
    private interface BalanceCheckCallback {
        void onSuccess(BigDecimal balance);
        void onFailure(String error);
    }

    // ====================================================
    // INVENTORY HOLDER
    // ====================================================
    public static class CashInventoryHolder implements InventoryHolder {

        public enum Type {
            MAIN_MENU,
            WITHDRAW_MENU,
            DEPOSIT_MENU
        }

        private final Type type;
        private final String category;
        private final int page;
        private final UUID viewerUuid;

        public CashInventoryHolder(Type type, String category, int page, UUID viewerUuid) {
            this.type = type;
            this.category = category;
            this.page = page;
            this.viewerUuid = viewerUuid;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }

        public Type getType() {
            return type;
        }

        public String getCategory() {
            return category;
        }

        public int getPage() {
            return page;
        }

        public UUID getViewerUuid() {
            return viewerUuid;
        }
    }
}
