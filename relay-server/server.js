// Dumb broadcast relay: forwards every message it receives to every other
// connected client, verbatim. Doesn't look at the payload at all — the mod
// decides what JSON to send and how to parse what it gets back.
const { WebSocketServer } = require('ws');

const port = process.env.PORT ? Number(process.env.PORT) : 8080;

const wss = new WebSocketServer({ port, maxPayload: 4096 });

function log(...args) {
	console.log(new Date().toISOString(), ...args);
}

let nextClientId = 1;

wss.on('connection', (ws, req) => {
	const id = nextClientId++;
	const addr = req.socket.remoteAddress;
	log(`[connect] #${id} from ${addr} (${wss.clients.size} total)`);

	// ws sends no keep-alive on its own — an idle connection looks dead to NATs/routers/
	// firewalls, which silently drop it (that's the code=1006 disconnects). Pinging keeps
	// the connection looking "active" and lets us notice and drop truly dead sockets.
	ws.isAlive = true;
	ws.on('pong', () => {
		ws.isAlive = true;
	});

	ws.on('message', (data, isBinary) => {
		// `data` always arrives as a Buffer — without passing isBinary through, ws defaults
		// to re-sending Buffers as binary frames, even though the mod sends text (JSON).
		// The Java client only handles onText, so a binary frame just gets silently dropped.
		let sent = 0;
		for (const client of wss.clients) {
			if (client !== ws && client.readyState === client.OPEN) {
				client.send(data, { binary: isBinary });
				sent++;
			}
		}
		log(`[message] #${id} -> ${sent} client(s), ${data.length} bytes`);
	});

	ws.on('close', (code) => {
		log(`[disconnect] #${id} code=${code} (${wss.clients.size - 1} remaining)`);
	});

	ws.on('error', (err) => {
		log(`[error] #${id} ${err.message}`);
	});
});

wss.on('error', (err) => {
	log(`[server error] ${err.message}`);
});

const HEARTBEAT_INTERVAL_MS = 20000;
const heartbeat = setInterval(() => {
	for (const ws of wss.clients) {
		if (ws.isAlive === false) {
			ws.terminate();
			continue;
		}
		ws.isAlive = false;
		ws.ping();
	}
}, HEARTBEAT_INTERVAL_MS);

wss.on('close', () => clearInterval(heartbeat));

log(`Chat Ping relay listening on ws://0.0.0.0:${port}`);
