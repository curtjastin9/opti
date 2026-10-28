package com.fastanchor.mixin;

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

	/**
	 * Air place: if you are holding a respawn anchor and pointing at nothing,
	 * place it at the spot in front of you instead of doing nothing.
	 */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fastanchor$airPlace(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			return;
		}

		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			return; // aiming at a real block or entity: vanilla behaviour
		}

		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.is(Items.RESPAWN_ANCHOR)) {
				continue;
			}

			Vec3 target = player.getEyePosition().add(player.getViewVector(1.0F).scale(AIR_PLACE_DISTANCE));
			BlockPos pos = BlockPos.containing(target);

			// Click the (empty) block position itself. Air is replaceable, so the anchor lands right there.
			BlockHitResult fake = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
			InteractionResult result = mc.gameMode.useItemOn(player, hand, fake);
			if (result.consumesAction()) {
				player.swing(hand);
				this.rightClickDelay = 0;
				ci.cancel();
				return;
			}
		}
	}

	/**
	 * Fast placement: remove the 4 tick wait after every right click, so holding
	 * the button places / charges every tick.
	 */
	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void fastanchor$noCooldown(CallbackInfo ci) {
		this.rightClickDelay = 0;
	}
}
