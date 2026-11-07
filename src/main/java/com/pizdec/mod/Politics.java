package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * Политическая жизнь: губернии и губернаторы (король назначает и увольняет), коррупция чиновников и скандалы,
 * рейтинги (официальные и настоящие), карманная оппозиция в парламенте и административный ресурс на выборах.
 */
public final class Politics {
	public static final String[] REGIONS = {"Северная губерния", "Южная губерния", "Западная губерния", "Восточная губерния"};
	public static final String CAPITAL = "Столичный округ";
	private static final String[] GOV_NAMES = {"Хапугин Аркадий Борисович", "Откатов Пётр Ильич", "Распилов Глеб Семёнович",
			"Прихватов Савва Кузьмич", "Сундуков Ефим Данилович", "Дачников Олег Олегович", "Честнов Иван Иванович",
			"Тендеров Марк Львович", "Бюджетов Тарас Петрович", "Яхтин Роман Аркадьевич", "Загребалов Фома Ильич",
			"Нецелевой Виктор Павлович"};
	private static final String[] PURPOSES = {"дачу в три этажа", "золотой унитаз", "яхту (в сухопутном королевстве)",
			"шубу для кошки", "часы за 40 000 бабла", "«ремонт дороги», которой нет", "строительство моста через лужу",
			"закупку воздуха для губернии", "благотворительный фонд имени себя", "квартиру для коня", "забор вокруг забора",
			"фонтан с шампанским", "покраску травы к приезду короля"};
	/** Карманная (системная) оппозиция: формально против, на деле — «за». */
	public static final Set<Integer> POCKET = Set.of(1, 2, 6);
	/** Рабочие места «бюджетников» — на них давят на выборах. */
	public static final Set<String> STATE_JOBS = Set.of("tax_helper", "guard", "treasurer", "warden", "bailiff", "spy",
			"assistant", "deputy", "agent", "portier", "jester", "cook", "waiter");

	public static int disqualified = -1;
	private static int timer;
	private static final Map<UUID, Integer> cooldown = new java.util.HashMap<>();
	private static final Map<UUID, Integer> stoleThisShift = new java.util.HashMap<>();

	private Politics() {}

	// ================================================================== губернии

	public static int region(PizdecState st, Vec3d p) {
		double dx = p.x - Border.bx(st), dz = p.z - Border.bz(st);
		if (Math.abs(dx) < 150 && Math.abs(dz) < 150) return -1;
		if (Math.abs(dz) >= Math.abs(dx)) return dz < 0 ? 0 : 1;
		return dx < 0 ? 2 : 3;
	}

	public static String regionName(int r) {
		return r < 0 ? CAPITAL : REGIONS[r];
	}

	/** Губернатор: имя, игрок (или null), сколько украл, лояльность. */
	public static final class Governor {
		public String name;
		public UUID player;
		public int stolen, loyalty;

		static Governor parse(String s) {
			String[] a = s.split("\\|", -1);
			Governor g = new Governor();
			g.name = a[0];
			g.player = a.length > 1 && !a[1].isEmpty() ? UUID.fromString(a[1]) : null;
			g.stolen = a.length > 2 ? Integer.parseInt(a[2]) : 0;
			g.loyalty = a.length > 3 ? Integer.parseInt(a[3]) : 50;
			return g;
		}

		String save() {
			return name + "|" + (player == null ? "" : player) + "|" + stolen + "|" + loyalty;
		}
	}

	private static String freshName(PizdecState st) {
		List<String> pool = new ArrayList<>(List.of(GOV_NAMES));
		for (String g : st.governors) pool.remove(Governor.parse(g).name);
		return pool.isEmpty() ? U.pick(GOV_NAMES) : U.pick(pool);
	}

	public static Governor governor(PizdecState st, int r) {
		ensure(st);
		return Governor.parse(st.governors.get(r));
	}

	private static void save(PizdecState st, int r, Governor g) {
		st.governors.set(r, g.save());
		st.markDirty();
	}

	private static void ensure(PizdecState st) {
		while (st.governors.size() < 4) {
			Governor g = new Governor();
			g.name = freshName(st);
			g.loyalty = U.between(30, 70);
			st.governors.add(g.save());
			st.markDirty();
		}
	}

	public static int governorOf(MinecraftServer s, UUID id) {
		PizdecState st = PizdecState.get(s);
		ensure(st);
		for (int r = 0; r < 4; r++) if (id.equals(governor(st, r).player)) return r;
		return -1;
	}

	// ================================================================== рейтинги

	public static void event(MinecraftServer s, double real) {
		PizdecState st = PizdecState.get(s);
		st.realRating = Math.max(3, Math.min(95, st.realRating + real));
		st.markDirty();
	}

	/** Официальный рейтинг короля: всегда около 86%. */
	public static double officialKing(PizdecState st) {
		if (Propaganda.record) return 146.0;
		return 86.0 + ((st.decrees * 7 + st.newsIssue) % 30) / 10.0;
	}

	public static String pct(double v) {
		return String.format(java.util.Locale.ROOT, "%.1f%%", v).replace('.', ',');
	}

	// ================================================================== деньги и коррупция

	/** Налог уплачен: деньги в казну, губернатору — доля, а иногда — «откат». */
	public static void onTax(MinecraftServer s, ServerPlayerEntity payer, int amount) {
		if (amount <= 0) return;
		PizdecState st = PizdecState.get(s);
		st.treasury += amount;
		Address.Home h = Address.home(s, payer.getUuid());
		int r = region(st, h != null ? h.pos() : payer.getPos());
		event(s, -0.05);
		if (r < 0) return;
		Governor g = governor(st, r);
		if (g.player != null) {
			ServerPlayerEntity gp = s.getPlayerManager().getPlayer(g.player);
			if (gp != null && gp != payer) {
				int share = Math.max(1, amount * 8 / 100);
				Money.add(gp, share);
				U.bar(gp, U.t("🏛 Налоги губернии: +" + share + " бабла губернатору (законно, почти)", Formatting.GOLD));
			}
		} else if (U.chance(0.15)) {
			int take = Math.max(5, amount / 4);
			steal(st, g.name, "губернатор (" + REGIONS[r] + ")", take);
			g.stolen += take;
			save(st, r, g);
		}
	}

	private static void steal(PizdecState st, String who, String post, int amount) {
		st.treasury = Math.max(0, st.treasury - amount);
		st.log(st.corruption, who + "|" + post + "|" + amount + "|" + U.pick(PURPOSES) + "|0|" + Docs.today(), 60);
	}

	/** Разоблачение: публичный скандал. idx — номер записи в журнале коррупции. */
	public static void scandal(MinecraftServer s, int idx, String source) {
		PizdecState st = PizdecState.get(s);
		if (idx < 0 || idx >= st.corruption.size()) return;
		String[] a = st.corruption.get(idx).split("\\|", -1);
		if (a.length < 6 || a[4].equals("1")) return;
		a[4] = "1";
		st.corruption.set(idx, String.join("|", a));
		st.scandals++;
		st.govRating = Math.max(5, st.govRating - 6);
		event(s, -3);
		Propaganda.loveDrop(1.0);
		String who = a[0], post = a[1], what = a[3];
		int amount = Integer.parseInt(a[2]);
		U.say(s, U.t("💼 КОРРУПЦИОННЫЙ СКАНДАЛ (" + source + "): " + post + " " + who + " присвоил " + amount
				+ " бабла из казны на " + what + ".", Formatting.DARK_RED, Formatting.BOLD));
		U.soundAll(s, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.4f);
		boolean fired = U.chance(0.6);
		int r = -1;
		for (int i = 0; i < 4; i++) if (governor(st, i).name.equals(who)) r = i;
		if (fired) {
			U.say(s, U.t("👑 Король лично разоблачил коррупционера и отправил его в отставку «в связи с утратой доверия». "
					+ "Справедливость восторжествовала (снова).", Formatting.GOLD));
			News.breaking(s, "Его Величество лично разоблачил коррупционера " + who + ". Народ восхищён прозорливостью короля.");
			if (r >= 0) fire(s, r, "коррупционный скандал");
			else if (st.names.containsValue(who)) punishPlayer(s, who, amount);
		} else {
			U.say(s, U.t("👑 Пресс-служба: «Проверка нарушений не выявила. " + who + " — ценный кадр. "
					+ "Распространители слухов будут найдены.»", Formatting.GRAY));
			News.breaking(s, "Клеветники пытались очернить честного чиновника " + who + ". Проверка подтвердила: он честен.");
			st.govRating = Math.max(5, st.govRating - 4);
			event(s, -2);
			if (r >= 0) {
				Governor g = governor(st, r);
				g.loyalty += 10;
				save(st, r, g);
			}
		}
		st.markDirty();
	}

	private static void punishPlayer(MinecraftServer s, String name, int amount) {
		ServerPlayerEntity p = s.getPlayerManager().getPlayer(name);
		if (p == null) return;
		Money.add(p, -amount);
		Jail.arrest(p, "хищение бюджетных средств в особо крупном размере (" + amount + ")", 150);
	}

	// ================================================================== кадровые решения короля

	public static void fire(MinecraftServer s, int r, String why) {
		PizdecState st = PizdecState.get(s);
		Governor old = governor(st, r);
		U.say(s, U.t("📜 Указ: губернатор «" + REGIONS[r] + "» " + old.name + " освобождён от должности (" + why + ").",
				Formatting.GOLD));
		if (old.player != null) {
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(old.player);
			if (p != null) {
				U.title(p, U.t("📜 ВЫ УВОЛЕНЫ", Formatting.RED, Formatting.BOLD), U.t(why, Formatting.GRAY));
				if (old.stolen > 0 && U.chance(0.5)) {
					Money.add(p, -old.stolen);
					Jail.arrest(p, "хищения на посту губернатора (" + old.stolen + ")", 150);
				}
			}
		}
		Governor g = new Governor();
		g.name = freshName(st);
		g.loyalty = U.between(30, 70);
		// иногда назначают игрока
		List<ServerPlayerEntity> cands = new ArrayList<>();
		for (ServerPlayerEntity p : U.free(s)) {
			if (old.player != null && p.getUuid().equals(old.player)) continue;
			if (governorOf(s, p.getUuid()) >= 0 || !Docs.hasPassport(p)) continue;
			if (Ranks.atLeast(p, 5) || Jobs.is(p, "assistant")) cands.add(p);
		}
		if (!cands.isEmpty() && U.chance(0.45)) {
			ServerPlayerEntity p = U.pick(cands);
			g.name = p.getName().getString();
			g.player = p.getUuid();
			g.loyalty = 60;
			U.title(p, U.t("📜 ВЫ — ГУБЕРНАТОР", Formatting.GOLD, Formatting.BOLD), U.t(REGIONS[r], Formatting.YELLOW));
			U.msg(p, U.t("📜 Король назначил вас губернатором («" + REGIONS[r] + "»). Вам — 8% налогов губернии. "
					+ "/gubernator — кабинет. Воровать можно (/gubernator ukrast), но аудит не дремлет.", Formatting.GOLD));
		}
		save(st, r, g);
		U.say(s, U.t("📜 Новым губернатором назначен " + g.name + ". Народ губернии единодушно одобряет (не спрашивали).",
				Formatting.GOLD));
	}

	private static void reshuffle(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		ensure(st);
		boolean any = false;
		for (int r = 0; r < 4; r++) {
			Governor g = governor(st, r);
			double c = 0.12 + g.stolen / 4000.0 - g.loyalty / 400.0;
			if (g.name.startsWith("Честнов")) c += 0.3; // честных не любят
			if (U.chance(Math.max(0.03, c))) {
				fire(s, r, U.pick("утрата доверия", "переход на другую работу", "по собственному желанию короля",
						"слишком высокий рейтинг", "слишком низкий рейтинг", "неправильно поклонился"));
				any = true;
			}
		}
		if (!any) {
			U.say(s, U.t("📜 Король провёл совещание с губернаторами. Все сохранили посты. Пока.", Formatting.GRAY));
		}
	}

	// ================================================================== тик

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		ensure(st);
		timer++;
		// НПЦ-чиновники воруют — раз в 5 минут
		if (timer % (20 * 300) == 0) {
			for (int r = 0; r < 4; r++) {
				Governor g = governor(st, r);
				if (g.player != null || !U.chance(0.4)) continue;
				int take = U.between(50, 800);
				steal(st, g.name, "губернатор (" + REGIONS[r] + ")", take);
				g.stolen += take;
				save(st, r, g);
			}
			if (U.chance(0.3)) {
				steal(st, U.pick("Министр дорог Ямщиков", "Министр культуры Балалайкин", "Завхоз дворца Тащилов",
						"Начальник стройки статуи Монументов", "Замминистра финансов Обнулин"), "министр", U.between(100, 1500));
			}
		}
		// аудит Счётной палаты — раз в 4 минуты
		if (timer % (20 * 240) == 0) {
			for (int i = 0; i < st.corruption.size(); i++) {
				if (st.corruption.get(i).contains("|0|") && U.chance(0.12)) {
					scandal(s, i, "аудит Счётной палаты");
					break;
				}
			}
		}
		// кадровые решения — раз в 15 минут
		if (timer % (20 * 900) == 0) reshuffle(s);
		// дрейф рейтингов — раз в минуту
		if (timer % 1200 == 0) {
			st.govRating += ((st.realRating + 22) - st.govRating) * 0.1 + (U.R.nextDouble() - 0.5) * 2;
			st.govRating = Math.max(5, Math.min(90, st.govRating));
			if (st.govRating < 35) {
				st.govNo++;
				st.govRating = 61;
				U.say(s, U.t("👑 Король отправил правительство в отставку. Новое правительство №" + st.govNo
						+ " сформировано из тех же людей, но в других кабинетах.", Formatting.GOLD));
				News.breaking(s, "Новое правительство №" + st.govNo + " — правительство надежды! Состав: прежний.");
			}
			st.markDirty();
		}
		if (timer % Jobs.SHIFT == 0) stoleThisShift.clear();
	}

	// ================================================================== выборы: админресурс

	public static boolean budgetWorker(ServerPlayerEntity p) {
		Jobs.Job j = Jobs.jobOf(p);
		return j != null && STATE_JOBS.contains(j.id());
	}

	/** За 10 минут до выборов: снятие «неудобной» партии и методички бюджетникам. */
	public static void beforeElection(MinecraftServer s) {
		disqualified = -1;
		if (U.chance(0.5)) {
			int[] opp = {3, 4, 5};
			disqualified = opp[U.R.nextInt(opp.length)];
			U.say(s, U.t("🗳 ЦИК: партия «" + Elections.PARTIES.get(disqualified).name() + "» снята с выборов. Причина: "
					+ U.pick("ошибки в подписях (подписи были написаны ручкой)", "подписи собраны у мёртвых душ (не наших)",
							"неправильный шрифт в заявлении", "лидер партии подозрительно похож на кота",
							"партия зарегистрирована в пятницу") + ".", Formatting.GOLD));
		}
		for (ServerPlayerEntity p : U.players(s)) {
			if (!budgetWorker(p)) continue;
			U.msg(p, U.t("📞 Начальство (" + Jobs.jobOf(p).name() + "): «Коллеги, явка обязательна. Голосуем за «"
					+ Elections.PARTIES.get(Elections.KING).name() + "» — /vybory. Кто не придёт — без премии. "
					+ "Это не давление, это корпоративная культура.»", Formatting.YELLOW));
		}
	}

	/** После выборов: премии и «разборы» бюджетников. votes — копия голосов до очистки. */
	public static void afterElection(MinecraftServer s, Map<UUID, Integer> votes) {
		for (ServerPlayerEntity p : U.players(s)) {
			if (!budgetWorker(p)) continue;
			Integer v = votes.get(p.getUuid());
			if (v == null) {
				Money.fine(p, 20, "неявка на выборы (бюджетник)");
			} else if (v == Elections.KING) {
				Money.give(p, 30, "премия за активную гражданскую позицию");
			} else {
				U.msg(p, U.t("📞 Начальство: «Мы, конечно, не знаем, как вы голосовали. Но премии в этом месяце не будет.»",
						Formatting.RED));
				SecretPolice.add(p, 3, "бюджетник голосовал не так");
			}
		}
		disqualified = -1;
	}

	// ================================================================== парламент: карманная оппозиция

	/** Итог голосования по законопроекту с учётом фракций. {за, против, воздержались}. */
	public static int[] billResult(PizdecState st, Map<UUID, Integer> playerVotes, StringBuilder comment) {
		int za = 0, protiv = 0, vozd = 0;
		for (int i = 0; i < st.seats.length; i++) {
			int n = st.seats[i];
			if (i == Elections.KING) {
				za += n;
			} else if (POCKET.contains(i)) {
				int a = (int) Math.round(n * (0.02 + U.R.nextDouble() * 0.08));
				protiv += a;
				za += n - a;
			} else {
				int a = (int) Math.round(n * (0.4 + U.R.nextDouble() * 0.5));
				int b = (int) Math.round((n - a) * 0.5);
				protiv += a;
				vozd += b;
				za += n - a - b;
			}
		}
		for (int v : playerVotes.values()) {
			if (v == 1) {
				protiv++;
				za--;
			} else if (v == 2) {
				vozd++;
				za--;
			}
		}
		int pocket = U.pick(new ArrayList<>(POCKET));
		comment.append("Фракция «").append(Elections.PARTIES.get(pocket).name()).append("» выступила с резкой критикой законопроекта")
				.append(" и ").append(U.pick("проголосовала «за»", "единогласно поддержала его", "попросила добавки"))
				.append(".");
		return new int[] {Math.max(0, za), protiv, vozd};
	}

	// ================================================================== команды

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("reyting").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("📊 Королевский центр изучения общественного мнения (КЦИОМ):", Formatting.GOLD));
			U.msg(p, U.t("   👑 Доверие королю: " + pct(officialKing(st)), Formatting.YELLOW));
			U.msg(p, U.t("   🏛 Одобрение правительства №" + st.govNo + ": " + pct(st.govRating), Formatting.YELLOW));
			U.msg(p, U.t("   🗳 «" + Elections.PARTIES.get(0).name() + "»: " + pct(55 + st.govRating / 3), Formatting.YELLOW));
			U.msg(p, U.t("   ❤ Индекс любви: " + pct(Propaganda.loveIndex()), Formatting.YELLOW));
			for (int r = 0; r < 4; r++) {
				Governor g = governor(st, r);
				U.msg(p, U.t("   " + REGIONS[r] + ": губ. " + g.name + " — " + pct(70 + (g.name.hashCode() & 0xff) % 25),
						Formatting.GRAY));
			}
			U.msg(p, U.t("   §8Опрошено 1600 человек. Все — сотрудники КЦИОМ. Погрешность: 0%.", Formatting.DARK_GRAY));
			return 1;
		}));
		d.register(CommandManager.literal("gubernii").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			int mine = region(st, p.getPos());
			U.msg(p, U.t("🗺 Губернии королевства (ты сейчас: " + regionName(mine) + "). Столичным округом (150 блоков от замка) "
					+ "правит лично король.", Formatting.GOLD));
			for (int r = 0; r < 4; r++) {
				Governor g = governor(st, r);
				U.msg(p, U.t("   " + REGIONS[r] + " — " + g.name + (g.player != null ? " (игрок)" : ""), Formatting.YELLOW));
			}
			U.msg(p, U.t("   Губернаторов назначает и увольняет король, раз в 15 минут. Дворяне могут попасть в губернаторы.",
					Formatting.GRAY));
			return 1;
		}));
		d.register(CommandManager.literal("gubernator").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			int r = governorOf(p.getServer(), p.getUuid());
			if (r < 0) {
				U.msg(p, U.t("🏛 Вы не губернатор. Губернаторов назначает король (шанс — у дворян и помощника короля).",
						Formatting.GRAY));
				return 0;
			}
			Governor g = governor(st, r);
			U.msg(p, U.t("🏛 Кабинет губернатора («" + REGIONS[r] + "»): лояльность " + g.loyalty + ", «освоено» " + g.stolen
					+ " бабла. Доход: 8% налогов губернии. /gubernator doklad — доложить королю, "
					+ "/gubernator ukrast <сумма> — «освоить» бюджет (до 500 за смену), /gubernator otstavka — уйти.",
					Formatting.GOLD));
			return 1;
		}).then(CommandManager.literal("doklad").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			int r = governorOf(p.getServer(), p.getUuid());
			if (r < 0) return 0;
			int now = p.getServer().getTicks();
			if (now - cooldown.getOrDefault(p.getUuid(), -99999) < 20 * 300) {
				U.msg(p, U.t("🏛 Король ещё не дочитал прошлый доклад.", Formatting.GRAY));
				return 0;
			}
			cooldown.put(p.getUuid(), now);
			Governor g = governor(st, r);
			g.loyalty += 10;
			save(st, r, g);
			U.msg(p, U.t("🏛 Доклад «Об успехах губернии: рост на " + U.between(12, 300) + "%» отправлен. Король доволен. "
					+ "Лояльность +10.", Formatting.GOLD));
			return 1;
		})).then(CommandManager.literal("ukrast")
				.then(CommandManager.argument("sum", IntegerArgumentType.integer(1, 500)).executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					MinecraftServer s = p.getServer();
					PizdecState st = PizdecState.get(s);
					int r = governorOf(s, p.getUuid());
					if (r < 0) return 0;
					int sum = IntegerArgumentType.getInteger(c, "sum");
					int used = stoleThisShift.getOrDefault(p.getUuid(), 0);
					if (used + sum > 500) {
						U.msg(p, U.t("🏛 Больше 500 за смену — даже для губернатора неприлично.", Formatting.GRAY));
						return 0;
					}
					if (st.treasury < sum) {
						U.msg(p, U.t("🏛 В казне нет столько. Её уже освоили до вас.", Formatting.GRAY));
						return 0;
					}
					stoleThisShift.put(p.getUuid(), used + sum);
					steal(st, p.getName().getString(), "губернатор (" + REGIONS[r] + ")", sum);
					Governor g = governor(st, r);
					g.stolen += sum;
					save(st, r, g);
					Money.add(p, sum);
					U.msg(p, U.t("🏛 «Освоено» " + sum + " бабла на «" + U.pick(PURPOSES) + "». Всё записано в архиве. "
							+ "Аудит — раз в 4 минуты.", Formatting.DARK_GREEN));
					return 1;
				}))).then(CommandManager.literal("otstavka").executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					int r = governorOf(p.getServer(), p.getUuid());
					if (r >= 0) fire(p.getServer(), r, "по собственному желанию");
					return 1;
				})));
	}
}
