package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/**
 * Выборы в Королевский парламент (раз в 30 минут) и заседания парламента (раз в 2 минуты).
 * Конституционная монархия с парламентом: парламент голосует, король издаёт указы.
 */
public final class Elections {
	public static final int CYCLE = 20 * 60 * 30;
	public static final int SESSION = 20 * 120;
	public static final int VOTE_WINDOW = 20 * 40;
	public static final int KING = 0;

	public record Party(String name, String leader, String slogan, Formatting color, Item icon, Block wool) {}

	public static final List<Party> PARTIES = List.of(
			new Party("Единая Корона", "Аркадий Верноподданный", "Король всегда прав. Даже когда неправ — прав.",
					Formatting.GOLD, Items.GOLD_BLOCK, Blocks.YELLOW_WOOL),
			new Party("Партия Налогоплательщиков", "Зоя Квитанцина", "Платим налоги — спим спокойно. Иногда.",
					Formatting.GREEN, Items.EMERALD, Blocks.GREEN_WOOL),
			new Party("Союз Крестьян и Шахтёров", "Степан Кирка", "Копать глубже, жить проще!",
					Formatting.DARK_GRAY, Items.IRON_PICKAXE, Blocks.BROWN_WOOL),
			new Party("Либерально-Картофельная партия", "Борис Картофельный", "Свобода, равенство, пюре.",
					Formatting.YELLOW, Items.POTATO, Blocks.ORANGE_WOOL),
			new Party("Партия Свободы от Кареты", "Олег Бегущий", "Ни одна карета не догонит свободного человека!",
					Formatting.BLUE, Items.LEATHER_BOOTS, Blocks.BLUE_WOOL),
			new Party("Зелёные Криперы", "Шипа Взрывайко", "Не трогайте криперов — и они не тронут вас (неправда).",
					Formatting.DARK_GREEN, Items.GUNPOWDER, Blocks.LIME_WOOL),
			new Party("Партия Шута Валеры", "Валера (шут)", "Голосуй за Валеру — хуже уже не будет!",
					Formatting.LIGHT_PURPLE, Items.CARVED_PUMPKIN, Blocks.PURPLE_WOOL));

	/** Текущий законопроект. */
	private static Laws.Law bill;
	private static int billTicks = -1;
	private static int sessionTimer;
	private static final Map<UUID, Integer> billVotes = new HashMap<>();

	private Elections() {}

	public static int minutesLeft(MinecraftServer s) {
		return Math.max(0, (CYCLE - PizdecState.get(s).electionTicks) / 20 / 60);
	}

	// ================================================================== голосование граждан

	public static void vote(ServerPlayerEntity p, int party) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!Docs.require(p, "участия в выборах")) return;
		if (Jail.busy(p)) {
			U.msg(p, U.t("🗳 ЦИК: «Заключённые и военнослужащие на фронте голосуют позже. Никогда.»", Formatting.RED));
			return;
		}
		if (st.votes.containsKey(p.getUuid())) {
			U.msg(p, U.t("🗳 ЦИК: «Вы уже проголосовали. Дважды голосуют только мёртвые души.»", Formatting.RED));
			return;
		}
		if (party == Politics.disqualified) {
			U.msg(p, U.t("🗳 ЦИК: «Эта партия снята с выборов. Бюллетень испорчен. Можете проголосовать за оставшихся.»",
					Formatting.RED));
			return;
		}
		st.votes.put(p.getUuid(), party);
		st.markDirty();
		if (party != KING) SecretPolice.add(p, 3, "голосовал за «" + PARTIES.get(party).name() + "»");
		Party pt = PARTIES.get(party);
		U.title(p, U.t("🗳 ВАШ ГОЛОС УЧТЁН", Formatting.GOLD, Formatting.BOLD), U.t("Спасибо за гражданскую позицию", Formatting.GRAY));
		U.msg(p, U.t("🗳 Бюллетень опущен в урну. Выбор: «" + pt.name() + "». Итоги — через " + minutesLeft(p.getServer())
				+ " мин. Каждый голос важен!", Formatting.GOLD));
		U.sound(p, SoundEvents.BLOCK_BARREL_CLOSE, 1f, 1f);
	}

	public static final class BallotGui extends Gui {
		private static final int[] SLOTS = {19, 20, 21, 22, 23, 24, 25};

		private BallotGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🗳 Избирательный бюллетень", BallotGui::new);
		}

		private void redraw() {
			fill(Items.WHITE_STAINED_GLASS_PANE);
			PizdecState st = PizdecState.get(player.getServer());
			set(4, icon(Items.PAPER, "§f§lВыборы в Королевский парламент №" + (st.electionNo + 1),
					"§7Отметьте ОДНУ партию.", "§7Итоги через " + minutesLeft(player.getServer()) + " мин.",
					st.votes.containsKey(player.getUuid()) ? "§aВы уже проголосовали" : "§eНужен паспорт"));
			for (int i = 0; i < PARTIES.size(); i++) {
				Party pt = PARTIES.get(i);
				boolean dq = i == Politics.disqualified;
				set(SLOTS[i], icon(dq ? Items.BARRIER : pt.icon(), (dq ? "§m" : "") + pt.color() + "§l" + pt.name(),
						"§7Лидер: " + pt.leader(), "§7«" + pt.slogan() + "»", "§7Мест в парламенте: " + st.seats[i],
						i == KING ? "§6Правящая партия" : Politics.POCKET.contains(i) ? "§7Системная оппозиция" : "§7Оппозиция",
						dq ? "§cСНЯТА С ВЫБОРОВ" : "§eЛКМ — отдать голос"));
			}
			set(40, icon(Items.BOOK, "§7Прошлые итоги", st.lastResult.isEmpty() ? "§7Выборов ещё не было" : "§7" + st.lastResult));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			for (int i = 0; i < SLOTS.length; i++) {
				if (SLOTS[i] == slot) {
					close();
					vote(player, i);
					return;
				}
			}
		}
	}

	// ================================================================== подсчёт

	/**
	 * Подсчёт голосов по утверждённой ЦИК методике. Учитываются бюллетени граждан
	 * и «электронные голоса» остального населения королевства.
	 */
	private static double[] tally(PizdecState st) {
		double[] share = new double[PARTIES.size()];
		int[] real = new int[PARTIES.size()];
		for (int v : st.votes.values()) if (v >= 0 && v < real.length) real[v]++;
		// «электронное голосование» населения
		double crown = 0.61 + U.R.nextDouble() * 0.18;
		if (Laws.is("free_elections")) crown = 0.54 + U.R.nextDouble() * 0.05;
		double rest = 1 - crown;
		double[] w = new double[PARTIES.size()];
		double sum = 0;
		for (int i = 1; i < w.length; i++) {
			w[i] = 0.3 + U.R.nextDouble() + real[i] * 0.4;
			sum += w[i];
		}
		share[KING] = crown;
		for (int i = 1; i < w.length; i++) share[i] = rest * w[i] / sum;
		int dq = Politics.disqualified;
		if (dq > 0 && dq < share.length) {
			share[KING] += share[dq];
			share[dq] = 0;
		}
		return share;
	}

	private static int[] seats(double[] share, int total) {
		int[] seats = new int[share.length];
		double[] rem = new double[share.length];
		int used = 0;
		for (int i = 0; i < share.length; i++) {
			double exact = share[i] * total;
			seats[i] = (int) Math.floor(exact);
			rem[i] = exact - seats[i];
			used += seats[i];
		}
		while (used < total) {
			int best = 0;
			for (int i = 1; i < rem.length; i++) if (rem[i] > rem[best]) best = i;
			seats[best]++;
			rem[best] = -1;
			used++;
		}
		return seats;
	}

	public static void hold(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.electionNo++;
		double[] share = tally(st);
		int voters = 9000 + U.R.nextInt(6000) + st.votes.size();
		double turnout = 0.71 + U.R.nextDouble() * 0.18;
		st.seats = seats(share, Castle.PARLIAMENT_SEATS);
		Integer[] order = new Integer[PARTIES.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Double.compare(share[b], share[a]));

		U.titleAll(s, U.t("🗳 ИТОГИ ВЫБОРОВ №" + st.electionNo, Formatting.GOLD, Formatting.BOLD),
				U.t("Победила «" + PARTIES.get(order[0]).name() + "» — " + pct(share[order[0]]), Formatting.YELLOW));
		U.say(s, U.t("🗳 ЦИК: выборы в Королевский парламент №" + st.electionNo + " признаны состоявшимися. Явка: "
				+ pct(turnout) + " (" + (int) (voters * turnout) + " избирателей). Нарушений не выявлено.", Formatting.GOLD));
		StringBuilder res = new StringBuilder();
		for (int i : order) {
			Party pt = PARTIES.get(i);
			U.say(s, U.t("   " + pct(share[i]) + " — «" + pt.name() + "» (" + st.seats[i] + " мест)", pt.color()));
			if (res.length() < 120) res.append(pt.name()).append(" ").append(pct(share[i])).append("; ");
		}
		U.say(s, U.t("🗳 Председатель ЦИК Нина Подсчётова: «Выборы прошли честно, прозрачно и с первой попытки.»", Formatting.GRAY));
		st.lastResult = "№" + st.electionNo + ": " + res;
		Politics.afterElection(s, new java.util.HashMap<>(st.votes));
		st.votes.clear();
		st.markDirty();
		Castle.paintSeats(s);
		U.soundAll(s, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.7f);
		News.breaking(s, "Убедительная победа «" + PARTIES.get(order[0]).name() + "» — " + pct(share[order[0]])
				+ "! Народ единодушно поддержал курс Его Величества.");
	}

	public static String pct(double v) {
		return String.format("%.1f%%", v * 100).replace('.', ',');
	}

	// ================================================================== парламент

	public static boolean inSession() {
		return bill != null;
	}

	public static void voteBill(ServerPlayerEntity p, int choice) {
		Jobs.Job j = Jobs.jobOf(p);
		if (j == null || !j.id().equals("deputy")) {
			U.msg(p, U.t("🏛 Голосуют только депутаты. Устроиться — биржа труда (/rabota).", Formatting.RED));
			return;
		}
		if (bill == null) {
			U.msg(p, U.t("🏛 Сейчас нет законопроекта на голосовании.", Formatting.GRAY));
			return;
		}
		if (billVotes.containsKey(p.getUuid())) {
			U.msg(p, U.t("🏛 Вы уже проголосовали. Кнопку за отсутствующих коллег нажмёт спикер.", Formatting.GRAY));
			return;
		}
		billVotes.put(p.getUuid(), choice);
		Jobs.onDeputyVote(p);
		U.msg(p, U.t("🏛 Ваш голос («" + (choice == 0 ? "за" : choice == 1 ? "против" : "воздержался") + "») учтён.", Formatting.GOLD));
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		st.electionTicks++;
		if (st.electionTicks % 1200 == 0) st.markDirty();
		int left = CYCLE - st.electionTicks;
		if (left == 20 * 60 * 10) Politics.beforeElection(s);
		if (left == 20 * 60 * 5) {
			U.say(s, U.t("🗳 До выборов в парламент 5 минут! Голосовать — ЦИК в здании парламента (паспорт обязателен). /vybory",
					Formatting.GOLD));
		}
		if (left <= 0) {
			st.electionTicks = 0;
			hold(s);
		}

		// заседания
		sessionTimer++;
		if (bill == null && sessionTimer >= SESSION) {
			sessionTimer = 0;
			startBill(s);
		}
		if (bill != null) {
			billTicks++;
			if (billTicks >= VOTE_WINDOW) finishBill(s);
		}
	}

	private static void startBill(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Laws.Law l;
		do {
			l = U.pick(Laws.ALL);
		} while (l == Laws.current && Laws.ALL.size() > 1);
		bill = l;
		billTicks = 0;
		billVotes.clear();
		st.billNo++;
		st.markDirty();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			Jobs.Job j = Jobs.jobOf(p);
			boolean deputy = j != null && j.id().equals("deputy");
			boolean inHall = Castle.inParliament(st, p.getPos());
			if (deputy || inHall) {
				U.msg(p, U.t("🏛 Парламент: на голосование вынесен законопроект №" + st.billNo + " «" + l.title + "». "
						+ (deputy ? "Голосуйте 40 сек: /parlament za | protiv | vozd" : "Депутаты голосуют."), Formatting.GOLD));
			}
		}
	}

	private static void finishBill(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Laws.Law l = bill;
		bill = null;
		billTicks = -1;
		StringBuilder comment = new StringBuilder();
		int[] res = Politics.billResult(st, billVotes, comment);
		int za = res[0], against = res[1], abst = res[2];
		U.say(s, U.t("🏛 " + comment, Formatting.GRAY));
		U.say(s, U.t("🏛 Парламент принял закон №" + st.billNo + " «" + l.title + "»: за — " + za + ", против — " + against
				+ ", воздержались — " + abst + ". Король подписал. Закон вступает в силу немедленно.", Formatting.GOLD));
		billVotes.clear();
		Laws.enact(s, l, "по решению парламента");
	}

	// ================================================================== депутатская неприкосновенность

	public static boolean deputyImmune(ServerPlayerEntity p) {
		Jobs.Job j = Jobs.jobOf(p);
		return j != null && j.id().equals("deputy") && Castle.inParliament(PizdecState.get(p.getServer()), p.getPos());
	}

	public static List<String> partyLines() {
		List<String> l = new ArrayList<>();
		for (Party p : PARTIES) l.add(p.name());
		return l;
	}
}
