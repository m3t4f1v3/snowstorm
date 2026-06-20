package io.github.m3t4f1v3.util;

import com.example.vsencumbrance.PlayerMassTracker;

import io.github.m3t4f1v3.Snowstorm;
import io.github.m3t4f1v3.mixin.LivingEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Snowstorm.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class IceWalkingHandler {
    
    // Debug/test configuration
    public static final boolean DEBUG_MODE = false;
    private static final double TEST_WEIGHT = 150.0; // For testing without weight system
    private static final boolean USE_TEST_WEIGHT = false; // Set to true to bypass mass tracker
    
    private static final double MAX_STRESS = Double.MAX_VALUE; // Cap stress to prevent cliff behavior
    
    private static final int BASE_TICK_DELAY = 5; // More responsive base delay
    
    // Cartoon effect: larger cracks on impact
    private static final int MAX_CRACK_RADIUS = 3; // Big cartoon cracks!
    
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        
        if (level.isClientSide()) return;
        if (!(entity instanceof Player player)) return;
        
        // Don't crack while swimming or in water
        if (player.isSwimming() || player.isInWater() || player.isUnderWater()) return;

        // only when we are on ice or frosted ice
        BlockPos pos = player.getOnPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.ICE) && !state.is(Blocks.FROSTED_ICE)) return;
        
        // Get player weight (with test option)
        double playerWeight;
        if (USE_TEST_WEIGHT) {
            playerWeight = TEST_WEIGHT;
            if (DEBUG_MODE && player.tickCount % 40 == 0) {
                System.out.println("[DEBUG] Test weight: " + playerWeight);
            }
        } else {
            playerWeight = PlayerMassTracker.computeTotalMass(player);
        }
        
        // Calculate stress (more responsive)
        double stress = calculateStress(player, playerWeight);
        
        if (DEBUG_MODE && player.tickCount % 20 == 0) {
            System.out.println(String.format("[DEBUG] Stress: %.2f | Weight: %.1f | OnGround: %s | Sprint: %s",
                stress, playerWeight, player.onGround(), player.isSprinting()));
        }
        
        // Apply effects based on stress level
        if (stress > 0.5) { // Lowered threshold for more responsiveness
            applyIceCracking(player, level, stress);
        }
        
        // Big cartoon cracks on heavy impacts (falling or sprint jumps)
        if ((player.fallDistance > 1.0f || (player.isSprinting() && !player.onGround() && player.getDeltaMovement().y < 0)) 
            && stress > 8.0) {
            createCartoonCrackPattern(player, level, stress);
        }
    }
    
    private static double calculateStress(Player player, double playerWeight) {
        // Only crack while standing on ground
        if (!player.onGround()) return 0;
        
        // Base stress from weight
        double overweight = Math.max(0, playerWeight - ShipWeightUtil.WEIGHT_THRESHOLD);
        double weightStress = Math.min(MAX_STRESS / 2, (overweight / 1500.0) * 8.0); // Linear scaling, more responsive
        
        // Movement contribution (more responsive)
        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double movementStress = Math.min(MAX_STRESS / 2, horizontalSpeed * 12.0); // Increased multiplier
        
        // Combine stresses
        double stress = weightStress + movementStress;
        
        // Sprinting amplifies significantly
        if (player.isSprinting()) {
            stress *= 1.6;
        }
        
        // Jump landing impact (very responsive)
        if (player.fallDistance > 0.5f) {
            double impactBonus = Math.min(15.0, player.fallDistance * 2.5);
            stress += impactBonus;
            
            if (DEBUG_MODE && player.fallDistance > 0.5f) {
                System.out.println("[IMPACT] Fall distance: " + player.fallDistance + " | Bonus: " + impactBonus);
            }
        }
        
        // Recent jumping adds stress (for jump landings)
        if (((LivingEntityAccessor) player).isJumping() && !player.onGround()) {
            stress += 3.0;
        }
        
        return Math.min(MAX_STRESS, Math.max(0, stress));
    }
    
    private static void applyIceCracking(Player player, Level level, double stress) {
        // Dynamic radius based on stress (cartoon scaling)
        int radius = 1;
        if (stress > 12.0) radius = 2;
        if (stress > 20.0) radius = 3;
        
        // Calculate crack delay (more responsive = lower numbers)
        int crackDelay = Math.max(1, (int)(8.0 - Math.min(7.0, stress / 3.0)));
        
        // Number of crack ticks (for propagation effect)
        int ticksToSchedule = Math.min(25, 2 + (int)(stress / 2.5));
        
        BlockPos centerPos = player.getOnPos();
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Skip corners on smaller cracks for better performance
                if (radius == 1 && Math.abs(x) + Math.abs(z) > 1) continue;
                if (radius == 2 && Math.abs(x) == 2 && Math.abs(z) == 2) continue;
                
                BlockPos pos = centerPos.offset(x, 0, z);
                
                // Random chance to crack surrounding blocks for cartoon effect
                if (Math.abs(x) + Math.abs(z) > radius) {
                    if (level.random.nextFloat() > 0.3f) continue;
                }
                
                BlockState state = level.getBlockState(pos);
                
                // Regular ice -> frosted ice
                if (state.is(Blocks.ICE)) {
                    level.setBlockAndUpdate(pos, Blocks.FROSTED_ICE.defaultBlockState());
                    scheduleCrackTicks(level, pos, crackDelay, ticksToSchedule);
                }
                // Existing frosted ice -> advance or break
                else if (state.is(Blocks.FROSTED_ICE)) {
                    int age = state.getValue(FrostedIceBlock.AGE);
                    
                    // Progressive cracking based on stress
                    int advanceChance = (int)(stress / 3.0);
                    if (level.random.nextInt(Math.max(1, 20 - advanceChance)) == 0) {
                        if (age < 3) {
                            level.setBlockAndUpdate(pos, state.setValue(FrostedIceBlock.AGE, age + 1));
                            if (DEBUG_MODE && age == 2) {
                                System.out.println("[CRACK] Ice advanced to stage " + (age + 1));
                            }
                        } else {
                            level.destroyBlock(pos, false);
                            if (DEBUG_MODE) {
                                System.out.println("[BREAK] Ice shattered!");
                            }
                        }
                    }
                    
                    scheduleCrackTicks(level, pos, crackDelay, ticksToSchedule);
                }
            }
        }
    }
    
    private static void createCartoonCrackPattern(Player player, Level level, double stress) {
        // Big cartoon-style cracks radiating outward
        BlockPos centerPos = player.getOnPos();
        int cartoonRadius = Math.min(MAX_CRACK_RADIUS, 2 + (int)(stress / 8.0));
        
        if (DEBUG_MODE) {
            System.out.println("[CARTOON!] Big crack pattern! Radius: " + cartoonRadius + " | Stress: " + stress);
        }
        
        // Radial crack pattern
        for (int r = 1; r <= cartoonRadius; r++) {
            // Different patterns based on direction
            int numCracks = 4 + (r * 2); // More cracks as radius increases
            
            for (int i = 0; i < numCracks; i++) {
                double angle = (2 * Math.PI * i / numCracks) + (level.random.nextDouble() * 0.5);
                int xOffset = (int)Math.round(Math.cos(angle) * r);
                int zOffset = (int)Math.round(Math.sin(angle) * r);
                
                BlockPos crackPos = centerPos.offset(xOffset, 0, zOffset);
                BlockState state = level.getBlockState(crackPos);
                
                if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
                    if (state.is(Blocks.ICE)) {
                        level.setBlockAndUpdate(crackPos, Blocks.FROSTED_ICE.defaultBlockState());
                    }
                    
                    // Advance multiple ages for dramatic effect
                    for (int advance = 0; advance < 2; advance++) {
                        BlockState currentState = level.getBlockState(crackPos);
                        if (currentState.is(Blocks.FROSTED_ICE)) {
                            int age = currentState.getValue(FrostedIceBlock.AGE);
                            if (age < 3) {
                                level.setBlockAndUpdate(crackPos, currentState.setValue(FrostedIceBlock.AGE, age + 1));
                            } else {
                                level.destroyBlock(crackPos, false);
                                break;
                            }
                        }
                    }
                    
                    // Immediate crack sound/visual tick
                    level.scheduleTick(crackPos, Blocks.FROSTED_ICE, 1);
                }
            }
        }
        
        // Extra: Crack the block directly under player more
        BlockPos underPos = centerPos.below();
        if (level.getBlockState(underPos).is(Blocks.FROSTED_ICE)) {
            level.scheduleTick(underPos, Blocks.FROSTED_ICE, 1);
        }
    }
    
    private static void scheduleCrackTicks(Level level, BlockPos pos, int baseDelay, int tickCount) {
        for (int i = 1; i <= Math.min(tickCount, 10); i++) {
            level.scheduleTick(pos, Blocks.FROSTED_ICE, baseDelay * i);
        }
    }

    public static void applyIceCrackingFromContactPoint(Level level, Vec3 center, double radius, double stress) {
        if (level.isClientSide()) return;
        if (radius <= 0.0 || stress <= 0.0) return;

        BlockPos centerPos = BlockPos.containing(center);
        int blockRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;

        for (int dx = -blockRadius; dx <= blockRadius; dx++) {
            for (int dz = -blockRadius; dz <= blockRadius; dz++) {
                double distanceSq = (double) dx * dx + (double) dz * dz;
                if (distanceSq > radiusSq) continue;

                double distance = Math.sqrt(distanceSq);
                double normalizedDistance = radius <= 1.0 ? 0.0 : distance / radius;
                double localStress = Math.max(0.0, stress * Math.max(0.25, 1.0 - normalizedDistance));

                applyIceCrackingForBlock(level, centerPos.offset(dx, 0, dz), localStress);
            }
        }
    }

    // Apply cracking behavior to a single block with an externally computed stress value.
    // This reuses the existing cracking progression logic but accepts a direct stress input
    // so other systems (ships, physics events) can drive ice breaking.
    public static void applyIceCrackingForBlock(Level level, BlockPos pos, double stress) {
        if (level.isClientSide()) return;

        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.ICE) && !state.is(Blocks.FROSTED_ICE)) return;

        int crackDelay = Math.max(1, (int)(8.0 - Math.min(7.0, stress / 3.0)));
        int ticksToSchedule = Math.min(25, 2 + (int)(stress / 2.5));

        // If it's regular ice, convert to frosted and schedule propagation ticks
        if (state.is(Blocks.ICE)) {
            int initialAge = 0;
            if (stress >= 3.0) {
                initialAge = 2;
            } else if (stress >= 1.5) {
                initialAge = 1;
            }

            level.setBlockAndUpdate(pos, Blocks.FROSTED_ICE.defaultBlockState().setValue(FrostedIceBlock.AGE, initialAge));
            scheduleCrackTicks(level, pos, crackDelay, ticksToSchedule);
            return;
        }

        // If it's frosted ice, advance age or break
        if (state.is(Blocks.FROSTED_ICE)) {
            int age = state.getValue(FrostedIceBlock.AGE);

            if (stress >= 3.0) {
                if (age < 3) {
                    level.setBlockAndUpdate(pos, state.setValue(FrostedIceBlock.AGE, Math.min(3, age + 2)));
                } else {
                    level.destroyBlock(pos, false);
                }
            } else if (stress >= 1.5) {
                if (age < 3) {
                    level.setBlockAndUpdate(pos, state.setValue(FrostedIceBlock.AGE, age + 1));
                } else {
                    level.destroyBlock(pos, false);
                }
            } else if (stress > 0.15) {
                int advanceChance = Math.max(1, 20 - (int)(stress * 6.0));
                if (level.random.nextInt(advanceChance) == 0) {
                    if (age < 3) {
                        level.setBlockAndUpdate(pos, state.setValue(FrostedIceBlock.AGE, age + 1));
                    } else {
                        level.destroyBlock(pos, false);
                    }
                }
            }

            scheduleCrackTicks(level, pos, crackDelay, ticksToSchedule);
        }
    }
}