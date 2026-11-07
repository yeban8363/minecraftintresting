package com.nullspace.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * Тёмное мокрое пятно на ковре. Если присесть на нём на несколько секунд,
 * пол «поддаётся» (логика в NoclipManager).
 */
public class DampCarpetBlock extends Block {
	public DampCarpetBlock(Settings settings) {
		super(settings);
	}

	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (random.nextInt(4) == 0) {
			double x = pos.getX() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			world.addParticle(ParticleTypes.FALLING_WATER, x, pos.getY() + 3.9, z, 0, 0, 0);
		}
	}
}
