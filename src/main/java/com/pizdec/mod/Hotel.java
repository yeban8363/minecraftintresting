package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * Королевский отель «Пять звёзд (из десяти)» в западном крыле замка.
 * 13 номеров четырёх категорий. Номер — это прописка, бесплатная кровать и (для дорогих) плюшки.
 */
public final class Hotel {
	public static final long STAY = 20L * 60 * 20; // 20 минут

	public enum Kind {
		ECONOMY("Эконом-капсула", 30, Items.GRAY_BED, "Кровать и лампа. Зато прописка."),
		STANDARD("Стандарт", 80, Items.LIGHT_BLUE_BED, "Кровать, сундук, цветок. Почти уютно."),
		LUX("Люкс", 250, Items.PURPLE_BED, "Две кровати, книги, регенерация и сытость в номере."),
		PRESIDENT("Королевский люкс", 1000, Items.RED_BED,
				"Маяк, зачарования, эндер-сундук, регенерация и дипломатическая неприкосновенность в номере.");

		final String title;
		final int price;
		final Item icon;
		final String desc;

		Kind(String title, int price, Item icon, String desc) {
			this.title = title;
			this.price = price;
			this.icon = icon;
			this.desc = desc;
		}
	}

	/** Номер: категория, внутренние границы (относительно замка), этаж. */
	public record Room(int no, Kind kind, int x0, int z0, int x1, int z1, int floor) {
		int y() {
			return floor == 0 ? 0 : 6;
		}

		boolean contains(PizdecState st, Vec3d p) {
			int y = st.cy + y();
			return p.x >= st.cx + x0 && p.x < st.cx + x1 + 1 && p.z >= st.cz + z0 && p.z < st.cz + z1 + 1 && p.y >= y - 0.5
					&& p.y < y + 4.5;
		}

		Vec3d center(PizdecState st) {
			return switch (kind) {
				case ECONOMY -> new Vec3d(st.cx + 4.5, st.cy, st.cz + z0 + 1.5);
				case STANDARD -> new Vec3d(st.cx + 12.5, st.cy, st.cz + z0 + 2.5);
				case LUX -> new Vec3d(st.cx + x0 + 1.5, st.cy + 6, st.cz + 18.5);
				case PRESIDENT -> new Vec3d(st.cx + 8.5, st.cy + 6, st.cz + 32.5);
			};
		}
	}

	public static final List<Room> ROOMS = new ArrayList<>();

	static {
		int no = 101;
		for (int i = 0; i < 6; i++) ROOMS.add(new Room(no++, Kind.ECONOMY, 3, 15 + 4 * i, 5, 17 + 4 * i, 0));
		for (int i = 0; i < 4; i++) ROOMS.add(new Room(no++, Kind.STANDARD, 11, 15 + 6 * i, 13, 19 + 6 * i, 0));
		ROOMS.add(new Room(201, Kind.LUX, 3, 15, 5, 24, 1));
		ROOMS.add(new Room(202, Kind.LUX, 11, 15, 13, 24, 1));
		ROOMS.add(new Room(300, Kind.PRESIDENT, 3, 26, 13, 41, 1));
	}

	private static final Map<UUID, Integer> lastWarn = new HashMap<>();

	private Hotel() {}

	public static Vec3d lobby(PizdecState st) {
		return new Vec3d(st.cx + 8.5, st.cy, st.cz + 11.5);
	}

	/** Индекс номера, который снимает игрок, или -1. */
	public static int roomOf(MinecraftServer s, UUID id) {
		PizdecState st = PizdecState.get(s);
		long now = s.getOverworld().getTime();
		for (Map.Entry<Integer, PizdecState.Rental> e : st.hotel.entrySet()) {
			if (e.getValue().who.equals(id) && e.getValue().until > now) return e.getKey();
		}
		return -1;
	}

	public static Room room(int idx) {
		return idx >= 0 && idx < ROOMS.size() ? ROOMS.get(idx) : null;
	}

	/** Номер, в котором игрок сейчас стоит (или -1). */
	public static int roomAt(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!st.castleBuilt || p.getServerWorld() != p.getServer().getOverworld()) return -1;
		for (int i = 0; i < ROOMS.size(); i++) if (ROOMS.get(i).contains(st, p.getPos())) return i;
		return -1;
	}

	public static boolean inOwnRoom(ServerPlayerEntity p) {
		int at = roomAt(p);
		return at >= 0 && at == roomOf(p.getServer(), p.getUuid());
	}

	/** Дипломатическая неприкосновенность в королевском люксе. */
	public static boolean diplomat(ServerPlayerEntity p) {
		int at = roomAt(p);
		return at >= 0 && ROOMS.get(at).kind == Kind.PRESIDENT && at == roomOf(p.getServer(), p.getUuid());
	}

	private static int price(Kind k) {
		if (Laws.is("hotel_free")) return 0;
		if (Laws.is("hotel_sale")) return k.price / 2;
		return k.price;
	}

	public static void rent(ServerPlayerEntity p, int idx) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		Room r = room(idx);
		if (r == null) return;
		long now = s.getOverworld().getTime();
		int mine = roomOf(s, p.getUuid());
		PizdecState.Rental cur = st.hotel.get(idx);
		boolean busy = cur != null && cur.until > now && !cur.who.equals(p.getUuid());
		if (busy) {
			U.msg(p, U.t("🏨 Номер " + r.no + " занят. Выберите другой.", Formatting.RED));
			return;
		}
		if (mine >= 0 && mine != idx) {
			U.msg(p, U.t("🏨 У вас уже есть номер " + ROOMS.get(mine).no + ". Сначала выселитесь.", Formatting.RED));
			return;
		}
		if (Jail.busy(p)) {
			U.msg(p, U.t("🏨 Из тюрьмы и с фронта бронирование не принимаем.", Formatting.RED));
			return;
		}
		if (!Docs.require(p, "заселения в отель")) return;
		int cost = price(r.kind);
		if (cost > 0 && !Money.pay(p, cost, "номер " + r.no + " (" + r.kind.title + ")")) return;
		long base = cur != null && cur.who.equals(p.getUuid()) && cur.until > now ? cur.until : now;
		st.hotel.put(idx, new PizdecState.Rental(p.getUuid(), base + STAY));
		st.names.put(p.getUuid(), p.getName().getString());
		st.markDirty();
		Jobs.onHotelGuest(s);
		Vec3d c = r.center(st);
		p.teleport(s.getOverworld(), c.x, c.y, c.z, p.getYaw(), 0f);
		U.title(p, U.t("🏨 НОМЕР " + r.no, Formatting.AQUA, Formatting.BOLD), U.t(r.kind.title + " — 20 минут", Formatting.GRAY));
		U.msg(p, U.t("🏨 Добро пожаловать в номер " + r.no + " (" + r.kind.title + ")! Номер считается вашим адресом "
				+ "для налоговой, кровать в номере бесплатная. Продлить — снова оплатить на ресепшене.", Formatting.AQUA));
		U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
	}

	public static void checkout(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		int mine = roomOf(p.getServer(), p.getUuid());
		if (mine < 0) {
			U.msg(p, U.t("🏨 У вас нет номера.", Formatting.GRAY));
			return;
		}
		st.hotel.remove(mine);
		st.markDirty();
		U.msg(p, U.t("🏨 Вы выселились из номера " + ROOMS.get(mine).no + ". Деньги не возвращаем, полотенца пересчитаем.",
				Formatting.GRAY));
	}

	public static void evictAll(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.hotel.clear();
		st.markDirty();
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		int t = s.getTicks();
		if (t % 10 != 0) return;
		long now = s.getOverworld().getTime();
		if (t % 100 == 0) {
			st.hotel.entrySet().removeIf(e -> {
				if (e.getValue().until > now) return false;
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(e.getValue().who);
				if (p != null) U.msg(p, U.t("🏨 Срок проживания в номере " + ROOMS.get(e.getKey()).no + " истёк.", Formatting.GRAY));
				return true;
			});
		}
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p.isCreative() || p.isSpectator()) continue;
			int at = roomAt(p);
			if (at < 0) continue;
			PizdecState.Rental r = st.hotel.get(at);
			boolean own = r != null && r.until > now && r.who.equals(p.getUuid());
			if (!own) {
				Vec3d l = lobby(st);
				p.teleport(s.getOverworld(), l.x, l.y, l.z, p.getYaw(), p.getPitch());
				Integer last = lastWarn.get(p.getUuid());
				if (last != null && t - last < 20 * 30) {
					Jail.arrest(p, "незаконное проникновение в номер " + ROOMS.get(at).no, 30);
				} else {
					U.msg(p, U.t("🏨 Это чужой или неоплаченный номер. Ещё раз — вызовем карету.", Formatting.RED));
				}
				lastWarn.put(p.getUuid(), t);
				continue;
			}
			Kind k = ROOMS.get(at).kind;
			if (t % 40 == 0 && (k == Kind.LUX || k == Kind.PRESIDENT)) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 60, k == Kind.PRESIDENT ? 1 : 0, false, false));
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, 20, 0, false, false));
			}
		}
	}

	// ------------------------------------------------------------------ ресепшен

	public static final class ReceptionGui extends Gui {
		private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 29, 30, 40};

		private ReceptionGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🏨 Королевский отель — ресепшен", ReceptionGui::new);
		}

		private void redraw() {
			fill(Items.LIGHT_BLUE_STAINED_GLASS_PANE);
			MinecraftServer s = player.getServer();
			PizdecState st = PizdecState.get(s);
			long now = s.getOverworld().getTime();
			int mine = roomOf(s, player.getUuid());
			set(4, icon(Items.BELL, "§bКоролевский отель «Пять звёзд (из десяти)»", "§7Проживание — 20 минут",
					"§7Номер = прописка для налоговой", "§7Кровать в своём номере бесплатная",
					mine >= 0 ? "§aВаш номер: " + ROOMS.get(mine).no + ", осталось "
							+ (st.hotel.get(mine).until - now) / 20 / 60 + " мин" : "§7У вас нет номера"));
			for (int i = 0; i < ROOMS.size() && i < SLOTS.length; i++) {
				Room r = ROOMS.get(i);
				PizdecState.Rental rt = st.hotel.get(i);
				boolean taken = rt != null && rt.until > now;
				boolean me = taken && rt.who.equals(player.getUuid());
				String state = me ? "§aВаш номер — ЛКМ продлить" : taken ? "§cЗанят: " + st.names.getOrDefault(rt.who, "гость")
						: "§eСвободен — ЛКМ снять";
				set(SLOTS[i], icon(r.kind.icon, (me ? "§a" : taken ? "§c" : "§f") + "№" + r.no + " — " + r.kind.title,
						"§6Цена: " + price(r.kind) + " бабла / 20 мин", "§7" + r.kind.desc,
						"§7Этаж: " + (r.floor + 1), state));
			}
			set(48, icon(Items.REDSTONE, "§cВыселиться", "§7Деньги не возвращаются"));
			set(49, icon(Items.GOLD_NUGGET, "§6Твоё бабло: " + Money.get(player)));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			if (slot == 48) {
				checkout(player);
				redraw();
				return;
			}
			for (int i = 0; i < SLOTS.length && i < ROOMS.size(); i++) {
				if (SLOTS[i] == slot) {
					close();
					rent(player, i);
					return;
				}
			}
		}

		@Override
		protected void tick() {
			if (age % 20 == 0) redraw();
		}
	}
}
