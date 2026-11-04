package dev.plex.ecc.safety;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The slow check's evidence rules, ported line for line from the pipeline's {@code src/ecc/redteam.py} ({@code render},
 * {@code ask_evidence}, {@code parse_answer}, {@code keyed}, {@code evidence_check}, {@code nrm}, {@code match_line},
 * {@code age_statement}, {@code validate}, {@code first_violator}, {@code decide}), which measured no failures on the
 * red-team set. Kimi reports evidence; this code decides who is banned. A change here must match a change there.
 *
 * <p>Python's text predicates are reproduced exactly for Unicode 15.1, the version Python 3.13 uses: {@code \s} is
 * Unicode White_Space plus U+001C to U+001F, and {@code [^\W_]} is a letter or a number. Python's {@code casefold} is
 * {@link #fold}, which differs in a few full foldings (ß stays ß, final sigma stays ς, ligatures such as ﬁ stay whole).
 * Both sides of every comparison are folded the same way, so a difference matters only when Kimi's quote differs from
 * the player's line in exactly such a letter.</p>
 */
final class Evidence
{
    static final String OFFENDER = "offender";
    static final String AT_RISK = "at_risk";
    static final String NOT_INVOLVED = "not_involved";
    static final String HELD = "held";
    static final Set<String> OFFENDING = Set.of("starting", "directed", "pushing", "grooming");
    // The only tags a player at risk may have.
    private static final Set<String> RESPONSES = Set.of("responding", "self");
    private static final Set<String> SELF_WORDS = Set.of("i", "im", "i'm", "me", "my");
    private static final int ADULT = 18;
    private static final String MARK = "^";
    private static final Pattern SPACES = Pattern.compile("[\\s\\x1c-\\x1f]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern EDGES = Pattern.compile("\\A[\\s\\x1c-\\x1f]+|[\\s\\x1c-\\x1f]+\\z",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FENCE = Pattern.compile("```(?:json)?");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    // Ranges of code points Python's str.isdigit accepts that are not decimal digits, which int() then rejects.
    private static final int[] NON_DECIMAL_DIGITS = {0xb2, 0xb3, 0xb9, 0xb9, 0x1369, 0x1371, 0x19da, 0x19da,
        0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089, 0x2460, 0x2468, 0x2474, 0x247c, 0x2488, 0x2490, 0x24ea, 0x24ea,
        0x24f5, 0x24fd, 0x24ff, 0x24ff, 0x2776, 0x277e, 0x2780, 0x2788, 0x278a, 0x2792, 0x10a40, 0x10a43,
        0x10e60, 0x10e68, 0x11052, 0x1105a, 0x1f100, 0x1f10a};
    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);

    private Evidence()
    {
    }

    /**
     * One line of a transcript: its author's account name, the text, and the fast check's P(ban), null when the line
     * has none.
     */
    record Line(String speaker, String text, @Nullable Double pBan)
    {
    }

    /**
     * A violation Kimi reported. {@code line} is its 1-based number in the view, or null.
     */
    record Violation(String quote, @Nullable Integer line, String tag)
    {
    }

    /**
     * What Kimi reported for one player.
     */
    record Reported(@Nullable BigInteger ownAge, @Nullable String ageMessage, List<Violation> violations)
    {
    }

    /**
     * A kept quote: the 0-based index of the player's own line it copies, and its tag.
     */
    record Quote(int index, String tag)
    {
    }

    /**
     * One player's validated evidence in one view.
     */
    record Kept(List<Quote> quotes, int dropped, int given, List<BigInteger> ages)
    {
    }

    record Outcome(String role, String cause)
    {
    }

    /**
     * Kimi's answer cannot be used (Python's ValueError, TypeError, KeyError or AttributeError); the call is retried.
     */
    static final class Unparseable extends Exception
    {
        Unparseable(String reason)
        {
            super(reason);
        }
    }

    // ask_evidence's user message.
    static String userMessage(String channel, List<Line> lines)
    {
        return "Messages in " + channel + ", in order:\n" + render(lines) + "\n\n"
                + "Players to judge (exact speaker names, one JSON string each): " + jsonList(speakers(lines));
    }

    // Each speaker once, in order of their first line.
    static List<String> speakers(List<Line> lines)
    {
        Set<String> speakers = new LinkedHashSet<>();
        lines.forEach(line -> speakers.add(line.speaker()));
        return List.copyOf(speakers);
    }

    // Datamarking: whitespace runs in player text become ^, one JSON object per line as Python's json.dumps writes it.
    static String render(List<Line> lines)
    {
        StringJoiner out = new StringJoiner("\n");
        for (int i = 0; i < lines.size(); i++)
        {
            Line line = lines.get(i);
            String text = SPACES.matcher(strip(line.text())).replaceAll(MARK);
            out.add("{\"line\": " + (i + 1) + ", \"speaker\": " + json(line.speaker()) + ", \"text\": " + json(text)
                    + "}");
        }
        return out.toString();
    }

    // json.dumps of a list of strings with ensure_ascii=False.
    private static String jsonList(List<String> values)
    {
        StringJoiner out = new StringJoiner(", ", "[", "]");
        values.forEach(value -> out.add(json(value)));
        return out.toString();
    }

    // json.dumps of a string with ensure_ascii=False: only quotes, backslashes and control characters are escaped.
    private static String json(String value)
    {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            switch (ch)
            {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> out.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return out.append('"').toString();
    }

    // Python's str.strip().
    static String strip(String text)
    {
        return EDGES.matcher(text).replaceAll("");
    }

    // Python's casefold; see the class comment for where it differs.
    private static String fold(String text)
    {
        return text.toLowerCase(Locale.ROOT);
    }

    // parse_answer: the last top-level JSON object in the text that parses as strict JSON, or null.
    @Nullable
    static JsonElement parseAnswer(@Nullable String raw)
    {
        String text = FENCE.matcher(raw == null ? "" : raw).replaceAll("");
        JsonElement found = null;
        int depth = 0;
        int start = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            if (inString)
            {
                if (escaped)
                {
                    escaped = false;
                }
                else if (ch == '\\')
                {
                    escaped = true;
                }
                else if (ch == '"')
                {
                    inString = false;
                }
                continue;
            }
            if (ch == '"')
            {
                inString = true;
            }
            else if (ch == '{')
            {
                if (depth == 0)
                {
                    start = i;
                }
                depth++;
            }
            else if (ch == '}' && depth > 0)
            {
                depth--;
                if (depth == 0)
                {
                    JsonElement parsed = strictJson(text.substring(start, i + 1));
                    found = parsed == null ? found : parsed;
                }
            }
        }
        return found;
    }

    // json.loads, or null when it would raise. Python also accepts NaN and Infinity, which strict JSON rejects.
    @Nullable
    private static JsonElement strictJson(String text)
    {
        try
        {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JSON.read(reader);
            return reader.peek() == JsonToken.END_DOCUMENT ? parsed : null;
        }
        catch (IOException | RuntimeException ex)
        {
            return null;
        }
    }

    // keyed: each judged player's entry, matched by casefolded name.
    private static Map<String, JsonElement> keyed(@Nullable JsonElement answer, List<String> players)
            throws Unparseable
    {
        if (!(answer instanceof JsonObject object))
        {
            throw new Unparseable("no JSON object");
        }
        if (object.get("players") instanceof JsonObject nested && players.stream().noneMatch(object::has))
        {
            object = nested;
        }
        Map<String, JsonElement> lower = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> lower.put(fold(entry.getKey()), entry.getValue()));
        Map<String, JsonElement> out = new LinkedHashMap<>();
        for (String player : players)
        {
            String key = fold(player);
            if (!lower.containsKey(key))
            {
                throw new Unparseable("missing player '" + player + "'");
            }
            out.put(player, lower.get(key));
        }
        return out;
    }

    // evidence_check: Kimi's answer for each judged player, or Unparseable.
    static Map<String, Reported> evidenceCheck(@Nullable JsonElement answer, List<String> players) throws Unparseable
    {
        Map<String, Reported> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : keyed(answer, players).entrySet())
        {
            if (!(entry.getValue() instanceof JsonObject value)
                    || value.has("violations") && !(value.get("violations") instanceof JsonArray))
            {
                throw new Unparseable("bad entry for '" + entry.getKey() + "'");
            }
            List<Violation> violations = new ArrayList<>();
            if (value.get("violations") instanceof JsonArray array)
            {
                for (JsonElement item : array)
                {
                    violations.add(violation(item));
                }
            }
            JsonElement message = value.get("age_message");
            String ageMessage = message instanceof JsonPrimitive text && text.isString() ? text.getAsString() : null;
            out.put(entry.getKey(), new Reported(ownAge(value.get("own_age")), ageMessage, List.copyOf(violations)));
        }
        return out;
    }

    private static Violation violation(JsonElement item) throws Unparseable
    {
        if (item instanceof JsonPrimitive text && text.isString())
        {
            return new Violation(text.getAsString(), null, "");
        }
        if (!(item instanceof JsonObject object) || !(object.get("quote") instanceof JsonPrimitive quote)
                || !quote.isString())
        {
            throw new Unparseable("bad violation");
        }
        return new Violation(quote.getAsString(), lineNumber(object.get("line")), tag(object));
    }

    // int(line) if str(line).isdigit() else None. A line number past int range can match no line.
    @Nullable
    private static Integer lineNumber(@Nullable JsonElement line) throws Unparseable
    {
        BigInteger value = null;
        if (line instanceof JsonPrimitive primitive && primitive.isNumber()
                && INTEGER.matcher(primitive.getAsString()).matches())
        {
            BigInteger number = new BigInteger(primitive.getAsString());
            value = number.signum() >= 0 ? number : null;
        }
        else if (line instanceof JsonPrimitive primitive && primitive.isString())
        {
            value = digits(primitive.getAsString());
        }
        if (value == null)
        {
            return null;
        }
        return value.bitLength() < Integer.SIZE ? value.intValue() : Integer.MAX_VALUE;
    }

    // str(q.get("tag", "")).strip().lower(): only a string tag can be one of the tags that are acted on.
    private static String tag(JsonObject violation)
    {
        JsonElement tag = violation.get("tag");
        if (tag == null)
        {
            return "";
        }
        if (tag.isJsonNull())
        {
            return "none";
        }
        String text = tag instanceof JsonPrimitive primitive ? primitive.getAsString() : tag.toString();
        return strip(text).toLowerCase(Locale.ROOT);
    }

    // own_age: int() of a number (a boolean is 1 or 0, as in Python) or of a string that holds only digits; else None.
    @Nullable
    private static BigInteger ownAge(@Nullable JsonElement age) throws Unparseable
    {
        if (!(age instanceof JsonPrimitive primitive))
        {
            return null;
        }
        if (primitive.isBoolean())
        {
            return primitive.getAsBoolean() ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (primitive.isNumber())
        {
            String literal = primitive.getAsString();
            if (INTEGER.matcher(literal).matches())
            {
                return new BigInteger(literal);
            }
            // Python reads any other number as a float and truncates it.
            double value = Double.parseDouble(literal);
            if (!Double.isFinite(value))
            {
                throw new Unparseable("own_age is not finite");
            }
            return new BigDecimal(value).toBigInteger();
        }
        return digits(strip(primitive.getAsString()));
    }

    // int(text) when text.isdigit(), else null. Digits that are not decimal fail int(), as in Python.
    @Nullable
    private static BigInteger digits(String text) throws Unparseable
    {
        if (text.isEmpty() || !text.codePoints().allMatch(Evidence::isDigit))
        {
            return null;
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i)))
        {
            int point = text.codePointAt(i);
            if (Character.getType(point) != Character.DECIMAL_DIGIT_NUMBER)
            {
                throw new Unparseable("invalid literal for int(): '" + text + "'");
            }
            value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(Character.digit(point, 10)));
        }
        return value;
    }

    // Python's str.isdigit for one code point.
    private static boolean isDigit(int point)
    {
        if (Character.getType(point) == Character.DECIMAL_DIGIT_NUMBER)
        {
            return true;
        }
        for (int i = 0; i < NON_DECIMAL_DIGITS.length; i += 2)
        {
            if (point >= NON_DECIMAL_DIGITS[i] && point <= NON_DECIMAL_DIGITS[i + 1])
            {
                return true;
            }
        }
        return false;
    }

    // nrm: the casefolded words (runs of letters and numbers) of the text, joined by single spaces.
    static String nrm(String text)
    {
        String folded = fold(text.replace(MARK, " "));
        StringJoiner words = new StringJoiner(" ");
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < folded.length(); i += Character.charCount(folded.codePointAt(i)))
        {
            int point = folded.codePointAt(i);
            if (isWordCharacter(point))
            {
                word.appendCodePoint(point);
            }
            else if (!word.isEmpty())
            {
                words.add(word);
                word.setLength(0);
            }
        }
        if (!word.isEmpty())
        {
            words.add(word);
        }
        return words.toString();
    }

    // Python's [^\W_]: a letter or a number.
    private static boolean isWordCharacter(int point)
    {
        int type = Character.getType(point);
        return Character.isLetter(point) || type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    // match_line's squash: nrm with runs of one repeated word counted as one, since a model cannot copy "lol lol lol"
    // spam to the exact count. Equal to re.sub(r"\b(\w+)(?: \1\b)+", r"\1", nrm(s)), as nrm leaves only whole words.
    private static String squash(String text)
    {
        String normalized = nrm(text);
        if (normalized.isEmpty())
        {
            return normalized;
        }
        StringJoiner out = new StringJoiner(" ");
        String previous = null;
        for (String word : normalized.split(" "))
        {
            if (!word.equals(previous))
            {
                out.add(word);
            }
            previous = word;
        }
        return out.toString();
    }

    // match_line: the index of the player's own line that the quote copies, or null. own maps index to text.
    @Nullable
    private static Integer matchLine(String quote, @Nullable Integer lineNumber, Map<Integer, String> own)
    {
        String wanted = squash(quote);
        if (wanted.isEmpty())
        {
            return null;
        }
        boolean partial = wanted.codePointCount(0, wanted.length()) >= 4;
        List<Integer> hits = new ArrayList<>();
        for (Map.Entry<Integer, String> line : own.entrySet())
        {
            String text = squash(line.getValue());
            if (wanted.equals(text) || partial && text.contains(wanted))
            {
                hits.add(line.getKey());
            }
        }
        if (hits.isEmpty())
        {
            return null;
        }
        if (lineNumber != null && hits.contains(lineNumber - 1))
        {
            return lineNumber - 1;
        }
        return hits.getFirst();
    }

    // age_statement, on an nrm'd text.
    private static boolean ageStatement(String normalized)
    {
        List<String> words = words(normalized);
        return words.size() <= 3 || words.stream().anyMatch(SELF_WORDS::contains);
    }

    // str.split() of an nrm'd text.
    private static List<String> words(String normalized)
    {
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }

    /**
     * validate: the kept evidence of one call, for the view {@code lines[0..cut]}. {@code triggerOf} maps each
     * triggered player to their trigger's index; an age counts only when stated before it (before the cut for others).
     */
    static Map<String, Kept> validate(Map<String, Reported> answer, List<Line> lines, int cut,
                                      Map<String, Integer> triggerOf)
    {
        Map<String, Kept> out = new LinkedHashMap<>();
        for (Map.Entry<String, Reported> entry : answer.entrySet())
        {
            String player = entry.getKey();
            Reported reported = entry.getValue();
            Map<Integer, String> own = new LinkedHashMap<>();
            for (int i = 0; i <= cut; i++)
            {
                if (lines.get(i).speaker().equals(player))
                {
                    own.put(i, lines.get(i).text());
                }
            }
            List<Quote> kept = new ArrayList<>();
            int dropped = 0;
            for (Violation violation : reported.violations())
            {
                Integer index = matchLine(violation.quote(), violation.line(), own);
                if (index == null)
                {
                    dropped++;
                }
                else
                {
                    kept.add(new Quote(index, violation.tag()));
                }
            }
            List<BigInteger> ages = statedAge(reported, player, lines, own, triggerOf.getOrDefault(player, cut));
            out.put(player, new Kept(List.copyOf(kept), dropped, reported.violations().size(), ages));
        }
        return out;
    }

    // validate's age rule: the age counts once, when one of the player's own lines before their trigger holds the
    // stated age message, and holds the number in a statement about themselves, or is a short confirmation ("yea") of
    // the nearest earlier line by someone else that holds it ("ur 11 right").
    private static List<BigInteger> statedAge(Reported reported, String player, List<Line> lines,
                                              Map<Integer, String> own, int trigger)
    {
        BigInteger age = reported.ownAge();
        String message = reported.ageMessage();
        if (age == null || message == null || message.isEmpty())
        {
            return List.of();
        }
        String said = nrm(message);
        String number = age.toString();
        for (Map.Entry<Integer, String> line : own.entrySet())
        {
            int i = line.getKey();
            String normalized = nrm(line.getValue());
            if (i >= trigger || said.isEmpty() || !(said.contains(normalized) || normalized.contains(said)))
            {
                continue;
            }
            String previous = "";
            for (int j = i - 1; j >= 0; j--)
            {
                if (!lines.get(j).speaker().equals(player))
                {
                    previous = nrm(lines.get(j).text());
                    break;
                }
            }
            List<String> words = words(normalized);
            if (words.contains(number) && ageStatement(normalized)
                    || words.size() <= 3 && words(previous).contains(number))
            {
                return List.of(age);
            }
        }
        return List.of();
    }

    // first_violator: the player whose kept quote is the earliest line in the view, or null.
    @Nullable
    static String firstViolator(Map<String, Kept> view)
    {
        String first = null;
        int earliest = Integer.MAX_VALUE;
        Comparator<String> names = Comparator.naturalOrder();
        for (Map.Entry<String, Kept> entry : view.entrySet())
        {
            for (Quote quote : entry.getValue().quotes())
            {
                if (quote.index() < earliest || quote.index() == earliest && names.compare(entry.getKey(), first) < 0)
                {
                    earliest = quote.index();
                    first = entry.getKey();
                }
            }
        }
        return first;
    }

    private static List<Quote> quotes(Map<String, Kept> view, String player)
    {
        Kept kept = view.get(player);
        return kept == null ? List.of() : kept.quotes();
    }

    /**
     * decide for a player who never triggered in the transcript: an offender only with corroboration, a kept quote
     * tagged starting, directed, pushing or grooming in the whole view and P(ban) at or above the corroboration
     * threshold on at least one of their kept quoted lines.
     */
    static Outcome decideUntriggered(String player, Map<String, Kept> full, List<Line> lines, double corroboration)
    {
        List<Quote> quotes = quotes(full, player);
        boolean offending = quotes.stream().anyMatch(quote -> OFFENDING.contains(quote.tag()));
        boolean corroborated = quotes.stream().anyMatch(quote ->
        {
            Double pBan = lines.get(quote.index()).pBan();
            return pBan != null && pBan >= corroboration;
        });
        if (offending && corroborated)
        {
            return new Outcome(OFFENDER, "corroborated");
        }
        return new Outcome(NOT_INVOLVED, "not triggered");
    }

    /**
     * decide for a triggered player, from the whole view and the view cut at their trigger. Any kept quote makes an
     * offender, unless the player is a minor by a validated age, every tag is responding or self, and they are the
     * first violator in neither view. With no kept quote they are cleared only when every line of theirs before the
     * trigger has a P(ban) below the clearing threshold; otherwise they are held.
     */
    static Outcome decideTriggered(String player, int trigger, Map<String, Kept> full, Map<String, Kept> cut,
                                   List<Line> lines, double clearing)
    {
        List<Map<String, Kept>> views = List.of(full, cut);
        List<Quote> kept = new ArrayList<>();
        views.forEach(view -> kept.addAll(quotes(view, player)));
        if (!kept.isEmpty())
        {
            Set<String> tags = new HashSet<>();
            kept.forEach(quote -> tags.add(quote.tag()));
            Set<String> firsts = new HashSet<>();
            views.forEach(view -> firsts.add(firstViolator(view)));
            List<BigInteger> ages = new ArrayList<>();
            views.forEach(view -> ages.addAll(view.containsKey(player) ? view.get(player).ages() : List.of()));
            boolean minor = !ages.isEmpty() && ages.stream().allMatch(age -> age.compareTo(BigInteger.valueOf(ADULT)) < 0);
            if (minor && !firsts.contains(player) && RESPONSES.containsAll(tags))
            {
                return new Outcome(AT_RISK, "kimi");
            }
            return new Outcome(OFFENDER, "kimi");
        }
        for (int i = 0; i < trigger; i++)
        {
            Line line = lines.get(i);
            if (line.speaker().equals(player) && (line.pBan() == null || line.pBan() >= clearing))
            {
                return new Outcome(HELD, "fast");
            }
        }
        return new Outcome(NOT_INVOLVED, "cleared");
    }
}
