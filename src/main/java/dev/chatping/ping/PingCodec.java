package dev.chatping.ping;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain, human-readable chat message carrying a ping's coordinates — no invisible
 * Unicode tricks, since those got flagged as suspicious by at least one server's
 * anti-cheat/anti-spam. Coordinates are scaled by {@link #SCALE} so the raw numbers
 * don't immediately read as "coordinates near me" — not real security, just avoids an
 * obvious pattern for a casual onlooker.
 */
public final class PingCodec {
	private PingCodec() {}

	private static final int SCALE = 10;
	// Not anchored, and run after stripping §-color codes: some servers wrap or recolor
	// chat content (rank prefixes, chat-formatting plugins) before it reaches other clients.
	private static final Pattern PATTERN = Pattern.compile("Ping at (-?\\d+), (-?\\d+), (-?\\d+)");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	public static String encode(PingPayload payload) {
		return "Ping at " + (payload.x() * SCALE) + ", " + (payload.y() * SCALE) + ", " + (payload.z() * SCALE);
	}

	public static Optional<PingPayload> decode(String chatContent) {
		Matcher matcher = PATTERN.matcher(COLOR_CODE.matcher(chatContent).replaceAll(""));
		if (!matcher.find()) {
			return Optional.empty();
		}
		int x = Integer.parseInt(matcher.group(1)) / SCALE;
		int y = Integer.parseInt(matcher.group(2)) / SCALE;
		int z = Integer.parseInt(matcher.group(3)) / SCALE;
		return Optional.of(new PingPayload(x, y, z));
	}
}
