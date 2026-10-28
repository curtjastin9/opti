package com.fastanchor.mixin;

import com.fastanchor.FastAnchorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	/** Vanilla's delay (in ticks) between right-click uses. Normally set to 4 after each use. */
	@Shadow
	private int rightClickDelay;

	/** How far in front of the eyes the air-placed anchor goes. Stays inside vanilla reach. */
	private static final double AIR_PLACE_DISTANCE = 4.5;

	/** An anchor never gets more than this many glowstone charges (vanilla allows 4). */
	private static final int MAX_CHARGE = 1;

	/** Minimum ticks between two air placements, so the server can keep up (prevents ghost anchors). */
	private static final int AIR_PLACE_GAP_TICKS = 4;

	@Unique
	private long fastanchor$lastAirPlace = -100L;

	private static boolean fastanchor$isTarget(ItemStack stack) {
		return stack.is(Items.TOTEM_OF_UNDYING)
			|| stack.is(Items.RESPAWN_ANCHOR)
			|| stack.is(Items.GLOWSTONE);
	}

	/**
	 * The hand that vanilla would actually use for this right click, but only if it holds
	 * a totem, anchor or glowstone. Returns null otherwise (optimizer off, or any other item).
	 * Main hand wins; the offhand is only used when the main hand is empty.
	 */
	private static InteractionHand fastanchor$activeHand(LocalPlayer player) {
		if (!FastAnchorConfig.enabled) {
			return null;
		}
		ItemStack main = player.getMainHandItem();
		if (fastanchor$isTarget(main)) {
			return InteractionHand.MAIN_HAND;
		}
		if (main.isEmpty() && fastanchor$isTarget(player.getOffhandItem())) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fastanchor$onUse(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			return;
		}
		if (!FastAnchorConfig.enabled) {
			return;
		}

		HitResult hit = mc.hitResult;

		// Charge limit: never put more than MAX_CHARGE glowstone into an anchor.
		// Clicking an already charged anchor with glowstone in either hand does nothing.
		boolean glowstoneInHand = player.getMainHandItem().is(Items.GLOWSTONE)
			|| player.getOffhandItem().is(Items.GLOWSTONE);
		if (glowstoneInHand && hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(blockHit.getBlockPos());
			if (state.is(Blocks.RESPAWN_ANCHOR)
				&& state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= MAX_CHARGE) {
				ci.cancel();
				return;
			}
		}

		// Air place: only when the item being used is a respawn anchor and you point at nothing.
		// Any other item (blocks, tools, etc.) behaves exactly like vanilla.
		InteractionHand hand = fastanchor$activeHand(player);
		if (hand == null) {
			return; // not holding totem / anchor / glowstone: vanilla behaviour
		}
		if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) {
			return; // glowstone / totem: nothing to air place
		}

		// No stacking: with an anchor in hand, clicking an existing anchor does nothing,
		// so a fast double click can never place a second anchor against the first one.
		if (hit instanceof BlockHitResult anchorHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState aimed = mc.level.getBlockState(anchorHit.getBlockPos());
			if (aimed.is(Blocks.RESPAWN_ANCHOR)) {
				int charge = aimed.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
				boolean glowstoneInOffhand = player.getOffhandItem().is(Items.GLOWSTONE);

				// Pop: a charged anchor clicked with an anchor in hand is left to vanilla, which detonates it.
				// (With glowstone in the offhand vanilla would stack a second anchor instead, so that is blocked.)
				if (charge >= 1 && !glowstoneInOffhand) {
					return;
				}

				// Glowstone in the offhand and the anchor not full yet: charge it with that glowstone
				// (vanilla would try to place a second anchor here). Otherwise just ignore the click.
				if (glowstoneInOffhand && charge < MAX_CHARGE) {
					InteractionResult charged = mc.gameMode.useItemOn(player, InteractionHand.OFF_HAND, anchorHit);
					if (charged.consumesAction()) {
						player.swing(InteractionHand.OFF_HAND);
						this.rightClickDelay = FastAnchorConfig.delay;
					}
				}
				ci.cancel();
			}
			return; // otherwise aiming at a real block: vanilla behaviour
		}
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			return; // aiming at an entity: vanilla behaviour
		}

		// Anti ghost: never send air placements faster than the server can confirm them.
		long now = mc.level.getGameTime();
		if (now - this.fastanchor$lastAirPlace < AIR_PLACE_GAP_TICKS) {
			ci.cancel();
			return;
		}

		Vec3 target = player.getEyePosition().add(player.getViewVector(1.0F).scale(AIR_PLACE_DISTANCE));
		BlockPos pos = BlockPos.containing(target);

		// Air place only into empty space that has no anchor in or right next to it,
		// and where nothing (you, a player, a mob) is standing. The server refuses
		// those spots, which is what made anchors vanish after being placed.
		if (!mc.level.getBlockState(pos).isAir()
			|| !mc.level.isUnobstructed(Blocks.RESPAWN_ANCHOR.defaultBlockState(), pos, CollisionContext.of(player))) {
			ci.cancel();
			return;
		}
		for (Direction dir : Direction.values()) {
			if (mc.level.getBlockState(pos.relative(dir)).is(Blocks.RESPAWN_ANCHOR)) {
				ci.cancel();
				return;
			}
		}

		// Anti ghost: if a plain solid block touches that spot, place against its face like a normal
		// placement. Servers accept that. Only when nothing touches it do we click the empty space itself.
		BlockHitResult placeHit = null;
		for (Direction dir : Direction.values()) {
			BlockPos support = pos.relative(dir);
			BlockState supportState = mc.level.getBlockState(support);
			if (supportState.hasBlockEntity() || supportState.is(Blocks.CRAFTING_TABLE)) {
				continue; // right clicking these would open a menu instead of placing
			}
			if (supportState.isCollisionShapeFullBlock(mc.level, support)) {
				Direction face = dir.getOpposite();
				Vec3 location = Vec3.atCenterOf(support)
					.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
				placeHit = new BlockHitResult(location, face, support, false);
				break;
			}
		}
		if (placeHit == null) {
			if (!FastAnchorConfig.pureAir) {
				ci.cancel();
				return;
			}
			// Click the (empty) block position itself. Air is replaceable, so the anchor lands right there.
			placeHit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		}

		InteractionResult result = mc.gameMode.useItemOn(player, hand, placeHit);
		if (result.consumesAction()) {
			player.swing(hand);
			this.fastanchor$lastAirPlace = now;
			this.rightClickDelay = FastAnchorConfig.delay;
			ci.cancel();
		}
	}

	/**
	 * Adjustable speed: after each right click, wait only the configured number of ticks
	 * (vanilla waits 4), but only when the item in use is a totem, anchor or glowstone.
	 */
	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void fastanchor$setDelay(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		if (player != null && fastanchor$activeHand(player) != null) {
			this.rightClickDelay = Math.min(this.rightClickDelay, FastAnchorConfig.delay);
		}
	}
}
