package dev.plex.ecc.safety;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the training pipeline (ECC-ML's {@code ecc.transcript} and {@code ecc.parse}, in Python) read a line from the
 * server's log, so that a line goes to ECC-Server split into speaker, name and text as training split it. Whitespace
 * is Python's {@code str.isspace()} set, which Java's {@link Character#isWhitespace} and {@link String#strip} do not
 * match (they leave U+00A0, U+2007 and U+202F).
 */
public final class LogReading
{
    // Python's str.isspace() characters, which are also what its re module's \s matches in a str pattern.
    private static final String SPACE = "\\t\\n\\u000B\\f\\r\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A"
            + "\\u2028\\u2029\\u202F\\u205F\\u3000";
    // transcript.py: re.sub(r"^(?:set|add|-o)\s+(?:\d+\s+)?", "", text, flags=re.I). Under re.I, s also matches U+017F;
    // \d is Unicode's decimal digits.
    private static final Pattern COMMAND_PREFIX = Pattern.compile(
            "^(?:[sS\\u017F][eE][tT]|[aA][dD][dD]|-[oO])[" + SPACE + "]+(?:\\p{Nd}+[" + SPACE + "]+)?");
    private static final Set<String> COMMAND_SKIPPED = Set.of("clear", "remove", "off");
    // parse.py VANILLA: a chat line in Minecraft's own format, "<name> text".
    private static final Pattern VANILLA = Pattern.compile("^<([^<>" + SPACE + "]+)> (.*)$", Pattern.DOTALL);
    private static final String SEPARATOR = " » ";
    // transcript.py DISCORD, verbatim, with re.S.
    private static final Pattern DISCORD = Pattern.compile("^\\[DiscordSRV\\] Chat: (?:\\[Discord[^\\]]*\\]|<[^>]*>"
            + "\\[<[^>]*>Discord</[^>]*>\\]</[^>]*>) (.*?) (?:>|<gray>></gray>) (.*)$",
            Pattern.DOTALL | Pattern.UNIX_LINES);

    private LogReading()
    {
    }

    public static boolean isSpace(int codePoint)
    {
        return switch (codePoint)
        {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029,
                 0x202F, 0x205F, 0x3000 -> true;
            default -> codePoint >= 0x2000 && codePoint <= 0x200A;
        };
    }

    // Python's str.strip().
    public static String strip(String text)
    {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start)))
        {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1)))
        {
            end--;
        }
        return text.substring(start, end);
    }

    public static boolean isBlank(String text)
    {
        return strip(text).isEmpty();
    }

    /**
     * The text of a command that sets a sign, an item's name or lore, or a tag, from the arguments as typed
     * (the sign, item and tag commands in {@code transcript.py}): a leading set, add or -o and an optional number are removed. Null when nothing is
     * left, or what is left is clear, remove or off.
     */
    @Nullable
    public static String commandText(String arguments)
    {
        String text = COMMAND_PREFIX.matcher(strip(arguments)).replaceFirst("");
        if (text.isEmpty() || COMMAND_SKIPPED.contains(text.toLowerCase(Locale.ROOT)))
        {
            return null;
        }
        return text;
    }

    /**
     * The name part of a chat line as the console shows it: the line without its closing {@code " » " + message}, so
     * a tag that ends in {@code "»"} stays part of the name; else the text before the first {@code " » "}, else the
     * name of Minecraft's own {@code <name> text} format; null when the line has none of these.
     */
    @Nullable
    public static String shownName(String consoleLine, String message)
    {
        String ending = SEPARATOR + message;
        if (consoleLine.endsWith(ending))
        {
            return consoleLine.substring(0, consoleLine.length() - ending.length());
        }
        int separator = consoleLine.indexOf(SEPARATOR);
        if (separator >= 0)
        {
            return consoleLine.substring(0, separator);
        }
        Matcher vanilla = VANILLA.matcher(consoleLine);
        return vanilla.matches() ? vanilla.group(1) : null;
    }

    /**
     * The speaker and text of DiscordSRV's console line for a Discord message shown in game, {@code "[DiscordSRV]
     * Chat: " + the line}, as training read it from the log file: up to the first line break, since the log's later
     * lines of a message are not log lines. Null when the line does not have DiscordSRV's format.
     */
    @Nullable
    public static Discord discord(String consoleLine)
    {
        int end = consoleLine.length();
        for (int i = 0; i < consoleLine.length(); i++)
        {
            char c = consoleLine.charAt(i);
            if (c == '\n' || c == '\r')
            {
                end = i;
                break;
            }
        }
        Matcher matcher = DISCORD.matcher(consoleLine.substring(0, end));
        return matcher.matches() ? new Discord(matcher.group(1), matcher.group(2)) : null;
    }

    public record Discord(String speaker, String text)
    {
    }
}
