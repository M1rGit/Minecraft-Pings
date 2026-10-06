package dev.chatping.ping;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/** authlib 6.x (bundled with this MC version): GameProfile is a plain class with getters. */
final class GameProfileCompat {
	private GameProfileCompat() {}

	static UUID id(GameProfile profile) {
		return profile.getId();
	}

	static String name(GameProfile profile) {
		return profile.getName();
	}
}
