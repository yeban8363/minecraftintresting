package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.EulerAngle;
import net.minecraft.util.math.Vec3d;

/**
 * Обязательные государственные церемонии: военные парады, гимн (стоять смирно), присяга у статуи.
 * И Главлит — цензура чата.
 */
public final class Ceremonies {
	public static final String PARADE_TAG = "pizdec_parade";
	public static final int PARADE_EVERY = 20 * 60 * 15, ANTHEM_EVERY = 20 * 60 * 10;

	private static final String[] ANTHEM = {
			"🎵 Гимн Королевства (слова и музыка — Его Величество):",
			"   Славься, корона, налоги и трон,",
			"   Карета быстрее, чем ветер и сон!",
			"   Указ за указом — нам радость одна:",
			"   Король наш навеки — и точка, страна!"};
	private static final float[] MELODY = {1.0f, 1.19f, 1.33f, 1.5f, 1.33f, 1.19f, 1.0f, 0.89f, 1.0f, 1.19f, 1.5f, 1.78f,
			1.5f, 1.33f, 1.19f, 1.0f};
	/** Главлит: слова, которых в королевстве нет. */
	private static final String[] CENSORED = {"дефицит", "очеред", "коррупц", "взятк", "откат", "распил", "фальсиф", "вброс",
			"цензур", "репресс", "тиран", "диктат", "инфляц", "кризис", "голый король", "нищет", "бедност"};

	private static int paradeTimer = PARADE_EVERY - 20 * 60 * 7, anthemTimer = ANTHEM_EVERY - 20 * 60 * 4;
	private static int anthemLeft;
	private static final Map<UUID, Vec3d> anthemPos = new HashMap<>();
	private static final Map<UUID, Integer> oath = new HashMap<>();

	private Ceremonies() {}

	private static boolean inDistrict(PizdecState st, ServerPlayerEntity p) {
		return p.getServerWorld() == p.getServer().getOverworld()
				&& p.squaredDistanceTo(Castle.courtyard(st)) < Propaganda.RADIUS * Propaganda.RADIUS;
	}

	// ================================================================== парад

	private static final class Marcher {
		final Entity e;
		final double col, row;

		Marcher(Entity e, double col, double row) {
			this.e = e;
			this.col = col;
			this.row = row;
		}
	}

	private static final List<Marcher> parade = new ArrayList<>();
	private static boolean spawning;
	private static double paradeZ;
	private static int paradeStage, paradePause, paradeAge;

	private static ArmorStandEntity soldier(ServerWorld w, boolean bannerman) {
		ArmorStandEntity a = EntityType.ARMOR_STAND.create(w);
		if (a == null) return null;
		a.setShowArms(true);
		a.setHideBasePlate(true);
		a.setNoGravity(true);
		a.setInvulnerable(true);
		a.equipStack(EquipmentSlot.HEAD, new ItemStack(bannerman ? Items.GOLDEN_HELMET : Items.IRON_HELMET));
		a.equipStack(EquipmentSlot.CHEST, new ItemStack(bannerman ? Items.GOLDEN_CHESTPLATE : Items.IRON_CHESTPLATE));
		a.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		a.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		a.equipStack(EquipmentSlot.MAINHAND, new ItemStack(bannerman ? Items.RED_BANNER : Items.IRON_SWORD));
		a.addCommandTag(PARADE_TAG);
		return a;
	}

	public static void startParade(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt || !parade.isEmpty()) return;
		ServerWorld w = s.getOverworld();
		st.paradeNo++;
		st.markDirty();
		paradeZ = st.cz - 26;
		paradeStage = 0;
		paradePause = 0;
		paradeAge = 0;
		spawning = true;
		// почётный караул — два голема впереди
		for (double col : new double[] {-1.0, 1.0}) {
			IronGolemEntity g = EntityType.IRON_GOLEM.create(w);
			if (g == null) continue;
			g.setAiDisabled(true);
			g.setInvulnerable(true);
			g.setCustomName(U.t("Почётный караул", Formatting.GOLD));
			g.addCommandTag(PARADE_TAG);
			g.refreshPositionAndAngles(st.cx + 28.5 + col, st.cy, paradeZ, 0f, 0f);
			w.spawnEntity(g);
			parade.add(new Marcher(g, col, 0));
		}
		// колонна: знаменосцы и 3 шеренги гвардейцев
		for (int row = 1; row <= 4; row++) {
			for (int c = 0; c < 4; c++) {
				ArmorStandEntity a = soldier(w, row == 1);
				if (a == null) continue;
				double col = -1.5 + c;
				a.refreshPositionAndAngles(st.cx + 28.5 + col, st.cy, paradeZ - row * 1.6, 0f, 0f);
				w.spawnEntity(a);
				parade.add(new Marcher(a, col, row * 1.6 + 1));
			}
		}
		spawning = false;
		U.say(s, U.t("🎖 ВОЕННЫЙ ПАРАД №" + st.paradeNo + " в честь Его Величества! Колонна входит через Арку Вечной Благодарности. "
				+ "Все к статуе короля! Бюджетникам — явка обязательна.", Formatting.GOLD, Formatting.BOLD));
		U.soundAll(s, SoundEvents.EVENT_RAID_HORN.value(), 0.8f, 1.2f);
	}

	private static void tickParade(MinecraftServer s, PizdecState st) {
		if (parade.isEmpty()) return;
		paradeAge++;
		ServerWorld w = s.getOverworld();
		double stopZ = st.cz + 11.5;
		if (paradePause > 0) {
			paradePause--;
			if (paradePause % 20 == 0) salute(w, st);
			if (paradePause == 0) paradeStage = 2;
		} else if (paradeStage == 0) {
			paradeZ += 0.12;
			if (paradeZ >= stopZ) {
				paradeZ = stopZ;
				paradeStage = 1;
				paradePause = 20 * 12;
				reviewStand(s, st);
			}
		} else {
			paradeZ -= 0.14;
			if (paradeZ < st.cz - 30 || paradeAge > 20 * 120) {
				for (Marcher m : parade) m.e.discard();
				parade.clear();
				return;
			}
		}
		float yaw = paradeStage == 2 ? 180f : 0f;
		boolean step = paradePause == 0 && (paradeAge / 6) % 2 == 0;
		for (Marcher m : parade) {
			if (m.e.isRemoved()) continue;
			double z = paradeZ - m.row;
			m.e.refreshPositionAndAngles(st.cx + 28.5 + m.col, st.cy, z, yaw, 0f);
			m.e.setYaw(yaw);
			if (m.e instanceof ArmorStandEntity a) {
				a.setBodyYaw(yaw);
				float leg = paradePause > 0 ? 0f : step ? 25f : -25f;
				a.setLeftLegRotation(new EulerAngle(leg, 0, 0));
				a.setRightLegRotation(new EulerAngle(-leg, 0, 0));
				a.setLeftArmRotation(new EulerAngle(-leg * 0.6f, 0, 0));
				a.setRightArmRotation(new EulerAngle(paradePause > 0 ? -150f : -20f, 0, 0));
			} else if (m.e instanceof IronGolemEntity g) {
				g.setBodyYaw(yaw);
				g.setHeadYaw(yaw);
			}
		}
	}

	private static void salute(ServerWorld w, PizdecState st) {
		Vec3d c = Propaganda.statue(st);
		ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
		rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1, List.of(new FireworkExplosionComponent(
				U.pick(FireworkExplosionComponent.Type.LARGE_BALL, FireworkExplosionComponent.Type.STAR,
						FireworkExplosionComponent.Type.BURST),
				IntList.of(0xC81E28, 0xFFD700), IntList.of(0xFFFFFF), true, true))));
		for (int i = 0; i < 2; i++) {
			FireworkRocketEntity f = new FireworkRocketEntity(w, c.x + U.R.nextGaussian() * 6, c.y + 2, c.z + U.R.nextGaussian() * 6,
					rocket.copy());
			w.spawnEntity(f);
		}
	}

	/** Колонна у статуи: считаем, кто пришёл. */
	private static void reviewStand(MinecraftServer s, PizdecState st) {
		Vec3d c = Propaganda.statue(st);
		int present = 0;
		for (ServerPlayerEntity p : U.players(s)) {
			if (p.isSpectator() || Jail.busy(p)) continue;
			boolean here = p.getServerWorld() == s.getOverworld() && p.squaredDistanceTo(c) < 40 * 40;
			if (here) {
				present++;
				Money.give(p, 5, "за участие в параде");
				SecretPolice.add(p, -2, "присутствовал на параде");
				U.title(p, U.t("🎖 УРА-А-А!", Formatting.GOLD, Formatting.BOLD), U.t("Колонна приветствует короля", Formatting.YELLOW));
			} else if (Politics.budgetWorker(p)) {
				Money.fine(p, 15, "неявка бюджетника на парад");
				SecretPolice.add(p, 3, "не пришёл на парад");
			}
		}
		Politics.event(s, 0.5);
		U.say(s, U.t("🎖 Колонна остановилась у статуи. Салют! На трибунах — " + (present + U.between(12000, 25000))
				+ " ликующих граждан (по данным организаторов).", Formatting.GOLD));
		News.breaking(s, "Грандиозный парад №" + st.paradeNo + ": вся страна в едином порыве. Иностранные гости потрясены.");
	}

	public static void onEntityLoad(Entity e) {
		if (spawning) return;
		if (e.getCommandTags().contains(PARADE_TAG)) {
			for (Marcher m : parade) if (m.e == e) return;
			e.discard();
		}
	}

	// ================================================================== гимн

	public static void anthem(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (anthemLeft > 0 || !st.castleBuilt) return;
		anthemPos.clear();
		for (ServerPlayerEntity p : U.players(s)) {
			if (!inDistrict(st, p) || Jail.busy(p) || p.isCreative() || p.isSpectator()) continue;
			anthemPos.put(p.getUuid(), p.getPos());
			U.title(p, U.t("🎵 ГИМН КОРОЛЕВСТВА", Formatting.GOLD, Formatting.BOLD), U.t("Стой смирно 8 секунд. Не двигайся.",
					Formatting.YELLOW));
			for (String line : ANTHEM) U.msg(p, U.t(line, Formatting.GOLD));
		}
		if (!anthemPos.isEmpty()) anthemLeft = 20 * 8;
	}

	private static void tickAnthem(MinecraftServer s) {
		if (anthemLeft <= 0) return;
		int i = (20 * 8 - anthemLeft) / 10;
		if ((20 * 8 - anthemLeft) % 10 == 0 && i < MELODY.length) {
			for (UUID id : anthemPos.keySet()) {
				ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
				if (p != null) U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.8f, MELODY[i]);
			}
		}
		if (--anthemLeft > 0) return;
		for (Map.Entry<UUID, Vec3d> e : anthemPos.entrySet()) {
			ServerPlayerEntity p = s.getPlayerManager().getPlayer(e.getKey());
			if (p == null || Jail.busy(p)) continue;
			Vec3d a = e.getValue(), b = p.getPos();
			double moved = Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.z - b.z, 2));
			if (moved > 1.2 || Math.abs(a.y - b.y) > 1.0) {
				Money.fine(p, 5, "неуважение к гимну (двигался)");
				SecretPolice.add(p, 4, "не стоял смирно во время гимна");
			} else {
				U.bar(p, U.t("🎵 Ты стоял смирно. Король доволен. Тайная полиция — тоже.", Formatting.GOLD));
			}
		}
		anthemPos.clear();
	}

	// ================================================================== присяга

	public static void oath(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		Vec3d c = Propaganda.statue(st);
		if (p.squaredDistanceTo(c.x, p.getY(), c.z) > 16 * 16) {
			U.msg(p, U.t("🗿 Присягу приносят у статуи короля (во дворе замка).", Formatting.GRAY));
			return;
		}
		int now = p.getServer().getTicks();
		if (now - oath.getOrDefault(p.getUuid(), -999999) < 20 * 60 * 20) {
			U.msg(p, U.t("🗿 Вы уже присягали недавно. Присяга действует 20 минут, потом — снова.", Formatting.GRAY));
			return;
		}
		oath.put(p.getUuid(), now);
		U.msg(p, U.t("🗿 «Я, " + p.getName().getString() + ", клянусь платить налоги, любить короля, стоять смирно при гимне, "
				+ "кланяться портретам и не замечать дефицита. Если нарушу — пусть меня заберёт карета.»", Formatting.GOLD));
		U.title(p, U.t("🗿 ПРИСЯГА ПРИНЯТА", Formatting.GOLD, Formatting.BOLD), U.t("Король принимает вашу верность", Formatting.YELLOW));
		U.sound(p, SoundEvents.BLOCK_BELL_USE, 1f, 0.8f);
		Money.give(p, 5, "присяга на верность");
		SecretPolice.add(p, -6, "принёс присягу у статуи");
	}

	public static boolean sworn(ServerPlayerEntity p) {
		return p.getServer().getTicks() - oath.getOrDefault(p.getUuid(), -999999) < 20 * 60 * 20;
	}

	// ================================================================== Главлит

	/** Цензурированная версия текста или null, если всё чисто. */
	public static String censor(String text) {
		String low = text.toLowerCase();
		StringBuilder out = new StringBuilder(text);
		boolean hit = false;
		for (String w : CENSORED) {
			int i = low.indexOf(w);
			while (i >= 0) {
				hit = true;
				int end = i + w.length();
				while (end < low.length() && Character.isLetter(low.charAt(end))) end++;
				int start = i;
				while (start > 0 && Character.isLetter(low.charAt(start - 1))) start--;
				for (int k = start; k < end; k++) out.setCharAt(k, '█');
				i = low.indexOf(w, end);
			}
		}
		if (Laws.is("glavlit_strict")) {
			String[] words = out.toString().split(" ");
			for (int k = 2; k < words.length; k += 3) words[k] = "█".repeat(Math.max(1, words[k].length()));
			return String.join(" ", words) + " ";
		}
		return hit ? out.toString() : null;
	}

	// ================================================================== тик и команды

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return;
		tickParade(s, st);
		tickAnthem(s);
		if (++paradeTimer >= PARADE_EVERY) {
			paradeTimer = 0;
			if (!U.players(s).isEmpty()) startParade(s);
		}
		if (paradeTimer == PARADE_EVERY - 20 * 60) {
			U.say(s, U.t("🎖 Через минуту — военный парад у статуи короля! Явка граждан приветствуется. Бюджетников — обязательна.",
					Formatting.GOLD));
		}
		if (++anthemTimer >= ANTHEM_EVERY) {
			anthemTimer = 0;
			anthem(s);
		}
	}

	public static void commands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("prisyaga").executes(c -> {
			oath(c.getSource().getPlayerOrThrow());
			return 1;
		}));
		d.register(CommandManager.literal("parad").executes(c -> {
			ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
			U.msg(p, U.t("🎖 Парад — каждые 15 минут, через Арку Вечной Благодарности к статуе короля. До парада: "
					+ Math.max(0, (PARADE_EVERY - paradeTimer) / 20) + " сек. Гимн — каждые 10 минут (стоять смирно). "
					+ "Присяга у статуи — /prisyaga.", Formatting.GOLD));
			return 1;
		}));
	}
}
