package dev.plex.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine.LogEntry;
import dev.plex.ecc.nush.Quarantine.Restriction;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class NUSHCommand extends SimplePlexCommand
{
    private static final List<String> ACTIONS = List.of("on", "off", "status", "time", "kick", "allow", "revoke",
            "list", "log", "feed");
    private static final String ALLOW_PERMISSION = "plex.nush.allow";

    private final ECCModule module;

    public NUSHCommand(ECCModule module)
    {
        super(command("nush")
                .description("The main command to manage the NUSH module")
                .usage("/<command> <on | off | status | time <minutes> | kick "
                        + "| allow <player> | revoke <player> | list | log <player> | feed>")
                .permission("plex.nush.use")
                .build());
        this.module = module;
    }

    @Override
    protected void configureCommand(LiteralArgumentBuilder<CommandSourceStack> command)
    {
        command.executes(context -> executeCommand(context, (sender, player) -> usage()));
        command.then(word("action")
                .suggests((context, builder) -> suggestMatching(builder, ACTIONS))
                .executes(context -> executeCommand(context,
                        (sender, player) -> executeAction(sender, string(context, "action"), null)))
                .then(word("value")
                        .suggests((context, builder) -> suggestValues(builder, string(context, "action")))
                        .executes(context -> executeCommand(context, (sender, player) -> executeAction(sender,
                                string(context, "action"), string(context, "value"))))
                        .then(greedyString("extra").executes(context ->
                                executeCommand(context, (sender, player) -> usage())))));
    }

    private CompletableFuture<Suggestions> suggestValues(SuggestionsBuilder builder, String action)
    {
        String normalized = action.toLowerCase(Locale.ROOT);
        if (normalized.equals("allow"))
        {
            Set<String> names = new LinkedHashSet<>(onlinePlayerNames());
            names.addAll(restrictedNames());
            return suggestMatching(builder, names);
        }
        if (normalized.equals("revoke"))
        {
            return suggestMatching(builder, onlinePlayerNames());
        }
        if (normalized.equals("log"))
        {
            return suggestMatching(builder, restrictedNames());
        }
        return builder.buildFuture();
    }

    private Component executeAction(CommandSender sender, String action, @Nullable String value)
    {
        if (value == null)
        {
            switch (action.toLowerCase(Locale.ROOT))
            {
                case "on" ->
                {
                    module.toggle(true);
                    return messageComponent("nushEnabled");
                }

                case "off" ->
                {
                    module.toggle(false);
                    return messageComponent("nushDisabled");
                }

                case "status" ->
                {
                    return messageComponent("nushStatus",
                            Placeholder.parsed("status", module.isEnabled() ? "<green>enabled</green>" : "<red>disabled</red>"),
                            Placeholder.unparsed("detection", module.raidDetector().status()),
                            Placeholder.unparsed("restricted", String.valueOf(module.quarantine().entries().size())));
                }

                case "kick" ->
                {
                    return kick(sender);
                }

                case "list" ->
                {
                    return list(sender);
                }

                case "feed" ->
                {
                    return toggleFeed(sender);
                }

                default ->
                {
                    return usage();
                }
            }
        }

        switch (action.toLowerCase(Locale.ROOT))
        {
            case "time" ->
            {
                return setTime(value);
            }

            case "allow" ->
            {
                return allow(sender, value);
            }

            case "revoke" ->
            {
                return revoke(sender, value);
            }

            case "log" ->
            {
                return log(sender, value);
            }

            default ->
            {
                return usage();
            }
        }
    }

    private Component setTime(String value)
    {
        int minutes;
        try
        {
            minutes = Integer.parseInt(value);
        }
        catch (NumberFormatException ex)
        {
            return messageComponent("timeMustBeNumber");
        }

        if (minutes < 1)
        {
            return messageComponent("timeMinimum");
        }
        module.setTime(minutes);
        return messageComponent("waitTimeSet", Placeholder.unparsed("minutes", String.valueOf(minutes)));
    }

    private Component kick(CommandSender sender)
    {
        int kicked = module.quarantine().kick(messageComponent("kickMessage"));
        Component result = messageComponent("playersKicked", Placeholder.unparsed("count", String.valueOf(kicked)),
                Placeholder.unparsed("admin", sender.getName()));
        module.feed().alert(result);
        return null;
    }

    private Component allow(CommandSender sender, String name)
    {
        checkPermission(sender, ALLOW_PERMISSION);
        resolveRestriction(sender, name, restriction -> allow(sender, restriction));
        return null;
    }

    private void allow(CommandSender sender, Restriction restriction)
    {
        String admin = sender.getName();
        module.quarantine().verify(restriction.uuid(), admin)
                .whenComplete((ignored, failure) ->
                {
                    if (failure != null)
                    {
                        module.getLogger().error("Unable to store the NUSH staff trust of {}", restriction.uuid(), failure);
                        sender.sendMessage(messageComponent("trustUpdateFailed"));
                        return;
                    }
                    if (!module.feed().receivesAlerts(sender))
                    {
                        sender.sendMessage(messageComponent("playerAllowed",
                                Placeholder.unparsed("player", restriction.name()),
                                Placeholder.unparsed("admin", admin)));
                    }
                });
    }

    private Component revoke(CommandSender sender, String name)
    {
        checkPermission(sender, ALLOW_PERMISSION);
        Player target = getNonNullPlayer(name);
        String targetName = target.getName();
        String admin = sender.getName();
        module.quarantine().revoke(target.getUniqueId()).whenComplete((ignored, failure) ->
        {
            if (failure != null)
            {
                module.getLogger().error("Unable to remove the NUSH staff trust of {}", target.getUniqueId(), failure);
                sender.sendMessage(messageComponent("trustUpdateFailed"));
                return;
            }
            Component revoked = messageComponent("playerRevoked", Placeholder.unparsed("player", targetName),
                    Placeholder.unparsed("admin", admin));
            module.feed().alert(revoked);
            if (!module.feed().receivesAlerts(sender))
            {
                sender.sendMessage(revoked);
            }
        });
        return null;
    }

    private Component list(CommandSender sender)
    {
        Collection<Restriction> restrictions = module.quarantine().entries();
        if (restrictions.isEmpty())
        {
            return messageComponent("listEmpty");
        }

        sender.sendMessage(messageComponent("listHeader"));
        for (Restriction restriction : restrictions)
        {
            long minutes = (restriction.remainingSeconds() + 59) / 60;
            sender.sendMessage(messageComponent("listEntry",
                    Placeholder.unparsed("player", restriction.name()),
                    Placeholder.unparsed("state", Bukkit.getPlayer(restriction.uuid()) == null ? "offline" : "online"),
                    Placeholder.unparsed("minutes", String.valueOf(minutes)),
                    Placeholder.unparsed("messages", String.valueOf(restriction.messages())),
                    Placeholder.unparsed("commands", String.valueOf(restriction.blockedCommands()))));
        }
        return null;
    }

    private Component log(CommandSender sender, String name)
    {
        resolveRestriction(sender, name, restriction -> showLog(sender, restriction));
        return null;
    }

    private void resolveRestriction(CommandSender sender, String name, Consumer<Restriction> action)
    {
        api().players().resolveCommandPlayer(name).whenComplete((target, failure) ->
        {
            if (failure != null)
            {
                if (!sendPlayerLookupFailure(sender, failure))
                {
                    module.getLogger().error("Unable to look up NUSH target {}", name, failure);
                    sender.sendMessage(Component.text("Player lookup failed."));
                }
                return;
            }
            Restriction resolved = target.map(view -> module.quarantine().entry(view.uuid())).orElse(null);
            if (resolved == null)
            {
                sender.sendMessage(messageComponent("playerNotNushed"));
                return;
            }
            action.accept(resolved);
        });
    }

    private void showLog(CommandSender sender, Restriction restriction)
    {
        sender.sendMessage(messageComponent("logHeader", Placeholder.unparsed("player", restriction.name())));
        Instant now = Instant.now();
        for (LogEntry entry : restriction.log())
        {
            sender.sendMessage(messageComponent("logEntry",
                    Placeholder.unparsed("ago", String.valueOf(Duration.between(entry.time(), now).toSeconds())),
                    Placeholder.unparsed("kind", entry.kind().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("text", entry.text())));
        }
    }

    private Component toggleFeed(CommandSender sender)
    {
        if (isConsole(sender))
        {
            return messageComponent("noPermissionConsole");
        }
        return messageComponent(module.feed().toggleMute(getUUID(sender)) ? "feedMuted" : "feedUnmuted");
    }

    private Set<String> restrictedNames()
    {
        Set<String> names = new LinkedHashSet<>();
        for (Restriction restriction : module.quarantine().entries())
        {
            names.add(restriction.name());
        }
        return names;
    }

}
