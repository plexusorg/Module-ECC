package dev.plex.ecc.safety;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

public record SafetySettings(URI eccServer)
{
    // Returns null after adding a line to errors for each invalid setting.
    @Nullable
    public static SafetySettings read(ConfigurationSection section, List<String> errors)
    {
        URI eccServer = eccServer(section.getString("model_host", ""), errors);
        return eccServer == null ? null : new SafetySettings(eccServer);
    }

    @Nullable
    private static URI eccServer(String value, List<String> errors)
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
