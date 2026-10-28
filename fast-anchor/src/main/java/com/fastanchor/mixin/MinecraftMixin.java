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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

	/**
	 * Air place: only when the item being used is a respawn anchor and you point at nothing.
	 * Any other item (blocks, tools, etc.) behaves exactly like vanilla.
	 */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fastanchor$airPlace(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			return;
		}

		InteractionHand hand = fastanchor$activeHand(player);
		if (hand == null) {
			return; // not holding totem / anchor / glowstone: vanilla behaviour
		}
		if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) {
			return; // glowstone / totem: nothing to air place
		}

		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			return; // aiming at a real block or entity: vanilla behaviour
		}

		Vec3 target = player.getEyePosition().add(player.getViewVector(1.0F).scale(AIR_PLACE_DISTANCE));
		BlockPos pos = BlockPos.containing(target);

		// Click the (empty) block position itself. Air is replaceable, so the anchor lands right there.
		BlockHitResult fake = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		InteractionResult result = mc.gameMode.useItemOn(player, hand, fake);
		if (result.consumesAction()) {
			player.swing(hand);
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
