package com.nullspace.block;

import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Лампа, которая иногда «заходится»: серия быстрых миганий с треском.
 * Случайный тик запускает серию, запланированные тики продолжают её.
 */
public class FlickeringLightBlock extends Block {
	public static final BooleanProperty LIT = Properties.LIT;

	public FlickeringLightBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LIT, true));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		world.playSound(null, pos, ModSounds.LAMP_BUZZ, SoundCategory.BLOCKS, 0.6f, 0.9f + random.nextFloat() * 0.2f);
		toggle(state, world, pos);
		world.scheduleBlockTick(pos, this, 2 + random.nextInt(5));
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		boolean continueBurst = random.nextFloat() < 0.75f;
		if (continueBurst) {
			toggle(state, world, pos);
			world.scheduleBlockTick(pos, this, 1 + random.nextInt(6));
		} else if (!state.get(LIT) && random.nextFloat() < 0.85f) {
			// В конце серии лампа обычно снова загорается. Обычно.
			world.setBlockState(pos, state.with(LIT, true), Block.NOTIFY_LISTENERS);
		}
	}

	private static void toggle(BlockState state, ServerWorld world, BlockPos pos) {
		world.setBlockState(pos, state.with(LIT, !state.get(LIT)), Block.NOTIFY_LISTENERS);
	}
}
