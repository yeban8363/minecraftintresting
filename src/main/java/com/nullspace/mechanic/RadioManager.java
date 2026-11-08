package com.nullspace.mechanic;

import com.nullspace.item.RadioItem;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Эфир радиоприёмника. Шесть частот:
 *   88.1  — шум;
 *   91.4  — пеленг: писк чаще, когда смотришь в сторону ближайшего EXIT, и выше, чем он ближе;
 *   95.0  — твой голос: обрывки записей, которых ты не делал;
 *   99.9  — музыка торгового центра, искажённая;
 *  103.3  — номерная станция: диктует координаты ближайшего EXIT;
 *  107.7  — «Дом»: птицы и ветер из обычного мира. Возвращает Стабильность.
 */
public final class RadioManager {
	public static final int STATIONS = 6;
	private static final String[] FREQS = {"88.1", "91.4", "95.0", "99.9", "103.3", "107.7"};
	private static final String[] KEYS = {"noise", "beacon", "voice", "music", "numbers", "home"};
	private static final int VOICE_LINES = 10;

	private static final class State {
		int freq = -1;
		long exitCachedAt = Long.MIN_VALUE;
		BlockPos exit;
		long nextStatic, nextBeep, nextVoice, nextMusic, nextHome, nextNumber, nextBars;
		Deque<Text> numbers;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	public static MutableText stationLabel(int freq) {
		return Text.translatable("radio.nullspace.label", FREQS[freq], Text.translatable("radio.nullspace.station." + KEYS[freq]))
				.formatted(Formatting.AQUA);
	}

	private static ItemStack activeRadio(ServerPlayerEntity player) {
		for (ItemStack stack : new ItemStack[]{player.getMainHandStack(), player.getOffHandStack()}) {
			if (stack.getItem() instanceof RadioItem && RadioItem.isOn(stack) && !RadioItem.isEmpty(stack)) return stack;
		}
		return null;
	}

	public static void tick(MinecraftServer server) {
		long now = server.getTicks();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			ItemStack radio = activeRadio(player);
			if (radio == null || player.isSpectator()) {
				STATES.remove(player.getUuid());
				continue;
			}
			State s = STATES.computeIfAbsent(player.getUuid(), id -> new State());
			int f = RadioItem.freq(radio);
			if (f != s.freq) {
				s.freq = f;
				s.nextBeep = s.nextVoice = s.nextMusic = s.nextHome = s.nextNumber = now + 10;
				s.numbers = null;
			}

			if (now >= s.nextStatic) {
				player.playSoundToPlayer(ModSounds.RADIO_STATIC, SoundCategory.PLAYERS, f == 0 ? 0.7f : 0.15f, 1.0f);
				s.nextStatic = now + 40;
			}

			switch (f) {
				case 0 -> noise(player, now);
				case 1 -> beacon(player, s, now);
				case 2 -> voice(player, s, now);
				case 3 -> music(player, s, now);
				case 4 -> numbers(player, s, now);
				case 5 -> home(player, s, now);
			}
		}
	}

	private static BlockPos exit(ServerPlayerEntity player, State s, long now) {
		if (now - s.exitCachedAt > 100) {
			s.exit = ExitLocator.find(player.getServerWorld(), player.getBlockPos());
			s.exitCachedAt = now;
		}
		return s.exit;
	}

	// ---------- станции ----------

	private static void noise(ServerPlayerEntity player, long now) {
		// В шуме иногда проступает голос — только когда реальность уже трещит.
		if (now % 20 == 0 && StabilityManager.get(player) < 30 && player.getRandom().nextFloat() < 0.03f) {
			sayVoice(player);
		}
	}

	private static void beacon(ServerPlayerEntity player, State s, long now) {
		BlockPos exit = exit(player, s, now);
		if (exit == null) {
			if (now >= s.nextBars) {
				player.sendMessage(stationLabel(1).append(Text.translatable("radio.nullspace.no_signal").formatted(Formatting.DARK_GRAY)), true);
				s.nextBars = now + 40;
			}
			return;
		}
		Vec3d to = Vec3d.ofCenter(exit).subtract(player.getPos());
		Vec3d flat = new Vec3d(to.x, 0, to.z);
		double dist = flat.length();
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d lookFlat = new Vec3d(look.x, 0, look.z);
		double dot = dist < 1.0E-3 || lookFlat.lengthSquared() < 1.0E-4 ? 1.0
				: lookFlat.normalize().dotProduct(flat.multiply(1.0 / dist));

		if (now >= s.nextBeep) {
			float pitch = (float) (0.7 + 1.1 * (1.0 - Math.min(dist, 400.0) / 400.0));
			player.playSoundToPlayer(ModSounds.RADIO_BEEP, SoundCategory.PLAYERS, 0.6f, pitch);
			s.nextBeep = now + 6 + (long) ((1.0 - dot) / 2.0 * 34);
		}
		if (now >= s.nextBars) {
			int bars = (int) Math.round((dot + 1.0) / 2.0 * 5);
			String strength = "▮".repeat(bars) + "▯".repeat(5 - bars);
			player.sendMessage(stationLabel(1).append(Text.literal("  " + strength).formatted(Formatting.GREEN)), true);
			s.nextBars = now + 10;
		}
	}

	private static void voice(ServerPlayerEntity player, State s, long now) {
		if (now < s.nextVoice) return;
		sayVoice(player);
		s.nextVoice = now + 160 + player.getRandom().nextInt(120);
	}

	private static void sayVoice(ServerPlayerEntity player) {
		int line = player.getRandom().nextInt(VOICE_LINES);
		player.sendMessage(Text.translatable("radio.nullspace.voice." + line, player.getGameProfile().getName())
				.formatted(Formatting.GRAY, Formatting.ITALIC), false);
		player.playSoundToPlayer(ModSounds.RADIO_VOICE, SoundCategory.PLAYERS, 0.8f, 0.95f + player.getRandom().nextFloat() * 0.1f);
	}

	private static void music(ServerPlayerEntity player, State s, long now) {
		if (now < s.nextMusic) return;
		player.playSoundToPlayer(ModSounds.MALL_MUSIC, SoundCategory.PLAYERS, 0.5f, 0.74f);
		s.nextMusic = now + 190;
	}

	private static void numbers(ServerPlayerEntity player, State s, long now) {
		if (now < s.nextNumber) return;
		if (s.numbers == null || s.numbers.isEmpty()) {
			s.numbers = buildNumbers(exit(player, s, now));
		}
		Text token = s.numbers.pollFirst();
		player.sendMessage(Text.literal("… ").append(token).append(" …").formatted(Formatting.DARK_AQUA, Formatting.ITALIC), false);
		player.playSoundToPlayer(ModSounds.RADIO_BEEP, SoundCategory.PLAYERS, 0.4f, 1.7f);
		s.nextNumber = now + (s.numbers.isEmpty() ? 160 : 24);
	}

	private static Deque<Text> buildNumbers(BlockPos exit) {
		Deque<Text> out = new ArrayDeque<>();
		if (exit == null) {
			for (int i = 0; i < 3; i++) out.add(Text.translatable("radio.nullspace.digit.0"));
			return out;
		}
		addAxis(out, "x", exit.getX());
		addAxis(out, "z", exit.getZ());
		return out;
	}

	private static void addAxis(Deque<Text> out, String axis, int value) {
		out.add(Text.translatable("radio.nullspace.axis_" + axis));
		if (value < 0) out.add(Text.translatable("radio.nullspace.minus"));
		for (char c : Integer.toString(Math.abs(value)).toCharArray()) {
			out.add(Text.translatable("radio.nullspace.digit." + c));
		}
	}

	private static void home(ServerPlayerEntity player, State s, long now) {
		if (now >= s.nextHome) {
			player.playSoundToPlayer(ModSounds.RADIO_HOME, SoundCategory.PLAYERS, 0.6f, 1.0f);
			s.nextHome = now + 120;
		}
		if (now % 20 == 0 && NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
			StabilityManager.add(player, 0.5f);
		}
	}

	public static void forget(ServerPlayerEntity player) {
		STATES.remove(player.getUuid());
	}

	private RadioManager() {
	}
}
