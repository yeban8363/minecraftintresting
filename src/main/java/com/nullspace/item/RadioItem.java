package com.nullspace.item;

import com.nullspace.mechanic.RadioManager;
import com.nullspace.registry.ModComponents;
import com.nullspace.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Радиоприёмник. ПКМ — следующая частота. Shift+ПКМ — вкл/выкл
 * (если сел — вставить батарейку). Работает, пока в руке.
 */
public class RadioItem extends Item {
	public RadioItem(Settings settings) {
		super(settings);
	}

	public static boolean isOn(ItemStack stack) {
		return Boolean.TRUE.equals(stack.get(ModComponents.RADIO_ON));
	}

	public static int freq(ItemStack stack) {
		Integer f = stack.get(ModComponents.RADIO_FREQ);
		return Math.floorMod(f == null ? 0 : f, RadioManager.STATIONS);
	}

	public static boolean isEmpty(ItemStack stack) {
		return stack.getDamage() >= stack.getMaxDamage();
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) return TypedActionResult.success(stack, true);

		if (user.isSneaking()) {
			if (isEmpty(stack)) {
				FlashlightItem.insertBattery(world, user, stack);
				return TypedActionResult.success(stack, false);
			}
			boolean on = !isOn(stack);
			stack.set(ModComponents.RADIO_ON, on);
			world.playSound(null, user.getBlockPos(), ModSounds.RADIO_TUNE, SoundCategory.PLAYERS, 0.6f, on ? 1.2f : 0.7f);
			user.sendMessage(on ? RadioManager.stationLabel(freq(stack)) : Text.translatable("message.nullspace.radio_off")
					.formatted(Formatting.GRAY), true);
			return TypedActionResult.success(stack, false);
		}

		if (!isOn(stack)) {
			user.sendMessage(Text.translatable("message.nullspace.radio_hint").formatted(Formatting.GRAY), true);
			return TypedActionResult.fail(stack);
		}
		int next = Math.floorMod(freq(stack) + 1, RadioManager.STATIONS);
		stack.set(ModComponents.RADIO_FREQ, next);
		world.playSound(null, user.getBlockPos(), ModSounds.RADIO_TUNE, SoundCategory.PLAYERS, 0.6f, 0.8f + next * 0.08f);
		user.sendMessage(RadioManager.stationLabel(next), true);
		return TypedActionResult.success(stack, false);
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient() || !isOn(stack) || !(entity instanceof PlayerEntity player)) return;
		boolean held = player.getMainHandStack() == stack || player.getOffHandStack() == stack;
		if (!held || player.getAbilities().creativeMode) return;
		if (world.getTime() % 20 == 0) {
			int damage = stack.getDamage() + 1;
			if (damage >= stack.getMaxDamage()) {
				stack.setDamage(stack.getMaxDamage());
				stack.set(ModComponents.RADIO_ON, false);
				player.sendMessage(Text.translatable("message.nullspace.radio_died").formatted(Formatting.DARK_GRAY), true);
			} else {
				stack.setDamage(damage);
			}
		}
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return true;
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		return 0x6FB7C9;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		int percent = Math.round(100f * (1.0f - (float) stack.getDamage() / stack.getMaxDamage()));
		tooltip.add(Text.translatable("tooltip.nullspace.charge", percent).formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("tooltip.nullspace.radio_hint").formatted(Formatting.DARK_GRAY));
	}
}
