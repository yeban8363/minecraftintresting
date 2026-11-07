package com.nullspace.mechanic;

import com.nullspace.block.ChalkMarkBlock;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Меловые метки при низкой Стабильности живут своей жизнью:
 * твои крестики иногда исчезают, а рядом появляются чужие стрелки.
 */
public final class ChalkEvents {

	/** @return true, если что-то произошло. */
	public static boolean tryHaunt(ServerPlayerEntity player, Random random) {
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();

		List<BlockPos> own = new ArrayList<>();
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -16; dx <= 16; dx++) {
			for (int dz = -16; dz <= 16; dz++) {
				for (int dy = -1; dy <= 2; dy++) {
					BlockState state = world.getBlockState(p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz));
					if (state.isOf(ModBlocks.CHALK_MARK) && !state.get(ChalkMarkBlock.STRANGER)) {
						own.add(p.toImmutable());
					}
				}
			}
		}

		// Стереть одну из твоих меток — только если ты на неё не смотришь вплотную.
		if (!own.isEmpty() && random.nextBoolean()) {
			BlockPos target = own.get(random.nextInt(own.size()));
			if (target.getSquaredDistance(center) > 25) {
				world.removeBlock(target, false);
				return true;
			}
		}

		// Нарисовать чужую стрелку на стене неподалёку.
		for (int attempt = 0; attempt < 24; attempt++) {
			BlockPos air = center.add(random.nextInt(13) - 6, 1, random.nextInt(13) - 6);
			if (!world.getBlockState(air).isAir()) continue;
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos wall = air.offset(dir.getOpposite());
				if (world.getBlockState(wall).isOf(ModBlocks.OFFICE_WALLPAPER)) {
					world.setBlockState(air, ModBlocks.CHALK_MARK.getDefaultState()
							.with(ChalkMarkBlock.FACING, dir)
							.with(ChalkMarkBlock.STRANGER, true));
					return true;
				}
			}
		}
		return false;
	}

	private ChalkEvents() {
	}
}
