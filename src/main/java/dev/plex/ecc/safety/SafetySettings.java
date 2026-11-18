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

public record SafetySettings(URI eccServer, String token, Duration verdictTimeout, List<String> trustedGroups,
                             List<String> allowedLinks, String notice, String warning, String itemNameChecked)
{
    private static final int MIN_TIMEOUT_MILLIS = 50;
    private static final int MAX_TIMEOUT_MILLIS = 10_000;
    // A LuckPerms group name.
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_.-]+");
    // A host and an optional path prefix, as ECC-Server reads the allowlist: the host as its link pattern matches one.
    private static final Pattern LINK = Pattern.compile("(?i)(?:[a-z0-9-]+\\.)+[a-z]{2,24}(?:/\\S*)?");

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
        int timeout = section.getInt("verdict_timeout_ms", 1000);
        if (timeout < MIN_TIMEOUT_MILLIS || timeout > MAX_TIMEOUT_MILLIS)
        {
            errors.add("chat_safety.verdict_timeout_ms must be between " + MIN_TIMEOUT_MILLIS + " and "
                    + MAX_TIMEOUT_MILLIS);
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
        String notice = text(section, "notice", errors);
        String warning = text(section, "warning", errors);
        String itemNameChecked = text(section, "item_name_checked", errors);
        if (eccServer == null || token.isEmpty() || !errors.isEmpty())
        {
            return null;
        }
        return new SafetySettings(eccServer, token, Duration.ofMillis(timeout), List.copyOf(trustedGroups),
                List.copyOf(allowedLinks), notice, warning, itemNameChecked);
    }

    private static String text(ConfigurationSection section, String key, List<String> errors)
    {
        String value = section.getString(key, "");
        if (value.isBlank())
        {
            errors.add("chat_safety." + key + " is empty");
        }
        return value;
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
