package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Портреты короля во всех учреждениях (и агитплакаты). Повреждение или снятие — отдельное преступление:
 * «оскорбление величества». Пропавший портрет вешают обратно, а ближайшего гражданина — в карету.
 */
public final class Portraits {
	public static final String TAG = "pizdec_portrait";
	public static final String KING = "king_portrait", EYE = "poster_eye", TAX = "poster_tax";

	private static final Map<String, Integer> missing = new HashMap<>();
	private static final Map<UUID, Integer> lastBow = new HashMap<>();
	private static final Map<UUID, Integer> nearSince = new HashMap<>();
	private static final Map<UUID, Integer> lastBowMsg = new HashMap<>();
	private static int placedCount;

	private Portraits() {}

	private static Optional<RegistryEntry.Reference<PaintingVariant>> variant(ServerWorld w, String id) {
		return w.getRegistryManager().get(RegistryKeys.PAINTING_VARIANT).getEntry(Identifier.of("pizdec", id));
	}

	private static PaintingEntity make(ServerWorld w, String id, BlockPos pos, Direction facing) {
		Optional<RegistryEntry.Reference<PaintingVariant>> v = variant(w, id);
		if (v.isEmpty()) return null;
		PaintingEntity e = new PaintingEntity(w, pos, facing, v.get());
		e.setInvulnerable(true);
		e.setSilent(true);
		e.addCommandTag(TAG);
		e.addCommandTag("pizdec_portrait_" + id);
		return e;
	}

	/**
	 * Ищет на стенах комнаты (x0..x1, z0..z1 — внутренность, y — пол) место под картину и вешает её.
	 * prefer — с каких стен пробовать в первую очередь (куда «смотрит» картина).
	 */
	static boolean hang(ServerWorld w, PizdecState st, String id, int x0, int y, int z0, int x1, int z1, Direction... prefer) {
		List<Direction> order = new ArrayList<>(List.of(prefer));
		for (Direction d : new Direction[] {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}) {
			if (!order.contains(d)) order.add(d);
		}
		for (int dy : new int[] {2, 1, 3}) {
			for (Direction f : order) {
				List<BlockPos> cands = new ArrayList<>();
				boolean alongX = f == Direction.SOUTH || f == Direction.NORTH;
				int a0 = alongX ? x0 : z0, a1 = alongX ? x1 : z1, mid = (a0 + a1) / 2;
				for (int off = 0; off <= (a1 - a0) / 2 + 1; off++) {
					for (int sgn : new int[] {1, -1}) {
						int a = mid + off * sgn;
						if (a < a0 || a > a1) continue;
						int x = alongX ? a : (f == Direction.EAST ? x0 : x1);
						int z = alongX ? (f == Direction.SOUTH ? z0 : z1) : a;
						cands.add(new BlockPos(x, y + dy, z));
						if (off == 0) break;
					}
				}
				for (BlockPos p : cands) {
					BlockPos wall = p.offset(f.getOpposite());
					var ws = w.getBlockState(wall);
					if (ws.isOf(Blocks.BLACK_CONCRETE) || ws.isOf(Blocks.CYAN_CONCRETE) || ws.isIn(net.minecraft.registry.tag.BlockTags.ALL_SIGNS)) {
						continue; // телевизоры и мониторы не закрываем
					}
					PaintingEntity e = make(w, id, p, f);
					if (e == null) return false;
					if (!e.canStayAttached()) continue;
					w.spawnEntity(e);
					st.portraitSpots.add(id + ";" + p.getX() + ";" + p.getY() + ";" + p.getZ() + ";" + f.getName());
					st.markDirty();
					placedCount++;
					return true;
				}
			}
		}
		PizdecMod.LOG.warn("Не нашлось стены для портрета {} в {}..{} {}..{}", id, x0, x1, z0, z1);
		return false;
	}

	/** Развешивает портреты по всем учреждениям (один раз). */
	public static void ensurePlaced(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.portraitsPlaced || !st.castleBuilt) return;
		ServerWorld w = s.getOverworld();
		B.w = w;
		int ox = st.cx, oy = st.cy, oz = st.cz;
		B.loadChunks(ox - 8, oz - 8, ox + Castle.SX + 8, oz + Castle.SZ + 46);
		placedCount = 0;
		// замок
		hang(w, st, KING, ox + 19, oy, oz + 29, ox + 37, oz + 41, Direction.EAST, Direction.WEST); // тронный зал
		hang(w, st, KING, ox + 19, oy, oz + 29, ox + 37, oz + 41, Direction.WEST, Direction.EAST);
		hang(w, st, KING, ox + 3, oy, oz + 7, ox + 13, oz + 13, Direction.WEST, Direction.EAST); // лобби отеля
		hang(w, st, KING, ox + 43, oy, oz + 7, ox + 53, oz + 17, Direction.SOUTH); // биржа труда
		hang(w, st, KING, ox + 43, oy, oz + 19, ox + 53, oz + 29, Direction.NORTH); // казарма
		hang(w, st, KING, ox + 43, oy, oz + 31, ox + 53, oz + 41, Direction.SOUTH); // казначейство
		hang(w, st, KING, ox + 17, oy, oz + 16, ox + 21, oz + 20, Direction.NORTH, Direction.SOUTH); // налоговая
		hang(w, st, KING, ox + 11, oy + 3, oz + 58, ox + 45, oz + 62, Direction.SOUTH); // зал парламента, над трибуной
		hang(w, st, KING, ox + 11, oy, oz + 51, ox + 45, oz + 56, Direction.NORTH); // фойе парламента
		hang(w, st, KING, ox + 49, oy, oz + 52, ox + 54, oz + 62, Direction.WEST, Direction.EAST); // ресторан
		hang(w, st, KING, ox + 3, oy, oz + 53, ox + 4, oz + 58, Direction.EAST); // тайная полиция, за спиной майора
		hang(w, st, KING, ox + 3, oy, oz + 70, ox + 7, oz + 77, Direction.NORTH); // допросная — король смотрит
		// агитплакаты
		hang(w, st, EYE, ox + 3, oy, oz + 60, ox + 7, oz + 68, Direction.WEST);
		hang(w, st, EYE, ox + 11, oy, oz + 51, ox + 45, oz + 56, Direction.SOUTH);
		hang(w, st, TAX, ox + 17, oy, oz + 16, ox + 21, oz + 20, Direction.EAST, Direction.WEST);
		hang(w, st, TAX, ox + 49, oy, oz + 52, ox + 54, oz + 62, Direction.EAST, Direction.WEST);
		hang(w, st, EYE, ox + 3, oy, oz + 7, ox + 13, oz + 13, Direction.EAST);
		// МФЦ, суд, тюрьма
		if (st.mfcBuilt) {
			B.loadChunks(st.mx, st.mz, st.mx + Mfc.W, st.mz + Mfc.D);
			hang(w, st, KING, st.mx + 1, st.my, st.mz + 1, st.mx + Mfc.W - 2, st.mz + Mfc.D - 2, Direction.NORTH);
			hang(w, st, TAX, st.mx + 1, st.my, st.mz + 1, st.mx + Mfc.W - 2, st.mz + Mfc.D - 2, Direction.EAST, Direction.WEST);
		}
		if (st.courtBuilt) {
			B.loadChunks(st.kx, st.kz, st.kx + Court.W, st.kz + Court.D);
			hang(w, st, KING, st.kx + 1, st.ky + 1, st.kz + 1, st.kx + Court.W - 2, st.kz + Court.D - 2, Direction.EAST,
					Direction.SOUTH);
		}
		if (st.prisonBuilt) {
			B.loadChunks(st.px + 36, st.pz, st.px + 50, st.pz + 12);
			hang(w, st, KING, st.px + 39, st.py, st.pz + 3, st.px + 46, st.pz + 8, Direction.SOUTH, Direction.NORTH);
			hang(w, st, EYE, st.px + 39, st.py, st.pz + 3, st.px + 46, st.pz + 8, Direction.EAST, Direction.WEST);
		}
		// КПП: в будке пограничника
		if (st.borderBuilt) {
			for (int k = 0; k < 4; k++) {
				var c = Border.checkpointPos(st, k);
				int cx = (int) Math.floor(c.x), cz = (int) Math.floor(c.z);
				B.loadChunks(cx - 10, cz - 10, cx + 10, cz + 10);
				hangNear(w, st, KING, cx, st.borderY[k], cz);
			}
		}
		st.portraitsPlaced = true;
		st.markDirty();
		PizdecMod.LOG.info("Развешано портретов и плакатов: {}", placedCount);
	}

	/** Для КПП (повёрнутых по-разному): ищет стену в квадрате ±8 вокруг. */
	private static void hangNear(ServerWorld w, PizdecState st, String id, int cx, int y, int cz) {
		for (Direction f : new Direction[] {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}) {
			for (int dx = -8; dx <= 8; dx++) {
				for (int dz = -8; dz <= 8; dz++) {
					BlockPos p = new BlockPos(cx + dx, y + 1, cz + dz);
					if (!w.getBlockState(p.offset(f.getOpposite())).isOf(Blocks.WHITE_CONCRETE)) continue;
					if (w.getBlockState(p.down()).isAir()) continue; // только внутри будки
					PaintingEntity e = make(w, id, p, f);
					if (e == null || !e.canStayAttached()) continue;
					w.spawnEntity(e);
					st.portraitSpots.add(id + ";" + p.getX() + ";" + p.getY() + ";" + p.getZ() + ";" + f.getName());
					placedCount++;
					return;
				}
			}
		}
	}

	// ================================================================== охрана портретов

	public static boolean isPortrait(Entity e) {
		return e instanceof PaintingEntity && e.getCommandTags().contains(TAG);
	}

	private static boolean isKing(Entity e) {
		return e.getCommandTags().contains("pizdec_portrait_" + KING);
	}

	/** Удар по портрету. */
	public static void onAttack(ServerPlayerEntity p, Entity e) {
		boolean king = isKing(e);
		SecretPolice.add(p, king ? 30 : 15, king ? "повредил портрет короля" : "сорвал агитплакат");
		Propaganda.loveDrop(0.3);
		Jail.arrest(p, king ? "оскорбление величества: повреждение портрета короля" : "порча агитационных материалов",
				king ? 120 : 60);
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.portraitsPlaced) return;
		int now = s.getTicks();
		ServerWorld w = s.getOverworld();
		if (now % 10 == 0) bows(s, w, now);
		if (now % 200 != 0) return;
		for (String spot : st.portraitSpots) {
			String[] a = spot.split(";");
			if (a.length < 5) continue;
			BlockPos pos = new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
			Direction f = Direction.byName(a[4]);
			if (f == null) continue;
			ServerPlayerEntity nearest = null;
			double best = 32 * 32;
			for (ServerPlayerEntity p : U.players(s)) {
				if (p.getServerWorld() != w) continue;
				double d = p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				if (d < best) {
					best = d;
					nearest = p;
				}
			}
			if (nearest == null) {
				missing.remove(spot);
				continue;
			}
			List<PaintingEntity> found = w.getEntitiesByClass(PaintingEntity.class, new Box(pos).expand(1.6),
					e -> e.getCommandTags().contains(TAG));
			if (found.size() > 1) {
				for (int i = 1; i < found.size(); i++) found.get(i).discard();
			}
			if (!found.isEmpty()) {
				missing.remove(spot);
				continue;
			}
			int m = missing.merge(spot, 1, Integer::sum);
			if (m < 2) continue; // даём миру догрузить сущности
			missing.remove(spot);
			PaintingEntity e = make(w, a[0], pos, f);
			if (e == null) continue;
			w.spawnEntity(e);
			if (best < 8 * 8 && !nearest.isCreative() && !Jail.busy(nearest)) {
				boolean king = a[0].equals(KING);
				U.say(s, U.t("🖼 " + (king ? "Портрет Его Величества" : "Агитплакат") + " был снят со стены! Портрет возвращён, "
						+ "подозреваемый задержан.", Formatting.DARK_RED));
				SecretPolice.add(nearest, king ? 30 : 15, king ? "снятие портрета короля" : "снятие агитплаката");
				Jail.arrest(nearest, king ? "оскорбление величества: снятие портрета короля" : "снятие агитплаката",
						king ? 120 : 60);
			}
		}
	}

	// ================================================================== поклоны

	public static boolean nearPortrait(ServerPlayerEntity p, double r) {
		if (!PizdecState.get(p.getServer()).portraitsPlaced) return false;
		return !p.getServerWorld().getEntitiesByClass(PaintingEntity.class, p.getBoundingBox().expand(r),
				e -> e.getCommandTags().contains("pizdec_portrait_" + KING)).isEmpty();
	}

	/** Поклонился недавно (или только что подошёл — дадим 3 секунды). */
	public static boolean bowedRecently(ServerPlayerEntity p) {
		int now = p.getServer().getTicks();
		Integer since = nearSince.get(p.getUuid());
		if (since == null || now - since < 60) return true;
		return now - lastBow.getOrDefault(p.getUuid(), -9999) < 200;
	}

	private static void bows(MinecraftServer s, ServerWorld w, int now) {
		for (ServerPlayerEntity p : U.players(s)) {
			if (p.getServerWorld() != w) continue;
			boolean near = nearPortrait(p, 3);
			if (!near) {
				nearSince.remove(p.getUuid());
				continue;
			}
			nearSince.putIfAbsent(p.getUuid(), now);
			if (p.isSneaking()) {
				Integer lb = lastBow.put(p.getUuid(), now);
				if ((lb == null || now - lb > 200) && now - lastBowMsg.getOrDefault(p.getUuid(), -99999) > 20 * 60) {
					lastBowMsg.put(p.getUuid(), now);
					U.bar(p, U.t("🖼 Ты поклонился портрету короля. Это заметили. Это хорошо.", Formatting.GOLD));
					SecretPolice.add(p, -1, "поклонился портрету короля");
				}
			}
		}
	}
}
