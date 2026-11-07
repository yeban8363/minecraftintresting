package com.nullspace.world;

import com.nullspace.NullspaceConfig;
import com.nullspace.block.EscalatorBlock;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.Direction;

/**
 * Уровень 3 — MALL. Бесконечный двухэтажный торговый центр.
 *
 * Мир разбит на клетки 48×48. Через каждую клетку крестом проходят две галереи
 * шириной 12 (lz 18..29 вдоль x и lx 18..29 вдоль z). На втором этаже в центре
 * галерей — атриумы: пол вырезан, по краям стеклянные перила, видно первый этаж.
 * Через атриумы ведут мостики и эскалаторы. В четырёх углах клетки — магазины
 * (на каждом этаже свой). Иногда первый этаж клетки занят фуд-кортом или кинотеатром.
 */
public final class MallLayout {
	public static final int CELL = 48;
	public static final int F0 = 16;    // пол первого этажа
	public static final int F1 = 23;    // пол второго этажа (он же потолок первого)
	public static final int ROOF = 30;
	public static final int TOP_Y = 32;
	public static final int HEIGHT = TOP_Y + 1;

	public enum Cell { STANDARD, FOOD_COURT, CINEMA }

	public enum Store { CLOTHING, BOOKS, ELECTRONICS, TOYS, CAFE, MUSIC, EMPTY, CLOSED }

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final Block[] WOOL = {
			Blocks.WHITE_WOOL, Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.PINK_WOOL,
			Blocks.LIME_WOOL, Blocks.BLACK_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.ORANGE_WOOL};

	private final long seed;

	public MallLayout(long seed) {
		this.seed = seed;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	double rand(int a, int b, int salt) {
		long h = seed ^ 0x3C6EF372FE94F82BL;
		h = mix(h + 0x9E3779B97F4A7C15L * a);
		h = mix(h + 0xC2B2AE3D27D4EB4FL * b);
		h = mix(h + 0x165667B19E3779F9L * salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	public Cell cellAt(int cx, int cz) {
		double r = rand(cx, cz, 1);
		if (r < 0.70) return Cell.STANDARD;
		if (r < 0.85) return Cell.FOOD_COURT;
		return Cell.CINEMA;
	}

	public Store storeAt(int cx, int cz, int quadrant, int floor) {
		double r = rand(cx * 4 + quadrant, cz * 2 + floor, 2);
		Store[] all = Store.values();
		return all[(int) (r * all.length)];
	}

	private boolean endless(int cx, int cz) {
		return rand(cx, cz, 3) < 0.15;
	}

	// ---------- геометрия галерей ----------

	private static boolean inX(int lz) {   // галерея вдоль оси x
		return lz >= 18 && lz <= 29;
	}

	private static boolean inZ(int lx) {   // галерея вдоль оси z
		return lx >= 18 && lx <= 29;
	}

	private static boolean walkway(int l) {
		return (l >= 18 && l <= 20) || (l >= 27 && l <= 29);
	}

	private static boolean band(int l) {
		return l >= 21 && l <= 26;
	}

	/** Площадь под эскалаторы (вместе с бортами). */
	private static boolean escalatorFootprint(int lx, int lz) {
		return (lx >= 8 && lx <= 14 && band(lz)) || (lz >= 8 && lz <= 14 && band(lx));
	}

	private static boolean bridge(int lx, int lz) {
		return (lx >= 15 && lx <= 17 && band(lz)) || (lz >= 15 && lz <= 17 && band(lx));
	}

	/** Вырез в полу второго этажа (атриум). */
	static boolean upperVoid(int x, int z) {
		int lx = Math.floorMod(x, CELL), lz = Math.floorMod(z, CELL);
		boolean v = (band(lz) && !walkway(lx)) || (band(lx) && !walkway(lz));
		return v && !escalatorFootprint(lx, lz) && !bridge(lx, lz);
	}

	/** Дыра в полу второго этажа: атриум или пролёт над эскалатором (кроме верхней ступени). */
	private static boolean hole(int x, int z) {
		if (upperVoid(x, z)) return true;
		int lx = Math.floorMod(x, CELL), lz = Math.floorMod(z, CELL);
		if (!escalatorFootprint(lx, lz)) return false;
		int k = (lx >= 8 && lx <= 14 && band(lz)) ? lx - 8 : lz - 8;
		return k < 6;
	}

	private static boolean railing(int x, int z) {
		if (hole(x, z)) return false;
		int lx = Math.floorMod(x, CELL), lz = Math.floorMod(z, CELL);
		if (!inX(lz) && !inZ(lx)) return false;
		if (escalatorFootprint(lx, lz)) return false;
		return hole(x + 1, z) || hole(x - 1, z) || hole(x, z + 1) || hole(x, z - 1);
	}

	// ---------------------------------------------------------------------

	public BlockState[] column(int x, int z) {
		int cx = Math.floorDiv(x, CELL), lx = Math.floorMod(x, CELL);
		int cz = Math.floorDiv(z, CELL), lz = Math.floorMod(z, CELL);

		BlockState[] s = new BlockState[HEIGHT];
		BlockState sub = ModBlocks.SUBFLOOR.getDefaultState();
		for (int y = 0; y < HEIGHT; y++) s[y] = sub;
		s[F0] = floorTile(x, z);
		for (int y = F0 + 1; y < F1; y++) s[y] = AIR;
		s[F1] = floorTile(x, z);
		for (int y = F1 + 1; y < ROOF; y++) s[y] = AIR;
		s[ROOF] = roof(lx, lz);

		Cell cell = cellAt(cx, cz);
		if (inX(lz) || inZ(lx)) {
			concourse(s, x, z, cx, cz, lx, lz);
			return s;
		}

		int quadrant = (lx < 18 ? 0 : 1) + (lz < 18 ? 0 : 2);
		int u = lx < 18 ? lx : 47 - lx;
		int v = lz < 18 ? lz : 47 - lz;

		switch (cell) {
			case STANDARD -> store(s, F0, storeAt(cx, cz, quadrant, 0), cx, cz, quadrant, u, v);
			case FOOD_COURT -> foodCourt(s, lx, lz, u, v);
			case CINEMA -> cinema(s, lz < 18, u, v);
		}
		store(s, F1, storeAt(cx, cz, quadrant, 1), cx, cz, quadrant + 4, u, v);
		return s;
	}

	private BlockState floorTile(int x, int z) {
		boolean dark = (Math.floorMod(x, 8) < 4) == (Math.floorMod(z, 8) < 4);
		return (dark ? ModBlocks.MALL_FLOOR_DARK : ModBlocks.MALL_FLOOR).getDefaultState();
	}

	private BlockState roof(int lx, int lz) {
		boolean lamp = lx % 6 == 3 && lz % 6 == 3;
		return (lamp ? ModBlocks.CEILING_LIGHT : ModBlocks.CEILING_TILE).getDefaultState();
	}

	// ---------- галереи ----------

	private void concourse(BlockState[] s, int x, int z, int cx, int cz, int lx, int lz) {
		// Свет первого этажа — панели в перекрытии.
		if (lx % 6 == 3 && lz % 6 == 3) s[F1] = ModBlocks.CEILING_LIGHT.getDefaultState();

		if (upperVoid(x, z)) {
			s[F1] = AIR;
		} else if (railing(x, z)) {
			s[F1 + 1] = Blocks.GLASS.getDefaultState();
			if ((lx + lz) % 4 == 0) s[F1] = ModBlocks.CEILING_LIGHT.getDefaultState();
		}

		if (escalatorFootprint(lx, lz)) {
			escalators(s, cx, cz, lx, lz);
			return;
		}

		// Сухой фонтан в центре креста.
		if (lx >= 22 && lx <= 25 && lz >= 22 && lz <= 25) {
			boolean rim = lx == 22 || lx == 25 || lz == 22 || lz == 25;
			s[F0 + 1] = (rim ? Blocks.POLISHED_ANDESITE : Blocks.AIR).getDefaultState();
			if (!rim) s[F0] = Blocks.POLISHED_BLACKSTONE.getDefaultState();
			return;
		}

		// Кадки с растениями и скамейки по краям галерей (на обоих этажах).
		boolean edgeX = inX(lz) && (lz == 18 || lz == 29) && !inZ(lx);
		boolean edgeZ = inZ(lx) && (lx == 18 || lx == 29) && !inX(lz);
		int along = edgeX ? lx : lz;
		if ((edgeX || edgeZ) && along % 8 == 4) {
			s[F0 + 1] = Blocks.POTTED_FERN.getDefaultState();
			if (!upperVoid(x, z) && !railing(x, z)) s[F1 + 1] = Blocks.POTTED_FERN.getDefaultState();
		} else if ((edgeX || edgeZ) && along % 8 == 6) {
			s[F0 + 1] = Blocks.OAK_SLAB.getDefaultState();
		}

		// Указатель «Вы здесь».
		if (lx == 19 && lz == 19 && rand(cx, cz, 5) < 0.6) {
			s[F0 + 1] = ModBlocks.MALL_MAP.getDefaultState();
			s[F0 + 2] = ModBlocks.MALL_MAP.getDefaultState();
		}
	}

	/** Эскалаторы: две полосы через атриум, вверх и вниз, 7 ступеней. */
	private void escalators(BlockState[] s, int cx, int cz, int lx, int lz) {
		boolean alongX = lx >= 8 && lx <= 14;
		int k = alongX ? lx - 8 : lz - 8;        // номер ступени 0..6
		int across = alongX ? lz : lx;           // 21..26
		Direction up = alongX ? Direction.EAST : Direction.SOUTH;

		s[F1] = AIR; // над эскалатором перекрытия нет

		if (across == 21 || across == 26) {      // борта
			for (int y = F0 + 1; y <= F0 + 1 + k; y++) s[y] = ModBlocks.ESCALATOR_BODY.getDefaultState();
			s[F0 + 2 + k] = Blocks.GLASS.getDefaultState();
			return;
		}
		boolean down = across >= 24;
		for (int y = F0 + 1; y < F0 + 1 + k; y++) s[y] = ModBlocks.ESCALATOR_BODY.getDefaultState();
		BlockState step = ModBlocks.ESCALATOR.getDefaultState()
				.with(EscalatorBlock.FACING, up)
				.with(EscalatorBlock.DOWN, down)
				.with(EscalatorBlock.ENDLESS, !down && k == 6 && endless(cx, cz));
		s[F0 + 1 + k] = step;
	}

	// ---------- магазины ----------

	private void store(BlockState[] s, int base, Store type, int cx, int cz, int salt, int u, int v) {
		int y1 = base + 1, y2 = base + 2, y3 = base + 3;
		int ceiling = base == F0 ? F1 : ROOF;
		boolean front = u == 17 || v == 17;
		boolean back = u == 0 || v == 0;

		if (back) {
			for (int y = y1; y < ceiling; y++) s[y] = ModBlocks.MALL_WALL.getDefaultState();
			// Служебная дверь EXIT в задней стене пустых магазинов.
			if (type == Store.EMPTY && u == 0 && v == 9
					&& rand(cx * 8 + salt, cz, 9) * Math.max(1, NullspaceConfig.get().exitRarity / 6) < 1.0) {
				s[y1] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[y2] = ModBlocks.EXIT_DOOR.getDefaultState();
				s[y3] = ModBlocks.EXIT_SIGN.getDefaultState();
			}
			return;
		}

		if (front) {
			boolean entrance = (u == 17 && v >= 7 && v <= 10 && v != 17) || (v == 17 && u >= 7 && u <= 10 && u != 17);
			for (int y = y1; y <= base + 4; y++) {
				if (type == Store.CLOSED) s[y] = ModBlocks.ROLLER_SHUTTER.getDefaultState();
				else s[y] = entrance ? AIR : Blocks.GLASS.getDefaultState();
			}
			boolean lit = type != Store.CLOSED && type != Store.EMPTY;
			s[base + 5] = (lit ? ModBlocks.SHOP_SIGN : ModBlocks.MALL_WALL).getDefaultState();
			for (int y = base + 6; y < ceiling; y++) s[y] = ModBlocks.MALL_WALL.getDefaultState();
			return;
		}

		// Потолок магазина: свет и динамик.
		boolean dark = type == Store.CLOSED || type == Store.EMPTY;
		if (u % 4 == 2 && v % 4 == 2) {
			s[ceiling] = (dark ? ModBlocks.CEILING_LIGHT_OFF : ModBlocks.CEILING_LIGHT).getDefaultState();
		} else if (u == 8 && v == 8 && type != Store.CLOSED) {
			s[ceiling] = ModBlocks.SPEAKER.getDefaultState();
		} else {
			s[ceiling] = (base == F0 ? ModBlocks.MALL_FLOOR : ModBlocks.CEILING_TILE).getDefaultState();
		}

		double h = rand(cx * 4096 + salt * 256 + u * 16 + v, cz, 11);
		switch (type) {
			case CLOTHING -> {
				if ((u == 4 || u == 8 || u == 12) && v >= 3 && v <= 14 && v % 2 == 1) {
					s[y1] = Blocks.OAK_FENCE.getDefaultState();
					s[y2] = WOOL[(int) (h * WOOL.length)].getDefaultState();
				}
			}
			case BOOKS -> {
				if ((u == 3 || u == 6 || u == 9 || u == 12) && v >= 3 && v <= 14) {
					s[y1] = Blocks.BOOKSHELF.getDefaultState();
					s[y2] = Blocks.BOOKSHELF.getDefaultState();
				}
			}
			case ELECTRONICS -> {
				if (v == 1) {
					s[y1] = Blocks.BLACK_CONCRETE.getDefaultState();
					s[y2] = ModBlocks.TV_STATIC.getDefaultState();
					s[y3] = ModBlocks.TV_STATIC.getDefaultState();
				} else if ((u == 5 || u == 11) && v >= 5 && v <= 12) {
					s[y1] = Blocks.BLACK_CONCRETE.getDefaultState();
					if (v % 2 == 0) s[y2] = ModBlocks.TV_STATIC.getDefaultState();
				}
			}
			case TOYS -> {
				if (h < 0.18) {
					s[y1] = WOOL[(int) (h / 0.18 * WOOL.length)].getDefaultState();
					if (h < 0.06) s[y2] = WOOL[(int) (h / 0.06 * WOOL.length)].getDefaultState();
				}
			}
			case CAFE -> {
				if (v == 2 && u >= 2 && u <= 14) {
					s[y1] = Blocks.QUARTZ_BLOCK.getDefaultState();
				} else if (u % 4 == 2 && v % 4 == 2 && v >= 5) {
					s[y1] = Blocks.OAK_FENCE.getDefaultState();
					s[y2] = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
				}
			}
			case MUSIC -> {
				if ((u == 4 || u == 9 || u == 13) && v >= 3 && v <= 13) {
					s[y1] = (v % 3 == 0 ? Blocks.JUKEBOX : Blocks.NOTE_BLOCK).getDefaultState();
				}
			}
			case EMPTY, CLOSED -> {
				if (h < 0.04) s[y1] = Blocks.BARREL.getDefaultState();
			}
		}

		// Находки.
		double f = rand(cx * 8 + salt, cz, 12);
		if (u == 2 && v == 2 && f < (type == Store.EMPTY ? 0.3 : 0.08)) s[y1] = ModBlocks.SUPPLY_CRATE.getDefaultState();
		if (u == 14 && v == 14 && f > 0.85) s[y1] = ModBlocks.PAPER_PILE.getDefaultState();
		if (type == Store.EMPTY && u == 3 && v == 14 && f < 0.25) s[y1] = ModBlocks.GENERATOR.getDefaultState();
	}

	// ---------- фуд-корт ----------

	private void foodCourt(BlockState[] s, int lx, int lz, int u, int v) {
		int y1 = F0 + 1;
		if (u == 0 || v == 0) {
			for (int y = y1; y < F1; y++) s[y] = ModBlocks.MALL_WALL.getDefaultState();
			return;
		}
		if (u == 1 || v == 1) {                         // прилавки вдоль стен
			s[y1] = Blocks.QUARTZ_BLOCK.getDefaultState();
			s[F0 + 4] = ModBlocks.SHOP_SIGN.getDefaultState();
			return;
		}
		if (lx % 4 == 1 && lz % 4 == 1) {                // столики
			s[y1] = Blocks.OAK_FENCE.getDefaultState();
			s[F0 + 2] = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
		} else if ((lx % 4 == 1 && lz % 4 == 2) || (lx % 4 == 2 && lz % 4 == 1)) {
			s[y1] = Blocks.OAK_SLAB.getDefaultState();    // стулья
		}
		if (u % 4 == 3 && v % 4 == 3) s[F1] = ModBlocks.CEILING_LIGHT.getDefaultState();
	}

	// ---------- кинотеатр ----------

	private void cinema(BlockState[] s, boolean screenNorth, int u, int v) {
		int y1 = F0 + 1;
		boolean wall = u == 0 || v == 0 || u == 17 || v == 17;
		if (wall) {
			for (int y = y1; y < F1; y++) s[y] = ModBlocks.MALL_WALL.getDefaultState();
			if (v == 17 && u >= 7 && u <= 10) {          // вход из галереи
				s[y1] = AIR;
				s[F0 + 2] = AIR;
				s[F0 + 3] = AIR;
				s[F0 + 4] = ModBlocks.SHOP_SIGN.getDefaultState();
			}
			return;
		}
		if (v == 1 && u >= 2 && u <= 15) {               // экран
			for (int y = F0 + 2; y <= F0 + 5; y++) s[y] = ModBlocks.CINEMA_SCREEN.getDefaultState();
			return;
		}
		// Ряды кресел смотрят на экран; спинка — с противоположной стороны.
		if (v >= 5 && v <= 15 && v % 2 == 1 && u >= 2 && u <= 15 && u != 8 && u != 9) {
			Direction back = screenNorth ? Direction.SOUTH : Direction.NORTH;
			s[y1] = Blocks.RED_NETHER_BRICK_STAIRS.getDefaultState().with(StairsBlock.FACING, back);
		}
		if (u % 6 == 3 && v % 6 == 3) s[F1] = ModBlocks.CEILING_LIGHT_OFF.getDefaultState();
	}

	/** Точка прибытия на лифте — галерея первого этажа. */
	public static double[] arrival(int cx, int cz) {
		return new double[]{cx * CELL + 36.5, F0 + 1, cz * CELL + 19.5};
	}
}
