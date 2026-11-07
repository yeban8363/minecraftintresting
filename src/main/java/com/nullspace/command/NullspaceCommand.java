package com.nullspace.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.nullspace.mechanic.AtmosphereDirector;
import com.nullspace.mechanic.BlackoutManager;
import com.nullspace.mechanic.NoclipManager;
import com.nullspace.mechanic.NullspaceTeleporter;
import com.nullspace.mechanic.StabilityManager;
import com.nullspace.mechanic.StadiumTeleporter;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Отладочные команды (уровень прав 2):
 *  /nullspace enter [игрок]    — провалиться сразу
 *  /nullspace trigger [игрок]  — провал с предвестниками
 *  /nullspace exit [игрок]     — вернуться домой
 *  /nullspace level1 [игрок]   — провалиться на Уровень 1
 *  /nullspace level2 [игрок]   — провалиться на Уровень 2
 *  /nullspace level3 [игрок]   — подняться на Уровень 3
 *  /nullspace blackout         — обесточивание
 *  /nullspace run              — бегущие шаги
 *  /nullspace stability <0-100>
 *  /nullspace stadium          — войти в стадион / выйти из него
 *  /nullspace silence <секунды>
 */
public final class NullspaceCommand {
	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("nullspace")
				.requires(source -> source.hasPermissionLevel(2))
				.then(withTarget("enter", NullspaceTeleporter::sendToNullspace))
				.then(withTarget("trigger", NoclipManager::begin))
				.then(withTarget("exit", NullspaceTeleporter::returnHome))
				.then(literal("stability")
						.then(argument("value", IntegerArgumentType.integer(0, 100))
								.executes(ctx -> {
									ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
									StabilityManager.set(player, IntegerArgumentType.getInteger(ctx, "value"));
									return 1;
								})))
				.then(withTarget("level1", NullspaceTeleporter::sendToLevel1))
				.then(withTarget("level2", NullspaceTeleporter::sendToLevel2))
				.then(withTarget("level3", NullspaceTeleporter::sendToLevel3))
				.then(literal("blackout").executes(ctx -> {
					BlackoutManager.start(ctx.getSource().getPlayerOrThrow(), 20 * 15);
					return 1;
				}))
				.then(literal("run").executes(ctx -> {
					AtmosphereDirector.forceRun(ctx.getSource().getPlayerOrThrow());
					return 1;
				}))
				.then(literal("stadium").executes(ctx -> {
					StadiumTeleporter.use(ctx.getSource().getPlayerOrThrow());
					return 1;
				}))
				.then(literal("silence")
						.then(argument("seconds", IntegerArgumentType.integer(1, 600))
								.executes(ctx -> {
									ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
									AtmosphereDirector.forceSilence(player, 20 * IntegerArgumentType.getInteger(ctx, "seconds"));
									return 1;
								}))));
	}

	private interface PlayerAction {
		void run(ServerPlayerEntity player);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> withTarget(String name, PlayerAction action) {
		return literal(name)
				.executes(ctx -> apply(ctx, ctx.getSource().getPlayerOrThrow(), action))
				.then(argument("target", EntityArgumentType.player())
						.executes(ctx -> apply(ctx, EntityArgumentType.getPlayer(ctx, "target"), action)));
	}

	private static int apply(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player, PlayerAction action) throws CommandSyntaxException {
		action.run(player);
		ctx.getSource().sendFeedback(() -> Text.literal("NULLSPACE: " + player.getName().getString()), true);
		return 1;
	}

	private NullspaceCommand() {
	}
}
