package com.nullspace.entity;

import com.nullspace.mechanic.BlackoutManager;
import com.nullspace.mechanic.StabilityManager;
import com.nullspace.registry.ModSounds;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Жилец. Высокая тёмная фигура без лица.
 *
 * Канон: в NULLSPACE никого нет. Жилец — не существо, а то, что пространство
 * показывает тебе, когда связь с реальностью слабеет. Он не может ранить —
 * только отнять у тебя реальность. Ударить его нельзя: рука проходит сквозь.
 *
 * WATCH — стоит в конце коридора и смотрит. Подойдёшь или будешь долго
 *         смотреть в ответ — исчезнет.
 * HUNT  — только во время обесточивания. Идёт к тебе, пока ты на него
 *         не смотришь. Смотришь — замирает. Дошёл — удар, и его нет.
 */
public class TenantEntity extends PathAwareEntity {
	public enum Mode { WATCH, HUNT }

	private Mode mode = Mode.WATCH;
	private UUID targetId;
	private int seenTicks;

	public TenantEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setInvulnerable(true);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 100.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.34)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
	}

	public void setup(Mode mode, PlayerEntity target) {
		this.mode = mode;
		this.targetId = target.getUuid();
		face(target);
	}

	@Override
	protected void initGoals() {
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient()) return;
		ServerWorld world = (ServerWorld) getWorld();

		PlayerEntity target = targetId == null ? null : world.getPlayerByUuid(targetId);
		if (target == null || target.isSpectator() || target.squaredDistanceTo(this) > 64 * 64) {
			discard();
			return;
		}
		face(target);

		double dist = Math.sqrt(squaredDistanceTo(target));
		boolean watched = isWatchedBy(target);

		if (mode == Mode.WATCH) {
			getNavigation().stop();
			seenTicks = watched ? seenTicks + 1 : Math.max(0, seenTicks - 1);
			if (dist < 9 || seenTicks > 50 || age > 20 * 40) vanish(world);
			return;
		}

		// HUNT
		if (!(target instanceof ServerPlayerEntity sp) || !BlackoutManager.isDark(sp) || age > 20 * 60) {
			vanish(world);
			return;
		}
		if (watched) {
			getNavigation().stop();
			setVelocity(0, getVelocity().y, 0);
		} else {
			getNavigation().startMovingTo(target, 1.7);
		}
		if (dist < 1.8) {
			StabilityManager.add(sp, -20f);
			sp.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0, false, false));
			sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0, false, false));
			sp.playSoundToPlayer(ModSounds.TENANT_SCREAM, SoundCategory.HOSTILE, 1.0f, 1.0f);
			vanish(world);
		}
	}

	/** Ударить галлюцинацию нельзя — она просто рассеивается. */
	@Override
	public boolean damage(DamageSource source, float amount) {
		if (!getWorld().isClient() && source.getAttacker() instanceof PlayerEntity) {
			vanish((ServerWorld) getWorld());
		}
		return false;
	}

	/** Смотрит ли игрок в нашу сторону и видит ли нас. */
	private boolean isWatchedBy(PlayerEntity player) {
		Vec3d toMe = getPos().add(0, getHeight() * 0.6, 0).subtract(player.getEyePos());
		double len = toMe.length();
		if (len < 1.0E-3) return true;
		double dot = player.getRotationVec(1.0f).dotProduct(toMe.multiply(1.0 / len));
		return dot > 0.55 && player.canSee(this);
	}

	private void face(PlayerEntity target) {
		double dx = target.getX() - getX(), dz = target.getZ() - getZ();
		float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		setYaw(yaw);
		bodyYaw = yaw;
		headYaw = yaw;
	}

	private void vanish(ServerWorld world) {
		world.playSound(null, getBlockPos(), ModSounds.TENANT_VANISH, SoundCategory.HOSTILE, 0.8f, 1.0f);
		discard();
	}
}
