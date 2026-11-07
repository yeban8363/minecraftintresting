package com.nullspace.item;

import com.nullspace.registry.ModComponents;
import com.nullspace.registry.ModItems;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Записка исследователя. Текст лежит в файлах локализации:
 * note.nullspace.<номер>.p<страница>. %1$s — ник читающего, %2$s — дата из будущего.
 */
public class NoteItem extends Item {
	/** Сколько страниц в каждой записке. Номер записки = индекс. */
	public static final int[] PAGES = {2, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 1};

	/** Открывает экран чтения. Подставляется клиентом; на сервере ничего не делает. */
	public static BiConsumer<ItemStack, PlayerEntity> opener = (stack, player) -> {
	};

	public NoteItem(Settings settings) {
		super(settings);
	}

	public static int id(ItemStack stack) {
		Integer id = stack.get(ModComponents.NOTE_ID);
		int v = id == null ? 0 : id;
		return Math.floorMod(v, PAGES.length);
	}

	public static ItemStack create(int id) {
		ItemStack stack = new ItemStack(ModItems.NOTE);
		stack.set(ModComponents.NOTE_ID, Math.floorMod(id, PAGES.length));
		return stack;
	}

	public static ItemStack random(Random random) {
		return create(random.nextInt(PAGES.length));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) {
			opener.accept(stack, user);
		}
		return TypedActionResult.success(stack, world.isClient());
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("tooltip.nullspace.note", id(stack) + 1).formatted(Formatting.GRAY));
	}
}
