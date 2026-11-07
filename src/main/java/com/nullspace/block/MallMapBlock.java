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

/** Схема торгового центра. Точка «Вы здесь» стоит не там. */
public class MallMapBlock extends Block {
	public MallMapBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient()) {
			String key = world.getRandom().nextInt(3) == 0 ? "message.nullspace.mall_map_moving" : "message.nullspace.mall_map";
			player.sendMessage(Text.translatable(key).formatted(Formatting.GRAY, Formatting.ITALIC), true);
		}
		return ActionResult.success(world.isClient());
	}
}
