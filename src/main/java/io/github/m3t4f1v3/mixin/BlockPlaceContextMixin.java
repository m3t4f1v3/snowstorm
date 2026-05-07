package io.github.m3t4f1v3.mixin;

import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockPlaceContext.class)
public class BlockPlaceContextMixin {
    @Shadow
    protected boolean replaceClicked;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V",
            at = @At("TAIL")
    )
    private void snowstorm$placeSnowAbovePowderSnow(
            Level level,
            @Nullable Player player,
            InteractionHand hand,
            ItemStack stack,
            BlockHitResult hitResult,
            CallbackInfo ci
    ) {
        if (stack.is(Items.SNOW) && hitResult.getDirection() == Direction.UP && level.getBlockState(hitResult.getBlockPos()).is(Blocks.POWDER_SNOW)) {
            this.replaceClicked = false;
        }
    }
}
