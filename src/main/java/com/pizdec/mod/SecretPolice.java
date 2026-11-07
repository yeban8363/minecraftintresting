package com.pizdec.mod;

import static com.pizdec.mod.B.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * Тайная полиция королевства («Управление по делам ничего особенного»).
 * У каждого гражданина есть скрытая «подозрительность» 0..100:
 * 20+ — слежка (за тобой ходит неприметный гражданин), 35+ — проверки документов,
 * 50+ — ордер на обыск дома, 70+ — допрос. Плюс подполье «Свободная Картошка» и облавы.
 */
public final class SecretPolice {
	public static final String MAJOR_TAG = "pizdec_major";
	public static final String INTERROGATOR_TAG = "pizdec_interrogator";
	public static final String AGENT_TAG = "pizdec_agent";
	public static final String UNDERGROUND = "«Свободная Картошка»";

	public static final int WATCH = 20, CHECKS = 35, WARRANT = 50, INTERROGATE = 70;

	private static final String[] AGENT_NAMES = {"Гражданин в сером плаще", "Случайный прохожий", "Человек с газетой",
			"Неприметный гражданин", "Турист (местный)", "Продавец семечек", "Дворник без метлы", "Мужчина в шляпе"};
	private static final String[] HINTS = {"👁 Тебе кажется, что за тобой кто-то идёт…",
			"👁 Человек с газетой опять оказался рядом. Совпадение.", "👁 Где-то щёлкнул затвор фотоаппарата.",
			"👁 Прохожий что-то записал в блокнот, глядя на тебя.", "👁 В кустах кто-то кашлянул.",
			"👁 Тебе в спину очень внимательно смотрят.", "👁 Кто-то идёт ровно с твоей скоростью."};
	private static final String[] SUSPICIOUS = {"свобод", "революц", "долой", "фальсиф", "подделал", "куплен", "тиран",
			"диктат", "оппозиц", "митинг", "протест", "эмигр", "сбежать", "свалить", "уехать", "подполь", "листовк",
			"картошк", "надоел", "бунт", "забастов", "репресс", "цензур"};
	private static final String[] LOYAL = {"слава королю", "люблю короля", "да здравствует", "король лучший",
			"спасибо королю", "король молодец"};

	/** Тайные позиции относительно замка. */
	private static final double[] SEAT = {5.5, 75.5};

	private static final Map<UUID, VillagerEntity> agents = new HashMap<>();
	private static boolean spawningAgent;
	private static final Map<UUID, Integer> lastCheck = new HashMap<>();
	private static final Map<UUID, Integer> lastWarrant = new HashMap<>();
	private static final Map<UUID, Integer> lastHint = new HashMap<>();
	private static final Map<UUID, Integer> lastChat = new HashMap<>();
	private static final Map<UUID, Integer> cooldown = new HashMap<>();
	private static final Map<UUID, Integer> lastCertificate = new HashMap<>();
	private static final Map<UUID, Interrogation> rooms = new HashMap<>();
	private static int raidTimer;

	private SecretPolice() {}

	// ================================================================== подозрительность

	public static int get(MinecraftServer s, UUID id) {
		return PizdecState.get(s).suspicion.getOrDefault(id, 0);
	}

	public static int get(ServerPlayerEntity p) {
		return get(p.getServer(), p.getUuid());
	}

	public static void setLevel(MinecraftServer s, UUID id, int v) {
		PizdecState st = PizdecState.get(s);
		st.suspicion.put(id, Math.max(0, Math.min(100, v)));
		st.markDirty();
	}

	/** Повысить (или понизить) подозрительность и записать в досье. */
	public static void add(ServerPlayerEntity p, int n, String why) {
		if (p == null || p.isCreative() || n == 0) return;
		MinecraftServer s = p.getServer();
		if (n > 0 && Laws.is("vigilance_week")) n *= 2;
		int before = get(s, p.getUuid());
		setLevel(s, p.getUuid(), before + n);
		note(s, p.getUuid(), why + " (" + (n > 0 ? "+" : "") + n + ")");
		int now = get(s, p.getUuid());
		if (before < WATCH && now >= WATCH) {
			U.msg(p, U.t("👁 Ты почувствовал на себе чей-то взгляд.", Formatting.DARK_GRAY, Formatting.ITALIC));
		}
	}

	public static void note(MinecraftServer s, UUID id, String text) {
		PizdecState st = PizdecState.get(s);
		List<String> d = st.dossier.computeIfAbsent(id, k -> new ArrayList<>());
		d.add("[" + java.time.LocalTime.now().withNano(0) + "] " + text);
		while (d.size() > 25) d.remove(0);
		st.markDirty();
	}

	public static String level(int v) {
		if (v >= INTERROGATE) return "§4особый интерес (допрос)";
		if (v >= WARRANT) return "§cордер на обыск";
		if (v >= CHECKS) return "§6проверки документов";
		if (v >= WATCH) return "§eнаблюдение";
		return "§aвне подозрений";
	}

	public static boolean member(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).underground.contains(p.getUuid());
	}

	public static boolean informant(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).informants.contains(p.getUuid());
	}

	// ================================================================== здание

	/** Штаб в западной части парламентского квартала: x 2..8, z 52..78. */
	static void buildHq(int ox, int oy, int oz) {
		int x0 = ox + 2, x1 = ox + 8, z0 = oz + 52, z1 = oz + 78, h = 6;
		clear(ox + 6, oy, oz + 60, ox + 6, oy + 2, oz + 60);
		clear(ox + 6, oy, oz + 70, ox + 6, oy + 2, oz + 70);
		clear(x0, oy, z0, x1, oy + h + 1, z1);
		fill(x0, oy - 1, z0, x1, oy - 1, z1, Blocks.GRAY_CONCRETE);
		walls(x0, oy, z0, x1, oy + h, z1, Blocks.GRAY_CONCRETE);
		fill(x0, oy + h + 1, z0, x1, oy + h + 1, z1, Blocks.SMOOTH_STONE_SLAB);
		for (int z = z0 + 2; z <= z1 - 2; z += 3) set(x1, oy + 2, z, Blocks.TINTED_GLASS); // окна, в которые не видно
		// вход с востока
		fill(x1, oy, oz + 55, x1, oy + 1, oz + 55, Blocks.AIR);
		B.door(x1, oy, oz + 55, Direction.EAST, Blocks.DARK_OAK_DOOR);
		Restaurant.sign(x1 + 1, oy + 2, oz + 55, Direction.EAST, "Управление", "по делам", "ничего особенного", "§8(вход свободный)");
		// --- приёмная (z 53..58)
		fill(x0 + 1, oy - 1, z0 + 1, x1 - 1, oy - 1, oz + 58, Blocks.DARK_OAK_PLANKS);
		// глухой переулок к входу: от прохода из замка вдоль стены парламента
		clear(ox + 9, oy, oz + 47, ox + 14, oy + 4, oz + 49);
		fill(ox + 9, oy - 1, oz + 47, ox + 14, oy - 1, oz + 48, Blocks.POLISHED_ANDESITE);
		clear(ox + 9, oy, oz + 49, ox + 9, oy + 4, oz + 57);
		fill(ox + 9, oy - 1, oz + 49, ox + 9, oy - 1, oz + 57, Blocks.GRAVEL);
		for (int z = 54; z <= 57; z++) {
			set(ox + 5, oy, oz + z, Blocks.DARK_OAK_PLANKS);
		}
		set(ox + 5, oy + 1, oz + 54, Blocks.LANTERN);
		set(ox + 5, oy + 1, oz + 57, Blocks.LECTERN);
		set(ox + 3, oy, oz + 53, Blocks.BARREL); // архив
		set(ox + 3, oy + 1, oz + 53, Blocks.BARREL);
		set(ox + 3, oy, oz + 58, Blocks.BARREL);
		B.chandelier(ox + 5, oy + h, oz + 56, 1);
		// перегородка и проход в комнату наблюдения
		fill(x0, oy, oz + 59, x1, oy + h, oz + 59, Blocks.GRAY_CONCRETE);
		fill(ox + 5, oy, oz + 59, ox + 5, oy + 1, oz + 59, Blocks.AIR);
		// --- комната наблюдения (z 60..68): стена мониторов
		for (int z = 61; z <= 67; z++) {
			for (int y = 1; y <= 4; y++) set(x0, oy + y, oz + z, (z + y) % 3 == 0 ? Blocks.CYAN_CONCRETE : Blocks.BLACK_CONCRETE);
		}
		for (int z = 61; z <= 67; z += 2) {
			set(ox + 4, oy, oz + z, Blocks.POLISHED_ANDESITE);
			set(ox + 4, oy + 1, oz + z, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE);
			set(ox + 5, oy, oz + z, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.EAST));
		}
		set(ox + 7, oy, oz + 61, Blocks.JUKEBOX); // «запись разговоров»
		set(ox + 7, oy, oz + 67, Blocks.REDSTONE_LAMP);
		B.chandelier(ox + 5, oy + h, oz + 64, 1);
		fill(x0, oy, oz + 69, x1, oy + h, oz + 69, Blocks.GRAY_CONCRETE);
		B.door(ox + 5, oy, oz + 69, Direction.NORTH, Blocks.IRON_DOOR);
		// --- допросная (z 70..77): стол, стул, лампа, «зеркало»
		fill(x0 + 1, oy - 1, oz + 70, x1 - 1, oy - 1, oz + 77, Blocks.BLACK_CONCRETE);
		set(ox + 5, oy, oz + 73, Blocks.DARK_OAK_PLANKS);
		set(ox + 5, oy + 1, oz + 73, Blocks.SMOOTH_STONE_SLAB);
		set(ox + 5, oy, oz + 75, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH));
		set(ox + 5, oy, oz + 71, B.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH));
		fill(x0, oy + 1, oz + 72, x0, oy + 2, oz + 75, Blocks.TINTED_GLASS);
		B.chandelier(ox + 5, oy + h, oz + 73, 3);
		set(ox + 7, oy, oz + 77, Blocks.ANVIL); // для антуража
	}

	static void spawnNpcs(ServerWorld world, PizdecState st) {
		Castle.npc(world, st.cx + 4.5, st.cy, st.cz + 55.5, 270f, MAJOR_TAG,
				U.t("🕶 Майор Неприметный", Formatting.DARK_GRAY), VillagerProfession.NONE);
		Castle.npc(world, st.cx + 5.5, st.cy, st.cz + 71.5, 0f, INTERROGATOR_TAG,
				U.t("🕶 Следователь Сухов", Formatting.DARK_GRAY), VillagerProfession.NONE);
	}

	private static boolean in(PizdecState st, Vec3d p, int x0, int z0, int x1, int z1) {
		return st.castleBuilt && p.x >= st.cx + x0 && p.x < st.cx + x1 + 1 && p.z >= st.cz + z0 && p.z < st.cz + z1 + 1
				&& p.y >= st.cy - 1 && p.y < st.cy + 7;
	}

	public static boolean inHq(PizdecState st, Vec3d p) {
		return in(st, p, 3, 53, 7, 77);
	}

	public static boolean inSurveillance(PizdecState st, Vec3d p) {
		return in(st, p, 3, 60, 7, 68);
	}

	private static boolean inInterrogation(PizdecState st, Vec3d p) {
		return in(st, p, 3, 70, 7, 77);
	}

	private static Vec3d lobby(PizdecState st) {
		return new Vec3d(st.cx + 9.5, st.cy, st.cz + 55.5);
	}

	// ================================================================== слежка

	private static void tail(MinecraftServer s, ServerPlayerEntity p) {
		ServerWorld world = s.getOverworld();
		VillagerEntity a = agents.get(p.getUuid());
		Vec3d look = p.getRotationVector().multiply(1, 0, 1);
		if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
		look = look.normalize();
		Vec3d want = p.getPos().subtract(look.multiply(7)).add(look.z * 2, 0, -look.x * 2);
		int gx = (int) Math.floor(want.x), gz = (int) Math.floor(want.z);
		double gy;
		BlockPos at = BlockPos.ofFloored(want.x, p.getY(), want.z);
		if (world.getBlockState(at).isAir() && world.getBlockState(at.up()).isAir() && !world.getBlockState(at.down()).isAir()) {
			gy = p.getY();
		} else {
			gy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, gx, gz);
		}
		want = new Vec3d(want.x, gy, want.z);
		if (a == null || a.isRemoved()) {
			a = EntityType.VILLAGER.create(world);
			if (a == null) return;
			a.refreshPositionAndAngles(want.x, want.y, want.z, 0, 0);
			a.setAiDisabled(true);
			a.setInvulnerable(true);
			a.setSilent(true);
			a.setCustomName(U.t(U.pick(AGENT_NAMES), Formatting.GRAY));
			a.setCustomNameVisible(false);
			a.addCommandTag(AGENT_TAG);
			agents.put(p.getUuid(), a);
			spawningAgent = true;
			world.spawnEntity(a);
			spawningAgent = false;
			return;
		}
		Vec3d cur = a.getPos();
		Vec3d d = want.subtract(cur);
		double len = d.length();
		Vec3d next = len > 28 ? want : len < 0.3 ? cur : cur.add(d.multiply(Math.min(1, 0.45 / len)));
		if (len <= 28) next = new Vec3d(next.x, gy, next.z);
		double dx = p.getX() - next.x, dz = p.getZ() - next.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		a.refreshPositionAndAngles(next.x, next.y, next.z, yaw, 0f);
		a.setHeadYaw(yaw);
		a.setBodyYaw(yaw);
	}

	private static void dropAgent(UUID id) {
		VillagerEntity a = agents.remove(id);
		if (a != null) a.discard();
	}

	public static void onEntityLoad(Entity e) {
		if (spawningAgent) return;
		if (e.getCommandTags().contains(AGENT_TAG) && !agents.containsValue(e)) e.discard();
	}

	// ================================================================== проверки, обыски, допросы

	/** Проверка документов агентом (или пограничником). Возвращает true, если всё чисто. */
	public static boolean documentCheck(ServerPlayerEntity p, String who) {
		U.msg(p, U.t("🕶 " + who + ": «Гражданин, ваши документы.»", Formatting.DARK_GRAY));
		U.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
		Docs.Check c = Docs.check(p);
		List<String> found = Border.confiscate(p);
		if (!found.isEmpty()) {
			U.msg(p, U.t("🕶 Изъято: " + String.join(", ", found) + ".", Formatting.RED));
			add(p, 15, "контрабанда при проверке: " + String.join(", ", found));
			Jail.arrest(p, "хранение контрабанды (выявлено при проверке документов)", 60);
			return false;
		}
		if (c == Docs.Check.NONE) {
			add(p, 5, "нет паспорта при проверке");
			Jail.arrest(p, "отсутствие паспорта при проверке документов", 40);
			return false;
		}
		if (c != Docs.Check.OK) {
			add(p, 10, "недействительный паспорт: " + Docs.checkText(c));
			Jail.arrest(p, "предъявление недействительного паспорта (" + Docs.checkText(c) + ")", 60);
			return false;
		}
		U.msg(p, U.t("🕶 " + who + ": «" + U.pick("Всё в порядке. Пока.", "Можете идти. Мы запомнили.", "Проходите. Не спешите.",
				"Хорошая фотография. Похожи.") + "»", Formatting.DARK_GRAY));
		return true;
	}

	public static ItemStack warrantItem(int no, String name, String reason) {
		return Gui.special(Items.PAPER, "warrant", "§cОрдер на обыск №" + no,
				"§7Выдан на: " + name, "§7Основание: " + reason, "§7Санкционировал: Майор Неприметный",
				"§8Обжалованию не подлежит");
	}

	/** Обыск по ордеру: инвентарь + контейнеры у дома. */
	public static void search(ServerPlayerEntity p, String reason) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		int no = ++st.warrantNo;
		st.markDirty();
		lastWarrant.put(p.getUuid(), s.getTicks());
		note(s, p.getUuid(), "выдан ордер на обыск №" + no + ": " + reason);
		U.title(p, U.t("🕶 ОБЫСК", Formatting.DARK_RED, Formatting.BOLD), U.t("Ордер №" + no, Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_IRON_DOOR_OPEN, 1f, 0.6f);
		p.getInventory().offerOrDrop(warrantItem(no, p.getName().getString(), reason));
		List<String> found = new ArrayList<>(Border.confiscate(p));
		Address.Home home = Address.home(s, p.getUuid());
		ServerWorld world = s.getOverworld();
		if (home != null) {
			U.msg(p, U.t("🕶 По адресу «" + home.name() + "» проводится обыск по ордеру №" + no + ".", Formatting.DARK_RED));
			BlockPos c = BlockPos.ofFloored(home.pos());
			if (world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
				for (BlockPos b : BlockPos.iterate(c.add(-6, -3, -6), c.add(6, 3, 6))) {
					if (!(world.getBlockEntity(b) instanceof Inventory inv)) continue;
					for (int i = 0; i < inv.size(); i++) {
						ItemStack it = inv.getStack(i);
						if (Border.isContraband(it)) {
							found.add(Border.label(it));
							inv.setStack(i, ItemStack.EMPTY);
						}
					}
					inv.markDirty();
				}
			} else {
				U.msg(p, U.t("🕶 Дом далеко, обыск проведён заочно. Протокол составлен заранее.", Formatting.GRAY));
			}
		}
		if (U.chance(0.15)) found.add("1 носок (для отчётности)");
		boolean real = found.stream().anyMatch(x -> !x.contains("носок"));
		if (real) {
			U.msg(p, U.t("🕶 Найдено и изъято: " + String.join(", ", found), Formatting.RED));
			add(p, 15, "по ордеру №" + no + " найдено: " + String.join(", ", found));
			Jail.arrest(p, "хранение запрещённого (обыск по ордеру №" + no + ")", 75);
		} else {
			U.msg(p, U.t("🕶 Ничего не найдено" + (found.isEmpty() ? "" : " (кроме: " + String.join(", ", found) + ")")
					+ ". Тайная полиция извиняется. Нет, не извиняется.", Formatting.GRAY));
			add(p, -15, "обыск №" + no + " ничего не дал");
		}
	}

	// ------------------------------------------------------------------ допрос

	private record Question(String q, String[] a, int[] score) {}

	private static final List<Question> QUESTIONS = List.of(
			new Question("Где вы были во время последнего указа?", new String[] {"Исполнял его с радостью", "Не помню",
					"Не ваше дело"}, new int[] {2, 1, 0}),
			new Question("Как вы относитесь к Его Величеству?", new String[] {"Люблю больше жизни", "Нормально",
					"А кто это?"}, new int[] {2, 1, 0}),
			new Question("Что вам известно о «Свободной Картошке»?", new String[] {"Это овощ", "Ничего, но готов донести",
					"Картошку — народу!"}, new int[] {1, 2, 0}),
			new Question("Почему вы так часто ходите у границы?", new String[] {"Любуюсь КПП", "Просто гуляю",
					"Хочу уехать"}, new int[] {2, 1, 0}),
			new Question("Сколько налогов вы заплатили бы добровольно?", new String[] {"Все", "Сколько скажут", "Ноль"},
					new int[] {2, 1, 0}),
			new Question("Кто победит на следующих выборах?", new String[] {"«Единая Корона», конечно", "Посмотрим",
					"Честный кандидат"}, new int[] {2, 1, 0}),
			new Question("Что вы читаете по вечерам?", new String[] {"«Королевскую правду»", "Ничего", "Листовки"},
					new int[] {2, 1, 0}),
			new Question("Вы знаете, почему вы здесь?", new String[] {"Это недоразумение", "Пришёл по зову сердца",
					"Потому что вы параноики"}, new int[] {1, 2, 0}),
			new Question("Опишите статую короля одним словом.", new String[] {"Величие", "Большая", "Безвкусица"},
					new int[] {2, 1, 0}),
			new Question("Подпишете признание?", new String[] {"Мне не в чем признаваться", "Подпишу, если вам так спокойнее",
					"Только с адвокатом"}, new int[] {1, 2, 0}),
			new Question("Кто ваши друзья?", new String[] {"Король и налоговая", "Не скажу", "Вам какое дело?"},
					new int[] {2, 1, 0}),
			new Question("Вы смотрите «Первый Королевский»?", new String[] {"Каждые 15 секунд", "Иногда",
					"У меня нет телевизора"}, new int[] {2, 1, 0}));

	private static final class Interrogation {
		final List<Question> qs = new ArrayList<>();
		final List<int[]> perm = new ArrayList<>();
		int step, timer, score;
		final String reason;

		Interrogation(String reason) {
			this.reason = reason;
			List<Question> pool = new ArrayList<>(QUESTIONS);
			java.util.Collections.shuffle(pool, U.R);
			for (int i = 0; i < 3; i++) {
				qs.add(pool.get(i));
				List<Integer> o = new ArrayList<>(List.of(0, 1, 2));
				java.util.Collections.shuffle(o, U.R);
				perm.add(new int[] {o.get(0), o.get(1), o.get(2)});
			}
		}
	}

	public static boolean interrogating(ServerPlayerEntity p) {
		return rooms.containsKey(p.getUuid());
	}

	public static void interrogate(ServerPlayerEntity p, String reason) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt || interrogating(p) || Jail.busy(p) || p.isCreative() || p.isSpectator()) return;
		dropAgent(p.getUuid());
		Interrogation in = new Interrogation(reason);
		rooms.put(p.getUuid(), in);
		note(s, p.getUuid(), "доставлен на допрос: " + reason);
		p.stopRiding();
		p.teleport(s.getOverworld(), st.cx + SEAT[0], st.cy, st.cz + SEAT[1], 180f, 0f);
		U.title(p, U.t("🕶 ДОПРОС", Formatting.DARK_RED, Formatting.BOLD), U.t(reason, Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 1f, 0.5f);
		U.msg(p, U.t("🕶 Ты в допросной тайной полиции. Отвечай: /otvet 1, /otvet 2 или /otvet 3. Молчание — тоже ответ.",
				Formatting.GRAY));
		ask(p, in);
	}

	private static void ask(ServerPlayerEntity p, Interrogation in) {
		Question q = in.qs.get(in.step);
		int[] o = in.perm.get(in.step);
		in.timer = 20 * 20;
		U.msg(p, U.t("🕶 Следователь Сухов (вопрос " + (in.step + 1) + "/3): «" + q.q() + "»", Formatting.WHITE));
		for (int i = 0; i < 3; i++) U.msg(p, U.t("   " + (i + 1) + ") " + q.a()[o[i]], Formatting.YELLOW));
		U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1f, 0.5f);
	}

	public static void answer(ServerPlayerEntity p, int choice) {
		Interrogation in = rooms.get(p.getUuid());
		if (in == null) {
			U.msg(p, U.t("🕶 Вас никто ни о чём не спрашивал. Пока.", Formatting.DARK_GRAY));
			return;
		}
		Question q = in.qs.get(in.step);
		int idx = in.perm.get(in.step)[choice - 1];
		int sc = q.score()[idx];
		in.score += sc;
		U.msg(p, U.t("🕶 Сухов " + (sc == 2 ? "одобрительно кивает." : sc == 1 ? "что-то записывает." : "молча смотрит. Долго."),
				Formatting.DARK_GRAY));
		next(p, in);
	}

	private static void next(ServerPlayerEntity p, Interrogation in) {
		in.step++;
		if (in.step < in.qs.size()) {
			ask(p, in);
			return;
		}
		finish(p, in);
	}

	private static void finish(ServerPlayerEntity p, Interrogation in) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		rooms.remove(p.getUuid());
		int score = in.score - (member(p) ? 1 : 0);
		Vec3d out = lobby(st);
		if (score >= 5) {
			setLevel(s, p.getUuid(), 25);
			note(s, p.getUuid(), "допрос: отпущен (" + score + "/6)");
			p.teleport(s.getOverworld(), out.x, out.y, out.z, 90f, 0f);
			U.msg(p, U.t("🕶 Сухов: «Свободны. Пока. Мы вам перезвоним.»", Formatting.GREEN));
		} else if (score >= 3) {
			setLevel(s, p.getUuid(), 30);
			st.informants.add(p.getUuid());
			st.markDirty();
			note(s, p.getUuid(), "допрос: завербован осведомителем (" + score + "/6)");
			p.teleport(s.getOverworld(), out.x, out.y, out.z, 90f, 0f);
			p.getInventory().offerOrDrop(Gui.special(Items.PAPER, "informant", "§8Подписка о сотрудничестве",
					"§7Псевдоним: «" + U.pick("Колобок", "Ромашка", "Тихоня", "Огурец", "Сосед") + "»",
					"§7Доносить: /stuknut <игрок> (+10 бабла)", "§8Никому не показывать"));
			Money.give(p, 50, "подъёмные осведомителя");
			U.msg(p, U.t("🕶 Сухов: «Вы нам подходите. Теперь вы — наш внештатный осведомитель. /stuknut <игрок>. "
					+ "И никому ни слова.»", Formatting.GOLD));
			if (member(p)) {
				U.msg(p, U.t("🥔 Ты всё ещё в подполье. Двойной агент — опасная профессия.", Formatting.DARK_GREEN));
			}
		} else {
			note(s, p.getUuid(), "допрос: передан в суд (" + score + "/6)");
			if (member(p)) expose(s, p.getUuid(), "раскрыт на допросе");
			Jail.arrest(p, "по результатам допроса тайной полиции (" + in.reason + ")", 150);
		}
	}

	private static void tickRooms(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		for (Map.Entry<UUID, Interrogation> e : new ArrayList<>(rooms.entrySet())) {
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(e.getKey());
			if (p == null) {
				rooms.remove(e.getKey());
				continue;
			}
			Interrogation in = e.getValue();
			if (Jail.jailed(p)) {
				rooms.remove(e.getKey());
				continue;
			}
			if (!inInterrogation(st, p.getPos()) || p.getServerWorld() != s.getOverworld()) {
				p.teleport(s.getOverworld(), st.cx + SEAT[0], st.cy, st.cz + SEAT[1], 180f, 0f);
				U.bar(p, U.t("🕶 Сядьте. Мы не закончили.", Formatting.DARK_RED));
			}
			if (--in.timer <= 0) {
				U.msg(p, U.t("🕶 Молчание засчитано как ответ.", Formatting.DARK_GRAY));
				next(p, in);
			} else if (in.timer % 100 == 0) {
				U.bar(p, U.t("🕶 Ответ: /otvet 1-3 · осталось " + in.timer / 20 + " сек", Formatting.GRAY));
			}
		}
	}

	public static void onLeave(ServerPlayerEntity p) {
		dropAgent(p.getUuid());
		if (rooms.remove(p.getUuid()) != null) {
			PizdecState st = PizdecState.get(p.getServer());
			st.wanted.add(p.getUuid());
			st.markDirty();
			note(p.getServer(), p.getUuid(), "сбежал с допроса (вышел из игры)");
			U.say(p.getServer(), U.t("🕶 Гражданин " + p.getName().getString() + " покинул допрос без разрешения. Это зря.",
					Formatting.DARK_GRAY));
		}
	}

	public static void onJoin(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (st.wanted.remove(p.getUuid())) {
			st.markDirty();
			p.getServer().execute(() -> Jail.arrest(p, "розыск тайной полиции", 120, true));
		}
	}

	// ================================================================== подполье

	public static ItemStack leaflet() {
		return Gui.special(Items.PAPER, "leaflet", "§2Листовка " + UNDERGROUND,
				"§7«" + U.pick("Картошку — народу! Корону — в музей!", "Налоги — это грабёж!", "Выборы куплены!",
						"Король голый (под мантией)!", "Свободу кротам!", "Долой карету!") + "»",
				"§4Запрещено. Хранение — тюрьма.");
	}

	private static void expose(MinecraftServer s, UUID id, String how) {
		PizdecState st = PizdecState.get(s);
		st.underground.remove(id);
		st.markDirty();
		setLevel(s, id, get(s, id) + 40);
		note(s, id, "участник подполья " + UNDERGROUND + ": " + how);
		ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
		String name = st.names.getOrDefault(id, "неизвестный");
		U.say(s, U.t("🕶 Тайная полиция раскрыла члена подпольной организации " + UNDERGROUND + ": " + name + ".",
				Formatting.DARK_RED));
		News.breaking(s, "Тайная полиция обезвредила опасного подпольщика " + name + ". Картошка в безопасности.");
		if (p == null) {
			st.wanted.add(id);
		}
		for (UUID m : st.underground) {
			ServerPlayerEntity mp = s.getPlayerManager().getPlayer(m);
			if (mp != null) U.msg(mp, U.t("🥔 [Подполье] " + name + " провален. Сжигайте листовки.", Formatting.DARK_GREEN));
		}
	}

	private static void raid(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.underground.isEmpty()) return;
		List<UUID> ms = new ArrayList<>(st.underground);
		UUID target = U.pick(ms);
		double c = 0.3 + get(s, target) / 200.0;
		if (Laws.is("vigilance_week")) c += 0.2;
		if (U.chance(c)) {
			expose(s, target, "облава «Чистая картошка»");
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(target);
			if (p != null) interrogate(p, "участие в подпольной организации");
		} else {
			for (UUID m : ms) {
				ServerPlayerEntity mp = s.getPlayerManager().getPlayer(m);
				if (mp != null) U.msg(mp, U.t("🥔 [Подполье] Была облава. Нас не нашли. Пока.", Formatting.DARK_GREEN));
			}
			U.say(s, U.t("🕶 Прошли плановые оперативные мероприятия. Всё спокойно. Граждане, будьте бдительны.",
					Formatting.DARK_GRAY));
		}
	}

	// ================================================================== тик

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		tickRooms(s);
		int now = s.getTicks();
		// хвосты — каждые 2 тика
		if (now % 2 == 0) {
			for (UUID id : new ArrayList<>(agents.keySet())) {
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
				if (p == null || Jail.busy(p) || p.getServerWorld() != s.getOverworld() || get(s, id) < WATCH - 5
						|| p.isCreative() || p.isSpectator()) {
					dropAgent(id);
					continue;
				}
				tail(s, p);
			}
		}
		if (now % 20 == 0) {
			for (ServerPlayerEntity p : U.players(s)) {
				if (p.isCreative() || p.isSpectator()) continue;
				surveillanceRoom(p, st);
				if (Jail.busy(p) || p.getServerWorld() != s.getOverworld()) continue;
				int v = get(p);
				if (v >= WATCH && !agents.containsKey(p.getUuid())) tail(s, p);
				if (v >= WATCH) {
					int lh = lastHint.getOrDefault(p.getUuid(), 0);
					if (now - lh > 20 * 45 && U.chance(0.3)) {
						lastHint.put(p.getUuid(), now);
						U.msg(p, U.t(U.pick(HINTS), Formatting.DARK_GRAY, Formatting.ITALIC));
					}
				}
				if (v >= INTERROGATE && U.chance(0.3)) {
					interrogate(p, "особый интерес тайной полиции");
					continue;
				}
				if (v >= WARRANT && now - lastWarrant.getOrDefault(p.getUuid(), -99999) > 20 * 180 && U.chance(0.2)) {
					search(p, "повышенная подозрительность");
					continue;
				}
				if (v >= CHECKS && now - lastCheck.getOrDefault(p.getUuid(), -99999) > 20 * 90 && U.chance(0.25)) {
					lastCheck.put(p.getUuid(), now);
					documentCheck(p, agents.containsKey(p.getUuid())
							? agents.get(p.getUuid()).getCustomName().getString() + " (внезапно показав корочку)"
							: "Агент тайной полиции");
				}
			}
		}
		// остывание подозрений — раз в 30 сек
		if (now % (20 * 30) == 0) {
			for (ServerPlayerEntity p : U.players(s)) {
				int v = get(p);
				int floor = member(p) ? 15 : 0;
				if (v > floor) setLevel(s, p.getUuid(), v - 1);
			}
		}
		// гранты подполью из-за рубежа — раз в 5 минут
		if (now % (20 * 300) == 0) {
			for (UUID m : st.underground) {
				ServerPlayerEntity mp = s.getPlayerManager().getPlayer(m);
				if (mp != null && !Jail.busy(mp)) {
					Money.add(mp, 15);
					U.msg(mp, U.t("🥔 [Подполье] Пришёл грант из-за рубежа: +15 бабла. Не трать на бургеры.", Formatting.DARK_GREEN));
				}
			}
		}
		// облавы — раз в 10 минут
		if (++raidTimer >= 20 * 600) {
			raidTimer = 0;
			raid(s);
		}
	}

	private static void surveillanceRoom(ServerPlayerEntity p, PizdecState st) {
		if (!inSurveillance(st, p.getPos())) return;
		if (!Jobs.is(p, "agent")) {
			Vec3d l = lobby(st);
			p.teleport(p.getServer().getOverworld(), l.x, l.y, l.z, 90f, 0f);
			U.msg(p, U.t("🕶 Посторонним вход воспрещён. Вас запомнили.", Formatting.DARK_RED));
			add(p, 10, "проник в комнату наблюдения");
			return;
		}
		List<ServerPlayerEntity> all = U.players(p.getServer());
		if (all.size() <= 1) {
			U.bar(p, U.t("📺 Мониторы: на связи только ты. Подозрительно.", Formatting.AQUA));
			return;
		}
		ServerPlayerEntity t = all.get((p.getServer().getTicks() / 40) % all.size());
		if (t == p) return;
		BlockPos b = t.getBlockPos();
		U.bar(p, U.t("📺 " + t.getName().getString() + ": " + b.getX() + " " + b.getY() + " " + b.getZ()
				+ " · подозр. " + get(t) + " · " + level(get(t)).replaceAll("§.", ""), Formatting.AQUA));
	}

	// ================================================================== чат

	/** Прослушка чата: ничего не блокирует, только записывает. */
	public static void onChat(ServerPlayerEntity p, String text) {
		String low = text.toLowerCase();
		int now = p.getServer().getTicks();
		for (String good : LOYAL) {
			if (low.contains(good)) {
				p.getServer().execute(() -> add(p, -3, "публично славил короля"));
				return;
			}
		}
		for (String bad : SUSPICIOUS) {
			if (low.contains(bad)) {
				if (now - lastChat.getOrDefault(p.getUuid(), -9999) < 20 * 10) return;
				lastChat.put(p.getUuid(), now);
				String quote = text.length() > 40 ? text.substring(0, 40) + "…" : text;
				p.getServer().execute(() -> {
					add(p, 6, "подозрительное высказывание: «" + quote + "»");
					if (U.chance(0.3)) U.msg(p, U.t("*в трубке что-то щёлкнуло*", Formatting.DARK_GRAY, Formatting.ITALIC));
				});
				return;
			}
		}
	}

	// ================================================================== доносы

	private static boolean cool(ServerPlayerEntity p, int seconds) {
		int now = p.getServer().getTicks();
		int last = cooldown.getOrDefault(p.getUuid(), -999999);
		if (now - last < seconds * 20) {
			U.msg(p, U.t("🕶 Не так часто. Через " + (seconds * 20 - (now - last)) / 20 + " сек.", Formatting.GRAY));
			return false;
		}
		cooldown.put(p.getUuid(), now);
		return true;
	}

	public static void stuknut(ServerPlayerEntity p, ServerPlayerEntity t) {
		if (t == p) {
			U.msg(p, U.t("🕶 Майор: «На себя? Оформим. Шучу. Пока.»", Formatting.DARK_GRAY));
			return;
		}
		if (!cool(p, 60)) return;
		MinecraftServer s = p.getServer();
		boolean inf = informant(p);
		if (member(t)) {
			Money.give(p, 100, "сигнал о подпольщике");
			expose(s, t.getUuid(), "по сигналу бдительного гражданина");
			if (!Jail.busy(t)) interrogate(t, "участие в подпольной организации");
			return;
		}
		add(t, inf ? 15 : 5, "сигнал от " + (inf ? "осведомителя" : "гражданина"));
		if (inf) {
			Money.give(p, 10, "гонорар осведомителя");
		} else if (U.chance(0.5)) {
			add(p, 10, "ложный донос");
		}
		U.msg(p, U.t("🕶 Сигнал принят. Гражданин " + t.getName().getString() + " будет проверен. Спасибо за бдительность.",
				Formatting.DARK_GRAY));
		for (ServerPlayerEntity wkr : U.players(s)) if (Jobs.is(wkr, "agent")) Jobs.share(wkr, 5);
	}

	// ================================================================== меню майора

	public static final class MajorGui extends Gui {
		private MajorGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "🕶 Майор Неприметный", MajorGui::new);
		}

		private void redraw() {
			fill(Items.BLACK_STAINED_GLASS_PANE);
			int v = SecretPolice.get(player);
			set(4, icon(Items.ENDER_EYE, "§8Индекс благонадёжности: §f" + (100 - v) + "%", "§7Уровень внимания: " + level(v),
					"§8Мы ничего о вас не знаем. Почти."));
			set(20, icon(Items.WRITABLE_BOOK, "§eЯвка с повинной", "§7Сознаться во всём: −25 подозрительности",
					"§7Риск: 40% — посадят за то, в чём сознались"));
			set(22, icon(Items.PAPER, "§fСправка о благонадёжности — 150", "§7−20 подозрительности",
					"§7Не чаще раза в 5 минут"));
			set(24, icon(Items.IRON_BARS, "§cСообщить о подпольщике", "§7Команда: /stuknut <игрок>",
					"§7Подпольщик — +100 бабла", "§7Ложный донос — сами под подозрением"));
			set(30, icon(Items.SPYGLASS, "§9Служба в тайной полиции", "§7Работа «Сотрудник тайной полиции»",
					"§7Звание Рыцарь+ и подозрительность < 10", "§eЛКМ — подать анкету"));
			set(32, icon(Items.COMPASS, "§fВыезд за границу", "§7Разрешение — МФЦ, окно №4 (300)",
					"§7Нужно: паспорт и подозрительность < 40", "§7Без разрешения — тюрьма. Шанс уйти — 0,5%."));
			if (informant(player)) set(40, icon(Items.NAME_TAG, "§8Вы — наш осведомитель", "§7/stuknut <игрок> — +10 бабла"));
			set(53, icon(Items.BARRIER, "§cУйти (если отпустят)"));
		}

		@Override
		protected void click(int slot, int button) {
			MinecraftServer s = player.getServer();
			switch (slot) {
				case 53 -> close();
				case 20 -> {
					close();
					SecretPolice.add(player, -25, "явка с повинной");
					if (U.chance(0.4)) {
						Jail.arrest(player, "чистосердечное признание (во всём)", 30);
					} else {
						U.msg(player, U.t("🕶 Майор: «Признание принято. Ступайте. Мы следим за вашим исправлением.»",
								Formatting.DARK_GRAY));
					}
				}
				case 22 -> {
					int now = s.getTicks();
					if (now - lastCertificate.getOrDefault(player.getUuid(), -999999) < 20 * 300) {
						U.msg(player, U.t("🕶 Майор: «Вы недавно уже были благонадёжны. Хватит.»", Formatting.GRAY));
						return;
					}
					if (!Money.pay(player, 150, "справку о благонадёжности")) return;
					lastCertificate.put(player.getUuid(), now);
					SecretPolice.add(player, -20, "купил справку о благонадёжности");
					player.getInventory().offerOrDrop(Gui.special(Items.PAPER, "loyal", "§fСправка о благонадёжности",
							"§7Предъявитель благонадёжен.", "§7Действительна до первого подозрения."));
					redraw();
				}
				case 30 -> {
					for (Jobs.Job j : Jobs.ALL) if (j.id().equals("agent")) Jobs.apply(player, j);
					redraw();
				}
				default -> {}
			}
		}
	}

	// ================================================================== команды

	private static ServerPlayerEntity agentOnly(ServerCommandSource src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayerEntity p = src.getPlayerOrThrow();
		if (!Jobs.is(p, "agent")) {
			U.msg(p, U.t("🕶 Эта команда только для сотрудников. А вы кто такой? Записали.", Formatting.DARK_RED));
			add(p, 5, "интересовался служебными командами");
			return null;
		}
		return p;
	}

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("otvet")
				.then(CommandManager.argument("n", IntegerArgumentType.integer(1, 3)).executes(c -> {
					answer(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "n"));
					return 1;
				})));
		d.register(CommandManager.literal("stuknut")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					stuknut(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"));
					return 1;
				})));
		d.register(CommandManager.literal("slezhka")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					ServerPlayerEntity p = agentOnly(c.getSource());
					if (p == null || !cool(p, 40)) return 0;
					ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
					add(t, 10, "установлено наблюдение (агент " + p.getName().getString() + ")");
					Jobs.share(p, 15);
					U.msg(p, U.t("🕶 Наблюдение за " + t.getName().getString() + " установлено. Подозрительность: " + get(t)
							+ " (" + level(get(t)).replaceAll("§.", "") + ").", Formatting.AQUA));
					List<String> dos = PizdecState.get(p.getServer()).dossier.getOrDefault(t.getUuid(), List.of());
					for (int i = Math.max(0, dos.size() - 3); i < dos.size(); i++) U.msg(p, U.t("   " + dos.get(i), Formatting.GRAY));
					return 1;
				})));
		d.register(CommandManager.literal("order")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					ServerPlayerEntity p = agentOnly(c.getSource());
					if (p == null) return 0;
					ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
					if (get(t) < 30) {
						U.msg(p, U.t("🕶 Майор не подпишет: подозрительность " + t.getName().getString() + " всего " + get(t)
								+ " (нужно 30+). Понаблюдайте ещё.", Formatting.GRAY));
						return 0;
					}
					if (Jail.busy(t) || !cool(p, 90)) return 0;
					Jobs.share(p, 30);
					search(t, "рапорт агента " + p.getName().getString());
					return 1;
				})));
		d.register(CommandManager.literal("dopros")
				.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
					ServerPlayerEntity p = agentOnly(c.getSource());
					if (p == null) return 0;
					ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
					if (get(t) < WARRANT) {
						U.msg(p, U.t("🕶 Для допроса нужна подозрительность 50+ (у " + t.getName().getString() + " — " + get(t)
								+ ").", Formatting.GRAY));
						return 0;
					}
					if (Jail.busy(t) || !cool(p, 120)) return 0;
					Jobs.share(p, 40);
					interrogate(t, "по рапорту агента");
					return 1;
				})));
		d.register(CommandManager.literal("podpolie").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (member(p)) {
				U.msg(p, U.t("🥔 [Подполье " + UNDERGROUND + "] Нас " + st.underground.size() + ". Команды: /pchat <текст>, "
						+ "/podpolie listovka (5), /podpolie razbrosat, /podpolie prodat (импорт ×2,5), /podpolie vyiti. "
						+ "Облавы — раз в 10 минут.", Formatting.DARK_GREEN));
			} else {
				U.msg(p, U.t("🥔 Шёпот из подворотни: «Хочешь свободы? /podpolie vstupit. Только тихо.»", Formatting.DARK_GREEN));
			}
			return 1;
		}).then(CommandManager.literal("vstupit").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (Jail.busy(p)) return 0;
			if (!st.underground.add(p.getUuid())) {
				U.msg(p, U.t("🥔 Ты уже с нами.", Formatting.DARK_GREEN));
				return 0;
			}
			st.markDirty();
			U.msg(p, U.t("🥔 Добро пожаловать в " + UNDERGROUND + ". Пароль: «Клубень». Отзыв: «Ботва». Никому ни слова.",
					Formatting.DARK_GREEN));
			if (Jobs.is(p, "agent")) U.msg(p, U.t("🥔 …Сотрудник тайной полиции в подполье. Смело. Глупо, но смело.",
					Formatting.DARK_GREEN));
			if (U.chance(0.15)) add(p, 20, "замечен на явке подполья");
			return 1;
		})).then(CommandManager.literal("vyiti").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			PizdecState st = PizdecState.get(p.getServer());
			if (st.underground.remove(p.getUuid())) {
				st.markDirty();
				U.msg(p, U.t("🥔 Ты вышел из подполья. Но подполье из тебя — нет.", Formatting.DARK_GREEN));
			}
			return 1;
		})).then(CommandManager.literal("listovka").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (!member(p)) {
				U.msg(p, U.t("🥔 Тебе тут не рады. Пока.", Formatting.GRAY));
				return 0;
			}
			if (!Money.pay(p, 5, "печать листовки")) return 0;
			p.getInventory().offerOrDrop(leaflet());
			return 1;
		})).then(CommandManager.literal("razbrosat").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (!member(p)) return 0;
			int n = 0;
			PlayerInventory inv = p.getInventory();
			for (int i = 0; i < inv.size(); i++) {
				if (Gui.isSpecial(inv.getStack(i), "leaflet")) {
					n += inv.getStack(i).getCount();
					inv.setStack(i, ItemStack.EMPTY);
				}
			}
			if (n == 0) {
				U.msg(p, U.t("🥔 Нечего разбрасывать. /podpolie listovka", Formatting.GRAY));
				return 0;
			}
			int readers = 0;
			for (ServerPlayerEntity o : U.players(p.getServer())) {
				if (o != p && o.squaredDistanceTo(p) < 24 * 24) {
					readers++;
					U.msg(o, U.t("📄 Тебе под ноги упала листовка " + UNDERGROUND + ": «Картошку — народу! Корону — в музей!»",
							Formatting.DARK_GREEN));
				}
			}
			Propaganda.loveDrop(0.1 * n);
			Money.add(p, 3 * n);
			U.msg(p, U.t("🥔 Разбросано листовок: " + n + ", прочитали: " + readers + ". Фонд выплатил " + 3 * n + " бабла.",
					Formatting.DARK_GREEN));
			if (U.chance(0.7)) add(p, 4 * n, "распространение листовок (" + n + " шт.)");
			return 1;
		})).then(CommandManager.literal("prodat").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			if (!member(p)) {
				U.msg(p, U.t("🥔 Чёрный рынок для своих.", Formatting.GRAY));
				return 0;
			}
			ItemStack h = p.getMainHandStack();
			int price = Border.foreignPrice(h);
			if (price <= 0) {
				U.msg(p, U.t("🥔 Это не импорт. Держи в руке товар от иностранцев.", Formatting.GRAY));
				return 0;
			}
			int sum = price * h.getCount() * 5 / 2;
			h.setCount(0);
			Money.add(p, sum);
			U.msg(p, U.t("🥔 Барыга «Шнырь» взял товар за " + sum + " бабла.", Formatting.DARK_GREEN));
			if (U.chance(0.5)) add(p, 5, "сбыт импортных товаров");
			return 1;
		})));
		d.register(CommandManager.literal("pchat")
				.then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					PizdecState st = PizdecState.get(p.getServer());
					if (!member(p)) {
						U.msg(p, U.t("🥔 Канал не найден.", Formatting.GRAY));
						return 0;
					}
					String text = StringArgumentType.getString(c, "text");
					boolean tapped = U.chance(0.08);
					for (UUID m : st.underground) {
						ServerPlayerEntity mp = p.getServer().getPlayerManager().getPlayer(m);
						if (mp == null) continue;
						U.msg(mp, U.t("🥔 [Подполье] " + p.getName().getString() + ": " + text, Formatting.DARK_GREEN));
						if (tapped) U.msg(mp, U.t("   *на линии щелчок*", Formatting.DARK_GRAY, Formatting.ITALIC));
					}
					if (tapped) add(p, 12, "перехвачено сообщение подполья: «" + (text.length() > 30 ? text.substring(0, 30) + "…" : text) + "»");
					return 1;
				})));
	}

	/** Админская часть: /pizdec delo и /pizdec podozrenie. */
	public static void showDossier(ServerCommandSource src, ServerPlayerEntity t) {
		PizdecState st = PizdecState.get(t.getServer());
		src.sendFeedback(() -> U.t("🗂 Дело №" + Math.abs(t.getUuid().hashCode() % 100000) + ": " + t.getName().getString()
				+ " · подозрительность " + get(t) + " (" + level(get(t)).replaceAll("§.", "") + ")"
				+ (member(t) ? " · ПОДПОЛЬЕ" : "") + (informant(t) ? " · осведомитель" : ""), Formatting.YELLOW), false);
		for (String line : st.dossier.getOrDefault(t.getUuid(), List.of())) {
			src.sendFeedback(() -> U.t("  " + line, Formatting.GRAY), false);
		}
	}
}
