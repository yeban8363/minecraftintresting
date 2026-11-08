package com.nullspace.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Карта уровней. Рисует только то, что ты видел своими глазами.
 * Когда Стабильность падает, карта начинает врать.
 */
public class LevelMapItem extends Item {
	/** Открывает экран карты. Подставляется клиентом. */
	public static Runnable opener = () -> {
	};

	public LevelMapItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) opener.run();
		return TypedActionResult.success(stack, world.isClient());
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.nullspace.level_map").formatted(Formatting.GRAY));
	}
}
