package com.littwoanchor;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

public class LitTwoAnchorClient implements ClientModInitializer {

	private static KeyMapping toggleKey;
	private static KeyMapping slowerKey;
	private static KeyMapping fasterKey;
	private static KeyMapping pureAirKey;

	private static boolean conflictChecked = false;
	private static boolean welcomed = false;

	@Override
	public void onInitializeClient() {
		LitTwoAnchorConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("littwoanchor", "main")
		);

		// "/" = optimizer on / off
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.littwoanchor.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_SLASH, category));
		// "[" slower, "]" faster
		slowerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.littwoanchor.slower", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, category));
		fasterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.littwoanchor.faster", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, category));
		// "\" = empty-space air place on / off
		pureAirKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.littwoanchor.pureair", InputConstants.Type.KEYSYM, InputConstants.KEY_BACKSLASH, category));

		ClientTickEvents.END_CLIENT_TICK.register(LitTwoAnchorClient::onTick);
	}

	private static void onTick(Minecraft client) {
		// Minecraft's own "Open Command" key is "/" too. When two keys share one button only one of them
		// gets the press, so the toggle never fired. Free the button once per launch.
		if (!conflictChecked && client.options != null) {
			conflictChecked = true;
			KeyMapping command = client.options.keyCommand;
			if (command.saveString().equals(toggleKey.saveString())
				|| command.saveString().equals(pureAirKey.saveString())) {
				command.setKey(InputConstants.UNKNOWN);
				KeyMapping.resetMapping();
				client.options.save();
			}
		}

		if (!welcomed && client.player != null) {
			welcomed = true;
			client.player.displayClientMessage(
				badge()
					.append(part(" loaded", ChatFormatting.GRAY))
					.append(sep())
					.append(part("/", ChatFormatting.WHITE)).append(part(" toggle  ", ChatFormatting.DARK_GRAY))
					.append(part("\\", ChatFormatting.WHITE)).append(part(" air  ", ChatFormatting.DARK_GRAY))
					.append(part("[ ]", ChatFormatting.WHITE)).append(part(" speed", ChatFormatting.DARK_GRAY)),
				false);
		}

		while (toggleKey.consumeClick()) {
			LitTwoAnchorConfig.enabled = !LitTwoAnchorConfig.enabled;
			LitTwoAnchorConfig.save();
			announce(client);
		}
		while (pureAirKey.consumeClick()) {
			LitTwoAnchorConfig.pureAir = !LitTwoAnchorConfig.pureAir;
			LitTwoAnchorConfig.save();
			announce(client);
		}
		while (slowerKey.consumeClick()) {
			LitTwoAnchorConfig.delay = LitTwoAnchorConfig.clamp(LitTwoAnchorConfig.delay + 1);
			LitTwoAnchorConfig.save();
			announce(client);
		}
		while (fasterKey.consumeClick()) {
			LitTwoAnchorConfig.delay = LitTwoAnchorConfig.clamp(LitTwoAnchorConfig.delay - 1);
			LitTwoAnchorConfig.save();
			announce(client);
		}
	}

	private static MutableComponent part(String text, ChatFormatting... formats) {
		return Component.literal(text).withStyle(formats);
	}

	/** Gray divider between sections. */
	private static MutableComponent sep() {
		return part("  |  ", ChatFormatting.DARK_GRAY);
	}

	/** "* LitTwo Anchor" title in bold aqua. */
	private static MutableComponent badge() {
		return part("\u2726 LitTwo Anchor", ChatFormatting.AQUA, ChatFormatting.BOLD);
	}

	/** Speed name for a delay value: 0 ticks = MAX ... vanilla delay = VANILLA. */
	private static MutableComponent speedLabel(int delay) {
		return switch (delay) {
			case 0 -> part("MAX", ChatFormatting.GREEN, ChatFormatting.BOLD);
			case 1 -> part("HIGH", ChatFormatting.GREEN);
			case 2 -> part("MED", ChatFormatting.YELLOW);
			case 3 -> part("LOW", ChatFormatting.GOLD);
			default -> part("VANILLA", ChatFormatting.RED);
		};
	}

	private static void announce(Minecraft client) {
		if (client.player == null) {
			return;
		}
		boolean on = LitTwoAnchorConfig.enabled;
		boolean air = LitTwoAnchorConfig.pureAir;

		MutableComponent line = badge()
			.append(sep())
			.append(on
				? part("\u25CF ON", ChatFormatting.GREEN, ChatFormatting.BOLD)
				: part("\u25CF OFF", ChatFormatting.RED, ChatFormatting.BOLD));

		if (on) {
			line.append(sep())
				.append(part("Speed ", ChatFormatting.GRAY))
				.append(speedLabel(LitTwoAnchorConfig.delay))
				.append(sep())
				.append(part("Air ", ChatFormatting.GRAY))
				.append(air
					? part("\u25CF ON", ChatFormatting.GREEN)
					: part("\u25CB OFF", ChatFormatting.DARK_GRAY));
		}
		// Action bar only: one clean line per press.
		client.player.displayClientMessage(line, true);
	}
}
