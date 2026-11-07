package com.nullspace.block;

import com.nullspace.mechanic.NullspaceTeleporter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Дверь под зелёной табличкой EXIT. Ведёт домой. Скорее всего. */
public class ExitDoorBlock extends Block {
	public ExitDoorBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			NullspaceTeleporter.returnHome(serverPlayer);
		}
		return ActionResult.success(world.isClient());
	}
}
