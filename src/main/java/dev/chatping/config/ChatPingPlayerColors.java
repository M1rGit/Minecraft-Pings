package dev.chatping.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import java.util.ArrayList;
import java.util.List;

/** Per-player ping colors, overriding {@link ChatPingConfig#markerColor} by nickname. */
@Config(name = "chatping_players")
public class ChatPingPlayerColors implements ConfigData {

	// Plain List<Entry> with no Gui annotation — AutoConfig auto-detects this as a complex
	// list and renders proper add/remove controls; CollapsibleObject is only for a single
	// nested object and collapses the whole list into one, hiding the add button.
	public List<Entry> entries = new ArrayList<>();

	public static class Entry {
		public String playerName = "";

		@ConfigEntry.ColorPicker
		public int color = 0xFFD140;

		public Entry() {}

		public Entry(String playerName, int color) {
			this.playerName = playerName;
			this.color = color;
		}
	}

	public int colorFor(String playerName, int fallback) {
		for (Entry entry : entries) {
			if (entry.playerName.equalsIgnoreCase(playerName)) {
				return entry.color;
			}
		}
		return fallback;
	}
}
