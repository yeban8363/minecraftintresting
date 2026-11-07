package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * Налоговая служба. Каждые 5 минут выезжает из ворот замка и едет по адресу прописки каждого игрока.
 * Хозяин дома — увидит, как карета налоговой подъезжает к табличке. Нет дома — заберут из сундуков.
 * Нет прописки — тюрьма или война.
 */
public final class Tax {
	public static final int PERIOD = 20 * 60 * 5;
	public static final String COLLECTOR_TAG = "pizdec_collector";
	public static final String TAX_CART_TAG = "pizdec_taxcart";

	/** Множитель налога на следующий визит (законы короля его меняют). */
	public static double multiplier = 1.0;
	public static boolean amnesty = false;

	private static final String[] REASONS = {
			"налог на дыхание", "сбор за то, что ты существуешь", "НДС на блоки",
			"налог на квадратность", "акциз на картошку", "пошлина на прыжки",
			"взнос на новую корону короля", "налог на наличие инвентаря", "сбор за использование солнца",
			"налог на ночной сон", "штраф за подозрительно счастливое лицо", "налог на налоги",
			"сбор на ремонт замка, который никто не ремонтирует", "налог на крипера, который взорвал твой дом",
			"плата за просмотр неба", "налог на гравитацию", "налог на то, что ты не заплатил налог",
			"налог на табличку с адресом", "сбор за то, что налоговая к тебе ехала", "налог на дом (за каждый блок)"
	};

	/** Визит налоговой по адресу. */
	private static final class Visit {
		final UUID player;
		final Vec3d home;
		final String homeName;
		final double mult;
		Vec3d pos;
		int phase; // 0 — едет (не видно), 1 — подъезжает (видно), 2 — уезжает
		int t;
		MinecartEntity cart;
		HorseEntity horse;
		VillagerEntity inspector;
		final ServerBossBar bar = new ServerBossBar(U.t("🧾 Налоговая выехала из замка", Formatting.GOLD),
				BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
		double startDist;

		Visit(UUID player, Vec3d home, String homeName, Vec3d from, double mult) {
			this.player = player;
			this.home = home;
			this.homeName = homeName;
			this.pos = from;
			this.mult = mult;
		}

		void remove() {
			if (inspector != null) inspector.discard();
			if (cart != null) {
				cart.removeAllPassengers();
				cart.discard();
			}
			if (horse != null) horse.discard();
			bar.clearPlayers();
		}
	}

	private static final Map<UUID, Visit> visits = new HashMap<>();
	private static boolean spawning;

	private Tax() {}

	public static void warn(MinecraftServer s) {
		U.say(s, U.t("⚠ Через 30 секунд из замка выезжает НАЛОГОВАЯ. Проверьте прописку (/adres). Прячьте бабло.",
				Formatting.RED));
		U.soundAll(s, SoundEvents.BLOCK_BELL_USE, 1f, 0.5f);
	}

	public static void raid(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.taxRaids++;
		st.markDirty();

		if (amnesty) {
			amnesty = false;
			multiplier = 1.0;
			U.titleAll(s, U.t("НАЛОГОВАЯ", Formatting.GREEN, Formatting.BOLD),
					U.t("…сегодня бухает. Амнистия! Повезло, нищеброды.", Formatting.GREEN));
			return;
		}

		U.titleAll(s, U.t("💸 НАЛОГОВАЯ ВЫЕХАЛА 💸", Formatting.DARK_RED, Formatting.BOLD),
				U.t("Проверка №" + st.taxRaids + ". Едем по адресам!", Formatting.RED));
		U.soundAll(s, SoundEvents.EVENT_RAID_HORN, 4f, 1f);

		Vec3d castleGate = new Vec3d(st.cx + 16.5, st.cy, st.cz - 3.5);
		for (ServerPlayerEntity p : U.players(s)) {
			if (Address.valid(s, p.getUuid())) {
				PizdecState.Address a = st.addresses.get(p.getUuid());
				Visit v = new Visit(p.getUuid(), new Vec3d(a.x + 0.5, a.y, a.z + 0.5), a.name, castleGate, multiplier);
				v.startDist = Math.max(1, horiz(v.pos, v.home));
				v.bar.addPlayer(p);
				Visit old = visits.put(p.getUuid(), v);
				if (old != null) old.remove();
				U.msg(p, U.t("🧾 Налоговая выехала из замка по адресу «" + a.name + "». Ехать " + (int) v.startDist
						+ " блоков.", Formatting.GOLD));
			} else {
				// прописки нет — налог списываем как есть, а дальше суровая кара
				U.msg(p, U.t("🧾 Налоговая не нашла твой адрес в реестре замка! Без прописки жить запрещено.",
						Formatting.DARK_RED, Formatting.BOLD));
				collect(p, null, multiplier, false);
				if (!Jail.busy(p)) {
					if (U.R.nextBoolean()) Jail.arrest(p, "проживание без прописки", 60);
					else War.exile(p, "проживание без прописки");
				}
			}
		}
		multiplier = 1.0;
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

	// ------------------------------------------------------------------ поездка

	private static void spawnCart(Visit v, ServerWorld w) {
		MinecartEntity cart = EntityType.MINECART.create(w);
		HorseEntity horse = EntityType.HORSE.create(w);
		VillagerEntity insp = EntityType.VILLAGER.create(w);
		if (cart == null || horse == null || insp == null) return;
		double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(v.pos.x), (int) Math.floor(v.pos.z));
		v.pos = new Vec3d(v.pos.x, y, v.pos.z);
		cart.refreshPositionAndAngles(v.pos.x, y, v.pos.z, 0f, 0f);
		cart.setNoGravity(true);
		cart.setInvulnerable(true);
		cart.setCustomName(U.t("🧾 Карета налоговой", Formatting.GOLD));
		cart.setCustomNameVisible(true);
		cart.addCommandTag(TAX_CART_TAG);
		horse.refreshPositionAndAngles(v.pos.x, y, v.pos.z, 0f, 0f);
		horse.setAiDisabled(true);
		horse.setNoGravity(true);
		horse.setInvulnerable(true);
		horse.addCommandTag(TAX_CART_TAG);
		insp.refreshPositionAndAngles(v.pos.x, y, v.pos.z, 0f, 0f);
		insp.setVillagerData(insp.getVillagerData().withProfession(VillagerProfession.LIBRARIAN));
		insp.setAiDisabled(true);
		insp.setInvulnerable(true);
		insp.setCustomName(U.t(U.pick("Инспектор Зинаида Петровна", "Инспектор Валентина", "Старший инспектор Гаврилов"),
				Formatting.RED));
		insp.setCustomNameVisible(true);
		insp.addCommandTag(TAX_CART_TAG);
		v.cart = cart;
		v.horse = horse;
		v.inspector = insp;
		spawning = true;
		try {
			w.spawnEntity(cart);
			w.spawnEntity(horse);
			w.spawnEntity(insp);
		} finally {
			spawning = false;
		}
		insp.startRiding(cart, true);
		v.phase = 1;
	}

	private static void drive(Visit v, ServerWorld w, Vec3d dir, double step) {
		Vec3d next = v.pos.add(dir.multiply(step));
		double gy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(next.x), (int) Math.floor(next.z));
		double y = v.pos.y + Math.max(-1.5, Math.min(1.5, gy - v.pos.y));
		v.pos = new Vec3d(next.x, y, next.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
		v.cart.setVelocity(Vec3d.ZERO);
		v.cart.refreshPositionAndAngles(v.pos.x, y, v.pos.z, yaw + 90f, 0f);
		Vec3d h = v.pos.add(dir.multiply(2.3));
		v.horse.refreshPositionAndAngles(h.x, y, h.z, yaw, 0f);
		v.horse.setHeadYaw(yaw);
		v.horse.setBodyYaw(yaw);
		if (v.t % 4 == 0) w.playSound(null, h.x, y, h.z, SoundEvents.ENTITY_HORSE_GALLOP, SoundCategory.NEUTRAL, 1.5f, 1f);
	}

	public static void tick(MinecraftServer s) {
		if (visits.isEmpty()) return;
		ServerWorld w = s.getOverworld();
		Iterator<Visit> it = visits.values().iterator();
		while (it.hasNext()) {
			Visit v = it.next();
			v.t++;
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(v.player);
			if (p == null || (v.cart != null && v.cart.isRemoved())) {
				v.remove();
				it.remove();
				continue;
			}
			boolean ownerHome = p.getServerWorld() == w && horiz(p.getPos(), v.home) < 64;
			double dist = horiz(v.pos, v.home);

			if (v.phase == 0) {
				if (v.t > 300 && dist > 60) v.pos = v.home.add(flatDir(v.home, v.pos).multiply(50)); // срезала
				if (dist < 50 && ownerHome) {
					spawnCart(v, w);
					if (v.cart == null) {
						arrive(p, v);
						v.remove();
						it.remove();
						continue;
					}
				} else if (dist < 3) {
					arrive(p, v);
					v.remove();
					it.remove();
					continue;
				} else {
					v.pos = v.pos.add(flatDir(v.pos, v.home).multiply(Math.min(3.0, dist)));
				}
				v.bar.setName(U.t("🧾 Налоговая едет к «" + v.homeName + "»: " + (int) dist + " м", Formatting.GOLD));
				v.bar.setPercent((float) Math.max(0, Math.min(1, dist / v.startDist)));
				continue;
			}
			if (v.phase == 1) {
				v.bar.setName(U.t("🧾 Налоговая подъезжает к дому: " + (int) dist + " м", Formatting.RED));
				v.bar.setPercent((float) Math.max(0, Math.min(1, dist / v.startDist)));
				if (dist < 2.5 || v.t > 1200) {
					arrive(p, v);
					v.phase = 2;
					v.t = 0;
					v.bar.clearPlayers();
				} else {
					drive(v, w, flatDir(v.pos, v.home), Math.min(1.4, dist));
				}
				continue;
			}
			// phase 2: стоим 3 секунды и уезжаем
			if (v.t < 60) {
				if (v.t % 15 == 0) {
					w.playSound(null, v.home.x, v.home.y, v.home.z, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.NEUTRAL,
							0.8f, 1.4f);
				}
				continue;
			}
			drive(v, w, flatDir(v.home, new Vec3d(PizdecState.get(s).cx + 16.5, v.pos.y, PizdecState.get(s).cz)), 1.4);
			if (v.t > 100) {
				w.spawnParticles(ParticleTypes.POOF, v.pos.x, v.pos.y + 1, v.pos.z, 20, 1, 1, 1, 0.05);
				v.remove();
				it.remove();
			}
		}
	}

	/** Налоговая доехала до адреса. */
	private static void arrive(ServerPlayerEntity p, Visit v) {
		boolean home = p.getServerWorld() == p.getServer().getOverworld() && horiz(p.getPos(), v.home) < 64;
		U.title(p, U.t("🧾 ТУК-ТУК", Formatting.GOLD, Formatting.BOLD),
				U.t(home ? "Налоговая у твоего дома «" + v.homeName + "»" : "Налоговая приходила, а тебя нет дома", Formatting.YELLOW));
		U.sound(p, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 1.4f);
		if (!home) U.msg(p, U.t("🧾 Тебя не было дома. Составлена опись имущества по адресу «" + v.homeName + "».", Formatting.GOLD));
		collect(p, BlockPos.ofFloored(v.home), v.mult, true);
	}

	// ------------------------------------------------------------------ сбор

	/**
	 * Собирает налог. home — где искать сундуки для описи (может быть null).
	 * canArrest — арестовать, если взять вообще нечего.
	 */
	private static void collect(ServerPlayerEntity p, BlockPos home, double mult, boolean canArrest) {
		String why = U.pick(REASONS);
		int bal = Money.get(p);
		int tax = (int) Math.max(10, Math.round(Math.max(bal, 0) * 0.2 * mult) + 10 * mult);
		tax = (int) Math.max(5, tax * (1 - Ranks.taxDiscount(p)));
		U.msg(p, U.t("🧾 Налоговая: «" + why + "» — " + tax + " бабла" + (Ranks.taxDiscount(p) > 0
				? " (скидка за звание «" + Ranks.name(p) + "»)" : "") + ".", Formatting.GOLD));

		if (bal >= tax) {
			Money.add(p, -tax);
			Jobs.onTaxCollected(p.getServer(), p, tax);
			U.msg(p, U.t("   Списано " + tax + ". Остаток: " + Money.get(p) + ". Спасибо за сотрудничество.", Formatting.GRAY));
			return;
		}

		int missing = tax - Math.max(bal, 0);
		if (bal > 0) {
			Money.set(p, 0);
			Jobs.onTaxCollected(p.getServer(), p, bal);
		}
		int stacks = Math.min(3, 1 + missing / 25);
		List<String> taken = new ArrayList<>();
		if (home != null) taken.addAll(confiscateChests(p.getServer().getOverworld(), home, stacks));
		if (taken.isEmpty()) taken.addAll(confiscate(p, stacks));
		if (!taken.isEmpty()) {
			U.msg(p, U.t("   Бабла не хватило. Изъято: " + String.join(", ", taken), Formatting.RED));
			U.sound(p, SoundEvents.BLOCK_CHAIN_BREAK, 1f, 0.5f);
			if (Money.get(p) < 0) sendCollector(p);
			return;
		}
		Money.add(p, -missing);
		U.msg(p, U.t("   Брать нечего. Долг: " + missing + ".", Formatting.DARK_RED, Formatting.BOLD));
		if (canArrest && !Jail.busy(p)) Jail.arrest(p, "уклонение от налогов", 60);
	}

	/** Опись: забираем из сундуков и бочек рядом с адресом. */
	private static List<String> confiscateChests(ServerWorld w, BlockPos home, int count) {
		List<Inventory> invs = new ArrayList<>();
		for (int dx = -6; dx <= 6; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -6; dz <= 6; dz++) {
					BlockEntity be = w.getBlockEntity(new BlockPos(home.getX() + dx, home.getY() + dy, home.getZ() + dz));
					if (be instanceof Inventory inv) invs.add(inv);
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (int n = 0; n < count && !invs.isEmpty(); n++) {
			Inventory inv = U.pick(invs);
			List<Integer> slots = new ArrayList<>();
			for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) slots.add(i);
			if (slots.isEmpty()) {
				invs.remove(inv);
				n--;
				continue;
			}
			int slot = U.pick(slots);
			ItemStack st = inv.getStack(slot);
			out.add(st.getCount() + "× " + st.getName().getString() + " (из сундука)");
			inv.setStack(slot, ItemStack.EMPTY);
			inv.markDirty();
		}
		return out;
	}

	/** Забирает случайные стаки из инвентаря. Возвращает список того, что забрали. */
	public static List<String> confiscate(ServerPlayerEntity p, int count) {
		PlayerInventory inv = p.getInventory();
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < inv.size(); i++) {
			if (!inv.getStack(i).isEmpty()) slots.add(i);
		}
		List<String> out = new ArrayList<>();
		for (int n = 0; n < count && !slots.isEmpty(); n++) {
			int slot = slots.remove(U.R.nextInt(slots.size()));
			ItemStack st = inv.getStack(slot);
			out.add(st.getCount() + "× " + st.getName().getString());
			inv.setStack(slot, ItemStack.EMPTY);
		}
		return out;
	}

	public static void sendCollector(ServerPlayerEntity p) {
		VindicatorEntity v = U.spawnNear(EntityType.VINDICATOR, p, 6);
		if (v == null) return;
		v.setCustomName(U.t(U.pick("Коллектор Гена", "Коллектор Толик", "Коллектор Жора", "Пристав Михалыч"),
				Formatting.DARK_RED));
		v.setCustomNameVisible(true);
		v.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		v.addCommandTag(COLLECTOR_TAG);
		v.setTarget(p);
		U.sound(p, SoundEvents.ENTITY_VINDICATOR_CELEBRATE, 1.5f, 0.8f);
	}

	public static int secondsLeft(int ticksSinceRaid) {
		return Math.max(0, (PERIOD - ticksSinceRaid) / 20);
	}

	/** Кареты налоговой после перезапуска не нужны. */
	public static void onEntityLoad(net.minecraft.entity.Entity e) {
		if (spawning || !e.getCommandTags().contains(TAX_CART_TAG)) return;
		for (Visit v : visits.values()) if (v.cart == e || v.horse == e || v.inspector == e) return;
		e.discard();
	}

	public static void onLeave(ServerPlayerEntity p) {
		Visit v = visits.remove(p.getUuid());
		if (v != null) v.remove();
	}
}
