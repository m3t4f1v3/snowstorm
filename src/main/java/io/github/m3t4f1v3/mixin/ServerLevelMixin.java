package io.github.m3t4f1v3.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {
    @ModifyArg(
            method = "tickChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
                    ordinal = 1
            ),
            index = 1
    )
    private BlockState snowstorm$powderSnowAtEightLayers(BlockState state) {
        if (state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == SnowLayerBlock.MAX_HEIGHT) {
            return Blocks.POWDER_SNOW.defaultBlockState();
        }

        return state;
    }
}