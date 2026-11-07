package com.pizdec.mod;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

/** Строительные помощники для замка, тюрьмы, суда и войны. Координаты абсолютные. */
final class B {
	static ServerWorld w;
	static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

	private B() {}

	static void set(int x, int y, int z, BlockState s) {
		w.setBlockState(new BlockPos(x, y, z), s, FLAGS);
	}

	static void set(int x, int y, int z, Block b) {
		set(x, y, z, b.getDefaultState());
	}

	static BlockState get(int x, int y, int z) {
		return w.getBlockState(new BlockPos(x, y, z));
	}

	static void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
			}
		}
	}

	static void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
		fill(x0, y0, z0, x1, y1, z1, b.getDefaultState());
	}

	/** Стены по периметру прямоугольника (без пола и потолка). */
	static void walls(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				set(x, y, z0, b);
				set(x, y, z1, b);
			}
			for (int z = z0; z <= z1; z++) {
				set(x0, y, z, b);
				set(x1, y, z, b);
			}
		}
	}

	/** Убирает всё, что не воздух. */
	static void clear(int x0, int y0, int z0, int x1, int y1, int z1) {
		BlockState air = Blocks.AIR.getDefaultState();
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					BlockPos p = new BlockPos(x, y, z);
					if (!w.getBlockState(p).isAir()) w.setBlockState(p, air, FLAGS);
				}
			}
		}
	}

	static boolean soft(BlockState b) {
		return b.isAir() || !b.getFluidState().isEmpty() || b.isReplaceable() || b.isIn(BlockTags.LEAVES)
				|| b.isIn(BlockTags.LOGS);
	}

	/** Расчистка участка и фундамент до твёрдой земли. top — верхний слой (y = oy-1). */
	static void site(int x0, int z0, int x1, int z1, int oy, int height, Block top, Block base) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				clear(x, oy, z, x, oy + height, z);
				for (int y = oy - 1; y > oy - 40; y--) {
					BlockState b = get(x, y, z);
					if (y < oy - 3 && !soft(b)) break;
					set(x, y, z, y == oy - 1 ? top : base);
				}
			}
		}
	}

	static void loadChunks(int x0, int z0, int x1, int z1) {
		for (int cx = Math.min(x0, x1) >> 4; cx <= Math.max(x0, x1) >> 4; cx++) {
			for (int cz = Math.min(z0, z1) >> 4; cz <= Math.max(z0, z1) >> 4; cz++) w.getChunk(cx, cz);
		}
	}

	static int ground(int x, int z) {
		return Math.max(w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), w.getSeaLevel() + 1);
	}

	/** Дверь: facing — куда «смотрит» дверь (в сторону выхода). */
	static void door(int x, int y, int z, Direction facing, Block door) {
		BlockState s = door.getDefaultState().with(DoorBlock.FACING, facing);
		set(x, y, z, s.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, s.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** Кровать: ноги в (x,y,z), изголовье в сторону toHead. */
	static void bed(int x, int y, int z, Direction toHead, Block bed) {
		BlockState s = bed.getDefaultState().with(BedBlock.FACING, toHead);
		set(x, y, z, s.with(BedBlock.PART, BedPart.FOOT));
		set(x + toHead.getOffsetX(), y, z + toHead.getOffsetZ(), s.with(BedBlock.PART, BedPart.HEAD));
	}

	static BlockState stairs(Block stairs, Direction up) {
		return stairs.getDefaultState().with(StairsBlock.FACING, up);
	}

	static BlockState hangingLantern() {
		return Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true);
	}

	static BlockState leaves() {
		return Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
	}

	/** Люстра: цепь с потолка и висячий фонарь. */
	static void chandelier(int x, int ceilingY, int z, int len) {
		for (int i = 1; i <= len; i++) set(x, ceilingY - i, z, Blocks.CHAIN);
		set(x, ceilingY - len - 1, z, hangingLantern());
	}

	/** Столб с фонарём. */
	static void lampPost(int x, int y, int z) {
		set(x, y, z, Blocks.DARK_OAK_FENCE);
		set(x, y + 1, z, Blocks.DARK_OAK_FENCE);
		set(x, y + 2, z, Blocks.LANTERN);
	}

	/** Стол: забор + нажимная плита. */
	static void table(int x, int y, int z) {
		set(x, y, z, Blocks.SPRUCE_FENCE);
		set(x, y + 1, z, Blocks.SPRUCE_PRESSURE_PLATE);
	}
}
