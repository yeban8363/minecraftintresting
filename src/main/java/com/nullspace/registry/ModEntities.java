package com.nullspace.registry;

import com.nullspace.NullspaceMod;
import com.nullspace.entity.SwimmerEntity;
import com.nullspace.entity.TenantEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
	public static final EntityType<TenantEntity> TENANT = Registry.register(Registries.ENTITY_TYPE,
			NullspaceMod.id("tenant"),
			EntityType.Builder.create(TenantEntity::new, SpawnGroup.MISC)
					.dimensions(0.6f, 2.4f)
					.maxTrackingRange(10)
					.makeFireImmune()
					.build("tenant"));

	public static final EntityType<SwimmerEntity> SWIMMER = Registry.register(Registries.ENTITY_TYPE,
			NullspaceMod.id("swimmer"),
			EntityType.Builder.create(SwimmerEntity::new, SpawnGroup.MISC)
					.dimensions(0.6f, 0.6f)
					.maxTrackingRange(8)
					.build("swimmer"));

	public static void register() {
		FabricDefaultAttributeRegistry.register(TENANT, TenantEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(SWIMMER, SwimmerEntity.createAttributes());
	}

	private ModEntities() {
	}
}
