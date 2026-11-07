package com.nullspace.client;

import com.mojang.authlib.GameProfile;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Галлюцинации — существуют только на твоём клиенте, другие игроки их не видят.
 *
 *  Стабильность < 50 — Двойник: фигура с твоим лицом стоит на краю зрения
 *                      и смотрит на тебя. Исчезает, если посмотреть прямо или подойти.
 *  Стабильность < 30 — Ложные стены: поперёк коридора вырастает стена,
 *                      которой нет. Растворяется, когда подходишь вплотную.
 */
public final class Hallucinations {
	private static final Random RANDOM = Random.create();
	private static final int DOPPEL_ID = -1_337_000;

	private static OtherClientPlayerEntity doppel;
	private static int doppelAge;

	private record FakeWall(BlockPos base, int height, int expiresAt) {
	}

	private static final List<FakeWall> WALLS = new ArrayList<>();
	private static int ticks;

	/** Двойник с твоим скином. */
	private static final class Doppelganger extends OtherClientPlayerEntity {
		Doppelganger(ClientWorld world, GameProfile profile) {
			super(world, profile);
		}

		@Override
		public SkinTextures getSkinTextures() {
			ClientPlayerEntity me = MinecraftClient.getInstance().player;
			return me != null ? me.getSkinTextures() : super.getSkinTextures();
		}
	}

	public static void tick(MinecraftClient client) {
		ticks++;
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || !ClientAtmosphere.inNullspace(client)) {
			clear(client);
			return;
		}
		float s = ClientStability.get();

		tickDoppel(client, player, s);
		tickWalls(client, player, s);
	}

	// ---------------- двойник ----------------

	private static void tickDoppel(MinecraftClient client, ClientPlayerEntity player, float s) {
		if (doppel != null) {
			doppelAge++;
			Vec3d eye = player.getEyePos();
			Vec3d to = doppel.getEyePos().subtract(eye);
			double dist = to.length();
			double dot = player.getRotationVec(1.0f).dotProduct(to.normalize());

			if (dot > 0.97 || dist < 5.0 || doppelAge > 200 || doppel.getWorld() != client.world) {
				if (dot > 0.97 && doppel.getWorld() == client.world) {
					client.world.playSound(doppel.getX(), doppel.getEyeY(), doppel.getZ(), ModSounds.WHISPER,
							SoundCategory.AMBIENT, 0.6f, 0.8f, false);
				}
				removeDoppel(client);
			} else {
				float yaw = yawTowards(doppel.getPos(), player.getPos());
				doppel.setYaw(yaw);
				doppel.setHeadYaw(yaw);
				doppel.setBodyYaw(yaw);
			}
			return;
		}
		if (s >= 50f) return;
		float chance = 0.0008f + 0.0045f * (50f - s) / 50f;
		if (RANDOM.nextFloat() < chance) spawnDoppel(client, player);
	}

	private static void spawnDoppel(MinecraftClient client, ClientPlayerEntity player) {
		ClientWorld world = client.world;
		if (world == null) return;
		for (int attempt = 0; attempt < 16; attempt++) {
			float side = RANDOM.nextBoolean() ? 1f : -1f;
			float yaw = player.getYaw() + side * (50f + RANDOM.nextFloat() * 45f);
			double dist = 9 + RANDOM.nextDouble() * 9;
			double rad = Math.toRadians(yaw);
			double x = player.getX() - Math.sin(rad) * dist;
			double z = player.getZ() + Math.cos(rad) * dist;
			BlockPos feet = BlockPos.ofFloored(x, player.getY(), z);
			if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()
					|| world.getBlockState(feet.down()).isAir()) continue;

			GameProfile profile = new GameProfile(UUID.randomUUID(), player.getGameProfile().getName());
			Doppelganger d = new Doppelganger(world, profile);
			d.setId(DOPPEL_ID);
			Vec3d at = new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			float face = yawTowards(at, player.getPos());
			d.refreshPositionAndAngles(at.x, at.y, at.z, face, 0f);
			d.setHeadYaw(face);
			d.setBodyYaw(face);
			world.addEntity(d);
			doppel = d;
			doppelAge = 0;
			return;
		}
	}

	private static void removeDoppel(MinecraftClient client) {
		if (doppel != null && client.world != null) {
			client.world.removeEntity(doppel.getId(), Entity.RemovalReason.DISCARDED);
		}
		doppel = null;
	}

	private static float yawTowards(Vec3d from, Vec3d to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
	}

	// ---------------- ложные стены ----------------

	private static void tickWalls(MinecraftClient client, ClientPlayerEntity player, float s) {
		ClientWorld world = client.world;
		if (world == null) return;

		WALLS.removeIf(w -> {
			boolean close = w.base().toCenterPos().squaredDistanceTo(player.getPos()) < 2.6 * 2.6;
			if (close || ticks >= w.expiresAt()) {
				restore(world, w);
				return true;
			}
			return false;
		});

		if (s >= 30f || !WALLS.isEmpty()) return;
		float chance = 0.0006f + 0.003f * (30f - s) / 30f;
		if (RANDOM.nextFloat() >= chance) return;

		Vec3d look = player.getRotationVec(1.0f);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		if (flat.lengthSquared() < 1.0E-4) return;
		flat = flat.normalize();
		boolean pools = world.getRegistryKey().getValue().getPath().equals("level_1");
		for (int d = 6; d <= 10; d++) {
			BlockPos base = BlockPos.ofFloored(player.getX() + flat.x * d, player.getY(), player.getZ() + flat.z * d);
			if (!world.getBlockState(base).isAir() || world.getBlockState(base.down()).isAir()) continue;
			int height = 0;
			while (height < 6 && world.getBlockState(base.up(height)).isAir()) height++;
			if (height < 3) continue;
			for (int dy = 0; dy < height; dy++) {
				BlockState state;
				if (pools) state = (dy == 1 ? ModBlocks.POOL_TILE_BLUE : ModBlocks.POOL_TILE).getDefaultState();
				else state = (dy == 0 ? ModBlocks.OFFICE_WALLPAPER_BASE : ModBlocks.OFFICE_WALLPAPER).getDefaultState();
				world.setBlockState(base.up(dy), state, Block.NOTIFY_ALL);
			}
			WALLS.add(new FakeWall(base, height, ticks + 200 + RANDOM.nextInt(200)));
			return;
		}
	}

	private static void restore(ClientWorld world, FakeWall wall) {
		for (int dy = 0; dy < wall.height(); dy++) {
			BlockPos p = wall.base().up(dy);
			BlockState state = world.getBlockState(p);
			if (state.isOf(ModBlocks.OFFICE_WALLPAPER) || state.isOf(ModBlocks.OFFICE_WALLPAPER_BASE)
					|| state.isOf(ModBlocks.POOL_TILE) || state.isOf(ModBlocks.POOL_TILE_BLUE)) {
				world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			}
		}
	}

	public static void clear(MinecraftClient client) {
		removeDoppel(client);
		if (client.world != null) {
			for (FakeWall w : WALLS) restore(client.world, w);
		}
		WALLS.clear();
	}

	private Hallucinations() {
	}
}
