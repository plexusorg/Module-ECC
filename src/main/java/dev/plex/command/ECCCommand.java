package dev.plex.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.Duration;
import java.time.Instant;

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
            String host = status.hostReady() == null ? "<yellow>not contacted yet</yellow>"
                    : status.hostReady() ? "<green>ready</green>" : "<red>not scoring</red>";
            sender.sendMessage(messageComponent("chatSafetyHost",
                    Placeholder.parsed("state", host),
                    Placeholder.unparsed("detail", status.hostDetail()),
                    Placeholder.unparsed("since", ago(status.hostSince()))));
            sender.sendMessage(messageComponent("chatSafetyBundle",
                    Placeholder.unparsed("bundle", status.bundle() == null ? "none read yet" : status.bundle()),
                    Placeholder.unparsed("backlog", String.valueOf(status.backlog()))));
            return messageComponent("chatSafetyPending",
                    Placeholder.unparsed("count", String.valueOf(status.pending().size())),
                    Placeholder.unparsed("players", status.pending().isEmpty() ? "none" : String.join(", ", status.pending())));
        })));
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
