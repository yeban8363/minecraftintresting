package com.nullspace.world;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Уровень 2 — PARKING. Бесконечная подземная парковка.
 *
 * Мир разбит на секции 32×32. Каждая шестая строка и каждый шестой столбец секций —
 * бесконечные прямые тоннели (с перекрёстками). Между тоннелями — огромные
 * открытые поля парковки 160×160 блоков: колонны, разметка, ряды машин.
 * В машинах никого нет. У некоторых включены фары.
 *
 * Секция парковки (локальные координаты 0..31):
 *   lz 1..5   — проход у задних стенок машин;
 *   lz 6..10  — северный ряд машин (смотрят на юг, в проезд);
 *   lz 13..18 — проезд;
 *   lz 21..25 — южный ряд машин (смотрят на север);
 *   колонны — каждые 16 блоков на линиях разметки.
 *
 * Тоннель: проезжая часть шириной 8, высокий потолок, натриевые лампы,
 * из стен каждые 32 блока — проходы на парковку.
 */
public final class ParkingLayout {
	public static final int SECTION = 32;
	public static final int FLOOR_Y = 16;
	public static final int PARKING_CEILING_Y = 21;
	public static final int TUNNEL_CEILING_Y = 23;
	public static final int TOP_Y = 25;
	public static final int HEIGHT = TOP_Y + 1;
	private static final int TUNNEL_EVERY = 6;

	public enum Section { PARKING, EMPTY, TUNNEL_EW, TUNNEL_NS, CROSS }

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final Block[] CAR_COLORS = {
			Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.BLACK_CONCRETE,
			Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.BROWN_CONCRETE};
	private static final Block[] BANDS = {
			Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.YELLOW_CONCRETE,
			Blocks.ORANGE_CONCRETE, Blocks.PURPLE_CONCRETE};

	private final long seed;

	public ParkingLayout(long seed) {
		this.seed = seed;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	double rand(int a, int b, int salt) {
		long h = seed ^ 0x2545F4914F6CDD1DL;
		h = mix(h + 0x9E3779B97F4A7C15L * a);
		h = mix(h + 0xC2B2AE3D27D4EB4FL * b);
		h = mix(h + 0x165667B19E3779F9L * salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	public Section sectionAt(int sx, int sz) {
		boolean row = Math.floorMod(sz, TUNNEL_EVERY) == 3;
		boolean col = Math.floorMod(sx, TUNNEL_EVERY) == 3;
		if (row && col) return Section.CROSS;
		if (row) return Section.TUNNEL_EW;
		if (col) return Section.TUNNEL_NS;
		return rand(sx, sz, 1) < 0.82 ? Section.PARKING : Section.EMPTY;
	}

	// ---------------------------------------------------------------------

	public BlockState[] column(int x, int z) {
		int sx = Math.floorDiv(x, SECTION), lx = Math.floorMod(x, SECTION);
		int sz = Math.floorDiv(z, SECTION), lz = Math.floorMod(z, SECTION);
		Section section = sectionAt(sx, sz);

		BlockState[] s = new BlockState[HEIGHT];
		BlockState concrete = ModBlocks.PARKING_CONCRETE.getDefaultState();
		for (int y = 0; y < HEIGHT; y++) s[y] = concrete;
		s[FLOOR_Y] = ModBlocks.ASPHALT.getDefaultState();

		switch (section) {
			case PARKING, EMPTY -> parking(s, sx, sz, lx, lz, section == Section.EMPTY);
			case TUNNEL_EW -> tunnel(s, sx, sz, lx, lz, true);
			case TUNNEL_NS -> tunnel(s, sz, sx, lz, lx, false);
			case CROSS -> cross(s, lx, lz);
		}
		return s;
	}

	private void open(BlockState[] s, int ceiling) {
		for (int y = FLOOR_Y + 1; y < ceiling; y++) s[y] = AIR;
	}

	// ---------- парковка ----------

	private void parking(BlockState[] s, int sx, int sz, int lx, int lz, boolean empty) {
		open(s, PARKING_CEILING_Y);
		s[PARKING_CEILING_Y] = parkingCeiling(sx, sz, lx, lz, empty);

		// Колонны с цветной полосой секции и жёлто-чёрной лентой внизу.
		if (lx % 16 == 0 && (lz == 3 || lz == 12 || lz == 19 || lz == 28)) {
			for (int y = FLOOR_Y + 1; y < PARKING_CEILING_Y; y++) s[y] = ModBlocks.PARKING_CONCRETE.getDefaultState();
			s[FLOOR_Y + 1] = ModBlocks.HAZARD_STRIPE.getDefaultState();
			s[FLOOR_Y + 3] = BANDS[(int) (rand(sx, sz, 2) * BANDS.length)].getDefaultState();
			return;
		}

		// Разметка мест.
		boolean bayLine = lx % 4 == 0 && ((lz >= 5 && lz <= 11) || (lz >= 20 && lz <= 26));
		if (bayLine) s[FLOOR_Y] = ModBlocks.PAINT_WHITE.getDefaultState();
		if (!empty && (lz == 15 || lz == 16) && lx % 8 < 4) s[FLOOR_Y] = ModBlocks.PAINT_YELLOW.getDefaultState();

		// Машины.
		int cx = lx % 4 - 1;
		if (cx >= 0) {
			int bay = lx / 4;
			if (lz >= 6 && lz <= 10) car(s, sx * 8 + bay, sz * 2, cx, lz - 6, empty);
			else if (lz >= 21 && lz <= 25) car(s, sx * 8 + bay, sz * 2 + 1, cx, 25 - lz, empty);
		}

		// Находки в проходах.
		double r = rand(sx, sz, 30);
		if (lx == 2 && lz == 1 && r < (empty ? 0.25 : 0.06)) s[FLOOR_Y + 1] = ModBlocks.SUPPLY_CRATE.getDefaultState();
		if (lx == 30 && lz == 30 && rand(sx, sz, 31) < 0.12) s[FLOOR_Y + 1] = ModBlocks.PAPER_PILE.getDefaultState();
		if (empty && lx == 8 && lz == 1 && r < 0.2) s[FLOOR_Y + 1] = ModBlocks.GENERATOR.getDefaultState();
	}

	private BlockState parkingCeiling(int sx, int sz, int lx, int lz, boolean empty) {
		boolean slot = lx % 6 == 3 && (lz == 8 || lz == 15 || lz == 23);
		if (!slot) return ModBlocks.PARKING_CONCRETE.getDefaultState();
		int salt = 100 + lx * 31 + lz;
		if (rand(sx, sz, salt) >= (empty ? 0.3 : 0.65)) return ModBlocks.PARKING_CONCRETE.getDefaultState();
		double r = rand(sx, sz, salt + 500);
		if (r < (empty ? 0.5 : 0.18)) return ModBlocks.CEILING_LIGHT_OFF.getDefaultState();
		if (r < (empty ? 0.6 : 0.28)) return ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState();
		return ModBlocks.CEILING_LIGHT.getDefaultState();
	}

	/**
	 * Машина 3×5 блоков. cx — поперёк (0..2), cz — вдоль (0 — багажник, 4 — капот).
	 * @param bayA,bayB координаты парковочного места для случайности
	 */
	private void car(BlockState[] s, int bayA, int bayB, int cx, int cz, boolean empty) {
		if (rand(bayA, bayB, 60) >= (empty ? 0.05 : 0.55)) return;
		Block color = CAR_COLORS[(int) (rand(bayA, bayB, 61) * CAR_COLORS.length)];
		boolean lights = rand(bayA, bayB, 62) < 0.6;
		placeCar(s, color, lights, cx, cz);
	}

	private static void placeCar(BlockState[] s, Block color, boolean lights, int cx, int cz) {
		BlockState body = color.getDefaultState();
		BlockState low;
		if (cz == 4 && cx != 1) low = (lights ? ModBlocks.HEADLIGHT : ModBlocks.HEADLIGHT_OFF).getDefaultState();
		else if (cz == 4) low = Blocks.BLACK_CONCRETE.getDefaultState();
		else if (cz == 0 && cx != 1) low = ModBlocks.TAILLIGHT.getDefaultState();
		else low = body;
		s[FLOOR_Y + 1] = low;
		if (cz >= 1 && cz <= 3) s[FLOOR_Y + 2] = Blocks.GRAY_STAINED_GLASS.getDefaultState();
	}

	// ---------- тоннели ----------

	/**
	 * Тоннель вдоль оси «a». Для тоннеля север-юг координаты переданы переставленными.
	 * @param a,b    координаты секции (a — вдоль тоннеля)
	 * @param la,lb  локальные координаты (la — вдоль, lb — поперёк)
	 */
	private void tunnel(BlockState[] s, int a, int b, int la, int lb, boolean eastWest) {
		boolean road = lb >= 12 && lb <= 19;
		boolean wall = lb == 11 || lb == 20;
		boolean sidePassage = la >= 4 && la <= 9 && !road;

		if (road) {
			open(s, TUNNEL_CEILING_Y);
			if (lb == 15 && la % 8 < 4) s[FLOOR_Y] = ModBlocks.PAINT_YELLOW.getDefaultState();
			if (lb == 12 || lb == 19) s[FLOOR_Y] = ModBlocks.PAINT_WHITE.getDefaultState();
			boolean lampSlot = (lb == 15 || lb == 16) && la % 6 == 0;
			if (lampSlot) {
				double r = rand(a * 64 + la, b, 70);
				s[TUNNEL_CEILING_Y] = (r < 0.15 ? ModBlocks.SODIUM_LAMP_OFF : ModBlocks.SODIUM_LAMP).getDefaultState();
			}
			// Брошенная машина посреди полосы — с включёнными фарами.
			if (rand(a, b, 71) < 0.22 && la >= 22 && la <= 26 && lb >= 13 && lb <= 15) {
				placeCar(s, CAR_COLORS[(int) (rand(a, b, 72) * CAR_COLORS.length)], true, lb - 13, la - 22);
			}
			return;
		}

		if (sidePassage) {
			open(s, PARKING_CEILING_Y);
			if (la == 4 || la == 9) s[FLOOR_Y] = ModBlocks.PAINT_WHITE.getDefaultState();
			return;
		}

		if (wall) {
			s[FLOOR_Y + 1] = ModBlocks.HAZARD_STRIPE.getDefaultState();
			int side = lb == 11 ? 0 : 1;
			int exitRarity = Math.max(1, NullspaceConfig.get().exitRarity / 8);
			if (la == 20 && rand(a, b * 2 + side, 73) * exitRarity < 1.0) {
				s[FLOOR_Y + 1] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[FLOOR_Y + 2] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[FLOOR_Y + 3] = ModBlocks.EXIT_SIGN.getDefaultState();
			} else if ((la == 26 || la == 27) && rand(a, b * 2 + side, 74) < 0.08) {
				s[FLOOR_Y + 1] = ModBlocks.ELEVATOR.getDefaultState();
				s[FLOOR_Y + 2] = ModBlocks.ELEVATOR.getDefaultState();
			}
		}
	}

	private void cross(BlockState[] s, int lx, int lz) {
		boolean road = (lx >= 12 && lx <= 19) || (lz >= 12 && lz <= 19);
		if (!road) {
			boolean wall = (lx == 11 || lx == 20) && !(lz >= 12 && lz <= 19)
					|| (lz == 11 || lz == 20) && !(lx >= 12 && lx <= 19);
			if (wall) s[FLOOR_Y + 1] = ModBlocks.HAZARD_STRIPE.getDefaultState();
			return;
		}
		open(s, TUNNEL_CEILING_Y);
		if ((lx == 15 || lx == 16) && (lz == 15 || lz == 16)) s[TUNNEL_CEILING_Y] = ModBlocks.SODIUM_LAMP.getDefaultState();
		if ((lx == 12 || lx == 19) && (lz == 12 || lz == 19)) s[FLOOR_Y] = ModBlocks.PAINT_YELLOW.getDefaultState();
	}

	/** Ближайшая дверь EXIT (в стенах тоннелей) в радиусе radius блоков, или null. */
	public net.minecraft.util.math.BlockPos nearestExit(int x, int z, int radius) {
		int csx = Math.floorDiv(x, SECTION), csz = Math.floorDiv(z, SECTION);
		int r = radius / SECTION;
		int rarity = Math.max(1, NullspaceConfig.get().exitRarity / 8);
		long best = Long.MAX_VALUE;
		int bx = 0, bz = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int sx = csx + dx, sz = csz + dz;
				Section sec = sectionAt(sx, sz);
				for (int side = 0; side < 2; side++) {
					int ex, ez;
					if (sec == Section.TUNNEL_EW && rand(sx, sz * 2 + side, 73) * rarity < 1.0) {
						ex = sx * SECTION + 20;
						ez = sz * SECTION + (side == 0 ? 11 : 20);
					} else if (sec == Section.TUNNEL_NS && rand(sz, sx * 2 + side, 73) * rarity < 1.0) {
						ex = sx * SECTION + (side == 0 ? 11 : 20);
						ez = sz * SECTION + 20;
					} else {
						continue;
					}
					long d = (long) (ex - x) * (ex - x) + (long) (ez - z) * (ez - z);
					if (d < best) { best = d; bx = ex; bz = ez; }
				}
			}
		}
		return best == Long.MAX_VALUE ? null : new net.minecraft.util.math.BlockPos(bx, FLOOR_Y + 1, bz);
	}

	/** Секция парковки рядом с заданной — сюда падают через слив. */
	public int[] findParking(int sx, int sz) {
		for (int r = 0; r < 16; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					if (sectionAt(sx + dx, sz + dz) == Section.PARKING) return new int[]{sx + dx, sz + dz};
				}
			}
		}
		return new int[]{sx, sz};
	}
}
