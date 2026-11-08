package com.nullspace.registry;

import com.nullspace.NullspaceConfig;
import com.nullspace.NullspaceMod;
import com.nullspace.item.BatteryItem;
import com.nullspace.item.ChalkItem;
import com.nullspace.item.LevelMapItem;
import com.nullspace.item.NoteItem;
import com.nullspace.item.RadioItem;
import com.nullspace.item.ThermosItem;
import com.nullspace.item.FlashlightItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.Block;
import net.minecraft.component.type.FoodComponent;
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

	public static final Item NOTE = register("note",
			new NoteItem(new Item.Settings().maxCount(1).component(ModComponents.NOTE_ID, 0)));

	public static final Item THERMOS = register("thermos",
			new ThermosItem(new Item.Settings().maxCount(4)
					.food(new FoodComponent.Builder().nutrition(3).saturationModifier(0.6f).alwaysEdible().build())));

	public static final Item CHALK = register("chalk",
			new ChalkItem(new Item.Settings().maxDamage(48)));

	public static final Item RADIO = register("radio",
			new RadioItem(new Item.Settings().maxDamage(900)
					.component(ModComponents.RADIO_ON, false)
					.component(ModComponents.RADIO_FREQ, 0)));

	public static final Item LEVEL_MAP = register("level_map",
			new LevelMapItem(new Item.Settings().maxCount(1)));

	public static final ItemGroup GROUP = Registry.register(Registries.ITEM_GROUP, NullspaceMod.id("main"),
			FabricItemGroup.builder()
					.icon(() -> new ItemStack(FLASHLIGHT))
					.displayName(Text.translatable("itemGroup.nullspace.main"))
					.entries((context, entries) -> {
						entries.add(FLASHLIGHT);
						entries.add(BATTERY);
						entries.add(THERMOS);
						entries.add(CHALK);
						entries.add(RADIO);
						entries.add(LEVEL_MAP);
						for (int i = 0; i < NoteItem.PAGES.length; i++) {
							entries.add(NoteItem.create(i));
						}
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
