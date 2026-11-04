package dev.plex.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ECCCommand extends SimplePlexCommand
{
    private static final String STATUS_PERMISSION = "plex.ecc.status";

    private final ECCModule module;

    public ECCCommand(ECCModule module)
    {
        super(command("ecc")
                .description("Show the state of chat safety, or turn its alerts off and on")
                .usage("/<command> <status | alerts>")
                .build());
        this.module = module;
    }

    @Override
    protected void configureCommand(LiteralArgumentBuilder<CommandSourceStack> command)
    {
        command.requires(source -> source.getSender().hasPermission(STATUS_PERMISSION)
                || source.getSender().hasPermission(ChatSafety.ALERTS_PERMISSION));
        command.executes(context -> executeCommand(context, (sender, player) -> usage()));
        command.then(Commands.literal("status")
                .requires(source -> source.getSender().hasPermission(STATUS_PERMISSION))
                .executes(context -> executeCommand(context, (sender, player) ->
        {
            ChatSafety safety = module.chatSafety();
            if (safety == null)
            {
                return messageComponent("chatSafetyOff");
            }
            ChatSafety.Status status = safety.status();
            safety.serverStatus().whenComplete((server, failure) -> showStatus(sender, status, server, failure));
            return null;
        })));
        command.then(Commands.literal("alerts")
                .requires(source -> source.getSender().hasPermission(ChatSafety.ALERTS_PERMISSION))
                .executes(context -> executeCommand(context, (sender, player) ->
        {
            if (!(sender instanceof Player staff))
            {
                return messageComponent("noPermissionConsole");
            }
            ChatSafety safety = module.chatSafety();
            if (safety == null)
            {
                return messageComponent("chatSafetyOff");
            }
            return messageComponent(safety.toggleAlerts(staff) ? "chatSafetyAlertsOff" : "chatSafetyAlertsOn");
        })));
    }

    // Three lines: the classifier, the Kimi review and the mutes. Server values are reduced to fixed state words, so
    // nothing of ECC-Server's configuration (W&B names, model ids, versions) reaches the chat.
    private void showStatus(CommandSender sender, ChatSafety.Status status, @Nullable JsonObject server,
                            @Nullable Throwable failure)
    {
        if (failure != null || server == null)
        {
            sender.sendMessage(messageComponent("chatSafetyUnreachable",
                    Placeholder.unparsed("since", ago(status.since())),
                    Placeholder.unparsed("buffered", String.valueOf(status.buffered())),
                    Placeholder.unparsed("muted", String.valueOf(status.muted()))));
            return;
        }
        JsonObject safety = object(server, "safety");
        JsonObject slow = object(safety, "slow_check");
        sender.sendMessage(messageComponent("chatSafetyClassifier",
                Placeholder.parsed("state", "ready".equals(string(server, "status")) ? "<green>ready</green>"
                        : "<red>not ready</red>"),
                Placeholder.unparsed("backlog", String.valueOf(number(safety, "backlog")))));
        sender.sendMessage(messageComponent("chatSafetyReview",
                Placeholder.parsed("state", reviewState(object(server, "bedrock"), slow)),
                Placeholder.unparsed("checking", names(slow, "checking")),
                Placeholder.unparsed("held", names(slow, "held"))));
        sender.sendMessage(messageComponent("chatSafetyMutes",
                Placeholder.unparsed("muted", String.valueOf(status.muted())),
                Placeholder.unparsed("pending", names(safety, "pending")),
                Placeholder.parsed("updates", updatesState(server.get("wandb")))));
        if (status.buffered() > 0 || status.unacknowledged() > 0)
        {
            sender.sendMessage(messageComponent("chatSafetyWaiting",
                    Placeholder.unparsed("buffered", String.valueOf(status.buffered())),
                    Placeholder.unparsed("unacknowledged", String.valueOf(status.unacknowledged()))));
        }
    }

    private static String reviewState(JsonObject bedrock, JsonObject slow)
    {
        if (!(bedrock.get("key_set") instanceof JsonPrimitive key) || !key.getAsBoolean())
        {
            return "<red>no Bedrock key</red>";
        }
        if (slow.get("answering") instanceof JsonPrimitive answering && !answering.getAsBoolean())
        {
            return "<red>failing</red>";
        }
        return "<green>ok</green>";
    }

    private static String updatesState(@Nullable JsonElement wandb)
    {
        if (!(wandb instanceof JsonObject status))
        {
            return "<yellow>not set up</yellow>";
        }
        String result = string(status, "result");
        return result.isEmpty() || "ok".equals(result) ? "<green>ok</green>" : "<red>failing</red>";
    }

    private static JsonObject object(JsonObject parent, String key)
    {
        return parent.get(key) instanceof JsonObject child ? child : new JsonObject();
    }

    private static String string(JsonObject parent, String key)
    {
        return parent.get(key) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    private static int number(JsonObject parent, String key)
    {
        return parent.get(key) instanceof JsonPrimitive value && value.isNumber() ? value.getAsInt() : 0;
    }

    // A list of player names, or "none".
    private static String names(JsonObject parent, String key)
    {
        if (!(parent.get(key) instanceof JsonArray list) || list.isEmpty())
        {
            return "none";
        }
        List<String> names = new ArrayList<>();
        list.forEach(name -> names.add(name.getAsString()));
        return String.join(", ", names);
    }

    private static String ago(Instant since)
    {
        Duration duration = Duration.between(since, Instant.now());
        if (duration.toHours() > 0)
        {
            return duration.toHours() + "h " + duration.toMinutesPart() + "m";
        }
        return duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
    }
}
