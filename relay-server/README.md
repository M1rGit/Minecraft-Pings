# Chat Ping relay server

A dumb WebSocket broadcast relay. Any message a client sends gets forwarded,
unchanged, to every *other* connected client. It never looks at the payload —
the mod decides the JSON shape and what to do with it. No rooms, no auth, no
persistence: whoever has the URL sees every ping.

## Run it

```
npm install
node server.js
```

Listens on port `8080` by default; override with `PORT=9000 node server.js`.

## Point the mod at it

In the mod's config, set transport mode to "Relay only" or "Relay + chat
fallback" and set the relay URL to `ws://<this-server-ip>:<port>` (or
`wss://...` if you put it behind a TLS-terminating reverse proxy — the relay
itself doesn't do TLS).

## Why this exists

Some servers mute/flag accounts for sending frequent short chat messages,
which breaks the normal ping transport (plain chat). This relay is a private,
self-hosted side channel: nothing goes through the Minecraft server at all.

## Deploying

Needs Node.js (18+) and one open TCP port. No database, no state beyond the
current list of open sockets — restarting it just disconnects everyone, who
then reconnect automatically from the mod side.
