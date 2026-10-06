package dev.chatping;

import dev.chatping.config.ChatPingConfigRoot;
import dev.chatping.ping.PingManager;
import dev.chatping.render.PingRenderer;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;

public class ChatPingClient implements ClientModInitializer {

	private KeyBinding placePingKey;

	@Override
	public void onInitializeClient() {
		AutoConfig.register(ChatPingConfigRoot.class, PartitioningSerializer.wrap(GsonConfigSerializer::new));

		placePingKey = KeyBindingHelper.registerKeyBinding(KeyBindings.createPlacePingKey());

		PingManager.registerChatListener();
		PingRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (placePingKey.wasPressed()) {
				PingManager.tryPlacePing(client);
			}
		});
	}
}
