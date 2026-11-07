package com.nullspace.mechanic;

import com.nullspace.world.NullspaceDimensions;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Правила Уровня 1: вода непрозрачная — с головой под водой почти ничего не видно.
 * Здесь никого нет. Тебе только кажется, что под поверхностью кто-то плывёт.
 */
public final class Level1Manager {

	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 10 != 0) return;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player.getWorld().getRegistryKey() != NullspaceDimensions.LEVEL_1 || player.isSpectator()) continue;
			if (player.isSubmergedInWater()) {
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, true, false, false));
			}
		}
	}

	private Level1Manager() {
	}
}
