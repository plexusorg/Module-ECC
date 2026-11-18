package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.plex.api.logging.LoggingApi;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ECC-Server's HTTP interface, and whether it answers. The verdicts, the presence sender and the action loop share
 * this link; the first failure after a working connection logs one warning, and the first answer after it logs one
 * more.
 */
final class EccServer
{
    // The bounds of the doubling wait before a sender tries an unreachable server again. The last bounds how long a
    // held line waits after the server comes back while nothing else reaches it.
    static final long FIRST_RETRY_MILLIS = 1000;
    static final long LAST_RETRY_MILLIS = 5000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // GET /actions holds the request for up to 30 seconds while there is nothing to do.
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(45);
    private static final int BODY_LIMIT = 300;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final URI base;
    private final String authorization;
    private final LoggingApi logger;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final Object lock = new Object();
    // Guarded by lock.
    private State state = new State(null, "not contacted yet", Instant.now());
    // Woken when a request finds the server answering after it did not.
    private final List<Backoff> backoffs = new CopyOnWriteArrayList<>();
    // Guarded by lock. The boot id of the server's last answer that carried one; null before the first.
    private String boot;
    // Guarded by lock. The boot id for which the server acknowledged a start; null until it has.
    private String announced;
    // Guarded by lock. The boot id whose announcement `restarted` was called for and has not been acknowledged yet.
    private String announcing;
    private volatile Runnable restarted = () ->
    {
    };

    EccServer(URI base, String token, LoggingApi logger)
    {
        this.base = base;
        this.authorization = "Bearer " + token;
        this.logger = logger;
    }

    /**
     * POST /line: the verdict on one line, completed on the HTTP client's thread. Completes with NONE when the server
     * does not answer within the timeout or answers anything but a verdict.
     */
    CompletableFuture<Verdict> line(JsonObject line, Duration timeout)
    {
        HttpRequest request = post("/line", line, timeout).header("Authorization", authorization).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) ->
                {
                    if (failure != null)
                    {
                        down("is unreachable: " + failure, false);
                        return Verdict.NONE;
                    }
                    try
                    {
                        check(response);
                        up();
                        if (parse(response, "/line") instanceof JsonObject json
                                && json.get("verdict") instanceof JsonElement verdict && verdict.isJsonPrimitive())
                        {
                            Verdict parsed = Verdict.of(verdict.getAsString());
                            if (parsed != Verdict.NONE)
                            {
                                return parsed;
                            }
                        }
                        logger.error("ECC-Server answered /line without a verdict: {0}",
                                MINI_MESSAGE.escapeTags(body(response)));
                    }
                    catch (Unavailable ex)
                    {
                        down(ex.getMessage(), ex.unauthorized);
                    }
                    catch (Rejected ex)
                    {
                        logger.error("ECC-Server refused line {0}: it {1}",
                                MINI_MESSAGE.escapeTags(String.valueOf(line.get("id"))),
                                MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
                    }
                    return Verdict.NONE;
                });
    }

    /**
     * POST /line for a line whose verdict is not used: completes with false when the server is unreachable or cannot
     * work now, so the line is to be sent again, and with true when it answered, a refusal included, which is logged.
     */
    CompletableFuture<Boolean> send(JsonObject line)
    {
        HttpRequest request = post("/line", line, REQUEST_TIMEOUT).header("Authorization", authorization).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) ->
                {
                    if (failure != null)
                    {
                        down("is unreachable: " + failure, false);
                        return false;
                    }
                    try
                    {
                        check(response);
                        up();
                    }
                    catch (Unavailable ex)
                    {
                        down(ex.getMessage(), ex.unauthorized);
                        return false;
                    }
                    catch (Rejected ex)
                    {
                        logger.error("ECC-Server refused line {0}, which is not sent again: it {1}",
                                MINI_MESSAGE.escapeTags(String.valueOf(line.get("id"))),
                                MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
                    }
                    return true;
                });
    }

    // A POST whose answer the plugin only reads for the boot id: /presence, and /line for a line sent again.
    void post(String path, JsonObject body) throws Unavailable, Rejected, InterruptedException
    {
        HttpResponse<String> response = send(post(path, body, REQUEST_TIMEOUT));
        try
        {
            booted(JsonParser.parseString(response.body()));
        }
        catch (JsonParseException ex)
        {
            // An answer that is not JSON carries no boot id.
        }
    }

    // GET /actions: every unacknowledged action, oldest first, held open for up to 30 seconds while there is none.
    JsonArray actions() throws Unavailable, Rejected, InterruptedException
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actions")).timeout(POLL_TIMEOUT).GET());
        if (parse(response, "/actions") instanceof JsonObject answer && answer.get("actions") instanceof JsonArray array)
        {
            booted(answer);
            return array;
        }
        throw new Rejected("did not return {boot, actions} for /actions");
    }

    // GET /status, read for the boot id only: it answers at once, where GET /actions holds a request for up to 30
    // seconds, so a poll that reaches a restarted server would learn its boot id that much later. A server without a
    // bundle answers 503 with the same body.
    void checkBoot() throws Unavailable, InterruptedException
    {
        HttpResponse<String> response;
        try
        {
            response = client.send(HttpRequest.newBuilder(uri("/status")).timeout(REQUEST_TIMEOUT)
                    .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex)
        {
            Unavailable unavailable = new Unavailable("is unreachable: " + ex);
            down(unavailable.getMessage(), false);
            throw unavailable;
        }
        if (response.statusCode() == 401)
        {
            Unavailable unauthorized = new Unavailable("answered 401: it did not accept chat_safety.token", true);
            down(unauthorized.getMessage(), true);
            throw unauthorized;
        }
        try
        {
            booted(JsonParser.parseString(response.body()));
        }
        catch (JsonParseException ex)
        {
            // An answer that is not JSON carries no boot id.
        }
    }

    // POST /presence for a start: the server's acknowledgement is what makes the plugin's announcement of the boot id
    // in its answer complete. The start is sent here and kept out of the outbox, which drops its oldest post when full.
    void start(JsonObject body) throws Unavailable, Rejected, InterruptedException
    {
        HttpResponse<String> response = send(post("/presence", body, REQUEST_TIMEOUT));
        String id = null;
        try
        {
            if (JsonParser.parseString(response.body()) instanceof JsonObject json
                    && json.get("boot") instanceof JsonPrimitive primitive && primitive.isString())
            {
                id = primitive.getAsString();
            }
        }
        catch (JsonParseException ex)
        {
            // An answer that is not JSON carries no boot id.
        }
        synchronized (lock)
        {
            if (id != null)
            {
                boot = id;
            }
            announced = boot;
            announcing = null;
        }
    }

    // Runs `restarted` on the thread that saw it each time ECC-Server answers with a boot id for which it has not
    // acknowledged a start: the first boot id the plugin sees, and each one after a restart. The server knows nothing
    // the plugin told it before.
    void onRestart(Runnable restarted)
    {
        this.restarted = restarted;
    }

    // POST /actions/ack.
    void ack(JsonElement id, boolean ok, String detail) throws Unavailable, Rejected, InterruptedException
    {
        JsonObject body = new JsonObject();
        body.add("id", id);
        body.addProperty("ok", ok);
        body.addProperty("detail", detail);
        send(post("/actions/ack", body, REQUEST_TIMEOUT));
    }

    State state()
    {
        synchronized (lock)
        {
            return state;
        }
    }

    // A sender's wait before it tries again, which ends as soon as any request finds the server answering again.
    Backoff backoff()
    {
        Backoff backoff = new Backoff();
        backoffs.add(backoff);
        return backoff;
    }

    void close()
    {
        client.shutdownNow();
    }

    private HttpRequest.Builder post(String path, JsonObject body, Duration timeout)
    {
        return HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private URI uri(String path)
    {
        return base.resolve(base.getPath() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Unavailable, Rejected, InterruptedException
    {
        HttpResponse<String> response;
        try
        {
            response = client.send(request.header("Authorization", authorization).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex)
        {
            Unavailable unavailable = new Unavailable("is unreachable: " + ex);
            down(unavailable.getMessage(), false);
            throw unavailable;
        }
        try
        {
            check(response);
        }
        catch (Unavailable ex)
        {
            down(ex.getMessage(), ex.unauthorized);
            throw ex;
        }
        up();
        return response;
    }

    // A server error means it is not working now; any other answer outside 2xx rejects this request.
    private static void check(HttpResponse<String> response) throws Unavailable, Rejected
    {
        int status = response.statusCode();
        if (status == 401)
        {
            throw new Unavailable("answered 401: it did not accept chat_safety.token", true);
        }
        if (status >= 500)
        {
            throw new Unavailable("answered " + status + " to " + response.request().uri().getPath() + ": "
                    + body(response));
        }
        if (status < 200 || status >= 300)
        {
            throw new Rejected("answered " + status + " to " + response.request().uri().getPath() + ": "
                    + body(response));
        }
    }

    private void up()
    {
        boolean returned;
        synchronized (lock)
        {
            if (Boolean.TRUE.equals(state.reachable()))
            {
                return;
            }
            returned = Boolean.FALSE.equals(state.reachable());
            state = new State(true, "answering", Instant.now());
        }
        backoffs.forEach(Backoff::wake);
        if (returned)
        {
            logger.warn("ECC-Server at {0} answers again; chat safety asks it again about the lines it holds",
                    MINI_MESSAGE.escapeTags(String.valueOf(base)));
        }
    }

    // Logs once per outage; a rejected token is logged again if it follows another failure in the same outage.
    private void down(String reason, boolean unauthorized)
    {
        synchronized (lock)
        {
            if (Boolean.FALSE.equals(state.reachable()))
            {
                boolean log = unauthorized && !reason.equals(state.detail());
                state = new State(false, reason, state.since());
                if (!log)
                {
                    return;
                }
            }
            else
            {
                state = new State(false, reason, Instant.now());
            }
        }
        if (unauthorized)
        {
            logger.error("ECC-Server at {0} {1}; set chat_safety.token to the output of 'sudo ecc-server token'."
                    + " Until the server accepts it, every line is held", MINI_MESSAGE.escapeTags(String.valueOf(base)),
                    MINI_MESSAGE.escapeTags(String.valueOf(reason)));
            return;
        }
        logger.warn("ECC-Server at {0} {1}; every line is held until it answers",
                MINI_MESSAGE.escapeTags(String.valueOf(base)), MINI_MESSAGE.escapeTags(String.valueOf(reason)));
    }

    // Notes the boot id an answer carries, and runs `restarted` when no start has been acknowledged for it.
    private void booted(JsonElement answer)
    {
        if (!(answer instanceof JsonObject json) || !(json.get("boot") instanceof JsonPrimitive id) || !id.isString())
        {
            return;
        }
        String previous;
        synchronized (lock)
        {
            previous = boot;
            boot = id.getAsString();
            if (boot.equals(announced) || boot.equals(announcing))
            {
                return;
            }
            announcing = boot;
        }
        if (previous != null && !previous.equals(id.getAsString()))
        {
            logger.warn("ECC-Server at {0} started again; chat safety sends it who is online and the lines it holds",
                    MINI_MESSAGE.escapeTags(String.valueOf(base)));
        }
        restarted.run();
    }

    private static JsonElement parse(HttpResponse<String> response, String path) throws Rejected
    {
        try
        {
            return JsonParser.parseString(response.body());
        }
        catch (JsonParseException ex)
        {
            throw new Rejected("returned invalid JSON for " + path + ": " + ex.getMessage());
        }
    }

    private static String body(HttpResponse<String> response)
    {
        String body = response.body() == null ? "" : response.body().strip();
        return body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) + "..." : body;
    }

    /**
     * ECC-Server's answer about a line. NONE: no answer in time, or no valid answer.
     */
    enum Verdict
    {
        DELIVER, HOLD, REJECT, NONE;

        static Verdict of(String value)
        {
            return switch (value)
            {
                case "deliver" -> DELIVER;
                case "hold" -> HOLD;
                case "reject" -> REJECT;
                default -> NONE;
            };
        }
    }

    /**
     * The link as last seen. {@code reachable} is null before the first contact.
     */
    record State(@Nullable Boolean reachable, String detail, Instant since)
    {
    }

    /**
     * The server did not answer, or answered that it cannot work now. Send the request again later.
     */
    static final class Unavailable extends Exception
    {
        // The server answered 401: it did not accept the token.
        private final boolean unauthorized;

        Unavailable(String reason)
        {
            this(reason, false);
        }

        Unavailable(String reason, boolean unauthorized)
        {
            super(reason);
            this.unauthorized = unauthorized;
        }
    }

    /**
     * The server answered and refused the request, or answered with something outside the protocol. Sending the same
     * request again gets the same answer.
     */
    static final class Rejected extends Exception
    {
        Rejected(String reason)
        {
            super(reason);
        }
    }
}
