package dev.chatping;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** The one line that differs between versions: KeyBinding's category is now KeyBinding.Category, not a String. */
final class KeyBindings {
	private KeyBindings() {}

	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("chatping", "main"));

	static KeyBinding createPlacePingKey() {
		return new KeyBinding(
				"key.chatping.place_ping",
				InputUtil.Type.MOUSE,
				GLFW.GLFW_MOUSE_BUTTON_3,
				CATEGORY);
	}
}
