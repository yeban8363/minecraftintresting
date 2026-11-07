package com.nullspace.world;

import com.nullspace.NullspaceMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

public final class NullspaceDimensions {
	/** Уровень 0 — OFFICE. Сам мир задаётся JSON-файлами в data/nullspace/dimension. */
	public static final RegistryKey<World> LEVEL_0 = RegistryKey.of(RegistryKeys.WORLD, NullspaceMod.id("level_0"));
	/** Уровень 1 — AQUATIC. */
	public static final RegistryKey<World> LEVEL_1 = RegistryKey.of(RegistryKeys.WORLD, NullspaceMod.id("level_1"));
	public static final RegistryKey<Biome> LEVEL_1_BIOME = RegistryKey.of(RegistryKeys.BIOME, NullspaceMod.id("level_1"));
	/** Уровень 2 — PARKING. */
	public static final RegistryKey<World> LEVEL_2 = RegistryKey.of(RegistryKeys.WORLD, NullspaceMod.id("level_2"));
	public static final RegistryKey<Biome> LEVEL_2_BIOME = RegistryKey.of(RegistryKeys.BIOME, NullspaceMod.id("level_2"));
	/** Стадион за аномальной дверью. */
	public static final RegistryKey<World> STADIUM = RegistryKey.of(RegistryKeys.WORLD, NullspaceMod.id("stadium"));
	public static final RegistryKey<Biome> LEVEL_0_BIOME = RegistryKey.of(RegistryKeys.BIOME, NullspaceMod.id("level_0"));

	public static boolean isNullspace(RegistryKey<World> key) {
		return key.getValue().getNamespace().equals(NullspaceMod.MOD_ID);
	}

	public static void register() {
		Registry.register(Registries.CHUNK_GENERATOR, NullspaceMod.id("office"), OfficeChunkGenerator.CODEC);
		Registry.register(Registries.CHUNK_GENERATOR, NullspaceMod.id("stadium"), StadiumChunkGenerator.CODEC);
		Registry.register(Registries.CHUNK_GENERATOR, NullspaceMod.id("aquatic"), AquaticChunkGenerator.CODEC);
		Registry.register(Registries.CHUNK_GENERATOR, NullspaceMod.id("parking"), ParkingChunkGenerator.CODEC);
	}

	private NullspaceDimensions() {
	}
}
