package dev.plex.ecc.safety;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public record SafetySettings(URI eccServer, String token)
{
    // Returns null after adding a line to errors for each invalid setting.
    @Nullable
    public static SafetySettings read(ConfigurationSection section, List<String> errors)
    {
        URI eccServer = eccServer(section.getString("model_host", ""), errors);
        String token = section.getString("token", "").strip();
        if (token.isEmpty())
        {
            errors.add("chat_safety.token is empty; set it to the output of 'sudo ecc-server token'");
        }
        else if (!token.matches("[\\x21-\\x7E]+"))
        {
            errors.add("chat_safety.token must be the output of 'sudo ecc-server token', with no spaces or"
                    + " special characters");
            token = "";
        }
        return eccServer == null || token.isEmpty() ? null : new SafetySettings(eccServer, token);
    }

    @Nullable
    private static URI eccServer(String value, List<String> errors)
    {
        try
        {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1)
            {
                errors.add("chat_safety.model_host '" + value + "' must be an http address with a port, such as"
                        + " http://127.0.0.1:8790");
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI("http", null, uri.getHost(), uri.getPort(), path, null, null);
        }
        catch (URISyntaxException ex)
        {
            errors.add("chat_safety.model_host '" + value + "' is not a valid address: " + ex.getMessage());
            return null;
        }
    }
}
