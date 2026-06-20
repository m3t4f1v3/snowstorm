package io.github.m3t4f1v3.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
import org.spongepowered.asm.mixin.Unique;
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

    @WrapMethod(method = "getCollisionShape")
    private VoxelShape snowstorm$emptyCollisionShapeOverPowderSnow(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            Operation<VoxelShape> original
    ) {
        VoxelShape originalShape = original.call(state, level, pos, context);
        if (!snowstorm$isPowderSnowLoaded(level, pos.below())) {
            return originalShape;
        }
        Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
        if (entity != null && PowderSnowBlock.canEntityWalkOnPowderSnow(entity)) {
            return originalShape;
        }
        return Shapes.empty();
    }

    @Unique
    private static boolean snowstorm$isPowderSnowLoaded(BlockGetter level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
            return chunk != null && chunk.getBlockState(pos).is(Blocks.POWDER_SNOW);
        }
        if (level instanceof LevelReader levelReader) {
            ChunkAccess chunk = levelReader.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk != null && chunk.getBlockState(pos).is(Blocks.POWDER_SNOW);
        }

        return level.getBlockState(pos).is(Blocks.POWDER_SNOW);
    }
}
