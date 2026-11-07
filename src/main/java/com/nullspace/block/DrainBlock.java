package com.nullspace.block;

import com.nullspace.mechanic.NullspaceTeleporter;
import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Слив на дне самого глубокого бассейна. Решётка поддаётся —
 * и тебя затягивает вниз, туда, где заводятся машины. Уровень 2.
 */
public class DrainBlock extends Block {
	public DrainBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			world.playSound(null, pos, ModSounds.DRAIN_ENGINE, SoundCategory.AMBIENT, 1.0f, 0.8f);
			NullspaceTeleporter.sendToLevel2(serverPlayer);
		}
		return ActionResult.success(world.isClient());
	}
}
