package com.nullspace.world;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Планировка Уровня 0. Чистая функция от (seed, x, z): одинаковый результат
 * при любом порядке генерации чанков, без хранения состояния.
 *
 * Мир разбит на клетки CELL×CELL. Линия x = cx*CELL — «вертикальная» стена между
 * клетками, линия z = cz*CELL — «горизонтальная». Каждая стена либо есть, либо нет,
 * и может иметь дверной проём шириной 2. Клетки сгруппированы в зоны 12×12,
 * у каждой зоны свой стиль: обычный лабиринт, зал с колоннами, теснота, тьма, «слишком ярко».
 */
public final class OfficeLayout {
	public static final int CELL = 5;
	public static final int ZONE = 12;

	public static final int FLOOR_Y = 10;   // ковёр
	public static final int CEILING_Y = 14; // плитка и лампы
	public static final int TOP_Y = 16;     // выше — пустота
	public static final int HEIGHT = TOP_Y + 1;

	public enum Style {
		STANDARD(0.50, 0.55, 0.55, 0.12, 0.06, false),
		PILLAR_HALL(0.04, 0.50, 0.45, 0.15, 0.05, true),
		NARROW(0.68, 0.45, 0.40, 0.25, 0.10, false),
		DARK(0.45, 0.55, 0.15, 0.55, 0.30, false),
		TOO_BRIGHT(0.30, 0.60, 1.00, 0.00, 0.00, false);

		final double wall, gap, lamp, dead, flicker;
		final boolean pillars;

		Style(double wall, double gap, double lamp, double dead, double flicker, boolean pillars) {
			this.wall = wall;
			this.gap = gap;
			this.lamp = lamp;
			this.dead = dead;
			this.flicker = flicker;
			this.pillars = pillars;
		}
	}

	private enum Column { OPEN, WALL, EXIT, DOOR }

	private static final BlockState AIR = Blocks.AIR.getDefaultState();

	private final long seed;

	public OfficeLayout(long seed) {
		this.seed = seed;
	}

	// ---------- случайность ----------

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Детерминированное число в [0, 1) для пары координат и «соли». */
	double rand(int a, int b, int salt) {
		long h = seed;
		h = mix(h + 0x9E3779B97F4A7C15L * a);
		h = mix(h + 0xC2B2AE3D27D4EB4FL * b);
		h = mix(h + 0x165667B19E3779F9L * salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	// ---------- зоны и стены ----------

	public Style styleAt(int cx, int cz) {
		double r = rand(Math.floorDiv(cx, ZONE), Math.floorDiv(cz, ZONE), 101);
		if (r < 0.45) return Style.STANDARD;
		if (r < 0.60) return Style.PILLAR_HALL;
		if (r < 0.80) return Style.NARROW;
		if (r < 0.92) return Style.DARK;
		return Style.TOO_BRIGHT;
	}

	private boolean wallV(int cx, int cz) {
		return rand(cx, cz, 1) < styleAt(cx, cz).wall;
	}

	private boolean wallH(int cx, int cz) {
		return rand(cx, cz, 2) < styleAt(cx, cz).wall;
	}

	/** Смещение проёма (1..3, проём занимает o и o+1) или -1, если проёма нет. */
	private int gapV(int cx, int cz) {
		if (rand(cx, cz, 3) >= styleAt(cx, cz).gap) return -1;
		return 1 + (int) (rand(cx, cz, 4) * 3);
	}

	private int gapH(int cx, int cz) {
		if (rand(cx, cz, 5) >= styleAt(cx, cz).gap) return -1;
		return 1 + (int) (rand(cx, cz, 6) * 3);
	}

	private boolean exitV(int cx, int cz) {
		return rand(cx, cz, 21) * NullspaceConfig.get().exitRarity < 1.0;
	}

	private boolean exitH(int cx, int cz) {
		return rand(cx, cz, 22) * NullspaceConfig.get().exitRarity < 1.0;
	}

	private boolean doorV(int cx, int cz) {
		return rand(cx, cz, 23) * NullspaceConfig.get().anomalyDoorRarity < 1.0;
	}

	private boolean doorH(int cx, int cz) {
		return rand(cx, cz, 24) * NullspaceConfig.get().anomalyDoorRarity < 1.0;
	}

	private Column columnAt(int x, int z) {
		int cx = Math.floorDiv(x, CELL), lx = Math.floorMod(x, CELL);
		int cz = Math.floorDiv(z, CELL), lz = Math.floorMod(z, CELL);

		if (lx == 0 && lz == 0) {
			boolean solid = styleAt(cx, cz).pillars
					|| wallV(cx, cz) || wallV(cx, cz - 1)
					|| wallH(cx, cz) || wallH(cx - 1, cz);
			return solid ? Column.WALL : Column.OPEN;
		}
		if (lx == 0) {
			if (!wallV(cx, cz)) return Column.OPEN;
			int g = gapV(cx, cz);
			if (g >= 0) return (lz == g || lz == g + 1) ? Column.OPEN : Column.WALL;
			if (lz == 2 && exitV(cx, cz)) return Column.EXIT;
			return (lz == 3 && doorV(cx, cz)) ? Column.DOOR : Column.WALL;
		}
		if (lz == 0) {
			if (!wallH(cx, cz)) return Column.OPEN;
			int g = gapH(cx, cz);
			if (g >= 0) return (lx == g || lx == g + 1) ? Column.OPEN : Column.WALL;
			if (lx == 2 && exitH(cx, cz)) return Column.EXIT;
			return (lx == 3 && doorH(cx, cz)) ? Column.DOOR : Column.WALL;
		}
		return Column.OPEN;
	}

	private BlockState ceilingAt(int x, int z) {
		int cx = Math.floorDiv(x, CELL), lx = Math.floorMod(x, CELL);
		int cz = Math.floorDiv(z, CELL), lz = Math.floorMod(z, CELL);
		if (lx != 2 || lz != 2) return ModBlocks.CEILING_TILE.getDefaultState();

		Style style = styleAt(cx, cz);
		if (rand(cx, cz, 7) >= style.lamp) return ModBlocks.CEILING_TILE.getDefaultState();
		if (rand(cx, cz, 8) < style.dead) return ModBlocks.CEILING_LIGHT_OFF.getDefaultState();
		if (rand(cx, cz, 9) < style.flicker) return ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState();
		return ModBlocks.CEILING_LIGHT.getDefaultState();
	}

	private BlockState floorAt(int x, int z) {
		boolean interior = Math.floorMod(x, CELL) != 0 && Math.floorMod(z, CELL) != 0;
		if (interior && rand(x, z, 11) < 1.0 / 900.0) return ModBlocks.DAMP_CARPET.getDefaultState();
		return ModBlocks.OFFICE_CARPET.getDefaultState();
	}

	/**
	 * Находки внутри клеток: ящики с припасами, пачки записок и редкие
	 * «посты» — генератор, ящик и записки вместе. Центр клетки (2,2) всегда пуст:
	 * туда приземляются провалившиеся.
	 */
	private BlockState objectAt(int x, int z) {
		int lx = Math.floorMod(x, CELL), lz = Math.floorMod(z, CELL);
		if (lx == 0 || lz == 0) return null;
		int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
		double r = rand(cx, cz, 30);

		if (r < 0.004) { // пост исследователя
			if (lx == 3 && lz == 1) return ModBlocks.GENERATOR.getDefaultState();
			if (lx == 1 && lz == 1) return ModBlocks.SUPPLY_CRATE.getDefaultState();
			if (lx == 3 && lz == 3) return ModBlocks.PAPER_PILE.getDefaultState();
			return null;
		}
		if (r < 0.018) return (lx == 1 && lz == 1) ? ModBlocks.SUPPLY_CRATE.getDefaultState() : null;
		if (r < 0.045) return (lx == 3 && lz == 3) ? ModBlocks.PAPER_PILE.getDefaultState() : null;
		return null;
	}

	/** Полный столбец блоков от y = 0 до TOP_Y включительно. */
	public BlockState[] column(int x, int z) {
		BlockState[] states = new BlockState[HEIGHT];
		BlockState subfloor = ModBlocks.SUBFLOOR.getDefaultState();
		for (int y = 0; y < FLOOR_Y; y++) states[y] = subfloor;
		states[FLOOR_Y] = floorAt(x, z);

		switch (columnAt(x, z)) {
			case OPEN -> {
				for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) states[y] = AIR;
				BlockState object = objectAt(x, z);
				if (object != null) states[FLOOR_Y + 1] = object;
			}
			case WALL -> {
				states[FLOOR_Y + 1] = ModBlocks.OFFICE_WALLPAPER_BASE.getDefaultState();
				for (int y = FLOOR_Y + 2; y < CEILING_Y; y++) states[y] = ModBlocks.OFFICE_WALLPAPER.getDefaultState();
			}
			case EXIT -> {
				states[FLOOR_Y + 1] = ModBlocks.EXIT_DOOR.getDefaultState();
				states[FLOOR_Y + 2] = ModBlocks.EXIT_DOOR.getDefaultState();
				states[FLOOR_Y + 3] = ModBlocks.EXIT_SIGN.getDefaultState();
			}
			case DOOR -> {
				states[FLOOR_Y + 1] = ModBlocks.ANOMALY_DOOR.getDefaultState();
				states[FLOOR_Y + 2] = ModBlocks.ANOMALY_DOOR.getDefaultState();
				states[FLOOR_Y + 3] = ModBlocks.OFFICE_WALLPAPER.getDefaultState();
			}
		}

		states[CEILING_Y] = ceilingAt(x, z);
		for (int y = CEILING_Y + 1; y <= TOP_Y; y++) states[y] = subfloor;
		return states;
	}

	/** Безопасная точка внутри случайной клетки: внутренности клеток всегда свободны. */
	public static int cellCenter(int cell) {
		return cell * CELL + 2;
	}
}
