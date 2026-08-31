package es.mrdino.strobelights.command;

import es.mrdino.strobelights.StrobeLightsPlugin;
import es.mrdino.strobelights.model.BlindnessLevel;
import es.mrdino.strobelights.model.Strobe;
import es.mrdino.strobelights.model.StrobeMode;
import es.mrdino.strobelights.service.StrobeManager;
import es.mrdino.strobelights.util.StrobeColors;
import es.mrdino.strobelights.util.StrobeTiming;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

public final class StrobeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_AQUA + "[StrobeLights] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of(
        "help", "create", "delete", "move", "rename", "set", "start", "stop",
        "toggle", "pulse", "tp", "discover", "flash", "info", "list", "gui", "reload"
    );

    private final StrobeLightsPlugin plugin;

    public StrobeCommand(StrobeLightsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.gui().open(player);
            } else {
                help(sender, label);
            }
            return true;
        }

        String action = canonicalAction(args[0]);
        try {
            return switch (action) {
                case "help" -> help(sender, label);
                case "create" -> create(sender, args);
                case "delete" -> delete(sender, args);
                case "move" -> move(sender, args);
                case "rename" -> rename(sender, args);
                case "set" -> set(sender, args);
                case "start" -> startStop(sender, args, true);
                case "stop" -> startStop(sender, args, false);
                case "toggle" -> toggle(sender, args);
                case "pulse" -> pulse(sender, args);
                case "tp" -> teleport(sender, args);
                case "discover" -> discover(sender, args);
                case "flash" -> flash(sender, args);
                case "info" -> info(sender, args);
                case "list" -> list(sender);
                case "gui" -> gui(sender, args);
                case "reload" -> reload(sender);
                default -> {
                    error(sender, tr(sender, "command.unknown", "label", label));
                    yield true;
                }
            };
        } catch (NumberFormatException exception) {
            error(sender, tr(sender, "command.invalid-number"));
            return true;
        }
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, tr(sender, "command.player-only-position"));
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe create <name> [color|#RRGGBB]"));
            return true;
        }
        StrobeManager manager = plugin.manager();
        String name = args[1];
        if (!manager.validName(name)) {
            error(sender, tr(sender, "command.invalid-name", "maximum",
                manager.maximumNameLength()));
            return true;
        }
        if (manager.find(name).isPresent()) {
            error(sender, tr(sender, "command.name-exists", "name", name));
            return true;
        }
        if (manager.size() >= manager.maximumStrobes()) {
            error(sender, tr(sender, "command.maximum-strobes", "maximum",
                manager.maximumStrobes()));
            return true;
        }

        int rgb = Strobe.DEFAULT_COLOR;
        if (args.length == 3) {
            OptionalInt parsed = StrobeColors.parse(args[2]);
            if (parsed.isEmpty()) {
                error(sender, tr(sender, "command.invalid-color"));
                return true;
            }
            rgb = parsed.getAsInt();
        }

        Placement placement = placement(player);
        if (placement == null) {
            error(sender, tr(sender, "command.point-outside-world"));
            return true;
        }
        if (!manager.locationAvailable(placement.location, null)) {
            error(sender, tr(sender, "command.space-not-free"));
            return true;
        }

        Strobe strobe = manager.create(name, placement.location, placement.face, rgb);
        success(sender, tr(sender, "command.created", "name", strobe.name(),
            "position", coordinates(sender, strobe), "color", StrobeColors.hex(strobe.rgb())));
        infoLine(sender, tr(sender, "command.created-off", "name", strobe.name()));
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        Optional<Strobe> strobe = requireStrobe(sender, args, "/strobe delete <name>");
        if (strobe.isEmpty()) {
            return true;
        }
        plugin.manager().delete(strobe.get());
        success(sender, tr(sender, "command.deleted", "name", strobe.get().name()));
        return true;
    }

    private boolean move(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, tr(sender, "command.player-only-position"));
            return true;
        }
        Optional<Strobe> strobe = requireStrobe(sender, args, "/strobe move <name>");
        if (strobe.isEmpty()) {
            return true;
        }
        Placement placement = placement(player);
        if (placement == null) {
            error(sender, tr(sender, "command.point-outside-world"));
            return true;
        }
        if (!plugin.manager().locationAvailable(placement.location, strobe.get())) {
            error(sender, tr(sender, "command.space-not-free"));
            return true;
        }
        plugin.manager().move(strobe.get(), placement.location, placement.face);
        success(sender, tr(sender, "command.moved", "name", strobe.get().name(),
            "position", coordinates(sender, strobe.get())));
        return true;
    }

    private boolean rename(CommandSender sender, String[] args) {
        if (args.length != 3) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe rename <name> <new-name>"));
            return true;
        }
        Optional<Strobe> strobe = findOrError(sender, args[1]);
        if (strobe.isEmpty()) {
            return true;
        }
        String newName = args[2];
        if (!plugin.manager().validName(newName)) {
            error(sender, tr(sender, "command.invalid-name", "maximum",
                plugin.manager().maximumNameLength()));
            return true;
        }
        if (plugin.manager().find(newName).isPresent()) {
            error(sender, tr(sender, "command.name-exists", "name", newName));
            return true;
        }
        String previousName = strobe.get().name();
        plugin.manager().rename(strobe.get(), newName);
        success(sender, tr(sender, "command.renamed", "old", previousName, "name", newName));
        return true;
    }

    private boolean set(CommandSender sender, String[] args) {
        if (args.length != 4) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe set <name> <color|refresh|mode|brightness|blindness|flashpower> <value>"));
            return true;
        }
        Optional<Strobe> optional = findOrError(sender, args[1]);
        if (optional.isEmpty()) {
            return true;
        }
        Strobe strobe = optional.get();
        String property = canonicalProperty(args[2]);
        switch (property) {
            case "color" -> {
                OptionalInt color = StrobeColors.parse(args[3]);
                if (color.isEmpty()) {
                    error(sender, tr(sender, "command.invalid-color"));
                    return true;
                }
                plugin.manager().setColor(strobe, color.getAsInt());
                success(sender, tr(sender, "command.color-set", "name", strobe.name(),
                    "color", StrobeColors.hex(strobe.rgb())));
            }
            case "refresh" -> {
                if (StrobeMode.parse(args[3]).orElse(null) == StrobeMode.STATIC) {
                    plugin.manager().setMode(strobe, StrobeMode.STATIC);
                    success(sender, tr(sender, "command.static-set", "name", strobe.name()));
                    return true;
                }
                int ticks = Integer.parseInt(args[3]);
                if (ticks < 1 || ticks > plugin.manager().maximumRefreshTicks()) {
                    error(sender, tr(sender, "command.refresh-range", "maximum",
                        plugin.manager().maximumRefreshTicks()));
                    return true;
                }
                plugin.manager().setRefreshTicks(strobe, ticks);
                success(sender, tr(sender, "command.refresh-set", "name", strobe.name(),
                    "ticks", ticks, "rate", formatHz(ticks)));
            }
            case "mode" -> {
                Optional<StrobeMode> mode = StrobeMode.parse(args[3]);
                if (mode.isEmpty()) {
                    error(sender, tr(sender, "command.mode-values"));
                    return true;
                }
                plugin.manager().setMode(strobe, mode.get());
                success(sender, tr(sender, "command.mode-set", "name", strobe.name(),
                    "mode", tr(sender, "mode." + mode.get().name().toLowerCase(Locale.ROOT))));
            }
            case "brightness" -> {
                int level = Integer.parseInt(args[3]);
                if (level < 0 || level > 15) {
                    error(sender, tr(sender, "command.brightness-range"));
                    return true;
                }
                plugin.manager().setLightLevel(strobe, level);
                success(sender, tr(sender, "command.brightness-set", "name", strobe.name(),
                    "level", level));
            }
            case "blindness" -> {
                Optional<BlindnessLevel> level = BlindnessLevel.parse(args[3]);
                if (level.isEmpty()) {
                    error(sender, tr(sender, "command.blindness-values"));
                    return true;
                }
                plugin.manager().setBlindness(strobe, level.get());
                success(sender, tr(sender, "command.blindness-set", "name", strobe.name(),
                    "level", plugin.messages().blindness(sender, level.get())));
                if (level.get() == BlindnessLevel.EXTREME) {
                    infoLine(sender, tr(sender, "command.extreme-info"));
                }
            }
            case "flashpower" -> {
                int power = Integer.parseInt(args[3]);
                if (power < 0 || power > 200) {
                    error(sender, tr(sender, "command.flash-power-range"));
                    return true;
                }
                plugin.manager().setFlashPower(strobe, power);
                success(sender, tr(sender, "command.flash-power-set", "name", strobe.name(),
                    "power", power));
            }
            default -> error(sender, tr(sender, "command.unknown-property"));
        }
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, tr(sender, "command.gui-player-only"));
            return true;
        }
        Optional<Strobe> strobe = requireStrobe(sender, args, "/strobe tp <name>");
        if (strobe.isEmpty()) {
            return true;
        }
        if (!plugin.manager().teleport(player, strobe.get())) {
            error(sender, tr(sender, "command.not-placed"));
            return true;
        }
        success(sender, tr(sender, "command.teleported", "name", strobe.get().name()));
        return true;
    }

    private boolean discover(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, tr(sender, "command.gui-player-only"));
            return true;
        }
        if (args.length > 2) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe discover [on|off|toggle]"));
            return true;
        }
        String value = args.length == 1 ? "toggle" : args[1].toLowerCase(Locale.ROOT);
        boolean enabled;
        switch (value) {
            case "on", "enable", "enabled", "si", "sí", "activar" ->
                enabled = plugin.manager().setDiscovery(player, true);
            case "off", "disable", "disabled", "no", "desactivar" ->
                enabled = plugin.manager().setDiscovery(player, false);
            case "toggle", "alternar" -> enabled = plugin.manager().toggleDiscovery(player);
            default -> {
                error(sender, tr(sender, "command.discovery-values"));
                return true;
            }
        }
        success(sender, tr(sender, enabled
            ? "command.discovery-enabled" : "command.discovery-disabled",
            "range", String.format(Locale.ROOT, "%.0f", plugin.manager().discoveryRange())));
        return true;
    }

    private boolean flash(CommandSender sender, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("give")) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe flash give <player>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null || !target.isOnline()) {
            error(sender, tr(sender, "command.flash.player-not-found", "player", args[2]));
            return true;
        }
        boolean stored = plugin.flashbangs().give(target);
        success(sender, tr(sender, "command.flash.given", "player", target.getName()));
        target.sendMessage(PREFIX + ChatColor.GOLD
            + tr(target, stored ? "command.flash.received" : "command.flash.received-dropped"));
        return true;
    }

    private boolean startStop(CommandSender sender, String[] args, boolean enabled) {
        if (args.length != 2) {
            error(sender, tr(sender, "command.usage", "usage",
                "/strobe " + (enabled ? "start" : "stop") + " <name|all>"));
            return true;
        }
        if (isAll(args[1])) {
            int changed = plugin.manager().setAllEnabled(enabled);
            success(sender, tr(sender, enabled ? "command.all-started" : "command.all-stopped",
                "changed", changed));
            return true;
        }
        Optional<Strobe> strobe = findOrError(sender, args[1]);
        if (strobe.isEmpty()) {
            return true;
        }
        if (enabled && !strobe.get().placed()) {
            error(sender, tr(sender, "command.not-placed"));
            return true;
        }
        plugin.manager().setEnabled(strobe.get(), enabled);
        success(sender, tr(sender, enabled ? "command.started" : "command.stopped",
            "name", strobe.get().name()));
        return true;
    }

    private boolean toggle(CommandSender sender, String[] args) {
        Optional<Strobe> strobe = requireStrobe(sender, args, "/strobe toggle <name>");
        if (strobe.isEmpty()) {
            return true;
        }
        if (!strobe.get().placed()) {
            error(sender, tr(sender, "command.not-placed"));
            return true;
        }
        boolean enabled = !strobe.get().enabled();
        plugin.manager().setEnabled(strobe.get(), enabled);
        success(sender, tr(sender, enabled ? "command.started" : "command.stopped",
            "name", strobe.get().name()));
        return true;
    }

    private boolean pulse(CommandSender sender, String[] args) {
        if (args.length != 2) {
            error(sender, tr(sender, "command.usage", "usage", "/strobe pulse <name|all>"));
            return true;
        }
        if (isAll(args[1])) {
            Collection<Strobe> all = plugin.manager().all();
            all.forEach(plugin.manager()::pulse);
            success(sender, tr(sender, "command.pulse-all", "count", all.size()));
            return true;
        }
        Optional<Strobe> strobe = findOrError(sender, args[1]);
        if (strobe.isEmpty()) {
            return true;
        }
        if (!strobe.get().placed()) {
            error(sender, tr(sender, "command.not-placed"));
            return true;
        }
        plugin.manager().pulse(strobe.get());
        success(sender, tr(sender, "command.pulse-one", "name", strobe.get().name()));
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Optional<Strobe> optional = requireStrobe(sender, args, "/strobe info <name>");
        if (optional.isEmpty()) {
            return true;
        }
        Strobe strobe = optional.get();
        sender.sendMessage(PREFIX + ChatColor.AQUA + strobe.name()
            + ChatColor.GRAY + " — " + (strobe.enabled()
                ? ChatColor.GREEN + tr(sender, "state.enabled")
                : ChatColor.RED + tr(sender, "state.disabled")));
        infoLine(sender, strobe.placed()
            ? tr(sender, "command.info.position", "position", coordinates(sender, strobe),
                "world", strobe.worldName(), "face",
                strobe.face().name().toLowerCase(Locale.ROOT))
            : tr(sender, "command.info.unplaced"));
        infoLine(sender, tr(sender, "command.info.color", "color",
            StrobeColors.hex(strobe.rgb())));
        infoLine(sender, tr(sender, "command.info.refresh", "ticks", strobe.refreshTicks(),
            "milliseconds", StrobeTiming.millisecondsPerPhase(strobe.refreshTicks()),
            "rate", formatHz(strobe.refreshTicks()), "mode",
            tr(sender, "mode." + strobe.mode().name().toLowerCase(Locale.ROOT))));
        infoLine(sender, tr(sender, "command.info.effects", "level", strobe.lightLevel(),
            "flash", plugin.messages().blindness(sender, strobe.blindness()),
            "power", strobe.flashPower()));
        return true;
    }

    private boolean list(CommandSender sender) {
        Collection<Strobe> strobes = plugin.manager().all();
        if (strobes.isEmpty()) {
            infoLine(sender, tr(sender, "command.list-empty"));
            return true;
        }
        sender.sendMessage(PREFIX + ChatColor.AQUA
            + tr(sender, "command.list-title", "count", strobes.size()));
        for (Strobe strobe : strobes) {
            sender.sendMessage(ChatColor.DARK_GRAY + " - "
                + (strobe.enabled() ? ChatColor.GREEN + "● " : ChatColor.RED + "○ ")
                + ChatColor.WHITE + strobe.name() + ChatColor.GRAY + " "
                + StrobeColors.hex(strobe.rgb()) + " @ " + coordinates(sender, strobe));
        }
        return true;
    }

    private boolean gui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, tr(sender, "command.gui-player-only"));
            return true;
        }
        if (args.length > 2) {
            error(sender, tr(sender, "command.usage", "usage", "/strobe gui [name]"));
            return true;
        }
        if (args.length == 2 && !plugin.gui().open(player, args[1])) {
            error(sender, tr(sender, "command.not-found-short", "name", args[1]));
            return true;
        }
        if (args.length == 1) {
            plugin.gui().open(player);
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadPlugin();
        success(sender, tr(sender, "command.reloaded", "count", plugin.manager().size()));
        return true;
    }

    private boolean help(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.AQUA + tr(sender, "command.help.title"));
        sender.sendMessage(ChatColor.GRAY + "/" + label
            + ChatColor.WHITE + " — " + tr(sender, "command.help.gui"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " create <name> [color]"
            + ChatColor.WHITE + " — " + tr(sender, "command.help.create"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> color <#RRGGBB|name>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> refresh <1-"
            + plugin.manager().maximumRefreshTicks() + "|static>"
            + ChatColor.WHITE + " — " + tr(sender, "command.help.refresh"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> mode <strobe|static>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> brightness <0-15>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> blindness <none|low|medium|high|extreme>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " set <name> flashpower <0-200>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " start|stop <name|all>");
        sender.sendMessage(ChatColor.GRAY + "/" + label
            + " toggle|pulse|info|move|tp|delete <name>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " discover [on|off]"
            + ChatColor.WHITE + " — " + tr(sender, "command.help.discovery"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " flash give <player>"
            + ChatColor.WHITE + " — " + tr(sender, "command.help.flash"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " gui [name]"
            + ChatColor.WHITE + " — " + tr(sender, "command.help.visual"));
        sender.sendMessage(ChatColor.GRAY + "/" + label + " rename <name> <new> | list | reload");
        return true;
    }

    private Optional<Strobe> requireStrobe(CommandSender sender, String[] args, String usage) {
        if (args.length != 2) {
            error(sender, tr(sender, "command.usage", "usage", usage));
            return Optional.empty();
        }
        return findOrError(sender, args[1]);
    }

    private Optional<Strobe> findOrError(CommandSender sender, String name) {
        Optional<Strobe> strobe = plugin.manager().find(name);
        if (strobe.isEmpty()) {
            error(sender, tr(sender, "command.not-found", "name", name));
        }
        return strobe;
    }

    private Placement placement(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(8.0, FluidCollisionMode.NEVER);
        Location location;
        BlockFace face;
        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            face = hit.getHitBlockFace();
            location = hit.getHitBlock().getRelative(face).getLocation();
        } else {
            face = BlockFace.SELF;
            location = player.getLocation().clone();
        }
        if (location.getBlockY() < player.getWorld().getMinHeight()
            || location.getBlockY() >= player.getWorld().getMaxHeight()) {
            return null;
        }
        return new Placement(location, face);
    }

    private String coordinates(CommandSender sender, Strobe strobe) {
        if (!strobe.placed()) {
            return tr(sender, "position.unplaced");
        }
        String position = String.format(
            Locale.ROOT,
            "%.2f, %.2f, %.2f",
            strobe.x(), strobe.y(), strobe.z()
        );
        return strobe.face() == BlockFace.SELF
            ? position + " " + tr(sender, "position.air")
            : position;
    }

    private static String formatHz(int refreshTicks) {
        return String.format(Locale.ROOT, "%.2f", StrobeTiming.flashesPerSecond(refreshTicks));
    }

    private static boolean isAll(String value) {
        return List.of("all", "todos", "tous", "alle", "tutti")
            .contains(value.toLowerCase(Locale.ROOT));
    }

    private static String canonicalAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "ayuda", "aide", "hilfe", "aiuto" -> "help";
            case "crear", "créer", "creer", "erstellen", "creare" -> "create";
            case "borrar", "eliminar", "supprimer", "löschen", "loeschen", "cancella" -> "delete";
            case "mover", "déplacer", "deplacer", "verschieben", "sposta" -> "move";
            case "renombrar", "renommer", "umbenennen", "rinomina" -> "rename";
            case "config", "configurar", "configurer", "einstellen", "configura" -> "set";
            case "encender", "démarrer", "demarrer", "starten", "avvia", "accendi" -> "start";
            case "apagar", "arrêter", "arreter", "stoppen", "spegni" -> "stop";
            case "alternar", "basculer", "umschalten", "alterna" -> "toggle";
            case "pulso", "impulsion", "impuls", "impulso" -> "pulse";
            case "teleport", "teletransporte", "teletransportar", "téléporter", "teleporter" -> "tp";
            case "descubrir", "descubrimiento", "découvrir", "decouvrir", "entdecken", "scopri" -> "discover";
            case "flashbang", "granada", "granadaflash", "stungrenade" -> "flash";
            case "informacion", "estado", "infos", "informationen", "informazioni" -> "info";
            case "lista", "liste", "elenco" -> "list";
            case "menu", "menú", "interfaz", "interface", "oberfläche", "oberflaeche",
                "interfaccia" -> "gui";
            case "recargar", "recharger", "neuladen", "ricarica" -> "reload";
            default -> action.toLowerCase(Locale.ROOT);
        };
    }

    private static String canonicalProperty(String property) {
        return switch (property.toLowerCase(Locale.ROOT)) {
            case "colour", "couleur", "farbe", "colore" -> "color";
            case "refresco", "rate", "velocidad", "vitesse", "geschwindigkeit", "velocità",
                "velocita" -> "refresh";
            case "intensity", "intensidad", "light", "luz", "brillo", "intensité",
                "intensite", "helligkeit", "intensità", "intensita" -> "brightness";
            case "blind", "ceguera", "deslumbramiento", "aveuglement", "blendung",
                "abbagliamento" -> "blindness";
            case "flash", "potenciaflash", "potencia-flash", "puissanceflash", "flashstärke",
                "flashstaerke", "potenzaflash" -> "flashpower";
            case "modo", "modus", "modalità", "modalita" -> "mode";
            default -> property.toLowerCase(Locale.ROOT);
        };
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + message);
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.RED + message);
    }

    private static void infoLine(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.GRAY + message);
    }

    private String tr(CommandSender sender, String key, Object... replacements) {
        return plugin.messages().text(sender, key, replacements);
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        String action = canonicalAction(args[0]);
        if (args.length == 2) {
            if (action.equals("create")) {
                return List.of();
            }
            if (List.of("start", "stop", "pulse").contains(action)) {
                List<String> names = new ArrayList<>(plugin.manager().names());
                names.add("all");
                return matching(names, args[1]);
            }
            if (List.of("delete", "move", "rename", "set", "toggle", "info").contains(action)) {
                return matching(plugin.manager().names(), args[1]);
            }
            if (action.equals("tp")) {
                return matching(plugin.manager().names(), args[1]);
            }
            if (action.equals("discover")) {
                return matching(List.of("on", "off", "toggle"), args[1]);
            }
            if (action.equals("flash")) {
                return matching(List.of("give"), args[1]);
            }
            if (action.equals("gui")) {
                return matching(plugin.manager().names(), args[1]);
            }
        }
        if (args.length == 3 && action.equals("create")) {
            return matching(StrobeColors.suggestions(), args[2]);
        }
        if (args.length == 3 && action.equals("set")) {
            return matching(List.of(
                "color", "refresh", "mode", "brightness", "blindness", "flashpower"
            ), args[2]);
        }
        if (args.length == 3 && action.equals("flash")
            && args[1].equalsIgnoreCase("give")) {
            return matching(
                plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList(),
                args[2]
            );
        }
        if (args.length == 4 && action.equals("set")) {
            return switch (canonicalProperty(args[2])) {
                case "color" -> matching(StrobeColors.suggestions(), args[3]);
                case "refresh" -> matching(List.of(
                    "1", "2", "5", "10", "20", "100", "600",
                    Integer.toString(plugin.manager().maximumRefreshTicks()), "static"
                ), args[3]);
                case "mode" -> matching(List.of("strobe", "static"), args[3]);
                case "brightness" -> matching(List.of("0", "5", "10", "15"), args[3]);
                case "blindness" -> matching(
                    List.of("none", "low", "medium", "high", "extreme"), args[3]
                );
                case "flashpower" -> matching(List.of("0", "50", "100", "150", "200"), args[3]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> matching(Collection<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private record Placement(Location location, BlockFace face) {
    }
}
