package dev.chatping;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** The one line that differs between versions: KeyMapping's category is KeyMapping.Category, not a String. */
final class KeyBindings {
	private KeyBindings() {}

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chatping", "main"));

	static KeyMapping createPlacePingKey() {
		return new KeyMapping(
				"key.chatping.place_ping",
				InputConstants.Type.MOUSE,
				GLFW.GLFW_MOUSE_BUTTON_3,
				CATEGORY);
	}
}
