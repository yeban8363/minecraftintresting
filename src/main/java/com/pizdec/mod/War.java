package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * Война. Идёт по канону в 1200 блоках к востоку от короля. Ссылка — 2 минуты.
 * Выжил — ветеранские. Погиб — героически, похороны за счёт государства.
 */
public final class War {
	public static final int DISTANCE = 1200;
	public static final int EXILE = 20 * 120;
	public static final int R = 30;
	public static final String ENEMY_TAG = "pizdec_enemy";

	private static ServerWorld w;

	private War() {}

	public static boolean exiled(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).exiles.containsKey(p.getUuid());
	}

	public static int secondsLeft(ServerPlayerEntity p) {
		Integer t = PizdecState.get(p.getServer()).exiles.get(p.getUuid());
		return t == null ? 0 : (t + 19) / 20;
	}

	// ------------------------------------------------------------------ поле боя

	private static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.warBuilt) return;
		ServerWorld world = s.getOverworld();
		int ox = st.cx + 16 + DISTANCE, oz = st.cz;
		for (int cx = (ox - R - 4) >> 4; cx <= (ox + R + 4) >> 4; cx++) {
			for (int cz = (oz - R - 4) >> 4; cz <= (oz + R + 4) >> 4; cz++) world.getChunk(cx, cz);
		}
		int oy = Math.max(world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox, oz), world.getSeaLevel() + 1);
		build(world, ox, oy, oz);
		st.warBuilt = true;
		st.wx = ox;
		st.wy = oy;
		st.wz = oz;
		st.markDirty();
		PizdecMod.LOG.info("Война развёрнута на {} {} {}", ox, oy, oz);
	}

	private static void set(int x, int y, int z, BlockState b) {
		w.setBlockState(new BlockPos(x, y, z), b, Block.NOTIFY_LISTENERS);
	}

	private static void set(int x, int y, int z, Block b) {
		set(x, y, z, b.getDefaultState());
	}

	private static void build(ServerWorld world, int ox, int oy, int oz) {
		w = world;
		BlockState air = Blocks.AIR.getDefaultState();
		Block[] ground = {Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.PODZOL, Blocks.MUD, Blocks.COARSE_DIRT, Blocks.SOUL_SOIL};
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (x * x + z * z > R * R) continue;
				for (int y = oy; y <= oy + 15; y++) {
					BlockPos p = new BlockPos(ox + x, y, oz + z);
					if (!w.getBlockState(p).isAir()) w.setBlockState(p, air, Block.NOTIFY_LISTENERS);
				}
				for (int y = oy - 1; y > oy - 30; y--) {
					BlockState b = w.getBlockState(new BlockPos(ox + x, y, oz + z));
					boolean soft = b.isAir() || !b.getFluidState().isEmpty() || b.isReplaceable() || b.isIn(BlockTags.LEAVES)
							|| b.isIn(BlockTags.LOGS);
					if (y < oy - 2 && !soft) break;
					set(ox + x, y, oz + z, y == oy - 1 ? U.pick(ground) : Blocks.DIRT);
				}
			}
		}
		// окопы по обе стороны
		for (int z = -18; z <= 18; z++) {
			for (int sx : new int[] {-12, 12}) {
				for (int dx = -1; dx <= 1; dx++) {
					set(ox + sx + dx, oy - 1, oz + z, Blocks.AIR);
					set(ox + sx + dx, oy - 2, oz + z, Blocks.MUD);
				}
				set(ox + sx + (sx < 0 ? -2 : 2), oy, oz + z, Blocks.SANDSTONE); // мешки с песком
			}
		}
		// колючая проволока
		for (int i = 0; i < 40; i++) {
			int x = U.between(-8, 8), z = U.between(-R + 6, R - 6);
			set(ox + x, oy, oz + z, Blocks.COBWEB);
		}
		// воронки
		for (int i = 0; i < 12; i++) {
			int x = U.between(-R + 5, R - 5), z = U.between(-R + 5, R - 5);
			if (Math.abs(x) < 3 && Math.abs(z) < 3) continue;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(ox + x + dx, oy - 1, oz + z + dz, Blocks.AIR);
			set(ox + x, oy - 2, oz + z, Blocks.AIR);
		}
		// развалины и пожары
		for (int i = 0; i < 6; i++) {
			int x = U.between(-R + 6, R - 6), z = U.between(-R + 6, R - 6);
			if (Math.abs(x) < 4) continue;
			for (int y = 0; y <= U.between(1, 4); y++) set(ox + x, oy + y, oz + z, Blocks.CRACKED_STONE_BRICKS);
			set(ox + x + 1, oy - 1, oz + z, Blocks.NETHERRACK);
			set(ox + x + 1, oy, oz + z, Blocks.FIRE);
		}
		// флаги армий
		for (int sx : new int[] {-20, 20}) {
			for (int y = oy; y <= oy + 6; y++) set(ox + sx, y, oz, Blocks.OAK_FENCE);
			Block wool = sx < 0 ? Blocks.YELLOW_WOOL : Blocks.BLACK_WOOL;
			for (int i = 1; i <= 3; i++) {
				set(ox + sx, oy + 6, oz + i, wool);
				set(ox + sx, oy + 5, oz + i, wool);
			}
		}
		// штаб — точка прибытия
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) set(ox + x, oy - 1, oz + z, Blocks.SPRUCE_PLANKS);
		set(ox, oy, oz + 2, Blocks.CAMPFIRE);
	}

	// ------------------------------------------------------------------ ссылка

	public static void exile(ServerPlayerEntity p, String reason) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		if (p.isSpectator()) return;
		ensureBuilt(s);
		Integer had = st.exiles.get(p.getUuid());
		if (had != null) {
			st.exiles.put(p.getUuid(), had + 20 * 30);
			st.markDirty();
			U.msg(p, U.t("⚔ Срок службы продлён на 30 сек.", Formatting.RED));
			return;
		}
		if (p.hasVehicle()) p.stopRiding();
		st.exiles.put(p.getUuid(), EXILE);
		st.markDirty();
		p.teleport(s.getOverworld(), st.wx + 0.5, st.wy, st.wz + 0.5, 90f, 0f);
		U.title(p, U.t("⚔ ССЫЛКА НА ВОЙНУ", Formatting.DARK_RED, Formatting.BOLD), U.t(reason, Formatting.GRAY));
		U.say(s, U.t("⚔ " + p.getName().getString() + " отправлен на войну (" + reason + "). Пишите письма.", Formatting.DARK_RED));
		U.msg(p, U.t("⚔ Продержись 2 минуты. Покинуть поле боя нельзя — это дезертирство. Выдан солдатский набор.",
				Formatting.RED));
		p.getInventory().offerOrDrop(new ItemStack(Items.IRON_SWORD));
		p.getInventory().offerOrDrop(new ItemStack(Items.BREAD, 6));
		p.getInventory().offerOrDrop(new ItemStack(Items.SHIELD));
		if (p.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) p.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		if (p.getEquippedStack(EquipmentSlot.CHEST).isEmpty()) {
			p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
		}
		U.sound(p, SoundEvents.EVENT_RAID_HORN, 3f, 0.7f);
	}

	private static void end(ServerPlayerEntity p, boolean died) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		st.exiles.remove(p.getUuid());
		st.markDirty();
		if (died) {
			U.say(s, U.t("⚔ " + p.getName().getString() + " пал смертью храбрых. Похороны за счёт государства.", Formatting.GRAY));
			return;
		}
		net.minecraft.util.math.Vec3d g = Castle.gateOutside(st);
		p.teleport(s.getOverworld(), g.x, g.y, g.z, 180f, 0f);
		Money.give(p, 100, "ветеранские");
		U.title(p, U.t("🎖 ДЕМОБИЛИЗОВАН", Formatting.GOLD, Formatting.BOLD), U.t("+100 бабла ветеранских", Formatting.YELLOW));
		U.say(s, U.t("🎖 " + p.getName().getString() + " вернулся с войны живым. Герой!", Formatting.GOLD));
		Ranks.tryKingPromotion(p, 0.03, "за храбрость на войне");
	}

	/** Смерть на войне — служба окончена. */
	public static boolean onDeath(ServerPlayerEntity p) {
		if (!exiled(p)) return false;
		end(p, true);
		return true;
	}

	/** Мирные переговоры: все возвращаются. */
	public static void releaseAll(MinecraftServer s) {
		for (ServerPlayerEntity p : new ArrayList<>(s.getPlayerManager().getPlayerList())) {
			if (exiled(p)) end(p, false);
		}
		PizdecState st = PizdecState.get(s);
		st.exiles.clear();
		st.markDirty();
	}

	public static void releaseByAdmin(ServerPlayerEntity p) {
		if (exiled(p)) end(p, false);
	}

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.exiles.isEmpty()) return;
		int now = s.getTicks();
		ServerWorld world = s.getOverworld();
		List<ServerPlayerEntity> done = new ArrayList<>();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			Integer t = st.exiles.get(p.getUuid());
			if (t == null || !p.isAlive()) continue;
			t--;
			st.exiles.put(p.getUuid(), t);
			if (t <= 0) {
				done.add(p);
				continue;
			}
			if (now % 10 == 0) keepOnField(p, st);
			if (now % 20 == 0) {
				st.markDirty();
				U.bar(p, U.t("⚔ Война: держаться ещё " + (t + 19) / 20 + " сек", Formatting.DARK_RED));
			}
			if (now % 90 == 0) spawnEnemy(p, world);
			if (now % 35 == 0) artillery(p, world);
		}
		for (ServerPlayerEntity p : done) end(p, false);
	}

	private static void keepOnField(ServerPlayerEntity p, PizdecState st) {
		double dx = p.getX() - (st.wx + 0.5), dz = p.getZ() - (st.wz + 0.5);
		boolean ok = p.getServerWorld() == p.getServer().getOverworld() && dx * dx + dz * dz < (R - 1) * (R - 1)
				&& Math.abs(p.getY() - st.wy) < 25;
		if (ok) return;
		if (p.hasVehicle()) p.stopRiding();
		p.teleport(p.getServer().getOverworld(), st.wx + 0.5, st.wy, st.wz + 0.5, p.getYaw(), p.getPitch());
		UUID id = p.getUuid();
		st.exiles.merge(id, 20 * 20, Integer::sum);
		U.msg(p, U.t("⚔ Дезертирство! +20 сек службы. Командир смотрит на тебя с укором.", Formatting.RED));
	}

	private static void spawnEnemy(ServerPlayerEntity p, ServerWorld world) {
		HostileEntity e = switch (U.R.nextInt(5)) {
			case 0 -> U.spawnNear(EntityType.PILLAGER, p, 14);
			case 1 -> U.spawnNear(EntityType.VINDICATOR, p, 14);
			case 2 -> U.spawnNear(EntityType.SKELETON, p, 14);
			case 3 -> U.spawnNear(EntityType.HUSK, p, 14);
			default -> U.spawnNear(EntityType.ZOMBIE, p, 14);
		};
		if (e == null) return;
		e.setCustomName(U.t(U.pick("Вражеский солдат", "Вражеский сержант", "Вражеский повар", "Наёмник", "Дезертир с той стороны"),
				Formatting.DARK_RED));
		e.addCommandTag(ENEMY_TAG);
		e.setTarget(p);
	}

	private static void artillery(ServerPlayerEntity p, ServerWorld world) {
		double a = U.R.nextDouble() * Math.PI * 2;
		double d = 7 + U.R.nextDouble() * 15;
		double x = p.getX() + Math.cos(a) * d, z = p.getZ() + Math.sin(a) * d;
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		world.createExplosion(null, x, y, z, 1.8f, World.ExplosionSourceType.NONE);
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y + 1, z, 20, 1, 1, 1, 0.05);
		if (U.chance(0.15)) {
			world.playSound(null, x, y, z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 2f, 0.6f);
		}
		if (U.chance(0.1)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0));
	}
}
