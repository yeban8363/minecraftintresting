package com.nullspace.client;

import com.nullspace.NullspaceMod;
import net.minecraft.client.MinecraftClient;

/** Клиентская часть атмосферы: гул ламп и режим тишины. */
public final class ClientAtmosphere {
	private static LampHumSound hum;
	private static int silenceTicks;
	private static int retryCooldown;

	public static boolean inNullspace(MinecraftClient client) {
		return client.world != null
				&& client.world.getRegistryKey().getValue().getNamespace().equals(NullspaceMod.MOD_ID);
	}

	public static boolean isSilent() {
		return silenceTicks > 0;
	}

	public static void silence(int ticks) {
		silenceTicks = Math.max(silenceTicks, ticks);
	}

	public static void tick(MinecraftClient client) {
		if (silenceTicks > 0) silenceTicks--;

		if (client.player == null || !inNullspace(client)) {
			hum = null;
			silenceTicks = 0;
			return;
		}

		// Гул перезапускается, если его оборвали (тишина, /stopsound, смена мира).
		if (retryCooldown > 0) retryCooldown--;
		boolean playing = hum != null && !hum.isDone() && client.getSoundManager().isPlaying(hum);
		if (!playing && !isSilent() && retryCooldown == 0) {
			hum = new LampHumSound(client.player);
			client.getSoundManager().play(hum);
			retryCooldown = 40;
		}
	}

	private ClientAtmosphere() {
	}
}
