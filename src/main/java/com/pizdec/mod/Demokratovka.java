package com.pizdec.mod;

import static com.pizdec.mod.B.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

/**
 * Демократовка — свободный демократический ПГТ в 200 блоках за северным КПП.
 * Нет короля, есть выборный мэр. Своя валюта (демо, Ð), своя полиция и суд, конституция,
 * бесплатная больница, библиотека, независимая газета, свободный рынок и гражданство для беженцев.
 */
public final class Demokratovka {
	public static final String NAME = "Демократовка";
	public static final int DIST = 200, HALF = 44, CYCLE = 20 * 60 * 20;
	public static final String MIGRATION_TAG = "pizdec_demo_migration", MAYOR_TAG = "pizdec_demo_mayor",
			DOCTOR_TAG = "pizdec_demo_doctor", LIBRARIAN_TAG = "pizdec_demo_librarian", EDITOR_TAG = "pizdec_demo_editor",
			SHERIFF_TAG = "pizdec_demo_sheriff", JUDGE_TAG = "pizdec_demo_judge", MARKET_TAG = "pizdec_demo_market",
			EXCHANGE_TAG = "pizdec_demo_exchange";
	public static final String[] TAGS = {MIGRATION_TAG, MAYOR_TAG, DOCTOR_TAG, LIBRARIAN_TAG, EDITOR_TAG, SHERIFF_TAG, JUDGE_TAG,
			MARKET_TAG, EXCHANGE_TAG};
	/** Кандидаты-НПЦ: партия → кандидат. */
	private static final String[][] PARTIES = {{"Партия Здравого Смысла", "Здравомыслов Пётр Ильич"},
			{"Велосипедисты Демократовки", "Велосипедова Анна Сергеевна"}, {"Союз Пенсионеров и Котов", "Мурлыкин Семён Ефимович"}};
	private static final String[] PROMISES = {"починить лужу на площади (в этот раз точно)", "провести велодорожку до КПП",
			"снизить налог на рынке", "открыть кошачью библиотеку", "сделать больницу ещё бесплатнее", "построить второй фонтан",
			"разрешить петь гимн, но только по желанию", "провести общественные слушания о слушаниях"};

	private static final Map<UUID, Integer> warnings = new HashMap<>();
	private static final Map<UUID, Integer> healed = new HashMap<>();
	private static final Map<UUID, Integer> lastFine = new HashMap<>();
	private static final Map<UUID, Integer> lastArticle = new HashMap<>();
	private static final Map<UUID, Integer> confirmAsylum = new HashMap<>();

	private Demokratovka() {}

	// ================================================================== валюта: демо (Ð)

	public static final class Demo {
		public static final String OBJ = "pizdec_demo";

		private static ScoreboardObjective obj(MinecraftServer s) {
			ServerScoreboard sb = s.getScoreboard();
			ScoreboardObjective o = sb.getNullableObjective(OBJ);
			if (o == null) {
				o = sb.addObjective(OBJ, ScoreboardCriterion.DUMMY, U.t("Ð Демо", Formatting.GREEN),
						ScoreboardCriterion.RenderType.INTEGER, true, null);
			}
			return o;
		}

		public static int get(ServerPlayerEntity p) {
			return p.getServer().getScoreboard().getOrCreateScore(p, obj(p.getServer())).getScore();
		}

		public static void add(ServerPlayerEntity p, int n) {
			p.getServer().getScoreboard().getOrCreateScore(p, obj(p.getServer())).incrementScore(n);
		}

		public static boolean pay(ServerPlayerEntity p, int n, String what) {
			if (p.isCreative()) return true;
			if (get(p) >= n) {
				add(p, -n);
				U.bar(p, U.t("−" + n + " Ð за " + what, Formatting.RED));
				return true;
			}
			U.bar(p, U.t("Не хватает демо: нужно " + n + " Ð (у тебя " + get(p) + "). Обменник — на рынке.", Formatting.RED));
			return false;
		}
	}

	// ================================================================== где

	public static boolean inside(PizdecState st, double x, double z) {
		return st.demoBuilt && Math.abs(x - st.dx - 0.5) <= HALF && Math.abs(z - st.dz - 0.5) <= HALF;
	}

	public static boolean inside(PizdecState st, Vec3d p) {
		return inside(st, p.x, p.z);
	}

	public static boolean inTown(ServerPlayerEntity p) {
		return p.getServerWorld() == p.getServer().getOverworld() && inside(PizdecState.get(p.getServer()), p.getPos());
	}

	public static boolean citizen(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).demoCitizens.contains(p.getUuid());
	}

	public static Vec3d hospital(PizdecState st) {
		return new Vec3d(st.dx + 18.5, st.dy, st.dz + 0.5);
	}

	public static Vec3d entrance(PizdecState st) {
		return new Vec3d(st.dx + 0.5, st.dy, st.dz + 40.5);
	}

	// ================================================================== стройка

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.demoBuilt || !st.borderBuilt) return;
		ServerWorld world = s.getOverworld();
		w = world;
		int ox = Border.bx(st), oz = Border.bz(st) - Border.R - DIST;
		loadChunks(ox - HALF - 4, oz - HALF - 4, ox + HALF + 4, oz + HALF + 4);
		int oy = ground(ox, oz);
		st.dx = ox;
		st.dy = oy;
		st.dz = oz;
		build(ox, oy, oz, Border.bz(st) - Border.R - 15);
		st.demoBuilt = true;
		st.markDirty();
		spawnNpcs(world, st);
		PizdecMod.LOG.info("Демократовка построена на {} {} {}", ox, oy, oz);
	}

	private static void house(int x0, int y, int z0, int x1, int z1, int h, Block wall, Block accent, Block roof) {
		clear(x0, y, z0, x1, y + h + 2, z1);
		fill(x0, y - 1, z0, x1, y - 1, z1, Blocks.SPRUCE_PLANKS);
		walls(x0, y, z0, x1, y + h, z1, wall);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				if (edge) {
					set(x, y, z, accent);
					boolean win = (x == x0 || x == x1) ? (z - z0) % 3 == 1 && z != z0 && z != z1 : (x - x0) % 3 == 1 && x != x0 && x != x1;
					if (win) {
						set(x, y + 2, z, Blocks.GLASS_PANE);
						set(x, y + 3, z, Blocks.GLASS_PANE);
					}
				}
			}
		}
		fill(x0 - 1, y + h + 1, z0 - 1, x1 + 1, y + h + 1, z1 + 1, roof);
		for (int x = x0 + 2; x < x1; x += 4) for (int z = z0 + 2; z < z1; z += 4) set(x, y + h, z, Blocks.GLOWSTONE);
	}

	private static void flagpole(int x, int y, int z, int h) {
		for (int i = 0; i < h; i++) set(x, y + i, z, Blocks.BIRCH_FENCE);
		for (int i = 1; i <= 3; i++) {
			set(x + i, y + h - 1, z, Blocks.LIME_WOOL);
			set(x + i, y + h - 2, z, Blocks.WHITE_WOOL);
			set(x + i, y + h - 3, z, Blocks.LIME_WOOL);
		}
	}

	private static void tree(int x, int y, int z) {
		for (int i = 0; i < 4; i++) set(x, y + i, z, Blocks.OAK_LOG);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 3; dy <= 5; dy++) {
					if (Math.abs(dx) + Math.abs(dz) + (dy - 3) <= 4 && !(dx == 0 && dz == 0 && dy < 4)) set(x + dx, y + dy, z + dz, B.leaves());
				}
			}
		}
	}

	private static void build(int ox, int oy, int oz, int roadEndZ) {
		site(ox - HALF, oz - HALF, ox + HALF, oz + HALF, oy, 26, Blocks.GRASS_BLOCK, Blocks.DIRT);
		// дорога от КПП
		loadChunks(ox - 4, oz + HALF, ox + 4, roadEndZ);
		for (int z = oz + HALF + 1; z <= roadEndZ; z++) {
			for (int x = ox - 2; x <= ox + 2; x++) {
				int y = ground(x, z);
				set(x, y - 1, z, Math.abs(x - ox) == 2 ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH);
			}
			if ((z - oz) % 40 == 0) lampPost(ox + 3, ground(ox + 3, z), z);
		}
		int mid = (oz + HALF + roadEndZ) / 2;
		Restaurant.standingSign(ox + 3, ground(ox + 3, mid), mid, 0, "↑ " + NAME, "свободный ПГТ", "до города", (mid - oz - HALF) + " м");
		// улица от въезда до площади
		fill(ox - 2, oy - 1, oz + 11, ox + 2, oy - 1, oz + HALF, Blocks.STONE_BRICKS);
		Restaurant.standingSign(ox - 3, oy, oz + 42, 0, "§l" + NAME.toUpperCase(), "свободный ПГТ", "Население:", "свободное");
		Restaurant.standingSign(ox + 3, oy, oz + 42, 0, "Здесь нет", "короля.", "Портреты —", "по желанию");
		flagpole(ox - 4, oy, oz + 42, 7);
		flagpole(ox + 4, oy, oz + 42, 7);

		// --- площадь Свободы
		for (int x = -10; x <= 10; x++) {
			for (int z = -10; z <= 10; z++) {
				boolean border = Math.abs(x) == 10 || Math.abs(z) == 10;
				set(ox + x, oy - 1, oz + z, border ? Blocks.POLISHED_ANDESITE : (x + z) % 2 == 0 ? Blocks.SMOOTH_STONE : Blocks.STONE_BRICKS);
			}
		}
		// фонтан (маленький — в демократии не принято меряться)
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
				set(ox + x, oy, oz + z, rim ? Blocks.STONE_BRICK_SLAB : Blocks.WATER);
			}
		}
		set(ox, oy, oz, Blocks.CHISELED_STONE_BRICKS);
		set(ox, oy + 1, oz, Blocks.FLOWER_POT);
		for (int[] c : new int[][] {{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) tree(ox + c[0], oy, oz + c[1]);
		for (int x : new int[] {-6, 6}) {
			set(ox + x, oy, oz - 5, B.stairs(Blocks.BIRCH_STAIRS, Direction.NORTH));
			set(ox + x, oy, oz + 5, B.stairs(Blocks.BIRCH_STAIRS, Direction.SOUTH));
		}
		for (int[] c : new int[][] {{-10, 0}, {10, 0}, {0, -10}}) lampPost(ox + c[0], oy, oz + c[1]);
		// трибуна для свободных высказываний
		set(ox - 5, oy, oz + 7, Blocks.BARREL);
		Restaurant.standingSign(ox - 5, oy + 1, oz + 7, 0, "Трибуна", "Говори что", "думаешь:", "/statya <текст>");
		// лужа, которую обещали починить
		set(ox + 6, oy - 1, oz + 9, Blocks.WATER);

		// --- Мэрия (север): x −9..9, z −30..−14
		house(ox - 9, oy, oz - 30, ox + 9, oz - 14, 8, Blocks.WHITE_CONCRETE, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.BIRCH_SLAB);
		fill(ox - 1, oy, oz - 14, ox + 1, oy + 2, oz - 14, Blocks.AIR);
		for (int x : new int[] {-3, 3}) fill(ox + x, oy, oz - 13, ox + x, oy + 5, oz - 13, Blocks.QUARTZ_PILLAR);
		fill(ox - 4, oy + 6, oz - 13, ox + 4, oy + 6, oz - 12, Blocks.SMOOTH_QUARTZ_SLAB);
		// часовая башня
		fill(ox - 2, oy + 9, oz - 26, ox + 2, oy + 15, oz - 22, Blocks.WHITE_CONCRETE);
		fill(ox - 1, oy + 12, oz - 27, ox + 1, oy + 14, oz - 27, Blocks.BLACK_CONCRETE);
		set(ox, oy + 13, oz - 27, Blocks.WHITE_CONCRETE);
		set(ox, oy + 14, oz - 27, Blocks.WHITE_CONCRETE);
		set(ox + 1, oy + 13, oz - 27, Blocks.WHITE_CONCRETE);
		fill(ox - 1, oy + 12, oz - 21, ox + 1, oy + 14, oz - 21, Blocks.BLACK_CONCRETE);
		set(ox, oy + 13, oz - 21, Blocks.WHITE_CONCRETE);
		set(ox, oy + 14, oz - 21, Blocks.WHITE_CONCRETE);
		set(ox + 1, oy + 13, oz - 21, Blocks.WHITE_CONCRETE);
		fill(ox - 3, oy + 16, oz - 27, ox + 3, oy + 16, oz - 21, Blocks.DARK_PRISMARINE_SLAB);
		set(ox, oy + 17, oz - 24, Blocks.BELL);
		flagpole(ox, oy + 18, oz - 24, 5);
		Restaurant.sign(ox, oy + 4, oz - 13, Direction.SOUTH, "§lМЭРИЯ", NAME, "Вход свободный", "(правда)");
		// внутри: миграционная служба, приёмная мэра, урна, конституция
		for (int x = -7; x <= -2; x++) set(ox + x, oy, oz - 20, Blocks.BIRCH_PLANKS);
		for (int x = 2; x <= 7; x++) set(ox + x, oy, oz - 24, Blocks.BIRCH_PLANKS);
		set(ox + 6, oy, oz - 17, Blocks.BARREL);
		set(ox + 6, oy + 1, oz - 17, Blocks.WHITE_CARPET);
		set(ox, oy, oz - 28, Blocks.LECTERN);
		B.chandelier(ox, oy + 8, oz - 20, 2);
		Restaurant.sign(ox - 4, oy + 2, oz - 29, Direction.SOUTH, "Миграционная", "служба", "Убежище —", "без очереди");
		Restaurant.sign(ox + 4, oy + 2, oz - 29, Direction.SOUTH, "Приёмная мэра", "и выборы", "Голосуют", "граждане");

		// --- Больница (восток): x 14..30, z −9..9
		house(ox + 14, oy, oz - 9, ox + 30, oz + 9, 6, Blocks.WHITE_CONCRETE, Blocks.WHITE_TERRACOTTA, Blocks.SMOOTH_STONE_SLAB);
		fill(ox + 14, oy, oz - 1, ox + 14, oy + 2, oz + 1, Blocks.AIR);
		// красный крест на фасаде
		fill(ox + 13, oy + 4, oz - 1, ox + 13, oy + 6, oz + 1, Blocks.WHITE_CONCRETE);
		fill(ox + 13, oy + 5, oz - 1, ox + 13, oy + 5, oz + 1, Blocks.RED_CONCRETE);
		set(ox + 13, oy + 4, oz, Blocks.RED_CONCRETE);
		set(ox + 13, oy + 6, oz, Blocks.RED_CONCRETE);
		for (int z : new int[] {-7, -4, 4, 7}) {
			B.bed(ox + 27, oy, oz + z, Direction.EAST, Blocks.WHITE_BED);
			set(ox + 25, oy, oz + z, Blocks.BREWING_STAND);
		}
		set(ox + 20, oy, oz - 1, Blocks.QUARTZ_BLOCK);
		set(ox + 20, oy, oz + 1, Blocks.QUARTZ_BLOCK);
		Restaurant.sign(ox + 13, oy + 3, oz + 3, Direction.WEST, "§lБОЛЬНИЦА", "бесплатная", "для всех", "без полиса");

		// --- Библиотека (запад): x −30..−14, z −9..9
		house(ox - 30, oy, oz - 9, ox - 14, oz + 9, 7, Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.DARK_OAK_SLAB);
		fill(ox - 14, oy, oz - 1, ox - 14, oy + 2, oz + 1, Blocks.AIR);
		for (int z = -8; z <= 8; z++) {
			if (Math.abs(z) <= 1) continue;
			for (int y = 0; y <= 3; y++) set(ox - 29, oy + y, oz + z, Blocks.BOOKSHELF);
		}
		for (int x = -27; x <= -17; x += 5) {
			for (int y = 0; y <= 2; y++) {
				set(ox + x, oy + y, oz - 8, Blocks.BOOKSHELF);
				set(ox + x, oy + y, oz + 8, Blocks.BOOKSHELF);
			}
		}
		for (int z : new int[] {-4, 4}) {
			table(ox - 22, oy, oz + z);
			set(ox - 21, oy, oz + z, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.EAST));
			set(ox - 23, oy, oz + z, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.WEST));
		}
		set(ox - 27, oy, oz, Blocks.LECTERN);
		Restaurant.sign(ox - 13, oy + 3, oz + 3, Direction.EAST, "§lБИБЛИОТЕКА", "Читай что", "хочешь.", "Бесплатно.");

		// --- Редакция «Свободного голоса» (юго-запад): x −30..−16, z 14..28
		house(ox - 30, oy, oz + 14, ox - 16, oz + 28, 6, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE, Blocks.SPRUCE_SLAB);
		fill(ox - 24, oy, oz + 14, ox - 22, oy + 2, oz + 14, Blocks.AIR);
		set(ox - 27, oy, oz + 25, Blocks.PISTON);
		set(ox - 27, oy + 1, oz + 25, Blocks.ANVIL);
		set(ox - 26, oy, oz + 25, Blocks.PISTON);
		for (int x = -20; x >= -28; x -= 4) table(ox + x, oy, oz + 20);
		Restaurant.sign(ox - 23, oy + 4, oz + 13, Direction.NORTH, "§l«СВОБОДНЫЙ", "§lГОЛОС»", "независимая", "газета");

		// --- Полиция и суд (юго-восток): x 16..30, z 14..28
		house(ox + 16, oy, oz + 14, ox + 30, oz + 28, 7, Blocks.LIGHT_GRAY_CONCRETE, Blocks.BLUE_TERRACOTTA, Blocks.STONE_BRICK_SLAB);
		fill(ox + 23, oy, oz + 15, ox + 23, oy + 7, oz + 27, Blocks.LIGHT_GRAY_CONCRETE);
		fill(ox + 18, oy, oz + 14, ox + 20, oy + 2, oz + 14, Blocks.AIR);
		fill(ox + 26, oy, oz + 14, ox + 28, oy + 2, oz + 14, Blocks.AIR);
		set(ox + 19, oy, oz + 22, Blocks.SPRUCE_PLANKS);
		set(ox + 20, oy, oz + 22, Blocks.SPRUCE_PLANKS);
		fill(ox + 25, oy, oz + 26, ox + 29, oy, oz + 26, Blocks.QUARTZ_BLOCK);
		set(ox + 27, oy, oz + 27, Blocks.QUARTZ_BLOCK);
		for (int x = 25; x <= 29; x++) set(ox + x, oy, oz + 19, B.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
		Restaurant.sign(ox + 19, oy + 3, oz + 13, Direction.NORTH, "§lПОЛИЦИЯ", "Служим людям,", "а не королю", "");
		Restaurant.sign(ox + 27, oy + 3, oz + 13, Direction.NORTH, "§lСУД", "Презумпция", "невиновности", "Присяжные");

		// --- Рынок и обменник (юг): x −9..9, z 15..32
		for (int x = -9; x <= 9; x++) for (int z = 15; z <= 32; z++) set(ox + x, oy - 1, oz + z, Blocks.COBBLESTONE);
		fill(ox - 2, oy - 1, oz + 15, ox + 2, oy - 1, oz + 32, Blocks.STONE_BRICKS);
		for (int[] st2 : new int[][] {{-8, 18}, {-8, 24}, {4, 18}, {4, 24}}) {
			int sx = ox + st2[0], sz = oz + st2[1];
			for (int[] c : new int[][] {{0, 0}, {4, 0}, {0, 3}, {4, 3}}) fill(sx + c[0], oy, sz + c[1], sx + c[0], oy + 2, sz + c[1], Blocks.OAK_FENCE);
			for (int x = 0; x <= 4; x++) {
				for (int z = 0; z <= 3; z++) set(sx + x, oy + 3, sz + z, (x + z) % 2 == 0 ? Blocks.LIME_WOOL : Blocks.WHITE_WOOL);
				set(sx + x, oy, sz, Blocks.BARREL);
			}
		}
		Restaurant.standingSign(ox - 3, oy, oz + 15, 8, "§lСВОБОДНЫЙ", "§lРЫНОК", "Дефицита нет.", "Совсем.");
		Restaurant.standingSign(ox + 3, oy, oz + 15, 8, "Обмен валют", "бабло ⇄ Ð", "Курс —", "рыночный");
		for (int[] c : new int[][] {{-12, -12}, {12, -12}}) flagpole(ox + c[0], oy, oz + c[1], 8);
	}

	private static void spawnNpcs(ServerWorld world, PizdecState st) {
		int ox = st.dx, oy = st.dy, oz = st.dz;
		Castle.npc(world, ox - 4.5, oy, oz - 21.5, 0f, MIGRATION_TAG, U.t("🛂 Миграционная служба: Убежищева Ольга", Formatting.GREEN),
				VillagerProfession.LIBRARIAN);
		Castle.npc(world, ox + 4.5, oy, oz - 25.5, 0f, MAYOR_TAG, U.t("🏛 Приёмная мэра и избирком", Formatting.GREEN),
				VillagerProfession.CARTOGRAPHER);
		Castle.npc(world, ox + 20.5, oy, oz + 0.5, 90f, DOCTOR_TAG, U.t("⚕ Доктор Добрякова (бесплатно)", Formatting.WHITE),
				VillagerProfession.CLERIC);
		Castle.npc(world, ox - 24.5, oy, oz + 0.5, 270f, LIBRARIAN_TAG, U.t("📚 Библиотекарь Книжкин", Formatting.GOLD),
				VillagerProfession.LIBRARIAN);
		Castle.npc(world, ox - 23.5, oy, oz + 21.5, 180f, EDITOR_TAG, U.t("📰 Редактор «Свободного голоса» Правдина", Formatting.AQUA),
				VillagerProfession.CARTOGRAPHER);
		Castle.npc(world, ox + 19.5, oy, oz + 20.5, 180f, SHERIFF_TAG, U.t("👮 Шериф Свободин", Formatting.BLUE),
				VillagerProfession.ARMORER);
		Castle.npc(world, ox + 27.5, oy + 1, oz + 27.5, 180f, JUDGE_TAG, U.t("⚖ Судья Справедливцева", Formatting.DARK_AQUA),
				VillagerProfession.CLERIC);
		Castle.npc(world, ox - 5.5, oy, oz + 22.5, 180f, MARKET_TAG, U.t("🛒 Свободный рынок: Купцов", Formatting.GREEN),
				VillagerProfession.FARMER);
		Castle.npc(world, ox + 6.5, oy, oz + 22.5, 180f, EXCHANGE_TAG, U.t("💱 Обменник «Курс»", Formatting.YELLOW),
				VillagerProfession.CARTOGRAPHER);
	}

	// ================================================================== гражданство

	public static void asylum(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (!inTown(p)) {
			U.msg(p, U.t("🛂 Убежище оформляют в мэрии " + NAME + "е.", Formatting.GRAY));
			return;
		}
		if (citizen(p)) {
			U.msg(p, U.t("🛂 Вы уже гражданин " + NAME + "и. Добро пожаловать домой.", Formatting.GREEN));
			return;
		}
		Long status = st.abroad.get(p.getUuid());
		boolean refugee = status != null && status < 0;
		if (!refugee) {
			int now = s.getTicks();
			if (now - confirmAsylum.getOrDefault(p.getUuid(), -9999) > 20 * 30) {
				confirmAsylum.put(p.getUuid(), now);
				U.msg(p, U.t("🛂 Ольга Убежищева: «Вы приехали легально. Если примете наше гражданство, в королевстве вас "
						+ "объявят предателем, и при возвращении будет тюрьма. Решать вам. Повторите запрос в течение 30 сек, "
						+ "если уверены.»", Formatting.YELLOW));
				return;
			}
		}
		st.demoCitizens.add(p.getUuid());
		st.abroad.put(p.getUuid(), Long.MAX_VALUE);
		st.markDirty();
		Demo.add(p, 30);
		p.getInventory().offerOrDrop(passport(p));
		U.title(p, U.t("🕊 ВЫ — ГРАЖДАНИН", Formatting.GREEN, Formatting.BOLD), U.t(NAME + "и", Formatting.WHITE));
		U.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
		U.msg(p, U.t("🛂 " + (refugee ? "Политическое убежище предоставлено. " : "") + "Добро пожаловать! Подъёмные: 30 Ð. "
				+ "Вы можете голосовать (/demo golos), баллотироваться в мэры (/demo kandidat), лечиться бесплатно, "
				+ "читать что угодно и говорить что думаете. Отказаться — /demo otkaz.", Formatting.GREEN));
		SecretPolice.note(s, p.getUuid(), "получил гражданство " + NAME + "и (ИЗМЕНА)");
		U.say(s, U.t("📢 Королевская правда: гражданин " + p.getName().getString() + " переметнулся в " + NAME
				+ "у. Его Величество объявил его предателем. Возвращение — только в карете.", Formatting.DARK_RED));
	}

	private static ItemStack passport(ServerPlayerEntity p) {
		ItemStack st = new ItemStack(Items.WRITTEN_BOOK);
		List<RawFilteredPair<Text>> pages = List.of(RawFilteredPair.of(Text.literal("§2§lПАСПОРТ ГРАЖДАНИНА§r\n§2§l" + NAME.toUpperCase()
				+ "§0\n\n" + p.getName().getString() + "\n\nВыдан: " + Docs.today() + "\nМиграционной службой\n\n§8Права гражданина — "
				+ "в Конституции. Обязанности — тоже, но их меньше.")));
		st.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of("Паспорт " + NAME + "и"),
				"Мэрия", 0, pages, true));
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "demo_passport");
		st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return st;
	}

	/** Гражданин погиб: свободная больница, а не похороны за свой счёт. true — обработано. */
	public static boolean onRespawn(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!st.demoBuilt || !citizen(p) || Jail.jailed(p)) return false;
		Vec3d h = hospital(st);
		st.abroad.put(p.getUuid(), Long.MAX_VALUE);
		p.teleport(p.getServer().getOverworld(), h.x, h.y, h.z, 90f, 0f);
		U.msg(p, U.t("⚕ Вас доставили в бесплатную больницу " + NAME + "и. Счёт: 0 Ð. Выздоравливайте!", Formatting.GREEN));
		return true;
	}

	// ================================================================== больница

	public static void heal(ServerPlayerEntity p) {
		int now = p.getServer().getTicks();
		if (now - healed.getOrDefault(p.getUuid(), -99999) < 20 * 60) {
			U.msg(p, U.t("⚕ Доктор Добрякова: «Вы же только что были. Отдохните, через минуту приходите.»", Formatting.GRAY));
			return;
		}
		healed.put(p.getUuid(), now);
		p.setHealth(p.getMaxHealth());
		p.clearStatusEffects();
		p.extinguish();
		p.getHungerManager().setFoodLevel(20);
		p.getHungerManager().setSaturationLevel(10f);
		U.sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
		U.msg(p, U.t("⚕ Доктор Добрякова: «" + U.pick("Здоровы! И это бесплатно — за счёт налогов, которые вы видите, куда идут.",
				"Сняли все последствия указов. Тут они не действуют.", "Следующий! Полиса не нужно, паспорта тоже.",
				"Витаминки на выходе. Тоже бесплатно.") + "»", Formatting.WHITE));
	}

	// ================================================================== библиотека

	private static ItemStack book(String title, String author, List<String> pages, String tag) {
		ItemStack st = new ItemStack(Items.WRITTEN_BOOK);
		List<RawFilteredPair<Text>> ps = new ArrayList<>();
		for (String pg : pages) ps.add(RawFilteredPair.of(Text.literal(pg)));
		st.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(title), author, 0, ps, true));
		NbtCompound t = new NbtCompound();
		t.putString("pizdec", tag);
		st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(t));
		return st;
	}

	public static ItemStack constitution() {
		return book("Конституция", "Сход жителей", List.of(
				"§l КОНСТИТУЦИЯ\n " + NAME.toUpperCase() + "§r\n\n§8Принята сходом жителей. Может быть изменена ими же.§0",
				"§lСт. 1.§r Власть принадлежит жителям. Король здесь не живёт.\n\n§lСт. 2.§r Мэр избирается гражданами раз в 20 минут. "
						+ "Голоса считаются. Все.",
				"§lСт. 3.§r Каждый может критиковать власть, включая мэра. Особенно мэра.\n\n§lСт. 4.§r Портреты руководителей "
						+ "не обязательны. Можно повесить кота.",
				"§lСт. 5.§r Торговля свободна. Цены — рыночные.\n\n§lСт. 6.§r Перемещение свободно. Выезд — без разрешений.",
				"§lСт. 7.§r Пресса независима. Цензуры нет.\n\n§lСт. 8.§r Суд независим, обвиняемый невиновен, пока не доказано "
						+ "обратное.",
				"§lСт. 9.§r Лечение в больнице бесплатно для всех.\n\n§lСт. 10.§r Убежище предоставляется любому, кто бежит от "
						+ "произвола. Гражданство — бесплатно.",
				"§lСт. 11.§r Ломать город, бить людей и гриферить — нельзя. Демократия — не анархия.\n\n§8Подписано всеми, "
						+ "кто дочитал.§0"), "constitution");
	}

	private static ItemStack history() {
		return book("История " + NAME + "и", "Книжкин", List.of(
				"§l ИСТОРИЯ\n " + NAME.toUpperCase() + "§r\n\nПГТ основали беженцы, которым повезло (0,5%).",
				"Первым делом они построили больницу, вторым — библиотеку, третьим — спорили, что строить третьим. Спорят до сих пор.",
				"Первый мэр пообещал починить лужу на площади. Лужа на месте. Мэра переизбрали. Это и есть демократия: "
						+ "можно ругать мэра и при этом ничего не бояться.",
				"Король называет " + NAME + "у «рассадником хаоса». Жители называют её «домом»."), "demo_book");
	}

	private static ItemStack refugeeGuide() {
		return book("Справочник беженца", "Миграционная служба", List.of(
				"§l СПРАВОЧНИК\n БЕЖЕНЦА§r\n\n1. Сбежавшим из королевства убежище даётся сразу: мэрия, Ольга Убежищева.",
				"2. Легально выехавшим — тоже, но подумайте: дома вас объявят предателем.\n\n3. Валюта — демо (Ð). Обменник на рынке.",
				"4. Больница бесплатна. После смерти граждан доставляют туда же.\n\n5. Голосуйте: /demo golos. Баллотируйтесь: "
						+ "/demo kandidat.",
				"6. Пишите в газету: /statya <текст>.\n\n7. Вернуться в королевство гражданину — тюрьма. Мы предупредили."),
				"demo_book");
	}

	private static ItemStack bannedBook() {
		return book("Анекдоты про короля", "Народ", List.of(
				"§l АНЕКДОТЫ\n ПРО КОРОЛЯ§r\n§4(запрещено в королевстве)§0\n\n— Ваше Величество, народ голодает!\n— Пусть едят "
						+ "Хрустики с лобстером.",
				"Король приказал построить статую в 25 блоков. Архитектор спросил: «Почему не 30?» Теперь архитектор на войне.",
				"— Почему в королевстве нет оппозиции?\n— Есть. Системная. Она голосует «за».",
				"Индекс любви к королю превысил 146%. Остальные 46% — сотрудники КЦИОМ."), "banned_book");
	}

	// ================================================================== независимая газета

	public static ItemStack freeVoice(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		List<String> pages = new ArrayList<>();
		pages.add("§2§l  «СВОБОДНЫЙ\n     ГОЛОС»§r\n§8Независимая газета " + NAME + "и · " + Docs.today() + " · бесплатно§0\n\n§l"
				+ U.pick("Мэр " + st.mayorName.split(" ")[0] + " снова не починил лужу. Редакция требует объяснений.",
						"В королевстве уже " + st.arrests + " арестов. Мы считаем — они нет.",
						"Новые беженцы прибыли в " + NAME + "у. Мест в больнице хватает.",
						"Рынок работает без дефицита " + (st.demoElectionNo + 3) + "-й день подряд."));
		int real = (int) Math.round(st.realRating + U.R.nextGaussian() * 4);
		List<String> sh = BlackMarket.shortageNames();
		pages.add("§l О СОСЕДЯХ§r\n\nАрестов в королевстве: " + st.arrests + "\nКоррупционных скандалов: " + st.scandals
				+ "\nДефицит сейчас: " + (sh.isEmpty() ? "—" : String.join(", ", sh.subList(0, Math.min(3, sh.size()))))
				+ "\n\nДоверие королю по оценке независимых социологов: ~" + Math.max(3, real) + "% (официально: "
				+ Politics.pct(Politics.officialKing(st)) + ").\n\n§8Независимых наблюдателей на выборы в королевство не пускают.§0");
		pages.add("§l НАШ ГОРОД§r\n\nМэр: " + st.mayorName + "\nНалог на рынке: " + st.demoTax + "%\nКазна города: "
				+ st.demoTreasury + " Ð\nГраждан: " + st.demoCitizens.size() + "\nКурс: 1 Ð = "
				+ String.format(java.util.Locale.ROOT, "%.1f", st.demoRate) + " бабла\n\nВыборы мэра через " + minutesLeft(st) + " мин.\n"
				+ (st.demoLastResult.isEmpty() ? "" : "\nПрошлые итоги: " + st.demoLastResult));
		StringBuilder op = new StringBuilder("§l МНЕНИЯ ЧИТАТЕЛЕЙ§r\n\n");
		if (st.articles.isEmpty()) op.append("Пока никто ничего не написал. Будьте первым: /statya <текст>");
		for (int i = Math.max(0, st.articles.size() - 3); i < st.articles.size(); i++) op.append(st.articles.get(i)).append("\n\n");
		pages.add(op.toString());
		pages.add("§l КРИТИКА ВЛАСТИ§r\n\n" + U.pick("Мэрия опять полгода обсуждает велодорожку. Хватит обсуждать — постройте.",
				"Налог на рынке " + st.demoTax + "% — это " + (st.demoTax > 7 ? "грабёж" : "терпимо, но мы следим") + ".",
				"Почему в библиотеке до сих пор нет книги про кошек? Вопрос к мэру.", "Шериф Свободин опять спал на посту. Хорошо, "
						+ "что у нас никто ничего не ломает.") + "\n\n§8За эту статью редакции ничего не будет. Так и должно быть.§0");
		ItemStack it = book("Свободный голос", "Редакция", pages, "free_voice");
		return it;
	}

	// ================================================================== полиция и суд

	private static void court(MinecraftServer s, ServerPlayerEntity p, int fine, String what) {
		PizdecState st = PizdecState.get(s);
		boolean guilty = U.chance(0.8);
		if (guilty) {
			Demo.add(p, -fine);
			lastFine.put(p.getUuid(), fine);
			st.demoTreasury += fine;
			U.msg(p, U.t("⚖ Суд " + NAME + "и (присяжные, адвокат бесплатно): " + what + " — виновен. Штраф " + fine
					+ " Ð. Обжаловать: /demo apellyaciya", Formatting.DARK_AQUA));
		} else {
			U.msg(p, U.t("⚖ Суд " + NAME + "и: " + what + " — доказательств недостаточно, оправдан. Презумпция невиновности.",
					Formatting.DARK_AQUA));
		}
		st.log(st.demoCourt, Docs.today() + " · " + p.getName().getString() + ": " + what + " → " + (guilty ? "штраф " + fine + " Ð"
				: "оправдан"), 30);
	}

	/** Ломать город нельзя: шериф останавливает, на третий раз — суд. */
	public static boolean allowBreak(ServerPlayerEntity p, BlockPos pos) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!inside(st, pos.getX() + 0.5, pos.getZ() + 0.5) || p.isCreative()) return true;
		int n = warnings.merge(p.getUuid(), 1, Integer::sum);
		if (n < 3) {
			U.bar(p, U.t("👮 Шериф: «Это общественная собственность. Предупреждение " + n + "/2.»", Formatting.BLUE));
		} else {
			warnings.put(p.getUuid(), 0);
			court(p.getServer(), p, 5, "порча городского имущества");
		}
		return false;
	}

	/** Насилие в городе. true — запретить удар. */
	public static boolean onAttack(ServerPlayerEntity p, Entity target) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!st.demoBuilt || p.isCreative()) return false;
		boolean npc = false;
		for (String t : TAGS) if (target.getCommandTags().contains(t)) npc = true;
		if (npc) {
			court(p.getServer(), p, 30, "нападение на работника города");
			return true;
		}
		if (target instanceof ServerPlayerEntity && inside(st, target.getPos())) {
			court(p.getServer(), p, 10, "драка на улице");
			return true;
		}
		return false;
	}

	// ================================================================== выборы мэра

	private static int minutesLeft(PizdecState st) {
		return Math.max(0, (CYCLE - st.demoElectionTicks) / 1200);
	}

	/** Кандидаты: ключ — id ("npc0".."npc2" или UUID), значение — подпись. */
	private static Map<String, String> candidates(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < PARTIES.length; i++) m.put("npc" + i, PARTIES[i][1] + " («" + PARTIES[i][0] + "»)");
		for (UUID id : st.demoCandidates) m.put(id.toString(), st.names.getOrDefault(id, "гражданин") + " (самовыдвиженец)");
		return m;
	}

	private static void election(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.demoElectionNo++;
		Map<String, String> cands = candidates(s);
		Map<String, Integer> votes = new LinkedHashMap<>();
		for (String k : cands.keySet()) votes.put(k, k.startsWith("npc") ? U.between(15, 60) : U.between(0, 8));
		int real = 0;
		for (Map.Entry<UUID, String> e : st.demoVotes.entrySet()) {
			if (votes.containsKey(e.getValue())) {
				votes.merge(e.getValue(), 5, Integer::sum); // голос гражданина-игрока весит как семья с котом
				real++;
			}
		}
		String win = null;
		int best = -1, total = 0;
		for (Map.Entry<String, Integer> e : votes.entrySet()) {
			total += e.getValue();
			if (e.getValue() > best) {
				best = e.getValue();
				win = e.getKey();
			}
		}
		StringBuilder res = new StringBuilder();
		U.say(s, U.t("🗳 " + NAME + ": выборы мэра №" + st.demoElectionNo + ". Подсчёт открытый, наблюдатели — все желающие. "
				+ "Явка: " + total + " (из них игроков — " + real + ").", Formatting.GREEN));
		for (Map.Entry<String, Integer> e : votes.entrySet()) {
			U.say(s, U.t("   " + cands.get(e.getKey()) + " — " + e.getValue() + " голосов (" + Math.round(100.0 * e.getValue() / Math.max(1, total))
					+ "%)", Formatting.WHITE));
			res.append(cands.get(e.getKey()).split(" ")[0]).append(" ").append(e.getValue()).append("; ");
		}
		ServerPlayerEntity oldMayor = st.mayor == null ? null : s.getPlayerManager().getPlayer(st.mayor);
		if (win.startsWith("npc")) {
			st.mayor = null;
			st.mayorName = PARTIES[Integer.parseInt(win.substring(3))][1];
		} else {
			st.mayor = UUID.fromString(win);
			st.mayorName = st.names.getOrDefault(st.mayor, "гражданин");
			ServerPlayerEntity m = s.getPlayerManager().getPlayer(st.mayor);
			if (m != null) {
				U.title(m, U.t("🏛 ВЫ — МЭР", Formatting.GREEN, Formatting.BOLD), U.t(NAME + "и", Formatting.WHITE));
				U.msg(m, U.t("🏛 Поздравляем! Команды мэра: /mer nalog <0-15>, /mer obrashchenie <текст>, /mer kazna. "
						+ "Зарплата — 25 Ð каждые 5 минут из казны. Через 20 минут — новые выборы. И да, вас будут критиковать.",
						Formatting.GREEN));
			}
		}
		if (oldMayor != null && !oldMayor.getUuid().equals(st.mayor)) {
			U.msg(oldMayor, U.t("🏛 Вы проиграли выборы. Власть передаётся мирно. Спасибо за службу!", Formatting.GRAY));
		}
		U.say(s, U.t("🗳 Новый мэр " + NAME + "и: " + st.mayorName + ". Проигравшие поздравили победителя. Так бывает.",
				Formatting.GREEN));
		st.demoLastResult = "№" + st.demoElectionNo + ": " + res;
		st.demoVotes.clear();
		st.demoCandidates.clear();
		st.markDirty();
	}

	public static final class VoteGui extends Gui {
		private final List<String> keys = new ArrayList<>();

		private VoteGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🗳 Выборы мэра " + NAME + "и", VoteGui::new);
		}

		private void redraw() {
			fill(Items.LIME_STAINED_GLASS_PANE);
			PizdecState st = PizdecState.get(player.getServer());
			keys.clear();
			Map<String, String> c = candidates(player.getServer());
			int slot = 19;
			Item[] icons = {Items.BOOK, Items.SADDLE, Items.STRING, Items.PLAYER_HEAD};
			int i = 0;
			for (Map.Entry<String, String> e : c.entrySet()) {
				if (slot > 25) break;
				keys.add(e.getKey());
				set(slot++, icon(icons[Math.min(i, 3)], "§f" + e.getValue(),
						"§7Обещает: " + PROMISES[Math.abs(e.getKey().hashCode()) % PROMISES.length],
						e.getKey().equals(st.demoVotes.get(player.getUuid())) ? "§aВаш голос" : "§eЛКМ — голосовать"));
				i++;
			}
			set(4, icon(Items.PAPER, "§fВыборы мэра №" + (st.demoElectionNo + 1), "§7Голосуют граждане " + NAME + "и",
					"§7До подсчёта: " + minutesLeft(st) + " мин", "§7Голос можно поменять до подсчёта",
					"§7Мэр сейчас: " + st.mayorName));
			set(40, icon(Items.WRITABLE_BOOK, "§eВыдвинуться в мэры — 20 Ð", "§7Только для граждан", "§7Команда: /demo kandidat"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			int idx = slot - 19;
			if (idx < 0 || idx >= keys.size()) return;
			if (!citizen(player)) {
				U.msg(player, U.t("🗳 Голосуют граждане. Гражданство — в миграционной службе (слева при входе).", Formatting.GRAY));
				return;
			}
			PizdecState st = PizdecState.get(player.getServer());
			st.demoVotes.put(player.getUuid(), keys.get(idx));
			st.markDirty();
			U.msg(player, U.t("🗳 Голос учтён. Его посчитают. Честно.", Formatting.GREEN));
			redraw();
		}
	}

	// ================================================================== рынок и обменник

	private static int demoPrice(PizdecState st, int i) {
		int base = Math.max(1, (int) Math.ceil(Shop.offerPrice(i) / 6.0));
		return base + base * st.demoTax / 100;
	}

	public static final class MarketGui extends Gui {
		private MarketGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🛒 Свободный рынок " + NAME + "и", MarketGui::new);
		}

		private void redraw() {
			fill(Items.LIME_STAINED_GLASS_PANE);
			PizdecState st = PizdecState.get(player.getServer());
			for (int i = 0; i < Shop.offers() && i < 45; i++) {
				ItemStack it = Shop.offerStack(i);
				set(i, icon(it, "§f" + it.getName().getString() + (it.getCount() > 1 ? " ×" + it.getCount() : ""),
						"§aЦена: " + demoPrice(st, i) + " Ð §7(налог мэрии " + st.demoTax + "%)", "§7Всегда в наличии", "§7ЛКМ — купить"));
			}
			set(49, icon(Items.EMERALD, "§aТвои демо: " + Demo.get(player) + " Ð"));
			set(48, icon(Items.HOPPER, "§eПродать то, что в руке", "§7Алмаз 6, изумруд 4, золото 2, железо 1,", "§7незерит 60, уголь/еда 0 (не берём)"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			PizdecState st = PizdecState.get(player.getServer());
			if (slot == 53) {
				close();
				return;
			}
			if (slot == 48) {
				sell(player);
				redraw();
				return;
			}
			if (slot >= Shop.offers() || slot >= 45) return;
			int price = demoPrice(st, slot);
			if (!Demo.pay(player, price, "покупку на рынке")) return;
			int tax = price - Math.max(1, (int) Math.ceil(Shop.offerPrice(slot) / 6.0));
			st.demoTreasury += Math.max(0, tax);
			st.markDirty();
			player.getInventory().offerOrDrop(Shop.offerStack(slot));
			U.sound(player, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1.1f);
			redraw();
		}
	}

	private static void sell(ServerPlayerEntity p) {
		ItemStack h = p.getMainHandStack();
		int each = h.isOf(Items.DIAMOND) ? 6 : h.isOf(Items.EMERALD) ? 4 : h.isOf(Items.GOLD_INGOT) ? 2 : h.isOf(Items.IRON_INGOT) ? 1
				: h.isOf(Items.NETHERITE_INGOT) ? 60 : 0;
		if (each == 0 || Gui.isSpecial(h, "fake_diamond")) {
			U.msg(p, U.t("🛒 Купцов: «Это не возьму. Алмазы, изумруды, золото, железо, незерит. И не фальшивые — я проверяю.»",
					Formatting.GRAY));
			return;
		}
		int sum = each * h.getCount();
		h.setCount(0);
		Demo.add(p, sum);
		U.msg(p, U.t("🛒 Продано за " + sum + " Ð. Без налоговой, без конфискаций.", Formatting.GREEN));
	}

	public static final class ExchangeGui extends Gui {
		private ExchangeGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "💱 Обменник «Курс»", ExchangeGui::new);
		}

		private void redraw() {
			fill(Items.YELLOW_STAINED_GLASS_PANE);
			double r = PizdecState.get(player.getServer()).demoRate;
			int[] n = {1, 10, 50};
			for (int i = 0; i < 3; i++) {
				set(10 + i, icon(Items.EMERALD, "§aКупить " + n[i] + " Ð", "§7За " + (int) Math.ceil(n[i] * r * 1.02) + " бабла"));
				set(14 + i, icon(Items.GOLD_NUGGET, "§6Продать " + n[i] + " Ð", "§7Получите " + (int) Math.floor(n[i] * r * 0.98) + " бабла"));
			}
			set(31, icon(Items.CLOCK, "§fКурс: 1 Ð = " + String.format(java.util.Locale.ROOT, "%.2f", r) + " бабла",
					"§7Курс плавает — рынок свободный", "§7У тебя: " + Demo.get(player) + " Ð и " + Money.get(player) + " бабла"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			PizdecState st = PizdecState.get(player.getServer());
			int[] n = {1, 10, 50};
			if (slot >= 10 && slot <= 12) {
				int k = n[slot - 10];
				if (Money.pay(player, (int) Math.ceil(k * st.demoRate * 1.02), "покупку демо")) Demo.add(player, k);
			} else if (slot >= 14 && slot <= 16) {
				int k = n[slot - 14];
				if (Demo.pay(player, k, "продажу демо")) Money.add(player, (int) Math.floor(k * st.demoRate * 0.98));
			}
			redraw();
		}
	}

	// ================================================================== общение с горожанами

	public static boolean talk(ServerPlayerEntity p, Entity e) {
		var tags = e.getCommandTags();
		PizdecState st = PizdecState.get(p.getServer());
		if (tags.contains(MIGRATION_TAG)) {
			asylum(p);
		} else if (tags.contains(MAYOR_TAG)) {
			VoteGui.open(p);
		} else if (tags.contains(DOCTOR_TAG)) {
			heal(p);
		} else if (tags.contains(LIBRARIAN_TAG)) {
			U.msg(p, U.t("📚 Книжкин: «Берите что хотите, бесплатно. Только не везите «Анекдоты» в королевство — там это "
					+ "контрабанда.»", Formatting.GOLD));
			p.getInventory().offerOrDrop(constitution());
			p.getInventory().offerOrDrop(U.pick(history(), refugeeGuide(), bannedBook()));
		} else if (tags.contains(EDITOR_TAG)) {
			p.getInventory().offerOrDrop(freeVoice(p.getServer()));
			U.msg(p, U.t("📰 Правдина: «Свежий номер. Хотите написать сами — /statya <текст>. Пишите что угодно, хоть про мэра.»",
					Formatting.AQUA));
		} else if (tags.contains(SHERIFF_TAG)) {
			U.msg(p, U.t("👮 Шериф Свободин: «Правила простые: не ломай город, не дерись. Всё остальное — свобода. "
					+ "Королевских указов тут нет, карет тоже.»", Formatting.BLUE));
		} else if (tags.contains(JUDGE_TAG)) {
			U.msg(p, U.t("⚖ Судья Справедливцева: «Последние дела суда:»", Formatting.DARK_AQUA));
			for (int i = Math.max(0, st.demoCourt.size() - 6); i < st.demoCourt.size(); i++) {
				U.msg(p, U.t("   " + st.demoCourt.get(i), Formatting.GRAY));
			}
			if (st.demoCourt.isEmpty()) U.msg(p, U.t("   Дел нет. Скучно, но хорошо.", Formatting.GRAY));
		} else if (tags.contains(MARKET_TAG)) {
			MarketGui.open(p);
		} else if (tags.contains(EXCHANGE_TAG)) {
			ExchangeGui.open(p);
		} else {
			return false;
		}
		return true;
	}

	public static boolean isTownNpc(Entity e) {
		for (String t : TAGS) if (e.getCommandTags().contains(t)) return true;
		return false;
	}

	// ================================================================== тик

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.demoBuilt) return;
		st.demoElectionTicks++;
		if (st.demoElectionTicks % 1200 == 0) st.markDirty();
		if (st.demoElectionTicks == CYCLE - 20 * 120) {
			for (ServerPlayerEntity p : U.players(s)) {
				if (!inTown(p)) continue;
				U.msg(p, U.t("🗳 Дебаты кандидатов на площади! " + PARTIES[U.R.nextInt(3)][1] + " обещает "
						+ U.pick(PROMISES) + ", а оппонент — " + U.pick(PROMISES) + ". Голосование — /demo golos.", Formatting.GREEN));
			}
		}
		if (st.demoElectionTicks >= CYCLE) {
			st.demoElectionTicks = 0;
			election(s);
		}
		int now = s.getTicks();
		// курс валют плавает
		if (now % 1200 == 0) {
			st.demoRate = Math.max(2.5, Math.min(8.0, st.demoRate + (U.R.nextDouble() - 0.5) * 0.4
					+ (BlackMarket.shortageNames().size() > 6 ? 0.1 : 0)));
			st.markDirty();
		}
		// зарплата мэра-игрока
		if (now % (20 * 300) == 0 && st.mayor != null) {
			ServerPlayerEntity m = s.getPlayerManager().getPlayer(st.mayor);
			if (m != null && st.demoTreasury >= 25) {
				st.demoTreasury -= 25;
				Demo.add(m, 25);
				U.bar(m, U.t("🏛 Зарплата мэра: +25 Ð (из казны города, отчёт — в газете)", Formatting.GREEN));
			}
		}
		if (now % 20 == 0) {
			for (ServerPlayerEntity p : U.players(s)) {
				if (inTown(p) && now % 400 == 0) {
					U.bar(p, U.t("🕊 " + NAME + " · мэр: " + st.mayorName.split(" ")[0] + " · выборы через " + minutesLeft(st)
							+ " мин · говори, что думаешь", Formatting.GREEN));
				}
			}
		}
	}

	// ================================================================== команды

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("demo").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (!st.demoBuilt) {
				U.msg(p, U.t(NAME + " ещё не построена.", Formatting.GRAY));
				return 0;
			}
			Vec3d e = entrance(st);
			U.msg(p, U.t("🕊 " + NAME + " — свободный ПГТ в " + DIST + " блоках за Северным КПП: " + U.coords((int) e.x, (int) e.y,
					(int) e.z) + ". Мэр: " + st.mayorName + ". Граждан: " + st.demoCitizens.size() + ". Твои демо: " + Demo.get(p)
					+ " Ð. " + (citizen(p) ? "Ты гражданин." : "Беженцам из королевства — гражданство в мэрии.") + " Команды: /demo golos, "
					+ "/demo kandidat, /demo otkaz, /demo apellyaciya, /demo prodat, /statya <текст>.", Formatting.GREEN));
			return 1;
		}).then(CommandManager.literal("golos").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (!inTown(p)) {
				U.msg(p, U.t("🗳 Голосовать можно в " + NAME + "е.", Formatting.GRAY));
				return 0;
			}
			VoteGui.open(p);
			return 1;
		})).then(CommandManager.literal("kandidat").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (!citizen(p)) {
				U.msg(p, U.t("🗳 Баллотироваться могут граждане.", Formatting.GRAY));
				return 0;
			}
			if (st.demoCandidates.contains(p.getUuid())) {
				U.msg(p, U.t("🗳 Вы уже кандидат. Агитируйте!", Formatting.GRAY));
				return 0;
			}
			if (!Demo.pay(p, 20, "избирательный взнос")) return 0;
			st.demoCandidates.add(p.getUuid());
			st.demoTreasury += 20;
			st.markDirty();
			U.say(p.getServer(), U.t("🗳 " + NAME + ": " + p.getName().getString() + " выдвинулся в мэры! Подписи никто не "
					+ "проверял на шрифт.", Formatting.GREEN));
			return 1;
		})).then(CommandManager.literal("otkaz").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (st.demoCitizens.remove(p.getUuid())) {
				if (Long.valueOf(Long.MAX_VALUE).equals(st.abroad.get(p.getUuid()))) st.abroad.put(p.getUuid(), -1L);
				st.markDirty();
				U.msg(p, U.t("🛂 Вы отказались от гражданства " + NAME + "и. Мы уважаем ваш выбор. Король — нет: "
						+ "дома вы всё ещё беглец.", Formatting.GRAY));
			}
			return 1;
		})).then(CommandManager.literal("apellyaciya").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			Integer f = lastFine.remove(p.getUuid());
			if (f == null) {
				U.msg(p, U.t("⚖ Обжаловать нечего.", Formatting.GRAY));
				return 0;
			}
			if (U.chance(0.4)) {
				Demo.add(p, f);
				U.msg(p, U.t("⚖ Апелляция удовлетворена. Штраф " + f + " Ð возвращён. Суд тоже ошибается — и признаёт это.",
						Formatting.DARK_AQUA));
			} else {
				U.msg(p, U.t("⚖ Апелляция отклонена. Решение мотивировано и опубликовано.", Formatting.DARK_AQUA));
			}
			return 1;
		})).then(CommandManager.literal("prodat").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (!inTown(p)) return 0;
			sell(p);
			return 1;
		})));
		d.register(CommandManager.literal("statya")
				.then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					PizdecState st = PizdecState.get(p.getServer());
					if (!inTown(p)) {
						U.msg(p, U.t("📰 Статьи принимает редакция «Свободного голоса» в " + NAME + "е.", Formatting.GRAY));
						return 0;
					}
					int now = p.getServer().getTicks();
					if (now - lastArticle.getOrDefault(p.getUuid(), -99999) < 20 * 120) {
						U.msg(p, U.t("📰 Одна статья в 2 минуты — редакция не успевает читать.", Formatting.GRAY));
						return 0;
					}
					lastArticle.put(p.getUuid(), now);
					String t = StringArgumentType.getString(c, "text");
					if (t.length() > 180) t = t.substring(0, 180) + "…";
					st.log(st.articles, "«" + t + "» — " + p.getName().getString(), 10);
					U.msg(p, U.t("📰 Статья опубликована в следующем номере. Без правок. Без Главлита.", Formatting.AQUA));
					for (ServerPlayerEntity o : U.players(p.getServer())) {
						if (o != p && inTown(o)) U.msg(o, U.t("📰 Новая статья в «Свободном голосе»: «" + t + "» — "
								+ p.getName().getString(), Formatting.AQUA));
					}
					return 1;
				})));
		d.register(CommandManager.literal("mer").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("🏛 Мэр " + NAME + "и: " + st.mayorName + ". Налог на рынке " + st.demoTax + "%, казна " + st.demoTreasury
					+ " Ð. " + (p.getUuid().equals(st.mayor) ? "Это вы! /mer nalog, /mer obrashchenie, /mer kazna" : ""),
					Formatting.GREEN));
			return 1;
		}).then(CommandManager.literal("nalog").then(CommandManager.argument("pct", IntegerArgumentType.integer(0, 15)).executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (!p.getUuid().equals(st.mayor)) {
				U.msg(p, U.t("🏛 Налоги меняет мэр. Хотите — избирайтесь.", Formatting.GRAY));
				return 0;
			}
			st.demoTax = IntegerArgumentType.getInteger(c, "pct");
			st.markDirty();
			U.say(p.getServer(), U.t("🏛 Мэр " + NAME + "и установил налог на рынке: " + st.demoTax + "%. Газета уже пишет "
					+ "критическую статью.", Formatting.GREEN));
			return 1;
		}))).then(CommandManager.literal("obrashchenie").then(CommandManager.argument("text", StringArgumentType.greedyString())
				.executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					PizdecState st = PizdecState.get(p.getServer());
					if (!p.getUuid().equals(st.mayor)) return 0;
					String t = StringArgumentType.getString(c, "text");
					for (ServerPlayerEntity o : U.players(p.getServer())) {
						if (inTown(o) || citizen(o)) U.msg(o, U.t("🏛 Мэр " + p.getName().getString() + ": «" + t + "»", Formatting.GREEN));
					}
					return 1;
				}))).then(CommandManager.literal("kazna").executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					PizdecState st = PizdecState.get(p.getServer());
					U.msg(p, U.t("🏛 Казна " + NAME + "и: " + st.demoTreasury + " Ð. Отчёт открытый: доходы — налог рынка и штрафы суда, "
							+ "расходы — зарплата мэра (25 Ð / 5 мин). Больница — бесплатно (врачи — волонтёры).", Formatting.GREEN));
					return 1;
				})));
	}
}
