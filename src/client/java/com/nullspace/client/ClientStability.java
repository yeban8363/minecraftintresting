package com.nullspace.client;

import com.nullspace.registry.ModSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.util.math.random.Random;

/**
 * Клиентская сторона Стабильности. Шкалы нет — только ощущения:
 * края экрана темнеют, при совсем низкой Стабильности бьётся сердце
 * и по экрану пробегают помехи.
 */
public final class ClientStability {
	private static float value = 100f;
	private static float shown = 100f;
	private static int heartbeatTimer;
	private static final Random RANDOM = Random.create();

	public static float get() {
		return value;
	}

	public static void set(float v) {
		value = v;
	}

	public static void tick(MinecraftClient client) {
		if (client.player == null || !ClientAtmosphere.inNullspace(client)) {
			shown += (100f - shown) * 0.05f;
			return;
		}
		shown += (value - shown) * 0.05f;

		if (value < 22f) {
			if (--heartbeatTimer <= 0) {
				heartbeatTimer = 16 + (int) value;
				float volume = 0.35f + 0.65f * (22f - value) / 22f;
				client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.HEARTBEAT, 1.0f, volume));
			}
		} else {
			heartbeatTimer = 0;
		}
	}

	public static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden) return;

		float k = Math.max(0f, Math.min(1f, (75f - shown) / 75f));
		if (k <= 0.01f) return;

		int w = ctx.getScaledWindowWidth();
		int h = ctx.getScaledWindowHeight();
		int steps = 14;
		int band = Math.max(1, (int) (Math.min(w, h) * 0.38f / steps));

		for (int i = 0; i < steps; i++) {
			float t = 1f - (float) i / steps;
			int alpha = (int) (255 * k * 0.22f * t * t);
			if (alpha <= 0) continue;
			int color = alpha << 24;
			int o = i * band;
			ctx.fill(o, o, w - o, o + band, color);                       // верх
			ctx.fill(o, h - o - band, w - o, h - o, color);               // низ
			ctx.fill(o, o + band, o + band, h - o - band, color);         // лево
			ctx.fill(w - o - band, o + band, w - o, h - o - band, color); // право
		}

		// Помехи при очень низкой Стабильности.
		if (shown < 25f && RANDOM.nextFloat() < 0.06f + (25f - shown) * 0.006f) {
			int lines = 1 + RANDOM.nextInt(3);
			for (int i = 0; i < lines; i++) {
				int y = RANDOM.nextInt(h);
				int height = 1 + RANDOM.nextInt(2);
				int color = RANDOM.nextBoolean() ? 0x30FFFFFF : 0x28FF2020;
				ctx.fill(0, y, w, y + height, color);
			}
		}
	}

	private ClientStability() {
	}
}
