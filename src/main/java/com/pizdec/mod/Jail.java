package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/** Королевская тюрьма: аресты, карета-автозак, сроки, залог. */
public final class Jail {
	public static final String CARRIAGE_TAG = "pizdec_carriage";

	/** Запись о заключённом. ticks — сколько осталось сидеть. */
	public static final class Record {
		public int ticks;
		public String crime;
		public int cell;
		public boolean transporting;

		public Record(int ticks, String crime, int cell) {
			this.ticks = ticks;
			this.crime = crime;
			this.cell = cell;
		}
	}

	/** Едущая карета. */
	private static final class Ride {
		final UUID player;
		final MinecartEntity cart;
		final HorseEntity horse;
		int phase; // 0 — едет за преступником, 1 — везёт в тюрьму
		int t;
		Vec3d dir = new Vec3d(1, 0, 0);

		Ride(UUID player, MinecartEntity cart, HorseEntity horse) {
			this.player = player;
			this.cart = cart;
			this.horse = horse;
		}
	}

	private static final Map<UUID, Ride> rides = new HashMap<>();
	private static final Map<UUID, Integer> releasedAt = new HashMap<>();
	private static final Map<UUID, Integer> lastEscape = new HashMap<>();

	private Jail() {}

	// ------------------------------------------------------------------ состояние

	public static boolean jailed(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).jail.containsKey(p.getUuid());
	}

	public static int secondsLeft(ServerPlayerEntity p) {
		Record r = PizdecState.get(p.getServer()).jail.get(p.getUuid());
		return r == null ? 0 : (r.ticks + 19) / 20;
	}

	public static int inmates(MinecraftServer s) {
		return PizdecState.get(s).jail.size();
	}

	// ------------------------------------------------------------------ арест

	/** Нарушение действующего указа. Первые 5 секунд указа — время на ознакомление. */
	public static void violation(ServerPlayerEntity p, String what) {
		if (jailed(p) || p.isCreative() || p.isSpectator()) return;
		if (!Laws.graceOver()) {
			U.bar(p, U.t("⚠ Нарушение указа! У тебя ещё пара секунд на ознакомление, потом — тюрьма", Formatting.YELLOW));
			return;
		}
		arrest(p, "нарушение указа «" + Laws.currentTitle() + "»: " + what, 30, false);
	}

	public static void arrest(ServerPlayerEntity p, String crime, int seconds) {
		arrest(p, crime, seconds, false);
	}

	/** Главная функция. force — арестовать даже креатив и даже сразу после выхода. */
	public static void arrest(ServerPlayerEntity p, String crime, int seconds, boolean force) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (!st.prisonBuilt || p.isSpectator()) return;
		if (!force && p.isCreative()) return;
		int ticks = seconds * 20 * Laws.sentenceMult();

		Record r = st.jail.get(p.getUuid());
		if (r != null) {
			r.ticks += ticks;
			st.markDirty();
			U.msg(p, U.t("⛓ Срок увеличен на " + ticks / 20 + " сек: " + crime, Formatting.RED));
			return;
		}
		Integer rel = releasedAt.get(p.getUuid());
		if (!force && rel != null && s.getTicks() - rel < 20 * 10) {
			Money.fine(p, 5, "условный срок: " + crime);
			return;
		}

		r = new Record(ticks, crime, U.R.nextInt(Prison.CELLS));
		st.jail.put(p.getUuid(), r);
		st.arrests++;
		st.markDirty();

		U.say(s, U.t("🚔 " + p.getName().getString() + " арестован за " + crime + ". Срок: " + ticks / 20 + " сек.",
				Formatting.RED));
		U.title(p, U.t("🚔 АРЕСТ", Formatting.DARK_RED, Formatting.BOLD), U.t(crime, Formatting.RED));
		U.sound(p, SoundEvents.EVENT_RAID_HORN, 2f, 1.4f);

		if (p.getServerWorld() != s.getOverworld() || !startRide(p, st)) {
			toCell(p, st, r);
			U.msg(p, U.t("Карета пробила портал и доставила тебя лично.", Formatting.GRAY));
		}
	}

	public static void release(ServerPlayerEntity p, String why) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (st.jail.remove(p.getUuid()) == null) return;
		st.markDirty();
		cancelRide(p.getUuid());
		releasedAt.put(p.getUuid(), s.getTicks());
		Vec3d e = Prison.exit(st);
		p.teleport(s.getOverworld(), e.x, e.y, e.z, 270f, 0f);
		p.removeStatusEffect(StatusEffects.WEAKNESS);
		p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
		U.title(p, U.t("🕊 НА СВОБОДЕ", Formatting.GREEN, Formatting.BOLD), U.t(why, Formatting.GRAY));
		U.say(s, U.t("🕊 " + p.getName().getString() + " вышел на свободу (" + why + ").", Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
	}

	/** Залог: 5 бабла за каждую оставшуюся секунду. */
	public static void bail(ServerPlayerEntity p) {
		if (!jailed(p)) {
			U.msg(p, U.t("Ты пока на свободе. Пока.", Formatting.GRAY));
			return;
		}
		int cost = Math.max(20, secondsLeft(p) * 5);
		if (Money.pay(p, cost, "залог")) release(p, "вышел под залог " + cost + " бабла");
		else U.msg(p, U.t("Залог стоит " + cost + " бабла. Сиди.", Formatting.RED));
	}

	public static void amnesty(MinecraftServer s, String why) {
		for (ServerPlayerEntity p : new ArrayList<>(s.getPlayerManager().getPlayerList())) {
			if (jailed(p)) release(p, why);
		}
		PizdecState st = PizdecState.get(s);
		st.jail.clear();
		st.markDirty();
	}

	private static void toCell(ServerPlayerEntity p, PizdecState st, Record r) {
		r.transporting = false;
		if (p.hasVehicle()) p.stopRiding();
		Vec3d c = Prison.cell(st, r.cell);
		p.teleport(p.getServer().getOverworld(), c.x, c.y, c.z, r.cell < 5 ? 0f : 180f, 0f);
		U.title(p, U.t("⛓ ТЮРЬМА", Formatting.DARK_GRAY, Formatting.BOLD),
				U.t("Срок: " + (r.ticks + 19) / 20 + " сек. Залог: /zalog", Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 1f, 0.7f);
	}

	// ------------------------------------------------------------------ карета

	private static boolean startRide(ServerPlayerEntity p, PizdecState st) {
		ServerWorld w = p.getServerWorld();
		MinecartEntity cart = EntityType.MINECART.create(w);
		HorseEntity horse = EntityType.HORSE.create(w);
		if (cart == null || horse == null) return false;

		double yaw = Math.toRadians(p.getYaw());
		Vec3d back = new Vec3d(Math.sin(yaw), 0, -Math.cos(yaw)).multiply(18); // за спиной
		Vec3d start = p.getPos().add(back);
		double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(start.x), (int) Math.floor(start.z));
		if (Math.abs(y - p.getY()) > 10) y = p.getY();

		cart.refreshPositionAndAngles(start.x, y, start.z, 0f, 0f);
		cart.setNoGravity(true);
		cart.setInvulnerable(true);
		cart.setCustomName(U.t("🚔 Королевская карета", Formatting.GOLD));
		cart.setCustomNameVisible(true);
		cart.addCommandTag(CARRIAGE_TAG);

		horse.refreshPositionAndAngles(start.x, y, start.z, 0f, 0f);
		horse.setAiDisabled(true);
		horse.setNoGravity(true);
		horse.setInvulnerable(true);
		horse.setCustomName(U.t("Конь Гоша", Formatting.GOLD));
		horse.addCommandTag(CARRIAGE_TAG);

		w.spawnEntity(cart);
		w.spawnEntity(horse);
		rides.put(p.getUuid(), new Ride(p.getUuid(), cart, horse));
		st.jail.get(p.getUuid()).transporting = true;
		U.msg(p, U.t("🚔 За тобой выехала королевская карета. Бежать бесполезно.", Formatting.RED));
		return true;
	}

	private static void cancelRide(UUID id) {
		Ride r = rides.remove(id);
		if (r == null) return;
		r.cart.removeAllPassengers();
		r.cart.discard();
		r.horse.discard();
	}

	/** Ставит карету и коня в точку pos, лицом по направлению dir. */
	private static void place(Ride r, ServerWorld w, Vec3d pos, Vec3d dir) {
		double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(pos.x), (int) Math.floor(pos.z));
		if (Math.abs(y - pos.y) > 6) y = pos.y;
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
		r.cart.setVelocity(Vec3d.ZERO);
		r.cart.refreshPositionAndAngles(pos.x, y, pos.z, yaw + 90f, 0f);
		Vec3d h = pos.add(dir.multiply(2.3));
		r.horse.refreshPositionAndAngles(h.x, y, h.z, yaw, 0f);
		r.horse.setHeadYaw(yaw);
		r.horse.setBodyYaw(yaw);
		if (r.t % 4 == 0) {
			w.playSound(null, h.x, y, h.z, SoundEvents.ENTITY_HORSE_GALLOP, net.minecraft.sound.SoundCategory.NEUTRAL, 1.5f, 1.2f);
			w.spawnParticles(ParticleTypes.CLOUD, pos.x, y + 0.2, pos.z, 3, 0.4, 0.1, 0.4, 0.02);
		}
	}

	private static void tickRides(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Iterator<Ride> it = rides.values().iterator();
		while (it.hasNext()) {
			Ride r = it.next();
			r.t++;
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(r.player);
			Record rec = st.jail.get(r.player);
			boolean broken = p == null || !p.isAlive() || rec == null || r.cart.isRemoved() || r.horse.isRemoved()
					|| p.getServerWorld() != r.cart.getWorld();
			if (broken) {
				r.cart.removeAllPassengers();
				r.cart.discard();
				r.horse.discard();
				if (rec != null) rec.transporting = false; // доставим при следующей проверке
				it.remove();
				continue;
			}
			ServerWorld w = p.getServerWorld();
			Vec3d cur = r.cart.getPos();

			if (r.phase == 0) {
				// едем за преступником — очень быстро
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 5, false, false));
				Vec3d to = p.getPos().subtract(cur);
				double dist = Math.sqrt(to.x * to.x + to.z * to.z);
				if (dist < 1.8 || r.t > 60) {
					if (p.hasVehicle()) p.stopRiding();
					p.startRiding(r.cart, true);
					Vec3d gate = Prison.gate(st);
					Vec3d d = gate.subtract(p.getPos());
					double len = Math.sqrt(d.x * d.x + d.z * d.z);
					r.dir = len < 0.01 ? new Vec3d(1, 0, 0) : new Vec3d(d.x / len, 0, d.z / len);
					r.phase = 1;
					r.t = 0;
					U.bar(p, U.t("🚔 Карета: «Садись, поехали. Разговаривать будешь с судьёй.»", Formatting.GOLD));
				} else {
					Vec3d dir = new Vec3d(to.x / dist, 0, to.z / dist);
					place(r, w, cur.add(dir.multiply(Math.min(1.4, dist))), dir);
				}
			} else {
				// везём в тюрьму
				if (p.getVehicle() != r.cart) p.startRiding(r.cart, true);
				Vec3d gate = Prison.gate(st);
				double left = Math.sqrt(Math.pow(gate.x - cur.x, 2) + Math.pow(gate.z - cur.z, 2));
				if (left < 2 || r.t > 70) {
					cancelRideEntities(r);
					it.remove();
					toCell(p, st, rec);
					continue;
				}
				place(r, w, cur.add(r.dir.multiply(Math.min(1.3, left))), r.dir);
			}
		}
	}

	private static void cancelRideEntities(Ride r) {
		r.cart.removeAllPassengers();
		r.cart.discard();
		r.horse.discard();
	}

	/** Карет после перезапуска быть не должно. */
	public static void onEntityLoad(Entity e) {
		if (!e.getCommandTags().contains(CARRIAGE_TAG)) return;
		for (Ride r : rides.values()) {
			if (r.cart == e || r.horse == e) return;
		}
		e.discard();
	}

	public static void onServerStart(MinecraftServer s) {
		rides.clear();
		releasedAt.clear();
		lastEscape.clear();
		for (Record r : PizdecState.get(s).jail.values()) r.transporting = false;
	}

	/** После смерти или входа в игру игрока просто возвращают в камеру, без штрафа за «побег». */
	public static void suppressEscape(ServerPlayerEntity p) {
		lastEscape.put(p.getUuid(), p.getServer().getTicks() + 100);
	}

	public static void onLeave(ServerPlayerEntity p) {
		cancelRide(p.getUuid());
		Record r = PizdecState.get(p.getServer()).jail.get(p.getUuid());
		if (r != null) r.transporting = false;
	}

	// ------------------------------------------------------------------ отсидка

	public static void tick(MinecraftServer s) {
		tickRides(s);
		PizdecState st = PizdecState.get(s);
		if (st.jail.isEmpty()) return;
		int now = s.getTicks();
		List<ServerPlayerEntity> toRelease = new ArrayList<>();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			Record r = st.jail.get(p.getUuid());
			if (r == null || r.transporting || !p.isAlive()) continue;
			r.ticks--;
			if (r.ticks <= 0) {
				toRelease.add(p);
				continue;
			}
			if (now % 10 == 0) keepInside(p, st, r, now);
			if (now % 20 == 0) {
				st.markDirty();
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 2, false, false));
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 40, 4, false, false));
				U.bar(p, U.t("⛓ Сидеть ещё " + (r.ticks + 19) / 20 + " сек — " + r.crime + "  |  /zalog", Formatting.GRAY));
			}
		}
		for (ServerPlayerEntity p : toRelease) release(p, "срок отсижен");
	}

	private static void keepInside(ServerPlayerEntity p, PizdecState st, Record r, int now) {
		Vec3d c = Prison.cell(st, r.cell);
		boolean sameWorld = p.getServerWorld() == p.getServer().getOverworld();
		double dx = Math.abs(p.getX() - c.x), dz = Math.abs(p.getZ() - c.z), dy = p.getY() - c.y;
		boolean in = sameWorld && dx < 2.2 && dz < 2.3 && dy > -1.5 && dy < 4;
		if (in) return;
		boolean escape = !sameWorld || dx > 8 || dz > 8 || Math.abs(dy) > 8;
		if (p.hasVehicle()) p.stopRiding();
		p.teleport(p.getServer().getOverworld(), c.x, c.y, c.z, p.getYaw(), p.getPitch());
		Integer last = lastEscape.get(p.getUuid());
		if (escape && (last == null || now - last > 100)) {
			lastEscape.put(p.getUuid(), now);
			r.ticks += 30 * 20;
			U.msg(p, U.t("⛓ Попытка побега! +30 сек. Палыч всё видел.", Formatting.RED));
		}
	}
}
