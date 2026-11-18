// Set ECC_SERVER_SRC to an ECC-Server checkout and FAKE_BEDROCK_SRC to a local fake Responses endpoint.
// Set ECC_REAL_FIXTURE to a private folder with bundle/, holdwords.txt, and answers.json.
// The test builds both Go programs and starts them on the test network.

import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { isAbsolute, join, relative, sep } from "node:path";

export const ECC = "ecc-e2e-ecc";
export const FAKE = "ecc-e2e-fake";
// The test network's subnet and ECC-Server's fixed addresses in it, so a stopped and started server is reachable at
// once, as a production server that keeps its address is. A started container otherwise gets a new IP address, which
// Java's name cache hides for up to 30 s, and with the IP address alone fixed, a new MAC address, which the other
// containers keep missing for about 20 s.
export const SUBNET = "10.89.231.0/24";
const ECC_IP = "10.89.231.10";
const ECC_MAC = "92:d0:c6:0a:29:0a";
const VOLUME = "ecc-e2e-ecc";
const IMAGE = "docker.io/library/alpine:3.24";
const SERVER_IMAGE = "docker.io/library/debian:bookworm-slim";
// Where the test reaches ECC-Server's HTTP interface.
const PUBLISHED_PORT = 18797;
const FAKE_KEY = "rig-fake-key";

type Run = (command: string[], options?: { cwd?: string; check?: boolean; env?: Record<string, string> }) =>
  Promise<string>;

export class RealServer {
  readonly host = ECC;
  token = "";
  private readonly folder: string;

  constructor(work: string, private readonly network: string,
    private readonly run: Run) {
    this.folder = join(work, "real");
  }

  // Builds both programs for Linux and lays out what the containers mount: bin, bundle (with its manifest),
  // holdwords.txt, answers.json, config.toml and the fake Bedrock key as a service credential.
  async build() {
    const server = process.env.ECC_SERVER_SRC;
    const fake = process.env.FAKE_BEDROCK_SRC;
    if (!server || !existsSync(join(server, "go.mod")) || !fake || !existsSync(join(fake, "go.mod"))) {
      throw new Error("set ECC_SERVER_SRC to an ECC-Server checkout and FAKE_BEDROCK_SRC to the fake Responses"
        + " endpoint's Go module");
    }
    const fixture = process.env.ECC_REAL_FIXTURE;
    if (!fixture || !["bundle/bundle.json", "holdwords.txt", "answers.json"].every((file) => existsSync(join(fixture, file)))) {
      throw new Error("set ECC_REAL_FIXTURE to a private folder with bundle/, holdwords.txt, and answers.json");
    }
    const fixturePath = realpathSync(fixture);
    const fromOutput = relative(this.folder, fixturePath);
    if (!fromOutput || (!isAbsolute(fromOutput) && fromOutput !== ".." && !fromOutput.startsWith(".." + sep))) {
      throw new Error("keep ECC_REAL_FIXTURE outside the generated e2e/work/real folder");
    }
    rmSync(this.folder, { recursive: true, force: true });
    mkdirSync(join(this.folder, "bin"), { recursive: true });
    mkdirSync(join(this.folder, "creds"), { recursive: true });
    const env = { GOOS: "linux", GOARCH: "amd64", CGO_ENABLED: "0" };
    await this.run(["go", "build", "-o", join(this.folder, "bin", "ecc-server"), "."], { cwd: server, env });
    await this.run(["go", "build", "-o", join(this.folder, "bin", "fakebedrock"), "."], { cwd: fake, env });
    const bundle = join(this.folder, "bundle");
    cpSync(join(fixturePath, "bundle"), bundle, { recursive: true });
    const files: Record<string, { sha256: string; bytes: number }> = {};
    for (const name of readdirSync(bundle)) {
      const data = readFileSync(join(bundle, name));
      files[name] = { sha256: createHash("sha256").update(data).digest("hex"), bytes: statSync(join(bundle, name)).size };
    }
    const version = JSON.parse(readFileSync(join(bundle, "bundle.json"), "utf8")).version;
    writeFileSync(join(bundle, "manifest.json"), JSON.stringify({ version, files }, null, 1));
    cpSync(join(fixturePath, "holdwords.txt"), join(this.folder, "holdwords.txt"));
    cpSync(join(fixturePath, "answers.json"), join(this.folder, "answers.json"));
    writeFileSync(join(this.folder, "creds", "ecc-server.bedrock-api-key"), FAKE_KEY);
    // The bundle's checkpoint is the waiting time alone (its schedule is never reached). The day's budget holds the
    // test's calls until one the fake reports as costly uses it up.
    writeFileSync(join(this.folder, "config.toml"), `listen = "0.0.0.0:8790"
data = "/data"
server = "e2e"

[judge]
service_tier = "flex"
max_output_tokens = 100
timeout = "30s"

[budget]
daily_usd = 1.0
monthly_usd = 150

[test]
hold_words_file = "/rig/holdwords.txt"
bedrock_endpoint = "http://${FAKE}:8791"
`);
  }

  // Starts the fake and the server with an empty data volume, and reads the server's token.
  async start() {
    await this.remove();
    await this.run(["podman", "volume", "create", VOLUME]);
    await this.run(["podman", "run", "-d", "--name", FAKE, "--network", this.network, "--memory", "128m",
      "-v", `${this.folder}:/rig:ro`, IMAGE, "/rig/bin/fakebedrock", "-addr", "0.0.0.0:8791", "-answers",
      "/rig/answers.json", "-log", "/tmp/calls.jsonl", "-key", FAKE_KEY, "-output-tokens", "50"]);
    await this.run(["podman", "run", "-d", "--name", ECC, "--network", this.network, "--ip", ECC_IP, "--mac-address", ECC_MAC,
      "--memory", "256m", "-p", `127.0.0.1:${PUBLISHED_PORT}:8790`,
      "--user", "1000:1000", "-v", `${VOLUME}:/data:U`, "-v", `${this.folder}:/rig:ro`,
      "-e", "CREDENTIALS_DIRECTORY=/rig/creds", SERVER_IMAGE, "sh", "-c",
      "/rig/bin/ecc-server use -config /rig/config.toml /rig/bundle"
      + " && exec /rig/bin/ecc-server run -config /rig/config.toml"]);
    await this.ready();
    this.token = (await this.exec("cat", "/data/token")).trim();
  }

  // Waits until the server answers its status as ready.
  async ready() {
    const until = Date.now() + 60_000;
    while (Date.now() < until) {
      const token = (await this.run(["podman", "exec", ECC, "cat", "/data/token"], { check: false })).trim();
      if (/^[0-9a-f]{64}$/.test(token)) {
        const status = await this.request("GET", "/status", token).catch(() => "");
        if (status.includes('"status":"ready"')) return;
      }
      await Bun.sleep(500);
    }
    throw new Error(`ECC-Server did not get ready:\n${await this.logs()}`);
  }

  // A request to the server's HTTP interface from the test, with the server's token; the answer's body.
  async request(method: string, path: string, token = this.token, body?: string): Promise<string> {
    const response = await fetch(`http://127.0.0.1:${PUBLISHED_PORT}${path}`, { method, body,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } });
    return response.text();
  }

  async stop() {
    await this.run(["podman", "stop", "-t", "5", ECC]);
  }

  async resume() {
    await this.run(["podman", "start", ECC]);
    await this.ready();
  }

  async restart() {
    await this.run(["podman", "restart", "-t", "5", ECC]);
    await this.ready();
  }

  async remove() {
    await this.run(["podman", "rm", "-f", "-t", "5", ECC, FAKE], { check: false });
    await this.run(["podman", "volume", "rm", "-f", VOLUME], { check: false });
  }

  exec(...command: string[]) {
    return this.run(["podman", "exec", ECC, ...command]);
  }

  logs() {
    return this.run(["podman", "logs", ECC], { check: false });
  }

  // Every record line so far, oldest first.
  async record(): Promise<any[]> {
    const out = await this.run(["podman", "exec", ECC, "sh", "-c", "cat /data/record/*.jsonl"], { check: false });
    return out.split("\n").filter((line) => line.startsWith("{")).map((line) => JSON.parse(line));
  }

  async queue(): Promise<any[]> {
    const out = await this.run(["podman", "exec", ECC, "cat", "/data/review/queue.jsonl"], { check: false });
    return out.split("\n").filter((line) => line.startsWith("{")).map((line) => JSON.parse(line));
  }

  async calls(): Promise<any[]> {
    const out = await this.run(["podman", "exec", FAKE, "cat", "/tmp/calls.jsonl"], { check: false });
    return out.split("\n").filter((line) => line.startsWith("{")).map((line) => JSON.parse(line));
  }

  // The first action of this type for this player that the server sent after record line `mark`, and the
  // plugin's acknowledgement of it, waiting up to `millis` for both.
  async action(type: string, uuid: string | null, mark: number, millis: number):
    Promise<{ sent: any; ack: any } | null> {
    const until = Date.now() + millis;
    while (Date.now() < until) {
      const record = (await this.record()).slice(mark);
      const sent = record.find((line) => line.type === "action_sent" && line.action === type
        && (uuid === null || line.uuid === uuid));
      const ack = sent && record.find((line) => line.type === "ack" && line.id === sent.id && line.ok);
      if (ack) return { sent, ack };
      await Bun.sleep(500);
    }
    return null;
  }
}
