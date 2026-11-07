package com.nullspace.block;

import com.nullspace.mechanic.StadiumTeleporter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Самая обычная офисная дверь с номерком. Размером 1×2.
 * За ней — стадион.
 */
public class AnomalyDoorBlock extends Block {
	public AnomalyDoorBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			StadiumTeleporter.use(serverPlayer);
		}
		return ActionResult.success(world.isClient());
	}
}
