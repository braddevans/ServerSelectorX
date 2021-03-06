package xyz.derkades.serverselectorx;

import static org.bukkit.ChatColor.DARK_AQUA;
import static org.bukkit.ChatColor.DARK_GRAY;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.update.spiget.SpigetUpdate;
import org.inventivetalent.update.spiget.UpdateCallback;
import org.inventivetalent.update.spiget.comparator.VersionComparator;

import xyz.derkades.derkutils.Cooldown;
import xyz.derkades.derkutils.bukkit.Colors;
import xyz.derkades.serverselectorx.placeholders.Placeholders;
import xyz.derkades.serverselectorx.placeholders.PlaceholdersDisabled;
import xyz.derkades.serverselectorx.placeholders.PlaceholdersEnabled;

public class Main extends JavaPlugin {
	
	private static final int CONFIG_VERSION = 7;
	
	public static final int GLOWING_ENCHANTMENT_ID = 96;
	
	public static Placeholders PLACEHOLDER_API;
	
	public static Map<String, Map<String, Object>> SERVER_PLACEHOLDERS = new HashMap<>();
	
	public static final String PREFIX = DARK_GRAY + "[" + DARK_AQUA + "ServerSelectorX" + DARK_GRAY + "]";

	private static ConfigurationManager configurationManager;
	
	private static JavaPlugin plugin;
	
	public static JavaPlugin getPlugin(){
		return plugin;
	}
	
	@Override
	public void onEnable(){
		plugin = this;
		
		configurationManager = new ConfigurationManager();
		configurationManager.reloadAll();

		//Register listeners
		Bukkit.getPluginManager().registerEvents(new SelectorOpenListener(), this);
		Bukkit.getPluginManager().registerEvents(new OnJoinListener(), this);
		Bukkit.getPluginManager().registerEvents(new ItemMoveDropCancelListener(), this);
		
		
		List<String> offHandVersions = Arrays.asList("1.9", "1.10", "1.11", "1.12");
		for (String version : offHandVersions) {
			if (Bukkit.getBukkitVersion().contains(version)) {
				Bukkit.getPluginManager().registerEvents(new OffHandMoveCancel(), this);
			}
		}
		
		//Register messaging channels
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> PingServersBackground.startPinging());
		
		//Register command
		getCommand("serverselectorx").setExecutor(new ReloadCommand());
		
		//Start bStats
		Stats.initialize();
		
		//Check if config is up to date
		int version = getConfig().getInt("version");
		if (version != CONFIG_VERSION){
			Logger logger = super.getLogger();
			logger.log(Level.SEVERE, "************** IMPORTANT **************");
			logger.log(Level.SEVERE, "You updated the plugin without deleting the config.");
			logger.log(Level.SEVERE, "Please rename config.yml to something else and restart your server.");
			logger.log(Level.SEVERE, "If you don't want to redo your config, see resource updates on spigotmc.org for instructions.");
			logger.log(Level.SEVERE, "***************************************");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		
		//Register custom selector commands
		registerCommands();
		
		//Check if PlaceHolderAPI is installed
		if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){
			Main.PLACEHOLDER_API = new PlaceholdersEnabled();
			getLogger().log(Level.INFO, "PlaceholderAPI is found. Placeholders will work!");
		} else {
			Main.PLACEHOLDER_API = new PlaceholdersDisabled();
			getLogger().log(Level.INFO, "PlaceholderAPI is not installed. The plugin will still work.");
		}
		
		//Check for updates asynchronously
		getServer().getScheduler().runTaskAsynchronously(this, () -> {
			checkForUpdates();
		});
	}
	
	public void reloadConfig(){	
		//Load default config if it has been deleted
		super.saveDefaultConfig();
		
		//Reload config
		super.reloadConfig();
	
		//Copy default selector if directory is empty
		boolean createFile = new File(this.getDataFolder() + "/menu").listFiles().length == 0;
		File file = new File(this.getDataFolder() + "/menu", "default.yml");
		if (createFile){
			URL inputUrl = getClass().getResource("/xyz/derkades/serverselectorx/default-selector.yml");
			try {
				FileUtils.copyURLToFile(inputUrl, file);
			} catch (IOException e){
				e.printStackTrace();
			}
		}
		
		//Initialize variables
		ItemMoveDropCancelListener.DROP_PERMISSION_ENABLED = getConfig().getBoolean("cancel-item-drop", false);
		ItemMoveDropCancelListener.MOVE_PERMISSION_ENABLED = getConfig().getBoolean("cancel-item-move", false);
	}
	
	/**
	 * Registers all custom commands by going through all menu files and adding commands
	 */
	private void registerCommands(){
		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
	
			bukkitCommandMap.setAccessible(true);
			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
			
			for (FileConfiguration config : Main.getServerSelectorConfigurationFiles()){
				String commandName = config.getString("command");
				
				if (commandName == null || commandName.equalsIgnoreCase("none")) {
					continue;
				}
				
				commandMap.register("ssx-custom", new Command(commandName){
	
					@Override
					public boolean execute(CommandSender sender, String label, String[] args) {
						if (sender instanceof Player){
							Player player = (Player) sender;
							Main.openSelector(player, config);
						}
						return true;
					}
					
				});
	
			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	@Deprecated
	public static FileConfiguration getSelectorConfigurationFile(String name){
		File file = new File(Main.getPlugin().getDataFolder() + "/menu", name + ".yml");
		if (file.exists()){
			return YamlConfiguration.loadConfiguration(file);
		} else {
			return null;
		}
	}
	
	@Deprecated
	public static List<FileConfiguration> getServerSelectorConfigurationFiles(){
		List<FileConfiguration> configs = new ArrayList<>();
		for (File file : new File(Main.getPlugin().getDataFolder() + "/menu").listFiles()){
			if (!file.getName().endsWith(".yml"))
				continue;

			configs.add(YamlConfiguration.loadConfiguration(file));
		}
		return configs;
	}
	
	public static ConfigurationManager getConfigurationManager() {
		return configurationManager;
	}
	
	public static void openSelector(Player player, FileConfiguration config) {
		long cooldown = Cooldown.getCooldown(config.getName() + player.getName());
		if (cooldown > 0) {
			String cooldownMessage = Main.getPlugin().getConfig().getString("cooldown-message", "&cYou cannot use this yet, please wait {x} seconds.");
			cooldownMessage = cooldownMessage.replace("{x}", String.valueOf((cooldown / 1000) + 1));
			cooldownMessage = Colors.parseColors(cooldownMessage);
			if (!(cooldownMessage.equals("") || cooldownMessage.equals(" "))) { //Do not send message if message is an empty string
				player.sendMessage(cooldownMessage);
			}
			
			return;
		}
		
		long cooldownDuration = Main.getPlugin().getConfig().getLong("selector-open-cooldown", 0);	
		if (cooldownDuration >= 1000) {
			Cooldown.addCooldown(config.getName() + player.getName(), cooldownDuration);
		}
		
		final boolean permissionsEnabled = Main.getPlugin().getConfig().getBoolean("permissions-enabled");
		final boolean hasPermission = player.hasPermission("ssx.use." + config.getName().replace(".yml", ""));
		if (!permissionsEnabled || hasPermission){
			
			//Play sound
			String soundString = Main.getPlugin().getConfig().getString("selector-open-sound");
			if (soundString != null && !soundString.equals("NONE")){
				try {
					Sound sound = Sound.valueOf(soundString);
					player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
				} catch (IllegalArgumentException e){
					Main.getPlugin().getLogger().log(Level.WARNING, "A sound with the name " + soundString + " could not be found. Make sure that it is the right name for your server version.");
				}
			}
			
			new SelectorMenu(player, config).open();
		} else if (config.getBoolean("no-permission-message-enabled", false)) {
			player.sendMessage(config.getString("no-permission-message"));
			return;
		}
	}
	
	public static void teleportPlayerToServer(final Player player, final String server){
		if (Cooldown.getCooldown("servertp" + player.getName() + server) > 0) {
			return;
		}
		
		Cooldown.addCooldown("servertp" + player.getName() + server, 1000);
		
		if (Main.getPlugin().getConfig().getBoolean("server-teleport-message-enabled", false)){
			if (Main.getPlugin().getConfig().getBoolean("chat-clear", false)){
				for (int i = 0; i < 150; i++) {
					player.sendMessage("");
				}
			}
			
			String message = Colors.parseColors(Main.getPlugin().getConfig().getString("server-teleport-message", "error"));
			player.sendMessage(message.replace("{x}", server));
		}

		try (
				ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
				DataOutputStream dos = new DataOutputStream(baos)
			){
			
	        dos.writeUTF("Connect");
	        dos.writeUTF(server);
	        player.sendPluginMessage(getPlugin(), "BungeeCord", baos.toByteArray());
		} catch (IOException e){
			e.printStackTrace();
		}
	}
	
	public static boolean UPDATE_AVAILABLE;
	public static String NEW_VERSION;
	public static String CURRENT_VERSION;
	public static String DOWNLOAD_LINK = "https://www.spigotmc.org/resources/serverselectorx.32853/updates";
	
	private void checkForUpdates() {
		if (!getConfig().getBoolean("updater")) {
			getLogger().info("The update checker is disabled.");
			return;
		}
		
		CURRENT_VERSION = Main.this.getDescription().getVersion();
		
		if (CURRENT_VERSION.equals("custom")) {
			getLogger().info("You are using a custom version so the update checker is disabled.");
			return;
		}
		
		SpigetUpdate updater = new SpigetUpdate(this, 32853).setVersionComparator(VersionComparator.EQUAL);
		
		updater.checkForUpdate(new UpdateCallback() {
			
			@Override
			public void updateAvailable(String newVersion, String downloadUrl, boolean hasDirectDownload) {
				UPDATE_AVAILABLE = true;
				NEW_VERSION = newVersion;
				
				getLogger().info("An update is available!");
				getLogger().info("Your version: " + CURRENT_VERSION);
				getLogger().info("Latest version: " + NEW_VERSION);
			}

			@Override
			public void upToDate() {
				UPDATE_AVAILABLE = false;
				
				getLogger().info("You are running the latest version.");
			}
		});
	}
	
    public static ItemStack addGlow(ItemStack item) {
    	try {
    		String version = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
    		
    		Class<?> craftItemStackClass = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
    		Class<?> nmsItemStackClass = Class.forName("net.minecraft.server." + version + ".ItemStack");
    		Class<?> nbtTagCompoundClass = Class.forName("net.minecraft.server." + version + ".NBTTagCompound");
    		Class<?> nbtTagListClass = Class.forName("net.minecraft.server." + version + ".NBTTagList");
    		Class<?> nbtBaseClass = Class.forName("net.minecraft.server." + version + ".NBTBase");
    		
        	Object nmsItemStack = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);

    		Object nbtTagCompound = nmsItemStackClass.getMethod("getTag").invoke(nmsItemStack);
    		if (nbtTagCompound == null) {
    			nbtTagCompound = nbtTagCompoundClass.getConstructor().newInstance();
    		}
    		
    		Object enchantments = nbtTagListClass.getConstructor().newInstance();
    		nbtTagCompoundClass.getMethod("set", String.class, nbtBaseClass).invoke(nbtTagCompound, "ench", enchantments);
            nmsItemStackClass.getMethod("setTag", nbtTagCompoundClass).invoke(nmsItemStack, nbtTagCompound);
            Object bukkitStack = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass).invoke(null, nmsItemStack);
            return (ItemStack) bukkitStack;
		} catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | InvocationTargetException |
				NoSuchMethodException | SecurityException | InstantiationException e) {
			throw new RuntimeException(e);
		}
    }

}
