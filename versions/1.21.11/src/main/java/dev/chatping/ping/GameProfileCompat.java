package dev.chatping.ping;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/** authlib 7.x (bundled with this MC version): GameProfile is now a record — id()/name(), no "get". */
final class GameProfileCompat {
	private GameProfileCompat() {}

	static UUID id(GameProfile profile) {
		return profile.id();
	}

	static String name(GameProfile profile) {
		return profile.name();
	}
}
