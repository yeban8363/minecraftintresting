package com.nullspace.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nullspace.NullspaceMod;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Генератор Уровня 0: бесконечные жёлтые коридоры. */
public class OfficeChunkGenerator extends ChunkGenerator {
	public static final MapCodec<OfficeChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(RegistryOps.getEntryLookupCodec(RegistryKeys.BIOME))
					.apply(instance, instance.stable(OfficeChunkGenerator::new)));

	private volatile OfficeLayout layout;

	public OfficeChunkGenerator(RegistryEntryLookup<Biome> biomeLookup) {
		super(new FixedBiomeSource(biomeLookup.getOrThrow(NullspaceDimensions.LEVEL_0_BIOME)));
	}

	/** Сид мира достаётся через NoiseConfig: так планировка у каждого мира своя. */
	public OfficeLayout layout(NoiseConfig noiseConfig) {
		OfficeLayout l = layout;
		if (l == null) {
			long seed = noiseConfig.getOrCreateRandomDeriver(NullspaceMod.id("office_layout")).split(0, 0, 0).nextLong();
			l = new OfficeLayout(seed);
			layout = l;
		}
		return l;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> getCodec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
		OfficeLayout l = layout(noiseConfig);
		ChunkPos chunkPos = chunk.getPos();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		Heightmap oceanFloor = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);

		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = chunkPos.getStartX() + lx;
				int z = chunkPos.getStartZ() + lz;
				BlockState[] column = l.column(x, z);
				for (int y = 0; y < column.length; y++) {
					BlockState state = column[y];
					if (state.isAir()) continue;
					chunk.setBlockState(pos.set(x, y, z), state, false);
					oceanFloor.trackUpdate(lx, y, lz, state);
					surface.trackUpdate(lx, y, lz, state);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk, GenerationStep.Carver carverStep) {
	}

	@Override
	public void buildSurface(ChunkRegion region, StructureAccessor structureAccessor, NoiseConfig noiseConfig, Chunk chunk) {
	}

	@Override
	public void populateEntities(ChunkRegion region) {
	}

	@Override
	public int getWorldHeight() {
		return 32;
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public int getMinimumY() {
		return 0;
	}

	@Override
	public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
		return OfficeLayout.HEIGHT;
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		return new VerticalBlockSample(0, layout(noiseConfig).column(x, z));
	}

	@Override
	public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
		OfficeLayout l = layout(noiseConfig);
		int cx = Math.floorDiv(pos.getX(), OfficeLayout.CELL);
		int cz = Math.floorDiv(pos.getZ(), OfficeLayout.CELL);
		text.add("NULLSPACE / Level 0 zone: " + l.styleAt(cx, cz));
	}
}
