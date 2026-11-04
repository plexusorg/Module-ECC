package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * ECC-Server's HTTP interface, and whether it answers. The sender and the action loop share this link; the
 * first failure after a working connection logs one warning, and the first answer after it logs one more and asks the
 * action loop to replace the mute set from the server.
 */
final class EccServer
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // GET /actions holds the request for up to 30 seconds while there is nothing to do.
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(45);
    private static final int BODY_LIMIT = 300;

    private final URI base;
    private final String authorization;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final Object lock = new Object();
    // Guarded by lock.
    private State state = new State(null, "not contacted yet", Instant.now());
    // Guarded by lock. The mute set must be replaced from GET /muted: at start, and after every outage.
    private boolean refreshMuted = true;

    EccServer(URI base, String token, Logger logger)
    {
        this.base = base;
        this.authorization = "Bearer " + token;
        this.logger = logger;
    }

    // POST /messages; the server answers once it has queued the message, and a resend of the same id is harmless.
    void postMessage(JsonObject message) throws Unavailable, Rejected, InterruptedException
    {
        send(post("/messages", message, REQUEST_TIMEOUT));
    }

    // GET /actions: every unacknowledged action, oldest first, held open for up to 30 seconds while there is none.
    JsonArray actions() throws Unavailable, Rejected, InterruptedException
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actions")).timeout(POLL_TIMEOUT).GET());
        return response.statusCode() == 204 ? new JsonArray() : array(response, "/actions");
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

    // GET /muted: the current mute set as [{"uuid", "name"}].
    JsonArray muted() throws Unavailable, Rejected, InterruptedException
    {
        return array(send(HttpRequest.newBuilder(uri("/muted")).timeout(REQUEST_TIMEOUT).GET()), "/muted");
    }

    // GET /status, answered on the HTTP client's thread; it fails with Unavailable or Rejected.
    CompletableFuture<JsonObject> status()
    {
        HttpRequest request = HttpRequest.newBuilder(uri("/status")).timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionally(ex ->
                {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    throw new CompletionException(new Unavailable("is unreachable: " + cause));
                })
                .thenApply(response ->
                {
                    try
                    {
                        check(response);
                        if (parse(response, "/status") instanceof JsonObject json)
                        {
                            return json;
                        }
                        throw new Rejected("did not return a JSON object for /status");
                    }
                    catch (Unavailable | Rejected ex)
                    {
                        throw new CompletionException(ex);
                    }
                });
    }

    State state()
    {
        synchronized (lock)
        {
            return state;
        }
    }

    // True once after start and after each outage: the caller replaces the mute set, or calls refreshFailed.
    boolean takeRefresh()
    {
        synchronized (lock)
        {
            boolean refresh = refreshMuted;
            refreshMuted = false;
            return refresh;
        }
    }

    void refreshFailed()
    {
        synchronized (lock)
        {
            refreshMuted = true;
        }
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
            refreshMuted |= returned;
        }
        if (returned)
        {
            logger.warn("ECC-Server at {} answers again; chat safety sends the buffered messages and reads the mute"
                    + " set from it", base);
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
            logger.error("ECC-Server at {} {}; set chat_safety.token to the output of 'sudo ecc-server token'."
                    + " Chat safety buffers messages, and its saved mute set decides who is muted until the server"
                    + " accepts the token", base, reason);
            return;
        }
        logger.warn("ECC-Server at {} {}; chat safety buffers messages, and its saved mute set decides who is muted"
                + " until the server answers", base, reason);
    }

    private static JsonArray array(HttpResponse<String> response, String path) throws Rejected
    {
        if (parse(response, path) instanceof JsonArray array)
        {
            return array;
        }
        throw new Rejected("did not return a JSON array for " + path);
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
