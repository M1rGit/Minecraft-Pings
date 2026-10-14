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

### Keeping it running (Linux, systemd)

Running `node server.js` in a terminal dies the moment you close the SSH
session. For a server that's meant to stay up, run it as a systemd service
instead:

```
sudo useradd -r -s /usr/sbin/nologin chatping-relay   # dedicated, unprivileged user
sudo mkdir -p /opt/chatping-relay
sudo cp -r relay-server/* /opt/chatping-relay/
cd /opt/chatping-relay && sudo npm install --omit=dev
sudo chown -R chatping-relay:chatping-relay /opt/chatping-relay
```

`/etc/systemd/system/chatping-relay.service`:

```ini
[Unit]
Description=Chat Ping relay server
After=network.target

[Service]
Type=simple
User=chatping-relay
WorkingDirectory=/opt/chatping-relay
ExecStart=/usr/bin/node server.js
Environment=PORT=8080
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl daemon-reload
sudo systemctl enable --now chatping-relay
sudo systemctl status chatping-relay    # check it's up
journalctl -u chatping-relay -f         # follow the logs
```

No systemd (e.g. a minimal container, or you just want something quick)?
`pm2 start server.js --name chatping-relay` or even a plain `tmux`/`screen`
session work fine too — the process itself keeps no state, so how you keep it
alive doesn't matter much.

### Opening the port

The relay needs one inbound TCP port reachable from your players (`8080` by
default). On a typical Linux firewall:

```
sudo ufw allow 8080/tcp          # ufw
# or
sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload   # firewalld
```

If the server is behind NAT (home connection, cloud VM's security group,
etc.) you'll also need to forward/allow that port at the network level, not
just in the host's own firewall.

### Optional: TLS (`wss://`)

The relay speaks plain `ws://`, no TLS. If you want `wss://` (e.g. because
players are on a network that blocks plain `ws://`, or you just don't want
ping coordinates crossing the internet unencrypted), put it behind a reverse
proxy that terminates TLS and forwards to the relay over plain HTTP/WS
locally — the relay code itself doesn't change. Example with nginx, assuming
you already have a cert (e.g. via certbot) for `relay.example.com`:

```nginx
server {
    listen 443 ssl;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

Then point the mod at `wss://relay.example.com` and keep the relay itself
bound to localhost only (nothing else needs to reach port 8080 directly).
