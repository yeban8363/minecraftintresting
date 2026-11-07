package com.nullspace.registry;

import com.nullspace.NullspaceMod;
import com.nullspace.block.GeneratorBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModBlockEntities {
	public static final BlockEntityType<GeneratorBlockEntity> GENERATOR = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			NullspaceMod.id("generator"),
			BlockEntityType.Builder.create(GeneratorBlockEntity::new, ModBlocks.GENERATOR).build(null));

	public static void register() {
	}

	private ModBlockEntities() {
	}
}
