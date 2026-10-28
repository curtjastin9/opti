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

	@Override
	public void onInitializeClient() {
		FastAnchorConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("fastanchor", "main")
		);

		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_SLASH, category));
		slowerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.slower", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, category));
		fasterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.fastanchor.faster", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, category));

		ClientTickEvents.END_CLIENT_TICK.register(FastAnchorClient::onTick);
	}

	private static void onTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			FastAnchorConfig.enabled = !FastAnchorConfig.enabled;
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
		String state = FastAnchorConfig.enabled ? "ON" : "OFF";
		String text = "Fast Anchor: " + state + "  |  delay " + FastAnchorConfig.delay
			+ " tick" + (FastAnchorConfig.delay == 1 ? "" : "s")
			+ " (0 = fastest, " + FastAnchorConfig.MAX_DELAY + " = vanilla)";
		client.player.displayClientMessage(Component.literal(text), true);
	}
}
