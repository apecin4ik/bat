package dev.battlepass;

import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Защита от фарма «поставил — сломал — поставил».
 * <p>
 * Правила:
 * <ul>
 *   <li>Блок, поставленный игроком, при ломании не засчитывается в BREAK_BLOCK.</li>
 *   <li>Если такой блок сломали, на этой позиции установка больше не засчитывается в PLACE_BLOCK
 *       (иначе можно ставить и ломать один и тот же блок по кругу).</li>
 *   <li>Исключение: выросший урожай (Ageable с максимальным возрастом) засчитывается как обычный сбор.</li>
 * </ul>
 * Память ограничена (старые записи вытесняются) и не переживает перезапуск сервера.
 */
public class AntiFarm {

    private record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final BattlePassPlugin plugin;
    private boolean enabled = true;
    private int limit = 50_000;
    /** Позиции, где сейчас стоит блок, поставленный игроком. */
    private Set<Pos> placed = lru(limit);
    /** Позиции, на которых уже ломали поставленный игроком блок: установка там не засчитывается. */
    private Set<Pos> tainted = lru(limit);

    public AntiFarm(BattlePassPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("anti-farm.enabled", true);
        int n = Math.max(1000, plugin.getConfig().getInt("anti-farm.memory", 50_000));
        if (n != limit) {
            limit = n;
            placed = lru(n);
            tainted = lru(n);
        }
    }

    /** @return true, если установку блока нужно засчитать в задание */
    public boolean onPlace(Block b) {
        if (!enabled) return true;
        Pos p = Pos.of(b);
        placed.add(p);
        return !tainted.contains(p);
    }

    /** @return true, если ломание блока нужно засчитать в задание */
    public boolean onBreak(Block b) {
        if (!enabled) return true;
        Pos p = Pos.of(b);
        if (!placed.remove(p)) return true; // блок не от игрока — обычная добыча
        if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
            return true; // выросший урожай — честный сбор
        }
        tainted.add(p);
        return false;
    }

    private static <T> Set<T> lru(int max) {
        return Collections.newSetFromMap(new LinkedHashMap<T, Boolean>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<T, Boolean> eldest) {
                return size() > max;
            }
        });
    }
}
