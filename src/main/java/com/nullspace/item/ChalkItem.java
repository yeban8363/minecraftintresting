package com.nullspace.item;

import com.nullspace.block.ChalkMarkBlock;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;

/** Мел. ПКМ по стене — нарисовать крестик «я здесь был». */
public class ChalkItem extends Item {
	public ChalkItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		Direction side = context.getSide();
		if (!side.getAxis().isHorizontal()) return ActionResult.PASS;

		World world = context.getWorld();
		BlockPos pos = context.getBlockPos().offset(side);
		if (!world.getBlockState(pos).isAir()) return ActionResult.PASS;

		if (!world.isClient()) {
			world.setBlockState(pos, ModBlocks.CHALK_MARK.getDefaultState()
					.with(ChalkMarkBlock.FACING, side)
					.with(ChalkMarkBlock.STRANGER, false));
			world.playSound(null, pos, ModSounds.CHALK, SoundCategory.PLAYERS, 0.8f, 0.9f + world.getRandom().nextFloat() * 0.2f);
			PlayerEntity player = context.getPlayer();
			ItemStack stack = context.getStack();
			if (player != null) {
				stack.damage(1, player, LivingEntity.getSlotForHand(context.getHand()));
			}
		}
		return ActionResult.success(world.isClient());
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.nullspace.chalk").formatted(Formatting.GRAY));
	}
}
