package com.pizdec.mod;

import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/**
 * Валюта мода — «бабло». Хранится в обычном скорборде, поэтому сохраняется вместе с миром
 * и сразу видна справа на экране.
 */
public final class Money {
	public static final String OBJ = "pizdec_money";
	public static final int START = 100;

	private Money() {}

	public static ScoreboardObjective obj(MinecraftServer s) {
		ServerScoreboard sb = s.getScoreboard();
		ScoreboardObjective o = sb.getNullableObjective(OBJ);
		if (o == null) {
			o = sb.addObjective(OBJ, ScoreboardCriterion.DUMMY,
					U.t("💰 Бабло", Formatting.GOLD, Formatting.BOLD),
					ScoreboardCriterion.RenderType.INTEGER, true, null);
			sb.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, o);
		}
		return o;
	}

	private static ScoreAccess score(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		return s.getScoreboard().getOrCreateScore(p, obj(s));
	}

	/** Есть ли у игрока вообще запись в банке (для стартового капитала). */
	public static boolean hasAccount(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		return s.getScoreboard().getScore(p, obj(s)) != null;
	}

	public static int get(ServerPlayerEntity p) {
		return score(p).getScore();
	}

	public static void set(ServerPlayerEntity p, int v) {
		score(p).setScore(v);
	}

	public static void add(ServerPlayerEntity p, int amount) {
		score(p).incrementScore(amount);
	}

	/** Начислить с сообщением в action bar. */
	public static void give(ServerPlayerEntity p, int amount, String why) {
		add(p, amount);
		U.bar(p, U.t("+" + amount + " бабла: " + why, Formatting.GREEN));
		U.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
	}

	/** Попробовать заплатить. Если денег нет — false и ничего не списывается. */
	public static boolean pay(ServerPlayerEntity p, int amount, String what) {
		if (p.isCreative()) return true;
		if (get(p) >= amount) {
			add(p, -amount);
			U.bar(p, U.t("−" + amount + " бабла за " + what, Formatting.RED));
			return true;
		}
		U.bar(p, U.t("Нищеброд! Нужно " + amount + " бабла за " + what + " (у тебя " + get(p) + ")",
				Formatting.DARK_RED));
		U.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.8f);
		return false;
	}

	/** Списать принудительно, можно уйти в минус (долг). */
	public static void fine(ServerPlayerEntity p, int amount, String why) {
		add(p, -amount);
		U.bar(p, U.t("ШТРАФ −" + amount + ": " + why, Formatting.RED, Formatting.BOLD));
		U.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.6f);
	}
}
