package com.nullspace.mechanic;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModBlocks;
import com.nullspace.world.OfficeLayout;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * «Копия дома». Когда игрок проваливается, пространство запоминает кусочек
 * места, откуда он упал (14×5×14 блоков), и собирает из него комнату
 * недалеко от точки приземления на Уровне 0.
 *
 * Всё то же самое — но сундуки пустые, вода и лава исчезли, кровати пропали,
 * а две вещи поменялись местами.
 */
public final class BaseMemory {
	private static final int W = 14, H = 6; // H: пол + 5 блоков вверх

	/** Снимок окрестности точки провала. [x][y][z], y = 0 — пол (на блок ниже ног). */
	public static BlockState[][][] capture(World world, BlockPos center) {
		BlockState[][][] snap = new BlockState[W][H][W];
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int x = 0; x < W; x++) {
			for (int y = 0; y < H; y++) {
				for (int z = 0; z < W; z++) {
					p.set(center.getX() + x - 6, center.getY() - 1 + y, center.getZ() + z - 6);
					snap[x][y][z] = sanitize(world.getBlockState(p));
				}
			}
		}
		return snap;
	}

	private static BlockState sanitize(BlockState s) {
		Block b = s.getBlock();
		if (b instanceof FluidBlock || b instanceof BedBlock || b instanceof NetherPortalBlock
				|| b instanceof EndPortalBlock || b instanceof AbstractFireBlock || s.isOf(Blocks.END_GATEWAY)
				|| s.isOf(Blocks.TNT)) {
			return Blocks.AIR.getDefaultState();
		}
		if (s.contains(Properties.WATERLOGGED)) s = s.with(Properties.WATERLOGGED, false);
		return s;
	}

	/**
	 * Собрать комнату 16×16 на сетке клеток Уровня 0. Вход — проём в западной стене,
	 * он выходит прямо во внутренность соседней клетки (там всегда свободно).
	 */
	public static void build(ServerWorld level0, int cellX, int cellZ, BlockState[][][] snap, Random random) {
		if (!NullspaceConfig.get().baseCopy) return;
		swapOnePair(snap, random);

		int x0 = cellX * OfficeLayout.CELL, z0 = cellZ * OfficeLayout.CELL;
		int floor = OfficeLayout.FLOOR_Y;
		int ceiling = floor + H; // 16
		BlockPos.Mutable p = new BlockPos.Mutable();
		int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				boolean wall = x == 0 || x == 15 || z == 0 || z == 15;
				for (int y = floor; y <= ceiling; y++) {
					p.set(x0 + x, y, z0 + z);
					BlockState state;
					if (y == ceiling) {
						state = (!wall && (x == 5 || x == 10) && (z == 5 || z == 10))
								? ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState()
								: ModBlocks.CEILING_TILE.getDefaultState();
					} else if (wall) {
						boolean doorway = x == 0 && (z == 2 || z == 3) && (y == floor + 1 || y == floor + 2);
						if (y == floor) state = ModBlocks.OFFICE_CARPET.getDefaultState();
						else if (doorway) state = Blocks.AIR.getDefaultState();
						else if (y == floor + 1) state = ModBlocks.OFFICE_WALLPAPER_BASE.getDefaultState();
						else state = ModBlocks.OFFICE_WALLPAPER.getDefaultState();
					} else {
						state = snap[x - 1][y - floor][z - 1];
					}
					level0.setBlockState(p, state, flags);
				}
			}
		}
	}

	/** «Одна деталь не на месте». */
	private static void swapOnePair(BlockState[][][] snap, Random random) {
		for (int attempt = 0; attempt < 40; attempt++) {
			int ax = random.nextInt(W), ay = 1 + random.nextInt(H - 1), az = random.nextInt(W);
			int bx = random.nextInt(W), by = 1 + random.nextInt(H - 1), bz = random.nextInt(W);
			BlockState a = snap[ax][ay][az], b = snap[bx][by][bz];
			if (a.isAir() || b.isAir() || a == b) continue;
			snap[ax][ay][az] = b;
			snap[bx][by][bz] = a;
			return;
		}
	}

	private BaseMemory() {
	}
}
