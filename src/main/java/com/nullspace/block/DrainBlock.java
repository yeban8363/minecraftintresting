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
 * Слив на дне самого глубокого бассейна. Пока закрыт решёткой.
 * Снизу тянет холодом, и где-то очень далеко заводится двигатель.
 */
public class DrainBlock extends Block {
	public DrainBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient()) {
			player.sendMessage(Text.translatable("message.nullspace.drain").formatted(Formatting.DARK_AQUA, Formatting.ITALIC), true);
			world.playSound(null, pos, ModSounds.DRAIN_ENGINE, SoundCategory.AMBIENT, 1.0f, 0.8f);
		}
		return ActionResult.success(world.isClient());
	}
}
