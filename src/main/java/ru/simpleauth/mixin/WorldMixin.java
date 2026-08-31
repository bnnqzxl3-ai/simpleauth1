package ru.simpleauth.mixin;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.simpleauth.AuthManager;
import ru.simpleauth.SimpleAuth;

/**
 * Пока включён режим без физики, у любой установки блока снимается флаг
 * «оповестить соседей». Из-за этого флага наковальни падают, двери и факелы
 * отваливаются без опоры, а вода начинает течь сразу после установки.
 */
@Mixin(World.class)
public abstract class WorldMixin {

    @ModifyVariable(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true)
    private int simpleauth$stripNeighborUpdates(int flags) {
        AuthManager manager = SimpleAuth.manager();
        if (manager != null && manager.isPhysicsDisabled()) {
            return flags & ~Block.NOTIFY_NEIGHBORS;
        }
        return flags;
    }

    /**
     * Команда /fill ставит блоки без обновлений, а затем отдельным проходом
     * сама пересчитывает соседей. Именно на нём отваливаются двери, факелы
     * и цветы, поэтому этот проход тоже глушим.
     */
    @Inject(method = "updateNeighbors", at = @At("HEAD"), cancellable = true)
    private void simpleauth$skipNeighborUpdates(BlockPos pos, Block sourceBlock, CallbackInfo ci) {
        AuthManager manager = SimpleAuth.manager();
        if (manager != null && manager.isPhysicsDisabled()) {
            ci.cancel();
        }
    }
}
