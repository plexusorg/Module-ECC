package dev.plex.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
            sender.sendMessage(messageComponent("chatSafetyPending",
                    Placeholder.unparsed("count", String.valueOf(status.pending().size())),
                    Placeholder.unparsed("players", names(status.pending()))));
            return slowCheck(status);
        })));
    }

    private Component slowCheck(ChatSafety.Status status)
    {
        String state;
        if (status.bedrockAnswering() == null)
        {
            state = "<yellow>not called yet</yellow>";
        }
        else if (status.bedrockAnswering())
        {
            state = "<green>answering</green> for " + ago(status.bedrockSince());
        }
        else
        {
            state = "<red>failing</red> for " + ago(status.bedrockSince()) + " <dark_gray>("
                    + MiniMessage.miniMessage().escapeTags(status.bedrockFailure()) + ")</dark_gray>";
        }
        return messageComponent("chatSafetySlowCheck",
                Placeholder.parsed("state", state),
                Placeholder.unparsed("checking", names(status.checking())),
                Placeholder.unparsed("held", names(status.held())));
    }

    private static String names(List<String> players)
    {
        return players.isEmpty() ? "none" : String.join(", ", players);
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
