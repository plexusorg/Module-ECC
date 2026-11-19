package dev.plex.ecc.safety;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record SafetySettings(URI eccServer, String token, List<String> trustedGroups, List<String> allowedLinks)
{
    // How long a line waits for ECC-Server's verdict; a line with none by then is held and asked about again. ECC-Server
    // answers hold itself after 900 ms, so its answer arrives within this.
    public static final Duration VERDICT_TIMEOUT = Duration.ofSeconds(1);
    // A LuckPerms group name.
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_.-]+");
    // A host and an optional path prefix, as ECC-Server reads the allowlist: the host as its link pattern matches one.
    private static final Pattern LINK = Pattern.compile("(?i)(?:[a-z0-9-]+\\.)+[a-z]{2,24}(?:/\\S*)?");

    // Returns null after adding a line to errors for each invalid setting.
    @Nullable
    public static SafetySettings read(ConfigurationSection section, List<String> errors)
    {
        URI eccServer = eccServer(section.getString("ecc_server", ""), errors);
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
        List<String> trustedGroups = new ArrayList<>();
        for (String group : section.getStringList("trusted_groups"))
        {
            String name = group.strip().toLowerCase(Locale.ROOT);
            if (GROUP.matcher(name).matches())
            {
                trustedGroups.add(name);
            }
            else
            {
                errors.add("chat_safety.trusted_groups: '" + group + "' is not a LuckPerms group name");
            }
        }
        List<String> allowedLinks = new ArrayList<>();
        for (String link : section.getStringList("allowed_links"))
        {
            String entry = link.strip();
            if (LINK.matcher(entry).matches())
            {
                allowedLinks.add(entry);
            }
            else
            {
                errors.add("chat_safety.allowed_links: '" + link + "' is not a host with an optional path, such as"
                        + " example.org or discord.gg/invite-code, written without http:// or https://");
            }
        }
        if (eccServer == null || token.isEmpty() || !errors.isEmpty())
        {
            return null;
        }
        return new SafetySettings(eccServer, token, List.copyOf(trustedGroups), List.copyOf(allowedLinks));
    }

    @Nullable
    private static URI eccServer(String value, List<String> errors)
    {
        try
        {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1)
            {
                errors.add("chat_safety.ecc_server '" + value + "' must be an http address with a port, such as"
                        + " http://127.0.0.1:8790");
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI("http", null, uri.getHost(), uri.getPort(), path, null, null);
        }
        catch (URISyntaxException ex)
        {
            errors.add("chat_safety.ecc_server '" + value + "' is not a valid address: " + ex.getMessage());
            return null;
        }
    }
}
