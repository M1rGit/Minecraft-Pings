package dev.chatping.config;

import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;

/**
 * Groups the two config sections as separate tabs in one Mod Menu screen, each still
 * persisted to its own file via {@link PartitioningSerializer} — general settings in
 * config/chatping/general.json, per-player colors in config/chatping/players.json.
 */
@Config(name = "chatping")
public class ChatPingConfigRoot extends PartitioningSerializer.GlobalData {

	@ConfigEntry.Category("general")
	@ConfigEntry.Gui.TransitiveObject
	public ChatPingConfig general = new ChatPingConfig();

	@ConfigEntry.Category("players")
	@ConfigEntry.Gui.TransitiveObject
	public ChatPingPlayerColors players = new ChatPingPlayerColors();
}
