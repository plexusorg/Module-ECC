package dev.plex.ecc.safety;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * The model host's HTTP interface (ECC-Server, README "HTTP interface"). Every shape it exchanges lives here.
 */
final class ModelHost
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // Longer than one llama-server call may take on the host (two minutes). A score that still runs past it counts as
    // having no score, since the host finishes it and adds the line to the window anyway.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final URI base;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    ModelHost(URI base)
    {
        this.base = base;
    }

    /**
     * Appends the message to its conversation's window on the host and scores it.
     *
     * @throws Unavailable the host is down or not ready, or llama-server failed; the message was not added to the
     *                     window, so send it again
     * @throws NoScore     the message may be in the window but has no score; do not send it again
     */
    Verdict.Score score(String conversation, String speaker, String text) throws Unavailable, NoScore, InterruptedException
    {
        JsonObject body = new JsonObject();
        body.addProperty("conversation", conversation);
        body.addProperty("speaker", speaker);
        body.addProperty("text", text);
        HttpResponse<String> response;
        try
        {
            response = send(request("/score")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        }
        catch (HttpTimeoutException ex)
        {
            // The host finishes a request it already started and adds the line, so a resend would add it twice.
            throw new NoScore("did not answer within " + REQUEST_TIMEOUT.toMinutes() + " minutes", true);
        }
        try
        {
            switch (response.statusCode())
            {
                case 200 ->
                {
                    JsonObject json = parse(response, "/score");
                    return new Verdict.Score(probability(json, "p_ban"), number(json, "margin"), text(json, "version"));
                }
                case 422, 400 -> throw new NoScore("answered " + response.statusCode() + ": " + response.body(), false);
                default -> throw new Unavailable("answered " + response.statusCode() + " to /score: " + response.body());
            }
        }
        catch (Invalid ex)
        {
            throw new NoScore("returned an invalid score: " + ex.getMessage(), false);
        }
    }

    /**
     * Reads the served bundle's version, decision parameters and slow-check system prompt.
     *
     * @throws Unavailable the host is down or not ready, or serves parameters this module cannot apply
     */
    Decision decision() throws Unavailable, InterruptedException
    {
        HttpResponse<String> response = sendOrUnavailable(request("/bundle").GET());
        if (response.statusCode() != 200)
        {
            throw new Unavailable("answered " + response.statusCode() + " to /bundle: " + response.body());
        }
        try
        {
            JsonObject json = parse(response, "/bundle");
            if (!(json.get("decision") instanceof JsonObject parameters))
            {
                throw new Invalid("/bundle has no decision object");
            }
            if (!(json.get("slow_check") instanceof JsonObject slowCheck))
            {
                throw new Invalid("/bundle has no slow_check object");
            }
            String rule = text(parameters, "window_rule");
            if (!Decision.SINGLE.equals(rule))
            {
                throw new Unavailable("serves window rule '" + rule + "', which this module does not apply");
            }
            return new Decision(text(json, "version"), rule, probability(parameters, "trigger"),
                    probability(parameters, "clearing"), probability(parameters, "corroboration"),
                    text(slowCheck, "system"));
        }
        catch (Invalid ex)
        {
            throw new Unavailable("serves invalid decision parameters: " + ex.getMessage());
        }
    }

    /**
     * Checks that the host is ready to score.
     *
     * @throws Unavailable the host is down or not ready
     */
    void health() throws Unavailable, InterruptedException
    {
        HttpResponse<String> response = sendOrUnavailable(request("/health").GET());
        if (response.statusCode() != 200)
        {
            throw new Unavailable("is not ready (" + response.statusCode() + "): " + response.body());
        }
    }

    void close()
    {
        client.shutdownNow();
    }

    private HttpRequest.Builder request(String path)
    {
        return HttpRequest.newBuilder(base.resolve(base.getPath() + path)).timeout(REQUEST_TIMEOUT);
    }

    // A request that never connected, or failed before an answer, did not reach the host's window.
    private HttpResponse<String> send(HttpRequest.Builder request)
            throws Unavailable, HttpTimeoutException, InterruptedException
    {
        try
        {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex)
        {
            if (ex instanceof HttpTimeoutException timeout && !(ex instanceof HttpConnectTimeoutException))
            {
                throw timeout;
            }
            throw new Unavailable("is unreachable: " + ex);
        }
    }

    private HttpResponse<String> sendOrUnavailable(HttpRequest.Builder request) throws Unavailable, InterruptedException
    {
        try
        {
            return send(request);
        }
        catch (HttpTimeoutException ex)
        {
            throw new Unavailable("did not answer within " + REQUEST_TIMEOUT.toMinutes() + " minutes");
        }
    }

    private static JsonObject parse(HttpResponse<String> response, String path) throws Invalid
    {
        try
        {
            if (JsonParser.parseString(response.body()) instanceof JsonObject json)
            {
                return json;
            }
            throw new Invalid(path + " did not return a JSON object");
        }
        catch (JsonParseException ex)
        {
            throw new Invalid(path + " returned invalid JSON: " + ex.getMessage());
        }
    }

    private static double number(JsonObject json, String key) throws Invalid
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble()))
        {
            throw new Invalid("'" + key + "' is missing or not a finite number");
        }
        return value.getAsDouble();
    }

    private static double probability(JsonObject json, String key) throws Invalid
    {
        double value = number(json, key);
        if (value < 0 || value > 1)
        {
            throw new Invalid("'" + key + "' is " + value + ", outside 0 to 1");
        }
        return value;
    }

    private static String text(JsonObject json, String key) throws Invalid
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())
        {
            throw new Invalid("'" + key + "' is missing or empty");
        }
        return value.getAsString();
    }

    /**
     * The host cannot score now. The message must be sent again later.
     */
    static final class Unavailable extends Exception
    {
        Unavailable(String reason)
        {
            super(reason);
        }
    }

    /**
     * The message may be in the host's window but has no usable score. {@code stalled} means the host took too long
     * to answer, so it is not scoring now either.
     */
    static final class NoScore extends Exception
    {
        private final boolean stalled;

        NoScore(String reason, boolean stalled)
        {
            super(reason);
            this.stalled = stalled;
        }

        boolean stalled()
        {
            return stalled;
        }
    }

    // A host answer that breaks the interface.
    private static final class Invalid extends Exception
    {
        Invalid(String reason)
        {
            super(reason);
        }
    }
}
