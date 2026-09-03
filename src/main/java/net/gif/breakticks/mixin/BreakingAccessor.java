package net.gif.breakticks.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the fields vanilla keeps on {@link MultiPlayerGameMode} while the client is
 * breaking a block. Mojang-mapped names for Minecraft 26.2:
 *
 * <pre>{@code
 * private BlockPos destroyBlockPos;   // block being broken  (Yarn: currentBreakingPos)
 * private float  destroyProgress;     // 0..1 accumulated     (Yarn: currentBreakingProgress)
 * private boolean isDestroying;       // already public as isDestroying() - no accessor needed
 * }</pre>
 */
@Mixin(MultiPlayerGameMode.class)
public interface BreakingAccessor {
	/** The block the client is currently breaking ({@code new BlockPos(-1,-1,-1)} when idle). */
	@Accessor("destroyBlockPos")
	BlockPos breakticks$getDestroyBlockPos();

	/** Accumulated break progress for the current target, 0..1. Vanilla breaks the block at 1.0. */
	@Accessor("destroyProgress")
	float breakticks$getDestroyProgress();
}
