package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kimi K3 on Amazon Bedrock through the Converse REST API, authenticated with a Bedrock API key (bearer token).
 * {@link #ask} is redteam.py's {@code Kimi.ask}: up to three retries, two, four and eight seconds apart; an API error,
 * a refusal, the token cap or an answer that fails the evidence check is retried, and a call that still fails is
 * reported as not ok.
 */
final class Bedrock
{
    static final int RETRIES = 3;
    private static final int MAX_TOKENS = 8000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // redteam.py's read timeout.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Set<String> REFUSALS = Set.of("content_filtered", "guardrail_intervened");
    private static final int ERROR_DETAIL = 200;

    private final URI endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    Bedrock(String region, String model, String token)
    {
        this.endpoint = URI.create("https://bedrock-runtime." + region + ".amazonaws.com/model/"
                + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20") + "/converse");
        this.token = token;
    }

    /**
     * The outcome of one evidence call. {@code answer} is null when the call failed. {@code reached} is true when
     * Bedrock answered any attempt, {@code rejectedKey} when it refused the API key (HTTP 401 or 403).
     */
    record Call(@Nullable Map<String, Evidence.Reported> answer, String raw, int attempts, List<String> errors,
                long tokensIn, long tokensOut, boolean reached, boolean rejectedKey)
    {
        boolean ok()
        {
            return answer != null;
        }
    }

    Call ask(String system, String user, List<String> players) throws InterruptedException
    {
        int attempts = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        boolean reached = false;
        boolean rejectedKey = false;
        List<String> errors = new ArrayList<>();
        Map<String, Evidence.Reported> answer = null;
        String raw = "";
        for (int attempt = 0; attempt <= RETRIES; attempt++)
        {
            attempts++;
            if (attempt > 0)
            {
                Thread.sleep(Duration.ofSeconds(1L << attempt));
            }
            HttpResponse<String> response;
            try
            {
                response = client.send(request(system, user), HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException ex)
            {
                errors.add("api: " + ex.getClass().getSimpleName() + ": " + shorten(String.valueOf(ex.getMessage())));
                continue;
            }
            if (response.statusCode() != 200)
            {
                rejectedKey |= response.statusCode() == 401 || response.statusCode() == 403;
                errors.add("api: HTTP " + response.statusCode() + ": " + shorten(response.body()));
                continue;
            }
            reached = true;
            JsonObject body;
            try
            {
                body = JsonParser.parseString(response.body()).getAsJsonObject();
            }
            catch (JsonParseException | IllegalStateException ex)
            {
                errors.add("api: invalid response: " + shorten(response.body()));
                continue;
            }
            JsonObject usage = body.get("usage") instanceof JsonObject object ? object : new JsonObject();
            tokensIn += count(usage, "inputTokens") + count(usage, "cacheReadInputTokens")
                    + count(usage, "cacheWriteInputTokens");
            tokensOut += count(usage, "outputTokens");
            String stop = body.get("stopReason") instanceof JsonPrimitive reason ? reason.getAsString() : null;
            raw = text(body);
            if (stop != null && REFUSALS.contains(stop) || Evidence.strip(raw).isEmpty())
            {
                errors.add("refused: " + stop);
                continue;
            }
            if ("max_tokens".equals(stop))
            {
                errors.add("token cap");
                continue;
            }
            try
            {
                answer = Evidence.evidenceCheck(Evidence.parseAnswer(raw), players);
                break;
            }
            catch (Evidence.Unparseable ex)
            {
                errors.add("unparseable: " + ex.getMessage());
            }
        }
        return new Call(answer, raw, attempts, List.copyOf(errors), tokensIn, tokensOut, reached, rejectedKey);
    }

    void close()
    {
        client.shutdownNow();
    }

    private HttpRequest request(String system, String user)
    {
        JsonObject body = new JsonObject();
        JsonArray systemBlocks = new JsonArray();
        systemBlocks.add(textBlock(system));
        body.add("system", systemBlocks);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        content.add(textBlock(user));
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);
        body.add("messages", messages);
        JsonObject inference = new JsonObject();
        inference.addProperty("maxTokens", MAX_TOKENS);
        body.add("inferenceConfig", inference);
        return HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static JsonObject textBlock(String text)
    {
        JsonObject block = new JsonObject();
        block.addProperty("text", text);
        return block;
    }

    // output.message.content[].text, joined; blocks without text (such as reasoning) add nothing.
    private static String text(JsonObject body)
    {
        StringBuilder out = new StringBuilder();
        if (body.get("output") instanceof JsonObject output && output.get("message") instanceof JsonObject message
                && message.get("content") instanceof JsonArray content)
        {
            for (JsonElement block : content)
            {
                if (block instanceof JsonObject object && object.get("text") instanceof JsonPrimitive text)
                {
                    out.append(text.getAsString());
                }
            }
        }
        return out.toString();
    }

    private static long count(JsonObject usage, String key)
    {
        return usage.get(key) instanceof JsonPrimitive number && number.isNumber() ? number.getAsLong() : 0;
    }

    private static String shorten(String text)
    {
        return text.length() <= ERROR_DETAIL ? text : text.substring(0, ERROR_DETAIL);
    }
}
