package dev.plex.ecc.safety;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record SafetySettings(String server, URI modelHost, int queueSize, int recordRetentionDays,
                             String bedrockRegion, String bedrockModel, String bedrockToken, Duration kickMin,
                             Duration kickMax, Duration heldCap)
{
    static final String BEDROCK_TOKEN = "AWS_BEARER_TOKEN_BEDROCK";
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

    // Returns null after adding a line to errors for each invalid setting. Secrets come from the .env file.
    @Nullable
    public static SafetySettings read(ConfigurationSection section, Path env, List<String> errors)
    {
        int before = errors.size();
        String server = section.getString("server", "");
        if (!SERVER_NAME.matcher(server).matches())
        {
            errors.add("chat_safety.server '" + server + "' must be 1 to 32 lowercase letters, digits, - or _");
        }
        URI modelHost = modelHost(section.getString("model_host", ""), errors);
        int queueSize = atLeastOne(section, "queue_size", errors);
        int retention = atLeastOne(section, "record_retention_days", errors);
        String region = section.getString("bedrock.region", "");
        if (!REGION.matcher(region).matches())
        {
            errors.add("chat_safety.bedrock.region '" + region + "' must be an AWS region, such as us-east-1");
        }
        String model = section.getString("bedrock.model", "");
        if (model.isBlank())
        {
            errors.add("chat_safety.bedrock.model is empty");
        }
        String token = secrets(env, errors).getOrDefault(BEDROCK_TOKEN, "");
        if (token.isBlank())
        {
            errors.add(BEDROCK_TOKEN + " is missing from " + env);
        }
        int kickMin = atLeastOne(section, "kick_delay_minutes.min", errors);
        int kickMax = atLeastOne(section, "kick_delay_minutes.max", errors);
        if (kickMax < kickMin)
        {
            errors.add("chat_safety.kick_delay_minutes.max must be at least its min");
        }
        int cap = atLeastOne(section, "held_cap_minutes", errors);
        return errors.size() == before ? new SafetySettings(server, modelHost, queueSize, retention, region, model,
                token.strip(), Duration.ofMinutes(kickMin), Duration.ofMinutes(kickMax), Duration.ofMinutes(cap)) : null;
    }

    private static int atLeastOne(ConfigurationSection section, String key, List<String> errors)
    {
        int value = section.getInt(key, 0);
        if (value < 1)
        {
            errors.add("chat_safety." + key + " '" + section.get(key) + "' must be at least 1");
        }
        return value;
    }

    // KEY=value lines; blank lines and # comments are skipped, and a value may be quoted.
    private static Map<String, String> secrets(Path env, List<String> errors)
    {
        Map<String, String> values = new HashMap<>();
        List<String> lines;
        try
        {
            lines = Files.readAllLines(env, StandardCharsets.UTF_8);
        }
        catch (NoSuchFileException ex)
        {
            return values;
        }
        catch (IOException ex)
        {
            errors.add("unable to read " + env + ": " + ex.getMessage());
            return values;
        }
        for (String line : lines)
        {
            String text = line.strip();
            int equals = text.indexOf('=');
            if (text.isEmpty() || text.startsWith("#") || equals < 1)
            {
                continue;
            }
            String value = text.substring(equals + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'")))
            {
                value = value.substring(1, value.length() - 1);
            }
            values.put(text.substring(0, equals).strip(), value);
        }
        return values;
    }

    @Nullable
    private static URI modelHost(String value, List<String> errors)
    {
        try
        {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || !InetAddress.getByName(uri.getHost()).isLoopbackAddress())
            {
                errors.add("chat_safety.model_host '" + value + "' must be an http address with a port on this machine,"
                        + " such as http://127.0.0.1:8790");
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI("http", null, uri.getHost(), uri.getPort(), path, null, null);
        }
        catch (URISyntaxException | UnknownHostException ex)
        {
            errors.add("chat_safety.model_host '" + value + "' is not a valid address: " + ex.getMessage());
            return null;
        }
    }
}
