package com.pizdec.mod;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * Королевская тюрьма. Стоит к западу от замка.
 * 10 камер: 5 с северной стороны коридора и 5 с южной, вход с востока.
 */
public final class Prison {
	public static final int W = 21; // x: 0..20
	public static final int D = 13; // z: 0..12
	public static final int CELLS = 10;
	public static final String GUARD_TAG = "pizdec_guard";

	private static ServerWorld w;

	private Prison() {}

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.prisonBuilt || !st.castleBuilt) return;
		ServerWorld world = s.getOverworld();
		int ox = st.cx - 34;
		int oz = st.cz + 6;
		for (int cx = (ox - 6) >> 4; cx <= (ox + W + 6) >> 4; cx++) {
			for (int cz = (oz - 6) >> 4; cz <= (oz + D + 6) >> 4; cz++) world.getChunk(cx, cz);
		}
		int oy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox + W / 2, oz + D / 2);
		oy = Math.max(oy, world.getSeaLevel() + 1);
		build(world, ox, oy, oz);
		st.prisonBuilt = true;
		st.px = ox;
		st.py = oy;
		st.pz = oz;
		st.markDirty();

		IronGolemEntity g = EntityType.IRON_GOLEM.create(world);
		if (g != null) {
			g.refreshPositionAndAngles(ox + 22.5, oy, oz + 3.5, 90f, 0f);
			g.setHeadYaw(90f);
			g.setBodyYaw(90f);
			g.setAiDisabled(true);
			g.setInvulnerable(true);
			g.setPersistent();
			g.setCustomName(U.t("Надзиратель Палыч", Formatting.DARK_GRAY, Formatting.BOLD));
			g.setCustomNameVisible(true);
			g.addCommandTag(GUARD_TAG);
			world.spawnEntity(g);
		}
		PizdecMod.LOG.info("Тюрьма построена на {} {} {}", ox, oy, oz);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.prisonBuilt
				&& p.getX() >= st.px - 3 && p.getX() <= st.px + W + 3
				&& p.getZ() >= st.pz - 3 && p.getZ() <= st.pz + D + 2
				&& p.getY() >= st.py - 6 && p.getY() <= st.py + 10;
	}

	/** Центр камеры (пол). */
	public static Vec3d cell(PizdecState st, int k) {
		int col = Math.floorMod(k, 5);
		int row = Math.floorMod(k, CELLS) / 5;
		return new Vec3d(st.px + 4 * col + 2 + 0.5, st.py, st.pz + (row == 0 ? 2 : 10) + 0.5);
	}

	/** Куда выпускают на свободу. */
	public static Vec3d exit(PizdecState st) {
		return new Vec3d(st.px + 23.5, st.py, st.pz + 6.5);
	}

	/** Куда подъезжает карета. */
	public static Vec3d gate(PizdecState st) {
		return new Vec3d(st.px + 26.5, st.py, st.pz + 6.5);
	}

	// ------------------------------------------------------------------ стройка

	private static void set(int x, int y, int z, BlockState b) {
		w.setBlockState(new BlockPos(x, y, z), b, Block.NOTIFY_LISTENERS);
	}

	private static void set(int x, int y, int z, Block b) {
		set(x, y, z, b.getDefaultState());
	}

	private static BlockState wall() {
		return U.chance(0.2) ? Blocks.CRACKED_DEEPSLATE_BRICKS.getDefaultState() : Blocks.DEEPSLATE_BRICKS.getDefaultState();
	}

	private static boolean soft(BlockState b) {
		return b.isAir() || !b.getFluidState().isEmpty() || b.isReplaceable()
				|| b.isIn(BlockTags.LEAVES) || b.isIn(BlockTags.LOGS);
	}

	private static void build(ServerWorld world, int ox, int oy, int oz) {
		w = world;
		BlockState air = Blocks.AIR.getDefaultState();

		// площадка
		for (int x = -3; x <= W + 6; x++) {
			for (int z = -3; z <= D + 2; z++) {
				for (int y = oy; y <= oy + 14; y++) {
					BlockPos p = new BlockPos(ox + x, y, oz + z);
					if (!w.getBlockState(p).isAir()) w.setBlockState(p, air, Block.NOTIFY_LISTENERS);
				}
				for (int y = oy - 1; y > oy - 40; y--) {
					BlockState b = w.getBlockState(new BlockPos(ox + x, y, oz + z));
					if (y < oy - 3 && !soft(b)) break;
					boolean in = x >= 0 && x < W && z >= 0 && z < D;
					set(ox + x, y, oz + z, y == oy - 1 ? (in ? Blocks.POLISHED_DEEPSLATE : Blocks.GRAVEL) : Blocks.COBBLED_DEEPSLATE);
				}
			}
		}

		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				boolean outer = x == 0 || x == W - 1 || z == 0 || z == D - 1;
				boolean cellWall = x % 4 == 0 && (z <= 4 || z >= 8);
				boolean bars = (z == 4 || z == 8) && x % 4 != 0;
				for (int y = oy; y <= oy + 4; y++) {
					if (outer || cellWall) set(ox + x, y, oz + z, wall());
					else if (bars) set(ox + x, y, oz + z, y == oy + 4 ? Blocks.DEEPSLATE_BRICKS : Blocks.IRON_BARS);
				}
				boolean lamp = z == 6 && x % 4 == 2;
				set(ox + x, oy + 5, oz + z, lamp ? Blocks.GLOWSTONE : Blocks.DEEPSLATE_TILES);
				if (outer && (x + z) % 2 == 0) set(ox + x, oy + 6, oz + z, Blocks.DEEPSLATE_TILE_WALL);
			}
		}

		// мебель в камерах: нары и параша
		for (int col = 0; col < 5; col++) {
			int x0 = 4 * col + 1;
			set(ox + x0, oy, oz + 1, Blocks.HAY_BLOCK);
			set(ox + x0 + 2, oy, oz + 1, Blocks.CAULDRON);
			set(ox + x0, oy, oz + 11, Blocks.HAY_BLOCK);
			set(ox + x0 + 2, oy, oz + 11, Blocks.CAULDRON);
		}

		// вход с востока
		for (int y = oy; y <= oy + 2; y++) {
			for (int z = 5; z <= 7; z++) set(ox + W - 1, y, oz + z, air);
		}
		set(ox + W, oy, oz + 4, Blocks.LANTERN);
		set(ox + W, oy, oz + 8, Blocks.LANTERN);
		// вышка надзирателя
		for (int y = oy; y <= oy + 7; y++) {
			set(ox + W + 3, y, oz + 10, Blocks.DARK_OAK_LOG);
			set(ox + W + 5, y, oz + 10, Blocks.DARK_OAK_LOG);
			set(ox + W + 3, y, oz + 12, Blocks.DARK_OAK_LOG);
			set(ox + W + 5, y, oz + 12, Blocks.DARK_OAK_LOG);
		}
		for (int x = W + 2; x <= W + 6; x++) {
			for (int z = 9; z <= 13; z++) set(ox + x, oy + 8, oz + z, Blocks.DARK_OAK_PLANKS);
		}
		set(ox + W + 4, oy + 9, oz + 11, Blocks.LANTERN);
		// дорожка к воротам замка
		for (int x = W; x <= W + 6; x++) {
			for (int z = 5; z <= 7; z++) set(ox + x, oy - 1, oz + z, Blocks.GRAVEL);
		}
	}
}
