package com.pizdec.mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * Новая пропаганда: громкоговорители в королевском квартале, «Индекс любви к королю» над головой,
 * Минута славы с обязательным поклоном и статуя, которая замечает поклоны.
 */
public final class Propaganda {
	public static final int SPEAKER_PERIOD = 20 * 60;
	public static final int GLORY_PERIOD = 20 * 60 * 6;
	public static final double RADIUS = 220;

	public static boolean record;
	private static double love = 98.7;
	private static double drop;
	private static ServerBossBar bar;
	private static int speakerTimer = 20 * 20, gloryTimer;
	private static int gloryLeft;
	private static final Set<UUID> gloryAudience = new HashSet<>();
	private static final Set<UUID> bowed = new HashSet<>();
	private static final Map<UUID, Integer> statueBow = new HashMap<>();
	private static final Map<UUID, Integer> statueHello = new HashMap<>();

	private static final String[] SPEAKER = {
			"Граждане! Бдительность — наше всё. О подозрительном сообщайте: /stuknut.",
			"Граница на замке. Ключ — у короля. Король — у нас.",
			"Сегодня Его Величество лично посадил дерево. Дерево благодарно.",
			"За границей нет налогов. Но нет и короля. Подумайте, что страшнее.",
			"Статую Его Величества видно из космоса. Космос — тоже наш.",
			"«Вкусно и Точка»: комбо «Налоговый» — сытно и патриотично!",
			"Тайной полиции не существует. Она вас слышит.",
			"Индекс любви к королю снова превысил 98%. Остальные 2% — уже работают над собой.",
			"Внимание! Иностранные туристы — гости. Гости — это временно. Будьте вежливы. И бдительны.",
			"Портрет короля в каждом учреждении — не украшение, а напоминание.",
			"Подполье «Свободная Картошка» — это миф. Мифы караются по закону.",
			"Король не спит. Король бодрствует за вас.",
			"Каждый уплаченный налог — кирпич в фундаменте процветания. Кирпичи принимаются и натурой.",
			"Товарищи! Паспорт — лучший друг гражданина. Проверьте, при вас ли он.",
			"Сегодня отличная погода. Благодарите короля.",
			"Выборы — праздник демократии. Результат праздника известен заранее, чтобы никто не волновался.",
			"Карета — это не наказание. Это бесплатный транспорт.",
			"Поклон при виде портрета укрепляет позвоночник и карьеру.",
			"Слухи о побегах за границу преувеличены. Шанс — 0,5%. Пограничники — 100%.",
			"Не забывайте улыбаться. Улыбка — бесплатна (пока).",
			"Так называемая «Демократовка» — рассадник хаоса. Там даже мэра ругают. Вслух!",
			"В «Демократовке» больница бесплатная, потому что лечить там некого: все сбежали обратно. (Нет.)",
			"Гражданство «Демократовки» — это измена. Карета ждёт каждого «демократа» на границе.",
			"«Свободный голос» — газета иностранных агентов. Хранение — контрабанда."};

	private Propaganda() {}

	public static void loveDrop(double d) {
		drop = Math.min(8, drop + d);
	}

	public static double loveIndex() {
		return record ? 146.0 : Math.max(90, love - drop);
	}

	private static boolean inDistrict(PizdecState st, ServerPlayerEntity p) {
		if (!st.castleBuilt || p.getServerWorld() != p.getServer().getOverworld()) return false;
		Vec3d c = Castle.courtyard(st);
		return p.squaredDistanceTo(c) < RADIUS * RADIUS || Border.nearCheckpoint(st, p.getPos(), 30) >= 0;
	}

	public static Vec3d statue(PizdecState st) {
		return new Vec3d(st.cx + 28.5, st.cy, st.cz + 18.5);
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		int now = s.getTicks();
		if (bar == null) {
			bar = new ServerBossBar(U.t("❤ Индекс любви к королю", Formatting.RED), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
		}
		if (now % 20 == 0) {
			love = Math.max(97.0, Math.min(99.9, love + (U.R.nextDouble() - 0.45) * 0.2));
			drop = Math.max(0, drop - 0.05);
			double v = loveIndex();
			bar.setPercent((float) Math.min(1.0, v / 100.0));
			bar.setName(U.t("❤ Индекс любви к королю: " + String.format(java.util.Locale.ROOT, "%.1f", v) + "%"
					+ (record ? " (официальный рекорд!)" : drop > 1 ? " (временные трудности)" : " (данные ЦИК)"),
					drop > 1 ? Formatting.GOLD : Formatting.RED));
			for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
				boolean show = inDistrict(st, p) && !Jail.jailed(p) && !Border.abroad(p);
				if (show) bar.addPlayer(p);
				else bar.removePlayer(p);
			}
		}
		// громкоговорители
		if (++speakerTimer >= SPEAKER_PERIOD) {
			speakerTimer = 0;
			String line = U.chance(0.3) ? News.headline(s) : U.pick(SPEAKER);
			ServerWorld w = s.getOverworld();
			Vec3d c = statue(st);
			w.playSound(null, c.x, c.y + 10, c.z, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 4f, 0.8f);
			for (ServerPlayerEntity p : U.players(s)) {
				if (!inDistrict(st, p) || Border.abroad(p)) continue;
				U.msg(p, U.t("📢 Громкоговоритель: «" + line + "»", Formatting.GOLD));
			}
		}
		// Минута славы
		if (++gloryTimer >= GLORY_PERIOD) {
			gloryTimer = 0;
			minuteOfGlory(s);
		}
		if (gloryLeft > 0) {
			for (UUID id : gloryAudience) {
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
				if (p != null && p.isSneaking()) bowed.add(id);
			}
			if (--gloryLeft == 0) endGlory(s);
		}
		// статуя замечает поклоны
		if (now % 10 == 0) {
			Vec3d c = statue(st);
			for (ServerPlayerEntity p : U.players(s)) {
				if (p.getServerWorld() != s.getOverworld() || p.squaredDistanceTo(c.x, p.getY(), c.z) > 16 * 16) continue;
				int hello = statueHello.getOrDefault(p.getUuid(), -99999);
				if (now - hello > 20 * 300) {
					statueHello.put(p.getUuid(), now);
					U.bar(p, U.t("🗿 Статуя Его Величества Короля Пиздец III. 25 блоков величия. Поклонись (Shift).",
							Formatting.GOLD));
				}
				if (p.isSneaking() && now - statueBow.getOrDefault(p.getUuid(), -99999) > 20 * 60) {
					statueBow.put(p.getUuid(), now);
					U.msg(p, U.t("🗿 Король заметил твой поклон. Тайная полиция — тоже. Это хорошо.", Formatting.GOLD));
					SecretPolice.add(p, -2, "поклонился статуе короля");
					love = Math.min(99.9, love + 0.02);
				}
			}
		}
	}

	public static void minuteOfGlory(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (gloryLeft > 0 || !st.castleBuilt) return;
		gloryAudience.clear();
		bowed.clear();
		for (ServerPlayerEntity p : U.players(s)) {
			if (!inDistrict(st, p) || Jail.busy(p) || p.isCreative() || p.isSpectator()) continue;
			gloryAudience.add(p.getUuid());
			U.title(p, U.t("🎺 МИНУТА СЛАВЫ", Formatting.GOLD, Formatting.BOLD),
					U.t("Поклонись королю (присядь) в течение 10 секунд!", Formatting.YELLOW));
			U.sound(p, SoundEvents.EVENT_RAID_HORN, 1f, 1.6f);
		}
		if (!gloryAudience.isEmpty()) gloryLeft = 20 * 10;
	}

	private static void endGlory(MinecraftServer s) {
		for (UUID id : gloryAudience) {
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
			if (p == null) continue;
			if (bowed.contains(id)) {
				Money.give(p, 2, "премия за лояльность (Минута славы)");
				SecretPolice.add(p, -1, "поклонился в Минуту славы");
			} else {
				U.msg(p, U.t("🎺 Минута славы окончена. Ты не поклонился. Замечено.", Formatting.DARK_GRAY));
				SecretPolice.add(p, 5, "не поклонился в Минуту славы");
				loveDrop(0.2);
			}
		}
		gloryAudience.clear();
		bowed.clear();
	}

	public static void onLeave(ServerPlayerEntity p) {
		if (bar != null) bar.removePlayer(p);
	}
}
