package dev.plex.ecc.safety;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

public record SafetySettings(String server, URI modelHost, int queueSize, int recordRetentionDays)
{
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    // Returns null after adding a line to errors for each invalid setting.
    @Nullable
    public static SafetySettings read(ConfigurationSection section, List<String> errors)
    {
        int before = errors.size();
        String server = section.getString("server", "");
        if (!SERVER_NAME.matcher(server).matches())
        {
            errors.add("chat_safety.server '" + server + "' must be 1 to 32 lowercase letters, digits, - or _");
        }
        URI modelHost = modelHost(section.getString("model_host", ""), errors);
        int queueSize = section.getInt("queue_size", 0);
        if (queueSize < 1)
        {
            errors.add("chat_safety.queue_size '" + section.get("queue_size") + "' must be at least 1");
        }
        int retention = section.getInt("record_retention_days", 0);
        if (retention < 1)
        {
            errors.add("chat_safety.record_retention_days '" + section.get("record_retention_days")
                    + "' must be at least 1");
        }
        return errors.size() == before ? new SafetySettings(server, modelHost, queueSize, retention) : null;
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
