package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * What became of a message. {@code decision} is one of the {@code Verdict} constants; {@code score} and
 * {@code parameters} are present when the message was scored or reused a scored message's verdict.
 */
record Verdict(String decision, @Nullable Score score, @Nullable Decision parameters, @Nullable String rule,
               @Nullable String reusedFrom)
{
    static final String OK = "ok";
    static final String TRIGGER = "trigger";
    // Recorded without a check.
    static final String SHADOWED = "shadowed";
    static final String BYPASS = "bypass";
    static final String RAID = "raid";
    static final String DROPPED = "dropped";
    static final String UNSCORED = "unscored";
    // Sent to the model host without a usable score.
    static final String NO_SCORE = "no_score";

    static Verdict unchecked(String decision)
    {
        return new Verdict(decision, null, null, null, null);
    }

    static Verdict scored(Score score, Decision parameters)
    {
        return new Verdict(parameters.triggers(score) ? TRIGGER : OK, score, parameters, null, null);
    }

    Verdict reusedBy(String reuseRule, String original)
    {
        return new Verdict(decision, score, parameters, reuseRule, original);
    }

    boolean triggered()
    {
        return TRIGGER.equals(decision);
    }

    void addTo(JsonObject json)
    {
        json.addProperty("decision", decision);
        if (score != null)
        {
            json.addProperty("p_ban", score.pBan());
            json.addProperty("margin", score.margin());
            json.addProperty("version", score.version());
        }
        if (rule != null)
        {
            json.addProperty("rule", rule);
            json.addProperty("reused_from", reusedFrom);
        }
    }

    record Score(double pBan, double margin, String version)
    {
    }
}
