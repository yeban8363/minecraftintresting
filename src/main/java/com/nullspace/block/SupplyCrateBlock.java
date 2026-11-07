package com.nullspace.block;

import com.nullspace.mechanic.Loot;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Ящик с припасами. Кто его здесь оставил — непонятно. Возможно, ты. Открывается один раз. */
public class SupplyCrateBlock extends Block {
	public static final BooleanProperty OPEN = Properties.OPEN;

	public SupplyCrateBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(OPEN, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(OPEN);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient()) return ActionResult.SUCCESS;

		if (state.get(OPEN)) {
			player.sendMessage(Text.translatable("message.nullspace.crate_empty").formatted(Formatting.GRAY), true);
			return ActionResult.CONSUME;
		}
		world.setBlockState(pos, state.with(OPEN, true), Block.NOTIFY_ALL);
		world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 0.8f, 0.8f);
		for (ItemStack stack : Loot.crate(world.getRandom())) {
			player.getInventory().offerOrDrop(stack);
		}
		return ActionResult.CONSUME;
	}
}
