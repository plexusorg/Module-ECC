package dev.plex.ecc.safety;

import com.google.gson.JsonObject;

/**
 * The deployed bundle's decision parameters and slow-check system prompt, read from the model host and kept in memory
 * only. {@link ModelHost} accepts only the {@code single} window rule: one message at or above the trigger threshold.
 * The prompt embeds the private policy, so it is never written to the record or the pending state.
 */
record Decision(String version, String windowRule, double trigger, double clearing, double corroboration,
                String slowCheckSystem)
{
    static final String SINGLE = "single";

    boolean triggers(Verdict.Score score)
    {
        return score.pBan() >= trigger;
    }

    // Everything but the prompt.
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
