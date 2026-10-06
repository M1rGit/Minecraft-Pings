package dev.chatping.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "chatping_general")
public class ChatPingConfig implements ConfigData {

	@ConfigEntry.BoundedDiscrete(min = 8, max = 256)
	public int raycastDistance = 128;

	@ConfigEntry.BoundedDiscrete(min = 1, max = 60)
	public int markerLifetimeSeconds = 12;

	@ConfigEntry.BoundedDiscrete(min = 0, max = 5000)
	public int pingCooldownMillis = 1000;

	public boolean hidePingChatLine = true;

	public boolean showDistanceInLabel = true;

	@ConfigEntry.ColorPicker
	public int markerColor = 0xFFD140;

	@ConfigEntry.BoundedDiscrete(min = 1, max = 100)
	public int dotSizePercent = 20;

	@ConfigEntry.BoundedDiscrete(min = 50, max = 300)
	public int labelScalePercent = 100;

	@ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
	public RenderMode renderMode = RenderMode.BEAM_AND_DOT;

	@ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
	public TransportMode transportMode = TransportMode.CHAT_ONLY;

	public String relayServerUrl = "ws://localhost:8080";

	public enum RenderMode {
		DOT_ONLY,
		BEAM_AND_DOT
	}

	public enum TransportMode {
		/** Default, unchanged behaviour — send through vanilla chat, encoded per {@link dev.chatping.ping.PingCodec}. */
		CHAT_ONLY,
		/** Only the WebSocket relay — never touches chat, so it can't trip a server's spam/mute filter. */
		RELAY_ONLY,
		/** Relay when connected, chat if it isn't — trades the mute-proof guarantee for "always gets there somehow". */
		RELAY_PREFERRED_CHAT_FALLBACK
	}
}
