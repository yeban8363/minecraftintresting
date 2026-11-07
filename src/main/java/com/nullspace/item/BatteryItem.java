package com.nullspace.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Батарейка. Некоторые севшие, но снаружи этого не видно. */
public class BatteryItem extends Item {
	public BatteryItem(Settings settings) {
		super(settings);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.nullspace.battery").formatted(Formatting.DARK_GRAY));
	}
}
