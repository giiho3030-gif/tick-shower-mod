package net.gif.breakticks.mixin;

import net.gif.breakticks.BreakTicksMod;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla hands the hit {@link Direction} to the interaction manager as a plain method argument and then
 * throws it away (only the position and the progress are stored), so this records the face the client last
 * punched. {@code continueDestroyBlock} fires once per tick with the face of the *current* crosshair clip,
 * which is what makes the number follow the face you are actually looking at.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeFaceMixin {
	@Inject(
			method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
			at = @At("HEAD")
	)
	private void breakticks$onStartDestroy(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> info) {
		BreakTicksMod.lastHitPos = pos;
		BreakTicksMod.lastHitFace = face;
	}

	@Inject(
			method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
			at = @At("HEAD")
	)
	private void breakticks$onContinueDestroy(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> info) {
		BreakTicksMod.lastHitPos = pos;
		BreakTicksMod.lastHitFace = face;
	}

	/** Releasing the mouse button (or the block popping) ends the break: drop the face with it. */
	@Inject(method = "stopDestroyBlock()V", at = @At("HEAD"))
	private void breakticks$onStopDestroy(CallbackInfo info) {
		BreakTicksMod.lastHitFace = null;
		BreakTicksMod.lastHitPos = null;
	}
}
