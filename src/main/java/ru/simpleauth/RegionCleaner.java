package ru.simpleauth;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Очистка прямоугольной области. Работает порциями по тикам, чтобы сервер
 * не подвисал, и не отправляет ни одной команды — в логах пусто.
 */
public class RegionCleaner {

    /** Сколько блоков обрабатываем за один тик. */
    private static final int PER_TICK = 20_000;
    /** Предохранитель от случайного стирания половины мира. */
    public static final long MAX_VOLUME = 3_000_000L;

    private final Deque<Task> queue = new ArrayDeque<>();

    private static class Task {
        ServerWorld world;
        UUID owner;
        int minX, minY, minZ, maxX, maxY, maxZ;
        int x, y, z;
        long done;
        long total;
    }

    public long volume(BlockPos a, BlockPos b) {
        long dx = Math.abs(a.getX() - b.getX()) + 1L;
        long dy = Math.abs(a.getY() - b.getY()) + 1L;
        long dz = Math.abs(a.getZ() - b.getZ()) + 1L;
        return dx * dy * dz;
    }

    public long enqueue(ServerPlayerEntity player, BlockPos a, BlockPos b) {
        Task task = new Task();
        task.world = player.getServerWorld();
        task.owner = player.getUuid();
        task.minX = Math.min(a.getX(), b.getX());
        task.minY = Math.min(a.getY(), b.getY());
        task.minZ = Math.min(a.getZ(), b.getZ());
        task.maxX = Math.max(a.getX(), b.getX());
        task.maxY = Math.max(a.getY(), b.getY());
        task.maxZ = Math.max(a.getZ(), b.getZ());
        task.x = task.minX;
        task.y = task.minY;
        task.z = task.minZ;
        task.total = volume(a, b);
        queue.add(task);
        return task.total;
    }

    public void tick(MinecraftServer server) {
        Task task = queue.peek();
        if (task == null) return;

        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int budget = PER_TICK;

        while (budget-- > 0) {
            pos.set(task.x, task.y, task.z);
            try {
                // NOTIFY_LISTENERS без обновления соседей: иначе песок и вода
                // начнут пересчитываться на каждом блоке
                task.world.setBlockState(pos, air, Block.NOTIFY_LISTENERS);
            } catch (Exception ignored) {
            }
            task.done++;

            task.x++;
            if (task.x > task.maxX) {
                task.x = task.minX;
                task.z++;
                if (task.z > task.maxZ) {
                    task.z = task.minZ;
                    task.y++;
                    if (task.y > task.maxY) {
                        queue.poll();
                        notifyDone(server, task);
                        return;
                    }
                }
            }
        }
    }

    private void notifyDone(MinecraftServer server, Task task) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(task.owner);
        if (player != null) {
            player.sendMessage(Text.literal("Очищено блоков: " + task.done)
                    .formatted(Formatting.DARK_GRAY), false);
        }
    }

    public boolean busy() {
        return !queue.isEmpty();
    }
}
