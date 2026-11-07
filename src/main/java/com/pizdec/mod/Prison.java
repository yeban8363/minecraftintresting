package com.pizdec.mod;

import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import static com.pizdec.mod.B.*;

/**
 * Королевская тюрьма строгого режима, версия 2. Периметр с колючкой и вышками, КПП, караулка,
 * тюремный корпус на 12 камер (кровать, параша, умывальник, стол, железная дверь), столовая
 * и прогулочный двор с турником.
 */
public final class Prison {
	public static final int W = 53, D = 27;
	public static final int CELLS = 12;
	public static final String GUARD_TAG = "pizdec_guard";

	private Prison() {}

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.prisonBuilt || !st.castleBuilt) return;
		ServerWorld world = s.getOverworld();
		w = world;
		int ox = st.cx - 65, oz = st.cz - 34;
		loadChunks(ox - 6, oz - 6, ox + W + 8, oz + D + 6);
		int oy = ground(ox + W / 2, oz + D / 2);
		build(ox, oy, oz);
		st.prisonBuilt = true;
		st.px = ox;
		st.py = oy;
		st.pz = oz;
		st.markDirty();

		IronGolemEntity g = EntityType.IRON_GOLEM.create(world);
		if (g != null) {
			g.refreshPositionAndAngles(ox + 48.5, oy, oz + 16.5, 90f, 0f);
			g.setHeadYaw(90f);
			g.setBodyYaw(90f);
			g.setAiDisabled(true);
			g.setInvulnerable(true);
			g.setPersistent();
			g.setCustomName(U.t("Надзиратель Палыч", Formatting.DARK_GRAY, Formatting.BOLD));
			g.setCustomNameVisible(true);
			g.addCommandTag(GUARD_TAG);
			g.addCommandTag(Castle.VERSION_TAG);
			world.spawnEntity(g);
		}
		PizdecMod.LOG.info("Тюрьма v2 построена на {} {} {}", ox, oy, oz);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.prisonBuilt && p.getX() >= st.px - 3 && p.getX() <= st.px + W + 2 && p.getZ() >= st.pz - 3
				&& p.getZ() <= st.pz + D + 2 && p.getY() >= st.py - 6 && p.getY() <= st.py + 14;
	}

	public static boolean inside(PizdecState st, Vec3d p) {
		return inside(st, BlockPos.ofFloored(p));
	}

	/** Центр камеры k (0..11): 6 с севера коридора, 6 с юга. */
	public static Vec3d cell(PizdecState st, int k) {
		int col = Math.floorMod(k, 6);
		int row = Math.floorMod(k, CELLS) / 6;
		return new Vec3d(st.px + 12.5 + 4 * col, st.py, st.pz + (row == 0 ? 8.0 : 19.0));
	}

	public static Vec3d exit(PizdecState st) {
		return new Vec3d(st.px + 55.5, st.py, st.pz + 13.5);
	}

	public static Vec3d gate(PizdecState st) {
		return new Vec3d(st.px + 58.5, st.py, st.pz + 13.5);
	}

	public static Vec3d yard(PizdecState st) {
		return new Vec3d(st.px + 43.5, st.py, st.pz + 20.5);
	}

	// ------------------------------------------------------------------ стройка

	private static void build(int ox, int oy, int oz) {
		site(ox - 3, oz - 3, ox + W + 6, oz + D + 2, oy, 16, Blocks.GRAVEL, Blocks.COBBLED_DEEPSLATE);

		// --- периметр: стена + «колючка», ворота-КПП на востоке
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				boolean edge = x == 0 || x == W - 1 || z == 0 || z == D - 1;
				if (!edge) continue;
				fill(ox + x, oy, oz + z, ox + x, oy + 3, oz + z, U.chance(0.15) ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS);
				fill(ox + x, oy + 4, oz + z, ox + x, oy + 5, oz + z, Blocks.IRON_BARS);
			}
		}
		fill(ox + W - 1, oy, oz + 12, ox + W - 1, oy + 3, oz + 14, Blocks.AIR);
		fill(ox + W - 1, oy + 4, oz + 11, ox + W - 1, oy + 5, oz + 15, Blocks.STONE_BRICKS);
		set(ox + W - 1, oy + 6, oz + 13, Blocks.REDSTONE_LAMP);
		lampPost(ox + W + 1, oy, oz + 10);
		lampPost(ox + W + 1, oy, oz + 16);
		// вышки по углам
		for (int[] c : new int[][] {{2, 2}, {W - 3, 2}, {2, D - 3}, {W - 3, D - 3}}) watchtower(ox + c[0], oy, oz + c[1]);
		// дорожка от ворот
		fill(ox + 35, oy - 1, oz + 12, ox + W + 6, oy - 1, oz + 14, Blocks.POLISHED_ANDESITE);

		// --- тюремный корпус x 4..34, z 4..22
		int bx0 = ox + 4, bx1 = ox + 34, bz0 = oz + 4, bz1 = oz + 22;
		fill(bx0, oy - 1, bz0, bx1, oy - 1, bz1, Blocks.POLISHED_DEEPSLATE);
		walls(bx0, oy, bz0, bx1, oy + 5, bz1, Blocks.DEEPSLATE_BRICKS);
		for (int x = bx0; x <= bx1; x++) {
			for (int z = bz0; z <= bz1; z++) {
				boolean lamp = z == oz + 13 && (x - bx0) % 4 == 2;
				set(x, oy + 6, z, lamp ? Blocks.GLOWSTONE : Blocks.DEEPSLATE_TILES);
			}
		}
		// узкие окна-бойницы с решётками
		for (int x = bx0 + 3; x < bx1; x += 4) {
			set(x - 1, oy + 3, bz0, Blocks.IRON_BARS);
			set(x - 1, oy + 3, bz1, Blocks.IRON_BARS);
		}
		fill(bx1, oy, oz + 12, bx1, oy + 2, oz + 14, Blocks.AIR); // вход
		fill(ox + 5, oy - 1, oz + 12, ox + 33, oy - 1, oz + 14, Blocks.POLISHED_BLACKSTONE_BRICKS); // коридор
		// стол дежурного у входа
		fill(ox + 31, oy, oz + 12, ox + 32, oy, oz + 12, Blocks.DARK_OAK_PLANKS);
		set(ox + 31, oy + 1, oz + 12, Blocks.LANTERN);

		// камеры: стены x = 10,14,…,34
		for (int k = 0; k <= 6; k++) {
			int x = ox + 10 + 4 * k;
			fill(x, oy, oz + 4, x, oy + 5, oz + 11, Blocks.DEEPSLATE_BRICKS);
			fill(x, oy, oz + 15, x, oy + 5, oz + 22, Blocks.DEEPSLATE_BRICKS);
		}
		for (int k = 0; k < 6; k++) {
			int x0 = ox + 11 + 4 * k;
			// север: решётка z=11 с железной дверью
			fill(x0, oy, oz + 11, x0 + 2, oy + 3, oz + 11, Blocks.IRON_BARS);
			fill(x0, oy + 4, oz + 11, x0 + 2, oy + 5, oz + 11, Blocks.DEEPSLATE_BRICKS);
			B.door(x0 + 1, oy, oz + 11, Direction.SOUTH, Blocks.IRON_DOOR);
			B.bed(x0, oy, oz + 6, Direction.NORTH, Blocks.RED_BED);
			set(x0 + 2, oy, oz + 5, Blocks.CAULDRON);
			set(x0 + 2, oy, oz + 6, Blocks.WATER_CAULDRON);
			B.table(x0 + 2, oy, oz + 9);
			set(x0 + 1, oy + 5, oz + 8, B.hangingLantern());
			// юг: решётка z=15
			fill(x0, oy, oz + 15, x0 + 2, oy + 3, oz + 15, Blocks.IRON_BARS);
			fill(x0, oy + 4, oz + 15, x0 + 2, oy + 5, oz + 15, Blocks.DEEPSLATE_BRICKS);
			B.door(x0 + 1, oy, oz + 15, Direction.NORTH, Blocks.IRON_DOOR);
			B.bed(x0, oy, oz + 20, Direction.SOUTH, Blocks.RED_BED);
			set(x0 + 2, oy, oz + 21, Blocks.CAULDRON);
			set(x0 + 2, oy, oz + 20, Blocks.WATER_CAULDRON);
			B.table(x0 + 2, oy, oz + 17);
			set(x0 + 1, oy + 5, oz + 18, B.hangingLantern());
		}
		// столовая (запад)
		for (int z : new int[] {6, 8, 18, 20}) {
			B.table(ox + 7, oy, oz + z);
			set(ox + 6, oy, oz + z, B.stairs(Blocks.SPRUCE_STAIRS, Direction.WEST));
			set(ox + 8, oy, oz + z, B.stairs(Blocks.SPRUCE_STAIRS, Direction.EAST));
		}
		set(ox + 5, oy, oz + 5, Blocks.SMOKER);
		set(ox + 5, oy, oz + 6, Blocks.CAULDRON);
		set(ox + 5, oy, oz + 21, Blocks.BARREL);
		set(ox + 7, oy + 5, oz + 13, B.hangingLantern());

		// --- прогулочный двор x 36..50, z 16..24
		fill(ox + 36, oy - 1, oz + 16, ox + 50, oy - 1, oz + 24, Blocks.COARSE_DIRT);
		// турник
		set(ox + 40, oy, oz + 22, Blocks.DARK_OAK_FENCE);
		set(ox + 40, oy + 1, oz + 22, Blocks.DARK_OAK_FENCE);
		set(ox + 42, oy, oz + 22, Blocks.DARK_OAK_FENCE);
		set(ox + 42, oy + 1, oz + 22, Blocks.DARK_OAK_FENCE);
		set(ox + 41, oy + 2, oz + 22, Blocks.IRON_BARS);
		set(ox + 40, oy + 2, oz + 22, Blocks.DARK_OAK_FENCE);
		set(ox + 42, oy + 2, oz + 22, Blocks.DARK_OAK_FENCE);
		// скамейки и «баскетбольное» кольцо
		fill(ox + 45, oy, oz + 18, ox + 48, oy, oz + 18, Blocks.SMOOTH_STONE_SLAB);
		fill(ox + 50, oy, oz + 20, ox + 50, oy + 3, oz + 20, Blocks.IRON_BARS);
		set(ox + 49, oy + 3, oz + 20, Blocks.CHAIN);
		lampPost(ox + 37, oy, oz + 17);

		// --- караулка x 38..47, z 2..9
		fill(ox + 38, oy - 1, oz + 2, ox + 47, oy - 1, oz + 9, Blocks.SPRUCE_PLANKS);
		walls(ox + 38, oy, oz + 2, ox + 47, oy + 4, oz + 9, Blocks.STONE_BRICKS);
		fill(ox + 38, oy + 5, oz + 2, ox + 47, oy + 5, oz + 9, Blocks.DARK_OAK_PLANKS);
		fill(ox + 42, oy, oz + 9, ox + 42, oy + 1, oz + 9, Blocks.AIR);
		set(ox + 40, oy + 2, oz + 9, Blocks.GLASS_PANE);
		set(ox + 45, oy + 2, oz + 9, Blocks.GLASS_PANE);
		B.bed(ox + 40, oy, oz + 3, Direction.WEST, Blocks.BLUE_BED);
		B.table(ox + 43, oy, oz + 5);
		set(ox + 46, oy, oz + 3, Blocks.CHEST);
		set(ox + 46, oy + 1, oz + 3, Blocks.BELL);
		set(ox + 42, oy + 4, oz + 5, B.hangingLantern());
	}

	private static void watchtower(int cx, int oy, int cz) {
		for (int dx = -1; dx <= 1; dx += 2) {
			for (int dz = -1; dz <= 1; dz += 2) fill(cx + dx, oy, cz + dz, cx + dx, oy + 7, cz + dz, Blocks.DARK_OAK_LOG);
		}
		fill(cx - 1, oy + 8, cz - 1, cx + 1, oy + 8, cz + 1, Blocks.SPRUCE_PLANKS);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) set(cx + dx, oy + 9, cz + dz, Blocks.SPRUCE_FENCE);
			}
		}
		set(cx, oy + 9, cz, Blocks.GLOWSTONE);
		fill(cx - 1, oy + 11, cz - 1, cx + 1, oy + 11, cz + 1, Blocks.DARK_OAK_SLAB);
		set(cx - 1, oy + 10, cz - 1, Blocks.SPRUCE_FENCE);
		set(cx + 1, oy + 10, cz + 1, Blocks.SPRUCE_FENCE);
	}
}
