package com.nullspace.mechanic;

import com.nullspace.entity.SwimmerEntity;
import com.nullspace.registry.ModEntities;
import com.nullspace.world.AquaticLayout;
import com.nullspace.world.NullspaceDimensions;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Правила Уровня 1:
 *  - вода непрозрачная: с головой под водой почти ничего не видно;
 *  - считаем, сколько игрок пробыл в воде (Пловец терпелив, но не бесконечно);
 *  - когда Стабильность пошатнулась (< 85), под водой появляется Пловец —
 *    то, что тебе мерещится.
 */
public final class Level1Manager {
	private static final Map<UUID, Integer> WATER_TICKS = new HashMap<>();

	public static int waterTicks(PlayerEntity player) {
		return WATER_TICKS.getOrDefault(player.getUuid(), 0);
	}

	public static void tick(MinecraftServer server) {
		int t = server.getTicks();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player.getWorld().getRegistryKey() != NullspaceDimensions.LEVEL_1 || player.isSpectator()) {
				WATER_TICKS.remove(player.getUuid());
				continue;
			}
			if (player.isTouchingWater()) WATER_TICKS.merge(player.getUuid(), 1, Integer::sum);
			else WATER_TICKS.remove(player.getUuid());

			if (t % 10 == 0 && player.isSubmergedInWater()) {
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, true, false, false));
			}
			if (t % 40 == 0 && !player.isCreative()) {
				ensureSwimmer(player);
			}
		}
	}

	public static void forget(ServerPlayerEntity player) {
		WATER_TICKS.remove(player.getUuid());
	}

	private static void ensureSwimmer(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		boolean exists = !world.getEntitiesByClass(SwimmerEntity.class,
				player.getBoundingBox().expand(64), e -> true).isEmpty();
		if (exists || StabilityManager.get(player) >= 85f || player.getRandom().nextFloat() > 0.35f) return;
		spawnSwimmer(player);
	}

	/** Найти глубокую воду в 10–26 блоках от игрока и выпустить туда Пловца. */
	public static boolean spawnSwimmer(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		Random random = player.getRandom();
		for (int attempt = 0; attempt < 24; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double dist = 10 + random.nextDouble() * 16;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
			BlockPos pos = new BlockPos(x, AquaticLayout.FLOOR_Y - 2, z);
			if (world.getFluidState(pos).isIn(FluidTags.WATER) && world.getFluidState(pos.up()).isIn(FluidTags.WATER)) {
				SwimmerEntity swimmer = ModEntities.SWIMMER.create(world);
				if (swimmer == null) return false;
				swimmer.refreshPositionAndAngles(x + 0.5, pos.getY() + 0.1, z + 0.5, random.nextFloat() * 360f, 0f);
				world.spawnEntity(swimmer);
				return true;
			}
		}
		return false;
	}

	private Level1Manager() {
	}
}
