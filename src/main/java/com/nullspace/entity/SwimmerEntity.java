package com.nullspace.entity;

import com.nullspace.mechanic.Level1Manager;
import com.nullspace.mechanic.StabilityManager;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.AquaticLayout;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Пловец. Тёмный силуэт под мутной водой Уровня 1.
 *
 * Канон: в NULLSPACE никого нет. Пловец — галлюцинация, он появляется,
 * только когда Стабильность уже пошатнулась. Ранить не может, но тянет вниз
 * и отнимает реальность.
 *
 * Пока ты на суше — плывёт рядом, параллельно тебе, под самой поверхностью.
 * Посмотришь прямо — уходит на глубину и пропадает.
 * Пробудешь в воде дольше пяти секунд — плывёт к тебе и тянет на дно.
 */
public class SwimmerEntity extends WaterCreatureEntity {
	private static final int PATIENCE = 100; // тиков в воде, после которых он начинает охоту

	private int outOfWater;
	private int diving = -1;
	private int attackCooldown;
	private float side = 1f;

	public SwimmerEntity(EntityType<? extends WaterCreatureEntity> type, World world) {
		super(type, world);
		setInvulnerable(true);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void initGoals() {
		// Поведение задаётся вручную в tick().
	}

	@Override
	public boolean isPushedByFluids() {
		return false;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient()) return;
		ServerWorld world = (ServerWorld) getWorld();
		setPose(EntityPose.SWIMMING);

		if (!isTouchingWater()) {
			if (++outOfWater > 40) discard();
			return;
		}
		outOfWater = 0;
		setNoGravity(true);
		if (attackCooldown > 0) attackCooldown--;

		if (diving >= 0) {
			setVelocity(0, -0.12, 0);
			if (++diving > 30) discard();
			return;
		}

		PlayerEntity target = world.getClosestPlayer(this, 48);
		if (target == null || target.isSpectator() || target.isCreative()) {
			setVelocity(getVelocity().multiply(0.8));
			if (age > 2400) discard();
			return;
		}

		boolean hunting = Level1Manager.waterTicks(target) > PATIENCE;
		Vec3d pos = getPos();
		Vec3d desired;

		if (hunting) {
			Vec3d to = target.getPos().add(0, 0.4, 0).subtract(pos);
			desired = to.lengthSquared() > 1.0E-4 ? to.normalize().multiply(0.24) : Vec3d.ZERO;
			if (to.length() < 1.6 && attackCooldown == 0) grab(world, target);
		} else {
			// Посмотрел прямо — уходит.
			Vec3d look = target.getRotationVec(1.0f);
			Vec3d toMe = getPos().subtract(target.getEyePos());
			if (toMe.length() < 16 && look.dotProduct(toMe.normalize()) > 0.985) {
				diving = 0;
				world.playSound(null, getBlockPos(), ModSounds.SWIMMER_NEAR, SoundCategory.HOSTILE, 0.8f, 0.7f);
				return;
			}
			// Плыть параллельно игроку, под самой поверхностью.
			Vec3d flat = new Vec3d(look.x, 0, look.z);
			flat = flat.lengthSquared() > 1.0E-4 ? flat.normalize() : new Vec3d(1, 0, 0);
			Vec3d perp = new Vec3d(-flat.z, 0, flat.x).multiply(7 * side);
			Vec3d aim = new Vec3d(target.getX() + perp.x + flat.x * 3, AquaticLayout.FLOOR_Y - 1.6, target.getZ() + perp.z + flat.z * 3);
			if (!isWater(world, aim)) {
				side = -side;
				aim = new Vec3d(pos.x, AquaticLayout.FLOOR_Y - 1.6, pos.z);
			}
			Vec3d to = aim.subtract(pos);
			desired = to.lengthSquared() > 0.25 ? to.normalize().multiply(0.11) : Vec3d.ZERO;
			if (age % 80 == 0 && random.nextInt(3) == 0) {
				world.playSound(null, getBlockPos(), ModSounds.SWIMMER_NEAR, SoundCategory.HOSTILE, 0.5f, 1.0f);
			}
		}

		// Не выпрыгивать из воды.
		Vec3d next = pos.add(desired);
		if (!isWater(world, next.add(0, 0.5, 0))) desired = new Vec3d(desired.x, Math.min(desired.y, -0.02), desired.z);

		setVelocity(getVelocity().lerp(desired, 0.25));
		velocityModified = true;

		Vec3d v = getVelocity();
		if (v.horizontalLengthSquared() > 1.0E-4) {
			float yaw = (float) (MathHelper.atan2(v.z, v.x) * (180.0 / Math.PI)) - 90f;
			setYaw(yaw);
			bodyYaw = yaw;
			headYaw = yaw;
		}
	}

	private void grab(ServerWorld world, PlayerEntity target) {
		attackCooldown = 30;
		target.setVelocity(target.getVelocity().add(0, -0.6, 0));
		target.velocityModified = true;
		world.playSound(null, target.getBlockPos(), ModSounds.SWIMMER_GRAB, SoundCategory.HOSTILE, 1.0f, 1.0f);
		if (target instanceof ServerPlayerEntity sp) StabilityManager.add(sp, -8f);
	}

	/** Ударить галлюцинацию нельзя — она уходит на глубину. */
	@Override
	public boolean damage(DamageSource source, float amount) {
		if (!getWorld().isClient() && source.getAttacker() instanceof PlayerEntity && diving < 0) {
			diving = 0;
		}
		return false;
	}

	private static boolean isWater(ServerWorld world, Vec3d p) {
		return world.getFluidState(BlockPos.ofFloored(p)).isIn(FluidTags.WATER);
	}
}
