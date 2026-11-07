package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * Работа. 10 профессий, смена — 2 минуты. Выполнил план — получил зарплату (минус 13% НДФЛ).
 * Два прогула подряд — уволен. Чтобы взяли, нужно подходящее звание, а иногда и ещё кое-что.
 */
public final class Jobs {
	public static final int SHIFT = 20 * 120;

	public record Job(String id, String name, Item icon, String task, int quota, int salary, int minRank,
			Predicate<ServerPlayerEntity> extra, String extraText) {}

	public static final List<Job> ALL = new ArrayList<>();
	private static final Map<UUID, Integer> progress = new HashMap<>();
	private static final Map<UUID, Integer> fishSeen = new HashMap<>();
	private static final Map<UUID, Vec3d> walkPos = new HashMap<>();
	/** «Пассивные» начисления за смену: доля налогов, аресты, доносы, гости отеля. */
	private static final Map<UUID, Integer> taxShare = new HashMap<>();
	private static final Map<UUID, Integer> cookSeen = new HashMap<>();
	private static final Map<UUID, Integer> donosCount = new HashMap<>();
	private static final Map<UUID, Integer> lastDonos = new HashMap<>();
	private static final java.util.Set<UUID> ukazUsed = new java.util.HashSet<>();
	private static final net.minecraft.item.Item[] FOODS = {Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
			Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO, Items.BREAD,
			Items.PUMPKIN_PIE, Items.CAKE, Items.COOKIE, Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP};
	private static int shiftTimer;

	private static void job(String id, String name, Item icon, String task, int quota, int salary, int minRank) {
		ALL.add(new Job(id, name, icon, task, quota, salary, minRank, p -> true, null));
	}

	private static void job(String id, String name, Item icon, String task, int quota, int salary, int minRank,
			Predicate<ServerPlayerEntity> extra, String extraText) {
		ALL.add(new Job(id, name, icon, task, quota, salary, minRank, extra, extraText));
	}

	static {
		job("janitor", "Дворник", Items.BRUSH, "сломать траву и цветы", 15, 25, 0);
		job("lumberjack", "Лесоруб", Items.IRON_AXE, "нарубить брёвен", 20, 45, 0);
		job("fisher", "Рыбак", Items.FISHING_ROD, "поймать рыбу", 3, 50, 0);
		job("miner", "Шахтёр", Items.IRON_PICKAXE, "добыть камня и руды", 40, 60, 1,
				p -> p.experienceLevel >= 5, "уровень опыта 5+");
		job("farmer", "Фермер", Items.WHEAT, "собрать урожай", 15, 50, 1);
		job("hunter", "Охотник", Items.BOW, "убить мобов или животных", 6, 55, 2);
		job("courier", "Курьер", Items.LEATHER_BOOTS, "пройти блоков", 400, 40, 2);
		job("tax_helper", "Помощник налоговой", Items.PAPER, "ничего, получаешь 10% чужих налогов", 0, 0, 3);
		job("guard", "Стражник королевства", Items.IRON_SWORD, "убить враждебных мобов", 5, 90, 4,
				p -> !Jail.jailed(p), "не сидеть в тюрьме");
		job("banker", "Банкир", Items.GOLD_BLOCK, "ничего, получаешь 3% от своего баланса (до 300)", 0, 0, 5,
				p -> Money.get(p) >= 1000, "1000+ бабла на счету");
		// версия 1.4
		job("jester", "Королевский шут", Items.CARVED_PUMPKIN, "напрыгать прыжков", 25, 35, 0);
		job("cook", "Повар королевской кухни", Items.SMOKER, "приготовить еды (печь/крафт)", 8, 50, 1);
		job("builder", "Строитель", Items.BRICKS, "поставить блоков", 40, 55, 2);
		job("portier", "Портье отеля", Items.BELL, "простоять в лобби отеля секунд (+10 за каждого гостя)", 60, 45, 2);
		job("treasurer", "Казначей", Items.GOLD_INGOT, "простоять в казначействе секунд", 60, 90, 3);
		job("warden", "Тюремный надзиратель", Items.IRON_BARS, "простоять на посту в тюрьме секунд (+5 за зэка)", 60, 70, 4,
				p -> !Jail.busy(p), "не сидеть самому");
		job("bailiff", "Судебный пристав", Items.CHAIN, "ничего, +10 за каждый арест в королевстве", 0, 0, 4);
		job("spy", "Шпион короля", Items.SPYGLASS, "доносить: /donos <игрок>, +25 за донос (до 3 за смену)", 0, 0, 4);
		job("assistant", "Помощник короля", Items.GOLDEN_HELMET,
				"простоять в тронном зале секунд; раз в смену /ukaz — новый указ", 60, 250, 5,
				p -> Money.get(p) >= 3000 && !Jail.busy(p), "3000+ бабла и чистая репутация");
	}

	private Jobs() {}

	public static Job jobOf(ServerPlayerEntity p) {
		String id = PizdecState.get(p.getServer()).jobs.get(p.getUuid());
		if (id == null) return null;
		for (Job j : ALL) if (j.id.equals(id)) return j;
		return null;
	}

	private static boolean is(ServerPlayerEntity p, String id) {
		Job j = jobOf(p);
		return j != null && j.id.equals(id);
	}

	private static void add(ServerPlayerEntity p, int n) {
		Job j = jobOf(p);
		if (j == null || j.quota == 0) return;
		int before = progress.getOrDefault(p.getUuid(), 0);
		int now = before + n;
		progress.put(p.getUuid(), now);
		if (before < j.quota && now >= j.quota) {
			U.bar(p, U.t("💼 План на смену выполнен! Зарплата придёт в конце смены.", Formatting.GREEN));
			U.sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
		}
	}

	public static int progressOf(ServerPlayerEntity p) {
		return progress.getOrDefault(p.getUuid(), 0);
	}

	public static int shiftSecondsLeft() {
		return Math.max(0, (SHIFT - shiftTimer) / 20);
	}

	// ------------------------------------------------------------------ события

	public static void onBreak(ServerPlayerEntity p, BlockState state) {
		if (is(p, "janitor") && (state.isReplaceable() || state.isIn(BlockTags.SMALL_FLOWERS))) add(p, 1);
		else if (is(p, "lumberjack") && state.isIn(BlockTags.LOGS)) add(p, 1);
		else if (is(p, "farmer") && state.isIn(BlockTags.CROPS)) add(p, 1);
		else if (is(p, "miner") && (state.isIn(BlockTags.BASE_STONE_OVERWORLD)
				|| Registries.BLOCK.getId(state.getBlock()).getPath().endsWith("_ore"))) add(p, 1);
	}

	public static void onKill(ServerPlayerEntity p, LivingEntity killed) {
		if (is(p, "hunter") && (killed instanceof Monster || killed instanceof AnimalEntity)) add(p, 1);
		if (is(p, "guard") && killed instanceof Monster) add(p, 1);
	}

	/** Налоговая делится с помощниками. */
	public static void onTaxCollected(MinecraftServer s, ServerPlayerEntity payer, int amount) {
		List<ServerPlayerEntity> helpers = new ArrayList<>();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p != payer && is(p, "tax_helper")) helpers.add(p);
		}
		if (helpers.isEmpty() || amount <= 0) return;
		int each = Math.max(1, amount / 10 / helpers.size());
		for (ServerPlayerEntity h : helpers) taxShare.merge(h.getUuid(), each, Integer::sum);
	}

	public static void onPlace(ServerPlayerEntity p) {
		if (is(p, "builder")) add(p, 1);
	}

	public static void onArrest(MinecraftServer s, ServerPlayerEntity arrested) {
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p != arrested && is(p, "bailiff")) taxShare.merge(p.getUuid(), 10, Integer::sum);
		}
	}

	public static void onHotelGuest(MinecraftServer s) {
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (is(p, "portier")) taxShare.merge(p.getUuid(), 10, Integer::sum);
		}
	}

	public static void onJump(ServerPlayerEntity p) {
		if (is(p, "jester")) add(p, 1);
	}

	/** Донос шпиона. */
	public static void donos(ServerPlayerEntity p, ServerPlayerEntity target) {
		if (!is(p, "spy")) {
			U.msg(p, U.t("🕵 Доносы принимаются только от штатных шпионов короля. Устройся: /rabota", Formatting.RED));
			return;
		}
		if (target == p) {
			U.msg(p, U.t("🕵 Донос на самого себя? Похвально, но нет.", Formatting.GRAY));
			return;
		}
		int now = p.getServer().getTicks();
		Integer last = lastDonos.get(p.getUuid());
		if (last != null && now - last < 20 * 60) {
			U.msg(p, U.t("🕵 Следующий донос через " + (20 * 60 - (now - last)) / 20 + " сек. Не части.", Formatting.GRAY));
			return;
		}
		if (Jail.busy(target)) {
			U.msg(p, U.t("🕵 Этот и так сидит или воюет.", Formatting.GRAY));
			return;
		}
		lastDonos.put(p.getUuid(), now);
		int c = donosCount.merge(p.getUuid(), 1, Integer::sum);
		if (c <= 3) taxShare.merge(p.getUuid(), 25, Integer::sum);
		U.msg(p, U.t("🕵 Донос принят. Гражданин " + target.getName().getString() + " будет проверен.", Formatting.DARK_GRAY));
		Jail.arrest(target, "по доносу (анонимному, разумеется)", 20);
	}

	/** Помощник короля издаёт внеочередной указ. */
	public static void ukaz(ServerPlayerEntity p) {
		if (!is(p, "assistant")) {
			U.msg(p, U.t("📜 Указы издаёт король и его помощник. Ты — ни то, ни другое.", Formatting.RED));
			return;
		}
		if (!ukazUsed.add(p.getUuid())) {
			U.msg(p, U.t("📜 В эту смену ты уже издавал указ. Король не любит конкурентов.", Formatting.GRAY));
			return;
		}
		U.say(p.getServer(), U.t("📜 Помощник короля " + p.getName().getString() + " издаёт внеочередной указ!", Formatting.GOLD));
		PizdecMod.forceLaw();
	}

	// ------------------------------------------------------------------ смены

	public static void tick(MinecraftServer s) {
		shiftTimer++;
		if (shiftTimer % 20 == 0) {
			for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
				Job j = jobOf(p);
				if (j == null) continue;
				if (j.id.equals("fisher")) {
					int f = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.FISH_CAUGHT));
					Integer was = fishSeen.put(p.getUuid(), f);
					if (was != null && f > was) add(p, f - was);
				}
				if (j.id.equals("cook")) {
					int sum = 0;
					for (net.minecraft.item.Item f : FOODS) sum += p.getStatHandler().getStat(Stats.CRAFTED.getOrCreateStat(f));
					Integer was = cookSeen.put(p.getUuid(), sum);
					if (was != null && sum > was) add(p, sum - was);
				}
				if (!Jail.busy(p) && p.getServerWorld() == s.getOverworld()) {
					PizdecState st = PizdecState.get(s);
					Vec3d pos = p.getPos();
					if (j.id.equals("portier") && Castle.inHotelLobby(st, pos)) add(p, 1);
					if (j.id.equals("treasurer") && Castle.inTreasury(st, pos)) add(p, 1);
					if (j.id.equals("assistant") && Castle.inThroneHall(st, pos)) add(p, 1);
					if (j.id.equals("warden") && Prison.inside(st, pos)) add(p, 1);
				}
				if (j.id.equals("courier")) {
					Vec3d now = p.getPos();
					Vec3d was = walkPos.put(p.getUuid(), now);
					if (was != null && !p.hasVehicle()) {
						double d = Math.sqrt(Math.pow(now.x - was.x, 2) + Math.pow(now.z - was.z, 2));
						if (d < 12) add(p, (int) Math.round(d)); // телепорты не считаются
					}
				}
			}
		}
		if (shiftTimer >= SHIFT) {
			shiftTimer = 0;
			payday(s);
		}
	}

	private static void payday(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			Job j = jobOf(p);
			if (j == null) continue;
			int salary;
			boolean done;
			if (j.id.equals("tax_helper") || j.id.equals("bailiff") || j.id.equals("spy")) {
				salary = taxShare.getOrDefault(p.getUuid(), 0);
				done = true;
			} else if (j.id.equals("banker")) {
				salary = Math.min(300, Math.max(0, Money.get(p)) * 3 / 100);
				done = true;
			} else {
				salary = j.salary + taxShare.getOrDefault(p.getUuid(), 0);
				if (j.id.equals("warden")) salary += 5 * Jail.inmates(s);
				done = progressOf(p) >= j.quota;
			}
			if (Jail.busy(p)) done = false;
			if (Laws.is("salary_freeze")) salary = 0;
			if (Laws.is("double_salary")) salary *= 2;

			if (done) {
				st.jobMisses.put(p.getUuid(), 0);
				int ndfl = salary * 13 / 100;
				if (salary > 0) {
					Money.add(p, salary - ndfl);
					U.msg(p, U.t("💼 Зарплата (" + j.name + "): " + salary + " − 13% НДФЛ = " + (salary - ndfl) + " бабла.",
							Formatting.GREEN));
				} else {
					U.msg(p, U.t("💼 Смена закрыта. Зарплаты нет: " + (Laws.is("salary_freeze") ? "король заморозил зарплаты"
							: "нечего было начислять") + ".", Formatting.GRAY));
				}
			} else {
				int miss = st.jobMisses.getOrDefault(p.getUuid(), 0) + 1;
				st.jobMisses.put(p.getUuid(), miss);
				if (miss >= 2) {
					st.jobs.remove(p.getUuid());
					st.jobMisses.remove(p.getUuid());
					U.msg(p, U.t("💼 Ты уволен с работы «" + j.name + "» за два прогула подряд. Запись в трудовой испорчена.",
							Formatting.RED));
				} else {
					U.msg(p, U.t("💼 План не выполнен (" + progressOf(p) + "/" + j.quota + "). Прогул! Ещё один — уволят.",
							Formatting.RED));
				}
			}
			st.markDirty();
		}
		progress.clear();
		taxShare.clear();
		donosCount.clear();
		ukazUsed.clear();
	}

	// ------------------------------------------------------------------ найм

	public static void apply(ServerPlayerEntity p, Job j) {
		PizdecState st = PizdecState.get(p.getServer());
		if (Jail.busy(p)) {
			U.msg(p, U.t("💼 Отдел кадров: «Из тюрьмы и с войны на работу не берём».", Formatting.RED));
			return;
		}
		if (!Docs.require(p, "трудоустройства")) return;
		if (!Ranks.atLeast(p, j.minRank)) {
			U.msg(p, U.t("💼 Отдел кадров: «" + j.name + "? Нужно звание не ниже «" + Ranks.name(j.minRank)
					+ "». Иди купи.»", Formatting.RED));
			return;
		}
		if (!j.extra.test(p)) {
			U.msg(p, U.t("💼 Отдел кадров: «Требование: " + j.extraText + ". Приходи, когда дорастёшь.»", Formatting.RED));
			return;
		}
		if (U.chance(0.1)) {
			U.msg(p, U.t("💼 Отдел кадров: «Вы нам не подходите». Почему? Не скажем. Попробуй ещё раз.", Formatting.RED));
			return;
		}
		st.jobs.put(p.getUuid(), j.id);
		st.jobMisses.put(p.getUuid(), 0);
		st.markDirty();
		progress.remove(p.getUuid());
		U.title(p, U.t("💼 ПРИНЯТ", Formatting.GREEN, Formatting.BOLD), U.t(j.name, Formatting.YELLOW));
		U.msg(p, U.t("💼 Ты принят на работу: " + j.name + ". План на смену (2 мин): " + describe(j) + ".", Formatting.GREEN));
	}

	public static void quit(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		Job j = jobOf(p);
		if (j == null) return;
		st.jobs.remove(p.getUuid());
		st.jobMisses.remove(p.getUuid());
		st.markDirty();
		U.msg(p, U.t("💼 Ты уволился с работы «" + j.name + "». Свобода! Но бабла не будет.", Formatting.YELLOW));
	}

	public static String describe(Job j) {
		return j.quota > 0 ? j.task + " ×" + j.quota + ", зарплата " + j.salary : j.task;
	}

	// ------------------------------------------------------------------ биржа труда

	public static final class JobsGui extends Gui {
		private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

		private JobsGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "💼 Биржа труда королевства", JobsGui::new);
		}

		private void redraw() {
			fill(Items.BROWN_STAINED_GLASS_PANE);
			Job cur = jobOf(player);
			set(4, icon(cur == null ? Items.BARRIER : cur.icon, cur == null ? "§7Ты безработный" : "§aТвоя работа: " + cur.name,
					cur == null ? "§7Выбери профессию ниже" : "§7План: " + describe(cur),
					cur == null || cur.quota == 0 ? "§7" : "§7Сделано: " + progressOf(player) + "/" + cur.quota,
					"§7До конца смены: " + shiftSecondsLeft() + " сек", "§7Твоё звание: " + Ranks.name(player)));
			for (int i = 0; i < ALL.size() && i < SLOTS.length; i++) {
				Job j = ALL.get(i);
				boolean rankOk = Ranks.atLeast(player, j.minRank);
				String req = "§7Звание: " + (j.minRank == 0 ? "любое" : Ranks.name(j.minRank) + "+") + (rankOk ? " §a✔" : " §c✖");
				String extra = j.extraText == null ? "§7" : "§7Ещё: " + j.extraText + (j.extra.test(player) ? " §a✔" : " §c✖");
				set(SLOTS[i], icon(j.icon, (cur == j ? "§a▶ " : "§f") + j.name, "§7План: " + describe(j), req, extra,
						cur == j ? "§aТы здесь работаешь" : "§eЛКМ — устроиться"));
			}
			set(49, icon(Items.REDSTONE, "§cУволиться", "§7Без выходного пособия"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			if (slot == 49) {
				quit(player);
				redraw();
				return;
			}
			for (int i = 0; i < SLOTS.length && i < ALL.size(); i++) {
				if (SLOTS[i] == slot) {
					if (jobOf(player) != ALL.get(i)) apply(player, ALL.get(i));
					redraw();
					return;
				}
			}
		}

		@Override
		protected void tick() {
			if (age % 20 == 0) redraw();
		}
	}
}
