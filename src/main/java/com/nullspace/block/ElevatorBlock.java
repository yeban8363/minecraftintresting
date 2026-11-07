package com.nullspace.block;

import com.nullspace.mechanic.NullspaceTeleporter;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
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
 * Лифт в стене тоннеля. На панели одна кнопка — «M».
 * Кнопка поддаётся. Лифт едет вверх — туда, где играет музыка. Уровень 3.
 */
public class ElevatorBlock extends Block {
	public ElevatorBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			world.playSound(null, pos, ModSounds.ELEVATOR_DING, SoundCategory.BLOCKS, 0.8f, 1.0f);
			if (world.getRegistryKey() != NullspaceDimensions.LEVEL_3) {
				NullspaceTeleporter.sendToLevel3(serverPlayer);
			}
		}
		return ActionResult.success(world.isClient());
	}
}
