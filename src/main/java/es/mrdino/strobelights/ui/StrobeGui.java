package es.mrdino.strobelights.ui;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.model.StrobeMode;
import es.mrdino.strobelights.util.StrobeColors;
import es.mrdino.strobelights.util.StrobeTiming;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

public final class StrobeGui implements Listener {

    private static final String PREFIX = ChatColor.DARK_AQUA + "[StrobeLights] " + ChatColor.RESET;
    private static final int LIST_SIZE = 27;
    private static final int PAGE_SIZE = 18;
    private static final int LIST_EMPTY_SLOT = 13;
    private static final int LIST_START_ALL_SLOT = 18;
    private static final int LIST_STOP_ALL_SLOT = 19;
    private static final int LIST_DISCOVERY_SLOT = 20;
    private static final int LIST_PREVIOUS_SLOT = 21;
    private static final int LIST_CREATE_SLOT = 22;
    private static final int LIST_NEXT_SLOT = 23;
    private static final int LIST_PACK_SLOT = 25;
    private static final int LIST_CLOSE_SLOT = 26;
    private static final int EDITOR_SIZE = 27;
    private static final int EDITOR_BACK_SLOT = 18;
    private static final int EDITOR_CLOSE_SLOT = 26;
    private static final int PALETTE_SIZE = 45;
    private static final int PALETTE_BACK_SLOT = 36;
    private static final int PALETTE_CLOSE_SLOT = 44;
    private static final int DELETE_SIZE = 27;
    private static final int DELETE_BACK_SLOT = 18;
    private static final int DELETE_CLOSE_SLOT = 26;
    private static final int[] PALETTE_SLOTS = {
        9, 10, 11, 12, 13, 14, 15, 16,
        18, 19, 20, 21, 22, 23, 24, 25
    };
    private static final List<PaletteColor> PALETTE = List.of(
        new PaletteColor("white", 0xFFFFFF, Material.WHITE_STAINED_GLASS),
        new PaletteColor("red", 0xFF0000, Material.RED_STAINED_GLASS),
        new PaletteColor("orange", 0xFF7A00, Material.ORANGE_STAINED_GLASS),
        new PaletteColor("yellow", 0xFFFF00, Material.YELLOW_STAINED_GLASS),
        new PaletteColor("lime", 0x7FFF00, Material.LIME_STAINED_GLASS),
        new PaletteColor("green", 0x00FF3C, Material.GREEN_STAINED_GLASS),
        new PaletteColor("cyan", 0x00FFFF, Material.CYAN_STAINED_GLASS),
        new PaletteColor("light-blue", 0x38BDF8, Material.LIGHT_BLUE_STAINED_GLASS),
        new PaletteColor("blue", 0x0066FF, Material.BLUE_STAINED_GLASS),
        new PaletteColor("purple", 0x8A2BE2, Material.PURPLE_STAINED_GLASS),
        new PaletteColor("magenta", 0xFF00FF, Material.MAGENTA_STAINED_GLASS),
        new PaletteColor("pink", 0xFF69B4, Material.PINK_STAINED_GLASS),
        new PaletteColor("light-gray", 0xBFC5C5, Material.LIGHT_GRAY_STAINED_GLASS),
        new PaletteColor("gray", 0x60686C, Material.GRAY_STAINED_GLASS),
        new PaletteColor("brown", 0x8B572A, Material.BROWN_STAINED_GLASS),
        new PaletteColor("black", 0x151515, Material.BLACK_STAINED_GLASS)
    );

    private final StrobeLightsPlugin plugin;
    private final Map<UUID, PlacementRequest> pendingPlacements = new ConcurrentHashMap<>();
    private final Map<UUID, NameRequest> pendingNames = new ConcurrentHashMap<>();

    public StrobeGui(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        pendingPlacements.remove(player.getUniqueId());
        pendingNames.remove(player.getUniqueId());
        openList(player, 0);
    }

    public boolean open(Player player, String strobeName) {
        pendingPlacements.remove(player.getUniqueId());
        pendingNames.remove(player.getUniqueId());
        Optional<Strobe> strobe = plugin.manager().find(strobeName);
        strobe.ifPresent(value -> openEditor(player, value, 0));
        return strobe.isPresent();
    }

    public void closeAll() {
        pendingPlacements.clear();
        pendingNames.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
                player.closeInventory();
            }
        }
    }

    private void openList(Player player, int requestedPage) {
        List<String> names = plugin.manager().all().stream().map(Strobe::name).toList();
        int maximumPage = Math.max(0, (names.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(maximumPage, requestedPage));
        GuiHolder holder = new GuiHolder(Screen.LIST, null, page, names);
        Inventory inventory = Bukkit.createInventory(
            holder,
            LIST_SIZE,
            Component.text(tr(player, "gui.list.title", "page", page + 1,
                "pages", maximumPage + 1), NamedTextColor.DARK_AQUA)
        );
        holder.inventory = inventory;

        int start = page * PAGE_SIZE;
        int end = Math.min(names.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            Optional<Strobe> optional = plugin.manager().find(names.get(index));
            if (optional.isPresent()) {
                inventory.setItem(index - start, strobeItem(player, optional.get()));
            }
        }
        if (names.isEmpty()) {
            inventory.setItem(LIST_EMPTY_SLOT, item(
                GuiIcon.NO_STROBES,
                title(tr(player, "gui.list.empty.title"), NamedTextColor.GRAY),
                lore(tr(player, "gui.list.empty.lore")),
                false
            ));
        }

        inventory.setItem(LIST_START_ALL_SLOT, item(
            GuiIcon.POWER_ON,
            title(tr(player, "gui.list.start-all.title"), NamedTextColor.GREEN),
            lore(tr(player, "gui.list.start-all.lore")),
            false
        ));
        inventory.setItem(LIST_STOP_ALL_SLOT, item(
            GuiIcon.POWER_OFF,
            title(tr(player, "gui.list.stop-all.title"), NamedTextColor.RED),
            lore(tr(player, "gui.list.stop-all.lore")),
            false
        ));
        boolean discovery = plugin.manager().isManualDiscovery(player);
        inventory.setItem(LIST_DISCOVERY_SLOT, item(
            GuiIcon.DISCOVERY,
            title(tr(player, "gui.list.discovery.title"), NamedTextColor.AQUA),
            lore(
                tr(player, "gui.list.discovery.status", "state",
                    tr(player, discovery ? "state.enabled" : "state.disabled")),
                tr(player, "gui.list.discovery.range", "range",
                    String.format(Locale.ROOT, "%.0f", plugin.manager().discoveryRange())),
                tr(player, "gui.list.discovery.lore")
            ),
            discovery
        ));
        if (page > 0) {
            inventory.setItem(LIST_PREVIOUS_SLOT, item(
                GuiIcon.PREVIOUS,
                title(tr(player, "gui.common.previous-page"), NamedTextColor.AQUA),
                List.of(),
                false
            ));
        }
        inventory.setItem(LIST_CREATE_SLOT, item(
            GuiIcon.CREATE,
            title(tr(player, "gui.list.create.title"), NamedTextColor.YELLOW),
            lore(
                tr(player, "gui.list.create.lore-1"),
                tr(player, "gui.list.create.lore-2")
            ),
            false
        ));
        if (page < maximumPage) {
            inventory.setItem(LIST_NEXT_SLOT, item(
                GuiIcon.NEXT,
                title(tr(player, "gui.common.next-page"), NamedTextColor.AQUA),
                List.of(),
                false
            ));
        }
        boolean packLoaded = plugin.resourcePack() != null
            && plugin.resourcePack().isLoaded(player);
        inventory.setItem(LIST_PACK_SLOT, item(
            GuiIcon.RESOURCE_PACK,
            title(tr(player, "gui.list.pack.title"), packLoaded ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
            lore(
                tr(player, "gui.list.pack.status", "status",
                    tr(player, packLoaded ? "state.loaded" : "state.pending")),
                tr(player, "gui.list.pack.lore")
            ),
            packLoaded
        ));
        inventory.setItem(LIST_CLOSE_SLOT, item(
            GuiIcon.CLOSE,
            title(tr(player, "gui.common.close"), NamedTextColor.RED),
            List.of(),
            false
        ));
        player.openInventory(inventory);
    }

    private void openCreateName(Player player, int returnPage) {
        if (plugin.manager().size() >= plugin.manager().maximumStrobes()) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.maximum-strobes"));
            return;
        }
        pendingNames.put(
            player.getUniqueId(),
            new NameRequest(NameMode.CREATE, null, returnPage)
        );
        player.closeInventory();
        player.sendMessage(PREFIX + ChatColor.AQUA + tr(player, "message.create-name-prompt"));
        player.sendMessage(PREFIX + ChatColor.GRAY + tr(player, "message.name-rules"));
    }

    private void openRename(Player player, Strobe strobe, int returnPage) {
        pendingNames.put(
            player.getUniqueId(),
            new NameRequest(NameMode.RENAME, strobe.name(), returnPage)
        );
        player.closeInventory();
        player.sendMessage(PREFIX + ChatColor.AQUA
            + tr(player, "message.rename-prompt", "name", strobe.name()));
        player.sendMessage(PREFIX + ChatColor.GRAY + tr(player, "message.cancel-help"));
    }

    private void openEditor(Player player, Strobe strobe, int returnPage) {
        GuiHolder holder = new GuiHolder(Screen.EDITOR, strobe.name(), returnPage, List.of());
        Inventory inventory = Bukkit.createInventory(
            holder,
            EDITOR_SIZE,
            Component.text(
                tr(player, "gui.editor.title", "name", strobe.name()),
                NamedTextColor.DARK_AQUA
            )
        );
        holder.inventory = inventory;

        inventory.setItem(4, strobeItem(player, strobe));
        inventory.setItem(EDITOR_BACK_SLOT, item(
            GuiIcon.BACK,
            title(tr(player, "gui.editor.back"), NamedTextColor.AQUA),
            List.of(),
            false
        ));
        inventory.setItem(9, item(
            strobe.enabled() ? GuiIcon.POWER_OFF : GuiIcon.POWER_ON,
            title(tr(player, strobe.enabled() ? "gui.editor.turn-off" : "gui.editor.turn-on"),
                strobe.enabled() ? NamedTextColor.RED : NamedTextColor.GREEN),
            lore(tr(player, "gui.editor.current-state", "state",
                tr(player, strobe.enabled() ? "state.enabled" : "state.disabled"))),
            strobe.enabled()
        ));
        inventory.setItem(10, item(
            GuiIcon.PULSE,
            title(tr(player, "gui.editor.pulse.title"), NamedTextColor.LIGHT_PURPLE),
            lore(
                tr(player, "gui.editor.pulse.lore-1"),
                tr(player, "gui.editor.pulse.lore-2")
            ),
            true
        ));
        inventory.setItem(11, item(
            GuiIcon.COLOR,
            title(tr(player, "gui.editor.color.title"), TextColor.color(strobe.rgb())),
            lore(
                tr(player, "gui.common.current", "value", StrobeColors.hex(strobe.rgb())),
                tr(player, "gui.editor.color.lore")
            ),
            true
        ));
        inventory.setItem(12, item(
            strobe.mode() == StrobeMode.STATIC ? GuiIcon.STATIC : GuiIcon.TIMING,
            title(tr(player, "gui.editor.refresh.title"), NamedTextColor.GOLD),
            lore(
                strobe.mode() == StrobeMode.STATIC
                    ? tr(player, "gui.editor.refresh.static-value")
                    : tr(player, "gui.editor.refresh.value", "ticks", strobe.refreshTicks(),
                        "rate", formatHz(strobe.refreshTicks())),
                tr(player, "gui.editor.refresh.faster"),
                tr(player, "gui.editor.refresh.slower"),
                tr(player, "gui.editor.refresh.shift"),
                tr(player, "gui.editor.refresh.static-hint", "maximum",
                    plugin.manager().maximumRefreshTicks())
            ),
            strobe.mode() == StrobeMode.STATIC
        ));
        inventory.setItem(13, item(
            GuiIcon.BRIGHTNESS,
            title(tr(player, "gui.editor.intensity.title"), NamedTextColor.YELLOW),
            lore(
                tr(player, "gui.editor.intensity.value", "level", strobe.lightLevel()),
                tr(player, "gui.common.left-plus-one"),
                tr(player, "gui.common.right-minus-one"),
                tr(player, "gui.editor.intensity.shift")
            ),
            strobe.lightLevel() == 15
        ));
        inventory.setItem(14, item(
            GuiIcon.CAMERA_FLASH,
            title(tr(player, "gui.editor.screen-flash.title"), NamedTextColor.DARK_PURPLE),
            lore(
                tr(player, "gui.editor.screen-flash.value", "level",
                    plugin.messages().blindness(player, strobe.blindness())),
                tr(player, "gui.common.left-next"),
                tr(player, "gui.common.right-previous"),
                tr(player, "gui.editor.screen-flash.extreme"),
                tr(player, "gui.editor.screen-flash.player-only")
            ),
            strobe.blindness().enabled()
        ));
        inventory.setItem(15, item(
            GuiIcon.FLASH_POWER,
            title(tr(player, "gui.editor.flash-power.title"), NamedTextColor.LIGHT_PURPLE),
            lore(
                tr(player, "gui.editor.flash-power.value", "power", strobe.flashPower()),
                tr(player, "gui.editor.flash-power.color"),
                tr(player, "gui.editor.flash-power.left"),
                tr(player, "gui.editor.flash-power.right"),
                tr(player, "gui.editor.flash-power.shift")
            ),
            strobe.flashPower() >= 100
        ));
        inventory.setItem(16, item(
            GuiIcon.RENAME,
            title(tr(player, "gui.editor.rename.title"), NamedTextColor.YELLOW),
            lore(tr(player, "gui.editor.rename.lore")),
            false
        ));
        inventory.setItem(20, item(
            GuiIcon.MOVE,
            title(tr(player, strobe.placed() ? "gui.editor.placement.move" : "gui.editor.placement.place"),
                strobe.placed() ? NamedTextColor.AQUA : NamedTextColor.YELLOW),
            lore(
                tr(player, "gui.editor.placement.surface"),
                tr(player, "gui.editor.placement.air"),
                strobe.placed()
                    ? tr(player, "gui.editor.placement.current", "position", position(player, strobe))
                    : tr(player, "gui.editor.placement.unplaced"),
                plugin.manager().easyArmorStandsAvailable()
                    ? tr(player, "gui.editor.placement.easy-armor-stands-ready")
                    : tr(player, "gui.editor.placement.easy-armor-stands-missing")
            ),
            !strobe.placed()
        ));
        inventory.setItem(22, item(
            GuiIcon.TELEPORT,
            title(tr(player, "gui.editor.teleport.title"), NamedTextColor.AQUA),
            lore(
                strobe.placed()
                    ? tr(player, "gui.editor.teleport.lore")
                    : tr(player, "gui.editor.teleport.unplaced")
            ),
            false
        ));
        inventory.setItem(24, item(
            GuiIcon.DELETE,
            title(tr(player, "gui.editor.delete.title"), NamedTextColor.RED),
            lore(tr(player, "gui.editor.delete.lore")),
            false
        ));
        inventory.setItem(EDITOR_CLOSE_SLOT, item(
            GuiIcon.CLOSE,
            title(tr(player, "gui.common.close"), NamedTextColor.RED),
            List.of(),
            false
        ));
        player.openInventory(inventory);
    }

    private void openPalette(Player player, Strobe strobe, int returnPage) {
        GuiHolder holder = new GuiHolder(Screen.PALETTE, strobe.name(), returnPage, List.of());
        Inventory inventory = Bukkit.createInventory(
            holder,
            PALETTE_SIZE,
            Component.text(
                tr(player, "gui.palette.title", "name", strobe.name()),
                NamedTextColor.DARK_AQUA
            )
        );
        holder.inventory = inventory;
        inventory.setItem(4, colorSwatch(
            strobe.rgb(),
            title(tr(player, "gui.palette.current", "color", StrobeColors.hex(strobe.rgb())),
                TextColor.color(strobe.rgb())),
            lore(tr(player, "gui.palette.exact-command", "name", strobe.name())),
            true
        ));
        for (int index = 0; index < PALETTE.size(); index++) {
            PaletteColor color = PALETTE.get(index);
            inventory.setItem(PALETTE_SLOTS[index], colorSwatch(
                color.rgb,
                title(tr(player, "color." + color.key), TextColor.color(color.rgb)),
                lore(StrobeColors.hex(color.rgb), tr(player, "gui.palette.select")),
                strobe.rgb() == color.rgb
            ));
        }
        inventory.setItem(PALETTE_BACK_SLOT, item(
            GuiIcon.BACK,
            title(tr(player, "gui.common.back"), NamedTextColor.AQUA),
            List.of(),
            false
        ));
        int red = strobe.rgb() >> 16 & 0xFF;
        int green = strobe.rgb() >> 8 & 0xFF;
        int blue = strobe.rgb() & 0xFF;
        inventory.setItem(28, rgbChannelItem(player, "red", red, 0xFF0000, NamedTextColor.RED));
        inventory.setItem(30, rgbChannelItem(player, "green", green, 0x00FF00, NamedTextColor.GREEN));
        inventory.setItem(32, rgbChannelItem(player, "blue", blue, 0x0066FF, NamedTextColor.BLUE));
        inventory.setItem(34, item(
            GuiIcon.COLOR,
            title(tr(player, "gui.palette.exact-rgb"), TextColor.color(strobe.rgb())),
            lore(
                "R " + red + " · G " + green + " · B " + blue,
                tr(player, "gui.palette.adjust-channels")
            ),
            true
        ));
        inventory.setItem(PALETTE_CLOSE_SLOT, item(
            GuiIcon.CLOSE,
            title(tr(player, "gui.common.close"), NamedTextColor.RED),
            List.of(),
            false
        ));
        player.openInventory(inventory);
    }

    private void openDeleteConfirmation(Player player, Strobe strobe, int returnPage) {
        GuiHolder holder = new GuiHolder(Screen.DELETE_CONFIRM, strobe.name(), returnPage, List.of());
        Inventory inventory = Bukkit.createInventory(
            holder,
            DELETE_SIZE,
            Component.text(
                tr(player, "gui.delete.title", "name", strobe.name()),
                NamedTextColor.DARK_RED
            )
        );
        holder.inventory = inventory;
        inventory.setItem(11, item(
            Material.LIME_CONCRETE,
            title(tr(player, "gui.delete.confirm"), NamedTextColor.GREEN),
            lore(tr(player, "gui.delete.confirm-lore")),
            false
        ));
        inventory.setItem(13, strobeItem(player, strobe));
        inventory.setItem(DELETE_BACK_SLOT, item(
            GuiIcon.BACK,
            title(tr(player, "gui.common.cancel"), NamedTextColor.RED),
            lore(tr(player, "gui.delete.cancel-lore")),
            false
        ));
        inventory.setItem(DELETE_CLOSE_SLOT, item(
            GuiIcon.CLOSE,
            title(tr(player, "gui.common.close"), NamedTextColor.RED),
            List.of(),
            false
        ));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getRawSlot() < 0
            || event.getRawSlot() >= top.getSize()) {
            return;
        }

        switch (holder.screen) {
            case LIST -> handleListClick(player, holder, event.getRawSlot());
            case EDITOR -> handleEditorClick(player, holder, event);
            case PALETTE -> handlePaletteClick(player, holder, event);
            case DELETE_CONFIRM -> handleDeleteClick(player, holder, event.getRawSlot());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        NameRequest request = pendingNames.remove(player.getUniqueId());
        if (request == null) {
            return;
        }
        event.setCancelled(true);
        String name = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleNameInput(player, request, name));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlacementClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        PlacementRequest request = pendingPlacements.get(player.getUniqueId());
        if (request == null) {
            return;
        }
        event.setCancelled(true);
        org.bukkit.Location location;
        org.bukkit.block.BlockFace face;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
            && event.getClickedBlock() != null
            && event.getBlockFace() != null) {
            face = event.getBlockFace();
            location = event.getClickedBlock().getRelative(face).getLocation();
        } else {
            face = org.bukkit.block.BlockFace.SELF;
            location = airPlacement(player);
        }
        finishPlacement(player, request, location, face);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAirPlacementSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        PlacementRequest request = pendingPlacements.get(player.getUniqueId());
        if (request == null) {
            return;
        }
        event.setCancelled(true);
        finishPlacement(
            player,
            request,
            airPlacement(player),
            org.bukkit.block.BlockFace.SELF
        );
    }

    private org.bukkit.Location airPlacement(Player player) {
        return player.getLocation().clone();
    }

    private void finishPlacement(
        Player player,
        PlacementRequest request,
        org.bukkit.Location location,
        org.bukkit.block.BlockFace face
    ) {
        Optional<Strobe> optional = plugin.manager().find(request.strobeName);
        if (optional.isEmpty()) {
            pendingPlacements.remove(player.getUniqueId());
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.strobe-gone"));
            return;
        }
        Strobe strobe = optional.get();
        if (location.getBlockY() < player.getWorld().getMinHeight()
            || location.getBlockY() >= player.getWorld().getMaxHeight()) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.point-outside-world"));
            return;
        }
        if (!plugin.manager().locationAvailable(location, strobe)) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.point-not-free"));
            return;
        }
        plugin.manager().move(strobe, location, face);
        pendingPlacements.remove(player.getUniqueId());
        player.sendMessage(PREFIX + ChatColor.GREEN
            + tr(player, "message.point-placed", "position", position(player, strobe)));
        Bukkit.getScheduler().runTask(plugin, () -> openEditor(player, strobe, request.returnPage));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingPlacements.remove(event.getPlayer().getUniqueId());
        pendingNames.remove(event.getPlayer().getUniqueId());
        plugin.manager().clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private void handleListClick(Player player, GuiHolder holder, int slot) {
        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = holder.page * PAGE_SIZE + slot;
            if (index < holder.names.size()) {
                plugin.manager().find(holder.names.get(index))
                    .ifPresent(strobe -> openEditor(player, strobe, holder.page));
            }
            return;
        }
        switch (slot) {
            case LIST_START_ALL_SLOT -> {
                plugin.manager().setAllEnabled(true);
                player.sendMessage(PREFIX + ChatColor.GREEN + tr(player, "message.all-started"));
                openList(player, holder.page);
            }
            case LIST_STOP_ALL_SLOT -> {
                plugin.manager().setAllEnabled(false);
                player.sendMessage(PREFIX + ChatColor.GREEN + tr(player, "message.all-stopped"));
                openList(player, holder.page);
            }
            case LIST_DISCOVERY_SLOT -> {
                boolean enabled = plugin.manager().toggleDiscovery(player);
                player.sendMessage(PREFIX + ChatColor.AQUA + tr(player, enabled
                    ? "message.discovery-enabled" : "message.discovery-disabled",
                    "range", String.format(Locale.ROOT, "%.0f",
                        plugin.manager().discoveryRange())));
                openList(player, holder.page);
            }
            case LIST_PREVIOUS_SLOT -> openList(player, holder.page - 1);
            case LIST_CREATE_SLOT -> openCreateName(player, holder.page);
            case LIST_NEXT_SLOT -> openList(player, holder.page + 1);
            case LIST_PACK_SLOT -> {
                if (plugin.resourcePack() != null) {
                    plugin.resourcePack().resend(player);
                    player.closeInventory();
                    player.sendMessage(PREFIX + ChatColor.AQUA + tr(player, "message.pack-resending"));
                }
            }
            case LIST_CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handleNameInput(Player player, NameRequest request, String name) {
        if (!player.isOnline()) {
            return;
        }
        if (isCancelWord(name)) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + tr(player, "message.name-cancelled"));
            if (request.mode == NameMode.RENAME) {
                plugin.manager().find(request.strobeName)
                    .ifPresentOrElse(
                        strobe -> openEditor(player, strobe, request.returnPage),
                        () -> openList(player, request.returnPage)
                    );
            } else {
                openList(player, request.returnPage);
            }
            return;
        }
        if (!plugin.manager().validName(name)) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.invalid-name",
                "maximum", plugin.manager().maximumNameLength()));
            pendingNames.put(player.getUniqueId(), request);
            return;
        }

        if (request.mode == NameMode.CREATE) {
            if (plugin.manager().find(name).isPresent()) {
                player.sendMessage(PREFIX + ChatColor.RED
                    + tr(player, "message.name-exists-retry", "name", name));
                pendingNames.put(player.getUniqueId(), request);
                return;
            }
            if (plugin.manager().size() >= plugin.manager().maximumStrobes()) {
                player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.maximum-strobes"));
                openList(player, request.returnPage);
                return;
            }
            Strobe strobe = plugin.manager().createDraft(name, player.getWorld());
            player.sendMessage(PREFIX + ChatColor.GREEN
                + tr(player, "message.strobe-created", "name", name));
            openEditor(player, strobe, request.returnPage);
            return;
        }

        Optional<Strobe> optional = plugin.manager().find(request.strobeName);
        if (optional.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.strobe-gone"));
            openList(player, request.returnPage);
            return;
        }
        Optional<Strobe> collision = plugin.manager().find(name);
        if (collision.isPresent() && collision.get() != optional.get()) {
            player.sendMessage(PREFIX + ChatColor.RED + tr(player, "message.name-exists"));
            pendingNames.put(player.getUniqueId(), request);
            return;
        }
        Strobe strobe = optional.get();
        if (!strobe.name().equals(name)) {
            plugin.manager().rename(strobe, name);
        }
        player.sendMessage(PREFIX + ChatColor.GREEN
            + tr(player, "message.name-saved", "name", name));
        openEditor(player, strobe, request.returnPage);
    }

    private void handleEditorClick(Player player, GuiHolder holder, InventoryClickEvent event) {
        Optional<Strobe> optional = plugin.manager().find(holder.strobeName);
        if (optional.isEmpty()) {
            openList(player, holder.page);
            return;
        }
        Strobe strobe = optional.get();
        switch (event.getRawSlot()) {
            case EDITOR_BACK_SLOT -> openList(player, holder.page);
            case 9 -> {
                if (!strobe.placed()) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW
                        + tr(player, "message.place-first"));
                    return;
                }
                plugin.manager().setEnabled(strobe, !strobe.enabled());
                openEditor(player, strobe, holder.page);
            }
            case 10 -> {
                if (!strobe.placed()) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW
                        + tr(player, "message.place-first"));
                    return;
                }
                plugin.manager().pulse(strobe);
                boolean previewed = plugin.manager().previewCameraFlash(player, strobe);
                player.sendMessage(PREFIX + ChatColor.LIGHT_PURPLE
                    + tr(player, "message.pulse-sent", "name", strobe.name()));
                if (!previewed) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW
                        + tr(player, "message.flash-preview-unavailable"));
                }
            }
            case 11 -> openPalette(player, strobe, holder.page);
            case 12 -> {
                int maximum = plugin.manager().maximumRefreshTicks();
                if (strobe.mode() == StrobeMode.STATIC) {
                    if (!event.isRightClick()) {
                        plugin.manager().setRefreshTicks(strobe, maximum);
                    }
                } else {
                    int amount = event.isShiftClick() ? 100
                        : strobe.refreshTicks() >= 200 ? 20 : 1;
                    if (event.isRightClick() && strobe.refreshTicks() >= maximum) {
                        plugin.manager().setMode(strobe, StrobeMode.STATIC);
                    } else {
                        int value = event.isRightClick()
                            ? strobe.refreshTicks() + amount
                            : strobe.refreshTicks() - amount;
                        plugin.manager().setRefreshTicks(
                            strobe,
                            Math.max(1, Math.min(maximum, value))
                        );
                    }
                }
                openEditor(player, strobe, holder.page);
            }
            case 13 -> {
                int amount = event.isShiftClick() ? 5 : 1;
                int value = event.isRightClick()
                    ? strobe.lightLevel() - amount
                    : strobe.lightLevel() + amount;
                plugin.manager().setLightLevel(strobe, Math.max(0, Math.min(15, value)));
                openEditor(player, strobe, holder.page);
            }
            case 14 -> {
                BlindnessLevel[] levels = BlindnessLevel.values();
                int direction = event.isRightClick() ? -1 : 1;
                int index = Math.floorMod(strobe.blindness().ordinal() + direction, levels.length);
                plugin.manager().setBlindness(strobe, levels[index]);
                openEditor(player, strobe, holder.page);
            }
            case 15 -> {
                int amount = event.isShiftClick() ? 25 : 5;
                int value = event.isRightClick()
                    ? strobe.flashPower() - amount
                    : strobe.flashPower() + amount;
                plugin.manager().setFlashPower(strobe, Math.max(0, Math.min(200, value)));
                openEditor(player, strobe, holder.page);
            }
            case 16 -> openRename(player, strobe, holder.page);
            case 20 -> {
                pendingPlacements.put(
                    player.getUniqueId(),
                    new PlacementRequest(strobe.name(), holder.page)
                );
                player.closeInventory();
                player.sendMessage(PREFIX + ChatColor.AQUA
                    + tr(player, "message.placement-prompt"));
                player.sendMessage(PREFIX + ChatColor.GRAY
                    + tr(player, "message.placement-cancel"));
            }
            case 22 -> {
                if (!plugin.manager().teleport(player, strobe)) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW
                        + tr(player, "message.place-first"));
                    return;
                }
                player.sendMessage(PREFIX + ChatColor.AQUA
                    + tr(player, "message.teleported", "name", strobe.name()));
                player.closeInventory();
            }
            case 24 -> openDeleteConfirmation(player, strobe, holder.page);
            case EDITOR_CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handlePaletteClick(Player player, GuiHolder holder, InventoryClickEvent event) {
        Optional<Strobe> optional = plugin.manager().find(holder.strobeName);
        if (optional.isEmpty()) {
            openList(player, holder.page);
            return;
        }
        Strobe strobe = optional.get();
        int slot = event.getRawSlot();
        if (slot == PALETTE_BACK_SLOT) {
            openEditor(player, strobe, holder.page);
            return;
        }
        if (slot == PALETTE_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        for (int index = 0; index < PALETTE_SLOTS.length; index++) {
            if (PALETTE_SLOTS[index] == slot) {
                PaletteColor color = PALETTE.get(index);
                plugin.manager().setColor(strobe, color.rgb);
                openEditor(player, strobe, holder.page);
                return;
            }
        }
        int channel = switch (slot) {
            case 28 -> 16;
            case 30 -> 8;
            case 32 -> 0;
            default -> -1;
        };
        if (channel >= 0) {
            int amount = event.isShiftClick() ? 16 : 1;
            int current = strobe.rgb() >> channel & 0xFF;
            int adjusted = event.isRightClick() ? current - amount : current + amount;
            adjusted = Math.max(0, Math.min(255, adjusted));
            int mask = ~(0xFF << channel);
            int rgb = (strobe.rgb() & mask) | (adjusted << channel);
            plugin.manager().setColor(strobe, rgb);
            openPalette(player, strobe, holder.page);
        }
    }

    private void handleDeleteClick(Player player, GuiHolder holder, int slot) {
        Optional<Strobe> optional = plugin.manager().find(holder.strobeName);
        if (optional.isEmpty()) {
            openList(player, holder.page);
            return;
        }
        Strobe strobe = optional.get();
        if (slot == 11) {
            plugin.manager().delete(strobe);
            player.sendMessage(PREFIX + ChatColor.GREEN
                + tr(player, "message.strobe-deleted", "name", strobe.name()));
            openList(player, holder.page);
        } else if (slot == DELETE_BACK_SLOT) {
            openEditor(player, strobe, holder.page);
        } else if (slot == DELETE_CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    private ItemStack strobeItem(Player player, Strobe strobe) {
        return item(
            GuiIcon.STROBE,
            title(strobe.name(), TextColor.color(strobe.rgb())),
            lore(
                title(tr(player, "gui.strobe.subtitle"), NamedTextColor.AQUA),
                title(tr(player, "gui.strobe.divider"), NamedTextColor.DARK_GRAY),
                title(tr(player, strobe.enabled() ? "gui.strobe.enabled" : "gui.strobe.disabled"),
                    strobe.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED),
                title(tr(player, "gui.strobe.mode", "mode", tr(player,
                    "mode." + strobe.mode().name().toLowerCase(Locale.ROOT))),
                    NamedTextColor.GOLD),
                title(tr(player, "gui.strobe.color", "color", StrobeColors.hex(strobe.rgb())),
                    TextColor.color(strobe.rgb())),
                title(strobe.mode() == StrobeMode.STATIC
                    ? tr(player, "gui.strobe.static")
                    : tr(player, "gui.strobe.refresh", "ticks", strobe.refreshTicks(),
                        "rate", formatHz(strobe.refreshTicks())), NamedTextColor.YELLOW),
                title(tr(player, "gui.strobe.intensity", "level", strobe.lightLevel()),
                    NamedTextColor.AQUA),
                title(tr(player, "gui.strobe.screen-flash", "level",
                    plugin.messages().blindness(player, strobe.blindness())),
                    NamedTextColor.LIGHT_PURPLE),
                title(tr(player, "gui.strobe.flash-power", "power", strobe.flashPower()),
                    NamedTextColor.GOLD),
                title(strobe.placed()
                    ? tr(player, "gui.strobe.position", "position", position(player, strobe))
                    : tr(player, "gui.strobe.unplaced"),
                    strobe.placed() ? NamedTextColor.GREEN : NamedTextColor.RED),
                title(tr(player, "gui.strobe.divider"), NamedTextColor.DARK_GRAY),
                title(tr(player, "gui.strobe.configure"), NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD)
            ),
            strobe.enabled()
        );
    }

    private static ItemStack item(
        GuiIcon icon,
        Component displayName,
        List<Component> lore,
        boolean glowing
    ) {
        ItemStack stack = item(Material.PAPER, displayName, lore, glowing);
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of(icon.customModelData));
        meta.setCustomModelDataComponent(component);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack colorSwatch(
        int rgb,
        Component displayName,
        List<Component> lore,
        boolean glowing
    ) {
        ItemStack stack = item(GuiIcon.COLOR_SWATCH, displayName, lore, glowing);
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setColors(List.of(Color.fromRGB(rgb & 0xFFFFFF)));
        meta.setCustomModelDataComponent(component);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack item(
        Material material,
        Component displayName,
        List<Component> lore,
        boolean glowing
    ) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(displayName.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
            .map(line -> line.decoration(TextDecoration.ITALIC, false))
            .toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack rgbChannelItem(
        Player player,
        String colorKey,
        int value,
        int rgb,
        TextColor color
    ) {
        return colorSwatch(
            rgb,
            title(tr(player, "color." + colorKey) + ": " + value, color),
            lore(
                tr(player, "gui.common.left-plus-one"),
                tr(player, "gui.common.right-minus-one"),
                tr(player, "gui.palette.shift-channel")
            ),
            value == 255
        );
    }

    private static Component title(String text, TextColor color) {
        return Component.text(text, color);
    }

    private static List<Component> lore(String... lines) {
        TextColor[] colors = {
            NamedTextColor.GRAY,
            NamedTextColor.AQUA,
            NamedTextColor.YELLOW,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.GREEN,
            NamedTextColor.WHITE
        };
        List<Component> result = new ArrayList<>(lines.length);
        for (int index = 0; index < lines.length; index++) {
            result.add(Component.text(lines[index], colors[index % colors.length]));
        }
        return result;
    }

    private static List<Component> lore(Component... lines) {
        return List.of(lines);
    }

    private static String formatHz(int ticks) {
        return String.format(Locale.ROOT, "%.2f", StrobeTiming.flashesPerSecond(ticks));
    }

    private String position(Player player, Strobe strobe) {
        String coordinates = String.format(
            Locale.ROOT,
            "%.2f, %.2f, %.2f",
            strobe.x(), strobe.y(), strobe.z()
        );
        return strobe.face() == org.bukkit.block.BlockFace.SELF
            ? coordinates + " " + tr(player, "position.air")
            : coordinates + " " + tr(player, "position.face", "face",
                strobe.face().name().toLowerCase(Locale.ROOT));
    }

    private String tr(Player player, String key, Object... replacements) {
        return plugin.messages().text(player, key, replacements);
    }

    private static boolean isCancelWord(String value) {
        return Set.of("cancel", "cancelar", "annuler", "abbrechen", "annulla")
            .contains(value.toLowerCase(Locale.ROOT));
    }

    private enum Screen {
        LIST,
        EDITOR,
        PALETTE,
        DELETE_CONFIRM
    }

    private enum NameMode {
        CREATE,
        RENAME
    }

    private enum GuiIcon {
        STROBE(6_800.0f),
        CREATE(6_801.0f),
        POWER_ON(6_802.0f),
        POWER_OFF(6_803.0f),
        DISCOVERY(6_804.0f),
        TELEPORT(6_805.0f),
        PULSE(6_806.0f),
        COLOR(6_807.0f),
        TIMING(6_808.0f),
        STATIC(6_809.0f),
        BRIGHTNESS(6_810.0f),
        CAMERA_FLASH(6_811.0f),
        RENAME(6_812.0f),
        MOVE(6_813.0f),
        DELETE(6_814.0f),
        RESOURCE_PACK(6_815.0f),
        FLASH_POWER(6_816.0f),
        BACK(6_817.0f),
        CLOSE(6_818.0f),
        PREVIOUS(6_819.0f),
        NEXT(6_820.0f),
        COLOR_SWATCH(6_821.0f),
        NO_STROBES(6_822.0f);

        private final float customModelData;

        GuiIcon(float customModelData) {
            this.customModelData = customModelData;
        }
    }

    private static final class GuiHolder implements InventoryHolder {
        private final Screen screen;
        private final String strobeName;
        private final int page;
        private final List<String> names;
        private Inventory inventory;

        private GuiHolder(Screen screen, String strobeName, int page, List<String> names) {
            this.screen = screen;
            this.strobeName = strobeName;
            this.page = page;
            this.names = List.copyOf(names);
        }

        @Override
        public Inventory getInventory() {
            return Objects.requireNonNull(inventory, "inventory");
        }
    }

    private record PaletteColor(String key, int rgb, Material material) {
    }

    private record PlacementRequest(String strobeName, int returnPage) {
    }

    private record NameRequest(NameMode mode, String strobeName, int returnPage) {
    }
}
