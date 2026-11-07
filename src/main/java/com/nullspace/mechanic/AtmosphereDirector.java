package com.nullspace.mechanic;

import com.nullspace.NullspaceConfig;
import com.nullspace.network.SilencePayload;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * «Директор» атмосферы: раскладывает события по времени, чтобы пространство
 * ощущалось живым. Все звуки слышит только конкретный игрок.
 *
 * События:
 *  - шаги за стеной: повторяют движение игрока с запаздыванием в полсекунды;
 *  - стук: если долго стоять неподвижно у стены, с той стороны постучат;
 *  - дыхание за спиной;
 *  - отдалённые непонятные звуки;
 *  - тишина: обрывается всё, даже гул ламп. Обычно после этого не происходит ничего.
 */
public final class AtmosphereDirector {
	private static final int ECHO_DELAY = 10;

	private static final class State {
		final Deque<Vec3d> trail = new ArrayDeque<>();
		long nextEvent;
		long stepsUntil;
		long silenceUntil;
		int stillTicks;
		boolean knocked;
		Vec3d echoSide = Vec3d.ZERO;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	public static void tick(MinecraftServer server) {
		if (!NullspaceConfig.get().atmosphere) return;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player.isSpectator() || !NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
				STATES.remove(player.getUuid());
				continue;
			}
			State s = STATES.computeIfAbsent(player.getUuid(), id -> {
				State fresh = new State();
				fresh.nextEvent = player.getServerWorld().getTime() + 200;
				return fresh;
			});
			tickPlayer(player, s);
		}
	}

	public static void forget(ServerPlayerEntity player) {
		STATES.remove(player.getUuid());
	}

	public static boolean isSilent(ServerPlayerEntity player) {
		State s = STATES.get(player.getUuid());
		return s != null && player.getServerWorld().getTime() < s.silenceUntil;
	}

	/** Принудительно включить тишину (для команды). */
	public static void forceSilence(ServerPlayerEntity player, int ticks) {
		State s = STATES.computeIfAbsent(player.getUuid(), id -> new State());
		startSilence(player, s, ticks);
	}

	private static void tickPlayer(ServerPlayerEntity player, State s) {
		ServerWorld world = player.getServerWorld();
		long now = world.getTime();
		Random random = player.getRandom();
		Vec3d pos = player.getPos();

		Vec3d last = s.trail.peekLast();
		boolean moving = last != null && last.squaredDistanceTo(pos) > 0.0025;
		s.trail.addLast(pos);
		while (s.trail.size() > ECHO_DELAY + 1) s.trail.removeFirst();

		if (now < s.silenceUntil) return;

		// --- стук: долго стоишь у стены ---
		if (moving) {
			s.stillTicks = 0;
			s.knocked = false;
		} else if (++s.stillTicks >= 600 && !s.knocked) {
			BlockPos wall = adjacentWall(world, player.getBlockPos().up());
			if (wall != null) {
				s.knocked = true;
				// Звуковой файл сам содержит три удара с паузами.
				playAt(player, ModSounds.KNOCK, Vec3d.ofCenter(wall), 0.9f, 1.0f);
			}
		}

		// --- эпизод шагов за стеной ---
		if (now < s.stepsUntil && moving && now % 8 == 0 && s.trail.size() > ECHO_DELAY) {
			Vec3d echo = s.trail.peekFirst().add(s.echoSide);
			playAt(player, ModSounds.STEPS_BEHIND_WALL, echo, 0.55f, 0.9f + random.nextFloat() * 0.15f);
		}

		// --- выбор следующего события ---
		if (now >= s.nextEvent) {
			// Чем ниже Стабильность, тем чаще что-то происходит.
			float stability = StabilityManager.get(player);
			double pace = 0.35 + 0.65 * (stability / StabilityManager.MAX);
			s.nextEvent = now + (long) (20L * (20 + random.nextInt(40)) * pace);

			if (stability < 60 && random.nextInt(100) < 25 && ChalkEvents.tryHaunt(player, random)) {
				return;
			}
			int roll = random.nextInt(100);
			if (roll < 35) {
				s.stepsUntil = now + 20L * (6 + random.nextInt(10));
				Vec3d look = player.getRotationVec(1.0f);
				Vec3d side = new Vec3d(-look.z, 0, look.x).normalize().multiply(random.nextBoolean() ? 5 : -5);
				s.echoSide = side;
			} else if (roll < 60) {
				Vec3d far = pos.add((random.nextDouble() - 0.5) * 60, 0, (random.nextDouble() - 0.5) * 60);
				playAt(player, ModSounds.DISTANT, far, 2.5f, 0.7f + random.nextFloat() * 0.5f);
			} else if (roll < 70) {
				Vec3d behind = pos.subtract(player.getRotationVec(1.0f).multiply(1.6)).add(0, 1.5, 0);
				playAt(player, ModSounds.BREATH, behind, 0.35f, 0.9f);
			} else if (roll < 85) {
				startSilence(player, s, 20 * (15 + random.nextInt(25)));
			}
			// иначе — ничего. Это тоже событие.
		}
	}

	private static void startSilence(ServerPlayerEntity player, State s, int ticks) {
		s.silenceUntil = player.getServerWorld().getTime() + ticks;
		s.stepsUntil = 0;
		NullspaceTeleporter.cutAllSounds(player);
		if (ServerPlayNetworking.canSend(player, SilencePayload.ID)) {
			ServerPlayNetworking.send(player, new SilencePayload(ticks));
		}
	}

	private static BlockPos adjacentWall(ServerWorld world, BlockPos pos) {
		for (Direction dir : Direction.Type.HORIZONTAL) {
			BlockPos p = pos.offset(dir);
			if (world.getBlockState(p).isOf(ModBlocks.OFFICE_WALLPAPER)) return p;
		}
		return null;
	}

	private static void playAt(ServerPlayerEntity player, SoundEvent sound, Vec3d at, float volume, float pitch) {
		player.networkHandler.sendPacket(new PlaySoundS2CPacket(
				Registries.SOUND_EVENT.getEntry(sound), SoundCategory.AMBIENT,
				at.x, at.y, at.z, volume, pitch, player.getRandom().nextLong()));
	}

	private AtmosphereDirector() {
	}
}
