package com.nullspace.world;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Уровень 1 — AQUATIC. Бесконечный комплекс бассейнов.
 *
 * Мир разбит на залы 16×16. Между залами — кафельные стены с широкими проёмами.
 * Типы залов:
 *   POOL    — бассейн 11×11 глубиной 2–6, подводные фонари в бортах;
 *   DEEP    — огромный бассейн глубиной 9, ступени уходят в темноту, на дне — слив;
 *   FLOODED — весь зал затоплен по щиколотку;
 *   DRY     — сухой кафельный зал с колоннами, здесь лежат находки.
 */
public final class AquaticLayout {
	public static final int HALL = 16;
	public static final int FLOOR_Y = 16;   // уровень бортика и поверхности воды
	public static final int CEILING_Y = 22;
	public static final int TOP_Y = 24;
	public static final int HEIGHT = TOP_Y + 1;
	public static final int DEEP_BOTTOM_Y = FLOOR_Y - 9;

	public enum Hall { POOL, DEEP, FLOODED, DRY }

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState WATER = Blocks.WATER.getDefaultState();

	private final long seed;

	public AquaticLayout(long seed) {
		this.seed = seed;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	double rand(int a, int b, int salt) {
		long h = seed ^ 0x5DEECE66DL;
		h = mix(h + 0x9E3779B97F4A7C15L * a);
		h = mix(h + 0xC2B2AE3D27D4EB4FL * b);
		h = mix(h + 0x165667B19E3779F9L * salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	public Hall hallAt(int hx, int hz) {
		double r = rand(hx, hz, 40);
		if (r < 0.55) return Hall.POOL;
		if (r < 0.65) return Hall.DEEP;
		if (r < 0.80) return Hall.FLOODED;
		return Hall.DRY;
	}

	private int poolDepth(int hx, int hz) {
		return 2 + (int) (rand(hx, hz, 41) * 5);
	}

	// ---------- стены ----------

	private boolean wallV(int hx, int hz) {
		return rand(hx, hz, 1) < 0.55;
	}

	private boolean wallH(int hx, int hz) {
		return rand(hx, hz, 2) < 0.55;
	}

	/** Начало проёма шириной 4 (2..9). */
	private int doorV(int hx, int hz) {
		return 2 + (int) (rand(hx, hz, 3) * 8);
	}

	private int doorH(int hx, int hz) {
		return 2 + (int) (rand(hx, hz, 4) * 8);
	}

	private boolean exitV(int hx, int hz) {
		return rand(hx, hz, 21) * Math.max(1, NullspaceConfig.get().exitRarity / 3) < 1.0;
	}

	private boolean exitH(int hx, int hz) {
		return rand(hx, hz, 22) * Math.max(1, NullspaceConfig.get().exitRarity / 3) < 1.0;
	}

	private enum Wall { NONE, SOLID, EXIT }

	private Wall wallAt(int x, int z) {
		int hx = Math.floorDiv(x, HALL), lx = Math.floorMod(x, HALL);
		int hz = Math.floorDiv(z, HALL), lz = Math.floorMod(z, HALL);
		if (lx == 0 && lz == 0) {
			return (wallV(hx, hz) || wallV(hx, hz - 1) || wallH(hx, hz) || wallH(hx - 1, hz)) ? Wall.SOLID : Wall.NONE;
		}
		if (lx == 0) {
			if (!wallV(hx, hz)) return Wall.NONE;
			int d = doorV(hx, hz);
			if (lz >= d && lz < d + 4) return Wall.NONE;
			return (lz == 13 && exitV(hx, hz)) ? Wall.EXIT : Wall.SOLID;
		}
		if (lz == 0) {
			if (!wallH(hx, hz)) return Wall.NONE;
			int d = doorH(hx, hz);
			if (lx >= d && lx < d + 4) return Wall.NONE;
			return (lx == 13 && exitH(hx, hz)) ? Wall.EXIT : Wall.SOLID;
		}
		return Wall.NONE;
	}

	// ---------- потолок ----------

	private BlockState ceilingAt(int hx, int hz, int lx, int lz, Hall hall) {
		if (lx % 4 != 2 || lz % 4 != 2) return ModBlocks.CEILING_TILE.getDefaultState();
		double lampChance = hall == Hall.DEEP ? 0.25 : 0.6;
		int salt = 50 + lx * 17 + lz;
		if (rand(hx, hz, salt) >= lampChance) return ModBlocks.CEILING_TILE.getDefaultState();
		double r = rand(hx, hz, salt + 300);
		if (r < 0.15) return ModBlocks.CEILING_LIGHT_OFF.getDefaultState();
		if (r < 0.24) return ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState();
		return ModBlocks.CEILING_LIGHT.getDefaultState();
	}

	// ---------- столбец ----------

	public BlockState[] column(int x, int z) {
		BlockState[] s = new BlockState[HEIGHT];
		BlockState sub = ModBlocks.SUBFLOOR.getDefaultState();
		BlockState tile = ModBlocks.POOL_TILE.getDefaultState();
		for (int y = 0; y < HEIGHT; y++) s[y] = sub;

		int hx = Math.floorDiv(x, HALL), lx = Math.floorMod(x, HALL);
		int hz = Math.floorDiv(z, HALL), lz = Math.floorMod(z, HALL);
		Hall hall = hallAt(hx, hz);

		s[FLOOR_Y] = tile;
		for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) s[y] = AIR;
		s[CEILING_Y] = ceilingAt(hx, hz, lx, lz, hall);

		Wall wall = wallAt(x, z);
		if (wall != Wall.NONE) {
			for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) s[y] = tile;
			s[FLOOR_Y + 2] = ModBlocks.POOL_TILE_BLUE.getDefaultState(); // синяя полоса
			if (wall == Wall.EXIT) {
				s[FLOOR_Y + 1] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[FLOOR_Y + 2] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[FLOOR_Y + 3] = ModBlocks.EXIT_SIGN.getDefaultState();
			}
			s[CEILING_Y] = ModBlocks.CEILING_TILE.getDefaultState();
			return s;
		}

		switch (hall) {
			case POOL -> pool(s, hx, hz, lx, lz);
			case DEEP -> deep(s, lx, lz);
			case FLOODED -> {
				s[FLOOR_Y - 1] = tile;
				s[FLOOR_Y] = WATER;
			}
			case DRY -> {
				boolean pillar = (lx == 4 || lx == 12) && (lz == 4 || lz == 12);
				if (pillar) for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) s[y] = tile;
				BlockState object = dryObject(hx, hz, lx, lz);
				if (object != null) s[FLOOR_Y + 1] = object;
			}
		}
		return s;
	}

	private void pool(BlockState[] s, int hx, int hz, int lx, int lz) {
		boolean inside = lx >= 3 && lx <= 13 && lz >= 3 && lz <= 13;
		int depth = poolDepth(hx, hz);
		if (inside) {
			int bottom = FLOOR_Y - depth;
			s[bottom] = ModBlocks.POOL_TILE_DARK.getDefaultState();
			for (int y = bottom + 1; y <= FLOOR_Y; y++) s[y] = WATER;
			return;
		}
		// Подводные фонари в бортах — в половине бассейнов.
		boolean rim = (lx == 2 || lx == 14) && lz >= 3 && lz <= 13 || (lz == 2 || lz == 14) && lx >= 3 && lx <= 13;
		if (rim && rand(hx, hz, 42) < 0.5 && (lx + lz) % 4 == 0) {
			s[FLOOR_Y - 1] = ModBlocks.POOL_LIGHT.getDefaultState();
		}
		for (int y = FLOOR_Y - depth; y < FLOOR_Y; y++) {
			if (!s[y].isOf(ModBlocks.POOL_LIGHT)) s[y] = ModBlocks.POOL_TILE.getDefaultState();
		}
		if (lx == 1 && lz == 8 && rand(hx, hz, 43) < 0.08) s[FLOOR_Y + 1] = ModBlocks.PAPER_PILE.getDefaultState();
	}

	/** Огромный глубокий бассейн: ступени вниз, во тьму, и слив на дне. */
	private void deep(BlockState[] s, int lx, int lz) {
		boolean inside = lx >= 2 && lx <= 14 && lz >= 2 && lz <= 14;
		if (!inside) return;
		s[DEEP_BOTTOM_Y] = ModBlocks.POOL_TILE_DARK.getDefaultState();
		for (int y = DEEP_BOTTOM_Y + 1; y <= FLOOR_Y; y++) s[y] = WATER;

		// Ступени вдоль северного края: каждая следующая на блок ниже.
		if (lz == 2 && lx <= 10) {
			int top = FLOOR_Y - 1 - (lx - 2);
			for (int y = DEEP_BOTTOM_Y + 1; y <= top; y++) s[y] = ModBlocks.POOL_TILE.getDefaultState();
		}
		if (lx == 8 && lz == 8) s[DEEP_BOTTOM_Y] = ModBlocks.DRAIN.getDefaultState();
	}

	private BlockState dryObject(int hx, int hz, int lx, int lz) {
		double r = rand(hx, hz, 30);
		if (r < 0.03) { // пост исследователя
			if (lx == 2 && lz == 14) return ModBlocks.GENERATOR.getDefaultState();
			if (lx == 2 && lz == 2) return ModBlocks.SUPPLY_CRATE.getDefaultState();
			if (lx == 14 && lz == 14) return ModBlocks.PAPER_PILE.getDefaultState();
			return null;
		}
		if (r < 0.15 && lx == 2 && lz == 2) return ModBlocks.SUPPLY_CRATE.getDefaultState();
		if (r >= 0.15 && r < 0.30 && lx == 14 && lz == 14) return ModBlocks.PAPER_PILE.getDefaultState();
		return null;
	}

	/** Центр зала с водой — сюда падают с Уровня 0. */
	public int[] findPoolHall(int startHx, int startHz) {
		for (int r = 0; r < 64; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					Hall h = hallAt(startHx + dx, startHz + dz);
					if (h == Hall.POOL && poolDepth(startHx + dx, startHz + dz) >= 3 || h == Hall.DEEP) {
						return new int[]{startHx + dx, startHz + dz};
					}
				}
			}
		}
		return new int[]{startHx, startHz};
	}
}
