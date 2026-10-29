package dev.battlepass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlayerData {
    public int xp = 0;
    public final Set<Integer> claimed = new HashSet<>();
    public final Map<String, Integer> progress = new HashMap<>();
    /** Задания, цель которых достигнута (награду ещё можно не забрать). */
    public final Set<String> done = new HashSet<>();
    /** Задания, за которые награда уже получена. */
    public final Set<String> rewarded = new HashSet<>();
    public String day = "";
}
