package com.pizdec.mod;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
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

/**
 * Документы королевства: паспорт гражданина и выписка из реестра недвижимости.
 * Данные хранятся в мире (PizdecState), бумажный документ — это книга, которую надо носить с собой.
 * Подлинность проверяется по номеру, владельцу и версии; копии (переписанные книги) недействительны.
 */
public final class Docs {
	public static final String DEPT = "Паспортным столом Королевского МФЦ «Мои документы»";
	public static final String DEPT_CODE = "777-001";
	public static final int PASSPORT_FEE = 50;
	public static final int REISSUE_FEE = 200;
	public static final int CHANGE_FEE = 100;
	public static final int REG_FEE = 10;
	public static final int MAX_AREA = 64 * 64;
	public static final int MIN_SIDE = 3;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

	private Docs() {}

	public static String today() {
		return LocalDate.now().format(DATE);
	}

	// ================================================================== паспорт: данные

	public static PizdecState.Passport passport(MinecraftServer s, UUID id) {
		return PizdecState.get(s).passports.get(id);
	}

	public static String number(PizdecState.Passport p) {
		return p.series + " №" + String.format("%06d", p.number);
	}

	public static String shortFio(PizdecState.Passport p) {
		String[] w = p.fio.split(" ");
		if (w.length < 3) return p.fio;
		return w[0] + " " + w[1].charAt(0) + "." + w[2].charAt(0) + ".";
	}

	/** Результат проверки документов игрока. */
	public enum Check {
		OK, NONE, NOT_ISSUED, FOREIGN, COPY, OUTDATED
	}

	public static Check check(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		PizdecState.Passport mine = st.passports.get(p.getUuid());
		PlayerInventory inv = p.getInventory();
		Check found = null;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack it = inv.getStack(i);
			if (!Gui.isSpecial(it, "passport")) continue;
			NbtCompound n = it.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
			WrittenBookContentComponent book = it.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
			boolean original = book != null && book.generation() == 0;
			if (!n.getString("owner").equals(p.getUuidAsString())) {
				found = Check.FOREIGN;
				continue;
			}
			if (!original) {
				if (found == null) found = Check.COPY;
				continue;
			}
			if (mine == null || n.getInt("ver") != mine.version || n.getInt("no") != mine.number) {
				if (found == null || found == Check.COPY) found = Check.OUTDATED;
				continue;
			}
			return Check.OK;
		}
		if (found != null) return found;
		return mine == null ? Check.NOT_ISSUED : Check.NONE;
	}

	public static boolean hasPassport(ServerPlayerEntity p) {
		return check(p) == Check.OK;
	}

	public static String checkText(Check c) {
		return switch (c) {
			case OK -> "паспорт в порядке";
			case NONE -> "паспорт не при себе";
			case NOT_ISSUED -> "паспорт не оформлен";
			case FOREIGN -> "предъявлен ЧУЖОЙ паспорт";
			case COPY -> "предъявлена копия, а не оригинал";
			case OUTDATED -> "паспорт недействителен (заменён или устарел)";
		};
	}

	/** Требование паспорта: false и объяснение, если документа нет. */
	public static boolean require(ServerPlayerEntity p, String forWhat) {
		if (p.isCreative()) return true;
		Check c = check(p);
		if (c == Check.OK) return true;
		U.msg(p, U.t("🪪 Для «" + forWhat + "» нужен паспорт: " + checkText(c) + ". Оформление — Королевский МФЦ, окно №1.",
				Formatting.RED));
		if (c == Check.FOREIGN) Jail.arrest(p, "использование чужого паспорта", 60);
		if (c == Check.COPY) Jail.arrest(p, "подделка документов (копия паспорта)", 45);
		return false;
	}

	// ================================================================== паспорт: книжка

	private static RawFilteredPair<Text> page(String s) {
		return RawFilteredPair.of(Text.literal(s));
	}

	private static ItemStack book(String title, String author, List<String> pages, int model, NbtCompound tag) {
		ItemStack st = new ItemStack(Items.WRITTEN_BOOK);
		List<RawFilteredPair<Text>> ps = new ArrayList<>();
		for (String s : pages) ps.add(page(s));
		st.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(title), author, 0, ps, true));
		st.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(model));
		st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return st;
	}

	public static ItemStack passportItem(MinecraftServer s, UUID owner, PizdecState.Passport p) {
		PizdecState st = PizdecState.get(s);
		String[] w = p.fio.split(" ");
		String fam = w.length > 0 ? w[0] : "", name = w.length > 1 ? w[1] : "", otch = w.length > 2 ? w[2] : "";
		List<String> pages = new ArrayList<>();
		pages.add("§4§l   КОРОЛЕВСТВО\n       ПИЗДЕЦ§r\n§l      ПАСПОРТ\n    гражданина§r\n\n§8Серия:§0 " + p.series + "\n§8Номер:§0 "
				+ String.format("%06d", p.number) + "\n§8Выдан:§0 МФЦ\n«Мои документы»\n§8Дата выдачи:§0\n" + p.issued
				+ "\n§8Код подразд.:§0 " + DEPT_CODE);
		pages.add("§8Фамилия:§0\n§l" + fam + "§r\n§8Имя:§0 §l" + name + "§r\n§8Отчество:§0\n§l" + otch + "§r\n§8Пол:§0 " + p.gender
				+ "\n§8Дата рождения:§0\n" + p.birth + "\n§8Место рождения:§0\nг. Спавн\n§8[ФОТО 3×4]§0 §o" + shortFio(p) + "§r");
		if (p.regName.isEmpty()) {
			pages.add("§l МЕСТО ЖИТЕЛЬСТВА§r\n\n§8Регистрация отсутствует.§0\n\nОформить: МФЦ, окно №2\n(нужна табличка-адрес и /adres)");
		} else {
			pages.add("§l МЕСТО ЖИТЕЛЬСТВА§r\n\n§8Зарегистрирован:§0\n«" + p.regName + "»\n§8Координаты:§0\n" + p.regX + " " + p.regY + " "
					+ p.regZ + "\n§8Дата:§0 " + p.regDate + "\n\n§8Отдел регистрации\n   [ М.П. ]§0");
		}
		PizdecState.Property prop = st.properties.get(owner);
		List<String> cr = st.crimes.getOrDefault(owner, List.of());
		pages.add("§l СВЕДЕНИЯ§r\n\n§8Звание:§0 " + Ranks.name(st.ranks.getOrDefault(owner, 0)) + "\n§8Недвижимость:§0 "
				+ (prop == null ? "нет" : "кад.№\n" + prop.cadastral) + "\n§8Судимости:§0 " + cr.size()
				+ "\n§8Ранее выданные паспорта:§0 " + p.version + "\n\n§8Документ является собственностью Короны. Утеря — штраф "
				+ REISSUE_FEE + " бабла.§0");
		pages.add("§l ОСОБЫЕ ОТМЕТКИ§r\n\n§8Предъявлять по первому требованию стражи. Копия документом не является. "
				+ "Использование чужого паспорта — уголовное преступление.§0\n\n§oПаспортист\nГалина Ивановна§r\n   [ М.П. ]");
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "passport");
		tag.putString("owner", owner.toString());
		tag.putInt("no", p.number);
		tag.putInt("ver", p.version);
		return book("Паспорт " + p.series + " " + String.format("%06d", p.number), shortFio(p), pages, 7702, tag);
	}

	/** Изымает все экземпляры паспорта владельца из инвентаря (при замене). */
	private static void removeOldPassports(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack it = inv.getStack(i);
			if (Gui.isSpecial(it, "passport")
					&& it.get(DataComponentTypes.CUSTOM_DATA).copyNbt().getString("owner").equals(p.getUuidAsString())) {
				inv.setStack(i, ItemStack.EMPTY);
			}
		}
	}

	// ================================================================== паспорт: оформление

	/** Черновик анкеты. */
	public static final class Draft {
		public String fio;
		public String birth;
		public String gender;
		public boolean photo;
	}

	public enum Field {
		FIO, BIRTH
	}

	private static final Map<UUID, Draft> drafts = new HashMap<>();
	private static final Map<UUID, Field> awaiting = new HashMap<>();
	/** Паспорта в изготовлении: UUID → тик готовности. */
	private static final Map<UUID, Integer> production = new HashMap<>();
	private static final Map<UUID, String> productionKind = new HashMap<>();

	public static Draft draft(ServerPlayerEntity p) {
		return drafts.computeIfAbsent(p.getUuid(), k -> new Draft());
	}

	public static boolean inProduction(ServerPlayerEntity p) {
		return production.containsKey(p.getUuid());
	}

	public static void ask(ServerPlayerEntity p, Field f) {
		awaiting.put(p.getUuid(), f);
		if (f == Field.FIO) {
			U.msg(p, U.t("🪪 Напишите в чат ФАМИЛИЮ ИМЯ ОТЧЕСТВО через пробел, с заглавных букв. Например: Иванов Иван Иванович. "
					+ "Сообщение увидит только паспортист. «отмена» — отменить.", Formatting.YELLOW));
		} else {
			U.msg(p, U.t("🪪 Напишите в чат дату рождения в формате ДД.ММ.ГГГГ, например 07.11.2000. Возраст — от 14 лет.",
					Formatting.YELLOW));
		}
	}

	/** Перехват ответа на анкету. true — сообщение съедено (в общий чат не идёт). */
	public static boolean onChat(ServerPlayerEntity p, String text) {
		Field f = awaiting.get(p.getUuid());
		if (f == null) return false;
		p.getServer().execute(() -> answer(p, f, text.trim()));
		return true;
	}

	private static final String WORD = "[A-ZА-ЯЁІЇЄҐ][a-zа-яёіїєґ'’]{1,24}(-[A-ZА-ЯЁІЇЄҐ][a-zа-яёіїєґ'’]{1,24})?";

	private static void answer(ServerPlayerEntity p, Field f, String text) {
		if (text.equalsIgnoreCase("отмена")) {
			awaiting.remove(p.getUuid());
			U.msg(p, U.t("🪪 Ввод отменён.", Formatting.GRAY));
			return;
		}
		Draft d = draft(p);
		if (f == Field.FIO) {
			String norm = text.replaceAll("\\s+", " ");
			String[] w = norm.split(" ");
			if (w.length != 3 || !w[0].matches(WORD) || !w[1].matches(WORD) || !w[2].matches(WORD)) {
				U.msg(p, U.t("🪪 Паспортист: «Неправильно. Нужно ровно три слова — Фамилия Имя Отчество, каждое с заглавной, "
						+ "только буквы (можно дефис). Попробуйте ещё раз.»", Formatting.RED));
				return;
			}
			boolean cyr = norm.matches(".*[А-Яа-яЁёІіЇїЄєҐґ].*"), lat = norm.matches(".*[A-Za-z].*");
			if (cyr && lat) {
				U.msg(p, U.t("🪪 Паспортист: «Не смешивайте алфавиты. Либо кириллица, либо латиница.»", Formatting.RED));
				return;
			}
			d.fio = norm;
			awaiting.remove(p.getUuid());
			U.msg(p, U.t("🪪 ФИО записано: " + norm, Formatting.GREEN));
		} else {
			LocalDate b;
			try {
				b = LocalDate.parse(text, DATE);
			} catch (DateTimeParseException e) {
				U.msg(p, U.t("🪪 Паспортист: «Такой даты не существует. Формат ДД.ММ.ГГГГ.»", Formatting.RED));
				return;
			}
			LocalDate now = LocalDate.now();
			if (b.isAfter(now.minusYears(14))) {
				U.msg(p, U.t("🪪 Паспортист: «Паспорт выдаётся с 14 лет. Приходите с родителями. Через несколько лет.»", Formatting.RED));
				return;
			}
			if (b.isBefore(now.minusYears(120))) {
				U.msg(p, U.t("🪪 Паспортист: «Столько не живут. Даже в нашем королевстве.»", Formatting.RED));
				return;
			}
			d.birth = b.format(DATE);
			awaiting.remove(p.getUuid());
			U.msg(p, U.t("🪪 Дата рождения записана: " + d.birth, Formatting.GREEN));
		}
		U.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
	}

	/** Фотобудка: игрок постоял в ней. */
	public static void photo(ServerPlayerEntity p) {
		Draft d = draft(p);
		if (d.photo) return;
		d.photo = true;
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1.6, p.getZ(), 1, 0, 0, 0, 0);
		U.sound(p, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1f, 1.5f);
		U.title(p, U.t("📸 ЩЁЛК", Formatting.WHITE, Formatting.BOLD), U.t("Фото 3×4 готово. Не моргали? Моргали. Сойдёт.", Formatting.GRAY));
	}

	/** Подача заявления на выдачу паспорта. */
	public static void submitNew(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (st.passports.containsKey(p.getUuid())) {
			U.msg(p, U.t("🪪 У вас уже есть паспорт. Для замены или восстановления — соответствующие кнопки.", Formatting.RED));
			return;
		}
		Draft d = draft(p);
		List<String> missing = new ArrayList<>();
		if (d.fio == null) missing.add("ФИО");
		if (d.birth == null) missing.add("дата рождения");
		if (d.gender == null) missing.add("пол");
		if (!d.photo) missing.add("фото (фотобудка у входа)");
		if (!missing.isEmpty()) {
			U.msg(p, U.t("🪪 Паспортист: «Анкета не заполнена: " + String.join(", ", missing) + ". Следующий!»", Formatting.RED));
			return;
		}
		if (inProduction(p)) return;
		if (!Money.pay(p, PASSPORT_FEE, "госпошлину за паспорт")) return;
		startProduction(p, "new");
	}

	/** Замена паспорта (смена ФИО / данных) или восстановление утерянного. */
	public static void submitReissue(ServerPlayerEntity p, boolean change) {
		PizdecState st = PizdecState.get(p.getServer());
		PizdecState.Passport cur = st.passports.get(p.getUuid());
		if (cur == null || inProduction(p)) return;
		Draft d = draft(p);
		if (change) {
			if (d.fio == null && d.birth == null && d.gender == null) {
				U.msg(p, U.t("🪪 Паспортист: «Что меняем-то? Заполните новые данные в анкете.»", Formatting.RED));
				return;
			}
			if (!d.photo) {
				U.msg(p, U.t("🪪 Паспортист: «Новое фото. Фотобудка у входа.»", Formatting.RED));
				return;
			}
			if (!Money.pay(p, CHANGE_FEE, "замену паспорта")) return;
			startProduction(p, "change");
		} else {
			if (check(p) == Check.OK) {
				U.msg(p, U.t("🪪 Паспортист: «Так вот же он, у вас в кармане. Не морочьте голову.»", Formatting.GRAY));
				return;
			}
			if (!Money.pay(p, REISSUE_FEE, "восстановление утерянного паспорта")) return;
			startProduction(p, "lost");
		}
	}

	private static void startProduction(ServerPlayerEntity p, String kind) {
		production.put(p.getUuid(), p.getServer().getTicks() + 20 * 10);
		productionKind.put(p.getUuid(), kind);
		U.msg(p, U.t("🪪 Заявление принято. Паспорт изготавливается, ожидайте ~10 секунд. Не уходите далеко.", Formatting.GOLD));
		U.sound(p, SoundEvents.BLOCK_ANVIL_USE, 0.5f, 1.6f);
	}

	private static void finishProduction(ServerPlayerEntity p, String kind) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		Draft d = draft(p);
		PizdecState.Passport ps = st.passports.get(p.getUuid());
		if (ps == null) {
			ps = new PizdecState.Passport();
			st.passportSeq++;
			ps.number = st.passportSeq;
			ps.series = "77 " + String.format("%02d", LocalDate.now().getYear() % 100);
			ps.version = 0;
		} else {
			// замена или восстановление: новый номер, старый недействителен
			st.passportSeq++;
			ps.number = st.passportSeq;
			ps.series = "77 " + String.format("%02d", LocalDate.now().getYear() % 100);
			ps.version++;
		}
		if (d.fio != null) ps.fio = d.fio;
		if (d.birth != null) ps.birth = d.birth;
		if (d.gender != null) ps.gender = d.gender;
		ps.issued = today();
		Address.Home home = null;
		if (Address.valid(s, p.getUuid())) home = Address.home(s, p.getUuid());
		if (home != null && ps.regName.isEmpty()) {
			PizdecState.Address a = st.addresses.get(p.getUuid());
			ps.regName = a.name;
			ps.regX = a.x;
			ps.regY = a.y;
			ps.regZ = a.z;
			ps.regDate = today();
		}
		st.passports.put(p.getUuid(), ps);
		st.names.put(p.getUuid(), p.getName().getString());
		st.markDirty();
		drafts.remove(p.getUuid());
		removeOldPassports(p);
		p.getInventory().offerOrDrop(passportItem(s, p.getUuid(), ps));
		U.title(p, U.t("🪪 ПАСПОРТ ГОТОВ", Formatting.GOLD, Formatting.BOLD), U.t(number(ps) + " — " + shortFio(ps), Formatting.YELLOW));
		U.msg(p, U.t("🪪 Выдан паспорт " + number(ps) + " на имя " + ps.fio + ". Носите его с собой: стража проверяет документы. "
				+ (ps.regName.isEmpty() ? "Регистрация по месту жительства — окно №2." : "Регистрация: «" + ps.regName + "».")
				+ (kind.equals("new") ? "" : " Прежний паспорт аннулирован."), Formatting.GOLD));
		U.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
	}

	/** Окно №2: регистрация (прописка) в паспорте по табличке-адресу. */
	public static void stampRegistration(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		PizdecState.Passport ps = st.passports.get(p.getUuid());
		if (ps == null || !require(p, "регистрации по месту жительства")) return;
		if (!Address.valid(s, p.getUuid())) {
			U.msg(p, U.t("🏠 Инспектор: «Адрес не найден в реестре замка. Поставьте табличку у дома и введите /adres рядом с ней.»",
					Formatting.RED));
			return;
		}
		PizdecState.Address a = st.addresses.get(p.getUuid());
		if (a.name.equals(ps.regName) && a.x == ps.regX && a.y == ps.regY && a.z == ps.regZ) {
			U.msg(p, U.t("🏠 Инспектор: «Вы уже зарегистрированы по этому адресу.»", Formatting.GRAY));
			return;
		}
		if (!Money.pay(p, REG_FEE, "регистрацию по месту жительства")) return;
		ps.regName = a.name;
		ps.regX = a.x;
		ps.regY = a.y;
		ps.regZ = a.z;
		ps.regDate = today();
		ps.version++;
		st.markDirty();
		removeOldPassports(p);
		p.getInventory().offerOrDrop(passportItem(s, p.getUuid(), ps));
		U.msg(p, U.t("🏠 В паспорт поставлен штамп о регистрации: «" + a.name + "». Старый экземпляр изъят, выдан обновлённый.",
				Formatting.GREEN));
		U.sound(p, SoundEvents.BLOCK_ANVIL_LAND, 0.4f, 1.8f);
	}

	/** Снять с регистрации (когда отнимают дом или табличку сломали). */
	public static void clearRegistration(MinecraftServer s, UUID id) {
		PizdecState.Passport ps = passport(s, id);
		if (ps == null || ps.regName.isEmpty()) return;
		ps.regName = "";
		ps.regDate = "";
		PizdecState.get(s).markDirty();
	}

	public static void addCrime(MinecraftServer s, UUID id, String crime) {
		PizdecState st = PizdecState.get(s);
		List<String> l = st.crimes.computeIfAbsent(id, k -> new ArrayList<>());
		l.add(today() + " — " + crime);
		while (l.size() > 10) l.remove(0);
		st.markDirty();
	}

	/** Справка о (не)судимости. */
	public static ItemStack criminalCertificate(MinecraftServer s, UUID id) {
		PizdecState st = PizdecState.get(s);
		PizdecState.Passport ps = st.passports.get(id);
		List<String> cr = st.crimes.getOrDefault(id, List.of());
		List<String> pages = new ArrayList<>();
		StringBuilder sb = new StringBuilder("§l     СПРАВКА§r\n о наличии (отсутствии) судимости\n\n§8Выдана:§0 " + ps.fio + "\n§8Паспорт:§0 "
				+ number(ps) + "\n§8Дата:§0 " + today() + "\n\n");
		if (cr.isEmpty()) sb.append("§2Судимостей НЕ имеет.§0");
		else sb.append("§4Имеет судимостей: ").append(cr.size()).append("§0\n(см. далее)");
		pages.add(sb.toString());
		StringBuilder cur = new StringBuilder();
		for (String c : cr) {
			if (cur.length() + c.length() > 200) {
				pages.add(cur.toString());
				cur = new StringBuilder();
			}
			cur.append("• ").append(c).append("\n");
		}
		if (cur.length() > 0) pages.add(cur.toString());
		pages.add("§8Нотариус\nИзольда Марковна§0\n\n   [ М.П. ]\n\n§8Справка действительна, пока король не передумает.");
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "certificate");
		tag.putString("owner", id.toString());
		ItemStack it = book("Справка о судимости", "МФЦ", pages, 7703, tag);
		return it;
	}

	// ================================================================== недвижимость

	public static final UUID CROWN = new UUID(0, 0);
	private static final Map<UUID, BlockPos[]> corners = new HashMap<>();

	/** Сделка купли-продажи: продавец → (покупатель, цена, срок). */
	public record Offer(UUID seller, UUID buyer, int price, int expires) {}

	private static final Map<UUID, Offer> offers = new HashMap<>();

	public record Owned(UUID owner, PizdecState.Property prop) {}

	/** Чей объект в этой точке (только обычный мир). */
	public static Owned propertyAt(MinecraftServer s, BlockPos pos) {
		PizdecState st = PizdecState.get(s);
		for (Map.Entry<UUID, PizdecState.Property> e : st.properties.entrySet()) {
			if (e.getValue().contains(pos.getX(), pos.getY(), pos.getZ())) return new Owned(e.getKey(), e.getValue());
		}
		for (PizdecState.Property p : st.crown) {
			if (p.contains(pos.getX(), pos.getY(), pos.getZ())) return new Owned(CROWN, p);
		}
		return null;
	}

	/** Может ли игрок ломать/строить/открывать здесь. null — можно, иначе причина запрета. */
	public static String denyReason(ServerPlayerEntity p, BlockPos pos) {
		if (p.isCreative() || p.getServerWorld() != p.getServer().getOverworld()) return null;
		Owned o = propertyAt(p.getServer(), pos);
		if (o == null) return null;
		if (o.owner.equals(CROWN)) return "конфисковано в пользу Короны (кад. №" + o.prop.cadastral + ")";
		boolean mine = o.owner.equals(p.getUuid()) || o.prop.residents.contains(p.getUuid());
		if (!mine) return "частная собственность (кад. №" + o.prop.cadastral + ")";
		if (o.prop.status == 1) return "на имущество наложен арест: " + o.prop.reason;
		return null;
	}

	public static void setCorner(ServerPlayerEntity p, int which) {
		BlockPos[] c = corners.computeIfAbsent(p.getUuid(), k -> new BlockPos[2]);
		c[which] = p.getBlockPos();
		U.msg(p, U.t("📐 Точка " + (which + 1) + " установлена: " + U.coords(c[which].getX(), c[which].getY(), c[which].getZ())
				+ (c[0] != null && c[1] != null ? ". Площадь: " + area(c) + " м². Регистрация — МФЦ, окно №3." : ""), Formatting.AQUA));
		showBounds(p);
	}

	private static int area(BlockPos[] c) {
		return (Math.abs(c[0].getX() - c[1].getX()) + 1) * (Math.abs(c[0].getZ() - c[1].getZ()) + 1);
	}

	public static BlockPos[] corners(ServerPlayerEntity p) {
		return corners.get(p.getUuid());
	}

	public static int fee(int area) {
		return Math.max(100, area * 2);
	}

	public static int value(int area) {
		return Math.max(150, area * 3);
	}

	/** Частицы по контуру участка (выбранного или зарегистрированного). */
	public static void showBounds(ServerPlayerEntity p) {
		int x0, z0, x1, z1;
		double y = p.getY() + 0.5;
		PizdecState.Property prop = PizdecState.get(p.getServer()).properties.get(p.getUuid());
		BlockPos[] c = corners.get(p.getUuid());
		if (c != null && c[0] != null && c[1] != null) {
			x0 = Math.min(c[0].getX(), c[1].getX());
			x1 = Math.max(c[0].getX(), c[1].getX());
			z0 = Math.min(c[0].getZ(), c[1].getZ());
			z1 = Math.max(c[0].getZ(), c[1].getZ());
		} else if (prop != null) {
			x0 = prop.x0;
			x1 = prop.x1;
			z0 = prop.z0;
			z1 = prop.z1;
		} else {
			return;
		}
		ServerWorld w = p.getServerWorld();
		for (int x = x0; x <= x1 + 1; x++) {
			w.spawnParticles(p, ParticleTypes.HAPPY_VILLAGER, true, x, y, z0, 1, 0, 0, 0, 0);
			w.spawnParticles(p, ParticleTypes.HAPPY_VILLAGER, true, x, y, z1 + 1, 1, 0, 0, 0, 0);
		}
		for (int z = z0; z <= z1 + 1; z++) {
			w.spawnParticles(p, ParticleTypes.HAPPY_VILLAGER, true, x0, y, z, 1, 0, 0, 0, 0);
			w.spawnParticles(p, ParticleTypes.HAPPY_VILLAGER, true, x1 + 1, y, z, 1, 0, 0, 0, 0);
		}
	}

	/** Окно №3: регистрация права собственности. */
	public static void registerProperty(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (!require(p, "регистрации недвижимости")) return;
		if (st.properties.containsKey(p.getUuid())) {
			U.msg(p, U.t("🏛 Регистратор: «У вас уже есть дом. Один гражданин — один дом. Остальное — коррупция.»", Formatting.RED));
			return;
		}
		BlockPos[] c = corners.get(p.getUuid());
		if (c == null || c[0] == null || c[1] == null) {
			U.msg(p, U.t("🏛 Регистратор: «Где межевание? Встаньте в один угол участка — /dom tochka1, в противоположный — "
					+ "/dom tochka2.»", Formatting.RED));
			return;
		}
		int x0 = Math.min(c[0].getX(), c[1].getX()), x1 = Math.max(c[0].getX(), c[1].getX());
		int z0 = Math.min(c[0].getZ(), c[1].getZ()), z1 = Math.max(c[0].getZ(), c[1].getZ());
		int y0 = Math.min(c[0].getY(), c[1].getY()) - 10, y1 = Math.max(c[0].getY(), c[1].getY()) + 30;
		int ar = (x1 - x0 + 1) * (z1 - z0 + 1);
		if (x1 - x0 + 1 < MIN_SIDE || z1 - z0 + 1 < MIN_SIDE) {
			U.msg(p, U.t("🏛 Регистратор: «Участок меньше 3×3 — это не дом, это будка.»", Formatting.RED));
			return;
		}
		if (ar > MAX_AREA) {
			U.msg(p, U.t("🏛 Регистратор: «Больше 64×64 только у короля. Уменьшите участок.»", Formatting.RED));
			return;
		}
		if (!Address.valid(s, p.getUuid())) {
			U.msg(p, U.t("🏛 Регистратор: «Нет адреса. Поставьте табличку на участке и введите /adres.»", Formatting.RED));
			return;
		}
		PizdecState.Address a = st.addresses.get(p.getUuid());
		if (a.x < x0 || a.x > x1 || a.z < z0 || a.z > z1) {
			U.msg(p, U.t("🏛 Регистратор: «Табличка-адрес должна стоять внутри участка.»", Formatting.RED));
			return;
		}
		// пересечения
		for (int x = x0; x <= x1; x += Math.max(1, (x1 - x0) / 8)) {
			for (int z = z0; z <= z1; z += Math.max(1, (z1 - z0) / 8)) {
				BlockPos b = new BlockPos(x, a.y, z);
				if (Castle.inside(st, b) || Prison.inside(st, b) || Court.inside(st, b) || Mfc.inside(st, b)) {
					U.msg(p, U.t("🏛 Регистратор: «Это королевская земля. Вы с ума сошли?»", Formatting.RED));
					return;
				}
			}
		}
		PizdecState.Property np = new PizdecState.Property();
		np.x0 = x0;
		np.x1 = x1;
		np.z0 = z0;
		np.z1 = z1;
		np.y0 = y0;
		np.y1 = y1;
		if (overlaps(st, np)) {
			U.msg(p, U.t("🏛 Регистратор: «Участок пересекается с чужой собственностью. Межуйтесь заново.»", Formatting.RED));
			return;
		}
		int f = fee(ar);
		if (!Money.pay(p, f, "госпошлину за регистрацию права собственности")) return;
		st.cadastralSeq++;
		np.cadastral = String.format("77:01:%07d:%d", st.cadastralSeq, U.between(10, 99));
		np.addr = a.name;
		np.regDate = today();
		np.value = value(ar);
		np.decree = st.decrees;
		st.properties.put(p.getUuid(), np);
		st.markDirty();
		corners.remove(p.getUuid());
		p.getInventory().offerOrDrop(deedItem(s, p.getUuid(), np));
		U.title(p, U.t("🏛 ПРАВО СОБСТВЕННОСТИ", Formatting.GOLD, Formatting.BOLD), U.t("Кад. № " + np.cadastral, Formatting.YELLOW));
		U.msg(p, U.t("🏛 Зарегистрировано право собственности на «" + a.name + "» (" + ar + " м², кад. №" + np.cadastral
				+ "). Теперь никто, кроме вас и ваших жильцов, не может тут ломать, строить и открывать сундуки. "
				+ "Помните: за тяжкие преступления суд может конфисковать дом.", Formatting.GOLD));
	}

	private static boolean overlaps(PizdecState st, PizdecState.Property n) {
		List<PizdecState.Property> all = new ArrayList<>(st.properties.values());
		all.addAll(st.crown);
		for (PizdecState.Property o : all) {
			if (o == n) continue;
			if (n.x0 <= o.x1 && n.x1 >= o.x0 && n.z0 <= o.z1 && n.z1 >= o.z0) return true;
		}
		return false;
	}

	public static ItemStack deedItem(MinecraftServer s, UUID owner, PizdecState.Property pr) {
		PizdecState st = PizdecState.get(s);
		PizdecState.Passport ps = st.passports.get(owner);
		String who = ps == null ? st.names.getOrDefault(owner, "?") : ps.fio;
		StringBuilder res = new StringBuilder();
		for (UUID r : pr.residents) res.append("\n• ").append(st.names.getOrDefault(r, "?"));
		List<String> pages = new ArrayList<>();
		pages.add("§l     ВЫПИСКА§r\nиз Единого королевского реестра недвижимости\n\n§8Кадастровый номер:§0\n§l" + pr.cadastral
				+ "§r\n§8Вид объекта:§0 жилой дом с участком\n§8Адрес:§0 «" + pr.addr + "»\n§8Площадь:§0 " + pr.area()
				+ " м²\n§8Дата регистрации:§0 " + pr.regDate);
		pages.add("§l ПРАВООБЛАДАТЕЛЬ§r\n\n§8ФИО:§0\n" + who + "\n§8Паспорт:§0\n" + (ps == null ? "—" : number(ps))
				+ "\n\n§8Вид права:§0 собственность\n§8Основание:§0 Указ Короля №" + pr.decree);
		pages.add("§l ГРАНИЦЫ УЧАСТКА§r\n\n§8X:§0 " + pr.x0 + " … " + pr.x1 + "\n§8Z:§0 " + pr.z0 + " … " + pr.z1 + "\n§8Высота:§0 "
				+ pr.y0 + " … " + pr.y1 + "\n\n§8Кадастровая стоимость:§0\n" + pr.value + " бабла");
		pages.add("§l ОБРЕМЕНЕНИЯ§r\n\n§8Арест:§0 " + (pr.status == 1 ? "§4ДА§0 — " + pr.reason : "нет")
				+ "\n§8Ипотека:§0 нет (король не даёт)\n§8Зарегистрированные жильцы:§0" + (res.length() == 0 ? " нет" : res.toString())
				+ "\n\n§oРегистратор\nЭдуард Кадастрович§r\n   [ М.П. ]");
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "deed");
		tag.putString("owner", owner.toString());
		tag.putString("cad", pr.cadastral);
		tag.putInt("ver", pr.version);
		return book("Выписка ЕГРН " + pr.cadastral, "Росреестр", pages, 7703, tag);
	}

	private static void reissueDeed(MinecraftServer s, UUID owner, PizdecState.Property pr, String note) {
		pr.version++;
		PizdecState.get(s).markDirty();
		ServerPlayerEntity p = s.getPlayerManager().getPlayer(owner);
		if (p == null) return;
		removeDeeds(p);
		p.getInventory().offerOrDrop(deedItem(s, owner, pr));
		if (note != null) U.msg(p, U.t("🏛 " + note + " Выдана обновлённая выписка, старая недействительна.", Formatting.GOLD));
	}

	private static void removeDeeds(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) if (Gui.isSpecial(inv.getStack(i), "deed")) inv.setStack(i, ItemStack.EMPTY);
	}

	public static void duplicateDeed(ServerPlayerEntity p) {
		PizdecState.Property pr = PizdecState.get(p.getServer()).properties.get(p.getUuid());
		if (pr == null || !require(p, "выдачи выписки")) return;
		if (!Money.pay(p, 20, "дубликат выписки")) return;
		reissueDeed(p.getServer(), p.getUuid(), pr, "Дубликат готов.");
	}

	public static void unregister(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		PizdecState.Property pr = st.properties.get(p.getUuid());
		if (pr == null || !require(p, "снятия объекта с учёта")) return;
		if (pr.status == 1) {
			U.msg(p, U.t("🏛 Регистратор: «Объект под арестом. Сначала погасите долги.»", Formatting.RED));
			return;
		}
		st.properties.remove(p.getUuid());
		st.markDirty();
		removeDeeds(p);
		U.msg(p, U.t("🏛 Объект " + pr.cadastral + " снят с учёта. Защиты больше нет.", Formatting.YELLOW));
	}

	public static void resident(ServerPlayerEntity p, ServerPlayerEntity who, boolean add) {
		PizdecState st = PizdecState.get(p.getServer());
		PizdecState.Property pr = st.properties.get(p.getUuid());
		if (pr == null) {
			U.msg(p, U.t("🏛 У вас нет зарегистрированного дома.", Formatting.RED));
			return;
		}
		if (!require(p, "регистрации жильца")) return;
		if (add) {
			if (who == p || pr.residents.contains(who.getUuid())) return;
			if (pr.residents.size() >= 5) {
				U.msg(p, U.t("🏛 Больше 5 жильцов — это уже общежитие. Нужна другая лицензия.", Formatting.RED));
				return;
			}
			pr.residents.add(who.getUuid());
			st.names.put(who.getUuid(), who.getName().getString());
			U.msg(who, U.t("🏛 " + p.getName().getString() + " вписал вас жильцом в дом «" + pr.addr + "».", Formatting.GREEN));
		} else {
			pr.residents.remove(who.getUuid());
			U.msg(who, U.t("🏛 Вас выписали из дома «" + pr.addr + "».", Formatting.YELLOW));
		}
		reissueDeed(p.getServer(), p.getUuid(), pr, add ? "Жилец добавлен." : "Жилец выписан.");
	}

	// ---------------- сделки

	public static void offer(ServerPlayerEntity seller, ServerPlayerEntity buyer, int price) {
		PizdecState st = PizdecState.get(seller.getServer());
		PizdecState.Property pr = st.properties.get(seller.getUuid());
		if (pr == null || !require(seller, "продажи недвижимости")) return;
		if (pr.status == 1) {
			U.msg(seller, U.t("🏛 Нотариус: «Объект под арестом, сделки запрещены.»", Formatting.RED));
			return;
		}
		if (buyer == seller) return;
		offers.put(seller.getUuid(), new Offer(seller.getUuid(), buyer.getUuid(), price, seller.getServer().getTicks() + 20 * 120));
		U.msg(seller, U.t("📝 Оферта направлена: дом «" + pr.addr + "» за " + price + " бабла. Покупатель должен принять в течение "
				+ "2 минут (/dom kupit " + seller.getName().getString() + ").", Formatting.AQUA));
		U.msg(buyer, U.t("📝 " + seller.getName().getString() + " предлагает купить дом «" + pr.addr + "» (" + pr.area() + " м², кад. №"
				+ pr.cadastral + ") за " + price + " бабла + 5% госпошлина. Принять: /dom kupit " + seller.getName().getString(),
				Formatting.AQUA));
	}

	public static void accept(ServerPlayerEntity buyer, ServerPlayerEntity seller) {
		MinecraftServer s = buyer.getServer();
		PizdecState st = PizdecState.get(s);
		Offer o = offers.get(seller.getUuid());
		if (o == null || !o.buyer().equals(buyer.getUuid()) || o.expires() < s.getTicks()) {
			U.msg(buyer, U.t("📝 Действующей оферты нет или она истекла.", Formatting.RED));
			return;
		}
		PizdecState.Property pr = st.properties.get(seller.getUuid());
		if (pr == null) return;
		if (!require(buyer, "покупки недвижимости") || !Docs.hasPassport(seller)) {
			U.msg(buyer, U.t("📝 Нотариус: «У продавца нет паспорта при себе. Сделка невозможна.»", Formatting.RED));
			return;
		}
		if (st.properties.containsKey(buyer.getUuid())) {
			U.msg(buyer, U.t("📝 Нотариус: «У вас уже есть дом. Продайте старый.»", Formatting.RED));
			return;
		}
		int duty = Math.max(5, o.price() / 20);
		if (!Money.pay(buyer, o.price() + duty, "покупку дома + госпошлина " + duty)) return;
		Money.give(seller, o.price(), "продажа дома");
		offers.remove(seller.getUuid());
		st.properties.remove(seller.getUuid());
		pr.residents.clear();
		pr.status = 0;
		pr.reason = "";
		pr.decree = st.decrees;
		pr.regDate = today();
		st.properties.put(buyer.getUuid(), pr);
		evictAddress(s, seller.getUuid(), pr);
		removeDeeds(seller);
		reissueDeed(s, buyer.getUuid(), pr, "Сделка зарегистрирована. Вы — новый собственник «" + pr.addr + "».");
		U.msg(seller, U.t("📝 Дом продан. Деньги получены. Вы выписаны.", Formatting.GREEN));
	}

	/** Если табличка-адрес владельца стоит внутри объекта — снимаем с регистрации. */
	private static void evictAddress(MinecraftServer s, UUID id, PizdecState.Property pr) {
		PizdecState st = PizdecState.get(s);
		PizdecState.Address a = st.addresses.get(id);
		if (a != null && pr.contains(a.x, a.y, a.z)) {
			st.addresses.remove(id);
			clearRegistration(s, id);
			st.markDirty();
		}
	}

	// ---------------- арест и конфискация

	/** Арест имущества за долги (снимается сам, когда баланс ≥ 0). */
	public static void lien(ServerPlayerEntity p, String reason) {
		PizdecState.Property pr = PizdecState.get(p.getServer()).properties.get(p.getUuid());
		if (pr == null || pr.status == 1) return;
		pr.status = 1;
		pr.reason = reason;
		reissueDeed(p.getServer(), p.getUuid(), pr, null);
		U.title(p, U.t("🏛 АРЕСТ ИМУЩЕСТВА", Formatting.DARK_RED, Formatting.BOLD), U.t(reason, Formatting.GRAY));
		U.msg(p, U.t("🏛 На дом «" + pr.addr + "» наложен арест: " + reason + ". Пока долг не погашен, ломать, строить и "
				+ "открывать сундуки в доме нельзя даже вам.", Formatting.RED));
	}

	/** Конфискация по приговору суда. */
	public static boolean confiscate(MinecraftServer s, UUID owner, String reason) {
		PizdecState st = PizdecState.get(s);
		PizdecState.Property pr = st.properties.remove(owner);
		if (pr == null) return false;
		pr.status = 0;
		pr.reason = reason;
		pr.residents.clear();
		pr.version++;
		st.crown.add(pr);
		st.formerOwners.put(pr.cadastral, owner);
		evictAddress(s, owner, pr);
		st.markDirty();
		ServerPlayerEntity p = s.getPlayerManager().getPlayer(owner);
		if (p != null) {
			removeDeeds(p);
			U.title(p, U.t("🏛 ДОМ КОНФИСКОВАН", Formatting.DARK_RED, Formatting.BOLD), U.t("в пользу Короны", Formatting.GRAY));
			U.msg(p, U.t("🏛 По приговору суда дом «" + pr.addr + "» (кад. №" + pr.cadastral + ") конфискован в пользу Короны: "
					+ reason + ". Вы сняты с регистрации. Выкупить можно в МФЦ, окно №3, за " + (pr.value * 3 / 2) + " бабла.",
					Formatting.RED));
		}
		U.say(s, U.t("🏛 Конфисковано в пользу Короны: «" + pr.addr + "». Объект выставлен на торги (МФЦ, окно №3).", Formatting.GOLD));
		return true;
	}

	/** Покупка с торгов или выкуп бывшим владельцем. */
	public static void buyFromCrown(ServerPlayerEntity p, int idx) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (idx < 0 || idx >= st.crown.size()) return;
		if (!require(p, "покупки с торгов")) return;
		if (st.properties.containsKey(p.getUuid())) {
			U.msg(p, U.t("🏛 У вас уже есть дом.", Formatting.RED));
			return;
		}
		PizdecState.Property pr = st.crown.get(idx);
		boolean former = p.getUuid().equals(st.formerOwners.get(pr.cadastral));
		int price = former ? pr.value * 3 / 2 : pr.value * 2;
		if (!Money.pay(p, price, former ? "выкуп конфискованного дома" : "покупку дома с торгов")) return;
		st.crown.remove(idx);
		st.formerOwners.remove(pr.cadastral);
		pr.status = 0;
		pr.reason = "";
		pr.regDate = today();
		pr.decree = st.decrees;
		st.properties.put(p.getUuid(), pr);
		st.markDirty();
		reissueDeed(s, p.getUuid(), pr, (former ? "Дом выкуплен у Короны." : "Дом куплен с торгов.")
				+ " Не забудьте поставить табличку и прописаться (/adres, окно №2).");
	}

	public static void tick(MinecraftServer s) {
		int t = s.getTicks();
		// изготовление паспортов
		if (!production.isEmpty()) {
			for (UUID id : new ArrayList<>(production.keySet())) {
				if (production.get(id) > t) continue;
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
				if (p == null) continue; // дождётся игрока
				String kind = productionKind.remove(id);
				production.remove(id);
				finishProduction(p, kind == null ? "new" : kind);
			}
		}
		// снятие ареста за долги
		if (t % 200 == 0) {
			PizdecState st = PizdecState.get(s);
			for (Map.Entry<UUID, PizdecState.Property> e : st.properties.entrySet()) {
				if (e.getValue().status != 1) continue;
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(e.getKey());
				if (p != null && Money.get(p) >= 0) {
					e.getValue().status = 0;
					e.getValue().reason = "";
					reissueDeed(s, e.getKey(), e.getValue(), "Долг погашен — арест с дома снят.");
				}
			}
			offers.values().removeIf(o -> o.expires() < t);
		}
	}

	// ================================================================== команды

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("pasport").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState.Passport ps = passport(p.getServer(), p.getUuid());
			if (ps == null) {
				U.msg(p, U.t("🪪 Паспорт не оформлен. Королевский МФЦ «Мои документы», окно №1 (/mfc — где это).", Formatting.RED));
				return 1;
			}
			U.msg(p, U.t("🪪 Паспорт " + number(ps) + ", " + ps.fio + ", " + ps.gender + ", д.р. " + ps.birth + ", выдан " + ps.issued
					+ ". Регистрация: " + (ps.regName.isEmpty() ? "нет" : "«" + ps.regName + "»") + ". Статус: " + checkText(check(p)),
					Formatting.GOLD));
			return 1;
		}));
		d.register(CommandManager.literal("proverka")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
					Jobs.Job j = Jobs.jobOf(p);
					if (j == null || !(j.id().equals("guard") || j.id().equals("warden"))) {
						U.msg(p, U.t("🛡 Проверять документы может только стража (стражник или надзиратель).", Formatting.RED));
						return 0;
					}
					if (t == p || p.squaredDistanceTo(t) > 8 * 8) {
						U.msg(p, U.t("🛡 Подойдите к гражданину ближе (8 блоков).", Formatting.GRAY));
						return 0;
					}
					inspect(p, t);
					return 1;
				})));
		d.register(CommandManager.literal("dom")
				.executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					PizdecState.Property pr = PizdecState.get(p.getServer()).properties.get(p.getUuid());
					if (pr == null) {
						U.msg(p, U.t("🏛 Дом не зарегистрирован. Как оформить: поставь табличку на участке → /adres → /dom tochka1 и "
								+ "/dom tochka2 по углам → МФЦ, окно №3 (нужен паспорт).", Formatting.AQUA));
					} else {
						U.msg(p, U.t("🏛 «" + pr.addr + "», кад. №" + pr.cadastral + ", " + pr.area() + " м², стоимость " + pr.value
								+ ", жильцов: " + pr.residents.size() + (pr.status == 1 ? ", АРЕСТ: " + pr.reason : "")
								+ ". Команды: tochka1/2, granicy, zhilec, vypisat, prodat, kupit.", Formatting.AQUA));
						showBounds(p);
					}
					return 1;
				})
				.then(CommandManager.literal("tochka1").executes(c -> {
					setCorner(c.getSource().getPlayerOrThrow(), 0);
					return 1;
				}))
				.then(CommandManager.literal("tochka2").executes(c -> {
					setCorner(c.getSource().getPlayerOrThrow(), 1);
					return 1;
				}))
				.then(CommandManager.literal("granicy").executes(c -> {
					showBounds(c.getSource().getPlayerOrThrow());
					return 1;
				}))
				.then(CommandManager.literal("zhilec").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					resident(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"), true);
					return 1;
				})))
				.then(CommandManager.literal("vypisat").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					resident(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"), false);
					return 1;
				})))
				.then(CommandManager.literal("prodat").then(CommandManager.argument("player", EntityArgumentType.player())
						.then(CommandManager.argument("price", IntegerArgumentType.integer(1)).executes(c -> {
							offer(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"),
									IntegerArgumentType.getInteger(c, "price"));
							return 1;
						}))))
				.then(CommandManager.literal("kupit").then(CommandManager.argument("seller", EntityArgumentType.player()).executes(c -> {
					accept(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "seller"));
					return 1;
				}))));
	}

	/** Проверка документов стражей. */
	public static void inspect(ServerPlayerEntity guard, ServerPlayerEntity t) {
		Check c = check(t);
		U.msg(guard, U.t("🛡 Проверка документов " + t.getName().getString() + ": " + checkText(c), Formatting.YELLOW));
		U.msg(t, U.t("🛡 Стража проверяет ваши документы… " + checkText(c), Formatting.YELLOW));
		switch (c) {
			case OK -> {
				PizdecState.Passport ps = passport(t.getServer(), t.getUuid());
				U.msg(guard, U.t("   " + ps.fio + ", " + number(ps) + ", регистрация: " + (ps.regName.isEmpty() ? "НЕТ" : ps.regName),
						Formatting.GRAY));
			}
			case FOREIGN -> Jail.arrest(t, "использование чужого паспорта", 60);
			case COPY -> Jail.arrest(t, "подделка документов", 45);
			case NOT_ISSUED, NONE, OUTDATED -> Jail.arrest(t, "отсутствие действительных документов", 30);
		}
		if (c != Check.OK) Money.give(guard, 10, "бдительность");
	}
}
