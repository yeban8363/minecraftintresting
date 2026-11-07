package com.nullspace.mechanic;

import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Обесточивание. Лампы вокруг игрока гаснут волной — от дальних к ближним,
 * несколько секунд полной темноты, потом свет возвращается, начиная с ближних.
 */
public final class BlackoutManager {
	private static final int RADIUS = 22;
	private static final int WAVE = 30;

	private record Lamp(BlockPos pos, BlockState original, int offAt, int onAt) {
	}

	private static final class Blackout {
		final ServerWorld world;
		final UUID player;
		final List<Lamp> lamps;
		final int darkFrom, darkUntil, end;
		int tick;

		Blackout(ServerWorld world, UUID player, List<Lamp> lamps, int darkFrom, int darkUntil, int end) {
			this.world = world;
			this.player = player;
			this.lamps = lamps;
			this.darkFrom = darkFrom;
			this.darkUntil = darkUntil;
			this.end = end;
		}
	}

	private static final List<Blackout> ACTIVE = new ArrayList<>();
	private static final Set<Long> LOCKED = new HashSet<>();

	public static boolean start(ServerPlayerEntity player, int darkTicks) {
		for (Blackout b : ACTIVE) if (b.player.equals(player.getUuid())) return false;

		ServerWorld world = player.getServerWorld();
		BlockPos c = player.getBlockPos();
		List<BlockPos> found = new ArrayList<>();
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				for (int dy = 1; dy <= 8; dy++) {
					p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					BlockState s = world.getBlockState(p);
					if (offStateFor(s) != null && !LOCKED.contains(p.asLong())) {
						found.add(p.toImmutable());
					}
				}
			}
		}

		found.sort(Comparator.comparingDouble((BlockPos b) -> b.getSquaredDistance(c)).reversed());
		int n = Math.max(1, found.size());
		List<Lamp> lamps = new ArrayList<>();
		for (int i = 0; i < found.size(); i++) {
			BlockPos pos = found.get(i);
			int offAt = i * WAVE / n;
			int onAt = WAVE + darkTicks + (n - 1 - i) * 20 / n;
			lamps.add(new Lamp(pos, world.getBlockState(pos), offAt, onAt));
			LOCKED.add(pos.asLong());
		}
		player.playSoundToPlayer(ModSounds.BLACKOUT, SoundCategory.AMBIENT, 1.0f, 1.0f);
		ACTIVE.add(new Blackout(world, player.getUuid(), lamps, WAVE, WAVE + darkTicks, WAVE + darkTicks + 25));
		return true;
	}

	/** Темно ли сейчас вокруг игрока из-за обесточивания. */
	public static boolean isDark(ServerPlayerEntity player) {
		for (Blackout b : ACTIVE) {
			if (b.player.equals(player.getUuid()) && b.tick >= b.darkFrom && b.tick < b.darkUntil) return true;
		}
		return false;
	}

	public static boolean isActive(ServerPlayerEntity player) {
		for (Blackout b : ACTIVE) if (b.player.equals(player.getUuid())) return true;
		return false;
	}

	public static void tick(MinecraftServer server) {
		Iterator<Blackout> it = ACTIVE.iterator();
		while (it.hasNext()) {
			Blackout b = it.next();
			b.tick++;
			for (Lamp lamp : b.lamps) {
				if (b.tick == lamp.offAt()) {
					BlockState off = offStateFor(b.world.getBlockState(lamp.pos()));
					if (off != null) {
						b.world.setBlockState(lamp.pos(), off, Block.NOTIFY_ALL);
						if (b.world.getRandom().nextInt(4) == 0) {
							b.world.playSound(null, lamp.pos(), ModSounds.LAMP_BUZZ, SoundCategory.BLOCKS, 0.5f, 0.7f);
						}
					}
				}
				if (b.tick == lamp.onAt()) restore(b.world, lamp);
			}
			if (b.tick == b.darkUntil) {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(b.player);
				if (player != null) player.playSoundToPlayer(ModSounds.POWER_ON, SoundCategory.AMBIENT, 0.8f, 1.0f);
			}
			if (b.tick > b.end) {
				for (Lamp lamp : b.lamps) LOCKED.remove(lamp.pos().asLong());
				it.remove();
			}
		}
	}

	/** Во что превращается лампа при обесточивании; null — это не лампа. */
	private static BlockState offStateFor(BlockState s) {
		if (s.isOf(ModBlocks.CEILING_LIGHT) || s.isOf(ModBlocks.CEILING_LIGHT_FLICKERING)) return ModBlocks.CEILING_LIGHT_OFF.getDefaultState();
		if (s.isOf(ModBlocks.SODIUM_LAMP)) return ModBlocks.SODIUM_LAMP_OFF.getDefaultState();
		return null;
	}

	private static void restore(ServerWorld world, Lamp lamp) {
		BlockState now = world.getBlockState(lamp.pos());
		if (now.isOf(ModBlocks.CEILING_LIGHT_OFF) || now.isOf(ModBlocks.SODIUM_LAMP_OFF)) {
			world.setBlockState(lamp.pos(), lamp.original(), Block.NOTIFY_ALL);
		}
	}

	/** При остановке сервера вернуть все лампы. */
	public static void restoreAll() {
		for (Blackout b : ACTIVE) {
			for (Lamp lamp : b.lamps) restore(b.world, lamp);
		}
		ACTIVE.clear();
		LOCKED.clear();
	}

	private BlackoutManager() {
	}
}
