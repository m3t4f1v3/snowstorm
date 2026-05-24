package io.github.m3t4f1v3.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SnowLayerBlock.class, priority = 400)
public class SnowLayerBlockMixin {
    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void snowstorm$canSurviveOnPowderSnow(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (snowstorm$isPowderSnowLoaded(level, pos.below())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void snowstorm$emptyCollisionShapeOverPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        if (!snowstorm$isPowderSnowLoaded(level, pos.below())) {
            return;
        }
        Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
        if (entity != null && PowderSnowBlock.canEntityWalkOnPowderSnow(entity)) {
            return;
        }
        cir.setReturnValue(Shapes.empty());
    }

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void snowstorm$emptyOutlineShapeOverPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        snowstorm$clearShapeWhenOverPowderSnow(level, pos, cir);
    }

    @Inject(method = "getVisualShape", at = @At("HEAD"), cancellable = true)
    private void snowstorm$emptyVisualShapeOverPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        snowstorm$clearShapeWhenOverPowderSnow(level, pos, cir);
    }

    @Inject(method = "getBlockSupportShape", at = @At("HEAD"), cancellable = true)
    private void snowstorm$emptySupportShapeOverPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        snowstorm$clearShapeWhenOverPowderSnow(level, pos, cir);
    }

    private static void snowstorm$clearShapeWhenOverPowderSnow(
            BlockGetter level,
            BlockPos pos,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        if (!snowstorm$isPowderSnowLoaded(level, pos.below())) {
            return;
        }
        cir.setReturnValue(Shapes.empty());
    }

    private static boolean snowstorm$isPowderSnowLoaded(BlockGetter level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
            return chunk != null && chunk.getBlockState(pos).is(Blocks.POWDER_SNOW);
        }
        if (level instanceof Level) {
            return false;
        }
        if (level instanceof LevelReader levelReader) {
            ChunkAccess chunk = levelReader.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk != null && chunk.getBlockState(pos).is(Blocks.POWDER_SNOW);
        }

        return level.getBlockState(pos).is(Blocks.POWDER_SNOW);
    }
}
