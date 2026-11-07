package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
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
		/** Везут в суд (а не сразу в камеру). */
		public boolean needsTrial;
		/** Сейчас идёт суд. */
		public boolean trial;

		public Record(int ticks, String crime, int cell) {
			this.ticks = ticks;
			this.crime = crime;
			this.cell = cell;
		}
	}

	/** Едущая карета. Пока далеко — едет «виртуально», рядом с игроком — по-настоящему. */
	private static final class Ride {
		final UUID player;
		MinecartEntity cart;
		HorseEntity horse;
		int phase; // 0 — едет из тюрьмы (далеко), 1 — подъезжает, 2 — везёт в тюрьму
		int t;
		Vec3d pos;
		Vec3d dir = new Vec3d(1, 0, 0);
		final ServerBossBar bar = new ServerBossBar(U.t("🚔 Королевская карета выехала", Formatting.RED),
				BossBar.Color.RED, BossBar.Style.NOTCHED_10);
		double startDist = 1;
		boolean toCourt;

		Ride(UUID player, Vec3d pos) {
			this.player = player;
			this.pos = pos;
		}

		void removeEntities() {
			if (cart != null) {
				cart.removeAllPassengers();
				cart.discard();
			}
			if (horse != null) horse.discard();
			bar.clearPlayers();
		}
	}

	private static final Map<UUID, Ride> rides = new HashMap<>();
	private static final Map<UUID, Integer> releasedAt = new HashMap<>();
	private static final Map<UUID, Integer> lastEscape = new HashMap<>();

	private Jail() {}

	// ------------------------------------------------------------------ состояние

	/** В тюрьме, в суде, в карете или на войне. */
	public static boolean busy(ServerPlayerEntity p) {
		return jailed(p) || War.exiled(p);
	}

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
		if (busy(p) || p.isCreative() || p.isSpectator()) return;
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
		if (War.exiled(p)) {
			// на войне не арестовывают — там и так плохо
			War.exile(p, crime);
			return;
		}

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

		if (!force) {
			// справки об освобождении из магазина
			net.minecraft.entity.player.PlayerInventory inv = p.getInventory();
			for (int i = 0; i < inv.size(); i++) {
				net.minecraft.item.ItemStack card = inv.getStack(i);
				if (Gui.isSpecial(card, "free_card")) {
					card.decrement(1);
					U.msg(p, U.t("📄 Справка об освобождении сработала. Арест за «" + crime + "» отменён.", Formatting.GREEN));
					U.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 1f);
					return;
				}
				if (Gui.isSpecial(card, "fake_card")) {
					card.decrement(1);
					if (U.R.nextBoolean()) {
						U.msg(p, U.t("📄 Стража не заметила подделку. Повезло.", Formatting.GREEN));
						return;
					}
					ticks *= 2;
					crime = crime + " + подделка документов";
					break;
				}
			}
		}

		r = new Record(ticks, crime, U.R.nextInt(Prison.CELLS));
		r.needsTrial = !force && st.courtBuilt && ticks > 20 * 10 && !Laws.is("no_court");
		st.jail.put(p.getUuid(), r);
		st.arrests++;
		st.markDirty();

		U.say(s, U.t("🚔 " + p.getName().getString() + " арестован за " + crime + ". Срок: " + ticks / 20 + " сек.",
				Formatting.RED));
		U.title(p, U.t("🚔 АРЕСТ", Formatting.DARK_RED, Formatting.BOLD), U.t(crime, Formatting.RED));
		U.sound(p, SoundEvents.EVENT_RAID_HORN, 2f, 1.4f);

		if (p.getServerWorld() != s.getOverworld() || !startRide(p, st)) {
			U.msg(p, U.t("Карета пробила портал и доставила тебя лично.", Formatting.GRAY));
			deliver(p, st, r);
		}
	}

	/** Привезли: в суд или сразу в камеру. */
	private static void deliver(ServerPlayerEntity p, PizdecState st, Record r) {
		if (r.needsTrial && st.courtBuilt) {
			r.needsTrial = false;
			Court.begin(p, r);
		} else {
			toCell(p, st, r);
		}
	}

	public static void toCellAfterTrial(ServerPlayerEntity p, Record r) {
		toCell(p, PizdecState.get(p.getServer()), r);
	}

	/** Убрать запись без телепортов и объявлений (например, при ссылке на войну). */
	public static void releaseQuietly(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		st.jail.remove(p.getUuid());
		st.markDirty();
		cancelRide(p.getUuid());
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

	private static final double FAR_SPEED = 3.5;   // блоков за тик, пока карету не видно
	private static final double NEAR_SPEED = 1.9;  // подъезд к игроку
	private static final double BACK_SPEED = 1.7;  // обратно в тюрьму

	private static boolean startRide(ServerPlayerEntity p, PizdecState st) {
		Ride r = new Ride(p.getUuid(), Prison.gate(st));
		r.toCourt = st.jail.get(p.getUuid()).needsTrial;
		r.startDist = Math.max(1, horiz(p.getPos(), r.pos));
		r.bar.addPlayer(p);
		rides.put(p.getUuid(), r);
		st.jail.get(p.getUuid()).transporting = true;
		U.msg(p, U.t("🚔 Из ворот тюрьмы выехала королевская карета. До тебя " + (int) r.startDist
				+ " блоков. Бежать бесполезно.", Formatting.RED));
		// если тюрьма рядом — карета сразу появляется у ворот, и видно, как она выезжает
		if (r.startDist < 72) spawnCarriage(r, p.getServerWorld());
		return true;
	}

	private static double horiz(Vec3d a, Vec3d b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static Vec3d flatDir(Vec3d from, Vec3d to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		return len < 0.001 ? new Vec3d(1, 0, 0) : new Vec3d(dx / len, 0, dz / len);
	}

	private static void spawnCarriage(Ride r, ServerWorld w) {
		MinecartEntity cart = EntityType.MINECART.create(w);
		HorseEntity horse = EntityType.HORSE.create(w);
		if (cart == null || horse == null) return;
		double y = ground(w, r.pos, r.pos.y);
		r.pos = new Vec3d(r.pos.x, y, r.pos.z);
		cart.refreshPositionAndAngles(r.pos.x, y, r.pos.z, 0f, 0f);
		cart.setNoGravity(true);
		cart.setInvulnerable(true);
		cart.setCustomName(U.t("🚔 Королевская карета", Formatting.GOLD));
		cart.setCustomNameVisible(true);
		cart.addCommandTag(CARRIAGE_TAG);
		horse.refreshPositionAndAngles(r.pos.x, y, r.pos.z, 0f, 0f);
		horse.setAiDisabled(true);
		horse.setNoGravity(true);
		horse.setInvulnerable(true);
		horse.setCustomName(U.t("Конь Гоша", Formatting.GOLD));
		horse.addCommandTag(CARRIAGE_TAG);
		// сначала запоминаем, потом спавним — иначе onEntityLoad примет карету за «зомби» после рестарта
		r.cart = cart;
		r.horse = horse;
		r.phase = 1;
		w.spawnEntity(cart);
		w.spawnEntity(horse);
	}

	private static double ground(ServerWorld w, Vec3d at, double prevY) {
		double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at.x), (int) Math.floor(at.z));
		// плавно, без скачков по деревьям
		return prevY + Math.max(-1.5, Math.min(1.5, y - prevY));
	}

	private static void cancelRide(UUID id) {
		Ride r = rides.remove(id);
		if (r != null) r.removeEntities();
	}

	/** Двигает карету с конём на step блоков в сторону dir. */
	private static void drive(Ride r, ServerWorld w, Vec3d dir, double step) {
		Vec3d next = r.pos.add(dir.multiply(step));
		double y = ground(w, next, r.pos.y);
		r.pos = new Vec3d(next.x, y, next.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
		r.cart.setVelocity(Vec3d.ZERO);
		r.cart.refreshPositionAndAngles(r.pos.x, y, r.pos.z, yaw + 90f, 0f);
		Vec3d h = r.pos.add(dir.multiply(2.3));
		r.horse.refreshPositionAndAngles(h.x, y, h.z, yaw, 0f);
		r.horse.setHeadYaw(yaw);
		r.horse.setBodyYaw(yaw);
		if (r.t % 3 == 0) {
			w.playSound(null, h.x, y, h.z, SoundEvents.ENTITY_HORSE_GALLOP, net.minecraft.sound.SoundCategory.NEUTRAL, 2f, 1.3f);
			w.spawnParticles(ParticleTypes.CLOUD, r.pos.x, y + 0.2, r.pos.z, 4, 0.5, 0.1, 0.5, 0.02);
		}
	}

	/** Сирена: два тона по очереди. */
	private static void siren(ServerPlayerEntity p, int t) {
		if (t % 10 == 0) U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BIT, 0.8f, (t / 10) % 2 == 0 ? 1.6f : 1.2f);
	}

	private static void tickRides(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Iterator<Ride> it = rides.values().iterator();
		while (it.hasNext()) {
			Ride r = it.next();
			r.t++;
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(r.player);
			Record rec = st.jail.get(r.player);
			boolean broken = p == null || !p.isAlive() || rec == null
					|| p.getServerWorld() != s.getOverworld()
					|| (r.cart != null && (r.cart.isRemoved() || r.horse.isRemoved()));
			if (broken) {
				r.removeEntities();
				if (rec != null) {
					rec.transporting = false; // доставим при следующей проверке
					rec.needsTrial = false;
				}
				it.remove();
				continue;
			}
			ServerWorld w = p.getServerWorld();

			if (r.phase == 0) {
				// карета мчится из тюрьмы, её пока не видно
				double dist = horiz(p.getPos(), r.pos);
				if (r.t > 120 && dist > 60) {
					// слишком далеко ехать — срезала дорогу через дворы
					r.pos = p.getPos().add(flatDir(p.getPos(), r.pos).multiply(45));
					U.msg(p, U.t("🚔 Карета срезала дорогу через огороды. Она уже близко.", Formatting.RED));
					dist = 45;
				}
				if (dist < 48) {
					r.pos = new Vec3d(r.pos.x, p.getY(), r.pos.z);
					spawnCarriage(r, w);
					if (r.cart == null) {
						r.removeEntities();
						it.remove();
						deliver(p, st, rec);
						continue;
					}
				} else {
					r.pos = r.pos.add(flatDir(r.pos, p.getPos()).multiply(FAR_SPEED));
				}
				r.bar.setName(U.t("🚔 Королевская карета мчится к тебе: " + (int) dist + " м", Formatting.RED));
				r.bar.setPercent((float) Math.max(0, Math.min(1, dist / r.startDist)));
				siren(p, r.t);
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 1, false, false));
				continue;
			}

			if (r.phase == 1) {
				// подъезжаем — уже видно
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 6, false, false));
				double dist = horiz(p.getPos(), r.pos);
				r.bar.setName(U.t("🚔 Карета рядом: " + (int) dist + " м", Formatting.RED));
				r.bar.setPercent((float) Math.max(0, Math.min(1, dist / r.startDist)));
				siren(p, r.t);
				if (dist < 1.8 || r.t > 400) {
					if (p.hasVehicle()) p.stopRiding();
					r.cart.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), r.cart.getYaw(), 0f);
					r.pos = p.getPos();
					p.startRiding(r.cart, true);
					Vec3d dest = r.toCourt ? Court.gate(st) : Prison.gate(st);
					r.dir = flatDir(r.pos, dest);
					r.startDist = Math.max(1, horiz(r.pos, dest));
					r.phase = 2;
					r.t = 0;
					U.bar(p, U.t("🚔 Кучер: «Садись, поехали. Разговаривать будешь с судьёй.»", Formatting.GOLD));
				} else {
					drive(r, w, flatDir(r.pos, p.getPos()), Math.min(NEAR_SPEED, dist));
				}
				continue;
			}

			// phase 2: везём в тюрьму
			if (p.getVehicle() != r.cart) p.startRiding(r.cart, true);
			Vec3d gate = r.toCourt ? Court.gate(st) : Prison.gate(st);
			double left = horiz(r.pos, gate);
			r.bar.setName(U.t((r.toCourt ? "⚖ Едем в суд: " : "🚔 Едем в тюрьму: ") + (int) left + " м", Formatting.DARK_RED));
			r.bar.setPercent((float) Math.max(0, Math.min(1, left / r.startDist)));
			siren(p, r.t);
			if (left < 2 || r.t > 20 * 15) {
				if (left >= 2) U.msg(p, U.t("🚔 Карета включила мигалку и телепортировалась. Так тоже можно.", Formatting.GRAY));
				r.removeEntities();
				it.remove();
				deliver(p, st, rec);
				continue;
			}
			r.dir = flatDir(r.pos, gate);
			drive(r, w, r.dir, Math.min(BACK_SPEED, left));
		}
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
		for (Record r : PizdecState.get(s).jail.values()) {
			r.transporting = false;
			r.trial = false;
			r.needsTrial = false;
		}
	}

	/** После смерти или входа в игру игрока просто возвращают в камеру, без штрафа за «побег». */
	public static void suppressEscape(ServerPlayerEntity p) {
		lastEscape.put(p.getUuid(), p.getServer().getTicks() + 100);
	}

	public static void onLeave(ServerPlayerEntity p) {
		cancelRide(p.getUuid());
		Record r = PizdecState.get(p.getServer()).jail.get(p.getUuid());
		if (r != null) {
			r.transporting = false;
			r.trial = false;
			r.needsTrial = false;
		}
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
			if (r == null || r.transporting || r.trial || !p.isAlive()) continue;
			r.ticks--;
			if (r.ticks <= 0) {
				toRelease.add(p);
				continue;
			}
			if (now % 10 == 0) keepInside(p, st, r, now);
			if (now % 20 == 0) {
				st.markDirty();
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 2, false, false));
				if (Laws.is("vip_jail")) Money.fine(p, 2, "проживание в тюрьме");
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
