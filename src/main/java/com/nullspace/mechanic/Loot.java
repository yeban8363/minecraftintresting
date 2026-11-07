package com.nullspace.mechanic;

import com.nullspace.item.NoteItem;
import com.nullspace.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/** Содержимое ящиков с припасами. */
public final class Loot {
	public static List<ItemStack> crate(Random r) {
		List<ItemStack> out = new ArrayList<>();
		if (r.nextFloat() < 0.60f) out.add(new ItemStack(ModItems.BATTERY, 1 + r.nextInt(2)));
		if (r.nextFloat() < 0.35f) out.add(new ItemStack(ModItems.THERMOS));
		if (r.nextFloat() < 0.30f) out.add(new ItemStack(ModItems.CHALK));
		if (r.nextFloat() < 0.45f) out.add(NoteItem.random(r));
		if (r.nextFloat() < 0.06f) out.add(new ItemStack(ModItems.FLASHLIGHT));
		if (out.isEmpty()) out.add(new ItemStack(ModItems.BATTERY));
		return out;
	}

	private Loot() {
	}
}
