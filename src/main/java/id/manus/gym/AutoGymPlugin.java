package id.manus.gym;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AutoGymPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, GymType> gyms = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File playerDataFile;
    private YamlConfiguration playerData;
    private NamespacedKey typeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "gym_type");
        loadGymTypes();
        loadPlayerData();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("gym") != null) {
            getCommand("gym").setExecutor(this);
            getCommand("gym").setTabCompleter(this);
        }
        getLogger().info("AutoGym aktif: " + gyms.size() + " tipe latihan dimuat.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) finishSession(player, false, false);
            else {
                Session session = sessions.remove(id);
                if (session != null) session.task.cancel();
            }
        }
        savePlayerData();
    }

    private void loadGymTypes() {
        gyms.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("gyms");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path = "gyms." + key + ".";
            Material material = Material.matchMaterial(getConfig().getString(path + "material", "PAPER"));
            String stat = getConfig().getString(path + "stat", "").toLowerCase(Locale.ROOT);
            int modelData = getConfig().getInt(path + "custom-model-data", -1);
            int seconds = Math.max(1, getConfig().getInt(path + "seconds", 30));
            String name = ChatColor.translateAlternateColorCodes('&', getConfig().getString(path + "display-name", key));
            if (material == null || modelData < 0 || !List.of("strength", "agility", "defense").contains(stat)) {
                getLogger().warning("Konfigurasi gym tidak valid untuk: " + key);
                continue;
            }
            gyms.put(key.toLowerCase(Locale.ROOT), new GymType(key.toLowerCase(Locale.ROOT), name, material, modelData, seconds, stat));
        }
    }

    private void loadPlayerData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        playerDataFile = new File(getDataFolder(), "player-data.yml");
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        if (playerData == null) return;
        try {
            playerData.save(playerDataFile);
        } catch (IOException exception) {
            getLogger().severe("Gagal menyimpan player-data.yml: " + exception.getMessage());
        }
    }

    private int getStatTenths(UUID uuid, String stat) {
        return playerData.getInt(uuid + ".tenths." + stat, 0);
    }

    private String statLabel(String key) {
        return switch (key) {
            case "strength" -> "Strength";
            case "agility" -> "Agility";
            case "defense" -> "Defense";
            default -> key;
        };
    }

    private String typeFromItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasCustomModelData()) return null;
        int id = meta.getCustomModelData();
        for (GymType gym : gyms.values()) {
            if (gym.material == item.getType() && gym.modelData == id) return gym.key;
        }
        return null;
    }

    @EventHandler
    public void onGymClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        String type = typeFromItem(event.getItem());
        if (type == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Kamu masih menjalani sesi gym.");
            return;
        }
        GymType gym = gyms.get(type);
        if (getStatTenths(player.getUniqueId(), gym.stat) >= 500) {
            player.sendMessage(ChatColor.YELLOW + statLabel(gym.stat) + " kamu sudah mencapai level maksimum 50.0.");
            return;
        }
        startSession(player, gym);
    }

    private void startSession(Player player, GymType gym) {
        UUID id = player.getUniqueId();
        Session session = new Session(gym, System.currentTimeMillis(), player.getWalkSpeed(), player.getLocation().getYaw(), player.getLocation().getPitch());
        sessions.put(id, session);
        player.setSprinting(false);
        player.showTitle(Title.title(
                Component.text(ChatColor.stripColor(gym.displayName), NamedTextColor.WHITE),
                Component.text("Bersiap untuk latihan", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(gym.seconds * 1000L), Duration.ofMillis(300))
        ));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.15f);
        player.sendMessage(ChatColor.GREEN + "Sesi " + ChatColor.stripColor(gym.displayName) + ChatColor.GREEN + " dimulai otomatis.");

        session.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !sessions.containsKey(id)) {
                    cancel();
                    return;
                }
                // Subtle fixed-frame camera sway: tiny pitch movement only, never orbiting.
                if (getConfig().getBoolean("camera.enabled", true)) {
                    int cycleTicks = Math.max(20, getConfig().getInt("camera.cycle-ticks", 48));
                    double amplitude = Math.max(0.0, Math.min(2.0, getConfig().getDouble("camera.pitch-amplitude-degrees", 0.45)));
                    double phase = (session.cameraTick % cycleTicks) / (double) cycleTicks * Math.PI * 2.0;
                    float pitch = (float) (session.originalPitch + Math.sin(phase) * amplitude);
                    player.setRotation(session.originalYaw, pitch);
                    session.cameraTick += 4;
                }
                long elapsed = System.currentTimeMillis() - session.startedAt;
                double fraction = Math.min(1.0, (double) elapsed / (gym.seconds * 1000L));
                int percent = (int) Math.floor(fraction * 100.0);
                showProgress(player, gym, percent);
                if (fraction >= 1.0) finishSession(player, true, true);
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    private void showProgress(Player player, GymType gym, int percent) {
        int length = Math.max(5, Math.min(40, getConfig().getInt("ui.progress-bar-length", 20)));
        int filled = (int) Math.round(length * (percent / 100.0));
        Component bar = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("█".repeat(filled), NamedTextColor.GREEN))
                .append(Component.text("█".repeat(length - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(percent + "%", percent >= 100 ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        player.sendActionBar(Component.text(ChatColor.stripColor(gym.displayName) + "  ", NamedTextColor.WHITE).append(bar));
    }

    private void finishSession(Player player, boolean completed, boolean message) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        if (session.task != null) session.task.cancel();
        player.setWalkSpeed(session.oldWalkSpeed);
        // Restore the exact view direction captured when the workout began.
        if (player.isOnline() && !player.isDead()) player.setRotation(session.originalYaw, session.originalPitch);
        if (!completed) {
            player.sendActionBar(Component.empty());
            if (message && player.isOnline()) player.sendMessage(ChatColor.RED + "Sesi gym dibatalkan; stat tidak bertambah.");
            return;
        }

        int before = getStatTenths(player.getUniqueId(), session.gym.stat);
        int after = Math.min(500, before + 1);
        playerData.set(player.getUniqueId() + ".tenths." + session.gym.stat, after);
        savePlayerData();
        showProgress(player, session.gym, 100);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.65f, 1.25f);
        player.sendMessage(ChatColor.GREEN + "Latihan selesai! " + statLabel(session.gym.stat) + ": " + formatLevel(before) + " -> " + formatLevel(after) + " / 50.0");
    }

    private String formatLevel(int tenths) {
        return String.format(Locale.US, "%.1f", tenths / 10.0);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("ui.lock-movement-during-workout", true)) return;
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom().clone().setDirection(event.getTo().getDirection()));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!getConfig().getBoolean("ui.cancel-on-damage", false)) return;
        if (sessions.containsKey(player.getUniqueId())) finishSession(player, false, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        finishSession(event.getPlayer(), false, false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        finishSession(event.getEntity(), false, true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Gunakan /gym stats sebagai pemain.");
                return true;
            }
            UUID id = player.getUniqueId();
            sender.sendMessage(ChatColor.GOLD + "=== Statistik Gym ===");
            for (String stat : List.of("strength", "agility", "defense")) {
                sender.sendMessage(ChatColor.YELLOW + statLabel(stat) + ": " + ChatColor.WHITE + formatLevel(getStatTenths(id, stat)) + " / 50.0");
            }
            return true;
        }
        if (!args[0].equalsIgnoreCase("give") && !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /gym stats, /gym give <benchpress|treadmill|pullup> [pemain], /gym reload");
            return true;
        }
        if (!sender.hasPermission("gym.admin")) {
            sender.sendMessage(ChatColor.RED + "Kamu tidak memiliki izin gym.admin.");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadGymTypes();
            sender.sendMessage(ChatColor.GREEN + "Konfigurasi AutoGym dimuat ulang.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /gym give <benchpress|treadmill|pullup> [pemain]");
            return true;
        }
        GymType gym = gyms.get(args[1].toLowerCase(Locale.ROOT));
        if (gym == null) {
            sender.sendMessage(ChatColor.RED + "Tipe gym tidak ditemukan. Tipe: " + String.join(", ", gyms.keySet()));
            return true;
        }
        Player target;
        if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
        else target = sender instanceof Player player ? player : null;
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Pemain tidak ditemukan. Dari console, tentukan nama pemain.");
            return true;
        }
        ItemStack item = new ItemStack(gym.material);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(gym.modelData);
        meta.setDisplayName(gym.displayName);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, gym.key);
        item.setItemMeta(meta);
        target.getInventory().addItem(item);
        sender.sendMessage(ChatColor.GREEN + "Item " + ChatColor.stripColor(gym.displayName) + " diberikan kepada " + target.getName() + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("stats", "give", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return gyms.keySet().stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private record GymType(String key, String displayName, Material material, int modelData, int seconds, String stat) { }

    private static final class Session {
        private final GymType gym;
        private final long startedAt;
        private final float oldWalkSpeed;
        private final float originalYaw;
        private final float originalPitch;
        private int cameraTick;
        private BukkitTask task;

        private Session(GymType gym, long startedAt, float oldWalkSpeed, float originalYaw, float originalPitch) {
            this.gym = gym;
            this.startedAt = startedAt;
            this.oldWalkSpeed = oldWalkSpeed;
            this.originalYaw = originalYaw;
            this.originalPitch = originalPitch;
        }
    }
}
