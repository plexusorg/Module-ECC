package dev.plex.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;

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
            showStatus(sender, safety.status());
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

    // Two lines: whether ECC-Server answers, and what the module holds.
    private void showStatus(CommandSender sender, ChatSafety.Status status)
    {
        TagResolver since = Placeholder.unparsed("since", ago(status.since()));
        sender.sendMessage(messageComponent(status.answering() ? "chatSafetyAnswering" : "chatSafetyUnreachable",
                since));
        sender.sendMessage(messageComponent("chatSafetyHeldCount",
                Placeholder.unparsed("held", String.valueOf(status.held())),
                Placeholder.unparsed("unanswered", String.valueOf(status.unanswered())),
                Placeholder.unparsed("unacknowledged", String.valueOf(status.unacknowledged()))));
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
