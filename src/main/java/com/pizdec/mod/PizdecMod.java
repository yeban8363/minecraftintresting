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
			Court.ensureBuilt(s);
			Mfc.ensureBuilt(s);
			Border.ensureBuilt(s);
			Portraits.ensurePlaced(s);
			Jail.onServerStart(s);
			Court.onServerStart();
			taxTimer = 0;
			lawTimer = Laws.PERIOD - 20 * 15; // первый закон через 15 секунд
			chaosTimer = 0;
			Laws.current = null;
		});

		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (Castle.isOldNpc(entity)) {
				entity.discard();
				return;
			}
			Jail.onEntityLoad(entity);
			Court.onEntityLoad(entity);
			Fpv.onEntityLoad(entity);
			Tax.onEntityLoad(entity);
			SecretPolice.onEntityLoad(entity);
			Border.onEntityLoad(entity);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			jumps.remove(handler.player.getUuid());
			Laws.forget(handler.player.getUuid());
			Jail.onLeave(handler.player);
			Tax.onLeave(handler.player);
			SecretPolice.onLeave(handler.player);
			Propaganda.onLeave(handler.player);
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
		Hotel.tick(s);
		Docs.tick(s);
		Mfc.tick(s);
		Fpv.tick(s);
		News.tick(s);
		Elections.tick(s);
		Court.tick(s);
		War.tick(s);
		Tax.tick(s);
		Jobs.tick(s);
		Restaurant.tick(s);
		SecretPolice.tick(s);
		Border.tick(s);
		Portraits.tick(s);
		Propaganda.tick(s);
		Gui.tickAll(s);

		if (chaosTimer >= nextChaos) {
			chaosTimer = 0;
			nextChaos = U.between(20 * 90, 20 * 180);
			Chaos.random(s);
		}

		for (ServerPlayerEntity p : U.players(s)) {
			// прыжки ловим через статистику — без миксинов
			int j = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.JUMP));
			Integer was = jumps.put(p.getUuid(), j);
			if (was != null && j > was && !Jail.jailed(p)) {
				Laws.onJump(p);
				Jobs.onJump(p);
			}

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
					+ "новый закон — каждые 40 секунд, за нарушения — тюрьма. Во дворе замка — магазин, казино и апгрейдер. "
					+ "Отель, биржа труда, казна — тоже там. /bank, /zakon, /rabota, /otel, /adres, /tyurma.", Formatting.YELLOW));
		}
		if (Jail.jailed(p)) {
			Jail.suppressEscape(p);
			U.msg(p, U.t("⛓ Ты всё ещё сидишь: " + Jail.secondsLeft(p) + " сек. Срок идёт только пока ты в игре.",
					Formatting.RED));
		}
		jumps.remove(p.getUuid());
		Ranks.applyTeam(p);
		st.names.put(p.getUuid(), p.getName().getString());
		st.markDirty();
		if (!Address.registered(p.getServer(), p.getUuid())) {
			U.msg(p, U.t("🏠 У тебя нет прописки! Поставь табличку у дома и введи /adres рядом с ней, "
					+ "иначе налоговая отправит тебя в тюрьму или на войну.", Formatting.RED));
		}
		if (War.exiled(p)) U.msg(p, U.t("⚔ Ты всё ещё на войне: " + War.secondsLeft(p) + " сек.", Formatting.DARK_RED));
		if (st.borderBuilt) {
			U.msg(p, U.t("🛂 Граница королевства закрыта: ±" + Border.R + " блоков от замка. Выезд — только через КПП "
					+ "с разрешением МФЦ (/granica). В замке: статуя короля, «Вкусно и Точка», и ничего особенного.",
					Formatting.DARK_GREEN));
		}
		SecretPolice.onJoin(p);
		Border.onJoin(p);
	}

	// ------------------------------------------------------------------ платная жизнь и аресты

	private static int price(int base) {
		return base * Laws.priceMult();
	}

	private static boolean royalArea(World world, BlockPos pos, PizdecState st) {
		return world.getRegistryKey() == World.OVERWORLD
				&& (Castle.inside(st, pos) || Prison.inside(st, pos) || Court.inside(st, pos) || Mfc.inside(st, pos)
						|| Border.inside(st, pos));
	}

	private static void registerPaidStuff() {
		// сон за деньги
		EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> {
			if (player instanceof ServerPlayerEntity p) {
				if (Jail.jailed(p)) return PlayerEntity.SleepFailureReason.OTHER_PROBLEM;
				if (Laws.is("no_beds") && !p.isCreative()) {
					Jail.violation(p, "лёг спать");
					return PlayerEntity.SleepFailureReason.OTHER_PROBLEM;
				}
				if (Hotel.inOwnRoom(p)) {
					U.bar(p, U.t("🏨 Сладких снов. Кровать включена в стоимость номера.", Formatting.AQUA));
					return null;
				}
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
			PizdecState mst = PizdecState.get(p.getServer());
			if (mst.mfcBuilt && hit.getBlockPos().equals(Mfc.terminal(mst)) && world.getRegistryKey() == World.OVERWORLD) {
				if (hand == Hand.MAIN_HAND) Mfc.takeTicket(p);
				return ActionResult.SUCCESS;
			}
			boolean container = b.isOf(Blocks.CHEST) || b.isOf(Blocks.TRAPPED_CHEST) || b.isOf(Blocks.BARREL)
					|| b.isIn(BlockTags.SHULKER_BOXES) || b.isOf(Blocks.FURNACE) || b.isOf(Blocks.BLAST_FURNACE) || b.isOf(Blocks.SMOKER)
					|| b.isOf(Blocks.HOPPER) || b.isOf(Blocks.DISPENSER) || b.isOf(Blocks.DROPPER) || b.isOf(Blocks.BREWING_STAND);
			boolean placing = p.getStackInHand(hand).getItem() instanceof net.minecraft.item.BlockItem;
			if (container || placing) {
				net.minecraft.util.math.BlockPos target = placing && !container ? hit.getBlockPos().offset(hit.getSide()) : hit.getBlockPos();
				String deny = Docs.denyReason(p, target);
				if (deny != null) {
					if (hand == Hand.MAIN_HAND) U.bar(p, U.t("🏛 Нельзя: " + deny, Formatting.RED));
					return ActionResult.FAIL;
				}
			}
			if (!p.isCreative() && !Laws.allowUse(p, b, p.getStackInHand(hand))) return ActionResult.FAIL;
			if (hand == Hand.MAIN_HAND && p.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem) Jobs.onPlace(p);
			if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
			if (Laws.is("pay_click") && !p.isCreative()) Money.fine(p, 1, "платный ПКМ");
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
				if (War.onDeath(newP)) return;
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
			String deny = Docs.denyReason(p, pos);
			if (deny != null) {
				U.bar(p, U.t("🏛 Нельзя: " + deny, Formatting.RED));
				if (deny.startsWith("частная")) Jail.arrest(p, "порча чужой собственности", 45);
				return false;
			}
			return Laws.allowBreak(p);
		});

		// бить: короля, налоговую, шута и надзирателя — нельзя; указы тоже могут запрещать
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity p) || p.isCreative()) return ActionResult.PASS;
			if (Jail.jailed(p)) return ActionResult.FAIL;
			var tags = entity.getCommandTags();
			if (Portraits.isPortrait(entity)) {
				Portraits.onAttack(p, entity);
				return ActionResult.FAIL;
			}
			if (tags.contains(Restaurant.CASHIER_TAG) || tags.contains(Restaurant.CHEF_TAG)) {
				Jail.arrest(p, "нападение на работника общепита", 45);
				return ActionResult.FAIL;
			}
			if (tags.contains(SecretPolice.MAJOR_TAG) || tags.contains(SecretPolice.INTERROGATOR_TAG)) {
				SecretPolice.add(p, 30, "нападение на сотрудника тайной полиции");
				Jail.arrest(p, "нападение на сотрудника несуществующей службы", 150);
				return ActionResult.FAIL;
			}
			if (tags.contains(SecretPolice.AGENT_TAG)) {
				SecretPolice.add(p, 20, "напал на агента наружного наблюдения");
				Jail.arrest(p, "нападение на неприметного гражданина", 90);
				return ActionResult.FAIL;
			}
			if (tags.contains(Border.OFFICER_TAG) || tags.contains(Border.CUSTOMS_TAG) || tags.contains(Border.GUARD_TAG)
					|| tags.contains(Border.DUTYFREE_TAG)) {
				SecretPolice.add(p, 20, "нападение на погранслужбу");
				Jail.arrest(p, "нападение на пограничника при исполнении", 120);
				return ActionResult.FAIL;
			}
			if (tags.contains(Border.TOURIST_TAG) || tags.contains(Border.GUIDE_TAG)) {
				Jail.arrest(p, "нападение на иностранного туриста (международный скандал)", 120);
				return ActionResult.FAIL;
			}
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
			if (tags.contains(Court.JURY_TAG)) {
				Jail.arrest(p, "давление на присяжных", 90);
				return ActionResult.FAIL;
			}
			for (String mt : Mfc.TAGS) {
				if (tags.contains(mt)) {
					Jail.arrest(p, "нападение на госслужащего МФЦ", 90);
					return ActionResult.FAIL;
				}
			}
			if (tags.contains(Castle.SPEAKER_TAG) || tags.contains(Castle.CEC_TAG) || tags.contains(Castle.PARTY_TAG)) {
				Jail.arrest(p, "нападение на политика (вмешательство в демократию)", 120);
				return ActionResult.FAIL;
			}
			if (tags.contains(Fpv.TAG)) return ActionResult.FAIL;
			if (tags.contains(Castle.GATE_GUARD_TAG) || tags.contains(Castle.CAPTAIN_TAG)) {
				Jail.arrest(p, "нападение на стражу", 120);
				return ActionResult.FAIL;
			}
			if (tags.contains(Castle.HR_TAG) || tags.contains(Castle.RECEPTION_TAG) || tags.contains(Castle.TREASURER_TAG)
					|| tags.contains(Castle.SECRETARY_TAG)) {
				Jail.arrest(p, "нападение на королевского служащего", 60);
				return ActionResult.FAIL;
			}
			if (tags.contains(Court.JUDGE_TAG) || tags.contains(Court.PROSECUTOR_TAG) || tags.contains(Court.LAWYER_TAG)) {
				Jail.arrest(p, "неуважение к суду", 90);
				return ActionResult.FAIL;
			}
			if (tags.contains(Tax.TAX_CART_TAG)) {
				Jail.arrest(p, "нападение на налоговую при исполнении", 90);
				return ActionResult.FAIL;
			}
			if (tags.contains(Castle.SHOP_TAG) || tags.contains(Castle.CASINO_TAG) || tags.contains(Castle.UPGRADER_TAG)) {
				Jail.arrest(p, "нападение на королевский бизнес", 60);
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
			Jobs.onBreak(p, state);
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
			Jobs.onKill(p, killed);
			if (killed.getCommandTags().contains(War.ENEMY_TAG)) {
				Money.give(p, 5, "трофей с войны");
				return;
			}
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
			if (Docs.onChat(sender, text)) return false; // ответ на анкету паспортиста
			SecretPolice.onChat(sender, text); // прослушка (ничего не блокирует)
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
			if (tags.contains(Jail.CARRIAGE_TAG) || tags.contains(Tax.TAX_CART_TAG)) return ActionResult.FAIL;
			if (tags.contains(Court.JUDGE_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("⚖ Судья Фемида Петровна: «" + U.pick("Не мешайте правосудию.",
							"Невиновных не бывает, бывают плохо проверенные.", "Взятки не беру. Только подарки.",
							"Следующий!") + "»", Formatting.DARK_PURPLE));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Court.PROSECUTOR_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Прокурор Злобин: «Я тебя запомнил.»", Formatting.DARK_RED));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Court.LAWYER_TAG)) return ActionResult.SUCCESS;
			if (tags.contains(Restaurant.CASHIER_TAG)) {
				if (hand == Hand.MAIN_HAND) Restaurant.cashierTalk(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Restaurant.CHEF_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("👨‍🍳 Шеф Гордей: «" + U.pick("Секрет шаурмы — государственная тайна.",
							"Заказывай на кассе, на кухню нельзя.", "Король любит прожарку medium. И налоги — well done.",
							"Картошку чистим вручную. Вручную тайной полиции.") + "»", Formatting.WHITE));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(SecretPolice.MAJOR_TAG)) {
				if (hand == Hand.MAIN_HAND) SecretPolice.MajorGui.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(SecretPolice.INTERROGATOR_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("🕶 Следователь Сухов: «" + U.pick("Присаживайтесь. Пока по-хорошему.",
							"Вы что-то хотели рассказать?", "Мы ещё увидимся.") + "»", Formatting.DARK_GRAY));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(SecretPolice.AGENT_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("👤 " + entity.getName().getString() + ": «" + U.pick("Я просто стою.", "Хорошая погода, да?",
							"Я вас не знаю.", "Идите, идите. Я тоже как раз туда.") + "»", Formatting.GRAY));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Border.OFFICER_TAG) || tags.contains(Border.CUSTOMS_TAG) || tags.contains(Border.DUTYFREE_TAG)
					|| tags.contains(Border.TOURIST_TAG) || tags.contains(Border.GUIDE_TAG)) {
				if (hand == Hand.MAIN_HAND) Border.talk(p, entity);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Border.GUARD_TAG)) return ActionResult.SUCCESS;
			if (tags.contains(Castle.KING_TAG)) {
				if (hand == Hand.MAIN_HAND) audience(p, entity.getBlockPos());
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.SHOP_TAG)) {
				if (hand == Hand.MAIN_HAND) Shop.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.CASINO_TAG)) {
				if (hand == Hand.MAIN_HAND) Casino.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.UPGRADER_TAG)) {
				if (hand == Hand.MAIN_HAND) Upgrader.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.INSPECTOR_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					Address.Home a = Address.home(p.getServer(), p.getUuid());
					U.msg(p, U.t("Зинаида Петровна: «Ваш баланс " + Money.get(p) + ". Следующая проверка через "
							+ Tax.secondsLeft(taxTimer) + " сек. " + (a == null ? "Прописки у вас НЕТ, гражданин. /adres"
							: "Прописаны по адресу «" + a.name() + "».") + " В реестре " + PizdecState.get(p.getServer()).addresses.size()
							+ " адресов. Справки не выдаём, обед.»", Formatting.RED));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.HR_TAG)) {
				if (hand == Hand.MAIN_HAND) Jobs.JobsGui.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.CEC_TAG)) {
				if (hand == Hand.MAIN_HAND) Elections.BallotGui.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.SPEAKER_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					PizdecState pst = PizdecState.get(p.getServer());
					U.msg(p, U.t("🏛 Спикер Голосуев: «" + (Elections.inSession() ? "Идёт голосование по законопроекту. Депутаты: "
							+ "/parlament za | protiv | vozd." : "Следующее заседание скоро. Парламент всегда поддерживает короля.")
							+ " Состав: " + pst.seats[0] + " из " + Castle.PARLIAMENT_SEATS + " мест у «Единой Короны».»", Formatting.GOLD));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.PARTY_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					for (int i = 0; i < Elections.PARTIES.size(); i++) {
						if (tags.contains("pizdec_party_" + i)) {
							Elections.Party pt = Elections.PARTIES.get(i);
							U.msg(p, U.t(pt.leader() + ": «" + pt.slogan() + " Голосуйте за «" + pt.name() + "» в ЦИК!»", pt.color()));
						}
					}
				}
				return ActionResult.SUCCESS;
			}
			for (int k = 0; k < Mfc.TAGS.length; k++) {
				if (tags.contains(Mfc.TAGS[k])) {
					if (hand == Hand.MAIN_HAND) Mfc.clerk(p, k);
					return ActionResult.SUCCESS;
				}
			}
			if (tags.contains(Castle.RECEPTION_TAG)) {
				if (hand == Hand.MAIN_HAND) Hotel.ReceptionGui.open(p);
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.TREASURER_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("💰 Казначей Аркадий: «На вашем счету " + Money.get(p) + " бабла. Сдать ценности: /bank sell. "
							+ "Купить звание: /zvaniya. Проценты не начисляем, мы же не банк. А, мы банк. Всё равно не начисляем.»",
							Formatting.GOLD));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.CAPTAIN_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("⚔ Капитан Петрович: «" + U.pick("В стражу берём с Рыцаря. Устраивайся через биржу труда.",
							"На войне сейчас " + PizdecState.get(p.getServer()).exiles.size() + " наших. Держатся.",
							"Видел карету? Это мы её обслуживаем. Коня Гошу не трогать.",
							"Подъём в шесть, отбой когда король скажет.") + "»", Formatting.BLUE));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.SECRETARY_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("📜 Секретарь Аполлинарий: «Сейчас действует: " + Laws.currentText() + ". Указов издано: "
							+ PizdecState.get(p.getServer()).decrees + ". Помощник короля может издать внеочередной указ: /ukaz»",
							Formatting.GOLD));
				}
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Castle.GATE_GUARD_TAG)) {
				if (hand == Hand.MAIN_HAND) U.msg(p, U.t("Стражник: «Проходи. Но я за тобой слежу.»", Formatting.GRAY));
				return ActionResult.SUCCESS;
			}
			if (tags.contains(Court.JURY_TAG)) {
				if (hand == Hand.MAIN_HAND) {
					U.msg(p, U.t("Присяжный: «" + U.pick("Виновен. А что, суд уже начался?", "Я тут за бесплатный обед.",
							"Мы всегда голосуем как скажет судья.", "Хр-р-р…") + "»", Formatting.GRAY));
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
		Ranks.tryKingPromotion(p, 0.02, "за приятную беседу");
		w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, kingPos.getX() + 0.5, kingPos.getY() + 2, kingPos.getZ() + 0.5, 15,
				0.4, 0.4, 0.4, 0.1);
		U.sound(p, SoundEvents.ENTITY_VILLAGER_CELEBRATE, 1f, 0.8f);
	}

	// ------------------------------------------------------------------ команды

	/** Внеочередной указ (помощник короля, аудиенция). */
	public static void forceLaw() {
		lawTimer = Laws.PERIOD;
	}

	/** Указ введён вне очереди (например, парламентом) — следующий через полный период. */
	public static void resetLawTimer() {
		lawTimer = 0;
	}

	/** Магазин/казино/апгрейдер по команде: рядом с лавкой бесплатно, издалека — 5 бабла «за доставку». */
	private static int remote(ServerPlayerEntity p, String what) {
		PizdecState st = PizdecState.get(p.getServer());
		boolean near = st.castleBuilt && p.getServerWorld() == p.getServer().getOverworld()
				&& p.squaredDistanceTo(Castle.courtyard(st)) < 40 * 40;
		if (!near && !Jail.jailed(p) && !Money.pay(p, 5, "дистанционное обслуживание")) return 0;
		switch (what) {
			case "shop" -> Shop.open(p);
			case "casino" -> Casino.open(p);
			default -> Upgrader.open(p);
		}
		return 1;
	}

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
							if (Gui.isSpecial(st, "fake_diamond")) {
								st.setCount(0);
								Jail.arrest(p, "сбыт фальшивых алмазов", 60);
								return 0;
							}
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

		d.register(CommandManager.literal("magazin").executes(c -> remote(c.getSource().getPlayerOrThrow(), "shop")));
		d.register(CommandManager.literal("kazino").executes(c -> remote(c.getSource().getPlayerOrThrow(), "casino")));
		d.register(CommandManager.literal("upgrade").executes(c -> remote(c.getSource().getPlayerOrThrow(), "upgrade")));

		d.register(CommandManager.literal("otel").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			int r = Hotel.roomOf(p.getServer(), p.getUuid());
			if (r >= 0) {
				Hotel.Room room = Hotel.room(r);
				U.msg(p, U.t("🏨 Твой номер: " + room.no() + ". Телепорт в номер стоит 5 бабла — /otel domoy", Formatting.AQUA));
			} else {
				U.msg(p, U.t("🏨 Номера снимают у администратора Светланы в западном крыле замка (лобби отеля).", Formatting.AQUA));
			}
			return 1;
		}).then(CommandManager.literal("domoy").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			int r = Hotel.roomOf(p.getServer(), p.getUuid());
			if (r < 0 || Jail.busy(p) || !Money.pay(p, 5, "такси до отеля")) return 0;
			net.minecraft.util.math.Vec3d v = Hotel.room(r).center(PizdecState.get(p.getServer()));
			p.teleport(p.getServer().getOverworld(), v.x, v.y, v.z, p.getYaw(), 0f);
			return 1;
		})));
		d.register(CommandManager.literal("donos")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					Jobs.donos(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"));
					return 1;
				})));
		d.register(CommandManager.literal("ukaz").executes(c -> {
			Jobs.ukaz(c.getSource().getPlayerOrThrow());
			return 1;
		}));

		Docs.commands(d);
		d.register(CommandManager.literal("vybory").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("🗳 Выборы №" + (st.electionNo + 1) + " через " + Elections.minutesLeft(p.getServer()) + " мин. Голосовать — "
					+ "ЦИК в здании парламента (к югу от тронного зала), нужен паспорт. "
					+ (st.votes.containsKey(p.getUuid()) ? "Вы уже проголосовали." : "Вы ещё не голосовали.")
					+ (st.lastResult.isEmpty() ? "" : " Прошлые итоги: " + st.lastResult), Formatting.GOLD));
			return 1;
		}));
		d.register(CommandManager.literal("parlament")
				.then(CommandManager.literal("za").executes(c -> {
					Elections.voteBill(c.getSource().getPlayerOrThrow(), 0);
					return 1;
				}))
				.then(CommandManager.literal("protiv").executes(c -> {
					Elections.voteBill(c.getSource().getPlayerOrThrow(), 1);
					return 1;
				}))
				.then(CommandManager.literal("vozd").executes(c -> {
					Elections.voteBill(c.getSource().getPlayerOrThrow(), 2);
					return 1;
				})));
		d.register(CommandManager.literal("gazeta").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (!Castle.inside(st, p.getBlockPos())) {
				U.msg(p, U.t("📰 Газеты продаются в замке (киоск в лобби отеля) и приходят постояльцам каждые 2 минуты.", Formatting.GRAY));
				return 0;
			}
			if (Money.pay(p, 2, "газету")) p.getInventory().offerOrDrop(News.newspaper(p.getServer()));
			return 1;
		}));
		d.register(CommandManager.literal("fpv").executes(c -> {
			Fpv.call(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("talon").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (!st.mfcBuilt || p.squaredDistanceTo(Mfc.entrance(st)) > 30 * 30) {
				U.msg(p, U.t("🎫 Талон берут в самом МФЦ. /mfc — где он.", Formatting.GRAY));
				return 0;
			}
			Mfc.takeTicket(p);
			return 1;
		}));
		d.register(CommandManager.literal("mfc").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("🏢 Королевский МФЦ «Мои документы»: " + (st.mfcBuilt ? U.coords(st.mx + 10, st.my, st.mz + 15) : "строится")
					+ " (к северо-востоку от ворот замка). Талон — терминал у входа. Окна: №1 паспорта, №2 прописка, "
					+ "№3 дома (Росреестр), №4 нотариус. Фотобудка — слева от входа.", Formatting.AQUA));
			return 1;
		}));

		d.register(CommandManager.literal("rabota").executes(c -> {
			Jobs.JobsGui.open(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("zvaniya").executes(c -> {
			Ranks.ShopGui.open(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("adres").executes(c -> {
			Address.register(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("adresa").executes(c -> {
			Address.list(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("advokat")
				.executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					U.msg(p, U.t("💼 Адвокаты: /advokat 1 — " + Court.LAWYERS[1] + " (30), /advokat 2 — " + Court.LAWYERS[2]
							+ " (150), /advokat 3 — " + Court.LAWYERS[3] + " (500). Работают только в суде. "
							+ "Договор заранее — в магазине.", Formatting.AQUA));
					return 1;
				})
				.then(CommandManager.argument("tier", IntegerArgumentType.integer(1, 3)).executes(c -> {
					Court.hireLawyer(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "tier"));
					return 1;
				})));
		d.register(CommandManager.literal("vzyatka")
				.then(CommandManager.argument("amount", IntegerArgumentType.integer(1)).executes(c -> {
					Court.bribe(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "amount"));
					return 1;
				})));
		d.register(CommandManager.literal("voyna").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("⚔ Война идёт в " + War.DISTANCE + " блоках к востоку от короля"
					+ (st.warBuilt ? " (" + U.coords(st.wx, st.wy, st.wz) + ")" : "") + ". "
					+ (War.exiled(p) ? "Ты там. Держись ещё " + War.secondsLeft(p) + " сек." : "Ты пока в тылу."),
					Formatting.DARK_RED));
			return 1;
		}));

		d.register(CommandManager.literal("zalog").executes(c -> {
			Jail.bail(c.getSource().getPlayerOrThrow());
			return 1;
		}));

		d.register(CommandManager.literal("tyurma").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			String where = st.prisonBuilt ? U.coords(st.px + 55, st.py, st.pz + 13) : "ещё не построена";
			String me = Jail.jailed(p) ? "Ты сидишь ещё " + Jail.secondsLeft(p) + " сек. Залог: /zalog" : "Ты пока на свободе.";
			U.msg(p, U.t("⛓ Тюрьма: " + where + ". Сидит: " + Jail.inmates(p.getServer()) + ". " + me, Formatting.GRAY));
			return 1;
		}));

		SecretPolice.commands(d);
		d.register(CommandManager.literal("vyezd").executes(c -> {
			Border.exit(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("granica").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("🛂 Граница: квадрат ±" + Border.R + " блоков от замка. КПП: " + Border.checkpointsText(st)
					+ ". Выезд: паспорт + разрешение (МФЦ, окно №4, 300) + подозрительность < 40 + без долгов → /vyezd у будки. "
					+ "Импорт — контрабанда. Пешком через поле — тюрьма (шанс сбежать 0,5%)."
					+ (Border.hasPermit(p) ? " У тебя есть разрешение." : ""), Formatting.DARK_GREEN));
			return 1;
		}));
		d.register(CommandManager.literal("restoran").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			net.minecraft.util.math.Vec3d e = Restaurant.entrance(st);
			U.msg(p, U.t("🍔 " + Restaurant.NAME + ": " + U.coords((int) e.x, (int) e.y, (int) e.z)
					+ " (парламентский квартал замка, восточнее парламента). Заказ — у кассира Ксюши.", Formatting.AQUA));
			return 1;
		}));

		d.register(CommandManager.literal("pizdec")
				.requires(src -> src.hasPermissionLevel(2))
				.then(CommandManager.literal("delo")
						.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
							SecretPolice.showDossier(c.getSource(), EntityArgumentType.getPlayer(c, "player"));
							return 1;
						})))
				.then(CommandManager.literal("podozrenie")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100)).executes(c -> {
									ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
									SecretPolice.setLevel(t.getServer(), t.getUuid(), IntegerArgumentType.getInteger(c, "value"));
									SecretPolice.note(t.getServer(), t.getUuid(), "подозрительность выставлена админом");
									return 1;
								}))))
				.then(CommandManager.literal("dopros")
						.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
							SecretPolice.interrogate(EntityArgumentType.getPlayer(c, "player"), "указ админа");
							return 1;
						})))
				.then(CommandManager.literal("slava").executes(c -> {
					Propaganda.minuteOfGlory(c.getSource().getServer());
					return 1;
				}))
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
					c.getSource().sendFeedback(() -> U.t("🏰 Замок (ворота): " + U.coords(st.cx + 28, st.cy, st.cz - 4)
							+ "  трон: " + U.coords(st.cx + 28, st.cy + 1, st.cz + 40)
							+ "  тюрьма: " + U.coords(st.px + 55, st.py, st.pz + 13)
							+ "  суд: " + U.coords(st.kx + 32, st.ky, st.kz + 10)
							+ (st.warBuilt ? "  война: " + U.coords(st.wx, st.wy, st.wz) : ""), Formatting.YELLOW), false);
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
				.then(CommandManager.literal("war")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(c -> {
									War.exile(EntityArgumentType.getPlayer(c, "player"), "указ админа");
									return 1;
								})))
				.then(CommandManager.literal("rank")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("rank", IntegerArgumentType.integer(0, Ranks.MAX))
										.executes(c -> {
											Ranks.set(EntityArgumentType.getPlayer(c, "player"),
													IntegerArgumentType.getInteger(c, "rank"));
											return 1;
										}))))
				.then(CommandManager.literal("free")
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(c -> {
									ServerPlayerEntity fp = EntityArgumentType.getPlayer(c, "player");
									Jail.release(fp, "помилован админом");
									War.releaseByAdmin(fp);
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
