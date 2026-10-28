package com.littwoanchor;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
			client.player.displayClientMessage(Component.literal(
				"[LitTwoAnchor] loaded.  / = on/off   \\ = empty-space air place   [ ] = speed"), false);
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

	private static void announce(Minecraft client) {
		if (client.player == null) {
			return;
		}
		String text = "[LitTwoAnchor] " + (LitTwoAnchorConfig.enabled ? "ON" : "OFF")
			+ "  |  delay " + LitTwoAnchorConfig.delay
			+ " tick" + (LitTwoAnchorConfig.delay == 1 ? "" : "s")
			+ " (0 = fastest, " + LitTwoAnchorConfig.MAX_DELAY + " = vanilla)"
			+ "  |  empty-space air place " + (LitTwoAnchorConfig.pureAir ? "ON" : "OFF");
		client.player.displayClientMessage(Component.literal(text), false);
		client.player.displayClientMessage(Component.literal(text), true);
	}
}
