package dev.plex.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class ECCCommand extends SimplePlexCommand
{
    private final ECCModule module;

    public ECCCommand(ECCModule module)
    {
        super(command("ecc")
                .description("Show the state of chat safety")
                .usage("/<command> status")
                .permission("plex.ecc.status")
                .build());
        this.module = module;
    }

    @Override
    protected void configureCommand(LiteralArgumentBuilder<CommandSourceStack> command)
    {
        command.executes(context -> executeCommand(context, (sender, player) -> usage()));
        command.then(Commands.literal("status").executes(context -> executeCommand(context, (sender, player) ->
        {
            ChatSafety safety = module.chatSafety();
            if (safety == null)
            {
                return messageComponent("chatSafetyOff");
            }
            ChatSafety.Status status = safety.status();
            String link = status.reachable() == null ? "<yellow>not contacted yet</yellow>"
                    : status.reachable() ? "<green>reachable</green>" : "<red>unreachable</red>";
            sender.sendMessage(messageComponent("chatSafetyLink",
                    Placeholder.parsed("state", link),
                    Placeholder.unparsed("detail", status.detail()),
                    Placeholder.unparsed("since", ago(status.since())),
                    Placeholder.unparsed("buffered", String.valueOf(status.buffered())),
                    Placeholder.unparsed("unacknowledged", String.valueOf(status.unacknowledged())),
                    Placeholder.unparsed("muted", String.valueOf(status.muted()))));
            safety.serverStatus().whenComplete((server, failure) -> showServer(sender, server, failure));
            return null;
        })));
    }

    // Each field of ECC-Server's GET /status on its own line, as the server reports it.
    private void showServer(CommandSender sender, @Nullable JsonObject server, @Nullable Throwable failure)
    {
        if (failure != null)
        {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            sender.sendMessage(messageComponent("chatSafetyServerUnavailable",
                    Placeholder.unparsed("reason", String.valueOf(cause.getMessage()))));
            return;
        }
        sender.sendMessage(messageComponent("chatSafetyServer"));
        for (Map.Entry<String, JsonElement> field : server.entrySet())
        {
            JsonElement value = field.getValue();
            sender.sendMessage(messageComponent("chatSafetyServerField",
                    Placeholder.unparsed("field", field.getKey()),
                    Placeholder.unparsed("value", value.isJsonPrimitive() ? value.getAsString() : value.toString())));
        }
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
