package com.nullspace.mechanic;

import com.nullspace.block.ChalkMarkBlock;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import com.nullspace.world.OfficeChunkGenerator;
import com.nullspace.world.OfficeLayout;
import net.minecraft.block.Block;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Коридор-петля на Уровне 0. Длинный одинаковый коридор: пройдёшь его середину —
 * и снова окажешься на двадцать шагов позади. С каждым кругом что-то меняется:
 *   1 — лампы начинают мерцать;
 *   2 — на стене появляется стрелка мелом, указывающая назад;
 *   3 — гаснут все лампы, кроме дальней;
 *   4 — на полу лежит записка. После этого петля отпускает.
 * Повернуть назад можно в любой момент.
 */
public final class LoopManager {
	private static final int MAX_LOOPS = 4;

	private static final Map<UUID, Double> PREV_X = new HashMap<>();
	private static final Map<UUID, Map<Long, Integer>> LOOPS = new HashMap<>();

	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getWorld(NullspaceDimensions.LEVEL_0);
		if (world == null || !(world.getChunkManager().getChunkGenerator() instanceof OfficeChunkGenerator gen)) return;
		OfficeLayout layout = gen.layout(world.getChunkManager().getNoiseConfig());
		int size = OfficeLayout.zoneSize();

		for (ServerPlayerEntity player : world.getPlayers()) {
			UUID id = player.getUuid();
			int zx = Math.floorDiv((int) Math.floor(player.getX()), size);
			int zz = Math.floorDiv((int) Math.floor(player.getZ()), size);
			if (!layout.loopZone(zx, zz)) {
				PREV_X.remove(id);
				continue;
			}
			double lx = player.getX() - zx * size;
			double lz = player.getZ() - zz * size;
			boolean inside = lz > OfficeLayout.LOOP_WALL_N && lz < OfficeLayout.LOOP_WALL_S
					&& lx > OfficeLayout.LOOP_FROM && lx < OfficeLayout.LOOP_TO;
			Double prev = PREV_X.put(id, lx);
			if (!inside || prev == null) continue;
			if (prev >= OfficeLayout.LOOP_TRIGGER || lx < OfficeLayout.LOOP_TRIGGER) continue;

			long zoneKey = ((long) zx << 32) ^ (zz & 0xFFFFFFFFL);
			Map<Long, Integer> mine = LOOPS.computeIfAbsent(id, k -> new HashMap<>());
			int count = mine.getOrDefault(zoneKey, 0);
			if (count >= MAX_LOOPS) continue;
			count++;
			mine.put(zoneKey, count);

			player.networkHandler.requestTeleport(player.getX() - OfficeLayout.LOOP_JUMP, player.getY(), player.getZ(),
					player.getYaw(), player.getPitch());
			PREV_X.put(id, lx - OfficeLayout.LOOP_JUMP);
			change(world, zx * size, zz * size, count);
		}
	}

	private static void change(ServerWorld world, int ox, int oz, int count) {
		int y = OfficeLayout.FLOOR_Y + 1;
		int midZ = oz + (OfficeLayout.LOOP_WALL_N + OfficeLayout.LOOP_WALL_S) / 2;
		switch (count) {
			case 1 -> {
				for (int lx = 20; lx <= 50; lx += 10) {
					world.setBlockState(new BlockPos(ox + lx, OfficeLayout.CEILING_Y, midZ),
							ModBlocks.CEILING_LIGHT_FLICKERING.getDefaultState(), Block.NOTIFY_ALL);
				}
			}
			case 2 -> world.setBlockState(new BlockPos(ox + 30, y + 1, oz + OfficeLayout.LOOP_WALL_N + 1),
					ModBlocks.CHALK_MARK.getDefaultState()
							.with(ChalkMarkBlock.FACING, Direction.SOUTH)
							.with(ChalkMarkBlock.STRANGER, true), Block.NOTIFY_ALL);
			case 3 -> {
				for (int lx = OfficeLayout.LOOP_FROM; lx <= OfficeLayout.LOOP_TO; lx++) {
					if (lx % 5 != 0 || lx == 50) continue;
					world.setBlockState(new BlockPos(ox + lx, OfficeLayout.CEILING_Y, midZ),
							ModBlocks.CEILING_LIGHT_OFF.getDefaultState(), Block.NOTIFY_ALL);
				}
				world.playSound(null, new BlockPos(ox + 30, y, midZ), ModSounds.BLACKOUT, SoundCategory.AMBIENT, 1.0f, 0.8f);
			}
			case 4 -> world.setBlockState(new BlockPos(ox + 36, y, midZ), ModBlocks.PAPER_PILE.getDefaultState(), Block.NOTIFY_ALL);
			default -> {
			}
		}
	}

	public static void forget(ServerPlayerEntity player) {
		PREV_X.remove(player.getUuid());
	}

	private LoopManager() {
	}
}
