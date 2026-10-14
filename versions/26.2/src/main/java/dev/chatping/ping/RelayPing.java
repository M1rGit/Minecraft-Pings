package dev.chatping.ping;

/** Wire shape for the WebSocket relay — plain JSON, no obfuscation needed since this never touches chat. */
public record RelayPing(int x, int y, int z, String sender) {}
