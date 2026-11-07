package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * FPV-дрон. По команде /fpv (или по воле короля) прилетает издалека и делает одно из трёх:
 * сбрасывает еду, сбрасывает бабло или влетает в тебя и взрывается.
 */
public final class Fpv {
	public static final String TAG = "pizdec_fpv";
	public static final int COOLDOWN = 20 * 90;

	private static final class Drone {
		final UUID target;
		final VexEntity vex;
		final int outcome; // 0 еда, 1 бабло, 2 камикадзе
		int t;

		Drone(UUID target, VexEntity vex, int outcome) {
			this.target = target;
			this.vex = vex;
			this.outcome = outcome;
		}
	}

	private static final List<Drone> drones = new ArrayList<>();
	private static final Map<UUID, Integer> lastCall = new HashMap<>();
	private static boolean spawning;

	private Fpv() {}

	/** Чипсы «Хрустик» со вкусом лобстера (своя текстура через custom_model_data). */
	public static ItemStack chips(int count) {
		ItemStack st = Gui.special(Items.COOKIE, "chips", "§6Чипсы «Хрустик» со вкусом лобстера",
				"Лобстер ненастоящий. Хруст настоящий.", "Иногда ускоряют. Иногда мутит.");
		st.setCount(count);
		st.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(7701));
		st.set(DataComponentTypes.FOOD, new FoodComponent.Builder().nutrition(5).saturationModifier(0.5f).alwaysEdible().snack()
				.statusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 15, 0), 0.5f)
				.statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 5, 0), 0.1f).build());
		return st;
	}

	/** Вызов дрона игроком. */
	public static void call(ServerPlayerEntity p) {
		int now = p.getServer().getTicks();
		Integer last = lastCall.get(p.getUuid());
		if (last != null && now - last < COOLDOWN && !p.isCreative()) {
			U.msg(p, U.t("🛸 Дрон ещё заряжается: " + (COOLDOWN - (now - last)) / 20 + " сек.", Formatting.GRAY));
			return;
		}
		if (Jail.jailed(p)) {
			U.msg(p, U.t("🛸 Над тюрьмой бесполётная зона.", Formatting.GRAY));
			return;
		}
		lastCall.put(p.getUuid(), now);
		int r = U.R.nextInt(100);
		launch(p, r < 40 ? 0 : r < 75 ? 1 : 2);
	}

	/** Запуск дрона к игроку с заданным исходом (или случайным, если -1). */
	public static void launch(ServerPlayerEntity p, int outcome) {
		if (outcome < 0) {
			int r = U.R.nextInt(100);
			outcome = r < 40 ? 0 : r < 75 ? 1 : 2;
		}
		ServerWorld w = p.getServerWorld();
		VexEntity v = EntityType.VEX.create(w);
		if (v == null) return;
		double a = U.R.nextDouble() * Math.PI * 2;
		v.refreshPositionAndAngles(p.getX() + Math.cos(a) * 45, p.getY() + 18, p.getZ() + Math.sin(a) * 45, 0f, 0f);
		v.setAiDisabled(true);
		v.setNoGravity(true);
		v.setInvulnerable(true);
		v.setSilent(true);
		v.setCustomName(U.t("🛸 FPV-дрон «" + U.pick("Мавик", "Шахед-мини", "Комар", "Птичка", "Оса") + "»", Formatting.RED));
		v.setCustomNameVisible(true);
		v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		v.addCommandTag(TAG);
		Drone d = new Drone(p.getUuid(), v, outcome);
		drones.add(d);
		spawning = true;
		try {
			w.spawnEntity(v);
		} finally {
			spawning = false;
		}
		U.msg(p, U.t("🛸 В небе жужжит FPV-дрон. Летит к тебе. Что везёт — неизвестно.", Formatting.YELLOW));
	}

	public static void tick(MinecraftServer s) {
		if (drones.isEmpty()) return;
		Iterator<Drone> it = drones.iterator();
		while (it.hasNext()) {
			Drone d = it.next();
			d.t++;
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(d.target);
			if (p == null || !p.isAlive() || d.vex.isRemoved() || p.getServerWorld() != d.vex.getWorld() || d.t > 20 * 30) {
				d.vex.discard();
				it.remove();
				continue;
			}
			ServerWorld w = p.getServerWorld();
			Vec3d target = p.getPos().add(0, d.outcome == 2 ? 1.0 : 2.5, 0);
			Vec3d cur = d.vex.getPos();
			Vec3d to = target.subtract(cur);
			double dist = to.length();
			if (d.t % 6 == 0) {
				w.playSound(null, cur.x, cur.y, cur.z, SoundEvents.ENTITY_BEE_LOOP_AGGRESSIVE, SoundCategory.HOSTILE, 2f, 1.8f);
			}
			w.spawnParticles(ParticleTypes.SMOKE, cur.x, cur.y + 0.3, cur.z, 1, 0.05, 0.05, 0.05, 0.0);
			if (dist < 1.6) {
				arrive(p, d, w);
				d.vex.discard();
				it.remove();
				continue;
			}
			double speed = d.outcome == 2 ? 1.5 : 1.1;
			Vec3d step = to.normalize().multiply(Math.min(speed, dist));
			Vec3d next = cur.add(step);
			float yaw = (float) Math.toDegrees(Math.atan2(-step.x, step.z));
			d.vex.refreshPositionAndAngles(next.x, next.y, next.z, yaw, 0f);
			d.vex.setHeadYaw(yaw);
		}
	}

	private static void arrive(ServerPlayerEntity p, Drone d, ServerWorld w) {
		Vec3d pos = d.vex.getPos();
		switch (d.outcome) {
			case 0 -> {
				ItemStack food = U.chance(0.45) ? chips(U.between(1, 3))
						: new ItemStack(U.pick(Items.BREAD, Items.COOKED_BEEF, Items.GOLDEN_CARROT, Items.BAKED_POTATO, Items.PUMPKIN_PIE),
								U.between(2, 5));
				w.spawnEntity(new ItemEntity(w, pos.x, pos.y, pos.z, food));
				U.msg(p, U.t("🛸 Дрон сбросил посылку: " + food.getName().getString() + " ×" + food.getCount() + ". Приятного аппетита!",
						Formatting.GREEN));
				U.sound(p, SoundEvents.ENTITY_ITEM_PICKUP, 1f, 0.8f);
			}
			case 1 -> {
				int sum = U.between(10, 60);
				Money.give(p, sum, "FPV-доставка");
				w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.x, pos.y, pos.z, 20, 0.5, 0.5, 0.5, 0.1);
				U.msg(p, U.t("🛸 Дрон сбросил конверт с баблом: +" + sum + ".", Formatting.GREEN));
			}
			default -> {
				U.title(p, U.t("💥 FPV!", Formatting.DARK_RED, Formatting.BOLD), U.t("дрон влетел прямо в тебя", Formatting.RED));
				w.createExplosion(d.vex, p.getX(), p.getY() + 0.5, p.getZ(), 2.4f, World.ExplosionSourceType.NONE);
				U.say(p.getServer(), U.t("💥 В " + p.getName().getString() + " влетел FPV-дрон.", Formatting.RED));
			}
		}
	}

	/** Дронов после перезапуска быть не должно. */
	public static void onEntityLoad(Entity e) {
		if (spawning || !e.getCommandTags().contains(TAG)) return;
		for (Drone d : drones) if (d.vex == e) return;
		e.discard();
	}
}
