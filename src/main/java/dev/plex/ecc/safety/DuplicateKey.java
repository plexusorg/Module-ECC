package dev.plex.ecc.safety;

import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The duplicate rule's key for a message: copies that differ only in case, colour codes, lookalike letters,
 * repeated letters or random-looking tokens get the same key. The model host never sees this form.
 */
final class DuplicateKey
{
    private static final Pattern COLOUR_CODES = Pattern.compile("[§&](?:#[0-9a-f]{6}|[0-9a-fk-orx])");
    // Hex runs, digit runs, and tokens mixing letters and digits, as raid tools append to dodge duplicate filters.
    private static final Pattern RANDOM_TOKEN = Pattern.compile("[0-9a-f]{8,}|\\d{3,}|(?=\\S*\\d)(?=\\S*[a-z])\\S{8,}");
    private static final String LOOKALIKES = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢаеорсухкмтвніјѕԁɡαορινтκε";
    private static final String LATIN = "abcdefghijklmnopqrstuvwyzaeopcyxkmtbhijsdgaopivtke";
    private static final String LEET = "0134578@$";
    private static final String LEET_LATIN = "oieastbas";

    private DuplicateKey()
    {
    }

    static String of(String text)
    {
        String folded = Normalizer.normalize(text, Form.NFKC).toLowerCase(Locale.ROOT);
        folded = COLOUR_CODES.matcher(folded).replaceAll("");
        StringJoiner tokens = new StringJoiner(" ");
        for (String token : folded.split("\\s+"))
        {
            if (token.isEmpty() || RANDOM_TOKEN.matcher(token).matches())
            {
                continue;
            }
            String mapped = collapse(map(token));
            if (!mapped.isEmpty())
            {
                tokens.add(mapped);
            }
        }
        return tokens.toString();
    }

    private static String map(String token)
    {
        StringBuilder mapped = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++)
        {
            char c = token.charAt(i);
            int lookalike = LOOKALIKES.indexOf(c);
            int leet = LEET.indexOf(c);
            if (lookalike >= 0)
            {
                mapped.append(LATIN.charAt(lookalike));
            }
            else if (leet >= 0)
            {
                mapped.append(LEET_LATIN.charAt(leet));
            }
            else if (Character.isLetterOrDigit(c))
            {
                mapped.append(c);
            }
        }
        return mapped.toString();
    }

    private static String collapse(String token)
    {
        StringBuilder collapsed = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++)
        {
            if (i == 0 || token.charAt(i) != token.charAt(i - 1))
            {
                collapsed.append(token.charAt(i));
            }
        }
        return collapsed.toString();
    }
}
