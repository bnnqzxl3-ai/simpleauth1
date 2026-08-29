package ru.simpleauth;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final PlayerDatabase database;
    private Config config;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();

    public AuthManager(PlayerDatabase database, Config config) {
        this.database = database;
        this.config = config;
    }

    private static class Pending {
        // куда вернуть после входа
        String returnWorld;
        double returnX, returnY, returnZ;
        float returnYaw, returnPitch;
        // где держим до входа
        double lockX, lockY, lockZ;
        float lockYaw, lockPitch;
        String lockWorld;

        boolean needsTeleport;
        long joinedAt;
        long lastReminder;
        long lastActionBar;
        int attempts;
        boolean wasRegistered;
    }

    public PlayerDatabase database() {
        return database;
    }

    public Config config() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    public boolean isAuthenticated(ServerPlayerEntity player) {
        return player != null && authenticated.contains(player.getUuid());
    }

    // ------------------------------------------------------------------ join

    public void onJoin(ServerPlayerEntity player) {
        String name = player.getGameProfile().getName();
        boolean registered = database.isRegistered(name);

        Pending p = new Pending();
        p.joinedAt = System.currentTimeMillis();
        p.wasRegistered = registered;

        String currentWorld = player.getWorld().getRegistryKey().getValue().toString();

        // Точка возврата. Если сервер упал прямо во время авторизации, игрок сейчас
        // стоит на точке авторизации — тогда берём координаты, сохранённые в базе.
        PlayerDatabase.Account account = registered ? database.get(name) : null;
        boolean atAuthSpawn = config.authSpawn.enabled && isNear(player, config.authSpawn);

        if (atAuthSpawn && account != null && account.lastX != null) {
            p.returnWorld = account.lastWorld != null ? account.lastWorld : currentWorld;
            p.returnX = account.lastX;
            p.returnY = account.lastY;
            p.returnZ = account.lastZ;
            p.returnYaw = account.lastYaw != null ? account.lastYaw : player.getYaw();
            p.returnPitch = account.lastPitch != null ? account.lastPitch : player.getPitch();
        } else {
            p.returnWorld = currentWorld;
            p.returnX = player.getX();
            p.returnY = player.getY();
            p.returnZ = player.getZ();
            p.returnYaw = player.getYaw();
            p.returnPitch = player.getPitch();
            if (registered) {
                database.saveReturnPosition(name, p.returnWorld,
                        p.returnX, p.returnY, p.returnZ, p.returnYaw, p.returnPitch);
            }
        }

        // где держим игрока до входа
        if (config.authSpawn.enabled) {
            p.lockWorld = config.authSpawn.world;
            p.lockX = config.authSpawn.x;
            p.lockY = config.authSpawn.y;
            p.lockZ = config.authSpawn.z;
            p.lockYaw = config.authSpawn.yaw;
            p.lockPitch = config.authSpawn.pitch;
            p.needsTeleport = true;
        } else {
            p.lockWorld = currentWorld;
            p.lockX = p.returnX;
            p.lockY = p.returnY;
            p.lockZ = p.returnZ;
            p.lockYaw = p.returnYaw;
            p.lockPitch = p.returnPitch;
            p.needsTeleport = false;
        }

        pending.put(player.getUuid(), p);
        authenticated.remove(player.getUuid());

        if (config.blindnessUntilLogin) {
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.BLINDNESS, 999999, 0, false, false, false));
        }

        // Игрока держат на месте, поэтому он может висеть в воздухе.
        // Без права полёта сервер выкинет его с «Flying is not allowed».
        player.setNoGravity(true);
        player.getAbilities().allowFlying = true;
        player.getAbilities().flying = true;
        player.sendAbilitiesUpdate();

        if (config.showTitles) {
            showTitle(player,
                    Text.literal(config.messages.titleServerName)
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
                    Text.literal(registered ? config.messages.subtitleLogin : config.messages.subtitleRegister)
                            .formatted(Formatting.WHITE),
                    10, 80, 20);
        }

        send(player, registered ? config.messages.needLogin : config.messages.needRegister, Formatting.YELLOW);
    }

    public void onDisconnect(ServerPlayerEntity player) {
        boolean wasPending = pending.remove(player.getUuid()) != null;
        authenticated.remove(player.getUuid());
        // Если игрок вышел, не успев войти, состояние авторизации нельзя дать
        // записать в его файл — иначе при следующем заходе он останется
        // неуязвимым и с полётом.
        if (wasPending) {
            try {
                resetToGameMode(player, false);
            } catch (Exception e) {
                SimpleAuth.LOGGER.warn("[SimpleAuth] Не удалось сбросить состояние при выходе", e);
            }
        }
    }

    // ---------------------------------------------------------------- unlock

    private void unlock(ServerPlayerEntity player) {
        Pending p = pending.remove(player.getUuid());
        authenticated.add(player.getUuid());

        if (config.blindnessUntilLogin) {
            player.removeStatusEffect(StatusEffects.BLINDNESS);
        }

        resetToGameMode(player, true);

        // вернуть на место выхода
        if (p != null && config.returnToLastPosition && config.authSpawn.enabled) {
            ServerWorld world = worldByName(player.getServer(), p.returnWorld);
            if (world != null) {
                player.teleport(world, p.returnX, p.returnY, p.returnZ, p.returnYaw, p.returnPitch);
                send(player, config.messages.returnedToPosition, Formatting.GRAY);
            }
        }

        if (config.showTitles) {
            showTitle(player,
                    Text.literal(config.messages.titleWelcome)
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
                    Text.literal(String.format(config.messages.subtitleWelcome,
                            player.getGameProfile().getName())).formatted(Formatting.WHITE),
                    10, 60, 20);
        }
        if (config.playSounds) {
            sound(player, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2F);
        }
        player.sendMessage(Text.empty(), true);
    }

    // -------------------------------------------------------------- commands

    public boolean tryRegister(ServerPlayerEntity player, String password, String confirm) {
        String name = player.getGameProfile().getName();
        if (isAuthenticated(player)) {
            send(player, config.messages.alreadyLoggedIn, Formatting.GRAY);
            return false;
        }
        if (database.isRegistered(name)) {
            send(player, config.messages.alreadyRegistered, Formatting.RED);
            return false;
        }
        if (!password.equals(confirm)) {
            send(player, config.messages.passwordsDoNotMatch, Formatting.RED);
            return false;
        }
        if (password.length() < config.minPasswordLength) {
            send(player, String.format(config.messages.passwordTooShort, config.minPasswordLength), Formatting.RED);
            return false;
        }
        if (password.length() > config.maxPasswordLength) {
            send(player, String.format(config.messages.passwordTooLong, config.maxPasswordLength), Formatting.RED);
            return false;
        }

        Pending p = pending.get(player.getUuid());
        database.register(name, password);
        if (p != null) {
            database.saveReturnPosition(name, p.returnWorld,
                    p.returnX, p.returnY, p.returnZ, p.returnYaw, p.returnPitch);
        }
        unlock(player);
        send(player, config.messages.registered, Formatting.GREEN);
        SimpleAuth.LOGGER.info("[SimpleAuth] Новый аккаунт: {}", name);

        if (config.broadcastNewPlayer && player.getServer() != null) {
            player.getServer().getPlayerManager().broadcast(
                    Text.literal(String.format(config.messages.broadcastNewPlayerMsg, name))
                            .formatted(Formatting.LIGHT_PURPLE), false);
        }
        return true;
    }

    public boolean tryLogin(ServerPlayerEntity player, String password) {
        String name = player.getGameProfile().getName();
        if (isAuthenticated(player)) {
            send(player, config.messages.alreadyLoggedIn, Formatting.GRAY);
            return false;
        }
        if (!database.isRegistered(name)) {
            send(player, config.messages.notRegistered, Formatting.RED);
            return false;
        }
        if (database.check(name, password)) {
            database.touchLogin(name);
            unlock(player);
            send(player, config.messages.loggedIn, Formatting.GREEN);
            return true;
        }

        if (config.playSounds) {
            sound(player, SoundEvents.ENTITY_VILLAGER_NO, 1.0F);
        }

        Pending p = pending.get(player.getUuid());
        if (p != null) {
            p.attempts++;
            if (config.maxLoginAttempts > 0 && p.attempts >= config.maxLoginAttempts) {
                player.networkHandler.disconnect(Text.literal(config.messages.kickTooManyAttempts));
                return false;
            }
        }
        send(player, config.messages.wrongPassword, Formatting.RED);
        return false;
    }

    public boolean tryChangePassword(ServerPlayerEntity player, String oldPassword, String newPassword) {
        String name = player.getGameProfile().getName();
        if (!isAuthenticated(player)) {
            send(player, config.messages.mustLoginFirst, Formatting.RED);
            return false;
        }
        if (!database.check(name, oldPassword)) {
            send(player, config.messages.wrongPassword, Formatting.RED);
            return false;
        }
        if (newPassword.length() < config.minPasswordLength) {
            send(player, String.format(config.messages.passwordTooShort, config.minPasswordLength), Formatting.RED);
            return false;
        }
        if (newPassword.length() > config.maxPasswordLength) {
            send(player, String.format(config.messages.passwordTooLong, config.maxPasswordLength), Formatting.RED);
            return false;
        }
        database.changePassword(name, newPassword);
        send(player, config.messages.passwordChanged, Formatting.GREEN);
        return true;
    }

    // ------------------------------------------------------------------ tick

    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Pending p = pending.get(player.getUuid());
            if (p == null) continue;

            // телепорт на точку авторизации (один раз, на первом тике)
            if (p.needsTeleport) {
                p.needsTeleport = false;
                ServerWorld world = worldByName(server, p.lockWorld);
                if (world != null) {
                    player.teleport(world, p.lockX, p.lockY, p.lockZ, p.lockYaw, p.lockPitch);
                }
            }

            // держим на месте: возвращаем, только если реально отошёл,
            // иначе постоянные телепорты выглядят для анти-читов как рывки
            double dx = player.getX() - p.lockX;
            double dy = player.getY() - p.lockY;
            double dz = player.getZ() - p.lockZ;
            if (dx * dx + dy * dy + dz * dz > 0.05D) {
                player.networkHandler.requestTeleport(p.lockX, p.lockY, p.lockZ, p.lockYaw, p.lockPitch);
            }
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0.0F;

            // напоминание в чат
            long reminderMs = Math.max(1, config.reminderIntervalSeconds) * 1000L;
            if (now - p.lastReminder >= reminderMs) {
                p.lastReminder = now;
                send(player, p.wasRegistered ? config.messages.needLogin : config.messages.needRegister,
                        Formatting.YELLOW);
            }

            // обратный отсчёт над хотбаром
            if (config.showActionBarTimer && config.loginTimeoutSeconds > 0
                    && now - p.lastActionBar >= 1000L) {
                p.lastActionBar = now;
                long left = config.loginTimeoutSeconds - (now - p.joinedAt) / 1000L;
                if (left >= 0) {
                    Formatting color = left <= 10 ? Formatting.RED
                            : (left <= 25 ? Formatting.GOLD : Formatting.GREEN);
                    player.sendMessage(
                            Text.literal(String.format(config.messages.actionBarTimer, left)).formatted(color),
                            true);
                }
            }

            // таймаут
            if (config.loginTimeoutSeconds > 0
                    && now - p.joinedAt > config.loginTimeoutSeconds * 1000L) {
                player.networkHandler.disconnect(Text.literal(config.messages.kickTimeout));
            }
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * Приводит неуязвимость, полёт и гравитацию к тому, что положено текущему
     * режиму игры. Специально не читает прежнее состояние игрока: оно могло
     * остаться испорченным после обрыва на этапе авторизации.
     */
    public void resetToGameMode(ServerPlayerEntity player, boolean sendUpdate) {
        GameMode mode = player.interactionManager.getGameMode();
        boolean creative = mode == GameMode.CREATIVE;
        boolean spectator = mode == GameMode.SPECTATOR;

        player.setNoGravity(false);
        player.setInvulnerable(creative || spectator);

        player.getAbilities().invulnerable = creative || spectator;
        player.getAbilities().allowFlying = creative || spectator;
        player.getAbilities().flying = spectator;
        player.getAbilities().creativeMode = creative;

        if (sendUpdate) {
            player.sendAbilitiesUpdate();
        }
    }

    private static boolean isNear(ServerPlayerEntity player, Config.SpawnPoint spawn) {
        double dx = player.getX() - spawn.x;
        double dy = player.getY() - spawn.y;
        double dz = player.getZ() - spawn.z;
        return dx * dx + dy * dy + dz * dz < 9.0;
    }

    public static ServerWorld worldByName(MinecraftServer server, String id) {
        if (server == null || id == null) return null;
        try {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) return null;
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, identifier);
            return server.getWorld(key);
        } catch (Exception e) {
            SimpleAuth.LOGGER.warn("[SimpleAuth] Неизвестный мир: {}", id);
            return null;
        }
    }

    public void showTitle(ServerPlayerEntity player, Text title, Text subtitle,
                          int fadeIn, int stay, int fadeOut) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    private void sound(ServerPlayerEntity player, SoundEvent event, float pitch) {
        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                event, SoundCategory.MASTER, 0.7F, pitch);
    }

    private void sound(ServerPlayerEntity player, RegistryEntry<SoundEvent> event, float pitch) {
        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                event, SoundCategory.MASTER, 0.7F, pitch);
    }

    public static void send(ServerPlayerEntity player, String message, Formatting color) {
        player.sendMessage(Text.literal("[Auth] " + message).formatted(color), false);
    }

    public void notifyMustLogin(ServerPlayerEntity player) {
        send(player, config.messages.mustLoginFirst, Formatting.RED);
    }
}
