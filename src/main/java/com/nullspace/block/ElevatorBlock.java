package com.nullspace.block;

import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Лифт в стене тоннеля. На панели одна кнопка — «M».
 * Она не нажимается. Где-то сверху играет музыка.
 */
public class ElevatorBlock extends Block {
	public ElevatorBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient()) {
			player.sendMessage(Text.translatable("message.nullspace.elevator").formatted(Formatting.GRAY, Formatting.ITALIC), true);
			world.playSound(null, pos, ModSounds.ELEVATOR_DING, SoundCategory.BLOCKS, 0.8f, 1.0f);
			world.playSound(null, pos.up(6), ModSounds.MALL_MUSIC, SoundCategory.AMBIENT, 0.35f, 0.92f);
		}
		return ActionResult.success(world.isClient());
	}
}
