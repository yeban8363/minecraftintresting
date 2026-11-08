package com.nullspace.client;

import com.nullspace.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Что игрок видел. Раз в полсекунды записываем чанки вокруг (на уровне головы):
 * стена, проход, выход, дверь. Только память этого клиента — карта не знает
 * ничего, чего ты не видел сам.
 */
public final class ClientMapMemory {
	public static final byte UNKNOWN = 0, OPEN = 1, WALL = 2, EXIT = 3, DOOR = 4;

	private static final Map<String, Map<Long, byte[]>> DATA = new HashMap<>();
	private static int ticks;

	public static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || !ClientAtmosphere.inNullspace(client)) return;
		if (++ticks % 10 != 0) return;

		Map<Long, byte[]> map = DATA.computeIfAbsent(dimension(client), k -> new HashMap<>());
		int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
		int y = client.player.getBlockY() + 1;
		BlockPos.Mutable p = new BlockPos.Mutable();

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int cx = pcx + dx, cz = pcz + dz;
				Chunk chunk = client.world.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				byte[] cells = map.computeIfAbsent(key(cx, cz), k -> new byte[256]);
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						p.set(cx * 16 + lx, y, cz * 16 + lz);
						cells[lz * 16 + lx] = code(chunk.getBlockState(p), client, p);
					}
				}
			}
		}
	}

	private static byte code(BlockState s, MinecraftClient client, BlockPos p) {
		if (s.isOf(ModBlocks.EXIT_DOOR) || s.isOf(ModBlocks.EXIT_SIGN)) return EXIT;
		if (s.isOf(ModBlocks.ANOMALY_DOOR) || s.isOf(ModBlocks.ELEVATOR)) return DOOR;
		if (s.isAir() || s.getCollisionShape(client.world, p).isEmpty()) return OPEN;
		return WALL;
	}

	public static byte get(String dimension, int x, int z) {
		Map<Long, byte[]> map = DATA.get(dimension);
		if (map == null) return UNKNOWN;
		byte[] cells = map.get(key(x >> 4, z >> 4));
		return cells == null ? UNKNOWN : cells[(z & 15) * 16 + (x & 15)];
	}

	public static String dimension(MinecraftClient client) {
		return client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	/** Новый мир — новая память. */
	public static void clear() {
		DATA.clear();
	}

	private ClientMapMemory() {
	}
}
