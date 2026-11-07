package com.nullspace.mechanic;

import com.nullspace.entity.TenantEntity;
import com.nullspace.item.FlashlightItem;
import com.nullspace.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Свет фонарика без клиентских модов: в точку, куда смотрит луч, ставится
 * невидимый светящийся блок и переезжает вслед за взглядом.
 */
public final class FlashlightLightManager {
	private static final double RANGE = 14.0;

	private record Light(ServerWorld world, BlockPos pos) {
	}

	private static final Map<UUID, Light> LIGHTS = new HashMap<>();

	public static void tick(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			Light current = LIGHTS.get(player.getUuid());
			ServerWorld world = player.getServerWorld();
			BlockPos target = activeFlashlight(player) ? findTarget(player) : null;
			// Рядом Жилец — фонарик барахлит.
			if (target != null && server.getTicks() % 3 == 0 && player.getRandom().nextFloat() < 0.6f
					&& !world.getEntitiesByClass(TenantEntity.class, player.getBoundingBox().expand(20), e -> true).isEmpty()) {
				target = null;
			}

			if (target == null) {
				if (current != null) {
					remove(current);
					LIGHTS.remove(player.getUuid());
				}
				continue;
			}

			boolean same = current != null && current.world() == world && current.pos().equals(target);
			if (same && world.getBlockState(target).isOf(ModBlocks.GLOW)) continue;

			if (current != null) remove(current);
			world.setBlockState(target, ModBlocks.GLOW.getDefaultState(), Block.NOTIFY_ALL);
			LIGHTS.put(player.getUuid(), new Light(world, target));
		}
	}

	public static void clear(ServerPlayerEntity player) {
		Light light = LIGHTS.remove(player.getUuid());
		if (light != null) remove(light);
	}

	private static boolean activeFlashlight(ServerPlayerEntity player) {
		if (player.isSpectator()) return false;
		return isLit(player.getMainHandStack()) || isLit(player.getOffHandStack());
	}

	private static boolean isLit(ItemStack stack) {
		return stack.getItem() instanceof FlashlightItem && FlashlightItem.isOn(stack) && !FlashlightItem.isEmpty(stack);
	}

	private static BlockPos findTarget(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		Vec3d eye = player.getEyePos();
		Vec3d dir = player.getRotationVec(1.0f);
		Vec3d end = eye.add(dir.multiply(RANGE));

		BlockHitResult hit = world.raycast(new RaycastContext(eye, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		Vec3d point = hit.getType() == HitResult.Type.MISS ? end : hit.getPos().subtract(dir.multiply(0.6));

		// Отступаем к игроку, пока не найдём свободную клетку.
		for (int i = 0; i < 6; i++) {
			BlockPos pos = BlockPos.ofFloored(point);
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || state.isOf(ModBlocks.GLOW)) return pos;
			point = point.subtract(dir);
		}
		return null;
	}

	private static void remove(Light light) {
		if (light.world().getBlockState(light.pos()).isOf(ModBlocks.GLOW)) {
			light.world().removeBlock(light.pos(), false);
		}
	}

	private FlashlightLightManager() {
	}
}
