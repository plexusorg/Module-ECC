package dev.plex.listener;

import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Captures /nick, signs, books and anvil item names for chat safety. A shadow-muted player's sign shows its text to
 * them alone; their nickname, book and item name changes do not happen, because those cannot be shown to one player.
 */
public class SafetyListener implements Listener
{
    private static final Set<String> NICK_COMMANDS = Set.of("nick", "enick", "nickname", "enickname", "nickmm",
            "nickminimessage");
    private static final int ANVIL_RESULT_SLOT = 2;

    private final ECCModule module;
    private final ChatSafety safety;

    public SafetyListener(ECCModule module, ChatSafety safety)
    {
        this.module = module;
        this.safety = safety;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNick(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        String label = parts[0].substring(1).toLowerCase(Locale.ROOT);
        if (parts.length < 2 || !NICK_COMMANDS.contains(label.substring(label.indexOf(':') + 1)))
        {
            return;
        }
        // "/nick <player> <nickname>" sets another player's nickname; the sender is still the author.
        String[] arguments = parts[1].strip().split(" ", 2);
        String nick = arguments.length == 2 && Bukkit.getPlayerExact(arguments[0]) != null ? arguments[1] : parts[1];
        if (nick.strip().equalsIgnoreCase("off"))
        {
            return;
        }
        Player player = event.getPlayer();
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            event.setCancelled(true);
        }
        safety.capture(player, ChatSafety.NICK, ChatSafety.PUBLIC_CONVERSATION, null, null, nick.strip(), shadowed);
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
        boolean shadowed = safety.isShadowMuted(uuid);
        safety.capture(player, ChatSafety.SIGN, ChatSafety.ownConversation(ChatSafety.SIGN, uuid), null, null,
                text.toString(), shadowed);
        if (!shadowed || text.length() == 0)
        {
            return;
        }
        for (int i = 0; i < lines.size(); i++)
        {
            event.line(i, Component.empty());
        }
        // The blank sign reaches everyone; one tick later its author alone is sent the text they wrote.
        Location location = event.getBlock().getLocation();
        Side side = event.getSide();
        module.ownTask(Bukkit.getRegionScheduler().runDelayed(module.plugin(), location,
                task -> showOwnSign(player, location, side, lines), 1));
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
        // Only pages this edit changed; earlier text was captured when it was written.
        List<Component> before = previous.pages();
        List<Component> after = next.pages();
        for (int i = 0; i < after.size(); i++)
        {
            if (i >= before.size() || !after.get(i).equals(before.get(i)))
            {
                text.add(PlainTextComponentSerializer.plainText().serialize(after.get(i)).strip());
            }
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean shadowed = safety.isShadowMuted(uuid);
        if (shadowed)
        {
            event.setCancelled(true);
        }
        safety.capture(player, ChatSafety.BOOK, ChatSafety.ownConversation(ChatSafety.BOOK, uuid), null, null,
                text.toString().strip(), shadowed);
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
        boolean shadowed = safety.isShadowMuted(uuid);
        if (shadowed)
        {
            event.setCancelled(true);
        }
        safety.capture(player, ChatSafety.ANVIL, ChatSafety.ownConversation(ChatSafety.ANVIL, uuid), null, null,
                name.strip(), shadowed);
    }
}
