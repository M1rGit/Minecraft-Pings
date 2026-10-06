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

	public enum RenderMode {
		DOT_ONLY,
		BEAM_AND_DOT
	}
}
