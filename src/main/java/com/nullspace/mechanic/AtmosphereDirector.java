package com.nullspace.mechanic;

import com.nullspace.NullspaceConfig;
import com.nullspace.network.SilencePayload;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
 * ощущалось живым. Чем ниже Стабильность, тем чаще и страшнее события.
 *
 * Всегда:      шаги за стеной, отдалённые звуки, дыхание за спиной, тишина,
 *              стук, если долго стоять у стены.
 * Стаб. < 70:  обесточивание — лампы гаснут волной.
 * Стаб. < 60:  меловые метки меняются.
 * Стаб. < 50:  кто-то бежит к тебе со спины — и шаги обрываются вплотную.
 * Стаб. < 35:  шёпот твоего ника в чате; «<ты> присоединился к игре».
 *
 * Уровень 2:   фары включаются за спиной, сигнализации срабатывают цепочкой,
 *              у пустой машины заводится двигатель.
 *
 * Здесь никого нет. Всё это — ты и пространство.
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
		// бегущие шаги
		int runLeft;
		Vec3d runPos = Vec3d.ZERO;
		Vec3d runStep = Vec3d.ZERO;
		long runSilenceAt;
		// звуки, запланированные на будущее (цепочка сигнализаций и т.п.)
		final Deque<Scheduled> queue = new ArrayDeque<>();
	}

	private record Scheduled(long at, Vec3d pos, SoundEvent sound, float volume, float pitch) {
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

	/** Запустить бегущие шаги (для команды). */
	public static void forceRun(ServerPlayerEntity player) {
		State s = STATES.computeIfAbsent(player.getUuid(), id -> new State());
		startRun(player, s);
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

		// --- запланированные звуки ---
		while (!s.queue.isEmpty() && s.queue.peekFirst().at() <= now) {
			Scheduled q = s.queue.pollFirst();
			playAt(player, q.sound(), q.pos(), q.volume(), q.pitch());
		}

		// --- бегущие шаги идут даже сквозь тишину: тишина — их конец ---
		if (s.runLeft > 0 && now % 5 == 0) {
			float volume = 0.45f + 0.5f * (1f - s.runLeft / 12f);
			playAt(player, stepsSound(player), s.runPos, volume, 1.1f + random.nextFloat() * 0.1f);
			s.runPos = s.runPos.add(s.runStep);
			if (--s.runLeft == 0) {
				s.runSilenceAt = now + 4; // дать последнему шагу прозвучать
			}
		}
		if (s.runSilenceAt > 0 && now >= s.runSilenceAt) {
			s.runSilenceAt = 0;
			startSilence(player, s, 20 * 12);
		}

		if (now < s.silenceUntil) return;

		// --- стук: долго стоишь у стены ---
		if (moving) {
			s.stillTicks = 0;
			s.knocked = false;
		} else if (++s.stillTicks >= 600 && !s.knocked) {
			BlockPos wall = adjacentWall(world, player.getBlockPos().up());
			if (wall != null) {
				s.knocked = true;
				playAt(player, ModSounds.KNOCK, Vec3d.ofCenter(wall), 0.9f, 1.0f);
			}
		}

		// --- эпизод шагов за стеной ---
		if (now < s.stepsUntil && moving && now % 8 == 0 && s.trail.size() > ECHO_DELAY) {
			Vec3d echo = s.trail.peekFirst().add(s.echoSide);
			playAt(player, stepsSound(player), echo, 0.55f, 0.9f + random.nextFloat() * 0.15f);
		}

		if (now < s.nextEvent) return;

		// --- выбор следующего события ---
		float stability = StabilityManager.get(player);
		double pace = 0.35 + 0.65 * (stability / StabilityManager.MAX);
		s.nextEvent = now + (long) (20L * (20 + random.nextInt(40)) * pace);
		int roll = random.nextInt(100);

		if (stability < 70 && roll < 9 && !BlackoutManager.isActive(player)) {
			BlackoutManager.start(player, 20 * (12 + random.nextInt(10)));
			return;
		}
		if (stability < 60 && roll >= 17 && roll < 25) {
			if (ChalkEvents.tryHaunt(player, random)) return;
		}
		if (stability < 50 && roll >= 25 && roll < 33) {
			startRun(player, s);
			return;
		}
		if (stability < 35 && roll >= 33 && roll < 37) {
			player.sendMessage(Text.literal("…" + player.getGameProfile().getName() + "…")
					.formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
			Vec3d behind = pos.subtract(player.getRotationVec(1.0f).multiply(1.2)).add(0, 1.6, 0);
			playAt(player, ModSounds.WHISPER, behind, 0.7f, 0.9f);
			return;
		}
		if (stability < 35 && roll >= 37 && roll < 40) {
			player.sendMessage(Text.translatable("multiplayer.player.joined", player.getDisplayName())
					.formatted(Formatting.YELLOW), false);
			return;
		}

		if (player.getWorld().getRegistryKey() == NullspaceDimensions.LEVEL_2 && random.nextInt(100) < 35) {
			parkingEvent(player, s, random, stability, now);
			return;
		}

		int basic = random.nextInt(100);
		if (basic < 35) {
			s.stepsUntil = now + 20L * (6 + random.nextInt(10));
			Vec3d look = player.getRotationVec(1.0f);
			Vec3d side = new Vec3d(-look.z, 0, look.x);
			s.echoSide = side.lengthSquared() > 1.0E-4
					? side.normalize().multiply(random.nextBoolean() ? 5 : -5)
					: new Vec3d(5, 0, 0);
		} else if (basic < 60) {
			Vec3d far = pos.add((random.nextDouble() - 0.5) * 60, 0, (random.nextDouble() - 0.5) * 60);
			playAt(player, distantSound(player), far, 2.5f, 0.7f + random.nextFloat() * 0.5f);
		} else if (basic < 70) {
			Vec3d behind = pos.subtract(player.getRotationVec(1.0f).multiply(1.6)).add(0, 1.5, 0);
			playAt(player, ModSounds.BREATH, behind, 0.35f, 0.9f);
		} else if (basic < 85) {
			startSilence(player, s, 20 * (15 + random.nextInt(25)));
		}
		// иначе — ничего. Это тоже событие.
	}

	// ---------------- парковка ----------------

	/**
	 * События Уровня 2. В машинах никого нет, но:
	 *  - за спиной у пустых машин включаются фары;
	 *  - сигнализации срабатывают цепочкой — от дальней машины к ближней;
	 *  - у соседней машины заводится двигатель.
	 */
	private static void parkingEvent(ServerPlayerEntity player, State s, Random random, float stability, long now) {
		ServerWorld world = player.getServerWorld();
		int roll = random.nextInt(100);
		if (roll < 40 && headlightsBehind(player, world)) return;
		if (roll < 75 && stability < 80 && alarmChain(player, world, s, now)) return;
		BlockPos car = nearestCarLight(player, world, 24);
		if (car != null) playAt(player, ModSounds.ENGINE_IDLE, Vec3d.ofCenter(car), 1.2f, 0.9f + random.nextFloat() * 0.2f);
	}

	private static boolean isCarLight(BlockState state) {
		return state.isOf(ModBlocks.HEADLIGHT) || state.isOf(ModBlocks.HEADLIGHT_OFF) || state.isOf(ModBlocks.TAILLIGHT);
	}

	private static boolean headlightsBehind(ServerPlayerEntity player, ServerWorld world) {
		Vec3d look = player.getRotationVec(1.0f);
		BlockPos c = player.getBlockPos();
		int switched = 0;
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -20; dx <= 20 && switched < 4; dx++) {
			for (int dz = -20; dz <= 20 && switched < 4; dz++) {
				p.set(c.getX() + dx, c.getY(), c.getZ() + dz);
				if (!world.getBlockState(p).isOf(ModBlocks.HEADLIGHT_OFF)) continue;
				Vec3d to = Vec3d.ofCenter(p).subtract(player.getEyePos()).normalize();
				if (look.dotProduct(to) > -0.1) continue;
				world.setBlockState(p, ModBlocks.HEADLIGHT.getDefaultState());
				if (switched == 0) playAt(player, ModSounds.HEADLIGHT_CLICK, Vec3d.ofCenter(p), 0.9f, 1.0f);
				switched++;
			}
		}
		return switched > 0;
	}

	private static boolean alarmChain(ServerPlayerEntity player, ServerWorld world, State s, long now) {
		BlockPos c = player.getBlockPos();
		Map<Long, BlockPos> cars = new HashMap<>();
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -32; dx <= 32; dx++) {
			for (int dz = -32; dz <= 32; dz++) {
				p.set(c.getX() + dx, c.getY(), c.getZ() + dz);
				if (isCarLight(world.getBlockState(p))) {
					long key = (((long) Math.floorDiv(p.getX(), 4)) << 32) ^ (Math.floorDiv(p.getZ(), 6) & 0xFFFFFFFFL);
					cars.putIfAbsent(key, p.toImmutable());
				}
			}
		}
		java.util.List<BlockPos> list = new java.util.ArrayList<>(cars.values());
		list.removeIf(b -> b.getSquaredDistance(c) < 16);
		if (list.size() < 2) return false;
		list.sort(java.util.Comparator.comparingDouble((BlockPos b) -> b.getSquaredDistance(c)).reversed());
		int n = Math.min(6, list.size());
		int step = Math.max(1, list.size() / n);
		for (int i = 0; i < n; i++) {
			BlockPos car = list.get(Math.min(list.size() - 1, i * step));
			s.queue.addLast(new Scheduled(now + i * 28L, Vec3d.ofCenter(car), ModSounds.CAR_ALARM, 1.6f, 0.95f + i * 0.02f));
		}
		return true;
	}

	private static BlockPos nearestCarLight(ServerPlayerEntity player, ServerWorld world, int radius) {
		BlockPos c = player.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				p.set(c.getX() + dx, c.getY(), c.getZ() + dz);
				if (!isCarLight(world.getBlockState(p))) continue;
				double d = p.getSquaredDistance(c);
				if (d > 9 && d < bestD) {
					bestD = d;
					best = p.toImmutable();
				}
			}
		}
		return best;
	}

	/** Шаги начинаются в 18 блоках за спиной и бегут к тебе. Последний — вплотную. */
	private static void startRun(ServerPlayerEntity player, State s) {
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		flat = flat.lengthSquared() > 1.0E-4 ? flat.normalize() : new Vec3d(0, 0, 1);
		s.runPos = player.getPos().subtract(flat.multiply(18));
		s.runStep = flat.multiply(16.5 / 12.0);
		s.runLeft = 12;
	}

	private static void startSilence(ServerPlayerEntity player, State s, int ticks) {
		s.silenceUntil = player.getServerWorld().getTime() + ticks;
		s.stepsUntil = 0;
		NullspaceTeleporter.cutAllSounds(player);
		if (ServerPlayNetworking.canSend(player, SilencePayload.ID)) {
			ServerPlayNetworking.send(player, new SilencePayload(ticks));
		}
	}

	private static SoundEvent stepsSound(ServerPlayerEntity player) {
		var key = player.getWorld().getRegistryKey();
		if (key == NullspaceDimensions.LEVEL_1) return ModSounds.WET_STEPS;
		if (key == NullspaceDimensions.LEVEL_2) return ModSounds.CONCRETE_STEPS;
		return ModSounds.STEPS_BEHIND_WALL;
	}

	private static SoundEvent distantSound(ServerPlayerEntity player) {
		var key = player.getWorld().getRegistryKey();
		if (key == NullspaceDimensions.LEVEL_1) return ModSounds.WATER_DISTANT;
		if (key == NullspaceDimensions.LEVEL_2) return ModSounds.PARKING_DISTANT;
		return ModSounds.DISTANT;
	}

	private static BlockPos adjacentWall(ServerWorld world, BlockPos pos) {
		for (Direction dir : Direction.Type.HORIZONTAL) {
			BlockPos p = pos.offset(dir);
			if (isWall(world.getBlockState(p))) return p;
		}
		return null;
	}

	public static boolean isWall(BlockState state) {
		return state.isOf(ModBlocks.OFFICE_WALLPAPER) || state.isOf(ModBlocks.POOL_TILE) || state.isOf(ModBlocks.POOL_TILE_BLUE)
				|| state.isOf(ModBlocks.PARKING_CONCRETE);
	}

	private static void playAt(ServerPlayerEntity player, SoundEvent sound, Vec3d at, float volume, float pitch) {
		player.networkHandler.sendPacket(new PlaySoundS2CPacket(
				Registries.SOUND_EVENT.getEntry(sound), SoundCategory.AMBIENT,
				at.x, at.y, at.z, volume, pitch, player.getRandom().nextLong()));
	}

	private AtmosphereDirector() {
	}
}
