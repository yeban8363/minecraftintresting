package com.nullspace.client;

import com.mojang.authlib.GameProfile;
import com.nullspace.registry.ModBlocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Окно на Уровне 0. Посмотришь в него — по ту сторону стоит человек, спиной к тебе.
 * Он повторяет каждое твоё движение. Это ты.
 */
public final class WindowMirror {
	private static final int MIRROR_ID = -1_338_000;
	private static Doppelganger mirror;
	private static int notLooking;

	public static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null || !ClientAtmosphere.inNullspace(client)) {
			remove(client);
			return;
		}

		HitResult hit = client.crosshairTarget;
		BlockHitResult window = null;
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
				&& world.getBlockState(bhr.getBlockPos()).isOf(ModBlocks.ODD_WINDOW)
				&& bhr.getSide().getAxis().isHorizontal()
				&& Vec3d.ofCenter(bhr.getBlockPos()).distanceTo(player.getEyePos()) < 10) {
			window = bhr;
		}
		if (window == null) {
			if (++notLooking > 8) remove(client);
			return;
		}
		notLooking = 0;

		// Отражаем игрока сквозь плоскость стены: сдвиг вдоль нормали, без зеркального разворота.
		Direction side = window.getSide();
		Vec3d n = new Vec3d(side.getOffsetX(), 0, side.getOffsetZ());
		Vec3d wall = Vec3d.ofCenter(window.getBlockPos());
		Vec3d p = player.getPos();
		double d = p.subtract(wall).dotProduct(n);
		Vec3d target = p.subtract(n.multiply(2 * d));

		BlockPos feet = BlockPos.ofFloored(target);
		if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()) {
			remove(client);
			return;
		}

		if (mirror == null) {
			mirror = new Doppelganger(world, new GameProfile(UUID.randomUUID(), player.getGameProfile().getName()));
			mirror.setId(MIRROR_ID);
			mirror.refreshPositionAndAngles(target.x, target.y, target.z, player.getYaw(), player.getPitch());
			world.addEntity(mirror);
		} else {
			mirror.updateTrackedPositionAndAngles(target.x, target.y, target.z, player.getYaw(), player.getPitch(), 2);
		}
		mirror.setHeadYaw(player.getHeadYaw());
		mirror.setBodyYaw(player.getBodyYaw());
		mirror.setSneaking(player.isSneaking());
	}

	private static void remove(MinecraftClient client) {
		if (mirror != null && client.world != null) {
			client.world.removeEntity(mirror.getId(), Entity.RemovalReason.DISCARDED);
		}
		mirror = null;
	}

	private WindowMirror() {
	}
}
