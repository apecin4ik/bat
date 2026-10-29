package dev.battlepass;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class LevelReward {
    public final int level;
    public final List<ItemStack> items = new ArrayList<>();
    public final List<String> commands = new ArrayList<>();
    /** 0 = использовать значение по умолчанию из config.yml */
    public int xp = 0;

    public LevelReward(int level) {
        this.level = level;
    }
}
