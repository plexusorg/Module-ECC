// End-to-end test of chat safety on a real Paper server, against the stub ECC-Server or the real one.
//
//   cd e2e && bun install && bun run.ts          the stub (stub.ts): the plugin alone
//   cd e2e && bun install && bun run.ts real     the real ECC-Server (real.ts)
//
// Builds the module against the Plex checkout in PLEX_DIR (default ../Plex, beside this one; built with
// gradlew :server:shadowJar), fetches the plugins it runs with into e2e/work/jars (ViaVersion, ViaBackwards and
// LuckPerms from Modrinth, Vault from GitHub, EssentialsX from its CI, DiscordSRV from its Maven repository), takes
// Plex's jar from PLEX_DIR and the guild module's from GUILDS_DIR (default ../Module-Guilds, built against PLEX_DIR),
// then starts Podman containers on one network: the stub, or ECC-Server and its fake Bedrock, and Paper 26.2
// (itzg/minecraft-server, 2 GB) in offline mode. DiscordSRV has no bot token, so it disables itself after the module
// hooked it. Four mineflayer bots join through ViaBackwards: Alice
// writes, Bob listens, Carol holds the bypass and alert permissions, Dave is in the trusted group. Each scenario passes
// or fails by what another bot received, or by what the server holds where no other player can look (a book, an
// item). With the stub, the test queues ECC-Server's actions itself; with the real server, a line holding a word of
// the private fixture's holdwords.txt is held, and the fake judge rules on it after the bundle's waiting time: `hold` for "holdme
// stays", `ban` for "holdme banned", `nothing` for everything else. ONLY=<regex> runs the scenarios whose names match;
// KEEP=1 leaves the containers running.
// The containers, the network and ECC-Server's volume are removed at the end. The Paper server folder is the Podman
// volume ecc-e2e-data, kept for the next run (podman volume rm ecc-e2e-data to start from nothing); e2e/work keeps the
// jars, server.log and <mode>.json, what the stub received or what ECC-Server recorded.

import mineflayer, { type Bot } from "mineflayer";
import { Vec3 } from "vec3";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { RealServer, SUBNET } from "./real.ts";

const here = import.meta.dir;
const repo = resolve(here, "..");
const work = join(here, "work");
const jars = join(work, "jars");
const data = join(work, "data");
const NETWORK = "ecc-e2e";
const VOLUME = "ecc-e2e-data";
const FLAT = JSON.stringify({ layers: [{ block: "minecraft:bedrock", height: 1 }, { block: "minecraft:dirt", height: 2 },
  { block: "minecraft:grass_block", height: 1 }], biome: "minecraft:plains" });
const STUB = "ecc-e2e-stub";
const SERVER = "ecc-e2e-mc";
const GAME_PORT = 25599;
const STUB_PORT = 8799;
const STUB_URL = `http://127.0.0.1:${STUB_PORT}`;
const MODE = process.argv[2] === "real" ? "real" : "stub";
// DISCORDSRV=0 runs without DiscordSRV.
const DISCORDSRV = process.env.DISCORDSRV !== "0";
const TRUSTED_GROUP = "rigtrusted";
const ALLOWED_LINKS = ["rigchat.example", "talk.rigvoice.example/j/Abc123"];
const MC_VERSION = "26.2";
const BOT_VERSION = "26.1";
const NOTICE = "Your message was not sent. It is being checked.";
const WARNING = "Your messages were not sent.";

// ---------------------------------------------------------------- setup

async function run(command: string[],
  options: { cwd?: string; check?: boolean; env?: Record<string, string> } = {}) {
  const process = Bun.spawn(command, { cwd: options.cwd ?? repo, stdout: "pipe", stderr: "pipe",
    env: options.env ? { ...Bun.env, ...options.env } : undefined });
  const [out, err] = await Promise.all([new Response(process.stdout).text(), new Response(process.stderr).text()]);
  const code = await process.exited;
  if (code !== 0 && options.check !== false) {
    throw new Error(`${command.join(" ")} exited ${code}\n${out}\n${err}`);
  }
  return out + err;
}

async function download(url: string, file: string) {
  const response = await fetch(url, { headers: { "User-Agent": "module-ecc-e2e" } });
  if (!response.ok) throw new Error(`GET ${url}: ${response.status}`);
  writeFileSync(file, Buffer.from(await response.arrayBuffer()));
}

async function modrinth(project: string): Promise<string> {
  const query = `game_versions=${encodeURIComponent(JSON.stringify([MC_VERSION]))}`
    + `&loaders=${encodeURIComponent(JSON.stringify(["paper"]))}`;
  const response = await fetch(`https://api.modrinth.com/v2/project/${project}/version?${query}`,
    { headers: { "User-Agent": "module-ecc-e2e" } });
  const versions = (await response.json()) as Array<{ files: Array<{ url: string; primary: boolean }> }>;
  if (versions.length === 0) throw new Error(`Modrinth has no ${project} for ${MC_VERSION}`);
  return (versions[0].files.find((file) => file.primary) ?? versions[0].files[0]).url;
}

async function essentialsUrl(): Promise<string> {
  const base = "https://ci.ender.zone/job/EssentialsX/lastSuccessfulBuild/";
  const build = (await (await fetch(`${base}api/json?tree=artifacts[relativePath,fileName]`)).json()) as
    { artifacts: Array<{ relativePath: string; fileName: string }> };
  const jar = build.artifacts.find((artifact) => /^EssentialsX-[\d.]+/.test(artifact.fileName));
  if (!jar) throw new Error("EssentialsX CI has no EssentialsX jar");
  return `${base}artifact/${jar.relativePath}`;
}

// Each plugin once; delete e2e/work/jars to fetch them again.
async function fetchPlugins() {
  mkdirSync(jars, { recursive: true });
  const wanted: Record<string, () => Promise<string>> = {
    "ViaVersion.jar": () => modrinth("viaversion"),
    "ViaBackwards.jar": () => modrinth("viabackwards"),
    "LuckPerms.jar": () => modrinth("luckperms"),
    "Vault.jar": async () => "https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar",
    "EssentialsX.jar": essentialsUrl,
    "DiscordSRV.jar": async () =>
      "https://nexus.scarsz.me/content/groups/public/com/discordsrv/discordsrv/1.30.5/discordsrv-1.30.5.jar",
  };
  for (const [file, url] of Object.entries(wanted)) {
    if (!existsSync(join(jars, file))) {
      const source = await url();
      console.log(`fetching ${file} from ${source}`);
      await download(source, join(jars, file));
    }
  }
}

const PLEX_DIR = resolve(process.env.PLEX_DIR ?? resolve(repo, "..", "Plex"));
const GUILDS_JAR = join(resolve(process.env.GUILDS_DIR ?? resolve(repo, "..", "Module-Guilds")), "build", "libs",
  "Module-Guilds.jar");

function plexJar(): string {
  const folder = join(PLEX_DIR, "server", "build", "libs");
  const jar = existsSync(folder) ? readdirSync(folder).find((name) => /^Plex-.*\.jar$/.test(name)) : undefined;
  if (!jar) {
    throw new Error(`No Plex jar in ${folder}; build Plex there (gradlew :server:shadowJar) or set PLEX_DIR`);
  }
  return join(folder, jar);
}

function moduleConfig(host: string, token: string): string {
  return `server:
  enabled: false
  wait_time: 5
  recent_join_minutes: 120
feed:
  interval_seconds: 10
  digest_threshold: 5
log:
  size: 50
chat_safety:
  enabled: true
  model_host: "${host}"
  token: "${token}"
  verdict_timeout_ms: 1000
  trusted_groups: ["${TRUSTED_GROUP}"]
  allowed_links: [${ALLOWED_LINKS.map((link) => `"${link}"`).join(", ")}]
  notice: "<gray>${NOTICE}"
  warning: "<red>${WARNING} Do not ask for or give contact details."
  item_name_checked: "<gray>Your item name was checked. Take the item again to rename it."
`;
}

// The plugins folder as the server gets it. Paper cannot write its files on a Windows folder mounted into the
// container, so the server folder is the volume ecc-e2e-data and this folder is copied into it before the start.
function preparePlugins(host: string, token: string) {
  const plugins = join(data, "plugins");
  rmSync(data, { recursive: true, force: true });
  const modules = join(plugins, "Plex", "modules");
  mkdirSync(join(modules, "Module-ECC"), { recursive: true });
  for (const file of readdirSync(jars)) {
    if (DISCORDSRV || file !== "DiscordSRV.jar") copyFileSync(join(jars, file), join(plugins, file));
  }
  copyFileSync(plexJar(), join(plugins, "Plex.jar"));
  copyFileSync(join(repo, "build", "libs", "Module-ECC.jar"), join(modules, "Module-ECC.jar"));
  if (!existsSync(GUILDS_JAR)) {
    throw new Error(`No ${GUILDS_JAR}; build the guild module against PLEX_DIR or set GUILDS_DIR`);
  }
  copyFileSync(GUILDS_JAR, join(modules, "Module-Guilds.jar"));
  writeFileSync(join(modules, "Module-ECC", "config.yml"), moduleConfig(host, token));
  // The bots all join from one address, faster than Bukkit's connection throttle allows.
  writeFileSync(join(data, "bukkit.yml"), "settings:\n  connection-throttle: -1\n");
}
async function podman(...args: string[]) {
  return run(["podman", ...args]);
}

async function removeContainers() {
  await run(["podman", "rm", "-f", "-t", "5", SERVER, STUB], { check: false });
  await real.remove();
  await run(["podman", "network", "rm", NETWORK], { check: false });
}

// The stub or the real ECC-Server, then Paper with the module's config naming it and holding its token.
async function startContainers() {
  await removeContainers();
  await podman("network", "create", "--subnet", SUBNET, NETWORK);
  let host = `http://${STUB}:8790`;
  let token = "e2e-token";
  if (MODE === "stub") {
    await podman("run", "-d", "--name", STUB, "--network", NETWORK, "--memory", "256m",
      "-p", `127.0.0.1:${STUB_PORT}:8790`, "-v", `${here}:/e2e:ro`, "docker.io/oven/bun:1.4.4-alpine",
      "bun", "/e2e/stub.ts");
  } else {
    await real.start();
    host = `http://${real.host}:8790`;
    token = real.token;
  }
  preparePlugins(host, token);
  // Bans, guilds, nicknames, permissions and inventories of an earlier run go; the world, Paper and the libraries Plex
  // downloads stay.
  await podman("run", "--rm", "-v", `${VOLUME}:/data`, "docker.io/library/alpine:3.24", "rm", "-rf",
    "/data/plugins/Plex/indefbans.yml", "/data/plugins/Plex/database.db", "/data/plugins/Essentials/userdata",
    "/data/plugins/Plex/modules", "/data/world/playerdata", "/data/plugins/LuckPerms", "/data/plugins/DiscordSRV",
    "/data/plugins/DiscordSRV.jar");
  await podman("create", "--name", SERVER, "--network", NETWORK, "--memory", "2g",
    "-p", `127.0.0.1:${GAME_PORT}:25565`, "-v", `${VOLUME}:/data`,
    "-e", "EULA=TRUE", "-e", "TYPE=PAPER", "-e", `VERSION=${MC_VERSION}`, "-e", "MEMORY=1536M",
    "-e", "ONLINE_MODE=FALSE", "-e", "ENFORCE_SECURE_PROFILE=FALSE", "-e", "LEVEL_TYPE=minecraft:flat",
    "-e", `GENERATOR_SETTINGS=${FLAT}`, "-e", "SPAWN_PROTECTION=0", "-e", "MODE=creative",
    "-e", "DIFFICULTY=peaceful", "-e", "VIEW_DISTANCE=4", "-e", "SIMULATION_DISTANCE=4",
    "-e", "GENERATE_STRUCTURES=FALSE", "-e", "SPAWN_MONSTERS=FALSE", "-e", "RCON_PASSWORD=e2e",
    "docker.io/itzg/minecraft-server:latest");
  await podman("cp", join(data, "plugins"), `${SERVER}:/data/`);
  await podman("cp", join(data, "bukkit.yml"), `${SERVER}:/data/bukkit.yml`);
  await podman("start", SERVER);
  const started = Date.now();
  while (Date.now() - started < 600_000) {
    const logs = await run(["podman", "logs", SERVER], { check: false });
    if (/Done \([\d.,]+s\)!/.test(logs)) return logs;
    if (/Exited|Error: Unable to access jarfile/.test(await run(["podman", "ps", "-a", "--filter", `name=${SERVER}`,
      "--format", "{{.Status}}"], { check: false }))) {
      throw new Error(`the server stopped while starting:\n${logs.slice(-4000)}`);
    }
    await Bun.sleep(2000);
  }
  throw new Error("the server did not start in 10 minutes");
}

async function rcon(command: string) {
  return run(["podman", "exec", SERVER, "rcon-cli", command]);
}

// ---------------------------------------------------------------- bots and the stub

type Heard = { at: number; text: string };

class Player {
  readonly heard: Heard[] = [];
  // Chat packets as received, which carry the sender's display name that the parsed message may leave out.
  readonly packets: Heard[] = [];
  readonly uuid: string;
  kicked: string | null = null;
  private constructor(readonly name: string, readonly bot: Bot) {
    this.uuid = offlineUuid(name);
    bot.on("message", (message) => this.heard.push({ at: Date.now(), text: message.toString() }));
    bot._client.on("packet", (packet: unknown, meta: { name: string }) => {
      if (/chat/.test(meta.name)) this.packets.push({ at: Date.now(), text: JSON.stringify(packet) });
    });
    bot.on("kicked", (reason) => { this.kicked = typeof reason === "string" ? reason : JSON.stringify(reason); });
  }

  static join(name: string): Promise<Player> {
    const bot = mineflayer.createBot({ host: "127.0.0.1", port: GAME_PORT, username: name, version: BOT_VERSION,
      auth: "offline", hideErrors: true });
    const player = new Player(name, bot);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${name} did not spawn`)), 60_000);
      bot.once("spawn", () => { clearTimeout(timer); resolve(player); });
      bot.once("kicked", (reason) => { clearTimeout(timer); reject(new Error(`${name} kicked: ${reason}`)); });
      bot.once("error", (error) => { clearTimeout(timer); reject(error); });
    });
  }

  say(text: string) {
    this.bot.chat(text);
  }

  // The first line heard since `since` that contains `text`, or null after `millis`.
  async hears(text: string, millis: number, since = 0): Promise<Heard | null> {
    const until = Date.now() + millis;
    while (Date.now() < until) {
      const found = this.heard.find((line) => line.at >= since && line.text.includes(text));
      if (found) return found;
      await Bun.sleep(100);
    }
    return this.heard.find((line) => line.at >= since && line.text.includes(text)) ?? null;
  }
}

function offlineUuid(name: string): string {
  const hash = createHash("md5").update(`OfflinePlayer:${name}`).digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function stub<T = any>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${STUB_URL}${path}`, body === undefined ? undefined
    : { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json" } });
  return (await response.json()) as T;
}

// The id of the line the stub received from this speaker with this text, waiting for it to arrive.
async function lineId(speaker: Player, text: string): Promise<string> {
  for (let i = 0; i < 50; i++) {
    const lines = await stub<any[]>("/stub/lines");
    const line = lines.find((entry) => entry.speaker_uuid === speaker.uuid && entry.text.includes(text));
    if (line) return line.id;
    await Bun.sleep(100);
  }
  throw new Error(`the stub never received "${text}" from ${speaker.name}`);
}

// The line ECC-Server received with this raw text, as the stub got it or as the real server recorded it; null after
// `tries` reads 200 ms apart.
type Received = { channel: string; uuid: string; shown?: string; trusted: boolean; speaker?: string;
  target: string | null; copies: number };
async function received(text: string, tries = 50): Promise<Received | null> {
  for (let i = 0; i < tries; i++) {
    if (MODE === "stub") {
      const lines = (await stub<any[]>("/stub/lines")).filter((entry) => entry.text === text);
      const line = lines[0];
      if (line) return { channel: line.channel, uuid: line.speaker_uuid, shown: line.shown, trusted: line.trusted,
        speaker: line.speaker, target: line.target, copies: lines.length };
    } else {
      const lines = (await real.record()).filter((entry) => entry.type === "line" && entry.raw === text);
      const line = lines[0];
      if (line) return { channel: line.channel, uuid: line.account, shown: line.raw_name, trusted: line.context === true,
        target: line.target ?? null, copies: lines.length };
    }
    await Bun.sleep(200);
  }
  return null;
}

// A shown name prepared as ECC-Server prepares it: colour codes, the client's [Not Secure] mark, whitespace.
const prepared = (name: string) => name.replace(/§./gs, "").replace(/&#[0-9a-f]{6}|&[0-9a-fk-orx]/gi, "")
  .replace(/^\[Not Secure\]\s*/, "").trim().replace(/\s+/g, " ");

async function ackOf(actionId: string): Promise<any> {
  for (let i = 0; i < 100; i++) {
    const ack = (await stub<any[]>("/stub/acks")).find((entry) => entry.id === actionId);
    if (ack) return ack;
    await Bun.sleep(100);
  }
  return null;
}

// ---------------------------------------------------------------- the server's side, by mode

const real = new RealServer(work, NETWORK, run);

// What ECC-Server does about one player's lines. With the stub, the test queues the action; with the real server,
// the action is the one its judge's ruling made after `mark` (a count of record lines), and the test waits for it.
interface Side {
  mark(): Promise<number>;
  // Releases the player's held lines with these texts (stub: in the reverse order when `reversed`) and returns the
  // acknowledgement's detail.
  release(player: Player, texts: string[], mark: number, reversed?: boolean): Promise<string>;
  warn(player: Player, mark: number): Promise<string>;
  // Bans the player (stub: naming `account` as the account, as a server that named it by a nickname would).
  ban(player: Player, mark: number, account?: string): Promise<{ id: string; detail: string }>;
}

const stubSide: Side = {
  mark: async () => 0,
  async release(player, texts, _mark, reversed) {
    const ids = [];
    for (const text of texts) ids.push(await lineId(player, text));
    if (reversed) ids.reverse();
    const action = await stub("/stub/action", { type: "release", uuid: player.uuid, account: player.name, ids });
    const ack = await ackOf(action.id);
    expect(ack?.ok, `release ${action.id} not acknowledged as done: ${JSON.stringify(ack)}`);
    return ack.detail;
  },
  async warn(player) {
    const action = await stub("/stub/action", { type: "warn", uuid: player.uuid, account: player.name });
    return (await ackOf(action.id))?.detail;
  },
  async ban(player, _mark, account) {
    const action = await stub("/stub/action", { type: "ban", uuid: player.uuid, account: account ?? player.name });
    const ack = await ackOf(action.id);
    expect(ack?.ok, `ban not acknowledged as done: ${JSON.stringify(ack)}`);
    return { id: action.id, detail: ack.detail };
  },
};

const realSide: Side = {
  mark: async () => (await real.record()).length,
  async release(player, _texts, mark) {
    const found = await real.action("release", player.uuid, mark, 45_000);
    expect(found, `ECC-Server sent no release for ${player.name} that the plugin acknowledged`);
    return `${found.ack.detail} (ids [${found.sent.ids.length}])`;
  },
  async warn(player, mark) {
    const found = await real.action("warn", player.uuid, mark, 30_000);
    expect(found, `ECC-Server sent no warn for ${player.name} that the plugin acknowledged`);
    return found.ack.detail;
  },
  async ban(player, mark) {
    const found = await real.action("ban", player.uuid, mark, 60_000);
    expect(found, `ECC-Server sent no ban for ${player.name} that the plugin acknowledged`);
    return { id: found.sent.id, detail: found.ack.detail };
  },
};

const side = MODE === "stub" ? stubSide : realSide;

// ---------------------------------------------------------------- scenarios

const results: Array<{ name: string; pass: boolean; detail: string }> = [];

async function scenario(name: string, body: () => Promise<string>) {
  if (process.env.ONLY && !new RegExp(process.env.ONLY).test(name)) return;
  try {
    const detail = await body();
    results.push({ name, pass: true, detail });
    console.log(`PASS  ${name}: ${detail}`);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    results.push({ name, pass: false, detail });
    console.log(`FAIL  ${name}: ${detail}`);
  }
}

function expect(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

// Asserts Bob does not hear the text for `millis`, and Alice hears the notice; returns when the hold started.
async function heldFor(alice: Player, bob: Player, text: string, since: number, millis = 3000) {
  const notice = await alice.hears(NOTICE, millis, since);
  expect(notice, `${alice.name} got no notice`);
  const leaked = await bob.hears(text, Math.max(0, since + millis - Date.now()), since);
  expect(!leaked, `${bob.name} heard "${text}" while it was held`);
}

type Bots = { alice: Player; bob: Player; carol: Player; dave: Player };
// When the line that the judge rules `hold` on (or the stub never releases) was written, and the record's length then.
const kept = { since: 0, mark: 0 };

async function lineScenarios({ alice, bob, carol, dave }: Bots) {
  if (MODE === "stub") await scenario("release before line verdict delivers exactly once", async () => {
    const since = Date.now();
    const text = "holdme race-before-verdict";
    alice.say(text);
    const heard = await bob.hears(text, 12_000, since);
    const line = await lineId(alice, text);
    const ack = (await stub<any[]>("/stub/acks")).find((entry) => String(entry.detail).includes("of 1 lines"));
    expect(heard, `Bob never received the early-cleared line; action acknowledgement ${JSON.stringify(ack)}`);
    await Bun.sleep(1500);
    const copies = bob.heard.filter((entry) => entry.at >= since && entry.text.includes(text));
    expect(copies.length === 1, `Bob received ${copies.length} copies after the verdict`);
    expect(ack?.ok && ack.detail === "delivered 1 of 1 lines", `bad release acknowledgement ${JSON.stringify(ack)}`);
    return `early release delivered one copy for ${line}; ${ack.detail}`;
  });
  await discordScenario();

  await scenario("clean public line arrives", async () => {
    const since = Date.now();
    alice.say("clean public line one");
    const heard = await bob.hears("clean public line one", 3000, since);
    expect(heard, "Bob did not hear it within 3 s");
    return `Bob heard it after ${heard.at - since} ms`;
  });

  await scenario("held public line never arrives; the sender gets the notice", async () => {
    kept.since = Date.now();
    kept.mark = await side.mark();
    alice.say("holdme stays public two");
    await heldFor(alice, bob, "holdme stays public two", kept.since);
    const leaked = await bob.hears("holdme stays public two", MODE === "real" ? 10_000 : 0, kept.since);
    expect(!leaked, "Bob heard the line after the judge's ruling");
    return MODE === "real" ? "Bob heard nothing in 3 s nor after the judge ruled hold; Alice got the notice"
      : "Bob heard nothing in 3 s; Alice got the notice";
  });

  await scenario("warn shows the warning and keeps lines held", async () => {
    const since = MODE === "real" ? kept.since : Date.now();
    const detail = await side.warn(alice, kept.mark);
    const warned = await alice.hears(WARNING, 5000, since);
    expect(warned, "Alice did not get the warning");
    expect(!(await bob.hears("holdme stays public two", 1000, 0)), "the held line was delivered after the warning");
    return `${MODE === "real" ? "the judge's hold ruling warned; " : ""}Alice got the warning; ack "${detail}"`;
  });

  await scenario("released lines arrive late and in order", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("holdme order A");
    await Bun.sleep(300);
    alice.say("holdme order B");
    await heldFor(alice, bob, "holdme order", since, 2000);
    const detail = await side.release(alice, ["holdme order A", "holdme order B"], mark, true);
    const heardA = await bob.hears("holdme order A", 5000, since);
    const heardB = await bob.hears("holdme order B", 5000, since);
    expect(heardA && heardB, "Bob did not hear both released lines");
    expect(bob.heard.indexOf(heardA) < bob.heard.indexOf(heardB), "Bob heard B before A");
    return `${MODE === "real" ? "the judge released both in one action" : "released with ids in the order [B, A]"};`
      + ` Bob heard A then B; ack "${detail}"`;
  });

  await scenario("clean private message arrives", async () => {
    const since = Date.now();
    alice.say("/msg Bob clean private five");
    const heard = await bob.hears("clean private five", 3000, since);
    expect(heard, "Bob did not get the private message");
    return `Bob got it after ${heard.at - since} ms: "${heard.text}"`;
  });

  await scenario("held private message, then released", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("/msg Bob holdme private six");
    await heldFor(alice, bob, "holdme private six", since);
    if (MODE === "stub") {
      const id = await lineId(alice, "holdme private six");
      const line = (await stub<any[]>("/stub/lines")).find((entry) => entry.id === id);
      expect(line.target_uuid === bob.uuid && line.target === "Bob" && line.channel === "msg",
        `private message fields wrong: ${JSON.stringify(line)}`);
    } else {
      const line = (await real.record()).find((entry) => entry.type === "line" && entry.text === "holdme private six");
      expect(line?.channel === "msg" && line.target === "Bob" && line.verdict === "hold",
        `the record's line: ${JSON.stringify(line)}`);
    }
    await side.release(alice, ["holdme private six"], mark);
    const heard = await bob.hears("holdme private six", 5000, since);
    expect(heard, "Bob did not get the released private message");
    return `held, then Bob got "${heard.text}"`;
  });

  await scenario("held /me, then released", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("/me holdme waves seven");
    await heldFor(alice, bob, "holdme waves seven", since);
    await side.release(alice, ["holdme waves seven"], mark);
    const heard = await bob.hears("holdme waves seven", 5000, since);
    expect(heard, "Bob did not see the released /me");
    return `held, then Bob saw "${heard.text}"`;
  });

  await scenario("held sign, then released", async () => {
    const sign = new Vec3(2, -60, 0);
    await rcon(`setblock ${sign.x} ${sign.y} ${sign.z} minecraft:oak_sign[rotation=0]`);
    await rcon(`tp Alice 2.5 -60 3.5 180 0`);
    await rcon(`tp Bob 0.5 -60 4.5 180 0`);
    await Bun.sleep(1500);
    const block = alice.bot.blockAt(sign);
    expect(block?.name === "oak_sign", `no sign at ${sign}: ${block?.name}`);
    const since = Date.now();
    const mark = await side.mark();
    await alice.bot.activateBlock(block);
    await Bun.sleep(500);
    alice.bot.updateSign(alice.bot.blockAt(sign)!, "holdme sign eight");
    const notice = await alice.hears(NOTICE, 3000, since);
    expect(notice, "Alice got no notice for the sign");
    await Bun.sleep(1500);
    const before = (bob.bot.blockAt(sign) as any)?.getSignText?.() ?? [];
    expect(!String(before).includes("holdme"), `Bob saw the held sign text: ${before}`);
    await side.release(alice, ["holdme sign eight"], mark);
    const until = Date.now() + 5000;
    let after: string[] = [];
    while (Date.now() < until) {
      after = (bob.bot.blockAt(sign) as any)?.getSignText?.() ?? [];
      if (String(after).includes("holdme sign eight")) break;
      await Bun.sleep(200);
    }
    expect(String(after).includes("holdme sign eight"), `Bob did not see the released sign: ${after}`);
    return `Bob saw [${String(before).replace(/\n/g, "|")}] while held, then "${String(after[0]).replace(/\n/g, " ")}"`;
  });

  await scenario("held nickname, then released", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("/nick HoldmeNick");
    const notice = await alice.hears(NOTICE, 3000, since);
    expect(notice, "Alice got no notice for the nickname");
    await Bun.sleep(1000);
    alice.say("clean after nick held");
    const heldLine = await bob.hears("clean after nick held", 3000, since);
    expect(heldLine && !heldLine.text.includes("HoldmeNick"), `the held nickname showed: ${heldLine?.text}`);
    await side.release(alice, ["HoldmeNick"], mark);
    await Bun.sleep(1500);
    const later = Date.now();
    alice.say("clean after nick released");
    const shown = await bob.hears("clean after nick released", 3000, later);
    expect(shown, "Bob did not hear the line after the release");
    const packet = bob.packets.find((entry) => entry.at >= later && entry.text.includes("clean after nick released"));
    const heldPacket = bob.packets.find((entry) => entry.at >= since && entry.text.includes("clean after nick held"));
    expect(heldPacket && !heldPacket.text.includes("HoldmeNick"), `the held nickname showed: ${heldPacket?.text}`);
    expect(packet?.text.includes("HoldmeNick"), `the released nickname did not show: ${packet?.text}`);
    return "Bob's chat packet named Alice while the nickname was held, and ~HoldmeNick after the release";
  });

  await scenario("held mail, then released", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("/mail send Bob holdme mail eleven");
    const notice = await alice.hears(NOTICE, 3000, since);
    expect(notice, "Alice got no notice for the mail");
    bob.say("/mail read");
    expect(!(await bob.hears("holdme mail eleven", 2000, since)), "Bob read the held mail");
    await side.release(alice, ["holdme mail eleven"], mark);
    await Bun.sleep(1000);
    const later = Date.now();
    bob.say("/mail read");
    const read = await bob.hears("holdme mail eleven", 3000, later);
    expect(read, "Bob could not read the released mail");
    return `Bob's mailbox had nothing while held, then "${read.text}"`;
  });

  await scenario("held book edit, then applied", async () => {
    await rcon("minecraft:item replace entity Alice hotbar.1 with minecraft:writable_book");
    await Bun.sleep(1000);
    const since = Date.now();
    const mark = await side.mark();
    // The bot waits for the edited book to come back, which a held edit never does.
    await Promise.race([alice.bot.writeBook(37, ["holdme book twelve"]).catch(() => undefined), Bun.sleep(3000)]);
    const notice = await alice.hears(NOTICE, 2000, since);
    expect(notice, "Alice got no notice for the book");
    const page = "data get entity Alice Inventory[{Slot:1b}].components.\"minecraft:writable_book_content\".pages[0]";
    const before = await rcon(page);
    expect(!before.includes("holdme book twelve"), "the held edit is in Alice's book");
    await side.release(alice, ["holdme book twelve"], mark);
    await Bun.sleep(1000);
    const after = await rcon(page);
    expect(after.includes("holdme book twelve"), `the released edit is not in Alice's book: ${after}`);
    return "the book stayed empty while held; the released edit was written to the same book";
  });

  await scenario("held item name, then taken on the next click", async () => {
    const anvilAt = new Vec3(4, -60, 4);
    await rcon(`setblock ${anvilAt.x} ${anvilAt.y} ${anvilAt.z} minecraft:anvil`);
    // Survival, so the bot moves items by clicks as a player would, not by creative inventory writes.
    await rcon("clear Alice");
    await rcon("gamemode survival Alice");
    await rcon("minecraft:xp add Alice 10 levels");
    await rcon("minecraft:item replace entity Alice hotbar.2 with minecraft:name_tag");
    await rcon("tp Alice 4.5 -60 2.5 0 30");
    await Bun.sleep(1500);
    const anvil = await alice.bot.openAnvil(alice.bot.blockAt(anvilAt)!);
    const within = <T>(promise: Promise<T>) => Promise.race([promise.catch(() => undefined), Bun.sleep(2000)]);
    await within(alice.bot.moveSlotItem(anvil.hotbarStart + 2, 0));
    await Bun.sleep(300);
    alice.bot._client.write("name_item", { name: "holdme tag thirteen" });
    await Bun.sleep(500);
    const since = Date.now();
    const mark = await side.mark();
    await within(alice.bot.clickWindow(2, 0, 0));
    const notice = await alice.hears(NOTICE, 3000, since);
    expect(notice, "Alice got no notice for the item name");
    await side.release(alice, ["holdme tag thirteen"], mark);
    const checked = await alice.hears("Your item name was checked", 3000, since);
    expect(checked, "Alice was not told the name was checked");
    await within(alice.bot.clickWindow(2, 0, 0));
    await within(alice.bot.clickWindow(anvil.inventoryStart, 0, 0));
    alice.bot.closeWindow(anvil);
    await Bun.sleep(1000);
    const tags: string[] = [];
    for (let slot = 0; slot < 36; slot++) {
      const id = await rcon(`data get entity Alice Inventory[{Slot:${slot}b}].id`);
      if (id.includes("name_tag")) {
        const count = await rcon(`data get entity Alice Inventory[{Slot:${slot}b}].count`);
        const name = await rcon(`data get entity Alice Inventory[{Slot:${slot}b}].components."minecraft:custom_name"`);
        tags.push(`slot ${slot}: ${count.trim().split(": ").pop()} x ${name.trim().split(": ").slice(1).join(": ")}`);
      }
    }
    await rcon("gamemode creative Alice");
    expect(tags.length === 1 && tags[0].includes(" 1 x") && tags[0].includes("holdme tag thirteen"),
      `Alice's name tags: ${tags.join("; ")}`);
    return "the first click was stopped and held; after the release Alice was told, and her next click took the name";
  });

  await scenario("a repeated held line is held, and released with it", async () => {
    const since = Date.now();
    const mark = await side.mark();
    alice.say("holdme repeat fourteen");
    await Bun.sleep(1500);
    alice.say("holdme repeat fourteen");
    await heldFor(alice, bob, "holdme repeat fourteen", since, 2500);
    const notices = alice.heard.filter((line) => line.at >= since && line.text.includes(NOTICE)).length;
    expect(notices === 2, `Alice got ${notices} notices for two held lines`);
    const detail = await side.release(alice, ["holdme repeat fourteen"], mark);
    await Bun.sleep(1500);
    const copies = bob.heard.filter((line) => line.at >= since && line.text.includes("holdme repeat fourteen")).length;
    expect(copies === 2, `Bob heard ${copies} copies, not 2`);
    return `both copies held; one release by the first id carried the repeat's id after it; ack "${detail}";`
      + " Bob heard both copies";
  });

  await commandScenarios({ alice, bob, carol, dave });
  await mutedScenario({ alice, bob, carol, dave });
  await guildScenarios({ alice, bob, carol, dave });
  await creditScenarios({ alice, bob, carol, dave });
}

// Whose line it is and how it is checked: an operator is not trusted for being one, a line forced by /sudo is its
// issuer's, a tag ending in "»" is part of the shown name, and /r with no one to reply to is no line.
async function creditScenarios({ alice, bob, carol, dave }: Bots) {
  await scenario("an operator without the bypass permission is checked", async () => {
    await rcon("minecraft:op Alice");
    await Bun.sleep(1000);
    const since = Date.now();
    const mark = await side.mark();
    alice.say("holdme operator twenty");
    try {
      await heldFor(alice, bob, "holdme operator twenty", since);
    } finally {
      await rcon("minecraft:deop Alice");
    }
    const line = await received("holdme operator twenty");
    expect(line && line.uuid === alice.uuid && !line.trusted, `the line: ${JSON.stringify(line)}`);
    await side.release(alice, ["holdme operator twenty"], mark);
    expect(await bob.hears("holdme operator twenty", 5000, since), "Bob did not hear the released line");
    return "Alice, opped and without plex.ecc.chat.bypass, was held and got the notice; the line went as untrusted;"
      + " Bob heard it after the release";
  });

  await scenario("a line forced by /sudo is its issuer's, with the issuer's account and trust", async () => {
    // Plex's anti-spam cancels a player's ninth chat line or command within 5 s.
    await Bun.sleep(5000);
    let since = Date.now();
    const mark = await side.mark();
    alice.say("/sudo Bob c:holdme forced twenty one");
    await heldFor(alice, dave, "holdme forced twenty one", since);
    expect(!bob.heard.some((line) => line.at >= since && line.text.includes(NOTICE)), "Bob got the notice");
    const forced = await received("holdme forced twenty one");
    expect(forced && forced.channel === "chat" && forced.uuid === alice.uuid && !forced.trusted
      && (MODE === "real" || forced.speaker === "Alice") && prepared(forced.shown ?? "").includes("Bob"),
      `the forced chat line: ${JSON.stringify(forced)}`);
    await side.release(alice, ["holdme forced twenty one"], mark);
    const released = await dave.hears("holdme forced twenty one", 5000, since);
    expect(released?.text.includes("Bob"), `Dave did not hear the released line as Bob's: ${released?.text}`);
    since = Date.now();
    alice.say("/sudo Bob me clean forced waves");
    const me = await received("clean forced waves");
    expect(me && me.channel === "me" && me.uuid === alice.uuid, `the forced /me: ${JSON.stringify(me)}`);
    expect(await dave.hears("clean forced waves", 3000, since), "Dave did not see the forced /me");
    since = Date.now();
    carol.say("/sudo Bob c:holdme carol forced");
    const trusted = await dave.hears("holdme carol forced", 1500, since);
    expect(trusted, "Dave did not hear Carol's forced line within 1.5 s");
    const carols = await received("holdme carol forced");
    expect(carols && carols.uuid === carol.uuid && carols.trusted, `Carol's forced line: ${JSON.stringify(carols)}`);
    return `Alice's forced chat line went as Alice's, shown "${forced.shown}", held with Alice notified and Bob not,`
      + ` then released as Bob's line; her forced /me went as Alice's; Carol's forced line went as hers, trusted,`
      + ` and Dave heard it after ${trusted.at - since} ms`;
  });

  await scenario("a tag that ends in » is part of the shown name", async () => {
    const riawo = await Player.join("riawo");
    try {
      await rcon("tp riawo 0.5 -60 2.5");
      await Bun.sleep(1500);
      riawo.say("/tag set [MB] »");
      await Bun.sleep(1500);
      const since = Date.now();
      riawo.say("hello");
      expect(await bob.hears("hello", 3000, since), "Bob did not hear riawo's line");
      const log = (await run(["podman", "exec", SERVER, "cat", "/data/logs/latest.log"])).split(/\r?\n/);
      const logged = log.map((entry) => /^\[\d\d:\d\d:\d\d\] \[Async Chat Thread[^\]]*\]: (.*)$/.exec(entry)?.[1])
        .find((entry) => entry?.endsWith("riawo » hello"));
      expect(logged && prepared(logged) === "[MB] » riawo » hello", `the console line: ${logged}`);
      const line = await received("hello");
      expect(line && line.uuid === riawo.uuid && prepared(line.shown ?? "") === "[MB] » riawo",
        `the line: ${JSON.stringify(line)}`);
      riawo.say("/tag clear");
      await Bun.sleep(1000);
      return `the console logged "${logged}"; ECC-Server got shown "${line.shown}", text "hello"`;
    } finally {
      riawo.bot.quit();
    }
  });

  await scenario("/r with no one to reply to sends nothing", async () => {
    const since = Date.now();
    carol.say("/r clean lonely reply");
    await Bun.sleep(2000);
    expect(!(await received("clean lonely reply", 5)), "ECC-Server received the reply");
    const told = carol.heard.find((line) => line.at >= since);
    return `ECC-Server received nothing; Carol was told "${told?.text}"`;
  });
}

// The commands that set an item's name or lore, a sign or a tag, read as the training pipeline read their log line;
// the raw text of private messages, mail and /me; a nickname set for another player.
async function commandScenarios({ alice, bob }: Bots) {
  const itemName = 'data get entity Alice Inventory[{Slot:0b}].components."minecraft:custom_name"';
  await scenario("a command that names an item is held, and run after the release", async () => {
    await rcon("minecraft:item replace entity Alice hotbar.0 with minecraft:stick");
    alice.bot.setQuickBarSlot(0);
    await Bun.sleep(1000);
    const since = Date.now();
    const mark = await side.mark();
    alice.say("/itemname holdme named fifteen");
    expect(await alice.hears(NOTICE, 3000, since), "Alice got no notice for /itemname");
    expect(!(await rcon(itemName)).includes("holdme named fifteen"), "the held command named the item");
    await side.release(alice, ["holdme named fifteen"], mark);
    let named = "";
    for (const until = Date.now() + 5000; Date.now() < until; await Bun.sleep(300)) {
      named = await rcon(itemName);
      if (named.includes("holdme named fifteen")) break;
    }
    expect(named.includes("holdme named fifteen"), `the released command did not name the item: ${named}`);
    return "held; after the release the command ran as Alice and named her stick";
  });

  await scenario("a command's set or add word and line number are not part of its text", async () => {
    const since = Date.now();
    alice.say("/lore add clean lore sixteen");
    alice.say("/editsign set 2 clean sign sixteen");
    alice.say("/tag set clean tag sixteen");
    alice.say("/itemname clear");
    await Bun.sleep(2000);
    expect(!alice.heard.some((line) => line.at >= since && line.text.includes(NOTICE)), "Alice got a notice");
    const lore = await rcon('data get entity Alice Inventory[{Slot:0b}].components."minecraft:lore"');
    expect(lore.includes("clean lore sixteen"), `the delivered /lore did not run: ${lore}`);
    if (MODE === "stub") {
      const lines = (await stub<any[]>("/stub/lines")).filter((line) => line.speaker_uuid === alice.uuid
        && (line.text.includes("sixteen") || line.text.toLowerCase().includes("clear")));
      const got = lines.map((line) => `${line.channel}:${line.text}`).sort();
      const want = ["item:clean lore sixteen", "sign:clean sign sixteen", "tag:clean tag sixteen"];
      expect(JSON.stringify(got) === JSON.stringify(want), `the lines sent: ${JSON.stringify(got)}`);
    }
    return MODE === "stub" ? "the stub got item, sign and tag lines without set/add and the number, none for"
      + " /itemname clear; the delivered /lore ran" : "none held; the delivered /lore ran";
  });

  await scenario("private messages, mail and /me reach the server as typed", async () => {
    const since = Date.now();
    alice.say("/msg Bob &cclean raw msg https://rawlink.example/a");
    alice.say("/mail send Bob &aclean raw mail https://rawlink.example/m");
    alice.say("/me &eclean raw waves");
    const heard = await bob.hears("clean raw msg", 3000, since);
    expect(heard, "Bob did not get the private message");
    if (MODE === "stub") {
      await Bun.sleep(1000);
      const lines = (await stub<any[]>("/stub/lines")).filter((line) => line.speaker_uuid === alice.uuid
        && line.text.includes("clean raw"));
      const got = Object.fromEntries(lines.map((line) => [line.channel, line.text]));
      expect(got.msg === "&cclean raw msg https://rawlink.example/a"
        && got.mail === "&aclean raw mail https://rawlink.example/m" && got.me === "&eclean raw waves",
        `the texts sent: ${JSON.stringify(got)}; Bob saw "${heard.text}"`);
    }
    return `Bob saw "${heard.text}"${MODE === "stub" ? "; the stub got each text as Alice typed it" : ""}`;
  });

  await scenario("EssentialsX's other names for /me and /msg, /mail sendtemp and /mail sendall reach the server as typed",
    async () => {
      // Plex's anti-spam cancels a player's ninth chat line or command within 5 s.
      await Bun.sleep(5000);
      const since = Date.now();
      alice.say("/action clean alias waves");
      alice.say("/t Bob clean alias message");
      alice.say("/mail sendtemp Bob 1d clean temp mail");
      alice.say("/mail sendall clean mail to all");
      const lines: Record<string, Received | null> = {};
      for (const text of ["clean alias waves", "clean alias message", "clean temp mail", "clean mail to all"]) {
        lines[text] = await received(text);
      }
      await Bun.sleep(1500);
      const all = await received("clean mail to all");
      const got = JSON.stringify(lines);
      expect(lines["clean alias waves"]?.channel === "me" && lines["clean alias message"]?.channel === "msg"
        && lines["clean alias message"]?.target === "Bob" && lines["clean temp mail"]?.channel === "mail"
        && lines["clean temp mail"]?.target === "Bob" && all?.channel === "mail" && all.target === "*"
        && all.copies === 1, `the lines: ${got}; sendall copies ${all?.copies}`);
      expect(await bob.hears("clean alias message", 3000, since), "Bob did not get the /t message");
      bob.say("/mail read");
      const read = await bob.hears("clean mail to all", 3000, since);
      expect(read, "Bob's mailbox does not hold the /mail sendall mail");
      return "/action as me, /t as msg to Bob, sendtemp as mail to Bob with the text after the time, sendall as one"
        + ` mail line to *; Bob read "${read.text}"`;
    });

  await scenario("a nickname set for another player names that player as its target", async () => {
    // Plex's anti-spam cancels a player's ninth chat line or command within 5 s.
    await Bun.sleep(5000);
    const since = Date.now();
    alice.say("/nick Bob CleanOther");
    await Bun.sleep(1500);
    alice.say("/nick Bob off");
    await Bun.sleep(1000);
    expect(!alice.heard.some((line) => line.at >= since && line.text.includes(NOTICE)), "Alice got a notice");
    if (MODE === "stub") {
      const lines = (await stub<any[]>("/stub/lines")).filter((line) => line.speaker_uuid === alice.uuid
        && line.channel === "nick" && line.text !== "HoldmeNick");
      expect(lines.length === 1 && lines[0].channel === "nick" && lines[0].text === "CleanOther"
        && lines[0].target === "Bob" && lines[0].target_uuid === bob.uuid, `the lines sent: ${JSON.stringify(lines)}`);
    }
    return MODE === "stub" ? "one nick line, target Bob, text CleanOther; nothing for /nick Bob off"
      : "delivered; nothing held";
  });
}

// A quarantined player's chat: only its author sees it, ECC-Server scores it as muted, and its verdict changes nothing:
// no notice, no warning, no delivery on release. The real judge rules hold on it, which warns.
async function mutedScenario({ alice, bob }: Bots) {
  await scenario("a quarantined player's line is sent as muted; a hold, warning or release changes nothing", async () => {
    await rcon("nush on");
    await Bun.sleep(1500);
    const since = Date.now();
    const mark = await side.mark();
    bob.say("holdme stays muted seventeen");
    const own = await bob.hears("holdme stays muted seventeen", 3000, since);
    expect(own, "Bob did not see his own quarantined line");
    let release: string;
    let warn: string;
    try {
      if (MODE === "stub") {
        const id = await lineId(bob, "holdme stays muted seventeen");
        const line = (await stub<any[]>("/stub/lines")).find((entry) => entry.id === id);
        expect(line.channel === "muted" && line.trusted === false && line.verdict === "hold",
          `the muted line: ${JSON.stringify(line)}`);
        warn = (await ackOf((await stub("/stub/action", { type: "warn", uuid: bob.uuid, account: bob.name })).id))
          ?.detail;
        const action = await stub("/stub/action", { type: "release", uuid: bob.uuid, account: bob.name, ids: [id] });
        release = `release: "${(await ackOf(action.id))?.detail}"`;
      } else {
        const found = await real.action("warn", bob.uuid, mark, 45_000);
        warn = found ? found.ack.detail : "no warning came";
        release = "the judge ruled hold";
      }
      await Bun.sleep(1500);
    } finally {
      await rcon("nush off");
    }
    expect(!(await alice.hears("holdme stays muted seventeen", 500, since)), "Alice heard the quarantined line");
    expect(!bob.heard.some((line) => line.at >= since && line.text.includes(NOTICE)), "Bob got the hold notice");
    expect(warn === "quarantined, not shown", `the warning's ack: "${warn}"`);
    expect(!bob.heard.some((line) => line.at >= since && line.text.includes(WARNING)), "Bob got the warning");
    return `Bob saw his line, Alice did not, Bob got no notice; warning: "${warn}"; ${release}`;
  });
}

// Guild chat, a module channel: Alice and Bob are in RigGuild, Carol and Dave are not. A line goes to the guild by
// /guild chat <line>, which fires the channel event on the server thread, or as chat while guild chat is toggled on,
// which fires it on the chat thread.
async function guildScenarios({ alice, bob, carol, dave }: Bots) {
  // Carol's staff alerts quote held lines; anything else she or Dave heard of a guild line is a leak.
  const outsiders = (text: string, since: number) =>
    [carol, dave].filter((player) => player.heard.some((line) => line.at >= since && line.text.includes(text)
      && !line.text.startsWith("[ECC]"))).map((player) => player.name);
  const copies = (player: Player, text: string, since: number) =>
    player.heard.filter((line) => line.at >= since && line.text.includes(text)).length;

  await scenario("clean guild lines reach the guild's other member and no one else", async () => {
    let since = Date.now();
    alice.say("/guild create RigGuild");
    expect(await alice.hears("RigGuild", 5000, since), "Alice was not told the guild was created");
    since = Date.now();
    alice.say("/guild invite Bob");
    expect(await bob.hears("RigGuild", 5000, since), "Bob got no invite");
    bob.say("/guild accept RigGuild");
    await Bun.sleep(2000);
    since = Date.now();
    alice.say("/guild chat clean guild command line");
    const command = await bob.hears("clean guild command line", 3000, since);
    expect(command, "Bob did not hear Alice's /guild chat line within 3 s");
    bob.say("/guild chat");
    await Bun.sleep(1000);
    const toggled = Date.now();
    bob.say("clean guild toggled line");
    const chat = await alice.hears("clean guild toggled line", 3000, toggled);
    expect(chat, "Alice did not hear Bob's guild chat line within 3 s");
    bob.say("/guild chat");
    await Bun.sleep(1500);
    const heardBy = outsiders("clean guild", since);
    expect(heardBy.length === 0, `${heardBy.join(" and ")}, outside the guild, heard a guild line`);
    expect(copies(alice, "clean guild command line", since) === 1 && copies(bob, "clean guild toggled line", since) === 1,
      "a sender saw their own guild line other than once");
    return `Bob heard the /guild chat line after ${command.at - since} ms; Alice heard Bob's toggled line after`
      + ` ${chat.at - toggled} ms; Carol and Dave heard neither; each sender saw their line once`;
  });

  await scenario("held guild lines, then released, reach the guild's other member and no one else", async () => {
    alice.say("/guild chat");
    await Bun.sleep(1000);
    const since = Date.now();
    const mark = await side.mark();
    alice.say("holdme guild chat A");
    await Bun.sleep(300);
    alice.say("/guild chat holdme guild command B");
    await heldFor(alice, bob, "holdme guild", since);
    const notices = alice.heard.filter((line) => line.at >= since && line.text.includes(NOTICE)).length;
    expect(notices === 2, `Alice got ${notices} notices for two held guild lines`);
    const detail = await side.release(alice, ["holdme guild chat A", "holdme guild command B"], mark);
    const heardA = await bob.hears("holdme guild chat A", 5000, since);
    const heardB = await bob.hears("holdme guild command B", 5000, since);
    expect(heardA && heardB, "Bob did not hear both released guild lines");
    expect(bob.heard.indexOf(heardA) < bob.heard.indexOf(heardB), "Bob heard B before A");
    alice.say("/guild chat");
    await Bun.sleep(1500);
    const heardBy = outsiders("holdme guild", since);
    expect(heardBy.length === 0, `${heardBy.join(" and ")}, outside the guild, heard a held guild line`);
    expect(copies(alice, "holdme guild chat A", since) === 1 && copies(alice, "holdme guild command B", since) === 1,
      "Alice saw a held guild line other than once");
    const alerts = carol.heard.filter((line) => line.at >= since && line.text.startsWith("[ECC] Held Alice's"));
    return `both held (chat and /guild chat); after the release Bob heard A then B ${heardB.at - since} ms after`
      + ` they were written; Dave heard neither, Carol only her ${alerts.length} staff alerts; Alice saw each once;`
      + ` ack "${detail}"`;
  });
}

// DiscordSRV runs with no bot token, so it never reaches Discord and disables itself; what can be checked is that the
// module links against it and subscribes to its API while it is enabled, and runs on after it is gone.
async function discordScenario() {
  await scenario(DISCORDSRV ? "DiscordSRV is hooked while it runs" : "the module runs without DiscordSRV", async () => {
    const logs = await run(["podman", "logs", SERVER], { check: false });
    const subscribed = logs.includes("API listener dev.plex.listener.DiscordSrvHook subscribed (2 methods)");
    expect(!/NoClassDefFoundError|ClassNotFoundException/.test(logs), "the server log has a class loading failure");
    expect(logs.includes("Chat safety is on"), "chat safety did not start");
    if (!DISCORDSRV) {
      expect(!subscribed, "the hook subscribed with no DiscordSRV");
      return "chat safety started; no DiscordSRV, no hook, no class loading failure";
    }
    expect(subscribed, "DiscordSRV logged no subscription of DiscordSrvHook");
    expect(/No bot token has been set/.test(logs), "DiscordSRV did not report the missing token");
    return "DiscordSRV logged the hook's subscription to its API, then disabled itself for want of a token;"
      + " no class loading failure";
  });
}

// ---- the stub only: actions queued on command and a server too slow to answer

async function stubScenarios({ alice, bob, carol, dave }: Bots) {
  await scenario("ECC-Server's notices, such as a stop of judging, are shown to staff", async () => {
    const since = Date.now();
    const action = await stub("/stub/action", { type: "notice", kind: "budget",
      text: "The judge's daily budget is used up." });
    const stop = await stub("/stub/action", { type: "notice", kind: "bedrock_key",
      text: "Judging stopped: Bedrock refused the key." });
    const heard = await carol.hears("The judge's daily budget is used up.", 5000, since);
    expect(heard, "Carol, who holds plex.ecc.alerts, did not see the budget notice");
    const stopped = await carol.hears("Bedrock refused the key", 5000, since);
    expect(stopped, "Carol did not see the judging-stop notice");
    expect(!(await bob.hears("daily budget", 500, since)) && !(await bob.hears("Bedrock refused", 0, since)),
      "Bob, who is not staff, saw a notice");
    return `Carol saw "${heard.text}" and "${stopped.text}"; acks "${(await ackOf(action.id))?.detail}",`
      + ` "${(await ackOf(stop.id))?.detail}"`;
  });

  await scenario("server not answering holds the line until it answers", async () => {
    await stub("/stub/slow", { millis: 4000, seconds: 6 });
    const since = Date.now();
    alice.say("slow line nine");
    await heldFor(alice, bob, "slow line nine", since, 4000);
    const heard = await bob.hears("slow line nine", 30_000, since);
    expect(heard, "Bob never heard the line after the server answered again");
    return `held with no answer in 1 s; Bob heard nothing for 4 s; delivered ${heard.at - since} ms after it was`
      + " written, once the asked-again request was answered deliver";
  });

  await scenario("a held line is asked again as soon as the server answers again, not after the backoff", async () => {
    await stub("/stub/down", { seconds: 12 });
    // Every sender's wait has doubled to its longest by the time the server is back.
    const since = Date.now();
    alice.say("clean line while the stub is down");
    await heldFor(alice, bob, "clean line while the stub is down", since, 9000);
    const wait = Math.max(0, since + 12_000 - Date.now());
    await Bun.sleep(wait);
    const back = Date.now();
    const heard = await bob.hears("clean line while the stub is down", 30_000, since);
    expect(heard, "Bob never heard the line after the server answered again");
    expect(heard.at - back <= 7000, `Bob heard the line ${heard.at - back} ms after the server answered again`);
    return `the stub answered 503 for 12 s; Bob heard the held line ${heard.at - back} ms after it answered again`;
  });

  await scenario("bypass player's line arrives at once", async () => {
    await stub("/stub/slow", { millis: 3000, seconds: 5 });
    const since = Date.now();
    carol.say("holdme staff line ten");
    const heard = await bob.hears("holdme staff line ten", 1500, since);
    expect(heard, "Bob did not hear Carol's line within 1.5 s");
    await lineId(carol, "holdme staff line ten");
    return `Bob heard it after ${heard.at - since} ms while the server took 3 s per line; the stub got it as trusted`;
  });

  await scenario("trusted-group player's line arrives at once", async () => {
    await stub("/stub/slow", { millis: 3000, seconds: 5 });
    const since = Date.now();
    dave.say("holdme trusted line ten");
    const heard = await bob.hears("holdme trusted line ten", 1500, since);
    expect(heard, "Bob did not hear Dave's line within 1.5 s");
    await lineId(dave, "holdme trusted line ten");
    return `Bob heard it after ${heard.at - since} ms while the server took 3 s per line; the stub got it as trusted`;
  });

  await scenario("lines reach the server in the order they were written, trusted ones among them", async () => {
    // While the server takes 1.5 s per line, a trusted line that waited for the one before it would arrive late.
    await stub("/stub/slow", { millis: 1500, seconds: 4 });
    const texts = ["order trusted one", "order trusted two", "order checked three", "order trusted four"];
    for (const text of texts) {
      (text.includes("checked") ? alice : dave).say(text);
      await Bun.sleep(250);
    }
    await Bun.sleep(5000);
    const lines = await stub<any[]>("/stub/lines");
    const arrivals = texts.map((text) => lines.find((line) => line.text === text)?.arrival);
    expect(arrivals.every((arrival, i) => arrival && (i === 0 || arrival > arrivals[i - 1]!)),
      `arrival numbers in written order: ${JSON.stringify(arrivals)}`);
    return `arrival numbers ${arrivals.join(", ")} for Dave, Dave, Alice, Dave, written 250 ms apart`;
  });

  await scenario("each channel arrives under its name; shown is the console line's name", async () => {
    const lines = await stub<any[]>("/stub/lines");
    const want: Array<[string, string]> = [["clean public line one", "chat"], ["clean private five", "msg"],
      ["holdme waves seven", "me"], ["holdme sign eight", "sign"], ["HoldmeNick", "nick"],
      ["holdme mail eleven", "mail"], ["holdme book twelve", "book"], ["holdme tag thirteen", "item"],
      ["holdme named fifteen", "item"], ["clean guild command line", "guild"], ["clean guild toggled line", "guild"],
      ["holdme stays muted seventeen", "muted"]];
    const wrong = want.filter(([text, channel]) => lines.find((line) => line.text === text)?.channel !== channel);
    expect(wrong.length === 0, `wrong or missing channels: ${JSON.stringify(wrong.map(([text]) =>
      lines.find((line) => line.text === text) ?? text))}`);
    expect(lines.every((line) => !("conversation" in line) && !("private" in line)), "a line carries a dropped field");
    const shownOn = lines.filter((line) => "shown" in line).map((line) => line.channel);
    expect(shownOn.every((channel) => channel === "chat" || channel === "guild"), `shown sent on ${shownOn}`);
    // The console log as Paper writes it: "[time] [thread/INFO]: <message>".
    const log = (await run(["podman", "exec", SERVER, "cat", "/data/logs/latest.log"])).split(/\r?\n/)
      .map((entry) => /^\[\d\d:\d\d:\d\d\] \[([^\]]*)\]: (.*)$/.exec(entry)).filter(Boolean) as RegExpExecArray[];
    const chat = lines.find((line) => line.text === "clean public line one");
    const chatLog = log.find((entry) => entry[1].startsWith("Async Chat Thread")
      && entry[2].endsWith(" » clean public line one"));
    const consoleName = chatLog?.[2].slice(0, chatLog[2].indexOf(" » "));
    expect(chatLog && prepared(chat.shown) === prepared(consoleName!),
      `shown "${chat.shown}", console "${chatLog?.[2]}"`);
    const guild = lines.find((line) => line.text === "clean guild command line");
    const guildLog = log.map((entry) => /^\[GUILD - (.+?):([0-9a-f-]{36})\] (\S+) (.*)$/s.exec(entry[2]))
      .find((match) => match?.[4] === "clean guild command line");
    expect(guildLog && guild.shown === guildLog[3], `guild shown "${guild.shown}", console "${guildLog?.[0]}"`);
    return `${want.length} lines on their channels; chat shown "${chat.shown}" as the console's`
      + ` "${chatLog![2]}"; guild shown "${guild.shown}" as "${guildLog![0]}"`;
  });

  await scenario("after ECC-Server restarts with a new boot id, start, joins and held lines are sent again", async () => {
    const since = Date.now();
    alice.say("holdme before the restart");
    await heldFor(alice, bob, "holdme before the restart", since, 2000);
    const id = await lineId(alice, "holdme before the restart");
    const restarted = await stub<{ boot: string }>("/stub/restart", {});
    let start: any;
    let joins: any[] = [];
    let resent: any;
    for (const until = Date.now() + 15_000; Date.now() < until; await Bun.sleep(500)) {
      const presence = (await stub<any[]>("/stub/presence")).filter((entry) => entry.boot === restarted.boot);
      start = presence.find((entry) => entry.kind === "start");
      joins = presence.filter((entry) => entry.kind === "join");
      resent = (await stub<any[]>("/stub/lines")).find((line) => line.boot === restarted.boot && line.id === id);
      if (start && joins.length >= 4 && resent) break;
    }
    expect(start && JSON.stringify(start.allowed_links) === JSON.stringify(ALLOWED_LINKS),
      `no start with the allowlist after the restart: ${JSON.stringify(start)}`);
    const names = joins.map((entry) => entry.name).sort().join(",");
    expect(names === "Alice,Bob,Carol,Dave", `joins after the restart: ${names}`);
    expect(resent && resent.verdict === "hold", `the held line was not sent again: ${JSON.stringify(resent)}`);
    expect(joins.every((entry) => entry.arrival > start.arrival && entry.arrival < resent.arrival),
      `out of order: start ${start.arrival}, joins ${joins.map((entry) => entry.arrival)}, line ${resent.arrival}`);
    const notices = alice.heard.filter((line) => line.at >= since && line.text.includes(NOTICE)).length;
    expect(notices === 1, `Alice got ${notices} notices for one held line`);
    const detail = await side.release(alice, ["holdme before the restart"], 0);
    const heard = await bob.hears("holdme before the restart", 5000, since);
    expect(heard, "Bob did not hear the line the restarted stub released");
    return `start (arrival ${start.arrival}), joins of ${names} (${joins.map((entry) => entry.arrival)}), then the`
      + ` held line (${resent.arrival}), answered hold; the restarted stub's release: "${detail}"; Bob heard it`;
  });

  await banScenario({ alice, bob, carol, dave });

  await scenario("presence and line fields reach the server", async () => {
    await Bun.sleep(1000);
    const presence = await stub<any[]>("/stub/presence");
    const kinds = presence.map((entry) => `${entry.kind}${entry.name ? ":" + entry.name : ""}`);
    expect(kinds[0] === "start", `first presence is not start: ${kinds}`);
    expect(JSON.stringify(presence[0].allowed_links) === JSON.stringify(ALLOWED_LINKS),
      `start's allowed_links: ${JSON.stringify(presence[0].allowed_links)}`);
    for (const expected of ["join:Alice", "join:Bob", "join:Carol", "join:Dave", "leave:Alice"]) {
      expect(kinds.includes(expected), `no ${expected} in ${kinds}`);
    }
    const lines = await stub<any[]>("/stub/lines");
    const line = lines.find((entry) => entry.text === "clean public line one");
    expect(line.trusted === false && line.channel === "chat" && line.speaker === "Alice"
      && line.speaker_uuid === alice.uuid && line.target === null && line.target_uuid === null
      && typeof line.shown === "string" && !("time" in line) && !("groups" in line),
      `line fields: ${JSON.stringify(line)}`);
    for (const text of ["holdme staff line ten", "holdme trusted line ten"]) {
      const trusted = lines.find((entry) => entry.text === text);
      expect(trusted?.trusted === true, `"${text}" was not sent as trusted: ${JSON.stringify(trusted)}`);
    }
    expect(presence.every((entry) => !("time" in entry) && !("groups" in entry)), "presence carries time or groups");
    return `presence ${kinds.join(", ")}; start's allowed_links ${JSON.stringify(presence[0].allowed_links)};`
      + " Alice's line trusted: false, Carol's and Dave's trusted: true";
  });

  // The module starts while ECC-Server is down: the first boot id it sees is one no start was acknowledged for.
  await scenario("a module that starts while ECC-Server is down sends start, joins and held lines once it answers", async () => {
    const mark = Date.now();
    await stub("/stub/down", { seconds: 600 });
    const done = (logs: string) => (logs.match(/Done \([\d.,]+s\)!/g) ?? []).length;
    const before = done(await run(["podman", "logs", SERVER], { check: false }));
    await podman("restart", "-t", "20", SERVER);
    for (const until = Date.now() + 300_000; Date.now() < until; await Bun.sleep(2000)) {
      if (done(await run(["podman", "logs", SERVER], { check: false })) > before) break;
    }
    const bob = await Player.join("Bob");
    const carol = await Player.join("Carol");
    try {
      bob.say("holdme line while the module started with no server");
      await Bun.sleep(12_000);
      const early = (await stub<any[]>("/stub/presence")).filter((entry) => entry.received >= mark);
      expect(early.length === 0, `presence reached a server that was down: ${JSON.stringify(early)}`);
      await stub("/stub/down", { seconds: 0 });
      let start: any;
      let joins: any[] = [];
      for (const until = Date.now() + 30_000; Date.now() < until; await Bun.sleep(500)) {
        const presence = (await stub<any[]>("/stub/presence")).filter((entry) => entry.received >= mark);
        start = presence.find((entry) => entry.kind === "start");
        joins = presence.filter((entry) => entry.kind === "join" && start && entry.arrival > start.arrival);
        if (start && ["Bob", "Carol"].every((name) => joins.some((entry) => entry.name === name))) break;
      }
      expect(start && JSON.stringify(start.allowed_links) === JSON.stringify(ALLOWED_LINKS),
        `no start with the allowlist after the server answered: ${JSON.stringify(start)}`);
      const names = [...new Set(joins.map((entry) => entry.name))].sort().join(",");
      expect(names === "Bob,Carol", `joins after start: ${names}`);
      let resent: any;
      for (const until = Date.now() + 15_000; Date.now() < until && !resent; await Bun.sleep(500)) {
        resent = (await stub<any[]>("/stub/lines")).find((line) => line.text === "holdme line while the module started with no server");
      }
      expect(resent && resent.verdict === "hold" && resent.arrival > start.arrival
        && ["Bob", "Carol"].every((name) => joins.some((entry) => entry.name === name && entry.arrival < resent.arrival)),
        `the held line: ${JSON.stringify(resent)}`);
      return `the module started with the server down; once it answered, start (arrival ${start.arrival}) with`
        + ` the allowlist, then joins of ${names}, then the held line (${resent.arrival})`;
    } finally {
      bob.bot.quit();
      carol.bot.quit();
    }
  });
}

// Alice is nicknamed Erin, the name of another account, when she is banned; the ban names her account, so Erin still
// logs in.
async function banScenario({ alice, bob }: Bots) {
  await scenario("ban kicks at once, writes the ban entry by UUID and account and drops held lines", async () => {
    await Bun.sleep(3000);
    alice.say("/nick Erin");
    await Bun.sleep(1500);
    if (MODE === "stub") alice.say("holdme dropped by the ban");
    const heldId = MODE === "stub" ? await lineId(alice, "holdme dropped by the ban") : "";
    const mark = await side.mark();
    if (MODE === "real") alice.say("holdme banned eighteen");
    const ban = await side.ban(alice, mark, "Erin");
    const until = Date.now() + 5000;
    while (!alice.kicked && Date.now() < until) await Bun.sleep(100);
    expect(alice.kicked, "Alice was not kicked");
    const bans = await run(["podman", "exec", SERVER, "cat", "/data/plugins/Plex/indefbans.yml"]);
    expect(bans.includes(`ecc-${ban.id}`) && bans.toLowerCase().includes(alice.uuid),
      `indefbans.yml has no entry ecc-${ban.id} for ${alice.uuid}`);
    expect(bans.includes("Alice") && !bans.toLowerCase().includes("erin"), `indefbans.yml: ${bans}`);
    let after = "";
    if (MODE === "stub") {
      const action = await stub("/stub/action", { type: "release", uuid: alice.uuid, account: alice.name, ids: [heldId] });
      after = `; release afterwards: "${(await ackOf(action.id))?.detail}"`;
    }
    expect(!(await bob.hears(MODE === "stub" ? "holdme dropped by the ban" : "holdme stays public two", 2000, 0)),
      "a dropped line was delivered");
    let refused = "";
    try {
      const again = await Player.join("Alice");
      again.bot.quit();
    } catch (error) {
      refused = String(error instanceof Error ? error.message : error).slice(0, 80);
    }
    expect(refused, "Alice could join again after the ban");
    // Every bot joins from one address, and Plex refuses a banned address before it looks at the name; without the
    // entry's address, its names decide.
    const network = SUBNET.split(".").slice(0, 3).join("\\.") + "\\.";
    await run(["podman", "exec", SERVER, "sed", "-i", `/${network}/d`, "/data/plugins/Plex/indefbans.yml"]);
    await rcon("plex reload");
    const erin = await Player.join("Erin");
    erin.bot.quit();
    return `${MODE === "real" ? "the judge ruled ban; " : "the ban named Erin as the account; "}ack "${ban.detail}";`
      + ` entry ecc-${ban.id} written under Alice${after}; Alice's rejoin refused (${refused}...); Erin logged in`;
  });
}

// ---- the real server only: the allowlist of links, trust while the server is down, the judge's ban, the budget
// and a restart

async function realScenarios({ alice, bob, carol, dave }: Bots) {
  await scenario("an allowlisted link is delivered; the same line with a link not on the list is held", async () => {
    const pairs = [["come see www.rigchat.example/rules now", "come see rigchat.example.evil.test/rules now"],
      ["join talk.rigvoice.example/j/Abc123 now", "join talk.rigvoice.example/j/Zzz999 now"]];
    const delays: number[] = [];
    for (const [allowed, other] of pairs) {
      let since = Date.now();
      alice.say(allowed);
      const heard = await bob.hears(allowed, 3000, since);
      expect(heard, `Bob did not hear "${allowed}" within 3 s`);
      delays.push(heard.at - since);
      since = Date.now();
      alice.say(other);
      // The first held line opens Alice's sitting and the judge releases it about 6 s later, so the second line,
      // written about 3 s after the first, is checked as held for 2 s only.
      await heldFor(alice, bob, other, since, 2000);
    }
    const words = (await real.exec("cat", "/rig/holdwords.txt")).split("\n").filter(Boolean);
    return `hold words ${words.join(", ")}: Bob heard the domain's and the exact URL's lines after ${delays.join(" and ")}`
      + " ms; the lines with a lookalike host and another path were held";
  });

  await scenario("trusted-group player is delivered at once while ECC-Server is stopped", async () => {
    await real.stop();
    const since = Date.now();
    dave.say("holdme trusted while down");
    const heard = await bob.hears("holdme trusted while down", 1500, since);
    expect(heard, "Bob did not hear Dave's line within 1.5 s");
    return `ECC-Server's container stopped; Bob heard Dave's line after ${heard.at - since} ms`;
  });

  await scenario("bypass player is delivered at once while ECC-Server is stopped", async () => {
    const since = Date.now();
    carol.say("holdme bypass while down");
    const heard = await bob.hears("holdme bypass while down", 1500, since);
    expect(heard, "Bob did not hear Carol's line within 1.5 s");
    return `Bob heard Carol's line after ${heard.at - since} ms`;
  });

  await scenario("an untrusted line is held while ECC-Server is stopped and delivered once it is back", async () => {
    const since = Date.now();
    bob.say("clean while the server is down");
    await heldFor(bob, dave, "clean while the server is down", since, 4000);
    await real.resume();
    const back = Date.now();
    const heard = await dave.hears("clean while the server is down", 45_000, since);
    expect(heard, "Dave never heard Bob's line after ECC-Server came back");
    // Each sender tries the server again at least every 5 s, a try fails within the 2 s connect timeout, and every
    // sender wakes when one finds the server answering.
    expect(heard.at - back <= 8000, `Dave heard Bob's line ${heard.at - back} ms after ECC-Server was ready again`);
    // The module resends the trusted lines once the server answers.
    let trusted: any[] = [];
    for (const until = Date.now() + 15_000; Date.now() < until; await Bun.sleep(1000)) {
      const record = await real.record();
      trusted = ["holdme trusted while down", "holdme bypass while down"].map((text) =>
        record.find((line) => line.type === "line" && line.text === text));
      if (trusted.every(Boolean)) break;
    }
    expect(trusted.every((line) => line?.context === true && !line.sitting && line.verdict === "deliver"),
      `the trusted lines as recorded: ${JSON.stringify(trusted)}`);
    return `held with no answer; ECC-Server started again; Dave heard it ${heard.at - back} ms after it was ready; Dave's and`
      + " Carol's lines reached the server afterwards and are recorded as context, in no sitting";
  });

  await banScenario({ alice, bob, carol, dave });

  await scenario("the judge's budget notice reaches staff", async () => {
    let since = Date.now();
    let mark = await side.mark();
    bob.say("holdme costly nineteen");
    await heldFor(bob, dave, "holdme costly nineteen", since);
    await side.release(bob, ["holdme costly nineteen"], mark);
    since = Date.now();
    mark = await side.mark();
    bob.say("holdme over budget nineteen");
    await heldFor(bob, dave, "holdme over budget nineteen", since);
    const notice = await carol.hears("budget of", 30_000, since);
    expect(notice, "Carol, who holds plex.ecc.alerts, saw no budget notice");
    expect(!bob.heard.some((line) => line.at >= since && line.text.includes("budget of")),
      "Bob, who is not staff, saw the notice");
    const sent = (await real.record()).slice(mark).find((line) => line.type === "action_sent"
      && line.action === "notice");
    expect(sent?.kind === "budget", `the record has no budget notice: ${JSON.stringify(sent)}`);
    return `the fake reported the costly call's usage past the $1 day; the next checkpoint was refused and Carol saw`
      + ` "${notice.text}"`;
  });

  await scenario("after a restart mid-sitting the module sends start, joins and the held line again", async () => {
    const before = (await real.queue()).length;
    const mark = await side.mark();
    await real.restart();
    const since = Date.now();
    const queue = await real.queue();
    const entry = queue.slice(before).find((line) => line.account === bob.uuid);
    expect(entry, `no review entry for Bob after the restart: ${JSON.stringify(queue.slice(before))}`);
    let after: any[] = [];
    let resent: any;
    for (const until = Date.now() + 20_000; Date.now() < until; await Bun.sleep(1000)) {
      after = (await real.record()).slice(mark);
      resent = after.find((line) => line.type === "line" && line.raw === "holdme over budget nineteen");
      if (resent) break;
    }
    const kinds = after.filter((line) => line.type === "presence")
      .map((line) => `${line.kind}${line.name ? ":" + line.name : ""}`);
    expect(kinds[0] === "start" && ["join:Bob", "join:Carol", "join:Dave"].every((kind) => kinds.includes(kind)),
      `presence after the restart: ${kinds}`);
    expect(resent?.verdict === "hold" && resent.account === bob.uuid
      && after.indexOf(resent) > after.findIndex((line) => line.type === "presence" && line.name === "Dave"),
      `the held line after the restart: ${JSON.stringify(resent)}`);
    expect(!(await dave.hears("holdme over budget nineteen", 8000, 0)), "the held line was delivered");
    return `review queue: "${entry.why}"; then presence ${kinds.join(", ")} and the held line again, held (the day's`
      + ` budget is used up); Dave did not hear it ${Date.now() - since} ms after the restart`;
  });

  // The module sends a module channel outside the vocabulary under its key; no module in the rig fires one, so the
  // line is posted as the module would send it.
  await scenario("a line on a channel outside the vocabulary is held and counted in /status", async () => {
    const body = JSON.stringify({ id: "rig-unknown-channel", channel: "rigmodule:chat", speaker_uuid: dave.uuid,
      speaker: "Dave", target_uuid: null, target: null, text: "clean unknown channel line", trusted: false });
    const answer = await real.request("POST", "/line", real.token, body);
    expect(answer.includes('"hold"'), `ECC-Server answered ${answer}`);
    const status = await real.request("GET", "/status");
    expect(/"unknown_channels":\{[^}]*"rigmodule:chat":1/.test(status), `status: ${status.slice(0, 600)}`);
    return `answered ${answer.trim()}; /status counts {"rigmodule:chat":1}`;
  });

  await scenario("presence and line fields reach the server", async () => {
    const record = await real.record();
    const presence = record.filter((line) => line.type === "presence");
    const kinds = presence.map((entry) => `${entry.kind}${entry.name ? ":" + entry.name : ""}`);
    expect(kinds[0] === "start", `first presence is not start: ${kinds}`);
    expect(JSON.stringify(presence[0].allowed_links) === JSON.stringify(ALLOWED_LINKS),
      `start's allowed_links: ${JSON.stringify(presence[0].allowed_links)}`);
    for (const expected of ["join:Alice", "join:Bob", "join:Carol", "join:Dave", "leave:Alice"]) {
      expect(kinds.includes(expected), `no ${expected} in ${kinds}`);
    }
    const line = record.find((entry) => entry.type === "line" && entry.text === "clean public line one");
    expect(line?.channel === "chat" && line.name === "Alice" && line.account === alice.uuid && !line.context
      && line.verdict === "deliver", `the record's line: ${JSON.stringify(line)}`);
    const links = await real.exec("cat", "/data/links.json");
    expect(links.trim() === JSON.stringify(ALLOWED_LINKS), `links.json: ${links}`);
    return `presence ${kinds.join(", ")}; the allowlist saved as ${links.trim()}`;
  });
}

// ---------------------------------------------------------------- main

async function main() {
  if (MODE === "real") {
    console.log("building ECC-Server and the fake Bedrock for Linux");
    await real.build();
  }
  console.log(`building the module (mode: ${MODE})`);
  await run([process.platform === "win32" ? join(repo, "gradlew.bat") : "./gradlew", "build", "-q",
    "--include-build", PLEX_DIR]);
  await fetchPlugins();
  console.log("starting ECC-Server's side and the server (the first start downloads Paper)");
  await startContainers();
  const players: Player[] = [];
  try {
    await rcon("lp creategroup tester");
    await rcon(`lp creategroup ${TRUSTED_GROUP}`);
    // No essentials.msg.url: EssentialsX then breaks links apart in the message it carries, which the module must not
    // send as the line's text.
    for (const permission of ["essentials.msg", "essentials.me", "essentials.nick", "essentials.nick.others",
      "essentials.mail", "essentials.mail.send", "essentials.itemname", "essentials.itemlore", "plex.guilds.guild",
      "plex.guilds.create", "plex.guilds.invite", "plex.guilds.accept", "plex.guilds.chat", "plex.tag"]) {
      await rcon(`lp group default permission set ${permission} true`);
    }
    // Staff alerts, which quote held lines, go to players who hold the permission when they join: Carol, not Bob.
    await rcon(`lp user ${offlineUuid("Carol")} permission set plex.ecc.alerts true`);
    const alice = await Player.join("Alice");
    const bob = await Player.join("Bob");
    const carol = await Player.join("Carol");
    const dave = await Player.join("Dave");
    players.push(alice, bob, carol, dave);
    await rcon("lp user Alice parent add tester");
    await rcon("lp user Carol permission set plex.ecc.chat.bypass true");
    // Alice, who is checked, and Carol, who is trusted, force lines onto Bob.
    await rcon("lp user Alice permission set essentials.sudo true");
    await rcon("lp user Carol permission set essentials.sudo true");
    for (const permission of ["essentials.mail.sendtemp", "essentials.mail.sendall"]) {
      await rcon(`lp user Alice permission set ${permission} true`);
    }
    await rcon(`lp user Dave parent add ${TRUSTED_GROUP}`);
    await rcon("tp Alice 0.5 -60 0.5");
    await rcon("tp Bob 0.5 -60 4.5");
    await rcon("tp Carol 0.5 -60 6.5");
    await rcon("tp Dave 0.5 -60 8.5");
    await Bun.sleep(2000);
    const bots = { alice, bob, carol, dave };
    await lineScenarios(bots);
    await (MODE === "stub" ? stubScenarios(bots) : realScenarios(bots));
  } finally {
    players.forEach((player) => player.bot.quit());
    const logs = await run(["podman", "logs", SERVER], { check: false });
    writeFileSync(join(work, "server.log"), logs);
    // The log file as Paper writes it, which the training pipeline reads.
    writeFileSync(join(work, "latest.log"), await run(["podman", "exec", SERVER, "cat", "/data/logs/latest.log"],
      { check: false }));
    if (MODE === "stub") {
      writeFileSync(join(work, "stub.json"), JSON.stringify({
        lines: await stub("/stub/lines").catch(() => null), presence: await stub("/stub/presence").catch(() => null),
        acks: await stub("/stub/acks").catch(() => null),
      }, null, 2));
    } else {
      writeFileSync(join(work, "real.json"), JSON.stringify({ record: await real.record().catch(() => null),
        queue: await real.queue().catch(() => null), calls: await real.calls().catch(() => null) }, null, 2));
      writeFileSync(join(work, "ecc-server.log"), await real.logs());
    }
    if (!process.env.KEEP) await removeContainers();
  }
  const failed = results.filter((result) => !result.pass).length;
  console.log(`\n${results.length - failed} passed, ${failed} failed (mode: ${MODE}). Server log: e2e/work/server.log`);
  process.exit(failed === 0 ? 0 : 1);
}

await main();
