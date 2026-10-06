package dev.chatping.ping;

import com.mojang.authlib.GameProfile;
import dev.chatping.config.ChatPingConfig;
import dev.chatping.config.ChatPingConfigRoot;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns the active ping list, places pings locally, and parses them out of incoming chat. */
public final class PingManager {
	private PingManager() {}

	public record Ping(BlockPos pos, String senderName, long createdAtMs) {}

	private static final List<Ping> ACTIVE = new ArrayList<>();
	private static long lastPingAtMs = 0L;

	private static ChatPingConfig config() {
		return AutoConfig.getConfigHolder(ChatPingConfigRoot.class).getConfig().general;
	}

	/** Pings still alive, right-sized for the renderer to iterate every frame. */
	public static List<Ping> activePings() {
		long lifetimeMs = config().markerLifetimeSeconds * 1000L;
		long now = System.currentTimeMillis();
		ACTIVE.removeIf(p -> now - p.createdAtMs() > lifetimeMs);
		return ACTIVE;
	}

	public static void registerChatListener() {
		ClientReceiveMessageEvents.ALLOW_CHAT.register(PingManager::onChatMessage);
		// Some servers (chat-formatting plugins, BedWars-style lobbies) broadcast player chat
		// as a server/system message instead of real signed player chat, so ALLOW_CHAT never
		// fires for it. Catch that path too — with no GameProfile, we fall back to guessing
		// the sender's name out of the text itself.
		ClientReceiveMessageEvents.ALLOW_GAME.register(PingManager::onGameMessage);
	}

	private static boolean onChatMessage(net.minecraft.text.Text message, net.minecraft.network.message.SignedMessage signedMessage,
			GameProfile sender, net.minecraft.network.message.MessageType.Parameters params, java.time.Instant receptionTimestamp) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return true;
		}
		// Unsigned ("Not Secure") chat — e.g. ely.by / alt-auth accounts — has no SignedMessage,
		// so fall back to the decorated Text; our pattern isn't anchored, so it still finds the
		// coordinates even with a sender name or "[Not Secure]" mixed into the same string.
		String rawContent = signedMessage != null ? signedMessage.getContent().getString() : message.getString();
		return handleIncoming(rawContent, sender, client);
	}

	private static boolean onGameMessage(net.minecraft.text.Text message, boolean overlay) {
		if (overlay) {
			return true; // action bar text, not chat
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return true;
		}
		return handleIncoming(message.getString(), null, client);
	}

	private static boolean handleIncoming(String rawContent, GameProfile sender, MinecraftClient client) {
		Optional<PingPayload> decoded = PingCodec.decode(rawContent);
		if (decoded.isEmpty()) {
			return true;
		}

		// No GameProfile (system-message path) — fall back to spotting our own name in the text.
		boolean isSelf = sender != null
				? GameProfileCompat.id(sender).equals(client.player.getUuid())
				: rawContent.contains(client.player.getName().getString());

		if (!isSelf) {
			PingPayload payload = decoded.get();
			String name = sender != null ? GameProfileCompat.name(sender) : guessSenderName(rawContent);
			ACTIVE.add(new Ping(new BlockPos(payload.x(), payload.y(), payload.z()), name, System.currentTimeMillis()));
		}

		return !config().hidePingChatLine;
	}

	/**
	 * Best-effort sender name when there's no GameProfile: scans backwards from "Ping at"
	 * for the first token that has a letter or digit in it, skipping decorative separator
	 * glyphs some servers insert right before the message (e.g. a custom arrow icon) —
	 * those render as missing-glyph junk if picked as the "name".
	 */
	private static String guessSenderName(String rawContent) {
		String before = rawContent.split("Ping at", 2)[0];
		String cleaned = before.replaceAll("[\\[\\]()»:>\\-]+", " ").trim();
		if (cleaned.isEmpty()) {
			return "???";
		}
		String[] tokens = cleaned.split("\\s+");
		for (int i = tokens.length - 1; i >= 0; i--) {
			if (tokens[i].chars().anyMatch(Character::isLetterOrDigit)) {
				return tokens[i];
			}
		}
		return "???";
	}

	public static void tryPlacePing(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}

		ChatPingConfig config = config();
		long now = System.currentTimeMillis();
		if (now - lastPingAtMs < config.pingCooldownMillis) {
			return;
		}

		HitResult hit = player.raycast(config.raycastDistance, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
			return;
		}
		lastPingAtMs = now;

		BlockPos pos = blockHit.getBlockPos();
		String selfName = player.getName().getString();

		// Shown instantly, before the server echo arrives — see onChatMessage's self-dedup.
		ACTIVE.add(new Ping(pos, selfName, now));

		client.player.networkHandler.sendChatMessage(PingCodec.encode(new PingPayload(pos.getX(), pos.getY(), pos.getZ())));
	}
}
