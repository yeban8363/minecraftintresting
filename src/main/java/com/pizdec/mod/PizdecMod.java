package com.pizdec.mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class PizdecMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("pizdec");

	private static int taxTimer;
	private static int lawTimer;
	private static int chaosTimer;
	private static int nextChaos = 20 * 120;
	private static final Map<UUID, Integer> jumps = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			Money.obj(s);
			Castle.ensureBuilt(s);
			taxTimer = 0;
			lawTimer = Laws.PERIOD - 20 * 15; // первый закон через 15 секунд
			chaosTimer = 0;
			Laws.current = null;
		});

		ServerTickEvents.END_SERVER_TICK.register(this::tick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			jumps.remove(handler.player.getUuid());
			Laws.forget(handler.player.getUuid());
		});

		registerPaidStuff();
		registerIncome();
		registerChat();
		registerNpcs();
		CommandRegistrationCallback.EVENT.register((d, reg, env) -> commands(d));
	}

	// ------------------------------------------------------------------ таймеры

	private void tick(MinecraftServer s) {
		taxTimer++;
		lawTimer++;
		chaosTimer++;

		if (taxTimer == Tax.PERIOD - 20 * 30) Tax.warn(s);
		if (taxTimer >= Tax.PERIOD) {
			taxTimer = 0;
			Tax.raid(s);
		}

		if (lawTimer >= Laws.PERIOD) {
			lawTimer = 0;
			Laws.next(s);
		}
		Laws.tick(s);

		if (chaosTimer >= nextChaos) {
			chaosTimer = 0;
			nextChaos = U.between(20 * 90, 20 * 180);
			Chaos.random(s);
		}

		for (ServerPlayerEntity p : U.players(s)) {
			// прыжки ловим через статистику — без миксинов
			int j = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.JUMP));
			Integer was = jumps.put(p.getUuid(), j);
			if (was != null && j > was) Laws.onJump(p);

			// должникам периодически напоминаем
			if (s.getTicks() % (20 * 60) == 0 && Money.get(p) < -100) {
				U.msg(p, U.t("📞 Банк: у вас долг " + (-Money.get(p)) + ". Коллекторы уже в пути.", Formatting.DARK_RED));
				Tax.sendCollector(p);
			}
		}
	}

	private static void onJoin(ServerPlayerEntity p) {
		if (!Money.hasAccount(p)) {
			Money.set(p, Money.START);
			U.msg(p, U.t("Добро пожаловать в Королевство Пиздец! Вот тебе " + Money.START
					+ " бабла на первое время. Его скоро заберёт налоговая.", Formatting.GOLD));
		}
		PizdecState st = PizdecState.get(p.getServer());
		if (st.castleBuilt) {
			U.msg(p, U.t("🏰 Замок короля: " + U.coords(st.cx + 16, st.cy, st.cz) + " (ворота). Налоговая — каждые 5 минут, "
					+ "новый закон — каждую минуту. /bank — твои деньги, /zakon — текущий закон.", Formatting.YELLOW));
		}
		jumps.remove(p.getUuid());
	}

	// ------------------------------------------------------------------ платная жизнь

	private static int price(int base) {
		return base * Laws.priceMult();
	}

	private static void registerPaidStuff() {
		// сон за деньги
		EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> {
			if (player instanceof ServerPlayerEntity p) {
				if (!Money.pay(p, price(15), "аренду кровати")) return PlayerEntity.SleepFailureReason.OTHER_PROBLEM;
			}
			return null;
		});

		// сундуки, верстаки, печки — всё платное
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
			if (p.isSneaking() && !p.getMainHandStack().isEmpty()) return ActionResult.PASS;
			BlockState b = world.getBlockState(hit.getBlockPos());
			int cost = 0;
			String what = null;
			if (b.isOf(Blocks.CHEST) || b.isOf(Blocks.TRAPPED_CHEST) || b.isOf(Blocks.BARREL) || b.isIn(BlockTags.SHULKER_BOXES)) {
				cost = 1;
				what = "доступ к личному имуществу";
			} else if (b.isOf(Blocks.CRAFTING_TABLE)) {
				cost = 2;
				what = "аренду верстака";
			} else if (b.isOf(Blocks.FURNACE) || b.isOf(Blocks.BLAST_FURNACE) || b.isOf(Blocks.SMOKER)) {
				cost = 2;
				what = "газ (опять подорожал)";
			} else if (b.isOf(Blocks.ENCHANTING_TABLE)) {
				cost = 10;
				what = "лицензию на магию";
			} else if (b.isIn(BlockTags.ANVIL)) {
				cost = 5;
				what = "аренду наковальни";
			} else if (b.isOf(Blocks.ENDER_CHEST)) {
				cost = 3;
				what = "офшорный счёт";
			}
			if (cost == 0) return ActionResult.PASS;
			return Money.pay(p, price(cost), what) ? ActionResult.PASS : ActionResult.FAIL;
		});

		// похороны за свой счёт
		ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
			if (!alive) {
				Money.fine(newP, 25, "похороны за свой счёт");
				jumps.remove(newP.getUuid());
			}
		});

		// королевское имущество трогать нельзя
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayerEntity p) || p.isCreative()) return true;
			if (world.getRegistryKey() == World.OVERWORLD && Castle.inside(PizdecState.get(p.getServer()), pos)) {
				Money.fine(p, 10, "порча королевского имущества");
				return false;
			}
			if (Laws.is("no_dig") && !Money.pay(p, 3, "лицензию на копание")) return false;
			return true;
		});
	}

	// ------------------------------------------------------------------ как заработать

	private static void registerIncome() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayerEntity p)) return;
			String id = Registries.BLOCK.getId(state.getBlock()).getPath();
			if (id.contains("diamond_ore") || id.contains("emerald_ore") || id.equals("ancient_debris")) {
				Money.give(p, 25, "ценная руда");
			} else if (id.endsWith("_ore")) {
				Money.give(p, 5, "руда");
			} else if (U.chance(0.05)) {
				Money.give(p, 1, "нашёл монетку в блоке");
			}
			if (Laws.is("build_reward")) Money.give(p, 2, "стройка века");
			if (Laws.is("money_trees") && state.isIn(BlockTags.LOGS)) Money.give(p, 5, "денежное дерево");
		});

		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killer, killed) -> {
			if (!(killer instanceof ServerPlayerEntity p)) return;
			if (killed.getCommandTags().contains(Tax.COLLECTOR_TAG)) {
				Money.give(p, 15, "коллектор повержен, долг частично списан");
				return;
			}
			if (killed instanceof VillagerEntity) {
				Money.fine(p, 30, "убийство мирного налогоплательщика");
				return;
			}
			if (Laws.is("kill_tax")) {
				Money.fine(p, 5, "моб был под защитой государства");
				return;
			}
			int base = killed instanceof Monster ? 4 : 1;
			if (Laws.is("bounty")) base += 10;
			Money.give(p, base, "охота");
		});
	}

	// ------------------------------------------------------------------ чат

	private static void registerChat() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (Laws.is("chat_paid")) return Money.pay(sender, 5, "свободу слова");
			if (Laws.is("chat_reward")) Money.give(sender, 3, "болтовня");
			return true;
		});
	}

	// ------------------------------------------------------------------ король и его люди

	private static void registerNpcs() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
			if (entity.getCommandTags().contains(Castle.KING_TAG)) {
				if (hand == Hand.MAIN_HAND) audience(p, entity.getBlockPos());
				return ActionResult.SUCCESS;
			}
			if (entity.getCommandTags().contains(Castle.INSPECTOR_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Зинаида Петровна: «Ваш баланс " + Money.get(p) + ". Следующая проверка через "
							+ Tax.secondsLeft(taxTimer) + " сек. Справки не выдаём, обед.»", Formatting.RED));
				}
				return ActionResult.SUCCESS;
			}
			if (entity.getCommandTags().contains(Castle.JESTER_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Шут Валера: «" + U.pick(
							"Знаешь, почему король издаёт закон каждую минуту? Потому что каждую секунду — не успевает.",
							"Налоговая берёт двадцать процентов. Остальные восемьдесят берёт король.",
							"Я тут шут, а ты — налогоплательщик. Угадай, кто из нас смешнее.",
							"Говорят, если поговорить с королём, он может дать алмаз. Или поджечь. Пятьдесят на пятьдесят.",
							"Коллектора можно убить. Долг — нельзя.") + "»", Formatting.LIGHT_PURPLE));
				}
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});
	}

	/** Аудиенция у короля: 10 бабла за вход, дальше — как повезёт. */
	private static void audience(ServerPlayerEntity p, BlockPos kingPos) {
		if (!Money.pay(p, 10, "аудиенцию у короля")) {
			U.msg(p, U.t("👑 Король: «Нищих не принимаю. Стража!»", Formatting.GOLD));
			p.addVelocity(0, 0.8, -1.5);
			p.velocityModified = true;
			return;
		}
		ServerWorld w = p.getServerWorld();
		int r = U.R.nextInt(8);
		String say;
		switch (r) {
			case 0 -> {
				say = "Держи 50 бабла и не говори никому.";
				Money.give(p, 50, "королевская милость");
			}
			case 1 -> {
				say = "Ты мне нравишься. Держи алмаз.";
				p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND));
			}
			case 2 -> {
				say = "Посвящаю тебя в рыцари! (временно)";
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 1));
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 60, 1));
			}
			case 3 -> {
				say = "Ты мне надоел. Лети.";
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 60, 3));
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 15, 0));
			}
			case 4 -> {
				say = "Хочешь новый закон? Получай новый закон!";
				lawTimer = Laws.PERIOD;
			}
			case 5 -> {
				say = "Налоговая! Тут один лишние деньги принёс.";
				taxTimer = Math.max(taxTimer, Tax.PERIOD - 20 * 30);
			}
			case 6 -> {
				say = "Хо-хо, держи подарок. Он немного горячий.";
				p.setOnFireFor(4f);
				p.getInventory().offerOrDrop(new ItemStack(Items.GOLD_INGOT, 3));
			}
			default -> {
				say = "Я подумаю над твоей просьбой. Плата не возвращается.";
			}
		}
		U.msg(p, U.t("👑 " + Castle.KING_NAME + ": «" + say + "»", Formatting.GOLD));
		w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, kingPos.getX() + 0.5, kingPos.getY() + 2, kingPos.getZ() + 0.5, 15,
				0.4, 0.4, 0.4, 0.1);
		U.sound(p, SoundEvents.ENTITY_VILLAGER_CELEBRATE, 1f, 0.8f);
	}

	// ------------------------------------------------------------------ команды

	private static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("bank")
				.executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					U.msg(p, U.t("💰 У тебя " + Money.get(p) + " бабла. Налоговая через "
							+ Tax.secondsLeft(taxTimer) + " сек.", Formatting.GOLD));
					return 1;
				})
				.then(CommandManager.literal("pay")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
										.executes(c -> {
											ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
											ServerPlayerEntity to = EntityArgumentType.getPlayer(c, "player");
											int amount = IntegerArgumentType.getInteger(c, "amount");
											int fee = Math.max(1, amount / 10);
											if (to == p) {
												Money.fine(p, fee, "перевод самому себе (а что, нельзя?)");
												return 1;
											}
											if (!Money.pay(p, amount + fee, "перевод + комиссия банка " + fee)) return 0;
											Money.give(to, amount, "перевод от " + p.getName().getString());
											return 1;
										}))))
				.then(CommandManager.literal("sell")
						.executes(c -> {
							ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
							ItemStack st = p.getMainHandStack();
							int each = 0;
							if (st.isOf(Items.EMERALD)) each = 15;
							else if (st.isOf(Items.DIAMOND)) each = 30;
							else if (st.isOf(Items.GOLD_INGOT)) each = 6;
							else if (st.isOf(Items.IRON_INGOT)) each = 3;
							else if (st.isOf(Items.COAL)) each = 1;
							else if (st.isOf(Items.NETHERITE_INGOT)) each = 200;
							if (each == 0) {
								U.msg(p, U.t("Скупка принимает только изумруды, алмазы, золото, железо, уголь и незерит.",
										Formatting.GRAY));
								return 0;
							}
							int total = each * st.getCount();
							st.setCount(0);
							Money.give(p, total, "сдал в скупку");
							return 1;
						})));

		d.register(CommandManager.literal("zakon").executes(c -> {
			c.getSource().sendFeedback(() -> U.t("📜 Сейчас действует: " + Laws.currentText(), Formatting.GOLD), false);
			return 1;
		}));

		d.register(CommandManager.literal("pizdec")
				.requires(src -> src.hasPermissionLevel(2))
				.then(CommandManager.literal("tax").executes(c -> {
					taxTimer = Tax.PERIOD;
					return 1;
				}))
				.then(CommandManager.literal("law").executes(c -> {
					lawTimer = Laws.PERIOD;
					return 1;
				}))
				.then(CommandManager.literal("chaos").executes(c -> {
					Chaos.random(c.getSource().getServer());
					return 1;
				}))
				.then(CommandManager.literal("castle").executes(c -> {
					PizdecState st = PizdecState.get(c.getSource().getServer());
					c.getSource().sendFeedback(() -> U.t("🏰 Замок: " + U.coords(st.cx + 16, st.cy, st.cz)
							+ "  трон: " + U.coords(st.cx + 16, st.cy + 1, st.cz + 27), Formatting.YELLOW), false);
					return 1;
				}))
				.then(CommandManager.literal("give")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", IntegerArgumentType.integer())
										.executes(c -> {
											ServerPlayerEntity to = EntityArgumentType.getPlayer(c, "player");
											Money.give(to, IntegerArgumentType.getInteger(c, "amount"), "указ админа");
											return 1;
										})))));
	}
}
