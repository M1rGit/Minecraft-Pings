package dev.chatping;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** The one line that differs between versions: KeyBinding's category parameter type changed. */
final class KeyBindings {
	private KeyBindings() {}

	static KeyBinding createPlacePingKey() {
		return new KeyBinding(
				"key.chatping.place_ping",
				InputUtil.Type.MOUSE,
				GLFW.GLFW_MOUSE_BUTTON_3,
				"category.chatping");
	}
}
