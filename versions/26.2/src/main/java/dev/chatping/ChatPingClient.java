package dev.chatping;

import dev.chatping.config.ChatPingConfigRoot;
import dev.chatping.ping.PingManager;
import dev.chatping.render.PingRenderer;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public class ChatPingClient implements ClientModInitializer {

	private KeyMapping placePingKey;

	@Override
	public void onInitializeClient() {
		AutoConfig.register(ChatPingConfigRoot.class, PartitioningSerializer.wrap(GsonConfigSerializer::new));

		placePingKey = KeyMappingHelper.registerKeyMapping(KeyBindings.createPlacePingKey());

		PingManager.registerChatListener();
		PingRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (placePingKey.consumeClick()) {
				PingManager.tryPlacePing(client);
			}
			PingManager.maintainRelayConnection();
		});
	}
}
