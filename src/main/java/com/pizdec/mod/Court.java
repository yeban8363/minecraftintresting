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
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * Королевский суд. Стоит рядом с тюрьмой. Карета привозит обвиняемого сюда, судья Фемида Петровна
 * слушает дело (12 секунд) и выносит приговор. Адвокат и взятка помогают. Иногда.
 */
public final class Court {
	public static final int W = 17, D = 13;
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
	private static ServerWorld w;

	private Court() {}

	// ------------------------------------------------------------------ здание

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.courtBuilt || !st.prisonBuilt) return;
		ServerWorld world = s.getOverworld();
		int ox = st.px + 2, oz = st.pz + 19;
		for (int cx = (ox - 6) >> 4; cx <= (ox + W + 8) >> 4; cx++) {
			for (int cz = (oz - 6) >> 4; cz <= (oz + D + 6) >> 4; cz++) world.getChunk(cx, cz);
		}
		int oy = Math.max(world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox + W / 2, oz + D / 2),
				world.getSeaLevel() + 1);
		build(world, ox, oy, oz);
		st.courtBuilt = true;
		st.kx = ox;
		st.ky = oy;
		st.kz = oz;
		st.markDirty();
		Castle.npc(world, ox + 1.5, oy + 1, oz + 6.5, 270f, JUDGE_TAG,
				U.t("⚖ Судья Фемида Петровна", Formatting.DARK_PURPLE, Formatting.BOLD), VillagerProfession.CLERIC);
		Castle.npc(world, ox + 6.5, oy, oz + 10.5, 0f, PROSECUTOR_TAG,
				U.t("Прокурор Злобин", Formatting.DARK_RED), VillagerProfession.LIBRARIAN);
		PizdecMod.LOG.info("Суд построен на {} {} {}", ox, oy, oz);
	}

	private static void set(int x, int y, int z, BlockState b) {
		w.setBlockState(new BlockPos(x, y, z), b, Block.NOTIFY_LISTENERS);
	}

	private static void set(int x, int y, int z, Block b) {
		set(x, y, z, b.getDefaultState());
	}

	private static void build(ServerWorld world, int ox, int oy, int oz) {
		w = world;
		BlockState air = Blocks.AIR.getDefaultState();
		for (int x = -2; x <= W + 5; x++) {
			for (int z = -2; z <= D + 1; z++) {
				for (int y = oy; y <= oy + 12; y++) {
					BlockPos p = new BlockPos(ox + x, y, oz + z);
					if (!w.getBlockState(p).isAir()) w.setBlockState(p, air, Block.NOTIFY_LISTENERS);
				}
				for (int y = oy - 1; y > oy - 40; y--) {
					BlockState b = w.getBlockState(new BlockPos(ox + x, y, oz + z));
					boolean soft = b.isAir() || !b.getFluidState().isEmpty() || b.isReplaceable() || b.isIn(BlockTags.LEAVES);
					if (y < oy - 3 && !soft) break;
					boolean in = x >= 0 && x < W && z >= 0 && z < D;
					set(ox + x, y, oz + z, y == oy - 1 ? (in ? Blocks.DARK_OAK_PLANKS : Blocks.GRAVEL) : Blocks.STONE_BRICKS);
				}
			}
		}
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				boolean edge = x == 0 || x == W - 1 || z == 0 || z == D - 1;
				boolean corner = (x == 0 || x == W - 1) && (z == 0 || z == D - 1);
				if (edge) {
					for (int y = oy; y <= oy + 6; y++) set(ox + x, y, oz + z, corner ? Blocks.QUARTZ_PILLAR : Blocks.SMOOTH_STONE);
					if ((x + z) % 2 == 0) set(ox + x, oy + 8, oz + z, Blocks.SMOOTH_STONE);
				}
				boolean lamp = !edge && x % 4 == 2 && z % 4 == 2;
				set(ox + x, oy + 7, oz + z, lamp ? Blocks.GLOWSTONE : Blocks.DARK_OAK_PLANKS);
			}
		}
		// окна
		for (int x = 3; x < W - 2; x += 3) {
			for (int y = oy + 2; y <= oy + 4; y++) {
				set(ox + x, y, oz, Blocks.GLASS);
				set(ox + x, y, oz + D - 1, Blocks.GLASS);
			}
		}
		// вход с востока
		for (int z = 5; z <= 7; z++) for (int y = oy; y <= oy + 3; y++) set(ox + W - 1, y, oz + z, air);
		set(ox + W, oy, oz + 4, Blocks.LANTERN);
		set(ox + W, oy, oz + 8, Blocks.LANTERN);
		// возвышение судьи и стол
		for (int z = 3; z <= 9; z++) {
			set(ox + 1, oy, oz + z, Blocks.DARK_OAK_PLANKS);
			set(ox + 2, oy, oz + z, Blocks.DARK_OAK_PLANKS);
			set(ox + 3, oy, oz + z, Blocks.DARK_OAK_PLANKS);
			set(ox + 3, oy + 1, oz + z, Blocks.DARK_OAK_PLANKS);
		}
		for (int y = oy + 1; y <= oy + 5; y++) set(ox + 1, y, oz + 6, y == oy + 1 ? Blocks.AIR : Blocks.RED_WOOL);
		set(ox + 1, oy + 5, oz + 5, Blocks.GOLD_BLOCK);
		set(ox + 1, oy + 5, oz + 7, Blocks.GOLD_BLOCK);
		// скамья подсудимых (клетка)
		for (int x = 6; x <= 10; x++) {
			for (int z = 1; z <= 4; z++) {
				boolean cage = x == 6 || x == 10 || z == 4;
				if (cage) for (int y = oy; y <= oy + 3; y++) set(ox + x, y, oz + z, Blocks.IRON_BARS);
			}
		}
		set(ox + 8, oy + 3, oz + 2, Blocks.LANTERN);
		// трибуна адвоката
		set(ox + 9, oy, oz + 10, Blocks.LECTERN);
		// скамьи публики
		for (int x = 12; x <= 14; x += 2) {
			for (int z = 2; z <= 10; z++) {
				if (z >= 5 && z <= 7) continue;
				set(ox + x, oy, oz + z, Blocks.SPRUCE_SLAB);
			}
		}
		// дорожка к воротам
		for (int x = W; x <= W + 5; x++) for (int z = 5; z <= 7; z++) set(ox + x, oy - 1, oz + z, Blocks.GRAVEL);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.courtBuilt && p.getX() >= st.kx - 2 && p.getX() <= st.kx + W + 5 && p.getZ() >= st.kz - 2
				&& p.getZ() <= st.kz + D + 1 && p.getY() >= st.ky - 6 && p.getY() <= st.ky + 10;
	}

	public static Vec3d dock(PizdecState st) {
		return new Vec3d(st.kx + 8.5, st.ky, st.kz + 2.5);
	}

	public static Vec3d gate(PizdecState st) {
		return new Vec3d(st.kx + W + 3.5, st.ky, st.kz + 6.5);
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
			tr.lawyerNpc = Castle.npc(p.getServer().getOverworld(), st.kx + 9.5, st.ky, st.kz + 9.5, 180f, LAWYER_TAG,
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
