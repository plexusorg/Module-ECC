package dev.plex.listener;

import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import dev.plex.ecc.safety.Line;
import dev.plex.ecc.safety.LogReading;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /nick, Minecraft's own message commands, the commands that set signs, item names and tags, signs, books and anvil
 * item names. Each is stopped until ECC-Server lets it through, then carried out by the module: the command is run
 * again as its author, the sign's text is written, the book's edit is applied. A sign shows its new text to its author
 * alone meanwhile. An anvil click cannot be resumed, so a checked name is let through on the author's next click.
 */
public class SafetyListener implements Listener
{
    private static final Set<String> NICK_COMMANDS = Set.of("nick", "enick", "nickname", "enickname", "nickmm",
            "nickminimessage");
    private static final Set<String> PRIVATE_COMMANDS = Set.of("msg", "tell", "w");
    private static final Set<String> TEAM_COMMANDS = Set.of("teammsg", "tm");
    private static final String SAY = "say";
    private static final Map<String, String> SET_COMMANDS = Map.ofEntries(
            Map.entry("sign", ChatSafety.SIGN), Map.entry("editsign", ChatSafety.SIGN),
            Map.entry("itemname", ChatSafety.ITEM), Map.entry("iname", ChatSafety.ITEM),
            Map.entry("irename", ChatSafety.ITEM), Map.entry("itemrename", ChatSafety.ITEM),
            Map.entry("rename", ChatSafety.ITEM), Map.entry("lore", ChatSafety.ITEM),
            Map.entry("itemlore", ChatSafety.ITEM), Map.entry("tag", ChatSafety.TAG),
            Map.entry("prefix", ChatSafety.TAG));
    private static final int ANVIL_RESULT_SLOT = 2;

    private final ECCModule module;
    private final ChatSafety safety;
    // Item names ECC-Server let through, by author, until their next click on an anvil result with that name.
    private final Map<UUID, String> checkedNames = new ConcurrentHashMap<>();

    public SafetyListener(ECCModule module, ChatSafety safety)
    {
        this.module = module;
        this.safety = safety;
    }

    // /nick as the training pipeline read it from the command's log line (ingest.command and dossier.py): the
    // nickname is the arguments, or what follows the first word when that word is an online player's name and more
    // follows; then that player's nickname is set, and the line names them as its target when it is not the author.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNick(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        if (parts.length < 2 || LogReading.isBlank(parts[1]) || !NICK_COMMANDS.contains(commandName(parts[0])))
        {
            return;
        }
        String rest = parts[1];
        int space = rest.indexOf(' ');
        Player owner = space < 0 ? null : Bukkit.getPlayerExact(rest.substring(0, space));
        String more = space < 0 ? "" : rest.substring(space + 1);
        String nick = owner != null && !LogReading.isBlank(more) ? more : rest;
        if (LogReading.strip(nick).toLowerCase(Locale.ROOT).equals("off"))
        {
            return;
        }
        Player author = safety.commandAuthor(event);
        Line line = owner == null || owner.getUniqueId().equals(author.getUniqueId())
                ? Line.of(author, ChatSafety.NICK, nick)
                : Line.to(author, ChatSafety.NICK, owner.getUniqueId(), owner.getName(), nick);
        rerun(event, author, line);
    }

    // Commands that set a sign's text, an item's name or lore, or a tag, by the name typed after any plugin: prefix,
    // whichever plugin runs them, as the training pipeline read them from the command's log line; and the other names
    // of EssentialsX's commands among them (/itemlore, /eitemname, /esign), which training did not see.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSetCommand(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        String channel = SET_COMMANDS.get(commandName(parts[0]));
        if (channel == null && Bukkit.getCommandMap().getCommand(parts[0].substring(1)) instanceof Command command)
        {
            channel = SET_COMMANDS.get(command.getName().toLowerCase(Locale.ROOT));
        }
        if (channel == null || parts.length < 2)
        {
            return;
        }
        String text = LogReading.commandText(parts[1]);
        if (text == null)
        {
            return;
        }
        Player author = safety.commandAuthor(event);
        rerun(event, author, Line.of(author, channel, text));
    }

    // Minecraft's own /me, /msg, /tell, /w, /teammsg, /tm and /say, by any label that reaches them. Team messages go
    // as guild chat and /say as public chat; the training pipeline had neither.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaMessage(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        Command command = Bukkit.getCommandMap().getCommand(parts[0].substring(1));
        if (parts.length < 2 || LogReading.isBlank(parts[1]) || command == null || command instanceof PluginIdentifiableCommand)
        {
            return;
        }
        Player author = safety.commandAuthor(event);
        String name = commandName(command.getName());
        Line line;
        if (PRIVATE_COMMANDS.contains(name))
        {
            String rest = parts[1];
            int space = rest.indexOf(' ');
            String text = space < 0 ? "" : rest.substring(space + 1);
            if (LogReading.isBlank(text))
            {
                return;
            }
            String typed = rest.substring(0, space);
            Player target = Bukkit.getPlayerExact(typed);
            line = target == null ? Line.to(author, ChatSafety.MSG, null, typed, text)
                    : Line.to(author, ChatSafety.MSG, target.getUniqueId(), target.getName(), text);
        }
        else if (TEAM_COMMANDS.contains(name))
        {
            line = Line.shown(author, ChatSafety.GUILD, author.getName(), parts[1]);
        }
        else if (ChatSafety.ME.equals(name))
        {
            line = Line.of(author, ChatSafety.ME, parts[1]);
        }
        else if (SAY.equals(name))
        {
            line = Line.shown(author, ChatSafety.CHAT,
                    PlainTextComponentSerializer.plainText().serialize(event.getPlayer().displayName()), parts[1]);
        }
        else
        {
            return;
        }
        rerun(event, author, line);
    }

    // The command's name or typed label, lower case, after its optional slash and namespace.
    static String commandName(String label)
    {
        String name = label.toLowerCase(Locale.ROOT);
        if (name.startsWith("/"))
        {
            name = name.substring(1);
        }
        return name.substring(name.lastIndexOf(':') + 1);
    }

    // Stops the command and runs it again as the player who ran it once the line may be delivered; the line is its
    // author's, who forced it with /sudo or ran it. A command whose player has left by then is not run.
    private void rerun(PlayerCommandPreprocessEvent event, Player author, Line line)
    {
        Player player = event.getPlayer();
        String command = event.getMessage().substring(1);
        if (!safety.check(author, line, () -> Commands.perform(module, player, command, () ->
                module.getLogger().info("Not running {}'s checked command, since they left: /{}", player.getName(),
                        command))))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event)
    {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        List<Component> lines = new ArrayList<>();
        StringJoiner text = new StringJoiner(" ");
        for (Component line : event.lines())
        {
            Component shown = Objects.requireNonNullElse(line, Component.empty());
            lines.add(shown);
            String plain = PlainTextComponentSerializer.plainText().serialize(shown).strip();
            if (!plain.isEmpty())
            {
                text.add(plain);
            }
        }
        if (text.length() == 0)
        {
            return;
        }
        Location location = event.getBlock().getLocation();
        Side side = event.getSide();
        Line line = Line.of(player, ChatSafety.SIGN, text.toString());
        if (safety.check(player, line, () -> module.ownTask(Bukkit.getRegionScheduler().run(module.plugin(), location,
                task -> writeSign(location, side, lines)))))
        {
            return;
        }
        // The blank side reaches everyone; one tick later its author alone is sent the text they wrote.
        for (int i = 0; i < lines.size(); i++)
        {
            event.line(i, Component.empty());
        }
        module.ownTask(Bukkit.getRegionScheduler().runDelayed(module.plugin(), location,
                task -> showOwnSign(player, location, side, lines), 1));
    }

    // Writes the checked text, unless the side was broken or written on meanwhile.
    private void writeSign(Location location, Side side, List<Component> lines)
    {
        if (!(location.getBlock().getState() instanceof Sign sign))
        {
            module.getLogger().info("Not writing a checked sign at {}: the sign is gone", location);
            return;
        }
        SignSide signSide = sign.getSide(side);
        if (signSide.lines().stream().anyMatch(line -> !PlainTextComponentSerializer.plainText().serialize(line)
                .isBlank()))
        {
            module.getLogger().info("Not writing a checked sign at {}: it was written on meanwhile", location);
            return;
        }
        for (int i = 0; i < lines.size(); i++)
        {
            signSide.line(i, lines.get(i));
        }
        sign.update();
    }

    private void showOwnSign(Player player, Location location, Side side, List<Component> lines)
    {
        if (!player.isOnline() || !(location.getBlock().getState() instanceof Sign sign))
        {
            return;
        }
        SignSide signSide = sign.getSide(side);
        for (int i = 0; i < lines.size(); i++)
        {
            signSide.line(i, lines.get(i));
        }
        player.sendBlockUpdate(location, sign);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event)
    {
        BookMeta previous = event.getPreviousBookMeta();
        BookMeta next = event.getNewBookMeta();
        StringJoiner text = new StringJoiner(" ");
        if (event.isSigning() && next.title() != null)
        {
            text.add(PlainTextComponentSerializer.plainText().serialize(next.title()));
        }
        // Only pages this edit changed; earlier text was checked when it was written.
        List<Component> before = previous.pages();
        List<Component> after = next.pages();
        for (int i = 0; i < after.size(); i++)
        {
            if (i >= before.size() || !after.get(i).equals(before.get(i)))
            {
                text.add(PlainTextComponentSerializer.plainText().serialize(after.get(i)).strip());
            }
        }
        if (text.toString().isBlank())
        {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getSlot() < 0 ? EquipmentSlot.OFF_HAND : null;
        int slot = event.getSlot();
        boolean signing = event.isSigning();
        Line line = Line.of(player, ChatSafety.BOOK, text.toString().strip());
        if (!safety.check(player, line, () -> applyBook(player, hand, slot, previous, next, signing)))
        {
            event.setCancelled(true);
        }
    }

    // Applies the checked edit to the book it was made on, if that book is still in the same slot and unchanged.
    private void applyBook(Player player, EquipmentSlot hand, int slot, BookMeta previous, BookMeta next,
                           boolean signing)
    {
        if (module.ownTask(player.getScheduler().run(module.plugin(), task ->
        {
            ItemStack item = hand == null ? player.getInventory().getItem(slot)
                    : player.getInventory().getItem(hand);
            if (item == null || item.getType() != Material.WRITABLE_BOOK || !previous.equals(item.getItemMeta()))
            {
                module.getLogger().info("Not applying {}'s checked book edit: the book left its slot or changed",
                        player.getName());
                return;
            }
            ItemStack edited = signing ? item.withType(Material.WRITTEN_BOOK) : item;
            edited.setItemMeta(signing ? signed(next) : next);
            if (hand == null)
            {
                player.getInventory().setItem(slot, edited);
            }
            else
            {
                player.getInventory().setItem(hand, edited);
            }
        }, null)) == null)
        {
            module.getLogger().info("Not applying {}'s checked book edit: they left", player.getName());
        }
    }

    // A signed book's meta with the edit's title, author and pages.
    private static BookMeta signed(BookMeta next)
    {
        BookMeta meta = (BookMeta) Bukkit.getItemFactory().getItemMeta(Material.WRITTEN_BOOK);
        meta.title(next.title());
        meta.author(next.author());
        meta.pages(next.pages());
        return meta;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event)
    {
        if (!(event.getView() instanceof AnvilView view) || event.getRawSlot() != ANVIL_RESULT_SLOT
                || !(event.getWhoClicked() instanceof Player player))
        {
            return;
        }
        ItemStack result = event.getCurrentItem();
        String name = view.getRenameText();
        if (result == null || result.isEmpty() || name == null || name.isBlank())
        {
            return;
        }
        UUID uuid = player.getUniqueId();
        String stripped = name.strip();
        if (checkedNames.remove(uuid, stripped))
        {
            return;
        }
        Line line = Line.of(player, ChatSafety.ITEM, stripped);
        if (!safety.check(player, line, () ->
        {
            checkedNames.put(uuid, stripped);
            player.sendMessage(safety.itemNameChecked());
        }))
        {
            event.setCancelled(true);
        }
    }
}
