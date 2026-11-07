package com.pizdec.mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import static com.pizdec.mod.B.*;

/**
 * Королевский МФЦ «Мои документы». Стоит к северо-востоку от ворот замка.
 * Электронная очередь (терминал у входа), фотобудка, четыре окна:
 * №1 паспорта, №2 регистрация по месту жительства, №3 Росреестр (дома), №4 нотариус.
 */
public final class Mfc {
	public static final int W = 21, D = 15;
	public static final String[] TAGS = {"pizdec_mfc1", "pizdec_mfc2", "pizdec_mfc3", "pizdec_mfc4"};
	public static final String[] CLERKS = {"Паспортистка Галина Ивановна", "Инспектор Валентина Сергеевна",
			"Регистратор Эдуард Кадастрович", "Нотариус Изольда Марковна"};
	public static final String[] WINDOWS = {"Паспорта", "Регистрация по месту жительства", "Росреестр: недвижимость",
			"Нотариус: сделки и справки"};

	private record Ticket(int no, int ready, int expires) {}

	private static final Map<UUID, Ticket> tickets = new HashMap<>();
	private static final Map<UUID, Integer> boothTime = new HashMap<>();
	private static int seq;

	private Mfc() {}

	// ------------------------------------------------------------------ здание

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.mfcBuilt || !st.castleBuilt) return;
		ServerWorld world = s.getOverworld();
		w = world;
		int ox = st.cx + 36, oz = st.cz - 28;
		loadChunks(ox - 18, oz - 4, ox + W + 4, oz + D + 22);
		int oy = ground(ox + W / 2, oz + D / 2);
		build(st, ox, oy, oz);
		st.mfcBuilt = true;
		st.mx = ox;
		st.my = oy;
		st.mz = oz;
		st.markDirty();
		VillagerProfession[] prof = {VillagerProfession.LIBRARIAN, VillagerProfession.CLERIC, VillagerProfession.CARTOGRAPHER,
				VillagerProfession.LIBRARIAN};
		for (int k = 0; k < 4; k++) {
			Castle.npc(world, ox + 3.5 + 5 * k, oy, oz + 1.5, 0f, TAGS[k],
					U.t("Окно №" + (k + 1) + " — " + CLERKS[k], Formatting.AQUA), prof[k]);
		}
		PizdecMod.LOG.info("МФЦ построен на {} {} {}", ox, oy, oz);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.mfcBuilt && p.getX() >= st.mx - 2 && p.getX() <= st.mx + W + 1 && p.getZ() >= st.mz - 2
				&& p.getZ() <= st.mz + D + 1 && p.getY() >= st.my - 5 && p.getY() <= st.my + 10;
	}

	public static BlockPos terminal(PizdecState st) {
		return new BlockPos(st.mx + 13, st.my, st.mz + 12);
	}

	public static Vec3d entrance(PizdecState st) {
		return new Vec3d(st.mx + 10.5, st.my, st.mz + 16.5);
	}

	private static boolean inBooth(PizdecState st, Vec3d p) {
		return p.x >= st.mx + 2 && p.x < st.mx + 3 && p.z >= st.mz + 12 && p.z < st.mz + 14 && Math.abs(p.y - st.my) < 1.5;
	}

	private static void build(PizdecState st, int ox, int oy, int oz) {
		site(ox - 2, oz - 2, ox + W + 1, oz + D + 2, oy, 12, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS);
		fill(ox, oy - 1, oz, ox + W - 1, oy - 1, oz + D - 1, Blocks.POLISHED_ANDESITE);
		walls(ox, oy, oz, ox + W - 1, oy + 4, oz + D - 1, Blocks.WHITE_CONCRETE);
		fill(ox, oy, oz, ox + W - 1, oy, oz, Blocks.LIGHT_GRAY_CONCRETE);
		fill(ox, oy, oz + D - 1, ox + W - 1, oy, oz + D - 1, Blocks.LIGHT_GRAY_CONCRETE);
		for (int x = ox; x < ox + W; x++) {
			for (int z = oz; z < oz + D; z++) {
				boolean lamp = (x - ox) % 4 == 2 && (z - oz) % 4 == 2 && x > ox && z > oz;
				set(x, oy + 5, z, lamp ? Blocks.SEA_LANTERN : Blocks.SMOOTH_STONE);
			}
		}
		// фасад: синяя полоса, вход, флаг
		fill(ox, oy + 4, oz + D - 1, ox + W - 1, oy + 4, oz + D - 1, Blocks.BLUE_CONCRETE);
		fill(ox + 9, oy, oz + D - 1, ox + 11, oy + 2, oz + D - 1, Blocks.AIR);
		fill(ox + 9, oy + 3, oz + D - 1, ox + 11, oy + 3, oz + D - 1, Blocks.GLASS);
		for (int x = 2; x < W - 2; x += 3) {
			if (x >= 8 && x <= 12) continue;
			set(ox + x, oy + 2, oz + D - 1, Blocks.GLASS_PANE);
			set(ox + x, oy + 2, oz, Blocks.GLASS_PANE);
		}
		for (int y = oy; y <= oy + 7; y++) set(ox + 7, y, oz + D + 1, Blocks.OAK_FENCE);
		set(ox + 6, oy + 7, oz + D + 1, Blocks.RED_WOOL);
		set(ox + 5, oy + 7, oz + D + 1, Blocks.YELLOW_WOOL);
		set(ox + 6, oy + 6, oz + D + 1, Blocks.RED_WOOL);
		set(ox + 5, oy + 6, oz + D + 1, Blocks.RED_WOOL);
		lampPost(ox + 13, oy, oz + D + 1);
		// окна №1-4
		Block[] colors = {Blocks.LIME_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.ORANGE_WOOL, Blocks.PURPLE_WOOL};
		for (int k = 0; k < 4; k++) {
			int x0 = ox + 2 + 5 * k;
			fill(x0, oy - 1, oz + 1, x0 + 2, oy - 1, oz + 2, Blocks.SPRUCE_PLANKS);
			fill(x0, oy, oz + 3, x0 + 2, oy, oz + 3, Blocks.QUARTZ_BLOCK); // стойка
			fill(x0, oy + 1, oz + 3, x0 + 2, oy + 2, oz + 3, Blocks.GLASS_PANE);
			set(x0 + 1, oy + 1, oz + 3, Blocks.AIR); // окошко
			set(x0 + 1, oy + 3, oz, colors[k]);
			set(x0 + 1, oy + 3, oz + 3, colors[k]);
			if (k < 3) fill(x0 + 3, oy, oz + 1, x0 + 3, oy + 3, oz + 3, Blocks.WHITE_CONCRETE);
			set(x0, oy, oz + 1, Blocks.BARREL);
			set(x0 + 2, oy, oz + 1, Blocks.BOOKSHELF);
		}
		// зал ожидания: стулья лицом к окнам
		for (int z : new int[] {7, 9}) {
			for (int x = 3; x <= 17; x++) {
				if (x == 10) continue;
				set(ox + x, oy, oz + z, B.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
			}
		}
		fill(ox + 3, oy - 1, oz + 6, ox + 17, oy - 1, oz + 10, Blocks.LIGHT_GRAY_WOOL);
		// терминал электронной очереди
		set(ox + 13, oy, oz + 12, Blocks.LODESTONE);
		set(ox + 13, oy + 1, oz + 12, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
		// табло (экран)
		fill(ox + 8, oy + 3, oz + 5, ox + 12, oy + 3, oz + 5, Blocks.BLACK_CONCRETE);
		set(ox + 10, oy + 3, oz + 5, Blocks.REDSTONE_LAMP);
		// фотобудка
		fill(ox + 1, oy, oz + 11, ox + 1, oy + 2, oz + 13, Blocks.BLACK_CONCRETE);
		fill(ox + 2, oy, oz + 11, ox + 3, oy + 2, oz + 11, Blocks.BLACK_CONCRETE);
		fill(ox + 1, oy + 3, oz + 11, ox + 3, oy + 3, oz + 13, Blocks.BLACK_CONCRETE);
		set(ox + 3, oy + 2, oz + 13, Blocks.RED_WOOL); // шторка
		set(ox + 2, oy + 2, oz + 11, Blocks.SEA_LANTERN); // вспышка
		set(ox + 2, oy - 1, oz + 12, Blocks.WHITE_WOOL);
		set(ox + 2, oy - 1, oz + 13, Blocks.WHITE_WOOL);
		// архив
		fill(ox + W - 2, oy, oz + 6, ox + W - 2, oy + 2, oz + 12, Blocks.BOOKSHELF);
		set(ox + W - 3, oy, oz + 12, Blocks.POTTED_FERN);
		set(ox + 4, oy, oz + 12, Blocks.POTTED_CACTUS);
		// дорожка к воротам замка
		int py = oy - 1;
		fill(ox + 9, py, oz + D, ox + 11, py, oz + D + 2, Blocks.POLISHED_ANDESITE);
		fill(st.cx + 30, py, oz + D + 1, ox + 11, py, oz + D + 3, Blocks.POLISHED_ANDESITE);
	}

	// ------------------------------------------------------------------ очередь

	public static void takeTicket(ServerPlayerEntity p) {
		int now = p.getServer().getTicks();
		Ticket t = tickets.get(p.getUuid());
		if (t != null && t.expires > now) {
			U.msg(p, U.t("🎫 У вас уже есть талон А-" + String.format("%03d", t.no)
					+ (t.ready > now ? ". Ожидайте вызова." : ". Проходите к окну."), Formatting.YELLOW));
			return;
		}
		seq = seq % 999 + 1;
		int wait = 20 * U.between(3, 7);
		tickets.put(p.getUuid(), new Ticket(seq, now + wait, now + wait + 20 * 180));
		U.msg(p, U.t("🎫 Ваш талон: А-" + String.format("%03d", seq) + ". Перед вами " + U.between(0, 3)
				+ " человек. Ожидайте вызова на табло.", Formatting.YELLOW));
		U.sound(p, SoundEvents.UI_BUTTON_CLICK, 1f, 1.4f);
	}

	private static boolean called(ServerPlayerEntity p) {
		Ticket t = tickets.get(p.getUuid());
		int now = p.getServer().getTicks();
		return t != null && t.ready <= now && t.expires > now;
	}

	public static void clerk(ServerPlayerEntity p, int window) {
		if (!called(p)) {
			Ticket t = tickets.get(p.getUuid());
			if (t != null && t.ready > p.getServer().getTicks()) {
				U.msg(p, U.t(CLERKS[window] + ": «Ваш номер ещё не вызвали. Ждите.»", Formatting.GRAY));
			} else {
				U.msg(p, U.t(CLERKS[window] + ": «Без талона не обслуживаем. Терминал у входа (ПКМ по камню-терминалу).»",
						Formatting.RED));
			}
			return;
		}
		if (Jail.busy(p)) return;
		switch (window) {
			case 0 -> PassportGui.open(p);
			case 1 -> RegGui.open(p);
			case 2 -> PropertyGui.open(p);
			default -> NotaryGui.open(p);
		}
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.mfcBuilt) return;
		int now = s.getTicks();
		if (now % 10 != 0) return;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			Ticket t = tickets.get(p.getUuid());
			if (t != null && t.ready > now - 10 && t.ready <= now) {
				int win = U.between(1, 4);
				U.title(p, U.t("🔔 А-" + String.format("%03d", t.no), Formatting.YELLOW, Formatting.BOLD),
						U.t("пройдите к окну №" + win + " (или к любому нужному)", Formatting.GRAY));
				U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
			}
			if (p.getServerWorld() == s.getOverworld() && inBooth(st, p.getPos())) {
				int bt = boothTime.merge(p.getUuid(), 10, Integer::sum);
				if (bt == 10) U.bar(p, U.t("📸 Не двигайтесь… 3… 2… 1…", Formatting.WHITE));
				if (bt >= 60) {
					Docs.photo(p);
					boothTime.put(p.getUuid(), -100000);
				}
			} else {
				boothTime.remove(p.getUuid());
			}
		}
	}

	// ------------------------------------------------------------------ окно №1: паспорта

	public static final class PassportGui extends Gui {
		private PassportGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🪪 Окно №1 — Паспорта", PassportGui::new);
		}

		private static String ok(boolean b) {
			return b ? "§a✔ " : "§c✖ ";
		}

		private void redraw() {
			fill(Items.LIME_STAINED_GLASS_PANE);
			PizdecState.Passport ps = Docs.passport(player.getServer(), player.getUuid());
			Docs.Draft d = Docs.draft(player);
			if (Docs.inProduction(player)) {
				set(22, icon(Items.CLOCK, "§eПаспорт изготавливается…", "§7Ожидайте, это займёт несколько секунд"));
				set(53, icon(Items.BARRIER, "§cЗакрыть"));
				return;
			}
			if (ps == null) {
				set(4, icon(Items.WRITABLE_BOOK, "§fЗаявление о выдаче паспорта",
						"§7Заполните анкету, сфотографируйтесь", "§7в фотобудке у входа и оплатите", "§7госпошлину " + Docs.PASSPORT_FEE + " бабла."));
			} else {
				set(4, icon(Items.WRITTEN_BOOK, "§6Ваш паспорт: " + Docs.number(ps), "§7" + ps.fio, "§7Выдан: " + ps.issued,
						"§7Статус: " + Docs.checkText(Docs.check(player))));
			}
			set(19, icon(Items.NAME_TAG, ok(d.fio != null) + "Фамилия Имя Отчество", d.fio == null ? "§7ЛКМ — ввести" : "§f" + d.fio));
			set(20, icon(Items.CLOCK, ok(d.birth != null) + "Дата рождения", d.birth == null ? "§7ЛКМ — ввести" : "§f" + d.birth));
			set(21, icon(Items.IRON_INGOT, (("Мужской".equals(d.gender)) ? "§a▶ " : "§f") + "Пол: мужской"));
			set(22, icon(Items.GOLD_INGOT, (("Женский".equals(d.gender)) ? "§a▶ " : "§f") + "Пол: женский"));
			set(23, icon(Items.PAINTING, ok(d.photo) + "Фото 3×4", "§7Фотобудка — слева от входа.", "§7Встаньте внутрь на 3 секунды."));
			if (ps == null) {
				set(40, icon(Items.EMERALD_BLOCK, "§a§lПодать заявление", "§7Госпошлина: " + Docs.PASSPORT_FEE + " бабла",
						"§7Срок изготовления: 10 секунд"));
			} else {
				set(38, icon(Items.ANVIL, "§eЗамена паспорта (новые данные)", "§7Заполните поля, которые меняются,",
						"§7и сделайте новое фото. " + Docs.CHANGE_FEE + " бабла"));
				set(40, icon(Items.COMPASS, "§eВосстановить утерянный", "§7" + Docs.REISSUE_FEE + " бабла. Прежний аннулируется"));
				set(42, icon(Items.PAPER, "§eСправка о судимости", "§720 бабла"));
			}
			set(49, icon(Items.GOLD_NUGGET, "§6Твоё бабло: " + Money.get(player)));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			if (Docs.inProduction(player)) return;
			Docs.Draft d = Docs.draft(player);
			PizdecState.Passport ps = Docs.passport(player.getServer(), player.getUuid());
			switch (slot) {
				case 19 -> {
					close();
					Docs.ask(player, Docs.Field.FIO);
				}
				case 20 -> {
					close();
					Docs.ask(player, Docs.Field.BIRTH);
				}
				case 21 -> d.gender = "Мужской";
				case 22 -> d.gender = "Женский";
				case 38 -> {
					if (ps != null) {
						close();
						Docs.submitReissue(player, true);
					}
				}
				case 40 -> {
					close();
					if (ps == null) Docs.submitNew(player);
					else Docs.submitReissue(player, false);
				}
				case 42 -> {
					if (ps != null && Money.pay(player, 20, "справку о судимости")) {
						player.getInventory().offerOrDrop(Docs.criminalCertificate(player.getServer(), player.getUuid()));
					}
				}
				default -> {
				}
			}
			if (player.currentScreenHandler == this) redraw();
		}

		@Override
		protected void tick() {
			if (age % 20 == 0) redraw();
		}
	}

	// ------------------------------------------------------------------ окно №2: регистрация

	public static final class RegGui extends Gui {
		private RegGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🏠 Окно №2 — Регистрация", RegGui::new);
		}

		private void redraw() {
			fill(Items.LIGHT_BLUE_STAINED_GLASS_PANE);
			MinecraftServer s = player.getServer();
			PizdecState st = PizdecState.get(s);
			PizdecState.Passport ps = Docs.passport(s, player.getUuid());
			PizdecState.Address a = Address.valid(s, player.getUuid()) ? st.addresses.get(player.getUuid()) : null;
			set(11, icon(Items.WRITTEN_BOOK, "§fВ паспорте", ps == null ? "§cПаспорта нет (окно №1)"
					: ps.regName.isEmpty() ? "§7Регистрации нет" : "§a«" + ps.regName + "» с " + ps.regDate));
			set(15, icon(Items.OAK_SIGN, "§fВ реестре адресов замка", a == null ? "§cАдреса нет: табличка + /adres"
					: "§a«" + a.name + "» " + U.coords(a.x, a.y, a.z)));
			set(31, icon(Items.EMERALD, "§a§lПоставить штамп о регистрации", "§7Переносит адрес из реестра в паспорт.",
					"§7Пошлина: " + Docs.REG_FEE + " бабла", "§7Номер в отеле — не постоянная регистрация."));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			if (slot == 31) {
				close();
				Docs.stampRegistration(player);
			}
		}
	}

	// ------------------------------------------------------------------ окно №3: Росреестр

	public static final class PropertyGui extends Gui {
		private PropertyGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🏛 Окно №3 — Росреестр", PropertyGui::new);
		}

		private void redraw() {
			fill(Items.ORANGE_STAINED_GLASS_PANE);
			PizdecState st = PizdecState.get(player.getServer());
			PizdecState.Property pr = st.properties.get(player.getUuid());
			BlockPos[] c = Docs.corners(player);
			if (pr != null) {
				set(4, icon(Items.WRITTEN_BOOK, "§6Ваш дом: «" + pr.addr + "»", "§7Кад. № " + pr.cadastral, "§7Площадь: " + pr.area() + " м²",
						"§7Стоимость: " + pr.value, pr.status == 1 ? "§cАРЕСТ: " + pr.reason : "§aОбременений нет"));
				set(20, icon(Items.PAPER, "§eДубликат выписки ЕГРН", "§720 бабла"));
				set(24, icon(Items.REDSTONE, "§cСнять объект с учёта", "§7Защита участка пропадёт"));
			} else {
				boolean sel = c != null && c[0] != null && c[1] != null;
				int area = sel ? (Math.abs(c[0].getX() - c[1].getX()) + 1) * (Math.abs(c[0].getZ() - c[1].getZ()) + 1) : 0;
				set(4, icon(Items.MAP, "§fРегистрация права собственности",
						"§71) Табличка на участке + /adres", "§72) /dom tochka1 и /dom tochka2 — углы", "§73) Паспорт при себе",
						"§74) Госпошлина: 2 бабла за м² (мин. 100)"));
				set(22, icon(Items.EMERALD_BLOCK, "§a§lЗарегистрировать", sel ? "§7Участок: " + area + " м²" : "§cУглы не выбраны",
						sel ? "§7Пошлина: " + Docs.fee(area) + " бабла" : "§7", "§7Максимум 64×64"));
			}
			// торги
			set(31, icon(Items.GOLDEN_HELMET, "§6Торги: конфискованное в пользу Короны", "§7Бывший владелец выкупает за ×1.5",
					"§7Остальные покупают за ×2"));
			for (int i = 0; i < st.crown.size() && i < 9; i++) {
				PizdecState.Property cp = st.crown.get(i);
				boolean former = player.getUuid().equals(st.formerOwners.get(cp.cadastral));
				int price = former ? cp.value * 3 / 2 : cp.value * 2;
				set(36 + i, icon(Items.OAK_DOOR, "§f«" + cp.addr + "»", "§7Кад. № " + cp.cadastral, "§7" + cp.area() + " м²",
						"§7Причина: " + cp.reason, "§6Цена: " + price + (former ? " (выкуп)" : ""), "§eЛКМ — купить"));
			}
			set(49, icon(Items.GOLD_NUGGET, "§6Твоё бабло: " + Money.get(player)));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			PizdecState st = PizdecState.get(player.getServer());
			boolean has = st.properties.containsKey(player.getUuid());
			if (slot == 22 && !has) {
				close();
				Docs.registerProperty(player);
				return;
			}
			if (slot == 20 && has) Docs.duplicateDeed(player);
			if (slot == 24 && has) {
				close();
				Docs.unregister(player);
				return;
			}
			if (slot >= 36 && slot <= 44) {
				close();
				Docs.buyFromCrown(player, slot - 36);
				return;
			}
			redraw();
		}
	}

	// ------------------------------------------------------------------ окно №4: нотариус

	public static final class NotaryGui extends Gui {
		private NotaryGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "📝 Окно №4 — Нотариус", NotaryGui::new);
		}

		private void redraw() {
			fill(Items.PURPLE_STAINED_GLASS_PANE);
			set(11, icon(Items.PAPER, "§eСправка о судимости", "§7Список приговоров. 20 бабла", "§7Нужен паспорт"));
			set(13, icon(Items.WRITABLE_BOOK, "§fКупля-продажа дома", "§7Продавец: /dom prodat <покупатель> <цена>",
					"§7Покупатель: /dom kupit <продавец>", "§7Оба — с паспортами. Госпошлина 5%."));
			set(15, icon(Items.PLAYER_HEAD, "§fЖильцы", "§7Вписать: /dom zhilec <игрок>", "§7Выписать: /dom vypisat <игрок>",
					"§7До 5 жильцов. Им можно всё, что и вам."));
			List<String> cr = PizdecState.get(player.getServer()).crimes.getOrDefault(player.getUuid(), List.of());
			set(31, icon(Items.IRON_BARS, "§7Ваши судимости: " + cr.size(), cr.isEmpty() ? "§aЧисты перед законом" : "§c" + cr.get(cr.size() - 1)));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			if (slot == 11) {
				if (!Docs.require(player, "справки о судимости")) return;
				if (Money.pay(player, 20, "справку о судимости")) {
					player.getInventory().offerOrDrop(Docs.criminalCertificate(player.getServer(), player.getUuid()));
				}
			}
		}
	}

}
