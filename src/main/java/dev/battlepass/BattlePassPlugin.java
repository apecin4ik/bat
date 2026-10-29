package dev.battlepass;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BattlePassPlugin extends JavaPlugin implements Listener {

    private static final String ADMIN_PERM = "battlepass.admin";
    private static final List<String> ADMIN_SUBS = List.of("edit", "reload", "givexp", "reset", "help");

    private PassManager pm;
    private Menus menus;
    private AntiFarm farm;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pm = new PassManager(this);
        pm.load();
        farm = new AntiFarm(this);
        menus = new Menus(this, pm);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(this, this);
        long ticks = Math.max(1, getConfig().getInt("autosave-minutes", 5)) * 1200L;
        getServer().getScheduler().runTaskTimer(this, pm::autosave, ticks, ticks);
        getLogger().info("BattlePass включён.");
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.saveOpenEditors();
        if (pm != null) pm.shutdown();
    }

    /** Перечитывает config, награды и задания. Прогресс игроков не затрагивается. */
    public void reloadAll() {
        pm.reload();
        farm.reload();
    }

    // ---------------------------------------------------------------- команды

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";

        if (ADMIN_SUBS.contains(sub)) {
            if (!s.hasPermission(ADMIN_PERM)) {
                s.sendMessage(PassManager.PREFIX + "§cНет прав.");
                return true;
            }
            admin(s, sub, a);
            return true;
        }

        if (!(s instanceof Player p)) {
            s.sendMessage("Команда только для игроков. Админ-команды: /" + label + " help");
            return true;
        }
        if (!p.hasPermission("battlepass.use")) {
            p.sendMessage(PassManager.PREFIX + "§cНет прав.");
            return true;
        }
        if (sub.equals("quests")) menus.openQuests(p);
        else menus.openMain(p, 0);
        return true;
    }

    private void admin(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "edit" -> {
                if (s instanceof Player p) menus.openAdmin(p, 0);
                else s.sendMessage(PassManager.PREFIX + "§cРедактор доступен только игрокам.");
            }
            case "reload" -> {
                reloadAll();
                s.sendMessage(PassManager.PREFIX + "§aConfig, награды и задания перезагружены. Прогресс игроков не тронут.");
            }
            case "givexp" -> {
                if (a.length < 3) {
                    s.sendMessage(PassManager.PREFIX + "§7Используй: /battlepass givexp <игрок> <число>");
                    return;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage(PassManager.PREFIX + "§cИгрок не найден.");
                    return;
                }
                try {
                    int n = Integer.parseInt(a[2]);
                    pm.addXp(t, n);
                    s.sendMessage(PassManager.PREFIX + "§aВыдано §d" + n + " §aопыта игроку §f" + t.getName());
                } catch (NumberFormatException ex) {
                    s.sendMessage(PassManager.PREFIX + "§cВведи число.");
                }
            }
            case "reset" -> {
                if (a.length < 2) {
                    s.sendMessage(PassManager.PREFIX + "§7Используй: /battlepass reset <игрок>");
                    return;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage(PassManager.PREFIX + "§cИгрок не найден.");
                    return;
                }
                pm.reset(t.getUniqueId());
                s.sendMessage(PassManager.PREFIX + "§aПрогресс игрока §f" + t.getName() + " §aсброшен.");
            }
            default -> help(s);
        }
    }

    private void help(CommandSender s) {
        s.sendMessage(PassManager.PREFIX + "§7Команды администратора:");
        s.sendMessage("§8 • §f/bp edit §7— редактор уровней, наград и заданий");
        s.sendMessage("§8 • §f/bp reload §7— перечитать config, награды и задания");
        s.sendMessage("§8 • §f/bp givexp <игрок> <число> §7— выдать опыт");
        s.sendMessage("§8 • §f/bp reset <игрок> §7— сбросить прогресс игрока");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        boolean admin = s.hasPermission(ADMIN_PERM);
        if (a.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("quests");
            if (admin) subs.addAll(ADMIN_SUBS);
            return filter(subs, a[0]);
        }
        if (admin && a.length == 2 && (a[0].equalsIgnoreCase("givexp") || a[0].equalsIgnoreCase("reset"))) {
            List<String> names = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
            return filter(names, a[1]);
        }
        if (admin && a.length == 3 && a[0].equalsIgnoreCase("givexp")) {
            return filter(List.of("100", "500", "1000"), a[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> src, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : src) {
            if (x.toLowerCase(Locale.ROOT).startsWith(p)) out.add(x);
        }
        return out;
    }

    // ---------------------------------------------------------------- прогресс заданий

    // MONITOR: считаем только то, что не отменили плагины защиты регионов и т.п.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!farm.onBreak(e.getBlock())) return;
        pm.progress(e.getPlayer(), Quest.Type.BREAK_BLOCK, e.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!farm.onPlace(e.getBlockPlaced())) return;
        pm.progress(e.getPlayer(), Quest.Type.PLACE_BLOCK, e.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            pm.progress(killer, Quest.Type.KILL_ENTITY, e.getEntityType().name(), 1);
        }
    }

    /** Крафт. Учитывает шифт-клик (крафт «на всю стопку») с поправкой на место в инвентаре. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        if (result.getType() == Material.AIR) return;
        int per = Math.max(1, result.getAmount());
        int crafts = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) {
                if (it != null && it.getType() != Material.AIR) min = Math.min(min, it.getAmount());
            }
            if (min == Integer.MAX_VALUE) return;
            crafts = Math.min(min, freeSpace(p.getInventory(), result.getType(), result.getMaxStackSize()) / per);
            if (crafts <= 0) return;
        } else {
            ItemStack cur = e.getCursor();
            if (cur != null && cur.getType() != Material.AIR
                    && (cur.getType() != result.getType() || cur.getAmount() + per > cur.getMaxStackSize())) {
                return; // предмет не попадёт в руку — крафт не состоялся
            }
        }
        pm.progress(p, Quest.Type.CRAFT_ITEM, result.getType().name(), crafts * per);
    }

    private static int freeSpace(PlayerInventory inv, Material m, int max) {
        int space = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType() == Material.AIR) space += max;
            else if (it.getType() == m) space += Math.max(0, max - it.getAmount());
        }
        return space;
    }

    /** Зачарование на столе зачарования. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        pm.progressEnchant(e.getEnchanter(), e.getItem().getType(), e.getEnchantsToAdd());
    }

    /** Зачарование на наковальне: засчитываются только чары, которые появились или стали сильнее. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory anvil)) return;
        if (e.getRawSlot() != 2 || !(e.getWhoClicked() instanceof Player p)) return;
        ItemStack res = anvil.getItem(2);
        ItemStack left = anvil.getItem(0);
        if (res == null || left == null || res.getType() == Material.AIR || res.getType() == Material.ENCHANTED_BOOK) return;
        if (!e.isShiftClick() && e.getCursor() != null && e.getCursor().getType() != Material.AIR) return;
        if (p.getGameMode() != GameMode.CREATIVE && p.getLevel() < anvil.getRepairCost()) return;
        Map<Enchantment, Integer> gained = new HashMap<>();
        for (Map.Entry<Enchantment, Integer> en : res.getEnchantments().entrySet()) {
            if (en.getValue() > left.getEnchantmentLevel(en.getKey())) gained.put(en.getKey(), en.getValue());
        }
        if (!gained.isEmpty()) pm.progressEnchant(p, res.getType(), gained);
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item it) {
            pm.progress(e.getPlayer(), Quest.Type.FISH, it.getItemStack().getType().name(), 1);
        }
    }
}
