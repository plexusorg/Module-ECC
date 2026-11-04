package dev.plex.ecc.safety;

import com.google.gson.JsonObject;

/**
 * The deployed bundle's decision parameters, read from the model host and kept in memory only.
 * {@link ModelHost} accepts only the {@code single} window rule: one message at or above the trigger threshold.
 */
record Decision(String version, String windowRule, double trigger, double clearing, double corroboration)
{
    static final String SINGLE = "single";

    boolean triggers(Verdict.Score score)
    {
        return score.pBan() >= trigger;
    }

    JsonObject toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("window_rule", windowRule);
        json.addProperty("trigger", trigger);
        json.addProperty("clearing", clearing);
        json.addProperty("corroboration", corroboration);
        return json;
    }
}
