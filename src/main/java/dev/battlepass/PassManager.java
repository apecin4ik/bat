package dev.battlepass;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PassManager {

    public static final String PREFIX = "§8[§5Пропуск§8] §7";

    private final BattlePassPlugin plugin;
    public final Map<Integer, LevelReward> levels = new TreeMap<>();
    public final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> players = new HashMap<>();

    private final File rewardsFile;
    private final File questsFile;
    private final File dataFile;

    /** Один поток для записи на диск: файлы пишутся строго по порядку и не вешают основной поток. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "BattlePass-IO"));

    private ZoneId zone = ZoneId.systemDefault();
    private int resetHour = 4;
    /** Есть ли несохранённые изменения в данных игроков. */
    private boolean dirty = false;

    public PassManager(BattlePassPlugin plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        dir.mkdirs();
        rewardsFile = new File(dir, "rewards.yml");
        questsFile = new File(dir, "quests.yml");
        dataFile = new File(dir, "data.yml");
    }

    // ------------------------------------------------------------ загрузка

    /** Первая загрузка при старте: всё, включая прогресс игроков. */
    public void load() {
        reload();
        players.clear();
        loadData();
    }

    /**
     * Перечитывает config.yml, rewards.yml и quests.yml.
     * Прогресс игроков НЕ трогает и ничего не перезаписывает заранее,
     * поэтому ручные правки yml-файлов подхватываются.
     */
    public void reload() {
        flush(); // дождаться отложенных записей, чтобы не прочитать устаревший файл
        plugin.reloadConfig();
        readSettings();
        levels.clear();
        quests.clear();
        loadRewards();
        loadQuests();
    }

    private void readSettings() {
        String tz = plugin.getConfig().getString("timezone", "system");
        zone = ZoneId.systemDefault();
        if (tz != null && !tz.isBlank() && !tz.equalsIgnoreCase("system")) {
            try {
                zone = ZoneId.of(tz.trim());
            } catch (DateTimeException ex) {
                plugin.getLogger().warning("Неизвестный часовой пояс '" + tz + "', использую системный.");
            }
        }
        resetHour = Math.max(0, Math.min(23, plugin.getConfig().getInt("daily-reset-hour", 4)));
    }

    private void loadRewards() {
        if (!rewardsFile.exists()) {
            for (int i = 1; i <= maxLevels(); i++) {
                LevelReward r = getReward(i);
                if (i % 10 == 0) {
                    r.items.add(new ItemStack(Material.NETHERITE_INGOT, 1));
                } else if (i % 5 == 0) {
                    r.items.add(new ItemStack(Material.DIAMOND, 3));
                } else {
                    r.items.add(new ItemStack(Material.COOKED_BEEF, 8));
                }
            }
            saveRewards();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(rewardsFile);
        ConfigurationSection sec = y.getConfigurationSection("levels");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            int lvl;
            try {
                lvl = Integer.parseInt(k);
            } catch (NumberFormatException ex) {
                continue;
            }
            LevelReward r = new LevelReward(lvl);
            r.xp = sec.getInt(k + ".xp", 0);
            r.commands.addAll(sec.getStringList(k + ".commands"));
            List<?> raw = sec.getList(k + ".items");
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof ItemStack is) r.items.add(is);
                }
            }
            levels.put(lvl, r);
        }
    }

    private void loadQuests() {
        if (!questsFile.exists()) {
            addDefaultQuest("miner", "Тёмные шахты", Quest.Type.BREAK_BLOCK, "STONE", 200, 150, true);
            addDefaultQuest("hunter", "Охота на нежить", Quest.Type.KILL_ENTITY, "ZOMBIE", 20, 200, true);
            addDefaultQuest("fisher", "Улов из бездны", Quest.Type.FISH, "ANY", 10, 150, true);
            addDefaultQuest("builder", "Зодчий мрака", Quest.Type.PLACE_BLOCK, "ANY", 300, 100, true);
            addDefaultQuest("bones", "Костяной кошмар", Quest.Type.KILL_ENTITY, "SKELETON", 30, 300, false);
            addDefaultQuest("smith", "Кузнец тьмы", Quest.Type.CRAFT_ITEM, "DIAMOND_PICKAXE", 1, 250, false);
            Quest ench = addDefaultQuest("sorcerer", "Чародей", Quest.Type.ENCHANT_ITEM, "DIAMOND_SWORD", 1, 300, false);
            ench.enchant = "SHARPNESS";
            ench.level = 3;
            addDefaultQuest("supplier", "Поставщик алмазов", Quest.Type.DELIVER_ITEM, "DIAMOND", 10, 400, false);
            saveQuests();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(questsFile);
        ConfigurationSection sec = y.getConfigurationSection("quests");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            Quest q = new Quest(id);
            q.name = sec.getString(id + ".name", q.name);
            try {
                q.type = Quest.Type.valueOf(sec.getString(id + ".type", "BREAK_BLOCK"));
            } catch (IllegalArgumentException ex) {
                q.type = Quest.Type.BREAK_BLOCK;
            }
            q.target = sec.getString(id + ".target", "ANY");
            q.amount = Math.max(1, sec.getInt(id + ".amount", 100));
            q.xp = Math.max(0, sec.getInt(id + ".xp", 100));
            q.daily = sec.getBoolean(id + ".daily", false);
            q.enchant = sec.getString(id + ".enchant", "ANY");
            q.level = Math.max(1, sec.getInt(id + ".level", 1));
            quests.put(id, q);
        }
    }

    private Quest addDefaultQuest(String id, String name, Quest.Type type, String target, int amount, int xp, boolean daily) {
        Quest q = new Quest(id);
        q.name = name;
        q.type = type;
        q.target = target;
        q.amount = amount;
        q.xp = xp;
        q.daily = daily;
        quests.put(id, q);
        return q;
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(k);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            PlayerData d = new PlayerData();
            d.xp = sec.getInt(k + ".xp");
            d.day = sec.getString(k + ".day", "");
            d.claimed.addAll(sec.getIntegerList(k + ".claimed"));
            d.done.addAll(sec.getStringList(k + ".done"));
            if (sec.contains(k + ".rewarded")) {
                d.rewarded.addAll(sec.getStringList(k + ".rewarded"));
            } else {
                // старые данные: раньше опыт выдавался сразу, значит награда уже получена
                d.rewarded.addAll(d.done);
            }
            ConfigurationSection pg = sec.getConfigurationSection(k + ".progress");
            if (pg != null) {
                for (String q : pg.getKeys(false)) d.progress.put(q, pg.getInt(q));
            }
            players.put(id, d);
        }
    }

    // ------------------------------------------------------------ сохранение

    /** Периодическое автосохранение: пишет данные игроков, только если они менялись. */
    public void autosave() {
        if (dirty) saveData();
    }

    /** Сохранение при выключении: дописывает всё в очереди и ждёт завершения записи. */
    public void shutdown() {
        saveData();
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Запись файлов не завершилась за 30 секунд.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Награды содержат ItemStack, поэтому сериализуем в основном потоке, а на диск пишем асинхронно. */
    public void saveRewards() {
        YamlConfiguration y = new YamlConfiguration();
        for (LevelReward r : levels.values()) {
            String b = "levels." + r.level;
            if (r.xp > 0) y.set(b + ".xp", r.xp);
            y.set(b + ".commands", r.commands);
            y.set(b + ".items", r.items);
        }
        String content = y.saveToString();
        submit(() -> writeFile(rewardsFile, content));
    }

    public void saveQuests() {
        YamlConfiguration y = new YamlConfiguration();
        for (Quest q : quests.values()) {
            String b = "quests." + q.id;
            y.set(b + ".name", q.name);
            y.set(b + ".type", q.type.name());
            y.set(b + ".target", q.target);
            y.set(b + ".amount", q.amount);
            y.set(b + ".xp", q.xp);
            y.set(b + ".daily", q.daily);
            if (q.type == Quest.Type.ENCHANT_ITEM) {
                y.set(b + ".enchant", q.enchant);
                y.set(b + ".level", q.level);
            }
        }
        String content = y.saveToString();
        submit(() -> writeFile(questsFile, content));
    }

    /**
     * В основном потоке делается только быстрый снимок данных (копии простых значений),
     * а сборка YAML и запись на диск идут в отдельном потоке.
     */
    public void saveData() {
        dirty = false;
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerData> en : players.entrySet()) {
            String b = "players." + en.getKey();
            PlayerData d = en.getValue();
            flat.put(b + ".xp", d.xp);
            flat.put(b + ".day", d.day);
            flat.put(b + ".claimed", new ArrayList<>(d.claimed));
            flat.put(b + ".done", new ArrayList<>(d.done));
            flat.put(b + ".rewarded", new ArrayList<>(d.rewarded));
            for (Map.Entry<String, Integer> pr : d.progress.entrySet()) {
                flat.put(b + ".progress." + pr.getKey(), pr.getValue());
            }
        }
        submit(() -> {
            YamlConfiguration y = new YamlConfiguration();
            flat.forEach(y::set);
            writeFile(dataFile, y.saveToString());
        });
    }

    private void submit(Runnable task) {
        if (io.isShutdown()) {
            task.run();
            return;
        }
        try {
            io.execute(task);
        } catch (RejectedExecutionException ex) {
            task.run();
        }
    }

    /** Ждёт, пока все отложенные записи завершатся. */
    private void flush() {
        if (io.isShutdown()) return;
        try {
            io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            plugin.getLogger().warning("Не дождался завершения записи файлов: " + ex.getMessage());
        }
    }

    /** Запись через временный файл: при сбое посреди записи старый файл остаётся целым. */
    private void writeFile(File f, String content) {
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить " + f.getName() + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ настройки и уровни

    public int maxLevels() {
        return Math.max(1, plugin.getConfig().getInt("max-levels", 30));
    }

    public void setMaxLevels(int n) {
        plugin.getConfig().set("max-levels", Math.max(1, Math.min(200, n)));
        plugin.saveConfig();
    }

    public int defaultCost() {
        return Math.max(1, plugin.getConfig().getInt("default-xp-per-level", 500));
    }

    public void setDefaultCost(int n) {
        plugin.getConfig().set("default-xp-per-level", Math.max(10, n));
        plugin.saveConfig();
    }

    public LevelReward getReward(int level) {
        return levels.computeIfAbsent(level, LevelReward::new);
    }

    /** Сколько опыта нужно, чтобы перейти на этот уровень с предыдущего. */
    public int cost(int level) {
        LevelReward r = getReward(level);
        return r.xp > 0 ? r.xp : defaultCost();
    }

    /** Сколько всего опыта нужно, чтобы достичь уровня. */
    public int cum(int level) {
        int s = 0;
        for (int i = 1; i <= level; i++) s += cost(i);
        return s;
    }

    public int levelOf(int xp) {
        int lvl = 0;
        int sum = 0;
        for (int i = 1; i <= maxLevels(); i++) {
            sum += cost(i);
            if (xp >= sum) lvl = i;
            else break;
        }
        return lvl;
    }

    // ------------------------------------------------------------ игроки

    /**
     * Текущий «игровой день» с учётом часового пояса и часа сброса из config.yml.
     * Например, при daily-reset-hour: 4 день меняется в 04:00, а не в полночь.
     */
    public String today() {
        return ZonedDateTime.now(zone).minusHours(resetHour).toLocalDate().toString();
    }

    public PlayerData data(Player p) {
        PlayerData d = players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
        String today = today();
        if (!today.equals(d.day)) {
            for (Quest q : quests.values()) {
                if (q.daily) {
                    d.progress.remove(q.id);
                    d.done.remove(q.id);
                    d.rewarded.remove(q.id);
                }
            }
            d.day = today;
            dirty = true;
        }
        return d;
    }

    public void reset(UUID id) {
        PlayerData d = new PlayerData();
        d.day = today(); // сразу текущий день, чтобы data() не гонял сброс повторно
        players.put(id, d);
        dirty = true;
    }

    public void addXp(Player p, int amount) {
        if (amount == 0) return;
        PlayerData d = data(p);
        int before = levelOf(d.xp);
        d.xp = Math.max(0, d.xp + amount);
        int after = levelOf(d.xp);
        dirty = true;
        if (amount > 0) {
            p.sendMessage(PREFIX + "§d+" + amount + " §7опыта пропуска");
        }
        if (after > before) {
            p.sendTitle("§5§lУРОВЕНЬ " + after, "§7Награда ждёт тебя в §d/bp", 10, 50, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.7f);
        }
    }

    private boolean ignored(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    /** Обычный прогресс заданий (ломать, ставить, убивать, ловить, крафтить). Сдача предметов сюда не попадает. */
    public void progress(Player p, Quest.Type type, String name, int n) {
        if (type == Quest.Type.DELIVER_ITEM || n <= 0 || ignored(p)) return;
        PlayerData d = data(p);
        for (Quest q : quests.values()) {
            if (q.type != type || d.done.contains(q.id)) continue;
            if (!q.target.equalsIgnoreCase("ANY") && !q.target.equalsIgnoreCase(name)) continue;
            advance(p, d, q, n);
        }
    }

    /** Прогресс заданий на зачарование: предмет item получил чары ench (чара -> уровень). */
    public void progressEnchant(Player p, Material item, Map<Enchantment, Integer> ench) {
        if (ench.isEmpty() || ignored(p)) return;
        PlayerData d = data(p);
        for (Quest q : quests.values()) {
            if (q.type != Quest.Type.ENCHANT_ITEM || d.done.contains(q.id)) continue;
            if (!q.target.equalsIgnoreCase("ANY") && !q.target.equalsIgnoreCase(item.name())) continue;
            boolean ok = false;
            for (Map.Entry<Enchantment, Integer> en : ench.entrySet()) {
                boolean same = q.enchant.equalsIgnoreCase("ANY")
                        || en.getKey().getKey().getKey().equalsIgnoreCase(q.enchant);
                if (same && en.getValue() >= q.level) {
                    ok = true;
                    break;
                }
            }
            if (ok) advance(p, d, q, 1);
        }
    }

    private void advance(Player p, PlayerData d, Quest q, int n) {
        int v = d.progress.merge(q.id, n, Integer::sum);
        dirty = true;
        if (v >= q.amount) {
            d.progress.put(q.id, q.amount);
            d.done.add(q.id);
            notifyDone(p, q);
        }
    }

    /** Красивое сообщение на экране: награду игрок забирает сам в /bp quests. */
    private void notifyDone(Player p, Quest q) {
        p.sendTitle("§5§l✔ ЗАДАНИЕ ВЫПОЛНЕНО", "§d" + q.name + " §8| §7забери награду в §d/bp quests", 10, 70, 20);
        p.sendMessage(PREFIX + "§a✔ Задание выполнено: §d" + q.name);
        p.sendMessage(PREFIX + "§7Открой §d/bp quests §7и нажми на задание, чтобы получить §d+" + q.xp + " §7опыта.");
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    public enum ClaimResult { OK, NOT_READY, ALREADY, MISSING_ITEMS }

    /**
     * Клик по заданию в меню. Для обычных заданий выдаёт опыт, если цель достигнута.
     * Для «сдать предметы» сначала забирает предметы из инвентаря.
     */
    public ClaimResult claimQuest(Player p, Quest q) {
        PlayerData d = data(p);
        if (d.rewarded.contains(q.id)) return ClaimResult.ALREADY;
        if (q.type == Quest.Type.DELIVER_ITEM) {
            Material m = Material.matchMaterial(q.target);
            if (m == null) return ClaimResult.NOT_READY;
            if (countItems(p, m) < q.amount) return ClaimResult.MISSING_ITEMS;
            takeItems(p, m, q.amount);
            d.progress.put(q.id, q.amount);
            d.done.add(q.id);
        } else if (!d.done.contains(q.id)) {
            return ClaimResult.NOT_READY;
        }
        d.rewarded.add(q.id);
        dirty = true;
        addXp(p, q.xp);
        return ClaimResult.OK;
    }

    /** Считает предметы по типу, игнорируя название, лор, чары и любые другие данные. */
    public int countItems(Player p, Material m) {
        PlayerInventory inv = p.getInventory();
        int c = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it != null && it.getType() == m) c += it.getAmount();
        }
        ItemStack off = inv.getItemInOffHand();
        if (off.getType() == m) c += off.getAmount();
        return c;
    }

    /** Забирает ровно n предметов типа m (с любыми изменениями: название, лор, чары). */
    private void takeItems(Player p, Material m, int n) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] cont = inv.getStorageContents();
        int left = n;
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (it == null || it.getType() != m) continue;
            int t = Math.min(left, it.getAmount());
            left -= t;
            if (t >= it.getAmount()) {
                cont[i] = null;
            } else {
                it.setAmount(it.getAmount() - t);
            }
        }
        inv.setStorageContents(cont);
        if (left > 0) {
            ItemStack off = inv.getItemInOffHand().clone();
            if (off.getType() == m) {
                int t = Math.min(left, off.getAmount());
                if (t >= off.getAmount()) {
                    inv.setItemInOffHand(null);
                } else {
                    off.setAmount(off.getAmount() - t);
                    inv.setItemInOffHand(off);
                }
            }
        }
        dirty = true;
    }

    public boolean claim(Player p, int level) {
        PlayerData d = data(p);
        if (level > levelOf(d.xp) || d.claimed.contains(level)) return false;
        LevelReward r = getReward(level);
        for (ItemStack it : r.items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it.clone());
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        for (String cmd : r.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        d.claimed.add(level);
        dirty = true;
        return true;
    }
}
