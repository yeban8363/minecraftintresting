package com.nullspace.mechanic;

import com.nullspace.world.AquaticChunkGenerator;
import com.nullspace.world.MallChunkGenerator;
import com.nullspace.world.OfficeChunkGenerator;
import com.nullspace.world.ParkingChunkGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Где ближайший выход. Планировки уровней — чистые функции от сида,
 * поэтому выход можно найти даже в ещё не сгенерированных чанках.
 */
public final class ExitLocator {
	public static final int RADIUS = 640;

	@Nullable
	public static BlockPos find(ServerWorld world, BlockPos from) {
		ChunkGenerator gen = world.getChunkManager().getChunkGenerator();
		NoiseConfig noise = world.getChunkManager().getNoiseConfig();
		int x = from.getX(), z = from.getZ();
		if (gen instanceof OfficeChunkGenerator g) return g.layout(noise).nearestExit(x, z, RADIUS);
		if (gen instanceof AquaticChunkGenerator g) return g.layout(noise).nearestExit(x, z, RADIUS);
		if (gen instanceof ParkingChunkGenerator g) return g.layout(noise).nearestExit(x, z, RADIUS);
		if (gen instanceof MallChunkGenerator g) return g.layout(noise).nearestExit(x, z, RADIUS);
		return null;
	}

	private ExitLocator() {
	}
}
