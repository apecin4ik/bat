package dev.battlepass;

import org.bukkit.Material;

public class Quest {

    public enum Type {
        BREAK_BLOCK("Ломать блоки", Material.IRON_PICKAXE),
        PLACE_BLOCK("Ставить блоки", Material.BRICKS),
        KILL_ENTITY("Убивать существ", Material.IRON_SWORD),
        FISH("Ловить рыбу", Material.FISHING_ROD),
        CRAFT_ITEM("Крафтить предметы", Material.CRAFTING_TABLE),
        ENCHANT_ITEM("Зачаровать предметы", Material.ENCHANTING_TABLE),
        /** Сдача предметов: игрок кликает по заданию в меню, предметы забираются из инвентаря. */
        DELIVER_ITEM("Сдать предметы", Material.CHEST);

        public final String title;
        public final Material icon;

        Type(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }

        public Type next() {
            Type[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public String id;
    public String name = "Новое задание";
    public Type type = Type.BREAK_BLOCK;
    public String target = "ANY";
    public int amount = 100;
    public int xp = 100;
    public boolean daily = false;
    /** Только для ENCHANT_ITEM: ключ чары (SHARPNESS, MENDING...) или ANY. */
    public String enchant = "ANY";
    /** Только для ENCHANT_ITEM: минимальный уровень чары. */
    public int level = 1;

    public Quest(String id) {
        this.id = id;
    }
}
