package com.nullspace.client;

import com.nullspace.NullspaceMod;
import com.nullspace.registry.ModSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;

/** Клиентская часть атмосферы: гул ламп и режим тишины. */
public final class ClientAtmosphere {
	private static LampHumSound hum;
	private static int silenceTicks;
	private static int retryCooldown;
	private static RegistryKey<World> humWorld;

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
			if (hum != null) hum.stop();
			hum = null;
			humWorld = null;
			silenceTicks = 0;
			return;
		}

		// Другой уровень — другой фон: на Уровне 1 плещется вода.
		RegistryKey<World> world = client.world.getRegistryKey();
		if (humWorld != world) {
			if (hum != null) hum.stop();
			hum = null;
			humWorld = world;
			retryCooldown = 0;
		}

		// Гул перезапускается, если его оборвали (тишина, /stopsound, смена мира).
		if (retryCooldown > 0) retryCooldown--;
		boolean playing = hum != null && !hum.isDone() && client.getSoundManager().isPlaying(hum);
		if (!playing && !isSilent() && retryCooldown == 0) {
			SoundEvent sound = world.getValue().getPath().equals("level_1") ? ModSounds.POOL_AMBIENCE : ModSounds.LAMP_HUM;
			hum = new LampHumSound(client.player, sound);
			client.getSoundManager().play(hum);
			retryCooldown = 40;
		}
	}

	private ClientAtmosphere() {
	}
}
