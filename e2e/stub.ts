// A stand-in for ECC-Server's HTTP side, following the wire contract of the v4 server design: POST /line answers
// {"verdict":"deliver"|"hold"|"reject"}, POST /presence, GET /actions (long poll) and POST /actions/ack. A line whose text
// holds the marker word is held; every other line is delivered; a line with no speaker_uuid, or marked trusted, is
// context and delivered. An untrusted line with the fixture marker "rejectme" is rejected. A line on a channel outside the bundle's vocabulary is held and counted, as ECC-Server does;
// a line that carries a field the wire no longer has (conversation, private) is refused.
// A line sent again with the same id gets the verdict it got the first time. A speaker's line with the same text as
// their previous line on the same channel and target, within 60 s of that line's last copy, is a repeat: it gets the
// verdict of the line it repeats, and a release of that line carries the repeat's id right after the line's. Each line
// is numbered in the order its request arrived, and presence with it. POST /presence answers {boot}, GET /actions
// {boot, actions} and GET /status {status, boot}, with the stub's random boot id; POST /stub/restart starts it again
// as a restarted server would: a new boot id, no verdicts, repeats or actions, while what it received stays readable,
// each entry with its boot id. The /stub/ paths, which need no token, let the test runner queue actions, make the server slow or answer 503, restart
// it, and read what it received.

const TOKEN = process.env.ECC_TOKEN ?? "e2e-token";
const PORT = Number(process.env.PORT ?? 8790);
const MARKER = (process.env.HOLD_MARKER ?? "holdme").toLowerCase();
const POLL_MILLIS = 25_000;
const REPEAT_MILLIS = 60_000;
const CHANNELS = new Set(["chat", "guild", "msg", "mail", "me", "nick", "sign", "book", "item", "tag", "muted",
  "discord"]);

type Line = {
  id: string; channel: string; speaker_uuid?: string | null; speaker: string; shown?: string;
  target_uuid: string | null; target: string | null; text: string; trusted: boolean;
};
type Action = {
  id: string; type: "release" | "warn" | "ban" | "notice"; uuid?: string; account?: string; ids?: string[];
  kind?: string; text?: string;
};

const lines: Array<Line & { verdict: string; received: number; arrival: number; asked: number; repeats?: string;
  boot: string; responded?: number }> = [];
let boot = crypto.randomUUID();
// Each speaker's last line per channel and target: its text, the line it repeats or is, and when its last copy came.
let last = new Map<string, { text: string; root: string; at: number }>();
const unknownChannels: Record<string, number> = {};
let arrivals = 0;
// The ids of each line's repeats, in arrival order.
let repeats = new Map<string, string[]>();
let verdicts = new Map<string, string>();
const presence: any[] = [];
const acks: any[] = [];
let pending: Action[] = [];
let waiters: Array<() => void> = [];
let slowUntil = 0;
let slowMillis = 0;
let downUntil = 0;
let nextAction = 1;
let orderedReleaseAcknowledged = false;
let vanillaOrderedReleaseAcknowledged = false;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function authorized(request: Request): boolean {
  return request.headers.get("Authorization") === `Bearer ${TOKEN}`;
}

async function line(request: Request): Promise<Response> {
  const arrival = ++arrivals;
  const now = Date.now();
  const body = (await request.json()) as Line;
  const fields = ["id", "channel", "speaker_uuid", "speaker", "target_uuid", "target", "text", "trusted"];
  const missing = fields.filter((field) => !(field in body));
  const dropped = ["conversation", "private"].filter((field) => field in body);
  if (missing.length > 0 || dropped.length > 0) {
    return json({ error: `missing ${missing.join(", ")}; dropped fields sent: ${dropped.join(", ")}` }, 400);
  }
  if (Date.now() < slowUntil) {
    await Bun.sleep(slowMillis);
  }
  let verdict = verdicts.get(body.id);
  const firstRequest = verdict === undefined;
  if (verdict === undefined) {
    const key = `${body.speaker_uuid}|${body.channel}|${body.target}`;
    const previous = body.speaker_uuid ? last.get(key) : undefined;
    let repeatOf: string | undefined;
    if (!CHANNELS.has(body.channel)) {
      verdict = "hold";
      unknownChannels[body.channel] = (unknownChannels[body.channel] ?? 0) + 1;
    } else if (!body.speaker_uuid || body.trusted) {
      verdict = "deliver";
    } else if (body.text.includes("rejectme")) {
      verdict = "reject";
    } else if (previous && previous.text === body.text && now - previous.at <= REPEAT_MILLIS) {
      repeatOf = previous.root;
      verdict = verdicts.get(previous.root)!;
      repeats.set(previous.root, [...(repeats.get(previous.root) ?? []), body.id]);
    } else {
      verdict = body.text.toLowerCase().includes(MARKER) ? "hold" : "deliver";
    }
    if (body.speaker_uuid) last.set(key, { text: body.text, root: repeatOf ?? body.id, at: now });
    verdicts.set(body.id, verdict);
    lines.push({ ...body, verdict, received: now, arrival, asked: 1, repeats: repeatOf, boot });
  } else {
    const seen = lines.find((entry) => entry.id === body.id);
    if (seen) seen.asked++;
  }
  if (firstRequest && ["clean order-before-release", "clean vanilla-order-before-release"].includes(body.text)) {
    const deadline = Date.now() + 5000;
    while (!(body.text === "clean order-before-release" ? orderedReleaseAcknowledged : vanillaOrderedReleaseAcknowledged) && Date.now() < deadline) await Bun.sleep(10);
    await Bun.sleep(100);
  }
  // A release may overtake the separate /line HTTP answer. Wait for its acknowledgement to force that order.
  if (firstRequest && (body.text.includes("holdme race-before-verdict") || body.text.includes("holdme ban-before-verdict"))) {
    const release = queue({ type: body.text.includes("holdme ban-before-verdict") ? "ban" : "release",
      uuid: body.speaker_uuid!, account: body.speaker, ids: [body.id] });
    const deadline = Date.now() + 10_000;
    while (!acks.some((ack) => ack.id === release.id) && Date.now() < deadline) await Bun.sleep(10);
    if (!acks.some((ack) => ack.id === release.id)) return json({ error: "release was not acknowledged" }, 500);
    if (body.text === "holdme race-before-verdict-order") orderedReleaseAcknowledged = true;
    if (body.text === "holdme race-before-verdict-vanilla-order") vanillaOrderedReleaseAcknowledged = true;
  }
  const answeredLine = lines.find((entry) => entry.id === body.id);
  if (answeredLine) answeredLine.responded = Date.now();
  return json({ verdict });
}

async function actions(): Promise<Response> {
  if (pending.length === 0) {
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, POLL_MILLIS);
      waiters.push(() => { clearTimeout(timer); resolve(); });
    });
  }
  return json({ boot, actions: pending });
}

// A restarted server: a new boot id, and nothing of what the one before knew. Waiting polls answer at once.
function restart() {
  boot = crypto.randomUUID();
  verdicts = new Map();
  repeats = new Map();
  last = new Map();
  pending = [];
  const waiting = waiters;
  waiters = [];
  waiting.forEach((wake) => wake());
  return { boot, arrival: arrivals };
}

// A release names the held lines as the runner gives them; each line's repeats follow it.
function queue(action: Omit<Action, "id">): Action {
  const queued = { ...action, id: `a${nextAction++}` } as Action;
  if (queued.type === "release") {
    queued.ids = (queued.ids ?? []).flatMap((id) => [id, ...(repeats.get(id) ?? [])]);
  }
  pending.push(queued);
  const waiting = waiters;
  waiters = [];
  waiting.forEach((wake) => wake());
  return queued;
}

Bun.serve({
  port: PORT,
  hostname: process.env.STUB_HOST ?? "0.0.0.0",
  idleTimeout: 60,
  async fetch(request) {
    const url = new URL(request.url);
    const route = `${request.method} ${url.pathname}`;
    if (url.pathname.startsWith("/stub/")) {
      switch (route) {
        case "POST /stub/action": return json(queue(await request.json()));
        case "POST /stub/slow": {
          const { millis, seconds } = await request.json();
          slowMillis = millis;
          slowUntil = Date.now() + seconds * 1000;
          return json({ slowUntil });
        }
        case "POST /stub/down": {
          const { seconds } = await request.json();
          downUntil = Date.now() + seconds * 1000;
          return json({ downUntil });
        }
        case "POST /stub/restart": return json(restart());
        case "GET /stub/identity": return process.env.STUB_RUN_NONCE ? json({nonce:process.env.STUB_RUN_NONCE,pid:process.pid}) : json({error:"no native run identity"},404);
        case "GET /stub/lines": return json(lines);
        case "GET /stub/status": return json({ unknown_channels: unknownChannels });
        case "GET /stub/presence": return json(presence);
        case "GET /stub/acks": return json(acks);
        case "GET /stub/pending": return json(pending);
      }
      return json({ error: "no such stub path" }, 404);
    }
    if (!authorized(request)) {
      return json({ error: "token" }, 401);
    }
    if (Date.now() < downUntil) {
      return json({ error: "down" }, 503);
    }
    switch (route) {
      case "POST /line": return line(request);
      case "POST /presence": {
        presence.push({ ...(await request.json()), received: Date.now(), arrival: ++arrivals, boot });
        return json({ boot });
      }
      case "GET /actions": return actions();
      case "GET /status": return json({ status: "ready", boot });
      case "POST /actions/ack": {
        const ack = await request.json();
        acks.push({ ...ack, received: Date.now() });
        const index = pending.findIndex((action) => action.id === ack.id);
        if (index >= 0) pending.splice(index, 1);
        return json({ ok: true });
      }
    }
    return json({ error: "not found" }, 404);
  },
});

console.log(`stub ECC-Server listening on ${PORT}, holding lines with "${MARKER}"`);
