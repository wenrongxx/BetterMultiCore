package com.bettersinglecore.optimize;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Replaces vanilla /particle leaf commands with an equivalent that fans out
 * per-player sends across worker threads.
 *
 * Vanilla semantics preserved:
 *  - same argument parsing (ParticleArgument / Vec3 / float / int)
 *  - same viewers (realPlayers or EntityArgument players)
 *  - same force flag, same distance rule inside ServerLevel.sendParticles
 *  - same success/error translation keys
 *
 * Only the per-player send loop is parallelized (Netty write is thread-safe).
 * execute itself stays on the main thread (world state).
 */
public final class ParticleCommandOffload {

    private static final SimpleCommandExceptionType ERROR_FAILED = new SimpleCommandExceptionType(
            Component.translatable("commands.particle.failed"));

    private static final Field COMMAND_FIELD;

    static {
        try {
            COMMAND_FIELD = CommandNode.class.getDeclaredField("command");
            COMMAND_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Plugin plugin;
    private final Logger log;
    private final boolean enabled;
    private final int parallelThreshold;
    private volatile boolean installed;

    private final AtomicInteger patchedNodes = new AtomicInteger();
    private final AtomicLong invocations = new AtomicLong();
    private final AtomicInteger parallelSends = new AtomicInteger();

    public ParticleCommandOffload(Plugin plugin, Logger log, boolean enabled, int parallelThreshold) {
        this.plugin = plugin;
        this.log = log;
        this.enabled = enabled;
        this.parallelThreshold = Math.max(2, parallelThreshold);
    }

    public void installDelayed() {
        if (!enabled) {
            return;
        }
        // Commands are registered before plugins enable; 1 tick delay is enough.
        plugin.getServer().getScheduler().runTask(plugin, this::install);
    }

    public void install() {
        if (!enabled || installed) {
            return;
        }
        try {
            CraftServer craft = (CraftServer) Bukkit.getServer();
            DedicatedServer server = craft.getServer();
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CommandNode<CommandSourceStack> particle = dispatcher.getRoot().getChild("particle");
            if (particle == null) {
                log.warning("[BSC] particle command node not found; parallel particle disabled");
                return;
            }
            patchedNodes.set(0);
            walk(particle, false, false, false, false, false, false);
            installed = true;
            log.info("[BSC] ParticleCommand parallel fan-out installed on "
                    + patchedNodes.get() + " leaf node(s), threshold="
                    + parallelThreshold + " viewers");
        } catch (Throwable t) {
            log.warning("[BSC] Failed to install particle offload: " + t);
        }
    }

    private void walk(CommandNode<CommandSourceStack> node,
                      boolean force, boolean hasPos, boolean hasDelta,
                      boolean hasSpeed, boolean hasCount, boolean useViewers) {
        String name = node.getName();
        boolean f = force || "force".equals(name);
        boolean v = useViewers || "viewers".equals(name);
        boolean p = hasPos || "pos".equals(name);
        boolean d = hasDelta || "delta".equals(name);
        boolean s = hasSpeed || "speed".equals(name);
        boolean c = hasCount || "count".equals(name);

        if (node.getCommand() != null) {
            Command<CommandSourceStack> replacement = createCommand(p, d, s, c, f, v);
            try {
                COMMAND_FIELD.set(node, replacement);
                patchedNodes.incrementAndGet();
            } catch (IllegalAccessException e) {
                log.warning("[BSC] Cannot replace particle command node: " + e);
            }
        }

        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            walk(child, f, p, d, s, c, v);
        }
    }

    private Command<CommandSourceStack> createCommand(boolean hasPos, boolean hasDelta,
                                                      boolean hasSpeed, boolean hasCount,
                                                      boolean force, boolean useViewers) {
        return ctx -> {
            invocations.incrementAndGet();
            CommandSourceStack source = ctx.getSource();
            ParticleOptions particle = ParticleArgument.getParticle(ctx, "name");
            Vec3 pos = hasPos ? Vec3Argument.getVec3(ctx, "pos") : source.getPosition();
            Vec3 delta = hasDelta ? Vec3Argument.getVec3(ctx, "delta") : Vec3.ZERO;
            float speed = hasSpeed ? FloatArgumentType.getFloat(ctx, "speed") : 0.0F;
            int count = hasCount ? IntegerArgumentType.getInteger(ctx, "count") : 0;

            Collection<ServerPlayer> viewers = useViewers
                    ? ParticleArgs.players(ctx)
                    : source.getServer().getPlayerList().realPlayers;

            return sendParallel(source, particle, pos, delta, speed, count, force, viewers);
        };
    }

    private int sendParallel(CommandSourceStack source, ParticleOptions particle,
                             Vec3 pos, Vec3 delta, float speed, int count,
                             boolean force, Collection<ServerPlayer> viewers)
            throws CommandSyntaxException {
        // Snapshot on the calling (main) thread — same list vanilla would iterate.
        ServerPlayer[] targets = viewers.toArray(new ServerPlayer[0]);
        if (targets.length == 0) {
            throw ERROR_FAILED.create();
        }

        ServerLevel level = source.getLevel();
        int sent;

        if (targets.length < parallelThreshold) {
            sent = sendSequential(level, particle, pos, delta, speed, count, force, targets);
        } else {
            sent = sendFanout(level, particle, pos, delta, speed, count, force, targets);
        }

        if (sent == 0) {
            throw ERROR_FAILED.create();
        }
        // Avoid BuiltInRegistries.PARTICLE_TYPE (field name differs after plugin remap).
        final String particleName = String.valueOf(particle);
        source.sendSuccess(() -> Component.translatable(
                "commands.particle.success", particleName), true);
        return sent;
    }

    private int sendSequential(ServerLevel level, ParticleOptions particle,
                               Vec3 pos, Vec3 delta, float speed, int count,
                               boolean force, ServerPlayer[] targets) {
        int n = 0;
        for (ServerPlayer player : targets) {
            if (level.sendParticles(player, particle, force, false,
                    pos.x, pos.y, pos.z, count,
                    delta.x, delta.y, delta.z, speed)) {
                n++;
            }
        }
        return n;
    }

    private int sendFanout(ServerLevel level, ParticleOptions particle,
                           Vec3 pos, Vec3 delta, float speed, int count,
                           boolean force, ServerPlayer[] targets) {
        parallelSends.incrementAndGet();
        AtomicInteger n = new AtomicInteger();
        // Independent per-player sends; Netty channel write is thread-safe.
        java.util.Arrays.stream(targets).parallel().forEach(player -> {
            if (level.sendParticles(player, particle, force, false,
                    pos.x, pos.y, pos.z, count,
                    delta.x, delta.y, delta.z, speed)) {
                n.incrementAndGet();
            }
        });
        return n.get();
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "installed=%s leaves=%d invocations=%d parallelBatches=%d threshold=%d",
                installed, patchedNodes.get(), invocations.get(), parallelSends.get(), parallelThreshold);
    }

    public boolean isInstalled() {
        return installed;
    }

    private static final class ParticleArgs {
        static Collection<ServerPlayer> players(CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException {
            return net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "viewers");
        }
    }
}
