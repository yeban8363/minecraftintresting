package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.Heightmap;

/** Всякие мелкие помогалки. */
public final class U {
	public static final Random R = new Random();

	private U() {}

	public static MutableText t(String s) {
		return Text.literal(s);
	}

	public static MutableText t(String s, Formatting... f) {
		return Text.literal(s).formatted(f);
	}

	/** Все живые игроки, которые не в спектаторе. */
	public static List<ServerPlayerEntity> players(MinecraftServer s) {
		List<ServerPlayerEntity> out = new ArrayList<>();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (!p.isSpectator() && p.isAlive()) out.add(p);
		}
		return out;
	}

	/** Живые игроки на свободе (не в тюрьме и не в карете). */
	public static List<ServerPlayerEntity> free(MinecraftServer s) {
		List<ServerPlayerEntity> out = new ArrayList<>();
		for (ServerPlayerEntity p : players(s)) {
			if (!Jail.busy(p)) out.add(p);
		}
		return out;
	}

	public static void say(MinecraftServer s, Text text) {
		s.getPlayerManager().broadcast(text, false);
	}

	public static void bar(ServerPlayerEntity p, Text text) {
		p.sendMessage(text, true);
	}

	public static void msg(ServerPlayerEntity p, Text text) {
		p.sendMessage(text, false);
	}

	public static void title(ServerPlayerEntity p, Text title, Text sub) {
		p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 50, 15));
		p.networkHandler.sendPacket(new SubtitleS2CPacket(sub));
		p.networkHandler.sendPacket(new TitleS2CPacket(title));
	}

	public static void titleAll(MinecraftServer s, Text title, Text sub) {
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) title(p, title, sub);
	}

	public static void sound(ServerPlayerEntity p, SoundEvent e, float vol, float pitch) {
		p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), e, SoundCategory.MASTER, vol, pitch);
	}

	public static void sound(ServerPlayerEntity p, RegistryEntry<SoundEvent> e, float vol, float pitch) {
		sound(p, e.value(), vol, pitch);
	}

	public static void soundAll(MinecraftServer s, SoundEvent e, float vol, float pitch) {
		for (ServerPlayerEntity p : players(s)) sound(p, e, vol, pitch);
	}

	public static void soundAll(MinecraftServer s, RegistryEntry<SoundEvent> e, float vol, float pitch) {
		soundAll(s, e.value(), vol, pitch);
	}

	@SafeVarargs
	public static <T> T pick(T... arr) {
		return arr[R.nextInt(arr.length)];
	}

	public static <T> T pick(List<T> list) {
		return list.get(R.nextInt(list.size()));
	}

	public static boolean chance(double c) {
		return R.nextDouble() < c;
	}

	public static int between(int min, int max) {
		return min + R.nextInt(max - min + 1);
	}

	/** Спавнит сущность. Возвращает null, если что-то пошло не так. */
	public static <T extends Entity> T spawn(EntityType<T> type, ServerWorld w, double x, double y, double z) {
		T e = type.create(w);
		if (e == null) return null;
		e.refreshPositionAndAngles(x, y, z, R.nextFloat() * 360f, 0f);
		w.spawnEntity(e);
		return e;
	}

	/** Спавнит сущность на поверхности рядом с игроком. */
	public static <T extends Entity> T spawnNear(EntityType<T> type, ServerPlayerEntity p, int radius) {
		ServerWorld w = p.getServerWorld();
		int x = p.getBlockX() + between(-radius, radius);
		int z = p.getBlockZ() + between(-radius, radius);
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (Math.abs(y - p.getBlockY()) > 12) y = p.getBlockY();
		return spawn(type, w, x + 0.5, y, z + 0.5);
	}

	public static String coords(int x, int y, int z) {
		return x + " " + y + " " + z;
	}
}
