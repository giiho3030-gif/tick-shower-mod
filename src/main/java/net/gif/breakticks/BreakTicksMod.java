package net.gif.breakticks;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.gif.breakticks.mixin.BreakingAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Break Ticks: while you mine a block in survival/adventure the elapsed/total break ticks ({@code 12/45t})
 * are painted flat onto the exact face you are hitting, on top of the vanilla crack overlay.
 *
 * <p>Everything is worked out once per client tick and cached for the frames in between, so the render event
 * does no world lookups, no allocation-heavy string building and no per-block scans.
 */
public final class BreakTicksMod implements ClientModInitializer {
	/**
	 * Block + face of the last punch, written by {@code MultiPlayerGameModeFaceMixin} on the client thread.
	 * Required because vanilla passes the hit face to the interaction manager as a throwaway argument and
	 * never stores it.
	 */
	public static Direction lastHitFace;
	public static BlockPos lastHitPos;

	/** One font line is 9 units tall, so 0.02 makes the label about 18 cm high - a third of a face. */
	private static final float TEXT_SCALE = 0.02F;
	/** 0.5 lands on the face plane, the extra 0.006 floats just outside it so nothing can z-fight. */
	private static final float FACE_SHIFT = 0.506F;

	private static final Matrix4f[] FACE_ROTATION = new Matrix4f[6];

	static {
		for (Direction direction : Direction.values()) {
			FACE_ROTATION[direction.get3DDataValue()] = faceRotation(direction);
		}
	}

	/**
	 * Matrix that lays the flat text plane onto a block face. Text is emitted in the local XY plane with the
	 * glyphs facing along local +Z, so the matrix needs three columns:
	 *
	 * <ul>
	 *   <li>{@code n} - the face normal, i.e. out of the block towards the viewer;
	 *   <li>{@code u} - the world direction that should read as "up" on that face (world +Y for the four side
	 *       faces, north/-Z for the top and bottom faces);
	 *   <li>{@code r} - {@code u x n}, that order on purpose: it keeps the basis right handed. {@code n x u}
	 *       would mirror the text.
	 * </ul>
	 *
	 * <p>Minecraft axes are +X east, +Y up, +Z south, and every vector here is a signed unit axis, so the
	 * matrix is an exact signed permutation. Joml's 16 float constructor is row major, so the three basis
	 * vectors read down the columns.
	 */
	private static Matrix4f faceRotation(Direction face) {
		float nx = face.getStepX();
		float ny = face.getStepY();
		float nz = face.getStepZ();
		boolean vertical = face.getAxis().isVertical();
		float ux = 0.0F;
		float uy = vertical ? 0.0F : 1.0F;
		float uz = vertical ? -1.0F : 0.0F;
		float rx = uy * nz - uz * ny; // r = u x n
		float ry = uz * nx - ux * nz;
		float rz = ux * ny - uy * nx;

		return new Matrix4f(rx, ux, nx, 0.0F, ry, uy, ny, 0.0F, rz, uz, nz, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
	}

	private static Minecraft client;
	private static boolean active;
	private static BlockPos renderPos;
	private static Direction renderFace;
	private static FormattedCharSequence renderLabel;
	// Total-tick cache, rebuilt only when the target, the tool or the block state changes.
	private static BlockPos cachedPos;
	private static ItemStack cachedStack;
	private static BlockState cachedState;
	private static int cachedTotalTicks;

	@Override
	public void onInitializeClient() {
		client = Minecraft.getInstance();
		// Track/caching: once per tick, right after vanilla's own progress accumulation for this tick.
		ClientTickEvents.END_CLIENT_TICK.register(BreakTicksMod::onEndClientTick);
		// Drawing: BEFORE_GIZMOS fires inside LevelRenderer#submitFeatures right after vanilla submitted the
		// block destroy animation for this frame, which is the last moment new submits are taken.
		LevelRenderEvents.BEFORE_GIZMOS.register(BreakTicksMod::onBeforeGizmos);
	}

	private static void onEndClientTick(Minecraft client) {
		MultiPlayerGameMode gameMode = client.gameMode;

		// Not breaking, creative, or no world: hide on the same tick, no fade.
		if (gameMode == null || client.level == null || client.player == null
				|| client.player.getAbilities().instabuild || !gameMode.isDestroying()) {
			active = false;
			return;
		}

		BreakingAccessor accessor = (BreakingAccessor) gameMode;
		BlockPos breakingPos = accessor.breakticks$getDestroyBlockPos();

		// Prefer the face the mixin captured for this exact block; otherwise fall back to the crosshair clip,
		// which vanilla re-does every tick anyway.
		Direction face = breakingPos.equals(lastHitPos) ? lastHitFace : null;

		if (face == null && client.hitResult instanceof BlockHitResult blockHit
				&& blockHit.getType() == HitResult.Type.BLOCK && breakingPos.equals(blockHit.getBlockPos())) {
			face = blockHit.getDirection();
		}

		if (face == null) {
			active = false;
			return;
		}

		BlockState state = client.level.getBlockState(breakingPos);
		ItemStack stack = client.player.getMainHandItem();

		if (cachedPos == null || !cachedPos.equals(breakingPos) || cachedStack != stack || cachedState != state) {
			cachedPos = breakingPos.immutable();
			cachedStack = stack;
			cachedState = state; // block states are interned, so == is a cheap exact state compare
			cachedTotalTicks = totalTicks(client, breakingPos, state);
		}

		// cachedTotalTicks: 0 = breaks instantly, -1 = unbreakable (hidden), >0 = normal.
		if (cachedTotalTicks < 0) {
			active = false;
			return;
		}

		int elapsed = cachedTotalTicks == 0
				? 0
				: Math.min(cachedTotalTicks, Math.round(accessor.breakticks$getDestroyProgress() * cachedTotalTicks));
		String text = cachedTotalTicks == 0 ? "0t" : elapsed + "/" + cachedTotalTicks + "t";

		renderPos = breakingPos.immutable();
		renderFace = face;
		renderLabel = Component.literal(text).getVisualOrderText();
		active = true;
	}

	/**
	 * Total ticks to break this block, taken from vanilla's own per-tick delta so the number can never drift:
	 * {@code MultiPlayerGameMode#continueDestroyBlock} does {@code destroyProgress += state.getDestroyProgress
	 * (player, level, pos)} every tick and pops the block at {@code destroyProgress >= 1.0}, which means the
	 * break takes {@code ceil(1 / delta)} ticks. A delta of 0 or less is unbreakable (hardness below 0, e.g.
	 * bedrock and barriers, or a state that cannot be destroyed by this player), a delta of 1 or more breaks
	 * on the very first tick.
	 */
	private static int totalTicks(Minecraft client, BlockPos pos, BlockState state) {
		float delta = state.getDestroyProgress(client.player, client.level, pos);

		if (delta <= 0.0F) {
			return -1;
		}

		return delta >= 1.0F ? 0 : (int) Math.ceil(1.0F / delta);
	}

	private static void onBeforeGizmos(LevelRenderContext context) {
		if (!active) {
			return;
		}

		PoseStack poseStack = context.poseStack();

		if (poseStack == null || renderLabel == null || renderPos == null || renderFace == null) {
			return;
		}

		Vec3 camera = context.levelState().cameraRenderState.pos;
		int width = client.font.width(renderLabel);

		poseStack.pushPose();
		// The level pose stack is identity at the start of every frame, so everything is camera relative,
		// exactly like the crack geometry vanilla submitted for this block.
		poseStack.translate(renderPos.getX() - camera.x, renderPos.getY() - camera.y, renderPos.getZ() - camera.z);
		// Centre of the hit face, pushed 6 mm outward along its normal.
		poseStack.translate(
				0.5F + renderFace.getStepX() * FACE_SHIFT,
				0.5F + renderFace.getStepY() * FACE_SHIFT,
				0.5F + renderFace.getStepZ() * FACE_SHIFT
		);
		// Lie the text down on the face, shrink it, and flip Y (the baked glyphs already negate Y, so this is
		// what makes world text read top-side up - same as name tags and text displays).
		poseStack.mulPose(FACE_ROTATION[renderFace.get3DDataValue()]);
		poseStack.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

		// AFTER_TERRAIN is the vanilla submit phase that runs right after the BREAKING_OVERLAY phase
		// (FeatureRenderDispatcher#executeTranslucentAfterTerrain), so the glyphs land over the cracks.
		context.submitNodeCollector().submitCustom(
				SubmitRenderPhases.AFTER_TERRAIN,
				new TextFeatureRenderer.Submit(
						new Matrix4f(poseStack.last().pose()),
						-width / 2.0F,
						-4.5F,
						renderLabel,
						false,
						Font.DisplayMode.POLYGON_OFFSET, // what signs use; SEE_THROUGH ignores depth instead
						LightCoordsUtil.FULL_BRIGHT,
						0xFFFFFFFF, // white text
						0xB0000000, // ~70 % black plate; Font draws it whenever the alpha is non-zero
						0 // 0 = no 8-direction outline
				)
		);

		poseStack.popPose();
	}
}
