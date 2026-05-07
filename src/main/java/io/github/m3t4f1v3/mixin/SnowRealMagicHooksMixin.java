package io.github.m3t4f1v3.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import snownee.snow.Hooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Hooks.class)
public class SnowRealMagicHooksMixin {
    @Inject(method = "canSnowSurvive", at = @At("HEAD"), cancellable = true, remap = false)
    private static void snowstorm$allowSnowOnPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level.getBlockState(pos.below()).is(Blocks.POWDER_SNOW)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "placeLayersOn", at = @At("HEAD"), cancellable = true, remap = false)
    private static void snowstorm$convertWeatherAccumulationToPowderSnow(
            Level level,
            BlockPos pos,
            int layers,
            boolean fallingEffect,
            BlockPlaceContext useContext,
            boolean playSound,
            boolean canConvert,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.POWDER_SNOW)) {
            BlockPos abovePos = pos.above();
            BlockPlaceContext aboveContext = BlockPlaceContext.at(useContext, abovePos, Direction.UP);
            cir.setReturnValue(Hooks.placeLayersOn(level, abovePos, layers, fallingEffect, aboveContext, playSound, canConvert));
            return;
        }

        if (layers != 1 || fallingEffect || playSound || !useContext.getItemInHand().isEmpty()) {
            return;
        }

        if (!state.is(Blocks.SNOW) || state.getValue(SnowLayerBlock.LAYERS) != SnowLayerBlock.MAX_HEIGHT - 1) {
            return;
        }

        BlockState powderSnow = Blocks.POWDER_SNOW.defaultBlockState();
        Block.pushEntitiesUp(state, powderSnow, level, pos);
        level.setBlockAndUpdate(pos, powderSnow);
        cir.setReturnValue(true);
    }
}
