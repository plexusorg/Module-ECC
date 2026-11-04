package dev.plex.ecc.safety;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The model host's HTTP interface (ECC-Server, README "HTTP interface"). Every shape it exchanges lives here.
 */
final class ModelHost
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
     * @throws NoScore     the message was added to the window but has no score; do not send it again
     */
    Verdict.Score score(String conversation, String speaker, String text) throws Unavailable, NoScore, InterruptedException
    {
        JsonObject body = new JsonObject();
        body.addProperty("conversation", conversation);
        body.addProperty("speaker", speaker);
        body.addProperty("text", text);
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve(base.getPath() + "/score"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        switch (response.statusCode())
        {
            case 200 ->
            {
                JsonObject json = parse(response, "/score");
                double pBan = number(json, "p_ban");
                double margin = number(json, "margin");
                if (pBan < 0 || pBan > 1)
                {
                    throw new NoScore("the host returned p_ban " + pBan + ", outside 0 to 1");
                }
                return new Verdict.Score(pBan, margin, text(json, "version"));
            }
            case 422, 400 -> throw new NoScore("the host answered " + response.statusCode() + ": " + response.body());
            default -> throw new Unavailable("answered " + response.statusCode() + " to /score: " + response.body());
        }
    }

    /**
     * Reads the served bundle's version and decision parameters.
     *
     * @throws Unavailable the host is down or not ready, or serves parameters this module cannot apply
     */
    Decision decision() throws Unavailable, InterruptedException
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve(base.getPath() + "/bundle")).GET());
        if (response.statusCode() != 200)
        {
            throw new Unavailable("answered " + response.statusCode() + " to /bundle: " + response.body());
        }
        try
        {
            JsonObject json = parse(response, "/bundle");
            JsonElement decision = json.get("decision");
            if (decision == null || !decision.isJsonObject())
            {
                throw new NoScore("/bundle has no decision object");
            }
            JsonObject parameters = decision.getAsJsonObject();
            String rule = text(parameters, "window_rule");
            if (!Decision.SINGLE.equals(rule))
            {
                throw new Unavailable("serves window rule '" + rule + "', which this module does not apply");
            }
            return new Decision(text(json, "version"), rule, threshold(parameters, "trigger"),
                    threshold(parameters, "clearing"), threshold(parameters, "corroboration"));
        }
        catch (NoScore invalid)
        {
            throw new Unavailable("serves invalid decision parameters: " + invalid.getMessage());
        }
    }

    /**
     * Checks that the host is ready to score.
     *
     * @throws Unavailable the host is down or not ready
     */
    void health() throws Unavailable, InterruptedException
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve(base.getPath() + "/health")).GET());
        if (response.statusCode() != 200)
        {
            throw new Unavailable("is not ready (" + response.statusCode() + "): " + response.body());
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Unavailable, InterruptedException
    {
        try
        {
            return client.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex)
        {
            throw new Unavailable("is unreachable: " + ex);
        }
    }

    private static JsonObject parse(HttpResponse<String> response, String path) throws NoScore
    {
        try
        {
            JsonElement json = JsonParser.parseString(response.body());
            if (!json.isJsonObject())
            {
                throw new NoScore(path + " did not return a JSON object");
            }
            return json.getAsJsonObject();
        }
        catch (JsonParseException ex)
        {
            throw new NoScore(path + " returned invalid JSON: " + ex.getMessage());
        }
    }

    private static double number(JsonObject json, String key) throws NoScore
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble()))
        {
            throw new NoScore("'" + key + "' is missing or not a finite number");
        }
        return value.getAsDouble();
    }

    private static double threshold(JsonObject json, String key) throws NoScore
    {
        double value = number(json, key);
        if (value < 0 || value > 1)
        {
            throw new NoScore("'" + key + "' is " + value + ", outside 0 to 1");
        }
        return value;
    }

    private static String text(JsonObject json, String key) throws NoScore
    {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())
        {
            throw new NoScore("'" + key + "' is missing or empty");
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
     * The message reached the host's window but has no usable score.
     */
    static final class NoScore extends Exception
    {
        NoScore(String reason)
        {
            super(reason);
        }
    }
}
