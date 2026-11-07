package com.nullspace.registry;

import com.nullspace.NullspaceConfig;
import com.nullspace.NullspaceMod;
import com.nullspace.item.BatteryItem;
import com.nullspace.item.FlashlightItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

public final class ModItems {
	public static final Item FLASHLIGHT = register("flashlight",
			new FlashlightItem(new Item.Settings()
					.maxDamage(Math.max(1, NullspaceConfig.get().flashlightSeconds))
					.component(ModComponents.FLASHLIGHT_ON, false)));

	public static final Item BATTERY = register("battery",
			new BatteryItem(new Item.Settings().maxCount(16)));

	public static final ItemGroup GROUP = Registry.register(Registries.ITEM_GROUP, NullspaceMod.id("main"),
			FabricItemGroup.builder()
					.icon(() -> new ItemStack(FLASHLIGHT))
					.displayName(Text.translatable("itemGroup.nullspace.main"))
					.entries((context, entries) -> {
						entries.add(FLASHLIGHT);
						entries.add(BATTERY);
						for (Block block : ModBlocks.WITH_ITEMS) {
							entries.add(block);
						}
					})
					.build());

	private static Item register(String name, Item item) {
		return Registry.register(Registries.ITEM, NullspaceMod.id(name), item);
	}

	public static void register() {
	}

	private ModItems() {
	}
}
