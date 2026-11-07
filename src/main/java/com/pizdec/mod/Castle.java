package com.pizdec.mod;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/** Замок Короля. Строится один раз рядом со спавном мира. */
public final class Castle {
	public static final int S = 33; // размер двора
	public static final String KING_TAG = "pizdec_king";
	public static final String INSPECTOR_TAG = "pizdec_inspector";
	public static final String JESTER_TAG = "pizdec_jester";
	public static final String SHOP_TAG = "pizdec_shop";
	public static final String CASINO_TAG = "pizdec_casino";
	public static final String UPGRADER_TAG = "pizdec_upgrader";
	public static final String KING_NAME = "Король Пиздец III";

	private static ServerWorld w;

	private Castle() {}

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.castleBuilt) return;
		ServerWorld world = s.getOverworld();
		BlockPos spawn = world.getSpawnPos();
		int ox = spawn.getX() + 40;
		int oz = spawn.getZ() - S / 2;

		// грузим все чанки под замком
		for (int cx = (ox - 6) >> 4; cx <= (ox + S + 6) >> 4; cx++) {
			for (int cz = (oz - 6) >> 4; cz <= (oz + S + 6) >> 4; cz++) {
				world.getChunk(cx, cz);
			}
		}
		int oy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox + S / 2, oz + S / 2);
		oy = Math.max(oy, world.getSeaLevel() + 1);

		build(world, ox, oy, oz);
		st.castleBuilt = true;
		st.cx = ox;
		st.cy = oy;
		st.cz = oz;
		st.markDirty();
		spawnNpcs(world, st);
		PizdecMod.LOG.info("Замок короля построен на {} {} {}", ox, oy, oz);
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		return st.castleBuilt
				&& p.getX() >= st.cx - 4 && p.getX() <= st.cx + S + 3
				&& p.getZ() >= st.cz - 4 && p.getZ() <= st.cz + S + 3
				&& p.getY() >= st.cy - 8 && p.getY() <= st.cy + 20;
	}

	public static BlockPos thronePos(PizdecState st) {
		return new BlockPos(st.cx + 16, st.cy + 1, st.cz + 27);
	}

	// ------------------------------------------------------------------ стройка

	private static void set(int x, int y, int z, BlockState b) {
		w.setBlockState(new BlockPos(x, y, z), b, Block.NOTIFY_LISTENERS);
	}

	private static void set(int x, int y, int z, Block b) {
		set(x, y, z, b.getDefaultState());
	}

	private static BlockState wall() {
		int r = U.R.nextInt(100);
		if (r < 70) return Blocks.STONE_BRICKS.getDefaultState();
		if (r < 85) return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
		return Blocks.CRACKED_STONE_BRICKS.getDefaultState();
	}

	private static boolean soft(BlockState b) {
		return b.isAir() || !b.getFluidState().isEmpty() || b.isReplaceable()
				|| b.isIn(BlockTags.LEAVES) || b.isIn(BlockTags.LOGS);
	}

	private static void build(ServerWorld world, int ox, int oy, int oz) {
		w = world;
		BlockState air = Blocks.AIR.getDefaultState();

		// 1. расчищаем площадку и подводим фундамент
		for (int x = -4; x <= S + 3; x++) {
			for (int z = -4; z <= S + 3; z++) {
				for (int y = oy; y <= oy + 36; y++) {
					BlockPos p = new BlockPos(ox + x, y, oz + z);
					if (!w.getBlockState(p).isAir()) w.setBlockState(p, air, Block.NOTIFY_LISTENERS);
				}
				boolean yard = x >= 0 && x < S && z >= 0 && z < S;
				for (int y = oy - 1; y > oy - 40; y--) {
					BlockState b = w.getBlockState(new BlockPos(ox + x, y, oz + z));
					if (y < oy - 3 && !soft(b)) break;
					if (y == oy - 1) {
						set(ox + x, y, oz + z, yard ? Blocks.GRASS_BLOCK : Blocks.COARSE_DIRT);
					} else {
						set(ox + x, y, oz + z, y == oy - 2 ? Blocks.DIRT : Blocks.STONE_BRICKS);
					}
				}
			}
		}

		// дорожка от ворот к донжону
		for (int x = 14; x <= 18; x++) {
			for (int z = -4; z <= 15; z++) set(ox + x, oy - 1, oz + z, Blocks.POLISHED_ANDESITE);
		}

		// 2. стены
		for (int x = 0; x < S; x++) {
			for (int z = 0; z < S; z++) {
				boolean edge = x == 0 || z == 0 || x == S - 1 || z == S - 1;
				boolean ring = !edge && (x == 1 || z == 1 || x == S - 2 || z == S - 2);
				if (edge) {
					for (int y = oy; y <= oy + 7; y++) set(ox + x, y, oz + z, wall());
					if ((x + z) % 2 == 0) set(ox + x, oy + 8, oz + z, wall());
				} else if (ring) {
					set(ox + x, oy + 6, oz + z, Blocks.DARK_OAK_PLANKS); // проход по стене
				}
			}
		}
		// ворота
		for (int x = 14; x <= 18; x++) {
			for (int y = oy; y <= oy + 4; y++) set(ox + x, y, oz, air);
		}
		set(ox + 13, oy, oz - 1, Blocks.LANTERN);
		set(ox + 19, oy, oz - 1, Blocks.LANTERN);

		// 3. башни по углам
		int[][] corners = {{0, 0}, {S - 1, 0}, {0, S - 1}, {S - 1, S - 1}};
		for (int[] c : corners) tower(ox + c[0], oy, oz + c[1], c[0] == 0 ? 1 : -1, c[1] == 0 ? 1 : -1);

		// 4. донжон (тронный зал) из кварца и золота — король живёт богато
		int kx0 = 9, kx1 = 23, kz0 = 15, kz1 = 29, kh = 9;
		for (int x = kx0; x <= kx1; x++) {
			for (int z = kz0; z <= kz1; z++) {
				boolean edge = x == kx0 || x == kx1 || z == kz0 || z == kz1;
				boolean corner = (x == kx0 || x == kx1) && (z == kz0 || z == kz1);
				set(ox + x, oy - 1, oz + z, (x + z) % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.BLACK_CONCRETE);
				if (edge) {
					for (int y = oy; y <= oy + kh; y++) {
						set(ox + x, y, oz + z, corner ? Blocks.GOLD_BLOCK : Blocks.QUARTZ_BRICKS);
					}
					if ((x + z) % 2 == 0) set(ox + x, oy + kh + 2, oz + z, Blocks.QUARTZ_BRICKS);
				}
				// крыша с подсветкой
				boolean lamp = !edge && (x - kx0) % 3 == 0 && (z - kz0) % 3 == 0;
				set(ox + x, oy + kh + 1, oz + z, lamp ? Blocks.GLOWSTONE : Blocks.DARK_OAK_PLANKS);
			}
		}
		// дверь в тронный зал
		for (int x = 15; x <= 17; x++) {
			for (int y = oy; y <= oy + 3; y++) set(ox + x, y, oz + kz0, air);
		}
		// окна
		for (int z = 18; z <= 27; z += 3) {
			for (int y = oy + 2; y <= oy + 4; y++) {
				set(ox + kx0, y, oz + z, Blocks.YELLOW_STAINED_GLASS);
				set(ox + kx1, y, oz + z, Blocks.YELLOW_STAINED_GLASS);
			}
		}
		// колонны
		for (int[] c : new int[][] {{11, 18}, {21, 18}, {11, 24}, {21, 24}}) {
			for (int y = oy; y <= oy + kh; y++) set(ox + c[0], y, oz + c[1], Blocks.QUARTZ_PILLAR);
		}
		// красная ковровая дорожка
		for (int z = kz0 + 1; z <= 25; z++) {
			for (int x = 15; x <= 17; x++) set(ox + x, oy, oz + z, Blocks.RED_CARPET);
		}
		// трон
		set(ox + 16, oy, oz + 27, Blocks.GOLD_BLOCK);
		set(ox + 15, oy, oz + 27, Blocks.GOLD_BLOCK);
		set(ox + 17, oy, oz + 27, Blocks.GOLD_BLOCK);
		set(ox + 15, oy + 1, oz + 27, Blocks.GOLD_BLOCK);
		set(ox + 17, oy + 1, oz + 27, Blocks.GOLD_BLOCK);
		for (int x = 15; x <= 17; x++) {
			for (int y = oy; y <= oy + 3; y++) set(ox + x, y, oz + 28, Blocks.GOLD_BLOCK);
		}
		set(ox + 16, oy + 4, oz + 28, Blocks.DIAMOND_BLOCK);
		// казна (смотреть можно, трогать — нет)
		set(ox + 12, oy, oz + 28, Blocks.EMERALD_BLOCK);
		set(ox + 13, oy, oz + 28, Blocks.EMERALD_BLOCK);
		set(ox + 12, oy + 1, oz + 28, Blocks.EMERALD_BLOCK);
		set(ox + 20, oy, oz + 28, Blocks.GOLD_BLOCK);
		set(ox + 19, oy, oz + 28, Blocks.GOLD_BLOCK);
		set(ox + 20, oy + 1, oz + 28, Blocks.GOLD_BLOCK);
		// светильники в зале
		for (int[] c : new int[][] {{10, 16}, {22, 16}, {10, 28}, {22, 28}}) {
			set(ox + c[0], oy, oz + c[1], Blocks.LANTERN);
		}

		// 5. будка налоговой у ворот
		for (int x = 3; x <= 7; x++) {
			for (int z = 3; z <= 7; z++) {
				boolean edge = x == 3 || x == 7 || z == 3 || z == 7;
				set(ox + x, oy - 1, oz + z, Blocks.POLISHED_ANDESITE);
				if (edge) for (int y = oy; y <= oy + 3; y++) set(ox + x, y, oz + z, Blocks.BRICKS);
				set(ox + x, oy + 4, oz + z, Blocks.BRICKS);
			}
		}
		for (int y = oy; y <= oy + 1; y++) set(ox + 7, y, oz + 5, air); // вход со стороны двора
		set(ox + 4, oy, oz + 4, Blocks.LANTERN);
		set(ox + 5, oy, oz + 6, Blocks.BARREL);

		// 6. «народ»: огород-грядки вдоль стены, чтобы было что облагать налогом
		for (int x = 22; x <= 29; x++) {
			for (int z = 3; z <= 8; z++) {
				set(ox + x, oy - 1, oz + z, z == 5 || z == 6 ? Blocks.WATER : Blocks.FARMLAND);
			}
		}
	}

	private static void tower(int cx, int oy, int cz, int sx, int sz) {
		int h = 13;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3;
				for (int y = oy; y < oy + h; y++) {
					if (shell) set(cx + dx, y, cz + dz, wall());
					else set(cx + dx, y, cz + dz, Blocks.AIR);
				}
				set(cx + dx, oy - 1, cz + dz, Blocks.STONE_BRICKS);
				set(cx + dx, oy + h, cz + dz, Blocks.STONE_BRICKS);
				if (shell && (dx + dz) % 2 == 0) set(cx + dx, oy + h + 1, cz + dz, wall());
			}
		}
		// проход со двора
		for (int y = oy; y <= oy + 2; y++) set(cx + 3 * sx, y, cz + sz, Blocks.AIR);
		// лестница наверх
		Direction face = sx > 0 ? Direction.EAST : Direction.WEST;
		BlockState ladder = Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, face);
		for (int y = oy; y <= oy + h; y++) set(cx - 2 * sx, y, cz, ladder);
		set(cx, oy, cz, Blocks.LANTERN);
		// флаг
		for (int y = oy + h + 1; y <= oy + h + 4; y++) set(cx, y, cz, Blocks.OAK_FENCE);
		for (int i = 1; i <= 3; i++) {
			set(cx + i * sx, oy + h + 4, cz, Blocks.RED_WOOL);
			set(cx + i * sx, oy + h + 3, cz, i == 2 ? Blocks.YELLOW_WOOL : Blocks.RED_WOOL);
		}
	}

	// ------------------------------------------------------------------ лавки во дворе

	/** Магазин, казино и апгрейдер — три лавки вдоль западной стены двора. */
	public static void ensureStalls(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt || st.stallsBuilt) return;
		ServerWorld world = s.getOverworld();
		w = world;
		stall(st, 4, 11, Blocks.LIME_WOOL, SHOP_TAG, U.t("🛒 Торговец Жорик", Formatting.GREEN),
				VillagerProfession.FARMER);
		stall(st, 4, 18, Blocks.RED_WOOL, CASINO_TAG, U.t("🎰 Крупье Анжела", Formatting.RED),
				VillagerProfession.CARTOGRAPHER);
		stall(st, 4, 25, Blocks.PURPLE_WOOL, UPGRADER_TAG, U.t("⬆ Апгрейдер Дима", Formatting.LIGHT_PURPLE),
				VillagerProfession.TOOLSMITH);
		st.stallsBuilt = true;
		st.markDirty();
	}

	private static void stall(PizdecState st, int sx, int sz, Block roof, String tag, net.minecraft.text.Text name,
			VillagerProfession prof) {
		int x = st.cx + sx, y = st.cy, z = st.cz + sz;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 0; dy <= 4; dy++) set(x + dx, y + dy, z + dz, Blocks.AIR);
				set(x + dx, y - 1, z + dz, Blocks.SMOOTH_STONE);
				set(x + dx, y + 4, z + dz, roof);
			}
		}
		for (int[] c : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
			for (int dy = 0; dy <= 3; dy++) set(x + c[0], y + dy, z + c[1], Blocks.SPRUCE_FENCE);
		}
		// прилавок перед продавцом
		set(x + 2, y, z - 1, Blocks.SPRUCE_PLANKS);
		set(x + 2, y, z + 1, Blocks.SPRUCE_PLANKS);
		set(x - 1, y, z - 1, Blocks.LANTERN);
		npc(w, x + 0.5, y, z + 0.5, 270f, tag, name, prof);
	}

	// ------------------------------------------------------------------ жители замка

	private static void spawnNpcs(ServerWorld world, PizdecState st) {
		spawnKing(world, st);
		npc(world, st.cx + 5.5, st.cy, st.cz + 5.5, 90f, INSPECTOR_TAG,
				U.t("Налоговый инспектор Зинаида Петровна", Formatting.RED), VillagerProfession.LIBRARIAN);
		npc(world, st.cx + 13.5, st.cy, st.cz + 21.5, 270f, JESTER_TAG,
				U.t("Шут Валера", Formatting.LIGHT_PURPLE), VillagerProfession.NITWIT);
	}

	public static void spawnKing(ServerWorld world, PizdecState st) {
		BlockPos t = thronePos(st);
		npc(world, t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 180f, KING_TAG,
				U.t("👑 " + KING_NAME, Formatting.GOLD, Formatting.BOLD), VillagerProfession.CLERIC);
	}

	static VillagerEntity npc(ServerWorld world, double x, double y, double z, float yaw, String tag,
			net.minecraft.text.Text name, VillagerProfession prof) {
		VillagerEntity v = EntityType.VILLAGER.create(world);
		if (v == null) return null;
		v.refreshPositionAndAngles(x, y, z, yaw, 0f);
		v.setHeadYaw(yaw);
		v.setBodyYaw(yaw);
		v.setVillagerData(v.getVillagerData().withProfession(prof).withLevel(5));
		v.setAiDisabled(true);
		v.setInvulnerable(true);
		v.setPersistent();
		v.setCustomName(name);
		v.setCustomNameVisible(true);
		v.addCommandTag(tag);
		world.spawnEntity(v);
		return v;
	}

	/** Если короля убили (/kill и т.п.), а рядом есть игроки — сажаем нового. */
	public static VillagerEntity findKing(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.castleBuilt) return null;
		ServerWorld world = s.getOverworld();
		BlockPos t = thronePos(st);
		boolean someoneNear = false;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p.getServerWorld() == world && p.squaredDistanceTo(t.getX(), t.getY(), t.getZ()) < 48 * 48) {
				someoneNear = true;
			}
		}
		if (!someoneNear) return null;
		List<VillagerEntity> kings = world.getEntitiesByClass(VillagerEntity.class, new Box(t).expand(24),
				e -> e.getCommandTags().contains(KING_TAG));
		if (kings.isEmpty()) {
			spawnKing(world, st);
			U.say(s, U.t("👑 На трон взошёл новый Король Пиздец III. Старый, говорят, не заплатил налоги.",
					Formatting.GOLD));
			return null;
		}
		return kings.get(0);
	}
}
