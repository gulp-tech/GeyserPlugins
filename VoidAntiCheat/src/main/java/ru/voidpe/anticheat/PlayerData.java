package ru.voidpe.anticheat;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Горячее состояние одного игрока. Все поля примитивные/ограниченные по размеру:
 * античит не хранит историю мира и не делает тяжёлых выборок на каждый пакет.
 */
public final class PlayerData {

    public record TimedTarget(long timeMillis, UUID target) {
    }

    public final UUID uuid;
    public boolean bedrock;
    public String inputMode = "UNKNOWN";
    public String deviceOs = "UNKNOWN";

    public boolean bypassAll;
    public boolean excludedHere;
    public long graceUntilMillis;

    // Последняя подтверждённая позиция (примитивы — без лишних Location на каждый move).
    public boolean hasLast;
    public String lastWorld = "";
    public double lastX;
    public double lastY;
    public double lastZ;
    public float lastYaw;
    public float lastPitch;
    public long lastMoveNanos;
    public int lastServerTick;
    public boolean lastOnGround;

    // Последняя безопасная точка для setback.
    public Location safeLocation;
    public long safeUpdatedMillis;

    // Speed: token bucket в блоках.
    public double speedTokens = 3.0;
    public long speedRefillNanos;
    public double lastHorizontalBps;

    // Вертикальное движение.
    public int airTicks;
    public int hoverStreak;
    public int ascentStreak;
    public int slowFallStreak;
    public int climbStreak;
    public int jesusTicks;
    public int noclipStreak;
    public double peakY;
    public double lastDeltaY;
    public long velocityGraceUntilMillis;
    public long elytraGraceUntilMillis;

    // Timer: окно количества position-движений к серверным тикам.
    public long timerWindowStartNanos;
    public int timerWindowStartTick;
    public int timerPackets;

    // Velocity / AntiKB: ожидаемое перемещение после PlayerVelocityEvent.
    public boolean velocityPending;
    public long velocityStartMillis;
    public int velocityStartTick;
    public double velocityUnitX;
    public double velocityUnitZ;
    public double velocityExpectedBlocks;
    public double velocityTravelBlocks;

    // Подряд идущие нарушения и кулдауны действий.
    public final Map<CheckId, Double> violations = new EnumMap<>(CheckId.class);
    public final Map<CheckId, Long> lastFlagMillis = new EnumMap<>(CheckId.class);
    public final Map<CheckId, Long> lastAlertMillis = new EnumMap<>(CheckId.class);
    public final Map<CheckId, Long> lastPunishMillis = new EnumMap<>(CheckId.class);
    public final Map<CheckId, Long> lastSetbackMillis = new EnumMap<>(CheckId.class);
    public boolean setbackRequested;
    public CheckId lastCheck;
    public String lastDetails = "";
    public long lastViolationMillis;

    // Ограниченные окна событий.
    public final ArrayDeque<Long> swings = new ArrayDeque<>();
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public final ArrayDeque<TimedTarget> attacks = new ArrayDeque<>();
    public int lastBreakTick = -1;
    public int breaksThisTick;
    public int lastPlaceTick = -1;
    public int placesThisTick;
    public int scaffoldPlaces;
    public long scaffoldWindowStartMillis;
    public long lastBigRotationMillis;
    public float rotationBeforeAttack;

    // Транспорт.
    public String vehicleWorld = "";
    public double vehicleX;
    public double vehicleZ;
    public long vehicleLastNanos;
    public double vehicleTokens = 5.0;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public double vl(CheckId check) {
        return violations.getOrDefault(check, 0.0);
    }

    public void setVl(CheckId check, double value) {
        if (value <= 0.001) {
            violations.remove(check);
        } else {
            violations.put(check, value);
        }
    }

    public void rememberPosition(Location to, int serverTick, long nowNanos, boolean onGround) {
        hasLast = true;
        lastWorld = to.getWorld() == null ? "" : to.getWorld().getName();
        lastX = to.getX();
        lastY = to.getY();
        lastZ = to.getZ();
        lastYaw = to.getYaw();
        lastPitch = to.getPitch();
        lastMoveNanos = nowNanos;
        lastServerTick = serverTick;
        lastOnGround = onGround;
    }

    public void resetMovement(Location to, int serverTick, long nowNanos, boolean onGround) {
        rememberPosition(to, serverTick, nowNanos, onGround);
        airTicks = 0;
        hoverStreak = 0;
        ascentStreak = 0;
        slowFallStreak = 0;
        climbStreak = 0;
        jesusTicks = 0;
        noclipStreak = 0;
        peakY = to.getY();
        lastDeltaY = 0;
        speedTokens = Math.max(speedTokens, 2.0);
        timerWindowStartNanos = nowNanos;
        timerWindowStartTick = serverTick;
        timerPackets = 0;
        velocityPending = false;
    }

    public void resetAirState() {
        airTicks = 0;
        hoverStreak = 0;
        ascentStreak = 0;
        slowFallStreak = 0;
        climbStreak = 0;
        jesusTicks = 0;
        peakY = lastY;
    }
}
