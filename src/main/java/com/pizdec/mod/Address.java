package com.pizdec.mod;

import java.util.Map;
import java.util.UUID;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Прописка. Ставишь табличку у дома, встаёшь рядом и пишешь /adres. Адрес попадает в реестр замка,
 * и налоговая будет приезжать именно туда. Без прописки — тюрьма или война.
 */
public final class Address {
	private Address() {}

	public static PizdecState.Address of(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).addresses.get(p.getUuid());
	}

	/** Цела ли табличка по адресу. Если нет — адрес аннулируется. */
	public static boolean valid(MinecraftServer s, UUID id) {
		PizdecState st = PizdecState.get(s);
		PizdecState.Address a = st.addresses.get(id);
		if (a == null) return false;
		ServerWorld w = s.getOverworld();
		boolean ok = w.getBlockState(new BlockPos(a.x, a.y, a.z)).isIn(BlockTags.ALL_SIGNS);
		if (!ok) {
			st.addresses.remove(id);
			st.markDirty();
		}
		return ok;
	}

	public static void register(ServerPlayerEntity p) {
		if (p.getServerWorld() != p.getServer().getOverworld()) {
			U.msg(p, U.t("🏠 Прописка только в королевстве (в обычном мире).", Formatting.RED));
			return;
		}
		if (Jail.busy(p)) {
			U.msg(p, U.t("🏠 Камеру и окоп пропиской не считаем.", Formatting.RED));
			return;
		}
		ServerWorld w = p.getServerWorld();
		BlockPos c = p.getBlockPos();
		BlockPos found = null;
		for (int dx = -4; dx <= 4 && found == null; dx++) {
			for (int dy = -2; dy <= 3 && found == null; dy++) {
				for (int dz = -4; dz <= 4 && found == null; dz++) {
					BlockPos b = new BlockPos(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					if (w.getBlockState(b).isIn(BlockTags.ALL_SIGNS)) found = b;
				}
			}
		}
		if (found == null) {
			U.msg(p, U.t("🏠 Рядом нет таблички. Поставь табличку у своего дома (напиши на ней что угодно) "
					+ "и введи /adres, стоя рядом.", Formatting.RED));
			return;
		}
		PizdecState st = PizdecState.get(p.getServer());
		if (st.castleBuilt && Castle.inside(st, found) || Prison.inside(st, found) || Court.inside(st, found)) {
			U.msg(p, U.t("🏠 Прописаться в королевских зданиях? Наглость. Но смешно. Нет.", Formatting.RED));
			return;
		}
		String name = signText(w, found);
		if (name.isBlank()) name = "Дом " + p.getName().getString();
		boolean had = st.addresses.containsKey(p.getUuid());
		st.addresses.put(p.getUuid(), new PizdecState.Address(found.getX(), found.getY(), found.getZ(), name));
		st.names.put(p.getUuid(), p.getName().getString());
		st.markDirty();
		U.title(p, U.t("🏠 ПРОПИСКА ОФОРМЛЕНА", Formatting.GREEN, Formatting.BOLD), U.t("«" + name + "»", Formatting.YELLOW));
		U.msg(p, U.t("🏠 Адрес «" + name + "» (" + U.coords(found.getX(), found.getY(), found.getZ()) + ") внесён в реестр замка"
				+ (had ? " вместо старого" : "") + ". Налоговая теперь знает, где ты живёшь. Не ломай табличку!", Formatting.GREEN));
		U.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 1f);
	}

	private static String signText(ServerWorld w, BlockPos pos) {
		BlockEntity be = w.getBlockEntity(pos);
		if (!(be instanceof SignBlockEntity sign)) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			String line = sign.getFrontText().getMessage(i, false).getString().trim();
			if (!line.isEmpty()) {
				if (sb.length() > 0) sb.append(' ');
				sb.append(line);
			}
		}
		String s = sb.toString();
		return s.length() > 40 ? s.substring(0, 40) : s;
	}

	/** Реестр адресов замка. */
	public static void list(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (st.addresses.isEmpty()) {
			U.msg(p, U.t("📜 Реестр адресов замка пуст. Все бомжи.", Formatting.GRAY));
			return;
		}
		U.msg(p, U.t("📜 Реестр адресов замка (" + st.addresses.size() + "):", Formatting.GOLD));
		for (Map.Entry<UUID, PizdecState.Address> e : st.addresses.entrySet()) {
			PizdecState.Address a = e.getValue();
			String who = st.names.getOrDefault(e.getKey(), "неизвестный");
			U.msg(p, U.t("  • " + who + " — «" + a.name + "» " + U.coords(a.x, a.y, a.z), Formatting.YELLOW));
		}
	}
}
