package io.github.m3t4f1v3.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PowderSnowBlock.class)
public class PowderSnowBlockMixin {
    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void snowstorm$removeCollisionUnderSnowCap(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        if (!level.getBlockState(pos.above()).is(Blocks.SNOW)) {
            return;
        }
        Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
        if (entity != null && PowderSnowBlock.canEntityWalkOnPowderSnow(entity)) {
            return;
        }
        if (entity == null || !PowderSnowBlock.canEntityWalkOnPowderSnow(entity)) {
            cir.setReturnValue(Shapes.empty());
        }
    }
}
