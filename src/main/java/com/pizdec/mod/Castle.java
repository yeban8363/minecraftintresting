package com.pizdec.mod;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import static com.pizdec.mod.B.*;

/**
 * Замок Короля, версия 2. 57×45 блоков: ворота с надвратными башнями, 4 угловые башни, двор с фонтаном,
 * налоговая, королевский сад, торговые ряды (магазин, казино, апгрейдер), отель на два этажа,
 * восточное крыло (биржа труда, казарма стражи, казначейство) и тронный зал.
 */
public final class Castle {
	public static final int SX = 57, SZ = 45;
	public static final int VERSION = 3;
	public static final int PARLIAMENT_SEATS = 210;

	public static final String VERSION_TAG = "pizdec_v2";
	public static final String KING_TAG = "pizdec_king";
	public static final String INSPECTOR_TAG = "pizdec_inspector";
	public static final String JESTER_TAG = "pizdec_jester";
	public static final String SHOP_TAG = "pizdec_shop";
	public static final String CASINO_TAG = "pizdec_casino";
	public static final String UPGRADER_TAG = "pizdec_upgrader";
	public static final String HR_TAG = "pizdec_hr";
	public static final String RECEPTION_TAG = "pizdec_reception";
	public static final String CAPTAIN_TAG = "pizdec_captain";
	public static final String TREASURER_TAG = "pizdec_treasurer";
	public static final String SECRETARY_TAG = "pizdec_secretary";
	public static final String GATE_GUARD_TAG = "pizdec_gateguard";
	public static final String SPEAKER_TAG = "pizdec_speaker";
	public static final String CEC_TAG = "pizdec_cec";
	public static final String PARTY_TAG = "pizdec_party";
	/** Все «наши» постоянные жители — у старых версий нет VERSION_TAG, их убираем. */
	public static final String[] NPC_TAGS = {KING_TAG, INSPECTOR_TAG, JESTER_TAG, SHOP_TAG, CASINO_TAG, UPGRADER_TAG, HR_TAG,
			RECEPTION_TAG, CAPTAIN_TAG, TREASURER_TAG, SECRETARY_TAG, GATE_GUARD_TAG, "pizdec_guard", "pizdec_judge",
			"pizdec_prosecutor", "pizdec_jury"};
	public static final String KING_NAME = "Король Пиздец III";

	private Castle() {}

	// ------------------------------------------------------------------ где что

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.castleBuilt && p.getX() >= st.cx - 5 && p.getX() <= st.cx + SX + 4 && p.getZ() >= st.cz - 5
				&& p.getZ() <= st.cz + SZ + 44 && p.getY() >= st.cy - 8 && p.getY() <= st.cy + 24;
	}

	public static BlockPos thronePos(PizdecState st) {
		return new BlockPos(st.cx + 28, st.cy + 1, st.cz + 40);
	}

	/** Перед воротами снаружи. */
	public static Vec3d gateOutside(PizdecState st) {
		return new Vec3d(st.cx + 28.5, st.cy, st.cz - 5.5);
	}

	public static Vec3d courtyard(PizdecState st) {
		return new Vec3d(st.cx + 28.5, st.cy, st.cz + 12.5);
	}

	public static Vec3d throneHall(PizdecState st) {
		return new Vec3d(st.cx + 28.5, st.cy, st.cz + 33.5);
	}

	private static boolean in(PizdecState st, Vec3d p, int x0, int z0, int x1, int z1, int y0, int y1) {
		return p.x >= st.cx + x0 && p.x < st.cx + x1 + 1 && p.z >= st.cz + z0 && p.z < st.cz + z1 + 1
				&& p.y >= st.cy + y0 - 0.5 && p.y < st.cy + y1 + 1;
	}

	public static boolean inThroneHall(PizdecState st, Vec3d p) {
		return in(st, p, 19, 29, 37, 41, 0, 10);
	}

	public static boolean inTreasury(PizdecState st, Vec3d p) {
		return in(st, p, 43, 31, 53, 41, 0, 5);
	}

	public static boolean inHotelLobby(PizdecState st, Vec3d p) {
		return in(st, p, 3, 7, 13, 13, 0, 4);
	}

	public static boolean inParliament(PizdecState st, Vec3d p) {
		return st.castleBuilt && in(st, p, 11, 58, 45, 79, -1, 11);
	}

	/** Сундук в номере отеля (куда кладут газету) или null. */
	public static BlockPos roomChest(PizdecState st, int room) {
		int[] r;
		if (room >= 6 && room <= 9) r = new int[] {13, 0, 15 + 6 * (room - 6) + 4};
		else if (room == 10) r = new int[] {5, 6, 24};
		else if (room == 11) r = new int[] {11, 6, 24};
		else if (room == 12) r = new int[] {13, 6, 34};
		else return null;
		return new BlockPos(st.cx + r[0], st.cy + r[1], st.cz + r[2]);
	}

	public static BlockPos lobbyNewsstand(PizdecState st) {
		return new BlockPos(st.cx + 4, st.cy, st.cz + 7);
	}

	/** Экраны телевизоров: {x, dy, z} относительно замка. */
	public static List<int[][]> tvScreens() {
		List<int[][]> l = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) l.add(new int[][] {{2, 2, 15 + 4 * i}, {2, 2, 16 + 4 * i}});
		for (int i = 0; i < 4; i++) l.add(new int[][] {{14, 2, 16 + 6 * i}, {14, 2, 17 + 6 * i}});
		l.add(new int[][] {{2, 8, 19}, {2, 8, 20}});
		l.add(new int[][] {{14, 8, 19}, {14, 8, 20}});
		l.add(new int[][] {{7, 8, 42}, {8, 8, 42}, {9, 8, 42}, {7, 9, 42}, {8, 9, 42}, {9, 9, 42}});
		l.add(new int[][] {{5, 2, 6}, {6, 2, 6}, {7, 2, 6}, {5, 3, 6}, {6, 3, 6}, {7, 3, 6}});
		int[][] foyer = new int[15][];
		int k = 0;
		for (int x = 36; x <= 40; x++) for (int y = 2; y <= 4; y++) foyer[k++] = new int[] {x, y, 50};
		l.add(foyer);
		int[][] hall = new int[36][];
		k = 0;
		for (int x = 24; x <= 32; x++) for (int y = 5; y <= 8; y++) hall[k++] = new int[] {x, y, 57};
		l.add(hall);
		return l;
	}

	public static boolean inBarracks(PizdecState st, Vec3d p) {
		return in(st, p, 43, 19, 53, 29, 0, 5);
	}

	// ------------------------------------------------------------------ стройка

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		ServerWorld world = s.getOverworld();
		w = world;
		if (!st.castleBuilt) {
			BlockPos spawn = world.getSpawnPos();
			st.cx = spawn.getX() + 40;
			st.cz = spawn.getZ() - 16;
			loadChunks(st.cx - 8, st.cz - 8, st.cx + SX + 8, st.cz + SZ + 46);
			st.cy = ground(st.cx + SX / 2, st.cz + SZ / 2);
			build(st);
			st.castleBuilt = true;
			st.version = VERSION;
			st.markDirty();
			spawnNpcs(world, st);
			initSeats(s);
			PizdecMod.LOG.info("Замок короля v2 построен на {} {} {}", st.cx, st.cy, st.cz);
		} else if (st.version < 2) {
			migrate(s, st);
		} else if (st.version < VERSION) {
			// версия 3: парламентский квартал, телевизоры, газетный киоск
			loadChunks(st.cx - 8, st.cz + 40, st.cx + SX + 8, st.cz + SZ + 46);
			parliamentQuarter(st.cx, st.cy, st.cz);
			tvsAndNewsstand(st.cx, st.cy, st.cz);
			spawnParliamentNpcs(world, st);
			st.version = VERSION;
			st.markDirty();
			initSeats(s);
			U.say(s, U.t("🏛 К замку пристроен парламентский квартал. Выборы — раз в 30 минут!", Formatting.GOLD));
		}
	}

	/** Перестройка старого замка (и тюрьмы с судом) под новую версию. */
	private static void migrate(MinecraftServer s, PizdecState st) {
		ServerWorld world = s.getOverworld();
		w = world;
		PizdecMod.LOG.info("Перестраиваю королевский квартал до версии {}", VERSION);
		removeOldNpcs(world);
		// старая тюрьма и суд — сносим до земли
		if (st.prisonBuilt) {
			loadChunks(st.px - 4, st.pz - 4, st.px + 28, st.pz + 16);
			clear(st.px - 3, st.py - 1, st.pz - 3, st.px + 27, st.py + 14, st.pz + 15);
			fill(st.px - 3, st.py - 1, st.pz - 3, st.px + 27, st.py - 1, st.pz + 15, Blocks.GRASS_BLOCK);
			st.prisonBuilt = false;
		}
		if (st.courtBuilt) {
			loadChunks(st.kx - 3, st.kz - 3, st.kx + 23, st.kz + 15);
			clear(st.kx - 2, st.ky - 1, st.kz - 2, st.kx + 22, st.ky + 12, st.kz + 14);
			fill(st.kx - 2, st.ky - 1, st.kz - 2, st.kx + 22, st.ky - 1, st.kz + 14, Blocks.GRASS_BLOCK);
			st.courtBuilt = false;
		}
		loadChunks(st.cx - 8, st.cz - 8, st.cx + SX + 8, st.cz + SZ + 8);
		loadChunks(st.cx - 8, st.cz + 40, st.cx + SX + 8, st.cz + SZ + 46);
		build(st);
		st.version = VERSION;
		st.markDirty();
		spawnNpcs(world, st);
		initSeats(s);
		U.say(s, U.t("🏗 Король перестроил замок, тюрьму и суд. Смотрите /pizdec castle — новые адреса.", Formatting.GOLD));
	}

	public static boolean isOldNpc(Entity e) {
		if (e.getCommandTags().contains(VERSION_TAG)) return false;
		for (String t : NPC_TAGS) if (e.getCommandTags().contains(t)) return true;
		return false;
	}

	private static void removeOldNpcs(ServerWorld world) {
		java.util.List<Entity> old = new java.util.ArrayList<>();
		for (Entity e : world.iterateEntities()) if (isOldNpc(e)) old.add(e);
		old.forEach(Entity::discard);
	}

	private static BlockState wallBlock() {
		int r = U.R.nextInt(100);
		if (r < 70) return Blocks.STONE_BRICKS.getDefaultState();
		if (r < 85) return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
		return Blocks.CRACKED_STONE_BRICKS.getDefaultState();
	}

	private static void build(PizdecState st) {
		int ox = st.cx, oy = st.cy, oz = st.cz;
		site(ox - 5, oz - 6, ox + SX + 4, oz + SZ + 4, oy, 40, Blocks.GRASS_BLOCK, Blocks.STONE_BRICKS);

		// --- внешняя стена с проходом поверху
		for (int x = 0; x < SX; x++) {
			for (int z = 0; z < SZ; z++) {
				boolean edge = x == 0 || z == 0 || x == SX - 1 || z == SZ - 1;
				boolean ring = !edge && (x == 1 || z == 1 || x == SX - 2 || z == SZ - 2);
				if (edge) {
					for (int y = oy; y <= oy + 9; y++) set(ox + x, y, oz + z, wallBlock());
					if ((x + z) % 2 == 0) set(ox + x, oy + 10, oz + z, wallBlock());
				} else if (ring) {
					set(ox + x, oy + 8, oz + z, Blocks.DARK_OAK_PLANKS);
				}
			}
		}
		// --- надвратные башни и ворота (север)
		for (int[] t : new int[][] {{22, 25}, {31, 34}}) {
			for (int x = t[0]; x <= t[1]; x++) {
				for (int z = -3; z <= 2; z++) {
					for (int y = oy; y <= oy + 12; y++) set(ox + x, y, oz + z, wallBlock());
					if ((x + z) % 2 == 0) set(ox + x, oy + 13, oz + z, wallBlock());
				}
			}
		}
		fill(ox + 26, oy, oz - 3, ox + 30, oy + 5, oz + 2, Blocks.AIR);
		fill(ox + 26, oy + 5, oz, ox + 30, oy + 5, oz, Blocks.IRON_BARS);
		set(ox + 28, oy + 7, oz - 1, Blocks.GOLD_BLOCK);
		lampPost(ox + 24, oy, oz - 5);
		lampPost(ox + 32, oy, oz - 5);
		// --- угловые башни
		for (int[] c : new int[][] {{0, 0}, {SX - 1, 0}, {0, SZ - 1}, {SX - 1, SZ - 1}}) {
			tower(ox + c[0], oy, oz + c[1], c[0] == 0 ? 1 : -1, c[1] == 0 ? 1 : -1);
		}

		// --- двор: дорожки, фонтан, фонари
		fill(ox + 26, oy - 1, oz - 6, ox + 30, oy - 1, oz + 27, Blocks.POLISHED_ANDESITE);
		fill(ox + 15, oy - 1, oz + 11, ox + 41, oy - 1, oz + 12, Blocks.POLISHED_ANDESITE);
		fill(ox + 26, oy - 1, oz + 23, ox + 41, oy - 1, oz + 24, Blocks.POLISHED_ANDESITE);
		fill(ox + 39, oy - 1, oz + 24, ox + 41, oy - 1, oz + 33, Blocks.POLISHED_ANDESITE);
		fill(ox + 15, oy - 1, oz + 5, ox + 41, oy - 1, oz + 6, Blocks.STONE_BRICKS);
		fountain(ox + 28, oy, oz + 18);
		for (int z : new int[] {8, 14, 22}) {
			lampPost(ox + 25, oy, oz + z);
			lampPost(ox + 31, oy, oz + z);
		}

		market(ox, oy, oz);
		taxOffice(ox, oy, oz);
		garden(ox, oy, oz);
		hotel(ox, oy, oz);
		eastWing(ox, oy, oz);
		keep(ox, oy, oz);
		parliamentQuarter(ox, oy, oz);
		tvsAndNewsstand(ox, oy, oz);
	}

	// ------------------------------------------------------------------ ТВ и газеты

	private static void tvsAndNewsstand(int ox, int oy, int oz) {
		for (int[][] tv : tvScreens()) for (int[] b : tv) set(ox + b[0], oy + b[1], oz + b[2], Blocks.BLACK_CONCRETE);
		set(ox + 4, oy, oz + 7, Blocks.BARREL);
	}

	// ------------------------------------------------------------------ парламентский квартал

	private static void parliamentQuarter(int ox, int oy, int oz) {
		int zq0 = SZ, zq1 = SZ + 39; // 45..84
		site(ox - 5, oz + zq0, ox + SX + 4, oz + zq1 + 4, oy, 30, Blocks.GRASS_BLOCK, Blocks.STONE_BRICKS);
		// южные башни замка частично попали под расчистку — отстраиваем заново
		tower(ox, oy, oz + SZ - 1, 1, -1);
		tower(ox + SX - 1, oy, oz + SZ - 1, -1, -1);
		// стены квартала
		for (int x = 0; x < SX; x++) {
			for (int z = zq0; z <= zq1; z++) {
				boolean edge = x == 0 || x == SX - 1 || z == zq1;
				boolean ring = !edge && (x == 1 || x == SX - 2 || z == zq1 - 1);
				if (edge) {
					for (int y = oy; y <= oy + 9; y++) set(ox + x, y, oz + z, wallBlock());
					if ((x + z) % 2 == 0) set(ox + x, oy + 10, oz + z, wallBlock());
				} else if (ring) {
					set(ox + x, oy + 8, oz + z, Blocks.DARK_OAK_PLANKS);
				}
			}
		}
		tower(ox, oy, oz + zq1, 1, -1);
		tower(ox + SX - 1, oy, oz + zq1, -1, -1);
		// проходы из замка
		fill(ox + 15, oy, oz + SZ - 1, ox + 17, oy + 4, oz + SZ - 1, Blocks.AIR);
		fill(ox + 39, oy, oz + SZ - 1, ox + 41, oy + 4, oz + SZ - 1, Blocks.AIR);
		fill(ox + 15, oy - 1, oz + 43, ox + 17, oy - 1, oz + 48, Blocks.POLISHED_ANDESITE);
		fill(ox + 39, oy - 1, oz + 43, ox + 41, oy - 1, oz + 48, Blocks.POLISHED_ANDESITE);
		fill(ox + 15, oy - 1, oz + 46, ox + 41, oy - 1, oz + 48, Blocks.POLISHED_ANDESITE);
		for (int z : new int[] {60, 70, 80}) {
			lampPost(ox + 6, oy, oz + z);
			lampPost(ox + 50, oy, oz + z);
		}

		// --- здание парламента x 10..46, z 50..80
		int x0 = ox + 10, x1 = ox + 46, z0 = oz + 50, z1 = oz + 80, h = 11;
		fill(x0, oy - 1, z0, x1, oy - 1, oz + 56, Blocks.POLISHED_ANDESITE);
		fill(x0, oy - 1, oz + 57, x1, oy - 1, z1, Blocks.DARK_OAK_PLANKS);
		walls(x0, oy, z0, x1, oy + h, z1, Blocks.QUARTZ_BRICKS);
		for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) fill(c[0], oy, c[1], c[0], oy + h, c[1], Blocks.QUARTZ_PILLAR);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean glass = x >= ox + 22 && x <= ox + 34 && z >= oz + 64 && z <= oz + 76;
				boolean lamp = !glass && (x - x0) % 4 == 2 && (z - z0) % 4 == 2;
				set(x, oy + h + 1, z, glass ? Blocks.GLASS.getDefaultState()
						: lamp ? Blocks.GLOWSTONE.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
			}
		}
		// фасад: вход, портик, герб
		fill(ox + 26, oy, z0, ox + 30, oy + 4, z0, Blocks.AIR);
		for (int x : new int[] {22, 25, 31, 34}) fill(ox + x, oy, oz + 47, ox + x, oy + 7, oz + 47, Blocks.QUARTZ_PILLAR);
		fill(ox + 21, oy + 8, oz + 47, ox + 35, oy + 8, oz + 49, Blocks.QUARTZ_SLAB);
		fill(ox + 26, oy + 6, z0, ox + 30, oy + 8, z0, Blocks.RED_WOOL);
		set(ox + 28, oy + 7, z0, Blocks.GOLD_BLOCK);
		for (int x = 2; x <= 34; x += 4) set(x0 + x, oy + 6, z0, Blocks.GLASS);

		// --- фойе (z 51..56): ЦИК, кабинки, урна, ТВ, лидеры партий
		fill(ox + 11, oy, oz + 57, ox + 45, oy + h, oz + 57, Blocks.QUARTZ_BRICKS);
		fill(ox + 13, oy, oz + 57, ox + 15, oy + 3, oz + 57, Blocks.AIR);
		fill(ox + 41, oy, oz + 57, ox + 43, oy + 3, oz + 57, Blocks.AIR);
		fill(ox + 12, oy, oz + 53, ox + 20, oy, oz + 53, Blocks.SPRUCE_PLANKS); // стол ЦИК
		set(ox + 19, oy, oz + 55, Blocks.BARREL); // урна
		set(ox + 19, oy + 1, oz + 55, Blocks.WHITE_CARPET);
		for (int bx : new int[] {22, 24}) {
			set(ox + bx - 1, oy, oz + 55, Blocks.WHITE_WOOL);
			set(ox + bx - 1, oy + 1, oz + 55, Blocks.WHITE_WOOL);
			set(ox + bx, oy, oz + 54, Blocks.WHITE_WOOL);
			set(ox + bx, oy + 1, oz + 54, Blocks.WHITE_WOOL);
			set(ox + bx, oy + 2, oz + 55, Blocks.RED_WOOL);
		}
		for (int x = 35; x <= 41; x++) set(ox + x, oy, oz + 55, B.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
		B.chandelier(ox + 18, oy + h + 1, oz + 54, 2);
		B.chandelier(ox + 38, oy + h + 1, oz + 54, 2);

		// --- зал заседаний (z 58..79)
		fill(ox + 24, oy, oz + 58, ox + 32, oy, oz + 60, Blocks.QUARTZ_BLOCK); // трибуна спикера
		fill(ox + 27, oy + 1, oz + 60, ox + 29, oy + 1, oz + 60, Blocks.DARK_OAK_PLANKS);
		set(ox + 28, oy, oz + 62, Blocks.LECTERN);
		// королевская ложа
		fill(ox + 34, oy, oz + 58, ox + 38, oy + 1, oz + 60, Blocks.QUARTZ_BLOCK);
		fill(ox + 34, oy + 2, oz + 58, ox + 38, oy + 2, oz + 60, Blocks.GOLD_BLOCK);
		set(ox + 36, oy + 3, oz + 58, B.stairs(Blocks.QUARTZ_STAIRS, Direction.NORTH));
		set(ox + 34, oy + 3, oz + 60, Blocks.LANTERN);
		set(ox + 38, oy + 3, oz + 60, Blocks.LANTERN);
		// амфитеатр: 7 рядов по 30 мест, поднимается к югу
		for (int r = 0; r < 7; r++) {
			int z = oz + 64 + 2 * r, hh = r;
			if (hh > 0) fill(ox + 11, oy, z, ox + 45, oy + hh - 1, z + 1, Blocks.DARK_OAK_PLANKS);
			for (int x = 12; x <= 44; x++) {
				if (x == 20 || x == 28 || x == 36) continue;
				set(ox + x, oy + hh, z, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH));
				set(ox + x, oy + hh, z + 1, Blocks.YELLOW_WOOL);
			}
		}
		fill(ox + 11, oy, oz + 78, ox + 45, oy + 6, oz + 79, Blocks.DARK_OAK_PLANKS);
		// окна и флаги партий
		for (int z : new int[] {60, 66, 72, 78}) {
			fill(x0, oy + 7, oz + z, x0, oy + 9, oz + z, Blocks.GLASS);
			fill(x1, oy + 7, oz + z, x1, oy + 9, oz + z, Blocks.GLASS);
		}
		for (int i = 0; i < Elections.PARTIES.size(); i++) {
			Block wool = Elections.PARTIES.get(i).wool();
			fill(x0, oy + 3, oz + 61 + 2 * i, x0, oy + 6, oz + 61 + 2 * i, wool);
			fill(x1, oy + 3, oz + 61 + 2 * i, x1, oy + 6, oz + 61 + 2 * i, wool);
		}
		for (int[] c : new int[][] {{20, 68}, {28, 70}, {36, 68}, {28, 64}}) B.chandelier(ox + c[0], oy + h + 1, oz + c[1], 2);
	}

	/** Состав парламента до первых выборов. */
	private static void initSeats(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		int sum = 0;
		for (int v : st.seats) sum += v;
		if (sum == 0) st.seats = new int[] {152, 13, 11, 10, 9, 8, 7};
		st.markDirty();
		paintSeats(s);
	}

	/** Перекрашивает спинки кресел по итогам выборов: сектора партий слева направо. */
	public static void paintSeats(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		w = s.getOverworld();
		List<BlockPos> seats = new java.util.ArrayList<>();
		for (int x = 12; x <= 44; x++) {
			if (x == 20 || x == 28 || x == 36) continue;
			for (int r = 0; r < 7; r++) seats.add(new BlockPos(st.cx + x, st.cy + r, st.cz + 65 + 2 * r));
		}
		int idx = 0;
		for (int party = 0; party < Elections.PARTIES.size(); party++) {
			for (int k = 0; k < st.seats[party] && idx < seats.size(); k++, idx++) {
				BlockPos b = seats.get(idx);
				set(b.getX(), b.getY(), b.getZ(), Elections.PARTIES.get(party).wool());
			}
		}
	}

	private static void spawnParliamentNpcs(ServerWorld world, PizdecState st) {
		int ox = st.cx, oy = st.cy, oz = st.cz;
		npc(world, ox + 28.5, oy + 1, oz + 58.5, 0f, SPEAKER_TAG, U.t("🏛 Спикер парламента Аркадий Голосуев", Formatting.GOLD),
				VillagerProfession.CLERIC);
		npc(world, ox + 16.5, oy, oz + 51.5, 0f, CEC_TAG, U.t("🗳 Председатель ЦИК Нина Подсчётова", Formatting.WHITE),
				VillagerProfession.LIBRARIAN);
		for (int i = 0; i < Elections.PARTIES.size(); i++) {
			Elections.Party pt = Elections.PARTIES.get(i);
			VillagerEntity v = npc(world, ox + 33.5 + 2 * i, oy, oz + 52.5, 0f, PARTY_TAG,
					U.t(pt.leader() + " («" + pt.name() + "»)", pt.color()), VillagerProfession.NITWIT);
			if (v != null) v.addCommandTag("pizdec_party_" + i);
		}
	}

	private static void tower(int cx, int oy, int cz, int sx, int sz) {
		int h = 16, r = 4;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				boolean shell = Math.abs(dx) == r || Math.abs(dz) == r;
				for (int y = oy; y < oy + h; y++) set(cx + dx, y, cz + dz, shell ? wallBlock() : Blocks.AIR.getDefaultState());
				set(cx + dx, oy - 1, cz + dz, Blocks.STONE_BRICKS);
				set(cx + dx, oy + 8, cz + dz, shell ? wallBlock() : Blocks.SPRUCE_PLANKS.getDefaultState());
				set(cx + dx, oy + h, cz + dz, Blocks.STONE_BRICKS);
				if (shell && (dx + dz) % 2 == 0) set(cx + dx, oy + h + 1, cz + dz, wallBlock());
			}
		}
		for (int y = oy; y <= oy + 2; y++) set(cx + r * sx, y, cz + 2 * sz, Blocks.AIR);
		Direction face = sx > 0 ? Direction.EAST : Direction.WEST;
		BlockState ladder = Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, face);
		for (int y = oy; y <= oy + h; y++) set(cx - (r - 1) * sx, y, cz, ladder);
		set(cx, oy, cz, Blocks.LANTERN);
		set(cx, oy + 9, cz, Blocks.LANTERN);
		for (int y = oy + h + 1; y <= oy + h + 5; y++) set(cx, y, cz, Blocks.OAK_FENCE);
		for (int i = 1; i <= 3; i++) {
			set(cx + i * sx, oy + h + 5, cz, Blocks.RED_WOOL);
			set(cx + i * sx, oy + h + 4, cz, i == 2 ? Blocks.YELLOW_WOOL : Blocks.RED_WOOL);
		}
	}

	private static void fountain(int cx, int oy, int cz) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				set(cx + dx, oy - 1, cz + dz, Blocks.STONE_BRICKS);
				set(cx + dx, oy, cz + dz, rim ? Blocks.STONE_BRICK_WALL : Blocks.WATER);
			}
		}
		for (int y = oy; y <= oy + 2; y++) set(cx, y, cz, Blocks.QUARTZ_PILLAR);
		set(cx, oy + 3, cz, Blocks.GOLD_BLOCK);
		set(cx, oy + 4, cz, Blocks.LANTERN);
	}

	/** Торговые ряды у северной стены: магазин и казино с апгрейдером. */
	private static void market(int ox, int oy, int oz) {
		stall(ox + 13, oz + 2, ox + 21, oz + 5, oy, Blocks.LIME_TERRACOTTA);
		stall(ox + 34, oz + 2, ox + 42, oz + 5, oy, Blocks.RED_TERRACOTTA);
		// товары и декор
		set(ox + 14, oy, oz + 3, Blocks.BARREL);
		set(ox + 20, oy, oz + 3, Blocks.BARREL);
		set(ox + 14, oy + 1, oz + 3, Blocks.MELON);
		set(ox + 20, oy + 1, oz + 3, Blocks.PUMPKIN);
		set(ox + 35, oy, oz + 3, Blocks.GOLD_BLOCK);
		set(ox + 41, oy, oz + 3, Blocks.JUKEBOX);
		set(ox + 38, oy, oz + 3, Blocks.ENCHANTING_TABLE);
	}

	private static void stall(int x0, int z0, int x1, int z1, int oy, Block roof) {
		fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.SPRUCE_PLANKS);
		fill(x0, oy, z0, x1, oy + 3, z0, Blocks.SPRUCE_PLANKS);
		fill(x0, oy, z0, x0, oy + 3, z1, Blocks.SPRUCE_PLANKS);
		fill(x1, oy, z0, x1, oy + 3, z1, Blocks.SPRUCE_PLANKS);
		fill(x0, oy + 4, z0, x1, oy + 4, z1 + 1, roof);
		fill(x0 + 1, oy, z1 - 1, x1 - 1, oy, z1 - 1, Blocks.SPRUCE_SLAB); // прилавок
		set(x0 + 1, oy + 3, z1, Blocks.LANTERN);
		set(x1 - 1, oy + 3, z1, Blocks.LANTERN);
	}

	private static void taxOffice(int ox, int oy, int oz) {
		int x0 = ox + 16, z0 = oz + 15, x1 = ox + 22, z1 = oz + 21;
		fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.POLISHED_ANDESITE);
		walls(x0, oy, z0, x1, oy + 4, z1, Blocks.BRICKS);
		fill(x0, oy + 5, z0, x1, oy + 5, z1, Blocks.DARK_OAK_PLANKS);
		fill(ox + 19, oy, z0, ox + 19, oy + 1, z0, Blocks.AIR);
		set(ox + 17, oy + 2, z0, Blocks.IRON_BARS);
		set(ox + 21, oy + 2, z0, Blocks.IRON_BARS);
		fill(ox + 17, oy, oz + 17, ox + 21, oy, oz + 17, Blocks.DARK_OAK_PLANKS); // стойка
		set(ox + 17, oy, oz + 20, Blocks.BARREL);
		set(ox + 21, oy, oz + 20, Blocks.BOOKSHELF);
		set(ox + 19, oy + 4, oz + 18, B.hangingLantern());
	}

	private static void garden(int ox, int oy, int oz) {
		for (int x = 33; x <= 39; x++) {
			for (int z = 14; z <= 20; z++) {
				boolean edge = x == 33 || x == 39 || z == 14 || z == 20;
				if (edge) {
					set(ox + x, oy - 1, oz + z, Blocks.GRASS_BLOCK);
					set(ox + x, oy, oz + z, B.leaves());
				} else {
					set(ox + x, oy - 1, oz + z, z == 17 ? Blocks.WATER : Blocks.FARMLAND);
					if (z != 17) set(ox + x, oy, oz + z, U.pick(Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.ALLIUM));
				}
			}
		}
		fill(ox + 36, oy, oz + 14, ox + 36, oy, oz + 14, Blocks.AIR);
	}

	// ------------------------------------------------------------------ отель

	private static void hotel(int ox, int oy, int oz) {
		int x0 = ox + 2, x1 = ox + 14, z0 = oz + 6, z1 = oz + 42;
		int y2 = oy + 6; // пол второго этажа (стоять на y2)
		fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.SPRUCE_PLANKS);
		walls(x0, oy, z0, x1, oy + 10, z1, Blocks.BRICKS);
		fill(x0 + 1, oy + 5, z0 + 1, x1 - 1, oy + 5, z1 - 1, Blocks.DARK_OAK_PLANKS); // перекрытие
		fill(x0, oy + 11, z0, x1, oy + 11, z1, Blocks.SPRUCE_PLANKS); // крыша
		fill(ox + 4, oy + 11, oz + 27, ox + 12, oy + 11, oz + 40, Blocks.GLASS); // стеклянный потолок люкса
		// вывеска «ОТЕЛЬ» из золота над входом
		fill(x1, oy + 4, oz + 10, x1, oy + 4, oz + 13, Blocks.GOLD_BLOCK);
		// окна во двор
		for (int z = z0 + 3; z < z1; z += 3) {
			set(x1, oy + 2, z, Blocks.GLASS_PANE);
			set(x1, oy + 8, z, Blocks.GLASS_PANE);
		}
		// вход в лобби
		fill(x1, oy, oz + 11, x1, oy + 2, oz + 12, Blocks.AIR);

		// --- лобби (1 этаж, z 7..13)
		fill(ox + 3, oy - 1, oz + 7, ox + 13, oy - 1, oz + 13, Blocks.POLISHED_ANDESITE);
		fill(ox + 9, oy, oz + 9, ox + 12, oy, oz + 9, Blocks.DARK_OAK_PLANKS); // ресепшен
		set(ox + 13, oy, oz + 7, Blocks.BOOKSHELF);
		set(ox + 13, oy + 1, oz + 7, Blocks.BOOKSHELF);
		set(ox + 6, oy, oz + 13, Blocks.POTTED_FERN);
		set(ox + 12, oy, oz + 13, Blocks.POTTED_RED_TULIP);
		fill(ox + 5, oy, oz + 11, ox + 8, oy, oz + 12, Blocks.RED_CARPET);
		B.chandelier(ox + 8, oy + 5, oz + 10, 1);
		// лестница на 2 этаж вдоль западной стены
		for (int i = 0; i <= 5; i++) set(ox + 3, oy + i, oz + 8 + i, B.stairs(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH));
		fill(ox + 3, oy + 5, oz + 8, ox + 3, oy + 5, oz + 12, Blocks.AIR);
		fill(ox + 3, oy + 6, oz + 8, ox + 3, oy + 7, oz + 12, Blocks.AIR);

		// --- 1 этаж: коридор x 7..9, стены коридора x=6 и x=10, перегородка z=14
		fill(ox + 3, oy, oz + 14, ox + 13, oy + 4, oz + 14, Blocks.BRICKS);
		fill(ox + 7, oy, oz + 14, ox + 9, oy + 2, oz + 14, Blocks.AIR);
		fill(ox + 6, oy, oz + 14, ox + 6, oy + 4, oz + 38, Blocks.BRICKS);
		fill(ox + 10, oy, oz + 14, ox + 10, oy + 4, oz + 38, Blocks.BRICKS);
		fill(ox + 7, oy - 1, oz + 14, ox + 9, oy - 1, oz + 41, Blocks.RED_TERRACOTTA);
		for (int z = 17; z <= 39; z += 5) B.chandelier(ox + 8, oy + 5, oz + z, 0);
		// эконом (запад): 6 капсул 3×3
		for (int i = 0; i < 6; i++) {
			int zr = oz + 15 + 4 * i;
			fill(ox + 3, oy, zr - 1, ox + 5, oy + 4, zr - 1, Blocks.BRICKS);
			fill(ox + 3, oy, zr + 3, ox + 5, oy + 4, zr + 3, Blocks.BRICKS);
			fill(ox + 3, oy - 1, zr, ox + 5, oy - 1, zr + 2, Blocks.GRAY_WOOL);
			B.door(ox + 6, oy, zr + 1, Direction.EAST, Blocks.SPRUCE_DOOR);
			B.bed(ox + 4, oy, zr, Direction.WEST, Blocks.GRAY_BED);
			set(ox + 5, oy, zr + 2, Blocks.LANTERN);
		}
		// стандарт (восток): 4 номера 3×5
		for (int i = 0; i < 4; i++) {
			int zr = oz + 15 + 6 * i;
			fill(ox + 11, oy, zr - 1, ox + 13, oy + 4, zr - 1, Blocks.BRICKS);
			fill(ox + 11, oy, zr + 5, ox + 13, oy + 4, zr + 5, Blocks.BRICKS);
			fill(ox + 11, oy - 1, zr, ox + 13, oy - 1, zr + 4, Blocks.LIGHT_BLUE_WOOL);
			B.door(ox + 10, oy, zr + 2, Direction.WEST, Blocks.OAK_DOOR);
			B.bed(ox + 12, oy, zr, Direction.EAST, Blocks.LIGHT_BLUE_BED);
			set(ox + 13, oy, zr + 4, Blocks.CHEST);
			set(ox + 11, oy, zr + 4, Blocks.POTTED_BLUE_ORCHID);
			set(ox + 12, oy + 4, zr + 2, B.hangingLantern());
		}
		// санузел в конце коридора
		set(ox + 4, oy, oz + 40, Blocks.CAULDRON);
		set(ox + 12, oy, oz + 40, Blocks.WATER_CAULDRON);
		set(ox + 8, oy + 4, oz + 40, B.hangingLantern());

		// --- 2 этаж: площадка z 7..13, перегородка z=14, коридор z 15..24, перегородка z=25
		fill(ox + 3, y2, oz + 14, ox + 13, y2 + 3, oz + 14, Blocks.BRICKS);
		fill(ox + 7, y2, oz + 14, ox + 9, y2 + 2, oz + 14, Blocks.AIR);
		fill(ox + 6, y2, oz + 15, ox + 6, y2 + 3, oz + 24, Blocks.BRICKS);
		fill(ox + 10, y2, oz + 15, ox + 10, y2 + 3, oz + 24, Blocks.BRICKS);
		fill(ox + 3, y2, oz + 25, ox + 13, y2 + 3, oz + 25, Blocks.BRICKS);
		fill(ox + 7, y2 - 1, oz + 7, ox + 13, y2 - 1, oz + 13, Blocks.RED_WOOL);
		fill(ox + 7, y2 - 1, oz + 15, ox + 9, y2 - 1, oz + 24, Blocks.RED_WOOL);
		B.chandelier(ox + 8, oy + 11, oz + 10, 1);
		B.chandelier(ox + 8, oy + 11, oz + 20, 1);
		// люкс А (запад) и люкс Б (восток): 3×10
		for (int side = 0; side < 2; side++) {
			int xa = side == 0 ? ox + 3 : ox + 11, xb = xa + 2;
			fill(xa, y2 - 1, oz + 15, xb, y2 - 1, oz + 24, Blocks.PURPLE_WOOL);
			B.door(side == 0 ? ox + 6 : ox + 10, y2, oz + 19, side == 0 ? Direction.EAST : Direction.WEST, Blocks.DARK_OAK_DOOR);
			Direction head = side == 0 ? Direction.WEST : Direction.EAST;
			int bx = side == 0 ? xa + 1 : xb - 1;
			B.bed(bx, y2, oz + 15, head, Blocks.PURPLE_BED);
			B.bed(bx, y2, oz + 16, head, Blocks.PURPLE_BED);
			int wallX = side == 0 ? xa : xb;
			fill(wallX, y2, oz + 22, wallX, y2 + 2, oz + 24, Blocks.BOOKSHELF);
			set(side == 0 ? xb : xa, y2, oz + 24, Blocks.CHEST);
			B.table(xa + 1, y2, oz + 20);
			set(xa + 1, y2 + 3, oz + 18, Blocks.GLOWSTONE);
			set(side == 0 ? xb : xa, y2, oz + 15, Blocks.POTTED_WITHER_ROSE);
		}
		// королевский люкс (президентский): z 26..41, x 3..13
		B.door(ox + 8, y2, oz + 25, Direction.NORTH, Blocks.IRON_DOOR);
		fill(ox + 3, y2 - 1, oz + 26, ox + 13, y2 - 1, oz + 41, Blocks.WHITE_CONCRETE);
		fill(ox + 5, y2 - 1, oz + 28, ox + 11, y2 - 1, oz + 39, Blocks.RED_CARPET.getDefaultState());
		for (int x = 3; x <= 13; x += 10) for (int z = 26; z <= 41; z += 15) set(ox + x, y2 - 1, oz + z, Blocks.GOLD_BLOCK);
		set(ox + 8, y2 - 1, oz + 27, Blocks.WHITE_CONCRETE);
		B.bed(ox + 7, y2, oz + 40, Direction.SOUTH, Blocks.RED_BED);
		B.bed(ox + 9, y2, oz + 40, Direction.SOUTH, Blocks.RED_BED);
		fill(ox + 3, y2, oz + 30, ox + 3, y2 + 3, oz + 36, Blocks.BOOKSHELF);
		set(ox + 4, y2, oz + 33, Blocks.ENCHANTING_TABLE);
		set(ox + 13, y2, oz + 30, Blocks.JUKEBOX);
		set(ox + 13, y2, oz + 32, Blocks.ENDER_CHEST);
		set(ox + 13, y2, oz + 34, Blocks.CHEST);
		set(ox + 13, y2, oz + 35, Blocks.CHEST);
		set(ox + 12, y2, oz + 41, Blocks.POTTED_BAMBOO);
		set(ox + 4, y2, oz + 41, Blocks.POTTED_BAMBOO);
		set(ox + 8, y2, oz + 34, Blocks.IRON_BLOCK);
		set(ox + 8, y2 + 1, oz + 34, Blocks.BEACON);
		B.table(ox + 6, y2, oz + 30);
		B.table(ox + 10, y2, oz + 30);
		B.chandelier(ox + 8, oy + 11, oz + 30, 1);
		B.chandelier(ox + 8, oy + 11, oz + 38, 1);
	}

	// ------------------------------------------------------------------ восточное крыло

	private static void eastWing(int ox, int oy, int oz) {
		int x0 = ox + 42, x1 = ox + 54, z0 = oz + 6, z1 = oz + 42;
		fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.POLISHED_ANDESITE);
		walls(x0, oy, z0, x1, oy + 6, z1, Blocks.STONE_BRICKS);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean lamp = (x - x0) % 4 == 2 && (z - z0) % 4 == 2;
				set(x, oy + 7, z, lamp ? Blocks.GLOWSTONE : Blocks.DARK_OAK_PLANKS);
			}
		}
		fill(x0 + 1, oy, oz + 18, x1 - 1, oy + 6, oz + 18, Blocks.STONE_BRICKS);
		fill(x0 + 1, oy, oz + 30, x1 - 1, oy + 6, oz + 30, Blocks.STONE_BRICKS);
		fill(x0, oy, oz + 11, x0, oy + 2, oz + 12, Blocks.AIR);
		fill(x0, oy, oz + 23, x0, oy + 2, oz + 24, Blocks.AIR);
		fill(x0, oy, oz + 32, x0, oy + 2, oz + 33, Blocks.AIR);
		for (int z = 8; z <= 40; z += 4) set(x0, oy + 3, oz + z, Blocks.GLASS_PANE);

		// биржа труда (z 7..17)
		fill(x0 + 1, oy - 1, oz + 7, x1 - 1, oy - 1, oz + 17, Blocks.OAK_PLANKS);
		fill(ox + 46, oy, oz + 10, ox + 51, oy, oz + 10, Blocks.SPRUCE_PLANKS);
		fill(ox + 53, oy, oz + 7, ox + 53, oy + 2, oz + 17, Blocks.BOOKSHELF);
		set(ox + 47, oy, oz + 14, Blocks.LECTERN);
		set(ox + 50, oy, oz + 14, Blocks.LECTERN);
		B.table(ox + 44, oy, oz + 15);
		B.table(ox + 44, oy, oz + 9);
		fill(ox + 46, oy + 3, oz + 7, ox + 51, oy + 3, oz + 7, Blocks.BROWN_WOOL); // «доска объявлений»

		// казарма стражи (z 19..29)
		fill(x0 + 1, oy - 1, oz + 19, x1 - 1, oy - 1, oz + 29, Blocks.STONE_BRICKS);
		for (int z = 20; z <= 28; z += 2) B.bed(ox + 52, oy, oz + z, Direction.EAST, Blocks.BLUE_BED);
		for (int z = 20; z <= 28; z++) set(ox + 44, oy, oz + z, z % 2 == 0 ? Blocks.SPRUCE_FENCE : Blocks.CHEST);
		set(ox + 48, oy, oz + 25, Blocks.HAY_BLOCK); // чучело
		set(ox + 48, oy + 1, oz + 25, Blocks.CARVED_PUMPKIN);
		set(ox + 48, oy, oz + 21, Blocks.SMITHING_TABLE);
		set(ox + 49, oy, oz + 21, Blocks.GRINDSTONE);
		set(ox + 50, oy, oz + 21, Blocks.ANVIL);

		// казначейство (z 31..41)
		fill(x0 + 1, oy - 1, oz + 31, x1 - 1, oy - 1, oz + 41, Blocks.POLISHED_DEEPSLATE);
		fill(x0 + 1, oy, oz + 35, x1 - 1, oy + 3, oz + 35, Blocks.IRON_BARS); // кассы
		fill(ox + 47, oy, oz + 35, ox + 49, oy, oz + 35, Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int x = 43; x <= 53; x++) {
			for (int z = 39; z <= 41; z++) {
				if ((x + z) % 3 == 0) set(ox + x, oy, oz + z, Blocks.GOLD_BLOCK);
				else if ((x + z) % 3 == 1) set(ox + x, oy, oz + z, Blocks.EMERALD_BLOCK);
				else set(ox + x, oy, oz + z, Blocks.CHEST);
			}
		}
		set(ox + 48, oy + 1, oz + 40, Blocks.DIAMOND_BLOCK);
	}

	// ------------------------------------------------------------------ тронный зал

	private static void keep(int ox, int oy, int oz) {
		int x0 = ox + 18, x1 = ox + 38, z0 = oz + 28, z1 = oz + 42, h = 12;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
				set(x, oy - 1, z, (x + z) % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.BLACK_CONCRETE);
				if (edge) {
					for (int y = oy; y <= oy + h; y++) set(x, y, z, corner ? Blocks.GOLD_BLOCK : Blocks.QUARTZ_BRICKS);
					if ((x + z) % 2 == 0) set(x, oy + h + 2, z, Blocks.QUARTZ_BRICKS);
				}
				set(x, oy + h + 1, z, Blocks.DARK_OAK_PLANKS);
			}
		}
		fill(ox + 26, oy, z0, ox + 30, oy + 5, z0, Blocks.AIR);
		fill(ox + 25, oy + 6, z0, ox + 31, oy + 6, z0, Blocks.GOLD_BLOCK);
		for (int z = 31; z <= 40; z += 3) {
			fill(x0, oy + 3, oz + z, x0, oy + 7, oz + z, Blocks.YELLOW_STAINED_GLASS);
			fill(x1, oy + 3, oz + z, x1, oy + 7, oz + z, Blocks.YELLOW_STAINED_GLASS);
		}
		for (int[] c : new int[][] {{21, 31}, {35, 31}, {21, 35}, {35, 35}, {21, 39}, {35, 39}}) {
			fill(ox + c[0], oy, oz + c[1], ox + c[0], oy + h, oz + c[1], Blocks.QUARTZ_PILLAR);
		}
		fill(ox + 27, oy, oz + 29, ox + 29, oy, oz + 38, Blocks.RED_CARPET);
		// трон
		fill(ox + 27, oy, oz + 40, ox + 29, oy, oz + 40, Blocks.GOLD_BLOCK);
		set(ox + 27, oy + 1, oz + 40, Blocks.GOLD_BLOCK);
		set(ox + 29, oy + 1, oz + 40, Blocks.GOLD_BLOCK);
		fill(ox + 27, oy, oz + 41, ox + 29, oy + 3, oz + 41, Blocks.GOLD_BLOCK);
		set(ox + 28, oy + 4, oz + 41, Blocks.DIAMOND_BLOCK);
		// знамёна
		for (int x : new int[] {24, 32}) {
			fill(ox + x, oy + 3, oz + 41, ox + x, oy + 9, oz + 41, Blocks.RED_WOOL);
			set(ox + x, oy + 6, oz + 41, Blocks.GOLD_BLOCK);
		}
		// казна у трона
		fill(ox + 20, oy, oz + 40, ox + 21, oy, oz + 41, Blocks.GOLD_BLOCK);
		set(ox + 20, oy + 1, oz + 41, Blocks.GOLD_BLOCK);
		fill(ox + 35, oy, oz + 40, ox + 36, oy, oz + 41, Blocks.EMERALD_BLOCK);
		// стол секретаря
		fill(ox + 24, oy, oz + 38, ox + 25, oy, oz + 38, Blocks.DARK_OAK_PLANKS);
		set(ox + 24, oy + 1, oz + 38, Blocks.LANTERN);
		// люстры
		B.chandelier(ox + 24, oy + h, oz + 33, 2);
		B.chandelier(ox + 32, oy + h, oz + 33, 2);
		B.chandelier(ox + 28, oy + h, oz + 36, 2);
		B.chandelier(ox + 28, oy + h, oz + 30, 2);
	}

	// ------------------------------------------------------------------ жители

	private static void spawnNpcs(ServerWorld world, PizdecState st) {
		int ox = st.cx, oy = st.cy, oz = st.cz;
		spawnKing(world, st);
		npc(world, ox + 25.5, oy, oz + 39.5, 180f, SECRETARY_TAG, U.t("📜 Секретарь короля Аполлинарий", Formatting.GOLD),
				VillagerProfession.CARTOGRAPHER);
		npc(world, ox + 32.5, oy, oz + 37.5, 90f, JESTER_TAG, U.t("Шут Валера", Formatting.LIGHT_PURPLE), VillagerProfession.NITWIT);
		npc(world, ox + 19.5, oy, oz + 18.5, 0f, INSPECTOR_TAG, U.t("Налоговый инспектор Зинаида Петровна", Formatting.RED),
				VillagerProfession.LIBRARIAN);
		npc(world, ox + 17.5, oy, oz + 3.5, 0f, SHOP_TAG, U.t("🛒 Торговец Жорик", Formatting.GREEN), VillagerProfession.FARMER);
		npc(world, ox + 36.5, oy, oz + 3.5, 0f, CASINO_TAG, U.t("🎰 Крупье Анжела", Formatting.RED), VillagerProfession.CARTOGRAPHER);
		npc(world, ox + 40.5, oy, oz + 3.5, 0f, UPGRADER_TAG, U.t("⬆ Апгрейдер Дима", Formatting.LIGHT_PURPLE),
				VillagerProfession.TOOLSMITH);
		npc(world, ox + 48.5, oy, oz + 8.5, 0f, HR_TAG, U.t("💼 Кадровичка Людмила", Formatting.YELLOW), VillagerProfession.LIBRARIAN);
		npc(world, ox + 10.5, oy, oz + 7.5, 0f, RECEPTION_TAG, U.t("🏨 Администратор Светлана", Formatting.AQUA),
				VillagerProfession.CLERIC);
		npc(world, ox + 46.5, oy, oz + 22.5, 270f, CAPTAIN_TAG, U.t("⚔ Капитан стражи Петрович", Formatting.BLUE),
				VillagerProfession.ARMORER);
		npc(world, ox + 48.5, oy, oz + 37.5, 180f, TREASURER_TAG, U.t("💰 Казначей Аркадий", Formatting.GOLD),
				VillagerProfession.CARTOGRAPHER);
		spawnParliamentNpcs(world, st);
		for (double gx : new double[] {25.5, 31.5}) {
			IronGolemEntity g = EntityType.IRON_GOLEM.create(world);
			if (g == null) continue;
			g.refreshPositionAndAngles(ox + gx, oy, oz - 4.5, 180f, 0f);
			g.setHeadYaw(180f);
			g.setBodyYaw(180f);
			g.setAiDisabled(true);
			g.setInvulnerable(true);
			g.setPersistent();
			g.setCustomName(U.t("Стражник у ворот", Formatting.GRAY));
			g.setCustomNameVisible(true);
			g.addCommandTag(GATE_GUARD_TAG);
			g.addCommandTag(VERSION_TAG);
			world.spawnEntity(g);
		}
	}

	public static void spawnKing(ServerWorld world, PizdecState st) {
		BlockPos t = thronePos(st);
		npc(world, t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 180f, KING_TAG,
				U.t("👑 " + KING_NAME, Formatting.GOLD, Formatting.BOLD), VillagerProfession.CLERIC);
	}

	static VillagerEntity npc(ServerWorld world, double x, double y, double z, float yaw, String tag, Text name,
			VillagerProfession prof) {
		VillagerEntity v = EntityType.VILLAGER.create(world);
		if (v == null) return null;
		v.refreshPositionAndAngles(x, y, z, yaw, 0f);
		v.setHeadYaw(yaw);
		v.setBodyYaw(yaw);
		v.setVillagerData(v.getVillagerData().withProfession(prof).withLevel(5));
		v.setAiDisabled(true);
		v.setInvulnerable(true);
		v.setPersistent();
		v.setCustomName(name);
		v.setCustomNameVisible(true);
		v.addCommandTag(tag);
		v.addCommandTag(VERSION_TAG);
		world.spawnEntity(v);
		return v;
	}

	/** Если короля убили (/kill и т.п.), а рядом есть игроки — сажаем нового. */
	public static VillagerEntity findKing(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return null;
		ServerWorld world = s.getOverworld();
		BlockPos t = thronePos(st);
		boolean someoneNear = false;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p.getServerWorld() == world && p.squaredDistanceTo(t.getX(), t.getY(), t.getZ()) < 48 * 48) someoneNear = true;
		}
		if (!someoneNear) return null;
		List<VillagerEntity> kings = world.getEntitiesByClass(VillagerEntity.class, new Box(t).expand(24),
				e -> e.getCommandTags().contains(KING_TAG) && e.getCommandTags().contains(VERSION_TAG));
		if (kings.isEmpty()) {
			spawnKing(world, st);
			U.say(s, U.t("👑 На трон взошёл новый Король Пиздец III. Старый, говорят, не заплатил налоги.", Formatting.GOLD));
			return null;
		}
		return kings.get(0);
	}
}
