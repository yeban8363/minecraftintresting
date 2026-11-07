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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
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

	private static final String[] INSULTS = {"дурак", "лох", "козёл", "козел", "идиот", "дебил", "тупой", "урод",
			"чмо", "хуй", "пидор", "говно", "сука", "лошара", "клоун"};

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			Money.obj(s);
			Castle.ensureBuilt(s);
			Prison.ensureBuilt(s);
			Jail.onServerStart(s);
			taxTimer = 0;
			lawTimer = Laws.PERIOD - 20 * 15; // первый закон через 15 секунд
			chaosTimer = 0;
			Laws.current = null;
		});

		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> Jail.onEntityLoad(entity));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			jumps.remove(handler.player.getUuid());
			Laws.forget(handler.player.getUuid());
			Jail.onLeave(handler.player);
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
		Jail.tick(s);

		if (chaosTimer >= nextChaos) {
			chaosTimer = 0;
			nextChaos = U.between(20 * 90, 20 * 180);
			Chaos.random(s);
		}

		for (ServerPlayerEntity p : U.players(s)) {
			// прыжки ловим через статистику — без миксинов
			int j = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.JUMP));
			Integer was = jumps.put(p.getUuid(), j);
			if (was != null && j > was && !Jail.jailed(p)) Laws.onJump(p);

			// должникам периодически напоминаем
			if (s.getTicks() % (20 * 60) == 0 && Money.get(p) < -100 && !Jail.jailed(p)) {
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
					+ "новый закон — каждые 40 секунд, за нарушения — тюрьма. /bank, /zakon, /tyurma.", Formatting.YELLOW));
		}
		if (Jail.jailed(p)) {
			Jail.suppressEscape(p);
			U.msg(p, U.t("⛓ Ты всё ещё сидишь: " + Jail.secondsLeft(p) + " сек. Срок идёт только пока ты в игре.",
					Formatting.RED));
		}
		jumps.remove(p.getUuid());
	}

	// ------------------------------------------------------------------ платная жизнь и аресты

	private static int price(int base) {
		return base * Laws.priceMult();
	}

	private static boolean royalArea(World world, BlockPos pos, PizdecState st) {
		return world.getRegistryKey() == World.OVERWORLD && (Castle.inside(st, pos) || Prison.inside(st, pos));
	}

	private static void registerPaidStuff() {
		// сон за деньги
		EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> {
			if (player instanceof ServerPlayerEntity p) {
				if (Jail.jailed(p)) return PlayerEntity.SleepFailureReason.OTHER_PROBLEM;
				if (!Money.pay(p, price(15), "аренду кровати")) {
					Jail.arrest(p, "попытка поспать бесплатно", 20);
					return PlayerEntity.SleepFailureReason.OTHER_PROBLEM;
				}
			}
			return null;
		});

		// сундуки, верстаки, печки — всё платное; плюс указы про двери и стройку
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
			if (Jail.jailed(p) && !p.isCreative()) {
				if (hand == Hand.MAIN_HAND) U.bar(p, U.t("⛓ В тюрьме руками ничего не трогают", Formatting.GRAY));
				return ActionResult.FAIL;
			}
			BlockState b = world.getBlockState(hit.getBlockPos());
			if (!p.isCreative() && !Laws.allowUse(p, b, p.getStackInHand(hand))) return ActionResult.FAIL;
			if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
			if (p.isSneaking() && !p.getMainHandStack().isEmpty()) return ActionResult.PASS;

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
			if (Money.pay(p, price(cost), what)) return ActionResult.PASS;
			Jail.arrest(p, "мелкое мошенничество: хотел бесплатно " + what, 20);
			return ActionResult.FAIL;
		});

		// похороны за свой счёт
		ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
			if (!alive) {
				jumps.remove(newP.getUuid());
				if (Jail.jailed(newP)) {
					Jail.suppressEscape(newP);
					U.msg(newP, U.t("Смерть не освобождает от наказания.", Formatting.GRAY));
				} else {
					Money.fine(newP, 25, "похороны за свой счёт");
				}
			}
		});

		// ломать: замок и тюрьма — святое, в тюрьме — вообще ничего
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayerEntity p) || p.isCreative()) return true;
			if (Jail.jailed(p)) return false;
			PizdecState st = PizdecState.get(p.getServer());
			if (royalArea(world, pos, st)) {
				Money.fine(p, 10, "порча королевского имущества");
				Jail.arrest(p, "порча королевского имущества", 90);
				return false;
			}
			return Laws.allowBreak(p);
		});

		// бить: короля, налоговую, шута и надзирателя — нельзя; указы тоже могут запрещать
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity p) || p.isCreative()) return ActionResult.PASS;
			if (Jail.jailed(p)) return ActionResult.FAIL;
			var tags = entity.getCommandTags();
			if (tags.contains(Castle.KING_TAG)) {
				Jail.arrest(p, "покушение на Его Величество", 180);
				return ActionResult.FAIL;
			}
			if (tags.contains(Castle.INSPECTOR_TAG)) {
				Jail.arrest(p, "нападение на налоговую службу", 90);
				return ActionResult.FAIL;
			}
			if (tags.contains(Castle.JESTER_TAG)) {
				Jail.arrest(p, "обидел шута", 30);
				return ActionResult.FAIL;
			}
			if (tags.contains(Prison.GUARD_TAG)) {
				Jail.arrest(p, "нападение на надзирателя Палыча", 120);
				return ActionResult.FAIL;
			}
			if (tags.contains(Jail.CARRIAGE_TAG)) {
				Jail.arrest(p, "сопротивление при аресте", 60);
				return ActionResult.FAIL;
			}
			return Laws.allowAttack(p, entity) ? ActionResult.PASS : ActionResult.FAIL;
		});
	}

	// ------------------------------------------------------------------ как заработать

	private static void registerIncome() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayerEntity p)) return;
			Laws.onBroken(p);
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
				Money.give(p, 15, "коллектор повержен");
				Jail.arrest(p, "сопротивление коллектору", 45);
				return;
			}
			if (killed instanceof VillagerEntity) {
				Money.fine(p, 30, "убийство мирного налогоплательщика");
				Jail.arrest(p, "убийство мирного налогоплательщика", 120);
				return;
			}
			Laws.onKill(p, killed);
			if (Laws.is("kill_tax")) return;
			int base = killed instanceof Monster ? 4 : 1;
			if (Laws.is("bounty")) base += 10;
			Money.give(p, base, "охота");
		});
	}

	// ------------------------------------------------------------------ чат

	private static void registerChat() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.getSignedContent();
			String low = text.toLowerCase();
			if (low.contains("корол") || low.contains("king")) {
				for (String bad : INSULTS) {
					if (low.contains(bad)) {
						sender.getServer().execute(() -> Jail.arrest(sender, "оскорбление Его Величества", 120));
						return false;
					}
				}
			}
			if (Jail.jailed(sender)) return true; // в тюрьме можно ныть сколько угодно
			return Laws.allowChat(sender, text);
		});
	}

	// ------------------------------------------------------------------ король и его люди

	private static void registerNpcs() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
			var tags = entity.getCommandTags();
			if (tags.contains(Jail.CARRIAGE_TAG)) return ActionResult.FAIL;
			if (tags.contains(Castle.KING_TAG)) {
				if (hand == Hand.MAIN_HAND) audience(p, entity.getBlockPos());
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.INSPECTOR_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Зинаида Петровна: «Ваш баланс " + Money.get(p) + ". Следующая проверка через "
							+ Tax.secondsLeft(taxTimer) + " сек. Справки не выдаём, обед.»", Formatting.RED));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Prison.GUARD_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Надзиратель Палыч: «Сейчас сидит " + Jail.inmates(p.getServer())
							+ " чел. Всего арестов: " + PizdecState.get(p.getServer()).arrests
							+ ". Ты следующий, я чувствую.»", Formatting.DARK_GRAY));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.JESTER_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Шут Валера: «" + U.pick(
							"Знаешь, почему король издаёт закон каждые 40 секунд? Потому что каждую секунду — не успевает.",
							"Налоговая берёт двадцать процентов. Остальные восемьдесят берёт король.",
							"Я тут шут, а ты — налогоплательщик. Угадай, кто из нас смешнее.",
							"Говорят, если поговорить с королём, он может дать алмаз. Или посадить. Пятьдесят на пятьдесят.",
							"Коллектора можно убить. Долг — нельзя. А за коллектора ещё и посадят.",
							"В тюрьме кормят бесплатно. Это единственное бесплатное место в королевстве.",
							"Карета у короля быстрее, чем ты. Проверено на моём брате.") + "»", Formatting.LIGHT_PURPLE));
				}
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});
	}

	/** Аудиенция у короля: 10 бабла за вход, дальше — как повезёт. */
	private static void audience(ServerPlayerEntity p, BlockPos kingPos) {
		if (!Money.pay(p, 10, "аудиенцию у короля")) {
			U.msg(p, U.t("👑 Король: «Нищих не принимаю. Стража! В темницу его!»", Formatting.GOLD));
			Jail.arrest(p, "попрошайничество у трона", 30);
			return;
		}
		ServerWorld w = p.getServerWorld();
		int r = U.R.nextInt(9);
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
			case 7 -> {
				say = "Мне не понравилось твоё лицо. В тюрьму!";
				Jail.arrest(p, "неприятное лицо", 30);
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

		d.register(CommandManager.literal("zalog").executes(c -> {
			Jail.bail(c.getSource().getPlayerOrThrow());
			return 1;
		}));

		d.register(CommandManager.literal("tyurma").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			String where = st.prisonBuilt ? U.coords(st.px + 20, st.py, st.pz + 6) : "ещё не построена";
			String me = Jail.jailed(p) ? "Ты сидишь ещё " + Jail.secondsLeft(p) + " сек. Залог: /zalog" : "Ты пока на свободе.";
			U.msg(p, U.t("⛓ Тюрьма: " + where + ". Сидит: " + Jail.inmates(p.getServer()) + ". " + me, Formatting.GRAY));
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
							+ "  трон: " + U.coords(st.cx + 16, st.cy + 1, st.cz + 27)
							+ "  тюрьма: " + U.coords(st.px + 20, st.py, st.pz + 6), Formatting.YELLOW), false);
					return 1;
				}))
				.then(CommandManager.literal("jail")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
										.executes(c -> {
											ServerPlayerEntity to = EntityArgumentType.getPlayer(c, "player");
											Jail.arrest(to, "указ админа", IntegerArgumentType.getInteger(c, "seconds"), true);
											return 1;
										}))))
				.then(CommandManager.literal("free")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(c -> {
									Jail.release(EntityArgumentType.getPlayer(c, "player"), "помилован админом");
									return 1;
								})))
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
