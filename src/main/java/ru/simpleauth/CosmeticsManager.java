package ru.simpleauth;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Косметика на частицах: крылья, нимб, аура, спираль, след.
 * Всё рисуется сервером, игрокам ничего ставить не надо.
 */
public class CosmeticsManager {

    private final AuthManager manager;
    private int tick = 0;

    public CosmeticsManager(AuthManager manager) {
        this.manager = manager;
    }

    /** Доступные виды косметики. */
    public static final String[] TYPES = {"wings", "halo", "aura", "helix", "trail"};

    private static final Map<String, ParticleEffect> PARTICLES = new LinkedHashMap<>();

    static {
        PARTICLES.put("endrod", ParticleTypes.END_ROD);
        PARTICLES.put("flame", ParticleTypes.FLAME);
        PARTICLES.put("soul", ParticleTypes.SOUL_FIRE_FLAME);
        PARTICLES.put("enchant", ParticleTypes.ENCHANT);
        PARTICLES.put("heart", ParticleTypes.HEART);
        PARTICLES.put("happy", ParticleTypes.HAPPY_VILLAGER);
        PARTICLES.put("crit", ParticleTypes.CRIT);
        PARTICLES.put("cloud", ParticleTypes.CLOUD);
        PARTICLES.put("dragon", ParticleTypes.DRAGON_BREATH);
        PARTICLES.put("totem", ParticleTypes.TOTEM_OF_UNDYING);
        PARTICLES.put("spark", ParticleTypes.ELECTRIC_SPARK);
        PARTICLES.put("glow", ParticleTypes.GLOW);
        PARTICLES.put("portal", ParticleTypes.PORTAL);
        PARTICLES.put("note", ParticleTypes.NOTE);
        PARTICLES.put("smoke", ParticleTypes.SMOKE);
        PARTICLES.put("firework", ParticleTypes.FIREWORK);
    }

    public static boolean isType(String value) {
        if (value == null) return false;
        for (String type : TYPES) {
            if (type.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    public static boolean isParticle(String value) {
        return value != null && PARTICLES.containsKey(value.toLowerCase(Locale.ROOT));
    }

    public static String typeList() {
        return String.join(", ", TYPES);
    }

    public static String particleList() {
        return String.join(", ", PARTICLES.keySet());
    }

    private static ParticleEffect particle(String name) {
        ParticleEffect effect = name == null ? null : PARTICLES.get(name.toLowerCase(Locale.ROOT));
        return effect != null ? effect : ParticleTypes.END_ROD;
    }

    private Config config() {
        return manager.config();
    }

    // -------------------------------------------------------------- данные

    public Config.Cosmetic get(String playerName) {
        if (config().cosmetics == null) return null;
        return config().cosmetics.get(playerName.toLowerCase(Locale.ROOT));
    }

    public void set(String playerName, String type, String particleName) {
        Config.Cosmetic cosmetic = get(playerName);
        if (cosmetic == null) {
            cosmetic = new Config.Cosmetic();
            cosmetic.name = playerName;
        }
        if (type != null) cosmetic.type = type.toLowerCase(Locale.ROOT);
        if (particleName != null) cosmetic.particle = particleName.toLowerCase(Locale.ROOT);
        config().cosmetics.put(playerName.toLowerCase(Locale.ROOT), cosmetic);
        config().save();
    }

    public void clear(String playerName) {
        if (config().cosmetics == null) return;
        config().cosmetics.remove(playerName.toLowerCase(Locale.ROOT));
        config().save();
    }

    // ------------------------------------------------------------ отрисовка

    public void tick(MinecraftServer server) {
        if (config().cosmetics == null || config().cosmetics.isEmpty()) return;
        tick++;
        if (tick % 2 != 0) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!manager.isAuthenticated(player)) continue;
            if (player.isSpectator()) continue;

            Config.Cosmetic cosmetic = get(player.getGameProfile().getName());
            if (cosmetic == null || cosmetic.type == null) continue;

            try {
                draw(player, cosmetic);
            } catch (Exception e) {
                SimpleAuth.LOGGER.warn("[SimpleAuth] Ошибка отрисовки косметики", e);
            }
        }
    }

    private void draw(ServerPlayerEntity player, Config.Cosmetic cosmetic) {
        ServerWorld world = player.getServerWorld();
        ParticleEffect effect = particle(cosmetic.particle);
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        switch (cosmetic.type) {
            case "wings" -> drawWings(world, player, effect, x, y, z);
            case "halo" -> drawHalo(world, effect, x, y, z);
            case "aura" -> drawAura(world, effect, x, y, z);
            case "helix" -> drawHelix(world, effect, x, y, z);
            case "trail" -> drawTrail(world, player, effect, x, y, z);
            default -> {
            }
        }
    }

    private void drawWings(ServerWorld world, ServerPlayerEntity player, ParticleEffect effect,
                           double x, double y, double z) {
        float yaw = (float) Math.toRadians(player.getYaw());
        // вектор "вперёд" и "вправо" относительно взгляда
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);

        // взмах: крылья то шире, то уже
        double flap = 0.75 + 0.25 * Math.sin(tick * 0.18);

        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 9; i++) {
                double t = i / 8.0;
                double spread = (0.18 + t * 0.85) * flap;
                double height = 1.05 + t * 1.0 - t * t * 0.55;
                double px = x + rightX * spread * side - forwardX * 0.28;
                double pz = z + rightZ * spread * side - forwardZ * 0.28;
                world.spawnParticles(effect, px, y + height, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private void drawHalo(ServerWorld world, ParticleEffect effect, double x, double y, double z) {
        double radius = 0.36;
        for (int i = 0; i < 8; i++) {
            double angle = (i / 8.0) * Math.PI * 2.0 + tick * 0.08;
            world.spawnParticles(effect,
                    x + Math.cos(angle) * radius,
                    y + 2.15,
                    z + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void drawAura(ServerWorld world, ParticleEffect effect, double x, double y, double z) {
        double radius = 0.95;
        for (int i = 0; i < 6; i++) {
            double angle = (i / 6.0) * Math.PI * 2.0 + tick * 0.12;
            world.spawnParticles(effect,
                    x + Math.cos(angle) * radius,
                    y + 0.1,
                    z + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void drawHelix(ServerWorld world, ParticleEffect effect, double x, double y, double z) {
        for (int i = 0; i < 2; i++) {
            double angle = tick * 0.22 + i * Math.PI;
            double height = ((tick * 0.04) % 2.2);
            world.spawnParticles(effect,
                    x + Math.cos(angle) * 0.55,
                    y + height,
                    z + Math.sin(angle) * 0.55,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void drawTrail(ServerWorld world, ServerPlayerEntity player, ParticleEffect effect,
                          double x, double y, double z) {
        // след только когда игрок реально движется
        double speed = player.getVelocity().horizontalLengthSquared();
        if (speed < 0.002) return;
        world.spawnParticles(effect, x, y + 0.1, z, 2, 0.15, 0.05, 0.15, 0.01);
    }
}
