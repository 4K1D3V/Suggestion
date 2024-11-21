package kitesolutions.gg.suggestion;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;

@SuppressWarnings("ALL")
public class Suggestion extends JavaPlugin {

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration adminLogs;
    private Connection databaseConnection;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> suggestionsCount = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("╔═══════════════════════════════════════════════════════════╗");
        getLogger().info("║                      Suggestion                           ║");
        getLogger().info("║                    Created by Kit (v1.0)                  ║");
        getLogger().info("║               Welcome to the Future of Minecraft!         ║");
        getLogger().info("║               Your suggestions shape the server!          ║");
        getLogger().info("╚═══════════════════════════════════════════════════════════╝");
        getLogger().info("SuggestionPlugin has been enabled!");

        saveDefaultConfig();
        reloadConfigurations();
        setupDatabase();
        registerCommands();
    }

    @Override
    public void onDisable() {
        getLogger().info("╔═══════════════════════════════════════════════════════════╗");
        getLogger().info("║                      Suggestion                           ║");
        getLogger().info("║                    Created by Kit (v1.0)                  ║");
        getLogger().info("║               Thank you for using the plugin!             ║");
        getLogger().info("║              Your suggestions make a difference!          ║");
        getLogger().info("╚═══════════════════════════════════════════════════════════╝");
        getLogger().info("SuggestionPlugin has been disabled.");

        closeDatabaseConnection();
        saveAdminLogs();
    }

    private void reloadConfigurations() {
        this.config = getConfig();
        File messagesFile = new File(getDataFolder(), "config.yml");
        if (!messagesFile.exists()) saveResource("config.yml", false);
        this.messages = YamlConfiguration.loadConfiguration(messagesFile);

        File adminLogsFile = new File(getDataFolder(), "admin_log.yml");
        if (!adminLogsFile.exists()) {
            try {
                adminLogsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Failed to create admin_log.yml!");
            }
        }
        this.adminLogs = YamlConfiguration.loadConfiguration(adminLogsFile);
    }

    private void saveAdminLogs() {
        try {
            adminLogs.save(new File(getDataFolder(), "admin_log.yml"));
        } catch (IOException e) {
            getLogger().severe("Failed to save admin_log.yml!");
        }
    }

    private void setupDatabase() {
        String host = config.getString("database.host");
        String port = config.getString("database.port");
        String dbName = config.getString("database.name");
        String username = config.getString("database.username");
        String password = config.getString("database.password");

        try {
            databaseConnection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + dbName, username, password
            );
            createDatabaseTables();
            getLogger().info("Connected to the database successfully.");
        } catch (SQLException e) {
            getLogger().severe("Error connecting to the database: " + e.getMessage());
        }
    }

    private void createDatabaseTables() throws SQLException {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS suggestions (
                id INT AUTO_INCREMENT PRIMARY KEY,
                player_uuid VARCHAR(36),
                category VARCHAR(50),
                suggestion TEXT,
                priority BOOLEAN DEFAULT FALSE,
                votes INT DEFAULT 0,
                status ENUM('pending', 'approved', 'rejected'),
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                admin_comments TEXT
            );
        """;
        try (Statement stmt = databaseConnection.createStatement()) {
            stmt.executeUpdate(createTableSQL);
        }
    }

    private void closeDatabaseConnection() {
        try {
            if (databaseConnection != null && !databaseConnection.isClosed()) {
                databaseConnection.close();
                getLogger().info("Database connection closed.");
            }
        } catch (SQLException e) {
            getLogger().severe("Error closing database connection: " + e.getMessage());
        }
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("suggest")).setExecutor(this::handleSuggestCommand);
        Objects.requireNonNull(getCommand("suggestions")).setExecutor(this::handleAdminCommand);
        Objects.requireNonNull(getCommand("vote")).setExecutor(this::handleVoteCommand);
        Objects.requireNonNull(getCommand("mysuggestions")).setExecutor(this::handleMySuggestionsCommand);
        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(this::handleLeaderboardCommand);
    }

    private boolean handleAdminCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can execute this command.");
            return false;
        }

        if (!player.hasPermission("suggestion.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return false;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /suggestions <list|approve|reject|view>");
            return false;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                listPendingSuggestions(player);
                return true;
            }
            case "approve" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /suggestions approve <id>");
                    return false;
                }
                approveSuggestion(player, args[1]);
                return true;
            }
            case "reject" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /suggestions reject <id> <reason>");
                    return false;
                }
                rejectSuggestion(player, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                return true;
            }
            case "view" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /suggestions view <id>");
                    return false;
                }
                viewSuggestion(player, args[1]);
                return true;
            }
            default -> {
                player.sendMessage(ChatColor.YELLOW + "Usage: /suggestions <list|approve|reject|view>");
                return false;
            }
        }
    }

    private void listPendingSuggestions(Player player) {
        String query = "SELECT id, suggestion, priority FROM suggestions WHERE status = 'pending'";
        try (Statement stmt = databaseConnection.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            player.sendMessage(ChatColor.YELLOW + "Pending Suggestions:");
            while (rs.next()) {
                String id = rs.getString("id");
                String suggestion = rs.getString("suggestion");
                boolean isPriority = rs.getBoolean("priority");
                String priorityTag = isPriority ? ChatColor.RED + "[HIGH PRIORITY] " : "";
                player.sendMessage(priorityTag + ChatColor.AQUA + "ID: " + id + " | " + suggestion);
            }
        } catch (SQLException e) {
            getLogger().severe("Error listing pending suggestions: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to fetch pending suggestions.");
        }
    }

    private void approveSuggestion(Player player, String id) {
        String updateQuery = "UPDATE suggestions SET status = 'approved' WHERE id = ?";
        try (PreparedStatement ps = databaseConnection.prepareStatement(updateQuery)) {
            ps.setInt(1, Integer.parseInt(id));
            int updated = ps.executeUpdate();
            if (updated > 0) {
                player.sendMessage(ChatColor.GREEN + "Suggestion " + id + " has been approved.");
                logAdminAction(player, "approved", id);
            } else {
                player.sendMessage(ChatColor.RED + "Suggestion " + id + " not found.");
            }
        } catch (SQLException e) {
            getLogger().severe("Error approving suggestion: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to approve suggestion.");
        }
    }

    private void rejectSuggestion(Player player, String id, String reason) {
        String updateQuery = "UPDATE suggestions SET status = 'rejected', admin_comments = ? WHERE id = ?";
        try (PreparedStatement ps = databaseConnection.prepareStatement(updateQuery)) {
            ps.setString(1, reason);
            ps.setInt(2, Integer.parseInt(id));
            int updated = ps.executeUpdate();
            if (updated > 0) {
                player.sendMessage(ChatColor.GREEN + "Suggestion " + id + " has been rejected with reason: " + reason);
                logAdminAction(player, "rejected", id + " (Reason: " + reason + ")");
            } else {
                player.sendMessage(ChatColor.RED + "Suggestion " + id + " not found.");
            }
        } catch (SQLException e) {
            getLogger().severe("Error rejecting suggestion: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to reject suggestion.");
        }
    }

    private void viewSuggestion(Player player, String id) {
        String query = "SELECT * FROM suggestions WHERE id = ?";
        try (PreparedStatement ps = databaseConnection.prepareStatement(query)) {
            ps.setInt(1, Integer.parseInt(id));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String suggestion = rs.getString("suggestion");
                String status = rs.getString("status");
                String comments = rs.getString("admin_comments");
                player.sendMessage(ChatColor.YELLOW + "Suggestion Details:");
                player.sendMessage(ChatColor.AQUA + "ID: " + id + " | " + suggestion);
                player.sendMessage(ChatColor.GOLD + "Status: " + status);
                if (comments != null) player.sendMessage(ChatColor.GRAY + "Comments: " + comments);
            } else {
                player.sendMessage(ChatColor.RED + "Suggestion " + id + " not found.");
            }
        } catch (SQLException e) {
            getLogger().severe("Error viewing suggestion: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to fetch suggestion details.");
        }
    }

    private void logAdminAction(Player admin, String action, String details) {
        adminLogs.set("actions." + System.currentTimeMillis(), admin.getName() + " " + action + " " + details);
        saveAdminLogs();
    }

    private boolean handleVoteCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can vote on suggestions.");
            return false;
        }

        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /vote <id> <up/down>");
            return false;
        }

        String id = args[0];
        String voteType = args[1].toLowerCase();

        if (!voteType.equals("up") && !voteType.equals("down")) {
            player.sendMessage(ChatColor.RED + "Invalid vote type. Use 'up' or 'down'.");
            return false;
        }

        try {
            int voteChange = voteType.equals("up") ? 1 : -1;
            String updateVoteQuery = "UPDATE suggestions SET votes = votes + ? WHERE id = ?";
            try (PreparedStatement ps = databaseConnection.prepareStatement(updateVoteQuery)) {
                ps.setInt(1, voteChange);
                ps.setInt(2, Integer.parseInt(id));
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    player.sendMessage(ChatColor.GREEN + "You have voted " + voteType + " for suggestion " + id + ".");
                } else {
                    player.sendMessage(ChatColor.RED + "Suggestion " + id + " not found.");
                }
            }
        } catch (SQLException | NumberFormatException e) {
            getLogger().severe("Error voting on suggestion: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to register your vote.");
        }
        return true;
    }

    private boolean handleMySuggestionsCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can view their suggestions.");
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        String query = "SELECT id, suggestion, status FROM suggestions WHERE player_uuid = ?";
        try (PreparedStatement ps = databaseConnection.prepareStatement(query)) {
            ps.setString(1, playerUUID.toString());
            ResultSet rs = ps.executeQuery();

            player.sendMessage(ChatColor.YELLOW + "Your Suggestions:");
            while (rs.next()) {
                String id = rs.getString("id");
                String suggestion = rs.getString("suggestion");
                String status = rs.getString("status");
                player.sendMessage(ChatColor.AQUA + "ID: " + id + " | Suggestion: " + suggestion + " | Status: " + status);
            }
        } catch (SQLException e) {
            getLogger().severe("Error fetching player suggestions: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to fetch your suggestions.");
        }
        return true;
    }

    private boolean handleLeaderboardCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can view the leaderboard.");
            return false;
        }

        // Get the number of top players to show (default is 10)
        int topLimit = 10;
        if (args.length > 0) {
            try {
                topLimit = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid number format. Showing the top 10.");
            }
        }

        String query = "SELECT player_uuid, COUNT(*) AS suggestion_count FROM suggestions WHERE status = 'approved' GROUP BY player_uuid ORDER BY suggestion_count DESC LIMIT ?";
        try (PreparedStatement ps = databaseConnection.prepareStatement(query)) {
            ps.setInt(1, topLimit);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                player.sendMessage(ChatColor.RED + "No approved suggestions found.");
                return false;
            }

            player.sendMessage(ChatColor.YELLOW + "Top Suggestion Contributors:");

            int rank = 1;
            do {
                String playerUUID = rs.getString("player_uuid");
                int suggestionCount = rs.getInt("suggestion_count");
                // Assuming you have a method to fetch player's name based on UUID.
                String playerName = getPlayerNameFromUUID(playerUUID);
                player.sendMessage(ChatColor.AQUA + "#" + rank + ": " + playerName + " - " + suggestionCount + " approved suggestions");
                rank++;
            } while (rs.next());

        } catch (SQLException e) {
            getLogger().severe("Error fetching leaderboard: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Failed to fetch leaderboard.");
        }

        return true;
    }

    private String getPlayerNameFromUUID(String uuid) {
        UUID playerUUID = UUID.fromString(uuid);
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            return player.getName();
        }
        // If the player is not online, you could fetch the name from the database if stored
        // or return "Unknown" if the player is offline and not found in the database.
        return "Unknown";
    }

    private boolean handleSuggestCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can submit suggestions.");
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        int cooldownTime = config.getInt("suggestion-cooldown");
        int maxSuggestions = config.getInt("max-suggestions-per-player");

        if (cooldowns.containsKey(playerUUID) && System.currentTimeMillis() - cooldowns.get(playerUUID) < cooldownTime * 1000L) {
            long timeLeft = (cooldownTime * 1000L - (System.currentTimeMillis() - cooldowns.get(playerUUID))) / 1000;
            player.sendMessage(ChatColor.RED + "You must wait " + timeLeft + " seconds before submitting another suggestion.");
            return false;
        }

        if (suggestionsCount.getOrDefault(playerUUID, 0) >= maxSuggestions) {
            sendMessage(player, "player.submission-fail");
            return false;
        }

        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /suggest <category> <suggestion>");
            return false;
        }

        boolean isPriority = args[args.length - 1].equalsIgnoreCase("priority");
        String category = args[0];
        String suggestion = String.join(" ", Arrays.copyOfRange(args, 1, args.length - (isPriority ? 1 : 0)));
        saveSuggestionToDatabase(playerUUID, category, suggestion, isPriority);

        cooldowns.put(playerUUID, System.currentTimeMillis());
        suggestionsCount.put(playerUUID, suggestionsCount.getOrDefault(playerUUID, 0) + 1);

        sendMessage(player, "player.submission-success");
        notifyDiscord(category, suggestion, player.getName(), isPriority);
        return true;
    }

    private void saveSuggestionToDatabase(UUID playerUUID, String category, String suggestion, boolean isPriority) {
        String insertSQL = "INSERT INTO suggestions (player_uuid, category, suggestion, priority, status) VALUES (?, ?, ?, ?, 'pending')";
        try (PreparedStatement ps = databaseConnection.prepareStatement(insertSQL)) {
            ps.setString(1, playerUUID.toString());
            ps.setString(2, category);
            ps.setString(3, suggestion);
            ps.setBoolean(4, isPriority);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error saving suggestion: " + e.getMessage());
        }
    }

    private void notifyDiscord(String category, String suggestion, String playerName, boolean isPriority) {
        String discordWebhookURL = config.getString("discord.webhook-url");
        if (discordWebhookURL != null && !discordWebhookURL.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                String priorityText = isPriority ? "[HIGH PRIORITY]" : "";
                getLogger().info("[Discord Webhook] New Suggestion by " + playerName + ": " + priorityText + "[" + category + "] " + suggestion);
            });
        }
    }

    private void sendMessage(Player player, String messageKey) {
        String message = messages.getString(messageKey, "Message not found.");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
