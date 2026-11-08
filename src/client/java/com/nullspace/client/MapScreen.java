package com.nullspace.client;

import com.nullspace.NullspaceMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;

/**
 * Экран карты уровней. 128×128 пикселей вокруг игрока, колёсико — масштаб.
 * При Стабильности ниже 40 карта врёт: строки съезжают, появляются коридоры,
 * которых нет, и выходы, которых нет; ты сам на карте стоишь не там.
 */
public class MapScreen extends Screen {
	private static final Identifier TEXTURE = NullspaceMod.id("dynamic/level_map");
	private static final int SIZE = 128;
	private static NativeImageBackedTexture texture;

	private int zoom = 1;
	private int ticks;
	private int markerDx, markerDy;

	public MapScreen() {
		super(Text.translatable("screen.nullspace.level_map"));
	}

	@Override
	protected void init() {
		rebuild();
	}

	@Override
	public void tick() {
		if (++ticks % 40 == 0) rebuild();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		zoom = vertical > 0 ? Math.max(1, zoom - 1) : Math.min(4, zoom + 1);
		rebuild();
		return true;
	}

	private static int abgr(int r, int g, int b) {
		return 0xFF000000 | (b << 16) | (g << 8) | r;
	}

	private int[] palette(String dim) {
		if (dim.endsWith("level_1")) return new int[]{210, 225, 230};
		if (dim.endsWith("level_2")) return new int[]{140, 140, 134};
		if (dim.endsWith("level_3")) return new int[]{232, 220, 196};
		if (dim.endsWith("level_0")) return new int[]{204, 182, 92};
		return new int[]{160, 160, 160};
	}

	private void rebuild() {
		if (client == null || client.player == null) return;
		if (texture == null) {
			texture = new NativeImageBackedTexture(new NativeImage(SIZE, SIZE, false));
			client.getTextureManager().registerTexture(TEXTURE, texture);
		}
		NativeImage img = texture.getImage();
		if (img == null) return;

		String dim = ClientMapMemory.dimension(client);
		int[] wall = palette(dim);
		int wallColor = abgr(wall[0], wall[1], wall[2]);
		int openColor = abgr(wall[0] / 6, wall[1] / 6, wall[2] / 6);
		int unknownColor = abgr(10, 10, 12);
		int exitColor = abgr(70, 255, 110);
		int doorColor = abgr(70, 150, 255);

		float lie = MathHelper.clamp((40f - ClientStability.get()) / 40f, 0f, 1f);
		Random r = Random.create(System.currentTimeMillis() / 4000L);
		int[] shift = new int[SIZE];
		if (lie > 0) for (int i = 0; i < SIZE; i++) if (r.nextFloat() < lie * 0.15f) shift[i] = r.nextInt(9) - 4;

		int px = client.player.getBlockX(), pz = client.player.getBlockZ();
		for (int iy = 0; iy < SIZE; iy++) {
			for (int ix = 0; ix < SIZE; ix++) {
				int wx = px + (ix - SIZE / 2 + shift[iy]) * zoom;
				int wz = pz + (iy - SIZE / 2) * zoom;
				int color = switch (ClientMapMemory.get(dim, wx, wz)) {
					case ClientMapMemory.OPEN -> openColor;
					case ClientMapMemory.WALL -> wallColor;
					case ClientMapMemory.EXIT -> exitColor;
					case ClientMapMemory.DOOR -> doorColor;
					default -> unknownColor;
				};
				img.setColor(ix, iy, color);
			}
		}

		if (lie > 0) {
			// Коридоры, которых нет.
			int lines = 1 + (int) (lie * 6);
			for (int i = 0; i < lines; i++) {
				int x = r.nextInt(SIZE), y = r.nextInt(SIZE), len = 10 + r.nextInt(40);
				boolean horizontal = r.nextBoolean();
				for (int k = 0; k < len; k++) {
					int ix = horizontal ? x + k : x, iy = horizontal ? y : y + k;
					if (ix >= SIZE || iy >= SIZE) break;
					img.setColor(ix, iy, openColor);
				}
			}
			// Выходы, которых нет.
			if (lie > 0.5f) {
				for (int i = 0; i < 2; i++) {
					int x = 2 + r.nextInt(SIZE - 4), y = 2 + r.nextInt(SIZE - 4);
					img.setColor(x, y, exitColor);
					img.setColor(x + 1, y, exitColor);
				}
			}
			markerDx = (int) ((r.nextInt(9) - 4) * lie);
			markerDy = (int) ((r.nextInt(9) - 4) * lie);
		} else {
			markerDx = markerDy = 0;
		}
		texture.upload();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		if (client == null || client.player == null || texture == null) return;

		int size = Math.min(256, Math.min(width, height) - 60);
		int x = (width - size) / 2, y = (height - size) / 2;
		ctx.fill(x - 3, y - 3, x + size + 3, y + size + 3, 0xFF2A2A2A);
		ctx.drawTexture(TEXTURE, x, y, size, size, 0, 0, SIZE, SIZE, SIZE, SIZE);

		// Ты (не всегда там, где на самом деле).
		float scale = (float) size / SIZE;
		int cx = x + size / 2 + (int) (markerDx * scale), cy = y + size / 2 + (int) (markerDy * scale);
		ctx.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);
		double yaw = Math.toRadians(client.player.getYaw());
		for (int i = 3; i <= 7; i++) {
			int dx = (int) Math.round(-Math.sin(yaw) * i), dy = (int) Math.round(Math.cos(yaw) * i);
			ctx.fill(cx + dx - 1, cy + dy - 1, cx + dx + 1, cy + dy + 1, 0xFFFF5050);
		}

		ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.nullspace.level_map.title",
				Text.translatable("level.nullspace." + ClientMapMemory.dimension(client).replace("nullspace:", ""))), width / 2, y - 16, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.nullspace.level_map.hint", zoom),
				width / 2, y + size + 8, 0xA0A0A0);
	}
}
