package com.littwoanchor.mixin;

import com.littwoanchor.LitTwoAnchorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
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

	/** Results of an air placement attempt. */
	private static final int AIR_PASS = 0;   // not an air placement: leave the click to vanilla
	private static final int AIR_DROP = 1;   // nothing valid to do: swallow the click
	private static final int AIR_WAIT = 2;   // valid, but too soon after the last one: keep the click and retry
	private static final int AIR_PLACED = 3; // anchor placed

	/** A kept air click is dropped if it could not be done within this many ticks. */
	private static final int AIR_QUEUE_TIMEOUT_TICKS = 6;

	/** Furthest a support face may be from the eyes. Vanilla reach is 4.5, servers allow a little extra. */
	private static final double SUPPORT_REACH = 5.0;

	/** Minimum ticks a freshly placed anchor must exist before glowstone may be used on it. Raised automatically by ping. */
	private static final int CONFIRM_TICKS = 3;

	/** A queued glowstone charge is dropped if it could not be done within this many ticks. */
	private static final int QUEUE_TIMEOUT_TICKS = 40;

	/** Our own tick clock: counts every client tick, never stalls or jumps like the server game time can under lag. */
	@Unique
	private long littwoanchor$tick = 0L;

	/** Tick of the last anchor / glowstone click we let through. Only one such click per tick (0 delay can fire twice). */
	@Unique
	private long littwoanchor$lastUseTick = -100L;

	@Unique
	private long littwoanchor$lastAirPlace = -100L;

	/** Where the last anchor we placed is, and when. */
	@Unique
	private BlockPos littwoanchor$placedPos = null;
	@Unique
	private long littwoanchor$placedAt = -100L;

	/** Spot a normal placement is about to fill (checked after the click). */
	@Unique
	private BlockPos littwoanchor$pendingPos = null;

	/**
	 * Set after an anchor was placed. While right click stays held down no further anchor is placed
	 * (no spam placing). Released as soon as the right click button is let go.
	 */
	@Unique
	private boolean littwoanchor$placeLatch = false;

	/**
	 * Set after a glowstone was used on an anchor. While right click stays held down no more glowstone
	 * is used (no spam charging, dragging over anchors does nothing). Released when right click is let go.
	 */
	@Unique
	private boolean littwoanchor$glowLatch = false;

	/** Item that was in the main hand last tick. Switching items resets the latches, so place > glow > pop can loop while holding. */
	@Unique
	private Item littwoanchor$lastItem = null;

	/** Anchor we just sent glowstone to, and when. The server has not necessarily confirmed the charge yet. */
	@Unique
	private BlockPos littwoanchor$chargedPos = null;
	@Unique
	private long littwoanchor$chargedAt = -100L;

	/** Pop click that came too early (the charge is not confirmed by the server yet). Done as soon as it is safe. */
	@Unique
	private BlockHitResult littwoanchor$queuedPop = null;
	@Unique
	private long littwoanchor$queuedPopAt = -100L;

	/** Glowstone click that came too early (anchor not confirmed by the server yet). It is done as soon as it is safe. */
	@Unique
	private BlockHitResult littwoanchor$queuedHit = null;
	@Unique
	private long littwoanchor$queuedAt = -100L;

	/** Air-place click that came too early. Done as soon as the gap since the last air placement has passed. */
	@Unique
	private boolean littwoanchor$queuedAir = false;
	@Unique
	private long littwoanchor$queuedAirAt = -100L;

	/** Forget every latch and queued click. Used when the optimizer is switched off. */
	@Unique
	private void littwoanchor$resetState() {
		this.littwoanchor$placeLatch = false;
		this.littwoanchor$glowLatch = false;
		this.littwoanchor$queuedHit = null;
		this.littwoanchor$queuedPop = null;
		this.littwoanchor$queuedAir = false;
		this.littwoanchor$pendingPos = null;
	}

	private static boolean littwoanchor$isTarget(ItemStack stack) {
		return stack.is(Items.TOTEM_OF_UNDYING)
			|| stack.is(Items.RESPAWN_ANCHOR)
			|| stack.is(Items.GLOWSTONE);
	}

	/**
	 * The hand that vanilla would actually use for this right click, but only if it holds
	 * a totem, anchor or glowstone. Returns null otherwise (optimizer off, or any other item).
	 * Main hand wins; the offhand is only used when the main hand is empty.
	 */
	private static InteractionHand littwoanchor$activeHand(LocalPlayer player) {
		if (!LitTwoAnchorConfig.enabled) {
			return null;
		}
		ItemStack main = player.getMainHandItem();
		if (littwoanchor$isTarget(main)) {
			return InteractionHand.MAIN_HAND;
		}
		if (main.isEmpty() && littwoanchor$isTarget(player.getOffhandItem())) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}

	/** The hand holding glowstone (main first), or null. */
	private static InteractionHand littwoanchor$glowstoneHand(LocalPlayer player) {
		if (player.getMainHandItem().is(Items.GLOWSTONE)) {
			return InteractionHand.MAIN_HAND;
		}
		if (player.getOffhandItem().is(Items.GLOWSTONE)) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}

	/** Ticks the server needs to confirm a placement: at least CONFIRM_TICKS, more on high ping. */
	private static int littwoanchor$confirmTicks(Minecraft mc) {
		int ticks = CONFIRM_TICKS;
		if (mc.player != null && mc.getConnection() != null) {
			var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			if (info != null) {
				ticks = Math.max(ticks, info.getLatency() / 50 + 2);
			}
		}
		return Math.min(ticks, 12);
	}

	@Unique
	private boolean littwoanchor$isUnconfirmed(Minecraft mc, BlockPos pos) {
		return pos.equals(this.littwoanchor$placedPos)
			&& this.littwoanchor$tick - this.littwoanchor$placedAt < littwoanchor$confirmTicks(mc);
	}

	/** True while a glowstone we sent to this anchor is still waiting for the server to confirm the charge. */
	@Unique
	private boolean littwoanchor$chargePending(Minecraft mc, BlockPos pos) {
		return pos.equals(this.littwoanchor$chargedPos)
			&& this.littwoanchor$tick - this.littwoanchor$chargedAt < littwoanchor$confirmTicks(mc) + 2;
	}

	@Unique
	private void littwoanchor$markCharged(Minecraft mc, BlockPos pos) {
		this.littwoanchor$chargedPos = pos;
		this.littwoanchor$chargedAt = this.littwoanchor$tick;
	}

	/** True for a short time after we placed this anchor. Stops fast clicking from stacking anchors on the one just placed. */
	@Unique
	private boolean littwoanchor$recentlyPlaced(Minecraft mc, BlockPos pos) {
		return pos.equals(this.littwoanchor$placedPos)
			&& this.littwoanchor$tick - this.littwoanchor$placedAt < 12;
	}

	@Unique
	private void littwoanchor$queueCharge(Minecraft mc, BlockHitResult hit) {
		if (this.littwoanchor$queuedHit == null) {
			this.littwoanchor$queuedHit = hit;
			this.littwoanchor$queuedAt = this.littwoanchor$tick;
		}
	}

	/**
	 * Every tick: release the latches once right click is let go, and finish a queued
	 * glowstone charge as soon as the server has had time to confirm the anchor.
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void littwoanchor$onTick(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		this.littwoanchor$tick++;
		// Switched off: do absolutely nothing, and drop anything still waiting so it cannot fire later.
		if (!LitTwoAnchorConfig.enabled) {
			littwoanchor$resetState();
			return;
		}
		boolean useHeld = mc.options != null && mc.options.keyUse.isDown();
		if (!useHeld) {
			this.littwoanchor$placeLatch = false;
			this.littwoanchor$glowLatch = false;
		}

		// Switching items while holding right click starts a fresh action: place > glow > pop, over and over.
		if (mc.player != null) {
			Item nowItem = mc.player.getMainHandItem().getItem();
			if (nowItem != this.littwoanchor$lastItem) {
				if (this.littwoanchor$lastItem != null) {
					this.littwoanchor$placeLatch = false;
					this.littwoanchor$glowLatch = false;
					this.rightClickDelay = Math.min(this.rightClickDelay, LitTwoAnchorConfig.delay);
				}
				this.littwoanchor$lastItem = nowItem;
			}
		}

		// Finish a queued pop (glow then quickly switch and click): detonate as soon as the server shows the charge.
		BlockHitResult pop = this.littwoanchor$queuedPop;
		if (pop != null) {
			LocalPlayer popPlayer = mc.player;
			if (popPlayer == null || mc.level == null || mc.gameMode == null || !LitTwoAnchorConfig.enabled) {
				this.littwoanchor$queuedPop = null;
			} else {
				BlockPos popPos = pop.getBlockPos();
				BlockState popState = mc.level.getBlockState(popPos);
				long popNow = this.littwoanchor$tick;
				ItemStack popMain = popPlayer.getMainHandItem();
				if (popNow - this.littwoanchor$queuedPopAt > littwoanchor$confirmTicks(mc) + 2
					|| !popState.is(Blocks.RESPAWN_ANCHOR)
					|| popMain.is(Items.GLOWSTONE)
					|| popPlayer.getEyePosition().distanceToSqr(Vec3.atCenterOf(popPos)) > 6.0 * 6.0) {
					this.littwoanchor$queuedPop = null;
				} else if (popState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= 1
						&& this.littwoanchor$lastUseTick != this.littwoanchor$tick) {
					this.littwoanchor$queuedPop = null;
					this.littwoanchor$lastUseTick = this.littwoanchor$tick;
					InteractionResult popResult = mc.gameMode.useItemOn(popPlayer, InteractionHand.MAIN_HAND, pop);
					if (popResult.consumesAction()) {
						popPlayer.swing(InteractionHand.MAIN_HAND);
						this.rightClickDelay = LitTwoAnchorConfig.delay;
					}
				}
			}
		}

		// Finish a kept air-place click as soon as the gap since the last air placement has passed.
		if (this.littwoanchor$queuedAir) {
			LocalPlayer airPlayer = mc.player;
			if (airPlayer == null || mc.level == null || mc.gameMode == null
				|| this.littwoanchor$tick - this.littwoanchor$queuedAirAt > AIR_QUEUE_TIMEOUT_TICKS) {
				this.littwoanchor$queuedAir = false;
			} else {
				InteractionHand airHand = littwoanchor$activeHand(airPlayer);
				if (airHand == null || !airPlayer.getItemInHand(airHand).is(Items.RESPAWN_ANCHOR)) {
					this.littwoanchor$queuedAir = false;
				} else if (this.littwoanchor$lastUseTick != this.littwoanchor$tick
					&& littwoanchor$tryAirPlace(mc, airPlayer, airHand) != AIR_WAIT) {
					this.littwoanchor$queuedAir = false;
				}
			}
		}

		BlockHitResult queued = this.littwoanchor$queuedHit;
		if (queued == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null || !LitTwoAnchorConfig.enabled) {
			this.littwoanchor$queuedHit = null;
			return;
		}
		BlockPos pos = queued.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		long now = this.littwoanchor$tick;

		// The anchor is gone (server refused it) or already charged: nothing to do.
		if (now - this.littwoanchor$queuedAt > QUEUE_TIMEOUT_TICKS
			|| !state.is(Blocks.RESPAWN_ANCHOR)
			|| state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= MAX_CHARGE) {
			this.littwoanchor$queuedHit = null;
			return;
		}
		if (littwoanchor$isUnconfirmed(mc, pos)) {
			return; // still waiting for the server
		}
		InteractionHand hand = littwoanchor$glowstoneHand(player);
		if (hand == null || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 6.0 * 6.0) {
			this.littwoanchor$queuedHit = null;
			return;
		}
		if (this.littwoanchor$lastUseTick == this.littwoanchor$tick) {
			return; // something else was already sent this tick: wait one tick
		}
		this.littwoanchor$lastUseTick = this.littwoanchor$tick;
		this.littwoanchor$queuedHit = null;
		InteractionResult result = mc.gameMode.useItemOn(player, hand, queued);
		if (result.consumesAction()) {
			littwoanchor$markCharged(mc, pos);
			player.swing(hand);
			this.rightClickDelay = LitTwoAnchorConfig.MAX_DELAY; // glowstone always at vanilla speed
			if (useHeld) {
				this.littwoanchor$glowLatch = true;
			}
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void littwoanchor$onUse(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			return;
		}
		if (!LitTwoAnchorConfig.enabled) {
			return;
		}

		// 0 delay lets vanilla fire a click AND the held key in the same tick. The second one lands on an
		// anchor that just changed (not placed / not popped / ghost). One anchor action per tick, always.
		if (littwoanchor$activeHand(player) != null) {
			if (this.littwoanchor$lastUseTick == this.littwoanchor$tick) {
				ci.cancel();
				return;
			}
			this.littwoanchor$lastUseTick = this.littwoanchor$tick;
		}

		HitResult hit = mc.hitResult;

		// Pop right after glow: you glowed the anchor and quickly switched to another item and clicked.
		// The client may not show the charge yet, so the click would be wasted (or place an anchor on its side).
		// Queue it and detonate the moment the server confirms the charge.
		if (hit instanceof BlockHitResult popHit && hit.getType() == HitResult.Type.BLOCK) {
			ItemStack popMain = player.getMainHandItem();
			boolean glowInUse = popMain.is(Items.GLOWSTONE)
				|| (popMain.isEmpty() && player.getOffhandItem().is(Items.GLOWSTONE));
			BlockPos popPos = popHit.getBlockPos();
			if (!glowInUse && littwoanchor$chargePending(mc, popPos)) {
				BlockState popState = mc.level.getBlockState(popPos);
				if (popState.is(Blocks.RESPAWN_ANCHOR)
					&& popState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) < 1) {
					if (this.littwoanchor$queuedPop == null) {
						this.littwoanchor$queuedPop = popHit;
						this.littwoanchor$queuedPopAt = this.littwoanchor$tick;
					}
					ci.cancel();
					return;
				}
			}
		}

		// Glowstone on an anchor.
		boolean glowstoneInHand = player.getMainHandItem().is(Items.GLOWSTONE)
			|| player.getOffhandItem().is(Items.GLOWSTONE);
		if (glowstoneInHand && hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(blockHit.getBlockPos());
			if (state.is(Blocks.RESPAWN_ANCHOR)) {
				// Charge limit: never put more than MAX_CHARGE glowstone into an anchor.
				if (state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= MAX_CHARGE) {
					ci.cancel();
					return;
				}
				// No spam: one glowstone per click. Holding right click or dragging over anchors does nothing.
				if (this.littwoanchor$glowLatch) {
					ci.cancel();
					return;
				}
				// Anti ghost glowstone: do not spend glowstone on an anchor the server has not confirmed yet.
				// The click is not lost: it is queued and done the moment the anchor is confirmed.
				if (littwoanchor$isUnconfirmed(mc, blockHit.getBlockPos())) {
					littwoanchor$queueCharge(mc, blockHit);
					ci.cancel();
					return;
				}
				// Vanilla is about to use the glowstone: block further glowstone until right click is released.
				ItemStack main = player.getMainHandItem();
				if (main.is(Items.GLOWSTONE) || (main.isEmpty() && player.getOffhandItem().is(Items.GLOWSTONE))) {
					this.littwoanchor$glowLatch = true;
					littwoanchor$markCharged(mc, blockHit.getBlockPos());
				}
			}
		}

		this.littwoanchor$pendingPos = null;

		// Air place: only when the item being used is a respawn anchor and you point at nothing.
		// Any other item (blocks, tools, etc.) behaves exactly like vanilla.
		InteractionHand hand = littwoanchor$activeHand(player);
		if (hand == null) {
			return; // not holding totem / anchor / glowstone: vanilla behaviour
		}
		if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) {
			return; // glowstone / totem: nothing to air place
		}

		if (hit instanceof BlockHitResult anchorHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState aimed = mc.level.getBlockState(anchorHit.getBlockPos());
			if (aimed.is(Blocks.RESPAWN_ANCHOR)) {
				int charge = aimed.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
				boolean glowstoneInOffhand = player.getOffhandItem().is(Items.GLOWSTONE);

				// Glowstone in the offhand and the anchor not full yet: charge it with that glowstone
				// (vanilla would try to place a second anchor here).
				if (glowstoneInOffhand && charge < MAX_CHARGE) {
					InteractionResult charged = mc.gameMode.useItemOn(player, InteractionHand.OFF_HAND, anchorHit);
					if (charged.consumesAction()) {
						player.swing(InteractionHand.OFF_HAND);
						this.rightClickDelay = LitTwoAnchorConfig.MAX_DELAY; // glowstone always at vanilla speed
						this.littwoanchor$glowLatch = true;
						littwoanchor$markCharged(mc, anchorHit.getBlockPos());
					}
					ci.cancel();
					return;
				}

				// A charged anchor clicked with an anchor in hand is left to vanilla, which detonates it.
				if (charge >= 1) {
					return;
				}
				// An empty anchor: you can place on top of it or on its side like normal, but not right after
				// you placed it, otherwise spamming or holding right click builds a tower of anchors.
				if (littwoanchor$recentlyPlaced(mc, anchorHit.getBlockPos())) {
					ci.cancel();
					return;
				}
			}

			// No spam: while right click is held after a placement, do not place another anchor.
			if (this.littwoanchor$placeLatch) {
				ci.cancel();
				return;
			}
			// Normal placement against a block (this includes anchors): remember where the anchor will appear
			// so we can tell once the server has had time to confirm it.
			this.littwoanchor$pendingPos = anchorHit.getBlockPos().relative(anchorHit.getDirection());
			return; // vanilla behaviour
		}
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			return; // aiming at an entity: vanilla behaviour
		}

		// No spam: one anchor per click. Holding right click does not place more.
		if (this.littwoanchor$placeLatch) {
			ci.cancel();
			return;
		}

		int airResult = littwoanchor$tryAirPlace(mc, player, hand);
		if (airResult == AIR_PLACED || airResult == AIR_DROP) {
			ci.cancel();
		} else if (airResult == AIR_WAIT) {
			// Too soon after the last air placement: keep the click and do it the moment it is safe,
			// instead of losing it. (Sending it now is what the server rolls back.)
			if (!this.littwoanchor$queuedAir) {
				this.littwoanchor$queuedAir = true;
				this.littwoanchor$queuedAirAt = this.littwoanchor$tick;
			}
			ci.cancel();
		}
	}

	/**
	 * Try to air place an anchor next to a glowed anchor, aiming where the player looks.
	 * Only ever sends a click the server accepts: against a real full-block face that is in reach,
	 * into a spot that is empty and free of entities. Clicking pure empty space is only done when pureAir is on.
	 */
	@Unique
	private int littwoanchor$tryAirPlace(Minecraft mc, LocalPlayer player, InteractionHand hand) {
		Vec3 eye = player.getEyePosition();
		Vec3 target = eye.add(player.getViewVector(1.0F).scale(AIR_PLACE_DISTANCE));
		BlockPos pos = BlockPos.containing(target);

		// Air place only into empty space where nothing (you, a player, a mob) is standing.
		// The server refuses those spots, which is what made anchors vanish after being placed.
		if (!mc.level.getBlockState(pos).isAir()
			|| !mc.level.isUnobstructed(Blocks.RESPAWN_ANCHOR.defaultBlockState(), pos, CollisionContext.of(player))) {
			return AIR_DROP;
		}

		// Air place only works right next to a glowed (charged) anchor, like the cpvp trick.
		// Everywhere else an anchor is placed like vanilla (against blocks only).
		boolean nextToGlowed = false;
		for (Direction dir : Direction.values()) {
			BlockPos near = pos.relative(dir);
			BlockState nearState = mc.level.getBlockState(near);
			if (nearState.is(Blocks.RESPAWN_ANCHOR)
				&& (nearState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= 1
					|| littwoanchor$chargePending(mc, near))) {
				nextToGlowed = true;
				break;
			}
		}
		if (!nextToGlowed) {
			return AIR_PASS; // vanilla behaviour: nothing to place on
		}

		// Anti ghost: place against the face of a real solid block, exactly like a normal placement.
		BlockHitResult placeHit = null;
		for (Direction dir : Direction.values()) {
			BlockPos support = pos.relative(dir);
			BlockState supportState = mc.level.getBlockState(support);
			if (supportState.hasBlockEntity() || supportState.is(Blocks.CRAFTING_TABLE)) {
				continue; // right clicking these would open a menu instead of placing
			}
			if (supportState.is(Blocks.RESPAWN_ANCHOR)
				&& (littwoanchor$recentlyPlaced(mc, support) || littwoanchor$isUnconfirmed(mc, support))) {
				continue; // never build against an anchor the server may not have confirmed yet
			}
			if (supportState.is(Blocks.RESPAWN_ANCHOR)
				&& supportState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) >= 1) {
				continue; // right clicking a charged anchor would detonate it
			}
			if (!supportState.isCollisionShapeFullBlock(mc.level, support)) {
				continue;
			}
			Direction face = dir.getOpposite();
			Vec3 location = Vec3.atCenterOf(support)
				.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			if (eye.distanceToSqr(location) > SUPPORT_REACH * SUPPORT_REACH) {
				continue; // too far: the server would refuse it
			}
			placeHit = new BlockHitResult(location, face, support, false);
			break;
		}
		if (placeHit == null) {
			if (!LitTwoAnchorConfig.pureAir) {
				return AIR_PASS; // nothing solid to place against: never send a placement the server may roll back
			}
			// pureAir on: click the (empty) block position itself. Air is replaceable, so the anchor lands there.
			placeHit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		}

		// Anti ghost: never send air placements faster than the server can confirm them.
		long now = this.littwoanchor$tick;
		if (now - this.littwoanchor$lastAirPlace < Math.max(2, LitTwoAnchorConfig.delay)) {
			return AIR_WAIT;
		}

		InteractionResult result = mc.gameMode.useItemOn(player, hand, placeHit);
		if (result.consumesAction()) {
			player.swing(hand);
			this.littwoanchor$lastAirPlace = now;
			this.littwoanchor$placedPos = pos;
			this.littwoanchor$placedAt = now;
			this.littwoanchor$placeLatch = true;
			this.rightClickDelay = LitTwoAnchorConfig.delay;
			return AIR_PLACED;
		}
		return AIR_PASS;
	}

	/**
	 * Adjustable speed: after each right click, wait only the configured number of ticks
	 * (vanilla waits 4), but only when the item in use is a totem or anchor. Glowstone stays at vanilla speed.
	 */
	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void littwoanchor$setDelay(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;

		// Remember anchors placed the normal way, so glowstone waits until the server confirmed them.
		if (this.littwoanchor$pendingPos != null && mc.level != null) {
			if (mc.level.getBlockState(this.littwoanchor$pendingPos).is(Blocks.RESPAWN_ANCHOR)) {
				this.littwoanchor$placedPos = this.littwoanchor$pendingPos;
				this.littwoanchor$placedAt = this.littwoanchor$tick;
				this.littwoanchor$placeLatch = true;
			}
			this.littwoanchor$pendingPos = null;
		}

		if (player != null) {
			InteractionHand active = littwoanchor$activeHand(player);
			if (active != null && !player.getItemInHand(active).is(Items.GLOWSTONE)) {
				this.rightClickDelay = Math.min(this.rightClickDelay, LitTwoAnchorConfig.delay);
			}
		}
	}
}
