package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Государственные СМИ: телеканал «Первый Королевский» (телевизоры в отеле и парламенте)
 * и газета «Королевская правда» (свежий номер приносят в отель каждые 2 минуты).
 */
public final class News {
	public static final int PAPER_PERIOD = 20 * 120;
	public static final int TV_PERIOD = 20 * 15;

	private static int paperTimer;
	private static String breaking;
	private static final Map<UUID, Integer> lastTv = new HashMap<>();

	private News() {}

	// ================================================================== генератор пропаганды

	private static String richest(MinecraftServer s) {
		ServerPlayerEntity best = null;
		for (ServerPlayerEntity p : U.players(s)) if (best == null || Money.get(p) > Money.get(best)) best = p;
		return best == null ? "казначей Аркадий" : best.getName().getString();
	}

	private static String someone(MinecraftServer s) {
		List<ServerPlayerEntity> ps = U.players(s);
		return ps.isEmpty() ? "житель Валера" : U.pick(ps).getName().getString();
	}

	public static void breaking(MinecraftServer s, String text) {
		breaking = text;
	}

	/** Случайная «новость» с реальными цифрами мира. */
	public static String headline(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		Elections.Party crown = Elections.PARTIES.get(Elections.KING);
		int growth = 3 + U.R.nextInt(40);
		String[] t = {
				"Указ №" + st.decrees + " «" + Laws.currentTitle() + "» встречен всенародным ликованием. Опрошенные плакали от счастья.",
				"Налоговая провела уже " + st.taxRaids + " проверок. Граждане благодарят: «Наконец-то наши деньги в надёжных руках».",
				"Преступность побеждена окончательно: за всё время всего " + st.arrests + " арестов, и каждый — абсолютно справедлив.",
				"Экономика королевства выросла на " + growth + "% только за сегодня. Учёные не успевают считать.",
				"На фронте наши войска снова наступают. Сейчас на передовой " + st.exiles.size() + " героев. Потерь нет (не считая погибших).",
				"Рейтинг партии «" + crown.name() + "» достиг " + (78 + U.R.nextInt(20)) + "%. Остальные партии рады за неё.",
				"Король лично посетил стройку и одобрил каждый блок. Блоки довольны.",
				"Иностранные агенты распространяют слухи о налогах. Налоги — это любовь короля в денежной форме.",
				"Гражданин " + someone(s) + " поблагодарил короля за всё. Особенно за карету.",
				"Самый богатый гражданин королевства — " + richest(s) + ". Налоговая уже выехала поздравить.",
				"В королевском отеле нет свободных мест: все хотят пожить поближе к Его Величеству.",
				"Карета Его Величества побила рекорд скорости. Убежать невозможно — и не нужно!",
				"Парламент единогласно поддержал все инициативы короля. Обсуждение заняло 0 секунд — рекорд эффективности.",
				"Учёные доказали: чем выше налоги, тем крепче сон. Проверено на добровольцах.",
				"Погода в королевстве отличная. Если идёт дождь — это слёзы радости неба.",
				"Цены в королевском магазине самые низкие в мире. Других магазинов в мире нет, но всё равно.",
				"Выборы через " + Elections.minutesLeft(s) + " мин. Эксперты единодушны: победит самая любимая народом партия.",
				"Оппозиция провела митинг. Пришли трое и курица. Курица поддержала короля.",
				"Паспортный стол перевыполнил план. Граждане становятся всё более документированными.",
				"FPV-дроны доставили гражданам еду и бабло. Отдельные взрывы — это праздничный салют.",
				"Ветераны войны получили по 100 бабла. Король щедр как никогда (он всегда щедр как никогда).",
				"Шут Валера опроверг слухи о том, что он умнее короля. «Это невозможно», — сказал шут.",
		};
		return U.pick(t);
	}

	// ================================================================== телевизоры

	/** Экран телевизора: блоки экрана (абсолютные) и центр. */
	public record Tv(List<BlockPos> screen, Vec3d center) {}

	public static List<Tv> tvs(PizdecState st) {
		List<Tv> out = new ArrayList<>();
		for (int[][] r : Castle.tvScreens()) {
			List<BlockPos> sc = new ArrayList<>();
			double cx = 0, cy = 0, cz = 0;
			for (int[] b : r) {
				BlockPos bp = new BlockPos(st.cx + b[0], st.cy + b[1], st.cz + b[2]);
				sc.add(bp);
				cx += bp.getX() + 0.5;
				cy += bp.getY() + 0.5;
				cz += bp.getZ() + 0.5;
			}
			out.add(new Tv(sc, new Vec3d(cx / r.length, cy / r.length, cz / r.length)));
		}
		return out;
	}

	private static final Block[] PIXELS = {Blocks.LIGHT_BLUE_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.RED_CONCRETE,
			Blocks.YELLOW_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.LIME_CONCRETE};

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		int now = s.getTicks();
		ServerWorld w = s.getOverworld();

		// газеты
		paperTimer++;
		if (paperTimer >= PAPER_PERIOD) {
			paperTimer = 0;
			deliver(s);
		}

		if (now % 20 != 0) return;
		List<Tv> tvs = tvs(st);
		for (Tv tv : tvs) {
			List<ServerPlayerEntity> viewers = new ArrayList<>();
			for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
				if (p.getServerWorld() == w && p.getPos().distanceTo(tv.center) < 7) viewers.add(p);
			}
			if (viewers.isEmpty()) continue;
			// «картинка»: экран мигает
			if (w.isChunkLoaded(tv.screen.get(0))) {
				for (BlockPos b : tv.screen) {
					w.setBlockState(b, U.pick(PIXELS).getDefaultState(), B.FLAGS);
				}
			}
			for (ServerPlayerEntity p : viewers) {
				Integer last = lastTv.get(p.getUuid());
				if (last != null && now - last < TV_PERIOD) continue;
				lastTv.put(p.getUuid(), now);
				String text = breaking != null && U.chance(0.5) ? "СРОЧНО: " + breaking : headline(s);
				U.msg(p, U.t("📺 «Первый Королевский»: ", Formatting.AQUA).append(U.t(text, Formatting.WHITE)));
				U.bar(p, U.t("📺 " + U.pick("Король — лучший", "Слава Короне", "Налоги — это любовь", "Всё хорошо",
						"Карета заботится о вас", "Голосуйте правильно"), Formatting.GOLD));
				U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BIT, 0.4f, 1.6f);
			}
		}
	}

	// ================================================================== газета

	private static RawFilteredPair<Text> page(String s) {
		return RawFilteredPair.of(Text.literal(s));
	}

	public static ItemStack newspaper(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		List<String> pages = new ArrayList<>();
		String date = Docs.today();
		String main = breaking != null ? breaking : headline(s);
		pages.add("§4§l  КОРОЛЕВСКАЯ\n     ПРАВДА§r\n§8№" + st.newsIssue + " · " + date + " · 2 бабла\nОрган Его Величества§0\n\n§l"
				+ main + "§r\n\n§8Читайте внутри: указы, выборы, фронт, криминал, гороскоп.");
		pages.add("§l УКАЗЫ И ВЛАСТЬ§r\n\n" + "Действует указ №" + st.decrees + " «" + Laws.currentTitle()
				+ "». Мудрость указа очевидна каждому.\n\n" + headline(s));
		StringBuilder el = new StringBuilder("§l ВЫБОРЫ§r\n\nДо выборов: " + Elections.minutesLeft(s) + " мин.\n§8Опрос «Королевской правды»:§0\n");
		double crown = 0.80 + U.R.nextDouble() * 0.15;
		el.append("«").append(Elections.PARTIES.get(0).name()).append("» — ").append(Elections.pct(crown)).append("\nОстальные — ")
				.append(Elections.pct(1 - crown)).append("\n§8(погрешность 0%)§0");
		pages.add(el.toString());
		pages.add("§l ФРОНТ И ЭКОНОМИКА§r\n\nНа фронте: " + st.exiles.size() + " героев, наступление идёт по плану.\n\n"
				+ "Налоговых проверок: " + st.taxRaids + "\nКурс бабла: стабильный (всегда)\nБогатейший: " + richest(s));
		StringBuilder crime = new StringBuilder("§l КРИМИНАЛ§r\n\nВсего арестов: " + st.arrests + "\n");
		int n = 0;
		for (Map.Entry<UUID, List<String>> e : st.crimes.entrySet()) {
			if (e.getValue().isEmpty() || n >= 4) continue;
			String c = e.getValue().get(e.getValue().size() - 1);
			crime.append("• ").append(st.names.getOrDefault(e.getKey(), "гражданин")).append(": ")
					.append(c.length() > 40 ? c.substring(0, 40) + "…" : c).append("\n");
			n++;
		}
		crime.append("\n§8Суд справедлив. Карета быстра.§0");
		pages.add(crime.toString());
		StringBuilder gov = new StringBuilder("§l ВЛАСТЬ НА МЕСТАХ§r\n\n");
		for (int r = 0; r < 4; r++) {
			Politics.Governor g = Politics.governor(st, r);
			gov.append(Politics.REGIONS[r].replace(" губерния", "")).append(": ").append(g.name.split(" ")[0])
					.append(" — ").append(U.pick("рост", "рекорд", "успех", "прорыв")).append("\n");
		}
		gov.append("\nДоверие королю: ").append(Politics.pct(Politics.officialKing(st))).append("\n§8██████ ████ ███████ ██ ████ [изъято Главлитом]§0");
		pages.add(gov.toString());
		List<String> sh = BlackMarket.shortageNames();
		pages.add("§l ТОРГОВЛЯ§r\n\nДефицита нет! Временно повышенный спрос на: "
				+ (sh.isEmpty() ? "ничего" : String.join(", ", sh.subList(0, Math.min(4, sh.size()))))
				+ ".\n\nСпекулянтов просим сообщать: /stuknut.\n\n§8Парадов проведено: " + st.paradeNo + ". Ликование — плановое.§0");
		String[] signs = {"Овен", "Телец", "Близнецы", "Рак", "Лев", "Дева", "Весы", "Скорпион", "Стрелец", "Козерог", "Водолей", "Рыбы"};
		String[] fate = {"заплатите налог", "встретите карету", "найдёте алмаз (фальшивый)", "получите указ",
				"проголосуете правильно", "съедите чипсы", "попадёте под FPV", "станете дворянином (нет)"};
		StringBuilder hz = new StringBuilder("§l ГОРОСКОП§r\n\n");
		for (int i = 0; i < 4; i++) hz.append(U.pick(signs)).append(": ").append(U.pick(fate)).append(".\n");
		hz.append("\n§l РЕКЛАМА§r\nОтель «Пять звёзд (из десяти)» — номера от 30!\nАдвокат Эдуард Невиновный — почти гарантия!");
		pages.add(hz.toString());
		pages.add("§l АНЕКДОТ§r\n\n" + U.pick("— Почему налоговая ездит на карете?\n— Потому что на тебе уже ездит король.",
				"Встречаются два гражданина. Один с паспортом, другой в тюрьме.",
				"— Как пройти в парламент?\n— Не надо, там уже всё решили.",
				"Шут Валера пошёл на выборы. Выборы пошли дальше.")
				+ "\n\n§8Главный редактор: Министерство правды. Тираж: все.§0");
		ItemStack st2 = new ItemStack(Items.WRITTEN_BOOK);
		List<RawFilteredPair<Text>> ps = new ArrayList<>();
		for (String p : pages) ps.add(page(p));
		st2.set(DataComponentTypes.WRITTEN_BOOK_CONTENT,
				new WrittenBookContentComponent(RawFilteredPair.of("Королевская правда №" + st.newsIssue), "Минправды", 0, ps, true));
		st2.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(7704));
		NbtCompound tag = new NbtCompound();
		tag.putString("pizdec", "newspaper");
		tag.putInt("issue", st.newsIssue);
		st2.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return st2;
	}

	/** Свежий номер: гостям в номер (в сундук или в руки), остальное — стопкой в лобби. */
	public static void deliver(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		st.newsIssue++;
		st.markDirty();
		ServerWorld w = s.getOverworld();
		long now = w.getTime();
		int delivered = 0;
		for (Map.Entry<Integer, PizdecState.Rental> e : st.hotel.entrySet()) {
			if (e.getValue().until <= now) continue;
			ItemStack paper = newspaper(s);
			BlockPos chest = Castle.roomChest(st, e.getKey());
			boolean put = false;
			if (chest != null) {
				BlockEntity be = w.getBlockEntity(chest);
				if (be instanceof Inventory inv) put = insert(inv, paper);
			}
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(e.getValue().who);
			if (!put && p != null) {
				p.getInventory().offerOrDrop(paper);
				put = true;
			}
			if (put) delivered++;
			if (p != null) {
				U.msg(p, U.t("📰 В ваш номер доставлен свежий выпуск «Королевской правды» №" + st.newsIssue
						+ (chest != null ? " (в сундуке)." : "."), Formatting.GRAY));
			}
		}
		// стопка в лобби
		BlockEntity be = w.getBlockEntity(Castle.lobbyNewsstand(st));
		if (be instanceof Inventory inv) {
			for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
			for (int i = 0; i < 6; i++) insert(inv, newspaper(s));
		}
		breaking = null;
		PizdecMod.LOG.info("Газета №{} доставлена: {} номеров", st.newsIssue, delivered);
	}

	private static boolean insert(Inventory inv, ItemStack st) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isEmpty()) {
				inv.setStack(i, st);
				inv.markDirty();
				return true;
			}
		}
		return false;
	}
}
