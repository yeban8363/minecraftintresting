package com.nullspace.item;

import com.nullspace.mechanic.StabilityManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.List;

/** Термос с ещё тёплым чаем. Сильно восстанавливает Стабильность. */
public class ThermosItem extends Item {
	public static final float STABILITY_RESTORE = 35f;

	public ThermosItem(Settings settings) {
		super(settings);
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.DRINK;
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		if (!world.isClient() && user instanceof ServerPlayerEntity player) {
			StabilityManager.add(player, STABILITY_RESTORE);
			player.sendMessage(Text.translatable("message.nullspace.thermos").formatted(Formatting.GOLD), true);
		}
		return super.finishUsing(stack, world, user);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.nullspace.thermos").formatted(Formatting.GRAY));
	}
}
