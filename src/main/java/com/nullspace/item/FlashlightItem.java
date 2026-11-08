package com.nullspace.item;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModComponents;
import com.nullspace.registry.ModItems;
import com.nullspace.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Фонарик. ПКМ — включить/выключить. Shift+ПКМ — вставить батарейку из инвентаря.
 * Полоска прочности показывает заряд. Светит, только пока в руке.
 */
public class FlashlightItem extends Item {
	public FlashlightItem(Settings settings) {
		super(settings);
	}

	public static boolean isOn(ItemStack stack) {
		return Boolean.TRUE.equals(stack.get(ModComponents.FLASHLIGHT_ON));
	}

	public static boolean isEmpty(ItemStack stack) {
		return stack.getDamage() >= stack.getMaxDamage();
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) {
			return TypedActionResult.success(stack, true);
		}

		if (user.isSneaking()) {
			insertBattery(world, user, stack);
			return TypedActionResult.success(stack, false);
		}

		if (isEmpty(stack) && !isOn(stack)) {
			user.sendMessage(Text.translatable("message.nullspace.flashlight_empty").formatted(Formatting.GRAY), true);
			world.playSound(null, user.getBlockPos(), ModSounds.FLASHLIGHT_CLICK, SoundCategory.PLAYERS, 0.5f, 0.6f);
			return TypedActionResult.fail(stack);
		}

		boolean on = !isOn(stack);
		stack.set(ModComponents.FLASHLIGHT_ON, on);
		world.playSound(null, user.getBlockPos(), ModSounds.FLASHLIGHT_CLICK, SoundCategory.PLAYERS, 0.6f, on ? 1.1f : 0.9f);
		return TypedActionResult.success(stack, false);
	}

	/** Вставить батарейку из инвентаря в прибор (фонарик или радио). */
	public static void insertBattery(World world, PlayerEntity user, ItemStack flashlight) {
		ItemStack battery = findBattery(user);
		if (battery.isEmpty()) {
			user.sendMessage(Text.translatable("message.nullspace.no_battery").formatted(Formatting.GRAY), true);
			return;
		}
		if (!user.getAbilities().creativeMode) {
			battery.decrement(1);
		}
		world.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 0.4f, 1.6f);

		if (world.getRandom().nextDouble() < NullspaceConfig.get().deadBatteryChance) {
			user.sendMessage(Text.translatable("message.nullspace.battery_dead").formatted(Formatting.DARK_GRAY), true);
			return;
		}
		flashlight.setDamage(0);
		user.sendMessage(Text.translatable("message.nullspace.battery_ok").formatted(Formatting.GRAY), true);
	}

	private static ItemStack findBattery(PlayerEntity user) {
		for (int i = 0; i < user.getInventory().size(); i++) {
			ItemStack s = user.getInventory().getStack(i);
			if (s.isOf(ModItems.BATTERY)) return s;
		}
		return ItemStack.EMPTY;
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
				stack.set(ModComponents.FLASHLIGHT_ON, false);
				player.sendMessage(Text.translatable("message.nullspace.flashlight_died").formatted(Formatting.DARK_GRAY), true);
				world.playSound(null, player.getBlockPos(), ModSounds.FLASHLIGHT_CLICK, SoundCategory.PLAYERS, 0.4f, 0.5f);
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
		float charge = 1.0f - (float) stack.getDamage() / stack.getMaxDamage();
		return charge > 0.25f ? 0xE8D36A : 0xC0392B;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		int percent = Math.round(100f * (1.0f - (float) stack.getDamage() / stack.getMaxDamage()));
		tooltip.add(Text.translatable("tooltip.nullspace.charge", percent).formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("tooltip.nullspace.flashlight_hint").formatted(Formatting.DARK_GRAY));
	}
}
