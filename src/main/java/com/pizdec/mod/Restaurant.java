package com.pizdec.mod;

import static com.pizdec.mod.B.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

/**
 * Королевский ресторан «Вкусно и Точка» в восточной части парламентского квартала.
 * Касса, кухня, зал. Заказ получает номер и готовится 5–8 секунд (с работником — быстрее).
 */
public final class Restaurant {
	public static final String NAME = "«Вкусно и Точка»";
	public static final String CASHIER_TAG = "pizdec_cashier";
	public static final String CHEF_TAG = "pizdec_chef";

	/** Позиция в меню. */
	public record Dish(String name, int price, String desc, java.util.function.Supplier<List<ItemStack>> make) {}

	public static final List<Dish> MENU = new ArrayList<>();

	private static final class Order {
		final UUID who;
		final int no;
		int left;
		final Dish dish;

		Order(UUID who, int no, int left, Dish dish) {
			this.who = who;
			this.no = no;
			this.left = left;
			this.dish = dish;
		}
	}

	private static final List<Order> orders = new ArrayList<>();
	private static int orderSeq = 100;
	private static int served;

	private static final String[] TRAY = {
			"На подносе вкладыш: «Король ест здесь каждый день. Иногда дважды.»",
			"На подносе вкладыш: «Картошка фри — гордость отечественного картофелеводства с 1 года правления»",
			"На подносе вкладыш: «Каждый бургер — вклад в казну. Спасибо за вашу лояльность!»",
			"На подносе вкладыш: «В соседних королевствах едят траву. У нас — бургеры.»",
			"На подносе вкладыш: «Соус «Королевский» — рецепт хранится в тайной полиции.»",
			"На подносе вкладыш: «Индекс бургера: у нас самый дешёвый бургер в мире (по данным королевской статистики)»",
			"На подносе вкладыш: «Помни: налоговая работает и во время обеда.»",
			"На подносе вкладыш: «Оставь отзыв! Положительный.»"};

	static {
		MENU.add(new Dish("§6Королевский бургер", 25, "Двойная котлета, сыр, соус «Королевский»",
				() -> List.of(food(Items.COOKED_BEEF, 2, "§6Королевский бургер", "Двойная котлета. Сыр. Лояльность."))));
		MENU.add(new Dish("§eКартошка фри", 10, "Большая. Из королевского картофеля.",
				() -> List.of(food(Items.BAKED_POTATO, 3, "§eКартошка фри", "Хрустящая, как бюджет королевства."))));
		MENU.add(new Dish("§fНаггетсы (6 шт.)", 12, "Курица высшего сорта (по документам)",
				() -> List.of(food(Items.COOKED_CHICKEN, 2, "§fНаггетсы", "Курица высшего сорта. По документам."))));
		MENU.add(new Dish("§6Шаурма Его Величества", 18, "Лаваш, мясо, капуста и тайна",
				() -> List.of(food(Items.RABBIT_STEW, 1, "§6Шаурма Его Величества", "Внутри — мясо. Какое — государственная тайна."))));
		MENU.add(new Dish("§fМолочный коктейль", 30, "Снимает все эффекты. Даже от указов.",
				() -> List.of(food(Items.MILK_BUCKET, 1, "§fМолочный коктейль «Пломбир»",
						"Снимает все эффекты, в том числе от указов.", "Королю не говорите."))));
		MENU.add(new Dish("§cХрустик с лобстером", 15, "Те самые чипсы",
				() -> List.of(Fpv.chips(1))));
		MENU.add(new Dish("§eПирожок с вишней", 8, "Горячий. Очень горячий.",
				() -> List.of(food(Items.PUMPKIN_PIE, 1, "§eПирожок с вишней", "Внутри — лава. Осторожно."))));
		MENU.add(new Dish("§aСалат «Цезарь-Король»", 14, "Капуста, сухари, уважение к монархии",
				() -> List.of(food(Items.BEETROOT_SOUP, 1, "§aСалат «Цезарь-Король»", "Полезно. По указу."))));
		MENU.add(new Dish("§6§lКомбо «Налоговый»", 40, "Бургер + фри + коктейль. 13% — сразу в казну.",
				() -> List.of(food(Items.COOKED_BEEF, 2, "§6Королевский бургер", "Из комбо «Налоговый»"),
						food(Items.BAKED_POTATO, 3, "§eКартошка фри", "Из комбо «Налоговый»"),
						food(Items.MILK_BUCKET, 1, "§fМолочный коктейль «Пломбир»", "Снимает все эффекты."))));
		MENU.add(new Dish("§dДетское комбо «Будущий налогоплательщик»", 20, "Наггетсы + сок + игрушка",
				() -> List.of(food(Items.COOKED_CHICKEN, 1, "§fНаггетсы", "Детская порция"),
						food(Items.HONEY_BOTTLE, 1, "§6Сок «Яблоко короля»", "Из королевских садов"),
						Gui.petRock())));
		MENU.add(new Dish("§4§lКомбо «Депутатское»", 120, "Всё лучшее. Оплачено народом.",
				() -> List.of(food(Items.GOLDEN_CARROT, 4, "§6Морковь в золоте", "Скромный депутатский перекус"),
						food(Items.COOKED_BEEF, 4, "§6Королевский бургер", "Двойная порция"),
						food(Items.GOLDEN_APPLE, 1, "§6Яблоко «Неприкосновенность»", "Только для слуг народа"))));
	}

	private Restaurant() {}

	private static ItemStack food(net.minecraft.item.Item item, int count, String name, String... lore) {
		ItemStack st = Gui.icon(item, name, lore);
		st.setCount(Math.min(count, st.getMaxCount()));
		return st;
	}

	// ------------------------------------------------------------------ где

	/** Зал ресторана (с кухней): x 48..55, z 51..69 относительно замка. Вход с севера. */
	public static boolean inside(PizdecState st, Vec3d p) {
		return st.castleBuilt && p.x >= st.cx + 48 && p.x < st.cx + 56 && p.z >= st.cz + 51 && p.z < st.cz + 70
				&& p.y >= st.cy - 1 && p.y < st.cy + 7;
	}

	public static Vec3d entrance(PizdecState st) {
		return new Vec3d(st.cx + 51.5, st.cy, st.cz + 49.5);
	}

	// ------------------------------------------------------------------ здание

	/** Строит ресторан. Координаты замка (ox, oy, oz). */
	static void build(int ox, int oy, int oz) {
		int x0 = ox + 48, x1 = ox + 55, z0 = oz + 51, z1 = oz + 69, h = 6;
		// старые фонари квартала на этом месте
		clear(ox + 50, oy, oz + 60, ox + 50, oy + 2, oz + 60);
		clear(ox + 50, oy, oz + 70, ox + 50, oy + 2, oz + 70);
		clear(x0, oy, z0, x1, oy + h + 1, z1);
		clear(ox + 42, oy, oz + 47, ox + 51, oy + 4, oz + 50);
		// дорожка от прохода из замка к двери
		fill(ox + 42, oy - 1, oz + 47, ox + 51, oy - 1, oz + 48, Blocks.POLISHED_ANDESITE);
		fill(ox + 50, oy - 1, oz + 49, ox + 52, oy - 1, oz + 50, Blocks.SMOOTH_STONE);
		// пол — шахматка
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				set(x, oy - 1, z, (x + z) % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE);
			}
		}
		walls(x0, oy, z0, x1, oy + h, z1, Blocks.SMOOTH_QUARTZ);
		// цокольный пояс и фриз — бирюзовая терракота
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				if (!edge) continue;
				set(x, oy, z, Blocks.CYAN_TERRACOTTA);
				set(x, oy + 5, z, Blocks.CYAN_TERRACOTTA);
				set(x, oy + 6, z, Blocks.SPRUCE_PLANKS);
			}
		}
		fill(x0, oy + h + 1, z0, x1, oy + h + 1, z1, Blocks.SPRUCE_SLAB);
		for (int x = x0 + 2; x <= x1 - 1; x += 3) for (int z = z0 + 2; z <= z1 - 1; z += 4) set(x, oy + h, z, Blocks.GLOWSTONE);
		// фасад (север): вход, витрины, полосатый козырёк, вывеска
		fill(ox + 50, oy, z0, ox + 52, oy + 2, z0, Blocks.AIR);
		for (int x : new int[] {49, 54}) fill(ox + x, oy + 1, z0, ox + x, oy + 3, z0, Blocks.GLASS_PANE);
		for (int x = 49; x <= 54; x++) set(ox + x, oy + 3, z0 - 1, (x % 2 == 0) ? Blocks.CYAN_WOOL : Blocks.WHITE_WOOL);
		set(ox + 49, oy, z0 - 1, Blocks.POTTED_RED_TULIP);
		set(ox + 53, oy, z0 - 1, Blocks.POTTED_RED_TULIP);
		sign(ox + 51, oy + 4, z0 - 1, Direction.NORTH, "§l«Вкусно", "§lи Точка»", "Королевский", "ресторан");
		sign(ox + 50, oy + 4, z0 - 1, Direction.NORTH, "Бургеры", "Шаурма", "Фри", "Коктейли");
		sign(ox + 52, oy + 4, z0 - 1, Direction.NORTH, "Открыто", "24/7", "Даже во время", "налоговой");
		// боковые окна
		for (int z : new int[] {54, 55, 57, 58, 60, 61}) {
			fill(x0, oy + 1, oz + z, x0, oy + 3, oz + z, Blocks.GLASS_PANE);
			fill(x1, oy + 1, oz + z, x1, oy + 3, oz + z, Blocks.GLASS_PANE);
		}
		// зал (z 52..62): столики со стульями
		for (int z : new int[] {55, 58, 61}) {
			for (int x : new int[] {ox + 50, ox + 53}) {
				table(x, oy, oz + z);
				set(x, oy, oz + z - 1, B.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH));
				set(x, oy, oz + z + 1, B.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
			}
		}
		set(x0 + 1, oy, z0 + 1, Blocks.POTTED_FERN);
		set(x1 - 1, oy, z0 + 1, Blocks.BARREL); // мусорка
		// касса (z = 63): стойка, проход для персонала у восточной стены
		for (int x = x0 + 1; x <= x1 - 2; x++) {
			set(x, oy, oz + 63, Blocks.CYAN_TERRACOTTA);
			set(x, oy + 1, oz + 63, Blocks.SMOOTH_QUARTZ_SLAB);
		}
		set(x0 + 1, oy + 2, oz + 63, Blocks.LANTERN);
		// кухня (z 64..68): плиты, коптильни, холодильник, табло заказов
		for (int x = x0 + 1; x <= x1 - 1; x++) set(x, oy, z1 - 1, x % 2 == 0 ? Blocks.SMOKER : Blocks.FURNACE);
		set(x0 + 1, oy, z1 - 2, Blocks.CAULDRON);
		set(x1 - 1, oy, z1 - 3, Blocks.IRON_BLOCK);
		set(x1 - 1, oy + 1, z1 - 3, Blocks.IRON_TRAPDOOR);
		set(x0 + 2, oy + 1, z1 - 1, Blocks.CAMPFIRE);
		fill(x0 + 2, oy + 3, z1, x1 - 2, oy + 4, z1, Blocks.BLACK_CONCRETE);
	}

	/** Табличка на стене: (x,y,z) — где висит, facing — куда смотрит текст. */
	static void sign(int x, int y, int z, Direction facing, String... lines) {
		set(x, y, z, Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(net.minecraft.block.WallSignBlock.FACING, facing));
		if (w.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z)) instanceof net.minecraft.block.entity.SignBlockEntity sb) {
			net.minecraft.block.entity.SignText t = sb.getText(true);
			for (int i = 0; i < lines.length && i < 4; i++) t = t.withMessage(i, net.minecraft.text.Text.literal(lines[i]));
			sb.setText(t, true);
			sb.markDirty();
		}
	}

	/** Стоячая табличка: rot 0..15 (0 — текст смотрит на юг, 8 — на север). */
	static void standingSign(int x, int y, int z, int rot, String... lines) {
		set(x, y, z, Blocks.BIRCH_SIGN.getDefaultState().with(net.minecraft.state.property.Properties.ROTATION, rot));
		if (w.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z)) instanceof net.minecraft.block.entity.SignBlockEntity sb) {
			net.minecraft.block.entity.SignText t = sb.getText(true);
			for (int i = 0; i < lines.length && i < 4; i++) t = t.withMessage(i, net.minecraft.text.Text.literal(lines[i]));
			sb.setText(t, true);
			sb.markDirty();
		}
	}

	static void spawnNpcs(ServerWorld world, PizdecState st) {
		Castle.npc(world, st.cx + 51.5, st.cy, st.cz + 64.5, 180f, CASHIER_TAG,
				U.t("🍔 Кассир Ксюша (" + NAME + ")", Formatting.AQUA), VillagerProfession.BUTCHER);
		Castle.npc(world, st.cx + 53.5, st.cy, st.cz + 66.5, 180f, CHEF_TAG,
				U.t("👨‍🍳 Шеф-повар Гордей", Formatting.WHITE), VillagerProfession.BUTCHER);
	}

	// ------------------------------------------------------------------ заказы

	private static int workersInside(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		int n = 0;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (Jobs.is(p, "waiter") && inside(st, p.getPos())) n++;
		}
		return n;
	}

	public static void order(ServerPlayerEntity p, Dish d) {
		MinecraftServer s = p.getServer();
		if (orders.stream().filter(x -> x.who.equals(p.getUuid())).count() >= 3) {
			U.msg(p, U.t("🍔 Ксюша: «У вас уже три заказа в работе. Не жадничайте.»", Formatting.GRAY));
			return;
		}
		if (BlackMarket.dishShort(MENU.indexOf(d))) {
			U.msg(p, U.t("🍔 Ксюша: «" + d.name.replaceAll("§.", "") + " — нет. Поставщик… ну, вы понимаете. Возьмите пирожок.»",
					Formatting.GRAY));
			return;
		}
		int price = d.price * Laws.priceMult();
		boolean free = Laws.is("free_burger");
		if (free) price = 0;
		if (price > 0 && !Money.pay(p, price, d.name.replaceAll("§.", ""))) return;
		int no = ++orderSeq;
		if (orderSeq >= 999) orderSeq = 100;
		int prep = U.between(20 * 5, 20 * 8);
		int workers = workersInside(s);
		if (workers > 0) prep = Math.max(20 * 2, prep / (1 + workers));
		orders.add(new Order(p.getUuid(), no, prep, d));
		U.msg(p, U.t("🧾 Заказ №" + no + " принят: " + d.name.replaceAll("§.", "") + (free ? " (бесплатно по указу!)" : "")
				+ ". Готовность ~" + prep / 20 + " сек." + (workers > 0 ? " На кухне работают люди — быстрее." : ""),
				Formatting.YELLOW));
		if (d.name.contains("Налоговый") && price > 0) {
			U.msg(p, U.t("🧾 13% стоимости комбо (" + price * 13 / 100 + " бабла) ушло в казну. Спасибо за патриотизм!", Formatting.GOLD));
		}
		U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
		Laws.onBuy(p);
		for (ServerPlayerEntity wkr : s.getPlayerManager().getPlayerList()) {
			if (Jobs.is(wkr, "waiter")) Jobs.share(wkr, 5);
		}
	}

	public static void tick(MinecraftServer s) {
		if (orders.isEmpty()) return;
		PizdecState st = PizdecState.get(s);
		for (Order o : new ArrayList<>(orders)) {
			if (--o.left > 0) continue;
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(o.who);
			if (p == null) {
				orders.remove(o);
				continue;
			}
			if (Jail.busy(p)) {
				// заказ остынет и уйдёт сокамерникам
				orders.remove(o);
				U.msg(p, U.t("🍔 Заказ №" + o.no + " остыл — вас забрали. Его съела стража.", Formatting.GRAY));
				continue;
			}
			orders.remove(o);
			served++;
			for (ItemStack it : o.dish.make.get()) p.getInventory().offerOrDrop(it.copy());
			U.title(p, U.t("🍔 Заказ №" + o.no + " готов!", Formatting.GOLD), U.t("Приятного аппетита. Слава королю!", Formatting.YELLOW));
			U.sound(p, SoundEvents.BLOCK_BELL_USE, 1f, 1.2f);
			U.msg(p, U.t(U.pick(TRAY), Formatting.DARK_AQUA));
			for (ServerPlayerEntity o2 : s.getPlayerManager().getPlayerList()) {
				if (o2 != p && inside(st, o2.getPos())) U.bar(o2, U.t("📺 Табло: «Заказ №" + o.no + " готов!»", Formatting.GOLD));
			}
			if (served % 25 == 0) {
				News.breaking(s, "«Вкусно и Точка» продал " + served + "-й бургер! Экономика королевства растёт.");
			}
		}
	}

	public static void cashierTalk(ServerPlayerEntity p) {
		MenuGui.open(p);
	}

	// ------------------------------------------------------------------ меню

	public static final class MenuGui extends Gui {
		private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24};

		private MenuGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🍔 " + NAME + " — меню", MenuGui::new);
		}

		private static net.minecraft.item.Item iconOf(Dish d) {
			ItemStack st = d.make.get().get(0);
			return st.getItem();
		}

		private void redraw() {
			fill(Items.CYAN_STAINED_GLASS_PANE);
			int mult = Laws.priceMult();
			boolean free = Laws.is("free_burger");
			for (int i = 0; i < MENU.size() && i < SLOTS.length; i++) {
				Dish d = MENU.get(i);
				if (BlackMarket.dishShort(i)) {
					set(SLOTS[i], icon(Items.BARRIER, d.name + " §c— нет в наличии", "§7Временно повышенный спрос"));
					continue;
				}
				set(SLOTS[i], icon(iconOf(d), d.name + " §7— " + (free ? "§aбесплатно" : "§e" + d.price * mult + " бабла"),
						"§7" + d.desc, "§eЛКМ — заказать"));
			}
			int mine = 0;
			for (Order o : orders) if (o.who.equals(player.getUuid())) mine++;
			set(40, icon(Items.PAPER, "§fВаши заказы на кухне: " + mine, "§7Номер вызовут на табло", "§7Заказ выдаётся в руки"));
			set(4, icon(Items.GOLDEN_HELMET, "§6Король рекомендует", "§7Комбо «Налоговый» — 13% в казну!",
					"§7Сегодня в зале обедал король (по слухам)"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			for (int i = 0; i < SLOTS.length && i < MENU.size(); i++) {
				if (SLOTS[i] == slot) {
					order(player, MENU.get(i));
					redraw();
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
