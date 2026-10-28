package com.fastanchor;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class FastAnchorClient implements ClientModInitializer {

	private static KeyMapping toggleKey;
	private static KeyMapping slowerKey;
	private static KeyMapping fasterKey;
	private static KeyMapping pureAirKey;

	private static boolean conflictChecked = false;
	private static boolean welcomed = false;

	@Override
	public void onInitializeClient() {
		FastAnchorConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("fastanchor", "main")
		);

		// "/" = optimizer on / off
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_SLASH, category));
		// "[" slower, "]" faster
		slowerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.slower", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, category));
		fasterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.faster", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, category));
		// "\" = empty-space air place on / off
		pureAirKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.pureair", InputConstants.Type.KEYSYM, InputConstants.KEY_BACKSLASH, category));

		ClientTickEvents.END_CLIENT_TICK.register(FastAnchorClient::onTick);
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
				"[LitTwoAnchorOptimizer] loaded.  / = on/off   \\ = empty-space air place   [ ] = speed"), false);
		}

		while (toggleKey.consumeClick()) {
			FastAnchorConfig.enabled = !FastAnchorConfig.enabled;
			FastAnchorConfig.save();
			announce(client);
		}
		while (pureAirKey.consumeClick()) {
			FastAnchorConfig.pureAir = !FastAnchorConfig.pureAir;
			FastAnchorConfig.save();
			announce(client);
		}
		while (slowerKey.consumeClick()) {
			FastAnchorConfig.delay = FastAnchorConfig.clamp(FastAnchorConfig.delay + 1);
			FastAnchorConfig.save();
			announce(client);
		}
		while (fasterKey.consumeClick()) {
			FastAnchorConfig.delay = FastAnchorConfig.clamp(FastAnchorConfig.delay - 1);
			FastAnchorConfig.save();
			announce(client);
		}
	}

	private static void announce(Minecraft client) {
		if (client.player == null) {
			return;
		}
		String text = "[LitTwoAnchorOptimizer] " + (FastAnchorConfig.enabled ? "ON" : "OFF")
			+ "  |  delay " + FastAnchorConfig.delay
			+ " tick" + (FastAnchorConfig.delay == 1 ? "" : "s")
			+ " (0 = fastest, " + FastAnchorConfig.MAX_DELAY + " = vanilla)"
			+ "  |  empty-space air place " + (FastAnchorConfig.pureAir ? "ON" : "OFF");
		client.player.displayClientMessage(Component.literal(text), false);
		client.player.displayClientMessage(Component.literal(text), true);
	}
}
