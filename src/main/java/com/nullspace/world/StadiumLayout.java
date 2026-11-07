package com.nullspace.world;

import com.nullspace.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Стадион за аномальной дверью. Один на весь мир, строится генератором чанков.
 *
 *  - зал 161×101 блок, потолок на высоте 30 блоков над полем;
 *  - зелёное поле с белой разметкой, вокруг — офисный ковролин;
 *  - трибуны вдоль длинных сторон, ряды красных и серых сидений;
 *  - на восточной стене табло «0:0»;
 *  - свет идёт ниоткуда (невидимые источники над полем);
 *  - в западной стене — та самая маленькая дверь обратно.
 */
public final class StadiumLayout {
	public static final int FLOOR_Y = 10;
	public static final int CEILING_Y = 40;
	public static final int TOP_Y = 42;
	public static final int HEIGHT = TOP_Y + 1;

	public static final int HALF_X = 80;
	public static final int HALF_Z = 50;
	public static final int SPAWN_X = -HALF_X + 2;

	private static final int FIELD_X = 60, FIELD_Z = 30;
	private static final int STANDS_FROM = 34, STANDS_MAX_X = 70;

	private static final BlockState AIR = Blocks.AIR.getDefaultState();

	// Табло: шрифт 3×5, каждый «пиксель» — 2×2 блока.
	private static final String[] ZERO = {"###", "#.#", "#.#", "#.#", "###"};
	private static final String[] COLON = {".", "#", ".", "#", "."};
	private static final String[][] SCORE = {ZERO, COLON, ZERO};
	private static final int BOARD_Z0 = -12, BOARD_Z1 = 12, BOARD_Y0 = 24, BOARD_Y1 = 38;
	private static final int TEXT_Z0 = -9, TEXT_TOP_Y = 36;

	public static BlockState[] column(int x, int z) {
		BlockState[] s = new BlockState[HEIGHT];
		BlockState sub = ModBlocks.SUBFLOOR.getDefaultState();
		for (int y = 0; y < FLOOR_Y; y++) s[y] = sub;

		int ax = Math.abs(x), az = Math.abs(z);

		// За пределами зала — сплошная толща.
		if (ax > HALF_X + 1 || az > HALF_Z + 1) {
			for (int y = FLOOR_Y; y < HEIGHT; y++) s[y] = sub;
			return s;
		}

		// Стены.
		if (ax == HALF_X + 1 || az == HALF_Z + 1) {
			s[FLOOR_Y] = ModBlocks.OFFICE_CARPET.getDefaultState();
			s[FLOOR_Y + 1] = ModBlocks.OFFICE_WALLPAPER_BASE.getDefaultState();
			for (int y = FLOOR_Y + 2; y < CEILING_Y; y++) s[y] = ModBlocks.OFFICE_WALLPAPER.getDefaultState();

			if (x == -(HALF_X + 1) && z == 0) {
				s[FLOOR_Y + 1] = ModBlocks.ANOMALY_DOOR.getDefaultState();
				s[FLOOR_Y + 2] = ModBlocks.ANOMALY_DOOR.getDefaultState();
			}
			if (x == HALF_X + 1 && z >= BOARD_Z0 && z <= BOARD_Z1) {
				for (int y = BOARD_Y0; y <= BOARD_Y1; y++) s[y] = scoreboard(z, y);
			}
			for (int y = CEILING_Y; y < HEIGHT; y++) s[y] = sub;
			return s;
		}

		// Пол.
		s[FLOOR_Y] = floor(x, z);
		for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) s[y] = AIR;

		// Трибуны.
		boolean stands = az >= STANDS_FROM && ax <= STANDS_MAX_X;
		if (stands) {
			int rows = (az - STANDS_FROM) / 2 + 1;
			for (int y = FLOOR_Y + 1; y < FLOOR_Y + rows; y++) s[y] = Blocks.GRAY_CONCRETE.getDefaultState();
			s[FLOOR_Y + rows] = (rows % 2 == 0 ? Blocks.RED_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE).getDefaultState();
		}

		// Свет ниоткуда.
		if (Math.floorMod(x, 12) == 0 && Math.floorMod(z, 12) == 0) {
			int y = stands ? FLOOR_Y + (az - STANDS_FROM) / 2 + 4 : FLOOR_Y + 4;
			if (y < CEILING_Y - 1) s[y] = Blocks.LIGHT.getDefaultState();
		}

		// Потолок.
		boolean lamp = Math.floorMod(x, 8) == 0 && Math.floorMod(z, 8) == 0;
		s[CEILING_Y] = (lamp ? ModBlocks.CEILING_LIGHT : ModBlocks.CEILING_TILE).getDefaultState();
		for (int y = CEILING_Y + 1; y < HEIGHT; y++) s[y] = sub;
		return s;
	}

	private static BlockState floor(int x, int z) {
		int ax = Math.abs(x), az = Math.abs(z);
		if (ax > FIELD_X || az > FIELD_Z) return ModBlocks.OFFICE_CARPET.getDefaultState();
		double r = Math.sqrt((double) x * x + (double) z * z);
		boolean line = ax == FIELD_X || az == FIELD_Z || x == 0 || Math.abs(r - 9.0) < 0.6;
		return (line ? Blocks.WHITE_WOOL : Blocks.GREEN_WOOL).getDefaultState();
	}

	private static BlockState scoreboard(int z, int y) {
		int col = (z - TEXT_Z0);
		int row = (TEXT_TOP_Y - y);
		if (col >= 0 && row >= 0 && row < 10) {
			int px = col / 2, py = row / 2;
			for (String[] glyph : SCORE) {
				int w = glyph[0].length();
				if (px < w) {
					if (glyph[py].charAt(px) == '#') return Blocks.RED_CONCRETE.getDefaultState();
					break;
				}
				px -= w + 1; // ширина символа + пробел
				if (px < 0) break;
			}
		}
		return Blocks.BLACK_CONCRETE.getDefaultState();
	}

	private StadiumLayout() {
	}
}
