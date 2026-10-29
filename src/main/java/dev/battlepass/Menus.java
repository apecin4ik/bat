package dev.battlepass;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class Menus implements Listener {

    static final String TITLE = "§8§l» §5§lБоевой Пропуск §8§l«";
    static final int[] LEVEL_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    static final int PER = LEVEL_SLOTS.length;
    static final int EDIT_ITEM_SLOTS = 27;

    enum Kind { MAIN, QUESTS, ADMIN, LEVEL_EDIT, QUEST_LIST, QUEST_EDIT }

    static class Holder implements InventoryHolder {
        final Kind kind;
        int page;
        int arg;
        String id;
        Inventory inv;

        Holder(Kind kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class Prompt {
        final Consumer<String> input;
        final Runnable back;

        Prompt(Consumer<String> input, Runnable back) {
            this.input = input;
            this.back = back;
        }
    }

    private final BattlePassPlugin plugin;
    private final PassManager pm;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Holder> editors = new ConcurrentHashMap<>();

    public Menus(BattlePassPlugin plugin, PassManager pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    // ================================================================ утилиты

    static ItemStack item(Material m, String name, String... lore) {
        return item(m, name, false, lore);
    }

    static ItemStack item(Material m, String name, boolean glow, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (glow) meta.addEnchant(Enchantment.LURE, 1, true);
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack pane(Material m) {
        return item(m, " ");
    }

    private Holder make(Kind k, int size, String title) {
        Holder h = new Holder(k);
        h.inv = Bukkit.createInventory(h, size, title);
        return h;
    }

    private void frame(Inventory inv) {
        int size = inv.getSize();
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) inv.setItem(i, black);
        for (int i = 0; i < 9; i++) {
            ItemStack s = pane(i % 2 == 0 ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE);
            inv.setItem(i, s);
            inv.setItem(size - 9 + i, s);
        }
    }

    private int pages(int max) {
        return Math.max(1, (max + PER - 1) / PER);
    }

    private int slotIndex(int slot) {
        for (int i = 0; i < PER; i++) if (LEVEL_SLOTS[i] == slot) return i;
        return -1;
    }

    private int step(ClickType c, int small, int big) {
        int v = c.isShiftClick() ? big : small;
        return c.isRightClick() ? -v : v;
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String describe(ItemStack it) {
        String n = (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) ? it.getItemMeta().getDisplayName() : pretty(it.getType());
        return it.getAmount() + "x " + n;
    }

    private static String bar(int v, int max, int len) {
        int f = max <= 0 ? len : (int) Math.min(len, (long) len * v / max);
        return "§5" + "█".repeat(f) + "§8" + "░".repeat(len - f);
    }

    private static String targetText(Quest q) {
        String t = q.target.equalsIgnoreCase("ANY") ? "любой" : q.target;
        if (q.type == Quest.Type.ENCHANT_ITEM) {
            t += " §7| чара: §f" + (q.enchant.equalsIgnoreCase("ANY") ? "любая" : q.enchant) + " §7ур. §f" + q.level + "+";
        }
        return t;
    }

    private void sound(Player p, Sound s, float pitch) {
        p.playSound(p.getLocation(), s, 0.7f, pitch);
    }

    // ================================================================ главное меню

    public void openMain(Player p, int page) {
        Holder h = make(Kind.MAIN, 54, TITLE);
        h.page = page;
        renderMain(h, p);
        p.openInventory(h.inv);
    }

    private void renderMain(Holder h, Player p) {
        Inventory inv = h.inv;
        PlayerData d = pm.data(p);
        int lvl = pm.levelOf(d.xp);
        int max = pm.maxLevels();
        int pages = pages(max);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        frame(inv);

        int claimable = 0;
        for (int i = 1; i <= lvl; i++) if (!d.claimed.contains(i)) claimable++;
        int need = lvl < max ? pm.cost(lvl + 1) : 0;
        int have = d.xp - pm.cum(lvl);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(p);
        sm.setDisplayName("§5§l" + p.getName());
        List<String> info = new ArrayList<>();
        info.add("§7Уровень: §d" + lvl + "§8/§d" + max);
        info.add("§7Всего опыта: §d" + d.xp);
        if (lvl < max) info.add("§7До следующего: §d" + have + "§8/§d" + need);
        info.add("");
        info.add(claimable > 0 ? "§aНаград к получению: §f" + claimable : "§8Наград к получению нет");
        sm.setLore(info);
        head.setItemMeta(sm);
        inv.setItem(4, head);

        for (int i = 0; i < PER; i++) {
            int level = h.page * PER + i + 1;
            if (level > max) break;
            inv.setItem(LEVEL_SLOTS[i], levelItem(level, lvl, d));
        }

        int filled = lvl >= max ? 7 : (need <= 0 ? 0 : (int) Math.round(7.0 * have / need));
        for (int i = 0; i < 7; i++) {
            boolean on = i < filled;
            inv.setItem(37 + i, item(on ? Material.PURPLE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    on ? "§5Прогресс" : "§8Прогресс",
                    lvl >= max ? "§dМаксимальный уровень" : "§7" + have + " §8/ §7" + need + " опыта"));
        }

        if (h.page > 0) inv.setItem(45, item(Material.ARROW, "§7← Назад", "§8Страница " + h.page + "/" + pages));
        if (h.page < pages - 1) inv.setItem(53, item(Material.ARROW, "§7Вперёд →", "§8Страница " + (h.page + 2) + "/" + pages));
        inv.setItem(48, item(Material.WRITABLE_BOOK, "§5§lЗадания", "§7Выполняй задания, чтобы", "§7получать опыт пропуска"));
        inv.setItem(50, item(Material.ENDER_CHEST, "§d§lЗабрать всё", claimable > 0, "§7Доступных наград: §f" + claimable));
    }

    private List<String> rewardLines(LevelReward r) {
        List<String> l = new ArrayList<>();
        for (ItemStack it : r.items) l.add("§8 • §f" + describe(it));
        if (!r.commands.isEmpty()) l.add("§8 • §d✦ Особый бонус");
        if (l.isEmpty()) l.add("§8 • §7—");
        return l;
    }

    private ItemStack levelItem(int level, int cur, PlayerData d) {
        LevelReward r = pm.getReward(level);
        List<String> lore = new ArrayList<>();
        lore.add("§7Награда:");
        lore.addAll(rewardLines(r));
        lore.add("");
        Material m;
        String name;
        boolean glow = false;
        if (d.claimed.contains(level)) {
            m = Material.CRYING_OBSIDIAN;
            name = "§5§lУровень " + level + " §8(получено)";
            lore.add("§5✔ Награда получена");
        } else if (level <= cur) {
            m = Material.ENDER_EYE;
            glow = true;
            name = "§d§lУровень " + level + " §5» забрать";
            lore.add("§a▶ Нажми, чтобы забрать");
        } else {
            m = Material.COAL_BLOCK;
            name = "§8§lУровень " + level;
            lore.add("§c✖ Нужно ещё §f" + (pm.cum(level) - d.xp) + " §cопыта");
        }
        ItemStack it = item(m, name, glow, lore.toArray(new String[0]));
        it.setAmount(Math.min(64, level));
        return it;
    }

    private void clickMain(Player p, Holder h, int slot) {
        int idx = slotIndex(slot);
        if (idx >= 0) {
            int level = h.page * PER + idx + 1;
            if (level <= pm.maxLevels()) {
                if (pm.claim(p, level)) {
                    p.sendMessage(PREFIX() + "§a✔ Награда за уровень §d" + level + " §aполучена");
                    sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
                } else {
                    sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                }
                renderMain(h, p);
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                if (h.page > 0) {
                    h.page--;
                    renderMain(h, p);
                    sound(p, Sound.UI_BUTTON_CLICK, 0.8f);
                }
            }
            case 53 -> {
                h.page++;
                renderMain(h, p);
                sound(p, Sound.UI_BUTTON_CLICK, 0.8f);
            }
            case 48 -> openQuests(p);
            case 50 -> {
                int got = 0;
                int lvl = pm.levelOf(pm.data(p).xp);
                for (int i = 1; i <= lvl; i++) if (pm.claim(p, i)) got++;
                if (got > 0) {
                    p.sendMessage(PREFIX() + "§a✔ Получено наград: §d" + got);
                    sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
                } else {
                    sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                }
                renderMain(h, p);
            }
            default -> { }
        }
    }

    private static String PREFIX() {
        return PassManager.PREFIX;
    }

    // ================================================================ меню заданий игрока

    public void openQuests(Player p) {
        Holder h = make(Kind.QUESTS, 54, "§8§l» §5§lЗадания §8§l«");
        renderQuests(h, p);
        p.openInventory(h.inv);
    }

    private void renderQuests(Holder h, Player p) {
        Inventory inv = h.inv;
        PlayerData d = pm.data(p);
        frame(inv);
        int slot = 9;
        for (Quest q : pm.quests.values()) {
            if (slot > 44) break;
            boolean handIn = q.type == Quest.Type.DELIVER_ITEM;
            boolean rewarded = d.rewarded.contains(q.id);
            boolean done = d.done.contains(q.id);
            int v;
            if (handIn) {
                Material dm = Material.matchMaterial(q.target);
                v = dm == null ? 0 : Math.min(q.amount, pm.countItems(p, dm));
            } else {
                v = done ? q.amount : d.progress.getOrDefault(q.id, 0);
            }
            boolean ready = !rewarded && (handIn ? v >= q.amount : done);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + q.type.title + ": §f" + targetText(q));
            lore.add("§7" + (handIn ? "В инвентаре: " : "Прогресс: ") + "§d" + v + "§8/§d" + q.amount);
            lore.add(bar(v, q.amount, 14));
            lore.add("§7Награда: §d+" + q.xp + " опыта");
            lore.add(q.daily ? "§8⟳ Обновляется каждый день" : "§8Одноразовое задание");
            if (handIn) lore.add("§8Принимаются любые такие предметы, даже переименованные");
            lore.add("");
            if (rewarded) lore.add("§5✔ Награда получена");
            else if (ready) lore.add(handIn ? "§a▶ Нажми, чтобы сдать предметы и забрать награду" : "§a▶ Нажми, чтобы забрать награду");
            else if (handIn) lore.add("§7Собери предметы и нажми, чтобы сдать");
            else lore.add("§8Награда будет доступна после выполнения");
            inv.setItem(slot++, item(q.type.icon,
                    (rewarded ? "§8§m" : ready ? "§a§l" : "§5§l") + ChatColor.stripColor(q.name),
                    ready, lore.toArray(new String[0])));
        }
        inv.setItem(49, item(Material.ARROW, "§7← К пропуску"));
    }

    private void clickQuests(Player p, Holder h, int slot) {
        if (slot == 49) {
            openMain(p, 0);
            return;
        }
        int idx = slot - 9;
        List<Quest> list = new ArrayList<>(pm.quests.values());
        if (slot < 9 || slot > 44 || idx >= list.size()) return;
        Quest q = list.get(idx);
        switch (pm.claimQuest(p, q)) {
            case OK -> {
                p.sendMessage(PREFIX() + "§a✔ Награда за задание получена: §d" + ChatColor.stripColor(q.name));
                sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
            }
            case MISSING_ITEMS -> {
                p.sendMessage(PREFIX() + "§cНе хватает предметов: нужно §f" + q.amount + "x " + q.target);
                sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
            }
            case NOT_READY -> {
                p.sendMessage(PREFIX() + "§cЗадание ещё не выполнено.");
                sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
            }
            case ALREADY -> sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
        }
        renderQuests(h, p);
    }

    // ================================================================ админ-меню

    public void openAdmin(Player p, int page) {
        Holder h = make(Kind.ADMIN, 54, "§8§l» §5§lНастройка пропуска §8§l«");
        h.page = page;
        renderAdmin(h);
        p.openInventory(h.inv);
    }

    private void renderAdmin(Holder h) {
        Inventory inv = h.inv;
        int max = pm.maxLevels();
        int pages = pages(max);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        frame(inv);
        for (int i = 0; i < PER; i++) {
            int level = h.page * PER + i + 1;
            if (level > max) break;
            LevelReward r = pm.getReward(level);
            List<String> lore = new ArrayList<>();
            lore.add("§7Награда:");
            lore.addAll(rewardLines(r));
            lore.add("");
            lore.add("§7Команд: §f" + r.commands.size());
            lore.add("§7Опыта на уровень: §f" + pm.cost(level) + (r.xp > 0 ? "" : " §8(по умолчанию)"));
            lore.add("");
            lore.add("§dКлик — редактировать");
            ItemStack it = item(Material.BOOK, "§5§lУровень " + level, lore.toArray(new String[0]));
            it.setAmount(Math.min(64, level));
            inv.setItem(LEVEL_SLOTS[i], it);
        }
        inv.setItem(4, item(Material.NETHER_STAR, "§5§lУровней в пропуске: §f" + max,
                "§7ЛКМ §a+1§7, ПКМ §c−1", "§7Shift — по 10"));
        inv.setItem(49, item(Material.EXPERIENCE_BOTTLE, "§5Опыта на уровень (по умолчанию): §f" + pm.defaultCost(),
                "§7ЛКМ §a+100§7, ПКМ §c−100", "§7Shift — по 1000"));
        if (h.page > 0) inv.setItem(45, item(Material.ARROW, "§7← Назад"));
        if (h.page < pages - 1) inv.setItem(53, item(Material.ARROW, "§7Вперёд →"));
        inv.setItem(48, item(Material.WRITABLE_BOOK, "§5§lРедактор заданий", "§7Создавай и меняй задания"));
        inv.setItem(50, item(Material.REDSTONE, "§cПерезагрузить файлы", "§7Перечитать config, rewards, quests", "§8Прогресс игроков не затрагивается"));
    }

    private void clickAdmin(Player p, Holder h, int slot, ClickType c) {
        int idx = slotIndex(slot);
        if (idx >= 0) {
            int level = h.page * PER + idx + 1;
            if (level <= pm.maxLevels()) openLevelEdit(p, level, h.page);
            return;
        }
        switch (slot) {
            case 4 -> {
                pm.setMaxLevels(pm.maxLevels() + step(c, 1, 10));
                renderAdmin(h);
            }
            case 49 -> {
                pm.setDefaultCost(pm.defaultCost() + step(c, 100, 1000));
                renderAdmin(h);
            }
            case 45 -> {
                if (h.page > 0) {
                    h.page--;
                    renderAdmin(h);
                }
            }
            case 53 -> {
                h.page++;
                renderAdmin(h);
            }
            case 48 -> openQuestList(p);
            case 50 -> {
                plugin.reloadAll();
                renderAdmin(h);
                p.sendMessage(PREFIX() + "§aФайлы перезагружены");
            }
            default -> { }
        }
    }

    // ================================================================ редактор наград уровня

    public void openLevelEdit(Player p, int level, int adminPage) {
        Holder h = make(Kind.LEVEL_EDIT, 54, "§8§l» §5Награды: уровень " + level + " §8§l«");
        h.arg = level;
        h.page = adminPage;
        renderLevelEdit(h, true);
        editors.put(p.getUniqueId(), h);
        p.openInventory(h.inv);
    }

    private void renderLevelEdit(Holder h, boolean withItems) {
        Inventory inv = h.inv;
        LevelReward r = pm.getReward(h.arg);
        if (withItems) {
            for (int i = 0; i < EDIT_ITEM_SLOTS; i++) inv.setItem(i, null);
            int i = 0;
            for (ItemStack it : r.items) {
                if (i >= EDIT_ITEM_SLOTS) break;
                inv.setItem(i++, it.clone());
            }
        }
        for (int i = EDIT_ITEM_SLOTS; i < 54; i++) {
            inv.setItem(i, pane(i < 36 ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
        }
        inv.setItem(31, item(Material.PAPER, "§5§lУровень " + h.arg,
                "§7Всё, что лежит выше (27 слотов),", "§7— это награда за уровень.", "§7Кладите и забирайте предметы свободно."));
        inv.setItem(45, item(Material.ARROW, "§7← Назад", "§8Изменения сохраняются"));
        inv.setItem(47, item(Material.WRITABLE_BOOK, "§d+ Добавить команду",
                "§7Клик — ввести команду в чате", "§8Без «/». Игрок: §f%player%"));
        inv.setItem(48, item(Material.BARRIER, "§c− Удалить последнюю команду"));
        List<String> cl = new ArrayList<>();
        if (r.commands.isEmpty()) cl.add("§8Команд нет");
        for (String cmd : r.commands) cl.add("§8• §f/" + cmd);
        inv.setItem(49, item(Material.COMMAND_BLOCK, "§5Команды награды", cl.toArray(new String[0])));
        inv.setItem(51, item(Material.RED_DYE, "§c− Опыт", "§7Клик §c−100§7, Shift §c−1000"));
        inv.setItem(52, item(Material.EXPERIENCE_BOTTLE, "§5Опыта на этот уровень: §f" + pm.cost(h.arg)));
        inv.setItem(53, item(Material.LIME_DYE, "§a+ Опыт", "§7Клик §a+100§7, Shift §a+1000"));
    }

    private void saveLevelEditor(Holder h) {
        LevelReward r = pm.getReward(h.arg);
        r.items.clear();
        for (int i = 0; i < EDIT_ITEM_SLOTS; i++) {
            ItemStack it = h.inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) r.items.add(it.clone());
        }
        pm.saveRewards();
    }

    public void saveOpenEditors() {
        for (Holder h : editors.values()) saveLevelEditor(h);
    }

    private void clickLevelEdit(Player p, Holder h, int slot, ClickType c) {
        LevelReward r = pm.getReward(h.arg);
        switch (slot) {
            case 45 -> {
                saveLevelEditor(h);
                openAdmin(p, h.page);
            }
            case 47 -> {
                int level = h.arg;
                int page = h.page;
                ask(p, "Введи команду награды (без «/», игрок — §f%player%§7). Пример: §fgive %player% diamond 5",
                        s -> {
                            r.commands.add(s.startsWith("/") ? s.substring(1) : s);
                            pm.saveRewards();
                            p.sendMessage(PREFIX() + "§aКоманда добавлена");
                        },
                        () -> openLevelEdit(p, level, page));
            }
            case 48 -> {
                if (!r.commands.isEmpty()) r.commands.remove(r.commands.size() - 1);
                pm.saveRewards();
                renderLevelEdit(h, false);
            }
            case 51, 53 -> {
                int delta = step(c, 100, 1000);
                if (c.isRightClick()) delta = -delta;
                if (slot == 51) delta = -Math.abs(delta); else delta = Math.abs(delta);
                r.xp = Math.max(10, pm.cost(h.arg) + delta);
                pm.saveRewards();
                renderLevelEdit(h, false);
            }
            default -> { }
        }
    }

    // ================================================================ редактор заданий

    public void openQuestList(Player p) {
        Holder h = make(Kind.QUEST_LIST, 54, "§8§l» §5§lРедактор заданий §8§l«");
        renderQuestList(h);
        p.openInventory(h.inv);
    }

    private void renderQuestList(Holder h) {
        Inventory inv = h.inv;
        frame(inv);
        int slot = 9;
        for (Quest q : pm.quests.values()) {
            if (slot > 44) break;
            inv.setItem(slot++, item(q.type.icon, "§5§l" + ChatColor.stripColor(q.name),
                    "§7Тип: §f" + q.type.title,
                    "§7Цель: §f" + targetText(q),
                    "§7Количество: §f" + q.amount,
                    "§7Награда: §d+" + q.xp + " опыта",
                    q.daily ? "§8⟳ Ежедневное" : "§8Одноразовое",
                    "",
                    "§dКлик — редактировать"));
        }
        inv.setItem(45, item(Material.ARROW, "§7← Назад"));
        inv.setItem(49, item(Material.LIME_DYE, "§a+ Новое задание"));
    }

    private void clickQuestList(Player p, Holder h, int slot) {
        if (slot == 45) {
            openAdmin(p, 0);
            return;
        }
        if (slot == 49) {
            int n = 1;
            while (pm.quests.containsKey("quest" + n)) n++;
            Quest q = new Quest("quest" + n);
            pm.quests.put(q.id, q);
            pm.saveQuests();
            openQuestEdit(p, q.id);
            return;
        }
        int idx = slot - 9;
        List<Quest> list = new ArrayList<>(pm.quests.values());
        if (idx >= 0 && idx < list.size() && slot <= 44) openQuestEdit(p, list.get(idx).id);
    }

    public void openQuestEdit(Player p, String id) {
        Holder h = make(Kind.QUEST_EDIT, 27, "§8§l» §5§lЗадание §8§l«");
        h.id = id;
        renderQuestEdit(h);
        p.openInventory(h.inv);
    }

    private void renderQuestEdit(Holder h) {
        Inventory inv = h.inv;
        Quest q = pm.quests.get(h.id);
        frame(inv);
        if (q == null) return;
        inv.setItem(10, item(q.type.icon, "§5Тип: §f" + q.type.title, "§7Клик — сменить тип"));
        inv.setItem(11, item(Material.NAME_TAG, "§5Цель: §f" + targetText(q),
                "§7Клик — ввести в чате", "§8Например: STONE, ZOMBIE, ANY"));
        inv.setItem(12, item(Material.REPEATER, "§5Количество: §f" + q.amount, "§7ЛКМ §a+1§7, ПКМ §c−1", "§7Shift — по 10"));
        inv.setItem(13, item(Material.EXPERIENCE_BOTTLE, "§5Награда: §d" + q.xp + " опыта", "§7ЛКМ §a+10§7, ПКМ §c−10", "§7Shift — по 100"));
        inv.setItem(14, item(Material.CLOCK, "§5Ежедневное: " + (q.daily ? "§aда" : "§cнет"), "§7Клик — переключить",
                "§8Ежедневные задания сбрасываются каждый день"));
        inv.setItem(15, item(Material.OAK_SIGN, "§5Название: §f" + q.name, "§7Клик — ввести в чате", "§8Можно цвета через &"));
        inv.setItem(16, item(Material.TNT, "§cУдалить задание", "§7Shift + клик"));
        if (q.type == Quest.Type.ENCHANT_ITEM) {
            inv.setItem(19, item(Material.ENCHANTED_BOOK, "§5Чара: §f" + (q.enchant.equalsIgnoreCase("ANY") ? "любая" : q.enchant),
                    "§7Клик — ввести в чате", "§8Например: SHARPNESS, PROTECTION, MENDING, ANY"));
            inv.setItem(20, item(Material.GLOWSTONE_DUST, "§5Мин. уровень чары: §f" + q.level, "§7ЛКМ §a+1§7, ПКМ §c−1"));
        }
        inv.setItem(22, item(Material.ARROW, "§7← Назад"));
    }

    private String normalizeEnchant(String s) {
        String u = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (u.equals("any") || u.equals("любая") || u.equals("любой")) return "ANY";
        NamespacedKey key = NamespacedKey.fromString(u);
        Enchantment en = key == null ? null : Enchantment.getByKey(key);
        return en == null ? null : en.getKey().getKey().toUpperCase(Locale.ROOT);
    }

    private String normalizeTarget(Quest.Type t, String s) {
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (u.equals("ANY") || u.equals("ЛЮБОЙ") || u.equals("ЛЮБАЯ") || u.equals("ЛЮБОЕ")) {
            return t == Quest.Type.DELIVER_ITEM ? null : "ANY"; // сдавать «любой предмет» нельзя
        }
        if (t == Quest.Type.KILL_ENTITY) {
            try {
                return EntityType.valueOf(u).name();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        Material m = Material.matchMaterial(u);
        return m == null ? null : m.name();
    }

    private void clickQuestEdit(Player p, Holder h, int slot, ClickType c) {
        Quest q = pm.quests.get(h.id);
        if (q == null) {
            openQuestList(p);
            return;
        }
        switch (slot) {
            case 10 -> {
                q.type = q.type.next();
                q.target = q.type == Quest.Type.DELIVER_ITEM ? "DIAMOND" : "ANY";
                q.enchant = "ANY";
                q.level = 1;
                pm.saveQuests();
                renderQuestEdit(h);
            }
            case 11 -> ask(p, "Введи цель: название блока/предмета/существа (§fSTONE§7, §fZOMBIE§7) или §fANY§7 для любой:",
                    s -> {
                        String t = normalizeTarget(q.type, s);
                        if (t == null) {
                            p.sendMessage(PREFIX() + "§cНеизвестная цель: §f" + s);
                            return;
                        }
                        q.target = t;
                        pm.saveQuests();
                    },
                    () -> openQuestEdit(p, q.id));
            case 12 -> {
                q.amount = Math.max(1, q.amount + step(c, 1, 10));
                pm.saveQuests();
                renderQuestEdit(h);
            }
            case 13 -> {
                q.xp = Math.max(0, q.xp + step(c, 10, 100));
                pm.saveQuests();
                renderQuestEdit(h);
            }
            case 14 -> {
                q.daily = !q.daily;
                pm.saveQuests();
                renderQuestEdit(h);
            }
            case 15 -> ask(p, "Введи новое название задания (можно цвета через &):",
                    s -> {
                        q.name = ChatColor.translateAlternateColorCodes('&', s);
                        pm.saveQuests();
                    },
                    () -> openQuestEdit(p, q.id));
            case 16 -> {
                if (c.isShiftClick()) {
                    pm.quests.remove(q.id);
                    pm.saveQuests();
                    openQuestList(p);
                }
            }
            case 19 -> {
                if (q.type == Quest.Type.ENCHANT_ITEM) {
                    ask(p, "Введи чару (§fSHARPNESS§7, §fMENDING§7, §fPROTECTION§7...) или §fANY§7 для любой:",
                            s -> {
                                String en = normalizeEnchant(s);
                                if (en == null) {
                                    p.sendMessage(PREFIX() + "§cНеизвестная чара: §f" + s);
                                    return;
                                }
                                q.enchant = en;
                                pm.saveQuests();
                            },
                            () -> openQuestEdit(p, q.id));
                }
            }
            case 20 -> {
                if (q.type == Quest.Type.ENCHANT_ITEM) {
                    q.level = Math.max(1, Math.min(10, q.level + step(c, 1, 1)));
                    pm.saveQuests();
                    renderQuestEdit(h);
                }
            }
            case 22 -> openQuestList(p);
            default -> { }
        }
    }

    // ================================================================ ввод в чат

    private void ask(Player p, String message, Consumer<String> onInput, Runnable back) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), new Prompt(onInput, back));
        p.sendMessage(PREFIX() + message);
        p.sendMessage("§8Напиши §fотмена§8, чтобы вернуться.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!msg.equalsIgnoreCase("отмена") && !msg.equalsIgnoreCase("cancel")) pr.input.accept(msg);
            pr.back.run();
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
        Holder h = editors.remove(e.getPlayer().getUniqueId());
        if (h != null) saveLevelEditor(h);
    }

    // ================================================================ события инвентаря

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (h.kind != Kind.MAIN && h.kind != Kind.QUESTS && !p.hasPermission("battlepass.admin")) {
            e.setCancelled(true);
            p.closeInventory();
            return;
        }
        boolean inTop = e.getClickedInventory() != null && e.getClickedInventory().equals(e.getInventory());

        if (h.kind == Kind.LEVEL_EDIT) {
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            if (!inTop) return;
            if (e.getRawSlot() < EDIT_ITEM_SLOTS) return;
            e.setCancelled(true);
        } else {
            e.setCancelled(true);
            if (!inTop) return;
        }

        int slot = e.getRawSlot();
        ClickType c = e.getClick();
        switch (h.kind) {
            case MAIN -> clickMain(p, h, slot);
            case QUESTS -> clickQuests(p, h, slot);
            case ADMIN -> clickAdmin(p, h, slot, c);
            case LEVEL_EDIT -> clickLevelEdit(p, h, slot, c);
            case QUEST_LIST -> clickQuestList(p, h, slot);
            case QUEST_EDIT -> clickQuestEdit(p, h, slot, c);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        int size = e.getInventory().getSize();
        for (int s : e.getRawSlots()) {
            if (s < size && (h.kind != Kind.LEVEL_EDIT || s >= EDIT_ITEM_SLOTS)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h && h.kind == Kind.LEVEL_EDIT) {
            saveLevelEditor(h);
            editors.remove(e.getPlayer().getUniqueId());
        }
    }
}
