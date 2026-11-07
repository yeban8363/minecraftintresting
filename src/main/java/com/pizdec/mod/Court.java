package com.pizdec.mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * Королевский суд. Стоит рядом с тюрьмой. Карета привозит обвиняемого сюда, судья Фемида Петровна
 * слушает дело (12 секунд) и выносит приговор. Адвокат и взятка помогают. Иногда.
 */
public final class Court {
	public static final int W = 29, D = 21;
	public static final String JURY_TAG = "pizdec_jury";
	public static final String JUDGE_TAG = "pizdec_judge";
	public static final String PROSECUTOR_TAG = "pizdec_prosecutor";
	public static final String LAWYER_TAG = "pizdec_lawyer";
	public static final int TRIAL = 20 * 12;

	public static final String[] LAWYERS = {"Государственный адвокат (спит)", "Адвокат по объявлению Гена",
			"Адвокат Борис Хитрых", "Адвокат Королевской коллегии Эдуард Невиновный"};
	public static final int[] LAWYER_PRICE = {0, 30, 150, 500};

	private static final class Trial {
		final UUID player;
		int t;
		int lawyer;
		int bribe = -1;
		VillagerEntity lawyerNpc;

		Trial(UUID player) {
			this.player = player;
		}
	}

	private static final Map<UUID, Trial> trials = new HashMap<>();
	private static boolean spawningLawyer;

	private Court() {}

	// ------------------------------------------------------------------ здание

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.courtBuilt || !st.prisonBuilt) return;
		ServerWorld world = s.getOverworld();
		B.w = world;
		int ox = st.cx - 52, oz = st.cz + 30;
		B.loadChunks(ox - 6, oz - 6, ox + W + 10, oz + D + 6);
		int oy = B.ground(ox + W / 2, oz + D / 2);
		build(ox, oy, oz);
		st.courtBuilt = true;
		st.kx = ox;
		st.ky = oy;
		st.kz = oz;
		st.markDirty();
		Castle.npc(world, ox + 2.5, oy + 1, oz + 10.5, 270f, JUDGE_TAG,
				U.t("⚖ Судья Фемида Петровна", Formatting.DARK_PURPLE, Formatting.BOLD), VillagerProfession.CLERIC);
		Castle.npc(world, ox + 10.5, oy, oz + 7.5, 90f, PROSECUTOR_TAG, U.t("Прокурор Злобин", Formatting.DARK_RED),
				VillagerProfession.LIBRARIAN);
		VillagerProfession[] jp = {VillagerProfession.FARMER, VillagerProfession.FISHERMAN, VillagerProfession.BUTCHER,
				VillagerProfession.MASON, VillagerProfession.SHEPHERD, VillagerProfession.NITWIT};
		String[] jn = {"Присяжный дед Митяй", "Присяжная тётя Люба", "Присяжный мясник Гриша", "Присяжный каменщик Вова",
				"Присяжная пастушка Оля", "Присяжный (спит)"};
		for (int i = 0; i < 6; i++) {
			Castle.npc(world, ox + 16.5 + i, oy, oz + 2.5, 0f, JURY_TAG, U.t(jn[i], Formatting.GRAY), jp[i]);
		}
		PizdecMod.LOG.info("Суд v2 построен на {} {} {}", ox, oy, oz);
	}

	private static void build(int ox, int oy, int oz) {
		B.site(ox - 2, oz - 2, ox + W + 6, oz + D + 1, oy, 14, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS);
		// стены, крыша, карниз
		B.fill(ox, oy - 1, oz, ox + W - 1, oy - 1, oz + D - 1, Blocks.DARK_OAK_PLANKS);
		B.walls(ox, oy, oz, ox + W - 1, oy + 8, oz + D - 1, Blocks.SMOOTH_STONE);
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				boolean edge = x == 0 || x == W - 1 || z == 0 || z == D - 1;
				boolean lamp = !edge && x % 4 == 2 && z % 4 == 2;
				B.set(ox + x, oy + 9, oz + z, edge ? Blocks.QUARTZ_BRICKS : lamp ? Blocks.GLOWSTONE : Blocks.DARK_OAK_PLANKS);
			}
		}
		for (int x = 0; x < W; x += 4) {
			B.fill(ox + x, oy, oz, ox + x, oy + 8, oz, Blocks.QUARTZ_PILLAR);
			B.fill(ox + x, oy, oz + D - 1, ox + x, oy + 8, oz + D - 1, Blocks.QUARTZ_PILLAR);
		}
		for (int x = 2; x < W - 1; x += 4) {
			B.fill(ox + x, oy + 3, oz, ox + x, oy + 6, oz, Blocks.GLASS);
			B.fill(ox + x, oy + 3, oz + D - 1, ox + x, oy + 6, oz + D - 1, Blocks.GLASS);
		}
		// вход с востока и портик с колоннами
		B.fill(ox + W - 1, oy, oz + 9, ox + W - 1, oy + 3, oz + 11, Blocks.AIR);
		B.fill(ox + W, oy - 1, oz + 4, ox + W + 3, oy - 1, oz + 16, Blocks.QUARTZ_BLOCK);
		for (int z : new int[] {5, 8, 12, 15}) B.fill(ox + W + 2, oy, oz + z, ox + W + 2, oy + 7, oz + z, Blocks.QUARTZ_PILLAR);
		B.fill(ox + W, oy + 8, oz + 4, ox + W + 3, oy + 8, oz + 16, Blocks.QUARTZ_SLAB);
		B.fill(ox + W - 1, oy + 5, oz + 9, ox + W - 1, oy + 6, oz + 11, Blocks.GOLD_BLOCK);
		B.set(ox + W - 1, oy + 7, oz + 10, Blocks.GOLD_BLOCK);
		B.fill(ox + W + 4, oy - 1, oz + 9, ox + W + 6, oy - 1, oz + 11, Blocks.GRAVEL);

		// ковровая дорожка
		B.fill(ox + 5, oy, oz + 9, ox + W - 2, oy, oz + 11, Blocks.RED_CARPET);
		// возвышение судьи и стол
		B.fill(ox + 1, oy, oz + 6, ox + 4, oy, oz + 14, Blocks.DARK_OAK_PLANKS);
		B.fill(ox + 4, oy + 1, oz + 7, ox + 4, oy + 1, oz + 13, Blocks.DARK_OAK_PLANKS);
		B.fill(ox + 4, oy + 2, oz + 7, ox + 4, oy + 2, oz + 13, Blocks.DARK_OAK_SLAB);
		B.set(ox + 3, oy + 2, oz + 8, Blocks.LANTERN);
		B.set(ox + 3, oy + 2, oz + 12, Blocks.LANTERN);
		// герб за спиной судьи
		B.fill(ox, oy + 3, oz + 8, ox, oy + 7, oz + 12, Blocks.RED_WOOL);
		B.fill(ox, oy + 4, oz + 9, ox, oy + 6, oz + 11, Blocks.GOLD_BLOCK);
		B.set(ox, oy + 5, oz + 10, Blocks.DIAMOND_BLOCK);
		// стол секретаря суда
		B.fill(ox + 6, oy, oz + 4, ox + 6, oy, oz + 5, Blocks.SPRUCE_PLANKS);
		B.set(ox + 6, oy + 1, oz + 4, Blocks.LECTERN);
		// клетка подсудимого
		B.fill(ox + 9, oy, oz + 1, ox + 9, oy + 3, oz + 5, Blocks.IRON_BARS);
		B.fill(ox + 13, oy, oz + 1, ox + 13, oy + 3, oz + 5, Blocks.IRON_BARS);
		B.fill(ox + 9, oy, oz + 5, ox + 13, oy + 3, oz + 5, Blocks.IRON_BARS);
		B.fill(ox + 9, oy + 4, oz + 1, ox + 13, oy + 4, oz + 5, Blocks.IRON_BARS);
		B.fill(ox + 10, oy - 1, oz + 1, ox + 12, oy - 1, oz + 4, Blocks.POLISHED_DEEPSLATE);
		// столы обвинения и защиты
		B.fill(ox + 8, oy, oz + 8, ox + 12, oy, oz + 8, Blocks.SPRUCE_PLANKS);
		B.fill(ox + 8, oy, oz + 12, ox + 12, oy, oz + 12, Blocks.SPRUCE_PLANKS);
		// свидетельская трибуна
		B.fill(ox + 5, oy, oz + 16, ox + 7, oy, oz + 18, Blocks.DARK_OAK_PLANKS);
		B.set(ox + 6, oy + 1, oz + 17, Blocks.LECTERN);
		// ложа присяжных
		B.fill(ox + 15, oy - 1, oz + 1, ox + 22, oy - 1, oz + 4, Blocks.SPRUCE_PLANKS);
		B.fill(ox + 15, oy, oz + 4, ox + 22, oy, oz + 4, Blocks.SPRUCE_FENCE);
		// скамьи публики
		for (int x = 16; x <= 26; x += 2) {
			for (int z = 7; z <= 18; z++) {
				if (z >= 9 && z <= 11) continue;
				B.set(ox + x, oy, oz + z, B.stairs(Blocks.SPRUCE_STAIRS, Direction.EAST));
			}
		}
		// люстры
		for (int x = 8; x <= 24; x += 8) B.chandelier(ox + x, oy + 9, oz + 10, 2);
		// архив
		B.fill(ox + 1, oy, oz + 18, ox + 4, oy + 3, oz + 19, Blocks.BOOKSHELF);
		B.fill(ox + 1, oy, oz + 1, ox + 4, oy + 3, oz + 2, Blocks.BOOKSHELF);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.courtBuilt && p.getX() >= st.kx - 2 && p.getX() <= st.kx + W + 6 && p.getZ() >= st.kz - 2
				&& p.getZ() <= st.kz + D + 1 && p.getY() >= st.ky - 6 && p.getY() <= st.ky + 12;
	}

	public static Vec3d dock(PizdecState st) {
		return new Vec3d(st.kx + 11.5, st.ky, st.kz + 3.0);
	}

	public static Vec3d gate(PizdecState st) {
		return new Vec3d(st.kx + W + 5.5, st.ky, st.kz + 10.5);
	}

	// ------------------------------------------------------------------ процесс

	public static boolean inTrial(ServerPlayerEntity p) {
		return trials.containsKey(p.getUuid());
	}

	/** Начинает суд над игроком (он уже в записи тюрьмы). */
	public static void begin(ServerPlayerEntity p, Jail.Record rec) {
		PizdecState st = PizdecState.get(p.getServer());
		rec.transporting = false;
		rec.trial = true;
		if (p.hasVehicle()) p.stopRiding();
		Vec3d d = dock(st);
		p.teleport(p.getServer().getOverworld(), d.x, d.y, d.z, 90f, 0f);
		Trial tr = new Trial(p.getUuid());
		// договор с адвокатом из магазина применяется сам — берём лучший
		PlayerInventory inv = p.getInventory();
		for (int tier = 3; tier >= 1 && tr.lawyer == 0; tier--) {
			for (int i = 0; i < inv.size(); i++) {
				if (Gui.isSpecial(inv.getStack(i), "lawyer" + tier)) {
					inv.getStack(i).decrement(1);
					tr.lawyer = tier;
					break;
				}
			}
		}
		if (Laws.is("free_lawyer") && tr.lawyer < 2) tr.lawyer = 2;
		trials.put(p.getUuid(), tr);
		if (tr.lawyer > 0) spawnLawyer(p, st, tr);
		U.title(p, U.t("⚖ ВСТАТЬ, СУД ИДЁТ!", Formatting.DARK_PURPLE, Formatting.BOLD), U.t(rec.crime, Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_ANVIL_LAND, 0.7f, 1.4f);
		say(p, "⚖ Судья Фемида Петровна: «Слушается дело гражданина " + p.getName().getString() + ". Обвинение: " + rec.crime + ".»");
		if (tr.lawyer == 0) {
			U.msg(p, U.t("   Адвоката нет. Нанять сейчас: /advokat 1 (30), /advokat 2 (150), /advokat 3 (500). "
					+ "Или рискнуть: /vzyatka <сумма>", Formatting.GRAY));
		}
	}

	private static void say(ServerPlayerEntity p, String text) {
		U.msg(p, U.t(text, Formatting.LIGHT_PURPLE));
	}

	private static void spawnLawyer(ServerPlayerEntity p, PizdecState st, Trial tr) {
		spawningLawyer = true;
		try {
			tr.lawyerNpc = Castle.npc(p.getServer().getOverworld(), st.kx + 10.5, st.ky, st.kz + 13.5, 90f, LAWYER_TAG,
					U.t("💼 " + LAWYERS[tr.lawyer], Formatting.AQUA), VillagerProfession.CARTOGRAPHER);
		} finally {
			spawningLawyer = false;
		}
	}

	public static void hireLawyer(ServerPlayerEntity p, int tier) {
		Trial tr = trials.get(p.getUuid());
		if (tr == null) {
			U.msg(p, U.t("Адвокат нужен только в суде. Можно купить договор заранее в магазине.", Formatting.GRAY));
			return;
		}
		if (tr.t > TRIAL - 60) {
			U.msg(p, U.t("Поздно, судья уже пишет приговор.", Formatting.RED));
			return;
		}
		if (tier <= tr.lawyer) {
			U.msg(p, U.t("У тебя уже есть адвокат не хуже.", Formatting.GRAY));
			return;
		}
		if (!Money.pay(p, LAWYER_PRICE[tier], "услуги адвоката")) return;
		if (tr.lawyerNpc != null) tr.lawyerNpc.discard();
		tr.lawyer = tier;
		spawnLawyer(p, PizdecState.get(p.getServer()), tr);
		say(p, "💼 " + LAWYERS[tier] + ": «Спокойно, я всё решу. Наверное.»");
	}

	public static void bribe(ServerPlayerEntity p, int amount) {
		Trial tr = trials.get(p.getUuid());
		if (tr == null) {
			U.msg(p, U.t("Взятку дают в суде. Тут её никто не возьмёт (при свидетелях).", Formatting.GRAY));
			return;
		}
		if (tr.bribe >= 0) {
			U.msg(p, U.t("Ты уже предлагал. Судья помнит.", Formatting.GRAY));
			return;
		}
		if (tr.t > TRIAL - 40) {
			U.msg(p, U.t("Поздно.", Formatting.RED));
			return;
		}
		if (!Money.pay(p, amount, "«подарок» судье")) return;
		tr.bribe = amount;
		say(p, "⚖ Судья Фемида Петровна многозначительно кашляет.");
	}

	public static void tick(MinecraftServer s) {
		if (trials.isEmpty()) return;
		PizdecState st = PizdecState.get(s);
		Iterator<Trial> it = trials.values().iterator();
		while (it.hasNext()) {
			Trial tr = it.next();
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(tr.player);
			Jail.Record rec = st.jail.get(tr.player);
			if (p == null || rec == null) {
				if (tr.lawyerNpc != null) tr.lawyerNpc.discard();
				if (rec != null) rec.trial = false;
				it.remove();
				continue;
			}
			tr.t++;
			if (tr.t % 10 == 0) keepInDock(p, st);
			if (tr.t % 20 == 0) {
				U.bar(p, U.t("⚖ Суд идёт… до приговора " + (TRIAL - tr.t) / 20 + " сек  |  /advokat  /vzyatka", Formatting.LIGHT_PURPLE));
			}
			if (tr.t == 60) {
				say(p, "⚖ Прокурор Злобин: «" + U.pick("Требую " + rec.ticks / 20 + " секунд! И ещё столько же, для порядка.",
						"Посмотрите на это лицо. Виновен.", "Я требую " + rec.ticks / 20 + " секунд. Мне за это премию дают.",
						"Ваша честь, у него даже прописки нет! Наверное.") + "»");
			}
			if (tr.t == 130) {
				if (tr.lawyer == 0) say(p, "💼 Государственный адвокат: «Хр-р-р…» (спит)");
				else say(p, "💼 " + LAWYERS[tr.lawyer] + ": «" + U.pick("Мой подзащитный невиновен, он просто так выглядит.",
						"Ваша честь, это был не он, это был крипер.", "Протестую! На каком основании? На любом!",
						"У моего клиента было тяжёлое детство в пещере.", "Ваша честь, посмотрите какие глазки!") + "»");
			}
			if (tr.t >= TRIAL) {
				it.remove();
				if (tr.lawyerNpc != null) tr.lawyerNpc.discard();
				verdict(p, rec, tr);
			}
		}
	}

	private static void keepInDock(ServerPlayerEntity p, PizdecState st) {
		Vec3d d = dock(st);
		if (p.getServerWorld() != p.getServer().getOverworld() || Math.abs(p.getX() - d.x) > 1.6 || Math.abs(p.getZ() - d.z) > 1.6
				|| Math.abs(p.getY() - d.y) > 2) {
			if (p.hasVehicle()) p.stopRiding();
			p.teleport(p.getServer().getOverworld(), d.x, d.y, d.z, 90f, 0f);
		}
	}

	private static void verdict(ServerPlayerEntity p, Jail.Record rec, Trial tr) {
		rec.trial = false;
		// взятка
		if (tr.bribe > 0) {
			double c = (double) tr.bribe / (tr.bribe + 300);
			if (U.R.nextDouble() < c) {
				say(p, "⚖ Судья Фемида Петровна: «За отсутствием состава преступления… и при наличии состава подарка — оправдан.»");
				Jail.release(p, "оправдан судом (подозрительно быстро)");
				return;
			}
			say(p, "⚖ Судья Фемида Петровна: «Взятка? МНЕ?! Это ещё +30 секунд. Деньги, конечно, не верну.»");
			rec.ticks += 30 * 20;
			rec.crime = rec.crime + " + дача взятки";
		}
		int[][] base = {{8, 12, 50, 15, 15}, {18, 25, 40, 10, 7}, {40, 30, 22, 5, 3}, {65, 25, 8, 2, 0}};
		int[] wgt = base[tr.lawyer].clone();
		int r = Ranks.get(p);
		wgt[0] += r * 3;
		wgt[3] = Math.max(0, wgt[3] - r);
		wgt[4] = Math.max(0, wgt[4] - r);
		if (Laws.is("harsh_court")) {
			wgt[0] = 0;
			wgt[3] += 20;
		}
		int sum = 0;
		for (int x : wgt) sum += x;
		int roll = U.R.nextInt(Math.max(1, sum));
		int outcome = 0;
		for (int i = 0; i < wgt.length; i++) {
			roll -= wgt[i];
			if (roll < 0) {
				outcome = i;
				break;
			}
		}
		String name = p.getName().getString();
		switch (outcome) {
			case 0 -> {
				say(p, "⚖ Приговор: ОПРАВДАН. «" + U.pick("Свободен. Пока что.", "Суд не нашёл ничего. Искал плохо.",
						"Адвокат был убедителен. Глазки сработали.") + "»");
				U.say(p.getServer(), U.t("⚖ Суд оправдал " + name + ". Король недоволен.", Formatting.GREEN));
				Jail.release(p, "оправдан судом");
			}
			case 1 -> {
				rec.ticks = Math.max(20 * 5, rec.ticks / 2);
				say(p, "⚖ Приговор: срок СОКРАЩЁН вдвое — " + rec.ticks / 20 + " сек.");
				Jail.toCellAfterTrial(p, rec);
			}
			case 2 -> {
				say(p, "⚖ Приговор: ВИНОВЕН. " + rec.ticks / 20 + " сек, как просил прокурор.");
				Jail.toCellAfterTrial(p, rec);
			}
			case 3 -> {
				rec.ticks *= 2;
				say(p, "⚖ Приговор: ВИНОВЕН, и судье не понравилось твоё лицо. Срок УДВОЕН — " + rec.ticks / 20 + " сек.");
				Jail.toCellAfterTrial(p, rec);
			}
			default -> {
				say(p, "⚖ Приговор: ССЫЛКА НА ВОЙНУ. «Родине нужны герои. И пушечное мясо.»");
				U.say(p.getServer(), U.t("⚔ " + name + " сослан судом на войну.", Formatting.DARK_RED));
				Jail.releaseQuietly(p);
				War.exile(p, "приговор суда: " + rec.crime);
			}
		}
	}

	/** Адвокатов после перезапуска быть не должно. */
	public static void onEntityLoad(Entity e) {
		if (spawningLawyer || !e.getCommandTags().contains(LAWYER_TAG)) return;
		for (Trial tr : trials.values()) if (tr.lawyerNpc == e) return;
		e.discard();
	}

	public static void onServerStart() {
		trials.clear();
	}

	public static ItemStack lawyerContract(int tier) {
		return Gui.special(Items.WRITABLE_BOOK, "lawyer" + tier, "§bДоговор: " + LAWYERS[tier],
				"Сработает сам на ближайшем суде.", tier == 3 ? "Почти гарантия оправдания." : tier == 2 ? "Хороший шанс." : "Ну, хоть что-то.");
	}
}
