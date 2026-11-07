package com.nullspace.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Экран кинотеатра. Показывают этот же зал. */
public class CinemaScreenBlock extends Block {
	public CinemaScreenBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient()) {
			player.sendMessage(Text.translatable("message.nullspace.cinema_screen").formatted(Formatting.GRAY, Formatting.ITALIC), true);
		}
		return ActionResult.success(world.isClient());
	}
}
