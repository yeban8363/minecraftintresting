package com.nullspace.mechanic;

import com.nullspace.entity.TenantEntity;
import com.nullspace.registry.ModEntities;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;

/** Где поставить Жильца. */
public final class TenantSpawner {

	/** В конце длинного коридора, куда смотрит игрок (или в любом длинном направлении). */
	public static boolean spawnWatcher(ServerPlayerEntity player) {
		Random random = player.getRandom();
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		for (int attempt = 0; attempt < 8; attempt++) {
			Vec3d dir;
			if (attempt == 0 && flat.lengthSquared() > 1.0E-4) {
				dir = flat.normalize();
			} else {
				double a = random.nextDouble() * Math.PI * 2;
				dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
			}
			BlockPos at = corridorEnd(player, dir, 14);
			if (at != null && spawn(player, at, TenantEntity.Mode.WATCH)) return true;
		}
		return false;
	}

	/** Где-то за спиной, в 16–26 блоках. */
	public static boolean spawnHunter(ServerPlayerEntity player) {
		Random random = player.getRandom();
		ServerWorld world = player.getServerWorld();
		Vec3d look = player.getRotationVec(1.0f);
		double base = Math.atan2(-look.z, -look.x);
		for (int attempt = 0; attempt < 24; attempt++) {
			double a = base + (random.nextDouble() - 0.5) * Math.PI;
			double d = 16 + random.nextDouble() * 10;
			BlockPos at = BlockPos.ofFloored(player.getX() + Math.cos(a) * d, player.getY(), player.getZ() + Math.sin(a) * d);
			if (fits(world, at) && spawn(player, at, TenantEntity.Mode.HUNT)) return true;
		}
		return false;
	}

	private static BlockPos corridorEnd(ServerPlayerEntity player, Vec3d dir, double minDist) {
		ServerWorld world = player.getServerWorld();
		Vec3d start = player.getPos().add(0, 1.0, 0);
		Vec3d end = start.add(dir.multiply(40));
		BlockHitResult hit = world.raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
		double dist = hit.getType() == HitResult.Type.MISS ? 40 : hit.getPos().distanceTo(start);
		if (dist < minDist) return null;
		Vec3d p = start.add(dir.multiply(dist - 1.2));
		BlockPos at = BlockPos.ofFloored(p.x, player.getY(), p.z);
		return fits(world, at) ? at : null;
	}

	private static boolean fits(ServerWorld world, BlockPos feet) {
		return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
				&& world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
				&& world.getBlockState(feet.up(2)).getCollisionShape(world, feet.up(2)).isEmpty()
				&& !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()
				&& world.getFluidState(feet).isEmpty();
	}

	private static boolean spawn(ServerPlayerEntity player, BlockPos at, TenantEntity.Mode mode) {
		ServerWorld world = player.getServerWorld();
		TenantEntity tenant = ModEntities.TENANT.create(world);
		if (tenant == null) return false;
		tenant.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
		tenant.setup(mode, player);
		return world.spawnEntity(tenant);
	}

	private TenantSpawner() {
	}
}
