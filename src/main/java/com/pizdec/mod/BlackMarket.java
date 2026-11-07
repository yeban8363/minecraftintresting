package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * Плановая экономика: в магазине и ресторане регулярно чего-то нет. Зато у фарцовщика Жоры есть всё —
 * в 2,5 раза дороже, из-под полы, и тайная полиция любит контрольные закупки.
 */
public final class BlackMarket {
	public static final String TAG = "pizdec_blackmarket";
	public static final int ROTATE = 20 * 180, DEALER_EVERY = 20 * 360, DEALER_STAYS = 20 * 180;
	private static final String[] SIDES = {"севернее", "южнее", "западнее", "восточнее", "к северо-востоку от", "к юго-западу от",
			"к северо-западу от", "к юго-востоку от"};

	private static final Set<Integer> shortage = new HashSet<>();
	private static final Set<Integer> dishShortage = new HashSet<>();
	private static final Map<Integer, Integer> limited = new HashMap<>();
	private static int rotateTimer = ROTATE - 20 * 30, dealerTimer = DEALER_EVERY - 20 * 120, dealerLeft;
	private static WanderingTraderEntity dealer;
	private static boolean spawning;
	private static String rumor;

	private BlackMarket() {}

	public static boolean shortage(int offer) {
		return shortage.contains(offer);
	}

	public static boolean dishShort(int dish) {
		return dishShortage.contains(dish);
	}

	/** Сколько осталось «выброшенного» товара (0 — обычная продажа). */
	public static int limited(int offer) {
		return limited.getOrDefault(offer, 0);
	}

	public static void onBought(MinecraftServer s, int offer) {
		Integer left = limited.get(offer);
		if (left == null) return;
		if (left <= 1) {
			limited.remove(offer);
			shortage.add(offer);
			U.say(s, U.t("🛒 «" + Shop.offerStack(offer).getName().getString() + "» снова закончился. Очередь расходится.",
					Formatting.GRAY));
		} else {
			limited.put(offer, left - 1);
		}
	}

	private static List<String> names(Set<Integer> set) {
		List<String> l = new ArrayList<>();
		for (int i : set) l.add(Shop.offerStack(i).getName().getString());
		return l;
	}

	public static List<String> shortageNames() {
		return names(shortage);
	}

	// ================================================================== плановая экономика

	private static void rotate(MinecraftServer s) {
		List<Integer> prev = new ArrayList<>(shortage);
		shortage.clear();
		limited.clear();
		int n = Laws.is("deficit_crisis") ? U.between(12, 20) : U.between(4, 8);
		int max = Math.min(Shop.offers(), 45);
		for (int k = 0; k < n; k++) shortage.add(U.R.nextInt(max));
		// «выбросили» один из прошлых дефицитов — ограниченной партией
		prev.removeAll(shortage);
		if (!prev.isEmpty()) {
			int i = U.pick(prev);
			limited.put(i, U.between(2, 4));
			U.say(s, U.t("📢 Внимание! В королевский магазин ВЫБРОСИЛИ «" + Shop.offerStack(i).getName().getString()
					+ "»! Партия ограничена: " + limited.get(i) + " шт. Очередь занимать у Жорика.", Formatting.YELLOW));
		}
		dishShortage.clear();
		if (U.chance(0.6)) dishShortage.add(U.R.nextInt(Restaurant.MENU.size()));
		if (U.chance(0.3)) dishShortage.add(U.R.nextInt(Restaurant.MENU.size()));
		U.say(s, U.t("🛒 Временно нет в продаже: " + String.join(", ", shortageNames())
				+ ". Министерство торговли: «Это не дефицит, это повышенный спрос благодарного населения.»", Formatting.GOLD));
		Politics.event(s, -0.4);
	}

	// ================================================================== фарцовщик

	private static void spawnDealer(MinecraftServer s, PizdecState st) {
		ServerWorld w = s.getOverworld();
		Vec3d c = Castle.courtyard(st);
		int side = U.R.nextInt(8);
		double ang = Math.toRadians(new double[] {270, 90, 180, 0, 315, 135, 225, 45}[side]);
		double r = U.between(55, 130);
		int x = (int) Math.floor(c.x + Math.cos(ang) * r), z = (int) Math.floor(c.z + Math.sin(ang) * r);
		w.getChunk(x >> 4, z >> 4);
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (!w.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) return;
		WanderingTraderEntity t = EntityType.WANDERING_TRADER.create(w);
		if (t == null) return;
		t.refreshPositionAndAngles(x + 0.5, y, z + 0.5, U.R.nextFloat() * 360, 0);
		t.setAiDisabled(true);
		t.setInvulnerable(true);
		t.setCustomName(U.t("🧥 Фарцовщик Жора", Formatting.DARK_PURPLE));
		t.setCustomNameVisible(true);
		t.addCommandTag(TAG);
		spawning = true;
		w.spawnEntity(t);
		spawning = false;
		dealer = t;
		dealerLeft = DEALER_STAYS;
		rumor = SIDES[side] + " замка, блоков в " + (int) (Math.round(r / 10.0) * 10);
		for (ServerPlayerEntity p : U.players(s)) {
			if (SecretPolice.member(p)) {
				U.msg(p, U.t("🥔 [Подполье] Жора на точке: " + U.coords(x, y, z) + ". 3 минуты.", Formatting.DARK_GREEN));
			} else if (U.chance(0.7)) {
				U.msg(p, U.t("🕶 Шёпот в толпе: «Жора приехал. Говорят, " + rumor + ". Есть всё, чего нет.»",
						Formatting.DARK_PURPLE, Formatting.ITALIC));
			}
		}
	}

	private static void removeDealer(MinecraftServer s, String why) {
		if (dealer != null) {
			dealer.discard();
			dealer = null;
			if (why != null) {
				for (ServerPlayerEntity p : U.players(s)) {
					if (SecretPolice.member(p)) U.msg(p, U.t("🥔 [Подполье] Жора свернул лавочку: " + why, Formatting.DARK_GREEN));
				}
			}
		}
		rumor = null;
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		if (++rotateTimer >= ROTATE) {
			rotateTimer = 0;
			rotate(s);
		}
		if (dealer != null) {
			if (dealer.isRemoved() || --dealerLeft <= 0) removeDealer(s, "уехал");
		} else if (++dealerTimer >= DEALER_EVERY) {
			dealerTimer = 0;
			if (!U.players(s).isEmpty()) spawnDealer(s, st);
		}
	}

	public static void onEntityLoad(Entity e) {
		if (spawning) return;
		if (e.getCommandTags().contains(TAG) && e != dealer) e.discard();
	}

	public static void onAttack(MinecraftServer s) {
		removeDealer(s, "его спугнули");
	}

	// ================================================================== торговля из-под полы

	public static final class DealerGui extends Gui {
		private final List<Integer> goods = new ArrayList<>();

		private DealerGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			goods.addAll(shortage);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🧥 Жора: «Чё надо?»", DealerGui::new);
		}

		private static int price(int i) {
			return Shop.offerPrice(i) * 5 / 2;
		}

		private void redraw() {
			fill(Items.PURPLE_STAINED_GLASS_PANE);
			int slot = 10;
			for (int i : goods) {
				if (slot > 34) break;
				ItemStack st = Shop.offerStack(i);
				set(slot, icon(st, "§d" + st.getName().getString() + (st.getCount() > 1 ? " ×" + st.getCount() : ""),
						"§eЦена: " + price(i) + " бабла (в магазине — нет)", "§8Из-под полы. Без чека.", "§eЛКМ — взять"));
				slot++;
				if (slot % 9 == 8) slot += 2;
			}
			for (int k = 0; k < Border.FOREIGN.length; k++) {
				ItemStack st = Border.foreign(Border.FOREIGN[k]);
				set(45 + k, icon(st, st.getName().getString() + " §7— §e" + Border.FOREIGN_PRICE[k] * 3 / 2,
						"§7Импорт. Контрабанда.", "§eЛКМ — взять"));
			}
			set(4, icon(Items.ENDER_EYE, "§5Правила рынка", "§7Каждая покупка — внимание тайной полиции.",
					"§7Иногда Жора — это контрольная закупка.", "§7Уходит через " + dealerLeft / 20 + " сек."));
			set(53, icon(Items.BARRIER, "§cУйти, будто ничего не было"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			ItemStack give = null;
			int cost = 0;
			if (slot >= 45 && slot < 45 + Border.FOREIGN.length) {
				int k = slot - 45;
				give = Border.foreign(Border.FOREIGN[k]);
				cost = Border.FOREIGN_PRICE[k] * 3 / 2;
			} else {
				int idx = 0;
				for (int s2 = 10; s2 <= 34 && idx < goods.size(); s2++) {
					if (s2 % 9 == 0 || s2 % 9 == 8) continue;
					if (s2 == slot) {
						give = Shop.offerStack(goods.get(idx));
						cost = price(goods.get(idx));
						break;
					}
					idx++;
				}
			}
			if (give == null) return;
			if (!Money.pay(player, cost, "товар у Жоры")) return;
			MinecraftServer s = player.getServer();
			if (U.chance(0.08)) {
				close();
				U.title(player, U.t("🕶 КОНТРОЛЬНАЯ ЗАКУПКА", Formatting.DARK_RED, Formatting.BOLD),
						U.t("Жора оказался майором", Formatting.GRAY));
				SecretPolice.add(player, 15, "попался на контрольной закупке у фарцовщика");
				Jail.arrest(player, "спекуляция и скупка дефицита", 60);
				removeDealer(s, "оказался майором");
				return;
			}
			player.getInventory().offerOrDrop(give);
			U.sound(player, SoundEvents.ENTITY_WANDERING_TRADER_YES, 1f, 1f);
			if (U.chance(0.6)) SecretPolice.add(player, 6, "покупал у фарцовщика");
			redraw();
		}

		@Override
		protected void tick() {
			if (age % 20 == 0) {
				if (dealer == null) close();
				else redraw();
			}
		}
	}

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("rynok").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (dealer == null || rumor == null) {
				U.msg(p, U.t("🕶 Чёрного рынка не существует. Жора сейчас тоже не существует. Загляните позже.",
						Formatting.DARK_PURPLE));
			} else if (SecretPolice.member(p)) {
				BlockPos b = dealer.getBlockPos();
				U.msg(p, U.t("🥔 Жора: " + U.coords(b.getX(), b.getY(), b.getZ()) + ", ещё " + dealerLeft / 20 + " сек.",
						Formatting.DARK_GREEN));
			} else {
				U.msg(p, U.t("🕶 Говорят, Жора стоит " + rumor + ". Ищи человека в длинном плаще. Ещё ~" + dealerLeft / 20
						+ " сек.", Formatting.DARK_PURPLE));
				SecretPolice.add(p, 2, "интересовался чёрным рынком");
			}
			U.msg(p, U.t("🛒 Дефицит сейчас: " + (shortage.isEmpty() ? "нет (официально)" : String.join(", ", shortageNames())),
					Formatting.GRAY));
			return 1;
		}));
	}
}
