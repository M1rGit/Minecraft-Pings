package dev.chatping.ping;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Thin wrapper around the JDK's built-in {@link WebSocket} client (no extra dependency
 * needed — java.net.http has shipped this since Java 11). Connects to the relay server
 * from {@code relay-server/}, a dumb broadcaster: whatever we send, every other
 * connected client receives verbatim, and vice versa.
 */
public final class RelayClient {
	private RelayClient() {}

	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
	private static final long RECONNECT_COOLDOWN_MS = 5000;

	private static volatile WebSocket socket;
	private static volatile String connectedUrl;
	private static volatile long lastConnectAttemptMs = 0L;

	/** Cheap to call often — no-ops unless the URL changed or the connection dropped. */
	public static void ensureConnected(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		WebSocket current = socket;
		if (current != null && url.equals(connectedUrl) && !current.isOutputClosed()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastConnectAttemptMs < RECONNECT_COOLDOWN_MS) {
			return;
		}
		lastConnectAttemptMs = now;
		connect(url);
	}

	private static synchronized void connect(String url) {
		closeQuietly();
		connectedUrl = url;
		try {
			HTTP_CLIENT.newWebSocketBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.buildAsync(URI.create(url), new RelayListener())
					.thenAccept(ws -> socket = ws)
					.exceptionally(ex -> {
						socket = null;
						return null;
					});
		} catch (RuntimeException e) {
			// Malformed URL, unresolvable host, etc. — just stay disconnected until the
			// next ensureConnected() call retries (rate-limited above).
			socket = null;
		}
	}

	public static boolean isConnected() {
		WebSocket s = socket;
		return s != null && !s.isOutputClosed();
	}

	public static void send(String json) {
		WebSocket s = socket;
		if (s != null) {
			s.sendText(json, true);
		}
	}

	private static void closeQuietly() {
		WebSocket s = socket;
		socket = null;
		if (s != null) {
			s.abort();
		}
	}

	private static final class RelayListener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			webSocket.request(1);
			if (last) {
				String message = buffer.toString();
				buffer.setLength(0);
				// onText runs on the HttpClient's own thread, not the render thread — touching
				// PingManager's ping list from here raced with PingRenderer iterating it and
				// crashed with ConcurrentModificationException. Hop to the client thread first.
				MinecraftClient.getInstance().execute(() -> PingManager.handleRelayMessage(message));
			}
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			socket = null;
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			socket = null;
		}
	}
}
