package com.pizdec.mod;

import static com.pizdec.mod.B.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

/**
 * Секретный государственный архив — в подвале «Управления по делам ничего особенного».
 * Личные дела, история судебных процессов, летопись указов, секретные документы и данные о коррупции.
 * Хранилище — за решёткой, по допуску. Можно украсть папку и слить её в прессу (очень опасно).
 */
public final class Archive {
	public static final String ARCHIVIST_TAG = "pizdec_archivist";
	private static final java.util.Map<UUID, Long> access = new java.util.HashMap<>();
	private static final java.util.Map<UUID, Integer> lastCaughtCheck = new java.util.HashMap<>();

	private Archive() {}

	// ================================================================== здание (подвал под штабом ТП)

	static void build(int ox, int oy, int oz) {
		int x0 = ox + 2, x1 = ox + 8, z0 = oz + 52, z1 = oz + 78, fy = oy - 6;
		// коробка подвала
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				set(x, fy, z, Blocks.POLISHED_DEEPSLATE);
				for (int y = fy + 1; y <= oy - 2; y++) set(x, y, z, edge ? Blocks.DEEPSLATE_TILES : Blocks.AIR);
			}
		}
		// лестница из приёмной
		set(ox + 3, oy, oz + 58, Blocks.AIR); // убираем бочку
		for (int k = 1; k <= 5; k++) set(ox + 3, oy - k, oz + 55 + k, B.stairs(Blocks.DEEPSLATE_TILE_STAIRS, Direction.SOUTH));
		set(ox + 3, oy - 1, oz + 57, Blocks.AIR);
		set(ox + 3, oy - 1, oz + 58, Blocks.AIR);
		set(ox + 3, oy - 1, oz + 59, Blocks.AIR);
		set(ox + 4, oy, oz + 57, Blocks.DARK_OAK_FENCE);
		set(ox + 4, oy, oz + 58, Blocks.DARK_OAK_FENCE);
		// читальный зал (z 61..65)
		for (int z = 62; z <= 64; z++) set(ox + 5, fy + 1, oz + z, Blocks.DARK_OAK_PLANKS);
		set(ox + 5, fy + 2, oz + 62, Blocks.LANTERN);
		set(ox + 5, fy + 2, oz + 64, Blocks.LECTERN);
		set(ox + 7, fy + 1, oz + 61, Blocks.BARREL);
		set(ox + 7, fy + 1, oz + 65, Blocks.BARREL);
		set(ox + 7, fy + 2, oz + 65, Blocks.BARREL);
		B.chandelier(ox + 5, oy - 1, oz + 63, 0);
		// решётка с железной дверью (z 66)
		for (int x = 3; x <= 7; x++) for (int y = fy + 1; y <= oy - 2; y++) set(ox + x, y, oz + 66, Blocks.IRON_BARS);
		B.door(ox + 5, fy + 1, oz + 66, Direction.NORTH, Blocks.IRON_DOOR);
		Restaurant.sign(ox + 4, fy + 3, oz + 65, Direction.NORTH, "§4ХРАНИЛИЩЕ", "Вход по допуску", "Гриф: ОВ", "");
		// хранилище (z 67..77): стеллажи, ящики, сейф
		for (int z = 67; z <= 76; z++) {
			for (int y = fy + 1; y <= fy + 3; y++) {
				set(ox + 3, y, oz + z, (z + y) % 4 == 0 ? Blocks.BARREL : Blocks.BOOKSHELF);
				set(ox + 7, y, oz + z, (z + y) % 5 == 0 ? Blocks.BARREL : Blocks.BOOKSHELF);
			}
		}
		for (int z = 69; z <= 75; z += 3) B.chandelier(ox + 5, oy - 1, oz + z, 0);
		set(ox + 5, fy + 1, oz + 77, Blocks.VAULT);
		set(ox + 4, fy + 1, oz + 77, Blocks.IRON_BLOCK);
		set(ox + 6, fy + 1, oz + 77, Blocks.IRON_BLOCK);
	}

	static void spawnNpcs(ServerWorld world, PizdecState st) {
		Castle.npc(world, st.cx + 6.5, st.cy - 5, st.cz + 63.5, 90f, ARCHIVIST_TAG,
				U.t("📁 Архивариус Пыльцов", Formatting.GRAY), VillagerProfession.LIBRARIAN);
	}

	private static BlockPos vault(PizdecState st) {
		return new BlockPos(st.cx + 5, st.cy - 5, st.cz + 77);
	}

	private static boolean inVaultRoom(PizdecState st, Vec3d p) {
		return st.castleBuilt && p.x >= st.cx + 3 && p.x < st.cx + 8 && p.z >= st.cz + 67 && p.z < st.cz + 78
				&& p.y >= st.cy - 6 && p.y < st.cy - 1;
	}

	private static Vec3d readingRoom(PizdecState st) {
		return new Vec3d(st.cx + 4.5, st.cy - 5, st.cz + 62.5);
	}

	private static Vec3d vaultInside(PizdecState st) {
		return new Vec3d(st.cx + 5.5, st.cy - 5, st.cz + 68.5);
	}

	// ================================================================== допуск

	public static boolean cleared(ServerPlayerEntity p) {
		if (Jobs.is(p, "agent") || Jobs.is(p, "assistant")) return true;
		Long until = access.get(p.getUuid());
		return until != null && until > p.getServer().getOverworld().getTime();
	}

	// ================================================================== журналы

	public static void logTrial(ServerPlayerEntity p, String crime, String verdict) {
		PizdecState st = PizdecState.get(p.getServer());
		String c = crime.length() > 60 ? crime.substring(0, 60) + "…" : crime;
		st.log(st.trials, Docs.today() + " · " + p.getName().getString() + ": " + c + " → " + verdict, 60);
	}

	// ================================================================== книги

	private static ItemStack book(String title, List<String> lines, String grif) {
		List<RawFilteredPair<Text>> pages = new ArrayList<>();
		StringBuilder cur = new StringBuilder("§4" + grif + "§0\n\n");
		for (String l : lines) {
			if (cur.length() + l.length() > 230 && cur.length() > 20) {
				pages.add(RawFilteredPair.of(Text.literal(cur.toString())));
				cur = new StringBuilder();
				if (pages.size() >= 50) break;
			}
			cur.append(l).append("\n\n");
		}
		if (cur.length() > 0) pages.add(RawFilteredPair.of(Text.literal(cur.toString())));
		ItemStack st = new ItemStack(Items.WRITTEN_BOOK);
		st.set(DataComponentTypes.WRITTEN_BOOK_CONTENT,
				new WrittenBookContentComponent(RawFilteredPair.of(title.length() > 32 ? title.substring(0, 32) : title),
						"Госархив", 0, pages, true));
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "archive");
		st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return st;
	}

	private static String redact(String s) {
		if (!U.chance(0.35)) return s;
		String[] w = s.split(" ");
		int i = U.R.nextInt(w.length);
		w[i] = "█".repeat(Math.max(3, Math.min(10, w[i].length())));
		return String.join(" ", w);
	}

	public static ItemStack personalFile(MinecraftServer s, UUID id, boolean full) {
		PizdecState st = PizdecState.get(s);
		String name = st.names.getOrDefault(id, "гражданин");
		List<String> l = new ArrayList<>();
		l.add("§lЛИЧНОЕ ДЕЛО§r\n" + name);
		PizdecState.Passport pp = st.passports.get(id);
		l.add(pp == null ? "Паспорт: не выдан." : "Паспорт: " + Docs.number(pp) + "\nФИО: " + pp.fio + "\nРожд.: " + pp.birth);
		Address.Home h = Address.home(s, id);
		l.add("Адрес: " + (h == null ? "без прописки (!)" : h.name()));
		int sus = SecretPolice.get(s, id);
		l.add("Благонадёжность: " + (100 - sus) + "%\nВнимание: " + SecretPolice.level(sus).replaceAll("§.", ""));
		l.add(st.underground.contains(id) && full ? "§4Связи с подпольем: УСТАНОВЛЕНЫ§0" : "Связи с подпольем: не выявлены");
		List<String> cr = st.crimes.getOrDefault(id, List.of());
		l.add("§lСУДИМОСТИ (" + cr.size() + ")§r");
		for (int i = Math.max(0, cr.size() - 8); i < cr.size(); i++) l.add("• " + cr.get(i));
		l.add("§lОПЕРАТИВНЫЕ ЗАПИСИ§r");
		List<String> dos = st.dossier.getOrDefault(id, List.of());
		for (int i = Math.max(0, dos.size() - 12); i < dos.size(); i++) l.add(full ? dos.get(i) : redact(dos.get(i)));
		if (!full) l.add("§8Часть сведений изъята в интересах государства.§0");
		return book("Дело: " + name, l, "ДСП");
	}

	private static ItemStack trialsBook(PizdecState st) {
		List<String> l = new ArrayList<>();
		l.add("§lИСТОРИЯ СУДЕБНЫХ ПРОЦЕССОВ§r\nКоролевский суд. Оправдательных приговоров: мало, но они были.");
		for (int i = st.trials.size() - 1; i >= 0; i--) l.add("• " + st.trials.get(i));
		if (st.trials.isEmpty()) l.add("Процессов пока не было. Суд скучает.");
		return book("История судебных процессов", l, "Открытая часть архива");
	}

	private static ItemStack lawsBook(PizdecState st) {
		List<String> l = new ArrayList<>();
		l.add("§lЛЕТОПИСЬ УКАЗОВ§r\nВсего издано: " + st.decrees + ". Каждый мудр.");
		for (int i = st.lawLog.size() - 1; i >= 0; i--) l.add(st.lawLog.get(i));
		return book("Летопись указов", l, "Открытая часть архива");
	}

	private static ItemStack secretsBook(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		List<String> docs = new ArrayList<>(List.of(
				"§lПротокол №17.§r Его Величество боится кур. Поручено переписать всех кур королевства.",
				"§lСмета на статую короля:§r 4 000 000 бабла. Фактически потрачено 400 000. Разница — «на ветер».",
				"§lСправка:§r пёс Налог — полковник тайной полиции. Его оклад выше, чем у пограничников.",
				"§lДокладная:§r подполье «Свободная Картошка» на треть состоит из наших сотрудников. Остальные — картошка.",
				"§lСводка КЦИОМ (не для печати):§r реальное доверие королю — " + Politics.pct(st.realRating)
						+ ". Официальное — " + Politics.pct(Politics.officialKing(st)) + ".",
				"§lВоенное ведомство:§r противник на фронте в 1200 блоках — переодетые стражники. Потерь нет. Побед тоже.",
				"§lМетодика:§r «Индекс любви к королю» = 98,7 + случайное число. Утверждено.",
				"§lХарактеристика:§r шут Валера — единственный, кто говорит королю правду. Поэтому он шут.",
				"§lПлан «Ценность»:§r часть товаров изымается со складов, чтобы граждане радовались завозу.",
				"§lКазна:§r на счету " + st.treasury + " бабла. Из них на губернии ушло [ЛИСТ ВЫРВАН].",
				"§lОб иностранцах:§r туристы настоящие. Гид Людмила — майор. Сыр — тоже майор.",
				"§lСекретный указ:§r при отсутствии указов издать указ об отсутствии указов.",
				"§lПро парады:§r «ликующих граждан на трибунах» — 20 000. Трибуны вмещают 40. Методика утверждена.",
				"§lО правительстве:§r правительство №" + st.govNo + " отличается от предыдущего табличками на дверях."));
		java.util.Collections.shuffle(docs, U.R);
		List<String> l = new ArrayList<>();
		l.add("§lСОВЕРШЕННО СЕКРЕТНО§r\nЭкз. единственный. Читать и съесть.");
		l.addAll(docs.subList(0, 6));
		return book("Совершенно секретно", l, "ОВ — особой важности");
	}

	private static ItemStack corruptionBook(PizdecState st) {
		List<String> l = new ArrayList<>();
		l.add("§lДАННЫЕ О КОРРУПЦИИ ЧИНОВНИКОВ§r\nПо материалам Счётной палаты. Не публиковать без указа.");
		long hidden = 0;
		for (int i = st.corruption.size() - 1; i >= 0; i--) {
			String[] a = st.corruption.get(i).split("\\|", -1);
			if (a.length < 6) continue;
			boolean exp = a[4].equals("1");
			if (!exp) hidden += Long.parseLong(a[2]);
			l.add((exp ? "§8[раскрыто]§0 " : "§4[скрыто]§0 ") + a[5] + " — " + a[1] + " " + a[0] + ": " + a[2] + " бабла на " + a[3]);
		}
		if (st.corruption.isEmpty()) l.add("Коррупции не выявлено. Пока.");
		l.add("§lИтого скрыто:§r " + hidden + " бабла. Скандалов было: " + st.scandals + ".");
		return book("Коррупция: материалы", l, "СЕКРЕТНО");
	}

	public static ItemStack folder() {
		return Gui.special(Items.BOOK, "secret_folder", "§4Папка «Совершенно секретно»",
				"§7Внутри — материалы о коррупции", "§7Слить в прессу: /slit (держа в руке)", "§4Хранение — измена");
	}

	// ================================================================== проникновение и хищение

	public static boolean special(PizdecState st, BlockPos pos) {
		if (!st.castleBuilt) return false;
		BlockPos door = new BlockPos(st.cx + 5, st.cy - 5, st.cz + 66);
		return pos.equals(door) || pos.equals(door.up()) || pos.equals(vault(st));
	}

	/** Клик по блоку в подвале. true — обработано. */
	public static boolean onUse(ServerPlayerEntity p, BlockPos pos) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!st.castleBuilt) return false;
		BlockPos door = new BlockPos(st.cx + 5, st.cy - 5, st.cz + 66);
		if (pos.equals(door) || pos.equals(door.up())) {
			boolean inside = p.getZ() > door.getZ() + 0.5;
			Vec3d to = inside ? readingRoom(st) : vaultInside(st);
			if (inside || cleared(p)) {
				p.teleport(p.getServer().getOverworld(), to.x, to.y, to.z, inside ? 180f : 0f, 0f);
				U.sound(p, SoundEvents.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
				return true;
			}
			if (p.isSneaking()) {
				if (U.chance(0.3)) {
					p.teleport(p.getServer().getOverworld(), to.x, to.y, to.z, 0f, 0f);
					U.msg(p, U.t("🔓 Замок поддался. Ты в хранилище. Тихо…", Formatting.DARK_GREEN));
					SecretPolice.add(p, 10, "взлом двери хранилища архива");
				} else {
					U.msg(p, U.t("🔒 Замок не поддаётся. Кажется, где-то зазвенел звонок.", Formatting.RED));
					SecretPolice.add(p, 8, "попытка взлома хранилища архива");
				}
			} else {
				U.msg(p, U.t("🔒 Хранилище — только по допуску. Оформить — у архивариуса. (Есть и другой способ — Shift+ПКМ. Не советуем.)",
						Formatting.GRAY));
			}
			return true;
		}
		if (pos.equals(vault(st))) {
			steal(p);
			return true;
		}
		return false;
	}

	private static void steal(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		int now = s.getTicks();
		if (now - lastCaughtCheck.getOrDefault(p.getUuid(), -9999) < 20 * 30) {
			U.msg(p, U.t("🗄 Сейф ещё не остыл после прошлой попытки.", Formatting.GRAY));
			return;
		}
		lastCaughtCheck.put(p.getUuid(), now);
		double chance = cleared(p) ? 0.7 : 0.45;
		if (U.chance(chance)) {
			p.getInventory().offerOrDrop(folder());
			U.title(p, U.t("🗄 ПАПКА У ТЕБЯ", Formatting.DARK_GREEN, Formatting.BOLD), U.t("Теперь — к прессе. /slit", Formatting.GRAY));
			SecretPolice.add(p, 15, "был у сейфа архива");
		} else {
			U.say(s, U.t("🚨 Попытка хищения государственной тайны в Секретном архиве! Злоумышленник задержан.",
					Formatting.DARK_RED));
			SecretPolice.add(p, 35, "попытка хищения секретных документов");
			Jail.arrest(p, "покушение на государственную тайну (хищение из архива)", 150);
		}
	}

	/** Слив папки в прессу: скандал на всю страну. */
	public static void leak(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		ItemStack h = p.getMainHandStack();
		if (!Gui.isSpecial(h, "secret_folder")) {
			U.msg(p, U.t("📰 Сливать нечего. Нужна папка «Совершенно секретно» в руке.", Formatting.GRAY));
			return;
		}
		h.decrement(1);
		int idx = -1;
		for (int i = st.corruption.size() - 1; i >= 0; i--) {
			if (st.corruption.get(i).contains("|0|")) {
				idx = i;
				break;
			}
		}
		if (idx < 0) {
			st.log(st.corruption, "Завхоз дворца Тащилов|завхоз|" + U.between(300, 3000) + "|позолоту трона второй раз|0|"
					+ Docs.today(), 60);
			idx = st.corruption.size() - 1;
		}
		boolean member = SecretPolice.member(p);
		int fee = member ? 500 : 250;
		Money.add(p, fee);
		U.msg(p, U.t("📰 Материалы переданы «иностранным СМИ». Гонорар: " + fee + " бабла.", Formatting.DARK_GREEN));
		Politics.scandal(s, idx, "утечка из Секретного архива");
		News.breaking(s, "Иностранные агенты распространяют фальшивку о «коррупции». Источник утечки будет найден.");
		SecretPolice.add(p, 40, "утечка секретных документов");
		if (U.chance(0.5)) Jail.arrest(p, "разглашение государственной тайны", 180);
	}

	// ================================================================== охрана хранилища

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt || s.getTicks() % 60 != 0) return;
		for (ServerPlayerEntity p : U.players(s)) {
			if (p.isCreative() || p.isSpectator() || Jail.busy(p)) continue;
			if (!inVaultRoom(st, p.getPos()) || cleared(p)) continue;
			if (U.chance(0.2)) {
				U.say(s, U.t("🚨 В хранилище Секретного архива обнаружен посторонний!", Formatting.DARK_RED));
				SecretPolice.add(p, 25, "незаконное проникновение в хранилище архива");
				Jail.arrest(p, "незаконное проникновение в Секретный архив", 120);
			} else {
				U.bar(p, U.t("🗄 Ты в хранилище без допуска. Каждая секунда — риск.", Formatting.RED));
			}
		}
	}

	// ================================================================== архивариус

	public static final class ArchiveGui extends Gui {
		private ArchiveGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "📁 Государственный архив", ArchiveGui::new);
		}

		private void redraw() {
			fill(Items.GRAY_STAINED_GLASS_PANE);
			boolean ok = cleared(player);
			set(4, icon(Items.NAME_TAG, ok ? "§aДопуск: есть" : "§7Допуск: нет", "§7Агенты ТП и помощник короля — с допуском",
					"§7Остальным — оформить (слот ниже)"));
			set(11, icon(Items.WRITTEN_BOOK, "§fМоё личное дело — 30", "§7Что о вас знает государство", "§8(частично засекречено)"));
			set(13, icon(Items.BOOK, "§fИстория судебных процессов — 10", "§7Все приговоры королевского суда"));
			set(15, icon(Items.BOOK, "§fЛетопись указов — 10", "§7Последние указы короля"));
			set(29, icon(Items.ENCHANTED_BOOK, (ok ? "§c" : "§8") + "Секретные документы правительства — 50",
					"§7Только по допуску"));
			set(31, icon(Items.GOLD_INGOT, (ok ? "§c" : "§8") + "Данные о коррупции чиновников — 50", "§7Только по допуску",
					"§7Материалы Счётной палаты"));
			set(33, icon(Items.PLAYER_HEAD, (ok ? "§c" : "§8") + "Дело другого гражданина", "§7Команда: /arhiv delo <игрок> (50)",
					"§7Только по допуску"));
			set(40, icon(Items.TRIPWIRE_HOOK, "§eОформить допуск — 300", "§7Звание Рыцарь+, подозрительность < 15",
					"§7Действует 10 минут"));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			MinecraftServer s = player.getServer();
			PizdecState st = PizdecState.get(s);
			boolean ok = cleared(player);
			switch (slot) {
				case 53 -> close();
				case 11 -> {
					if (Money.pay(player, 30, "выписку из личного дела")) {
						player.getInventory().offerOrDrop(personalFile(s, player.getUuid(), false));
						SecretPolice.add(player, 3, "интересовался своим делом");
					}
				}
				case 13 -> {
					if (Money.pay(player, 10, "копию судебного архива")) player.getInventory().offerOrDrop(trialsBook(st));
				}
				case 15 -> {
					if (Money.pay(player, 10, "летопись указов")) player.getInventory().offerOrDrop(lawsBook(st));
				}
				case 29, 31 -> {
					if (!ok) {
						U.msg(player, U.t("📁 Пыльцов: «Нет допуска — нет документов. Есть допуск — тоже не всегда.»",
								Formatting.GRAY));
						return;
					}
					if (Money.pay(player, 50, "секретную выписку")) {
						player.getInventory().offerOrDrop(slot == 29 ? secretsBook(s) : corruptionBook(st));
						SecretPolice.note(s, player.getUuid(), "получал секретные документы в архиве");
					}
				}
				case 40 -> {
					if (ok) {
						U.msg(player, U.t("📁 Допуск у вас уже есть.", Formatting.GRAY));
						return;
					}
					if (!Ranks.atLeast(player, 4) || SecretPolice.get(player) >= 15) {
						U.msg(player, U.t("📁 Отказано: нужно звание Рыцарь+ и безупречная благонадёжность.", Formatting.RED));
						return;
					}
					if (Money.pay(player, 300, "форму допуска")) {
						access.put(player.getUuid(), s.getOverworld().getTime() + 20L * 600);
						U.msg(player, U.t("📁 Допуск оформлен на 10 минут. Хранилище — за решёткой (ПКМ по двери).",
								Formatting.GREEN));
						SecretPolice.note(s, player.getUuid(), "получил допуск к архиву");
					}
				}
				default -> {}
			}
			redraw();
		}
	}

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("slit").executes(c -> {
			leak(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("arhiv").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			U.msg(p, U.t("📁 Государственный архив — в подвале «Управления по делам ничего особенного» (лестница в приёмной). "
					+ "Вход: " + U.coords(st.cx + 9, st.cy, st.cz + 55) + ". Личные дела, суды, указы; секреты — по допуску.",
					Formatting.GRAY));
			return 1;
		}).then(CommandManager.literal("delo").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
			if (!cleared(p)) {
				U.msg(p, U.t("📁 Чужие дела — только по допуску.", Formatting.RED));
				return 0;
			}
			if (!Money.pay(p, 50, "выписку из чужого дела")) return 0;
			p.getInventory().offerOrDrop(personalFile(p.getServer(), t.getUuid(), true));
			SecretPolice.note(p.getServer(), p.getUuid(), "запрашивал дело " + t.getName().getString());
			return 1;
		}))));
	}
}
