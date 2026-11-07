package com.nullspace.mechanic;

import com.nullspace.NullspaceConfig;
import com.nullspace.NullspaceMod;
import com.nullspace.registry.ModAttachments;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.AquaticChunkGenerator;
import com.nullspace.world.AquaticLayout;
import com.nullspace.world.MallLayout;
import com.nullspace.world.NullspaceDimensions;
import com.nullspace.world.ParkingChunkGenerator;
import com.nullspace.world.ParkingLayout;
import com.nullspace.world.OfficeLayout;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Перемещения между обычным миром и NULLSPACE. */
public final class NullspaceTeleporter {

	/** Провалить игрока на Уровень 0. Если он уже там — перебросить в другое место уровня. */
	public static void sendToNullspace(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerWorld level0 = server.getWorld(NullspaceDimensions.LEVEL_0);
		if (level0 == null) {
			NullspaceMod.LOGGER.error("Измерение nullspace:level_0 не загружено — проверь data/nullspace/dimension");
			return;
		}

		boolean alreadyInside = NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey());
		if (!alreadyInside) {
			player.setAttached(ModAttachments.RETURN_POINT,
					new ReturnPoint(player.getWorld().getRegistryKey(), player.getBlockPos()));
			leaveTrace(player);
		}

		Random random = player.getRandom();
		int spread = Math.max(OfficeLayout.CELL * 4, NullspaceConfig.get().spawnSpread);
		int cx = (random.nextInt(spread * 2) - spread) / OfficeLayout.CELL;
		int cz = (random.nextInt(spread * 2) - spread) / OfficeLayout.CELL;
		double x = OfficeLayout.cellCenter(cx) + 0.5;
		double z = OfficeLayout.cellCenter(cz) + 0.5;
		double y = OfficeLayout.FLOOR_Y + 1.5; // полблока падения на ковёр (потолок низкий)

		cutAllSounds(player);
		player.teleport(level0, x, y, z, player.getYaw(), 20.0f);
		player.fallDistance = 0;
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, false, false));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false));
		player.playSoundToPlayer(ModSounds.NOCLIP_FALL, SoundCategory.MASTER, 1.0f, 1.0f);
		player.sendMessage(Text.translatable(alreadyInside
				? "message.nullspace.fell_again"
				: "message.nullspace.fell").formatted(Formatting.YELLOW), true);
	}

	/**
	 * Провалиться глубже — на Уровень 1. Игрок падает с потолка прямо в бассейн.
	 * Точка возврата домой сохраняется прежняя.
	 */
	public static void sendToLevel1(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerWorld level1 = server.getWorld(NullspaceDimensions.LEVEL_1);
		if (level1 == null) {
			NullspaceMod.LOGGER.error("Измерение nullspace:level_1 не загружено");
			return;
		}
		if (!NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
			player.setAttached(ModAttachments.RETURN_POINT,
					new ReturnPoint(player.getWorld().getRegistryKey(), player.getBlockPos()));
		}

		Random random = player.getRandom();
		int spread = Math.max(AquaticLayout.HALL * 4, NullspaceConfig.get().spawnSpread);
		int hx = (random.nextInt(spread * 2) - spread) / AquaticLayout.HALL;
		int hz = (random.nextInt(spread * 2) - spread) / AquaticLayout.HALL;
		if (level1.getChunkManager().getChunkGenerator() instanceof AquaticChunkGenerator gen) {
			int[] hall = gen.layout(level1.getChunkManager().getNoiseConfig()).findPoolHall(hx, hz);
			hx = hall[0];
			hz = hall[1];
		}
		double x = hx * AquaticLayout.HALL + 8.5;
		double z = hz * AquaticLayout.HALL + 8.5;
		double y = AquaticLayout.CEILING_Y - 1.9;

		cutAllSounds(player);
		player.teleport(level1, x, y, z, player.getYaw(), 60.0f);
		player.fallDistance = 0;
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false));
		player.playSoundToPlayer(ModSounds.NOCLIP_FALL, SoundCategory.MASTER, 1.0f, 0.7f);
		player.sendMessage(Text.translatable("message.nullspace.fell_level1").formatted(Formatting.AQUA), true);
	}

	/**
	 * Через слив — на Уровень 2. Игрок оказывается в проезде подземной парковки.
	 */
	public static void sendToLevel2(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerWorld level2 = server.getWorld(NullspaceDimensions.LEVEL_2);
		if (level2 == null) {
			NullspaceMod.LOGGER.error("Измерение nullspace:level_2 не загружено");
			return;
		}
		if (!NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
			player.setAttached(ModAttachments.RETURN_POINT,
					new ReturnPoint(player.getWorld().getRegistryKey(), player.getBlockPos()));
		}

		Random random = player.getRandom();
		int spread = Math.max(ParkingLayout.SECTION * 4, NullspaceConfig.get().spawnSpread);
		int sx = (random.nextInt(spread * 2) - spread) / ParkingLayout.SECTION;
		int sz = (random.nextInt(spread * 2) - spread) / ParkingLayout.SECTION;
		if (level2.getChunkManager().getChunkGenerator() instanceof ParkingChunkGenerator gen) {
			int[] section = gen.layout(level2.getChunkManager().getNoiseConfig()).findParking(sx, sz);
			sx = section[0];
			sz = section[1];
		}
		double x = sx * ParkingLayout.SECTION + 6.5;
		double z = sz * ParkingLayout.SECTION + 16.0;
		double y = ParkingLayout.FLOOR_Y + 1;

		cutAllSounds(player);
		player.teleport(level2, x, y, z, -90f, 0f);
		player.fallDistance = 0;
		player.setAir(player.getMaxAir());
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, false, false));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false));
		player.playSoundToPlayer(ModSounds.NOCLIP_FALL, SoundCategory.MASTER, 1.0f, 0.55f);
		player.sendMessage(Text.translatable("message.nullspace.fell_level2").formatted(Formatting.GRAY), true);
	}

	/** Лифт «M» — на Уровень 3. Двери открываются в галерею первого этажа. */
	public static void sendToLevel3(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerWorld level3 = server.getWorld(NullspaceDimensions.LEVEL_3);
		if (level3 == null) {
			NullspaceMod.LOGGER.error("Измерение nullspace:level_3 не загружено");
			return;
		}
		if (!NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
			player.setAttached(ModAttachments.RETURN_POINT,
					new ReturnPoint(player.getWorld().getRegistryKey(), player.getBlockPos()));
		}
		Random random = player.getRandom();
		int spread = Math.max(MallLayout.CELL * 4, NullspaceConfig.get().spawnSpread);
		int cx = (random.nextInt(spread * 2) - spread) / MallLayout.CELL;
		int cz = (random.nextInt(spread * 2) - spread) / MallLayout.CELL;
		double[] at = MallLayout.arrival(cx, cz);

		cutAllSounds(player);
		player.teleport(level3, at[0], at[1], at[2], -90f, 0f);
		player.fallDistance = 0;
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false));
		player.playSoundToPlayer(ModSounds.ELEVATOR_DING, SoundCategory.MASTER, 1.0f, 1.0f);
		player.sendMessage(Text.translatable("message.nullspace.fell_level3").formatted(Formatting.LIGHT_PURPLE), true);
	}

	/** Вернуть игрока туда, откуда он провалился. */
	public static void returnHome(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;

		ReturnPoint point = player.getAttached(ModAttachments.RETURN_POINT);
		ServerWorld world = point != null ? server.getWorld(point.dimension()) : null;
		if (world == null) world = server.getOverworld();
		BlockPos target = point != null ? point.pos() : world.getSpawnPos();
		target = findSafeSpot(world, target);

		cutAllSounds(player);
		player.teleport(world, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, player.getYaw(), player.getPitch());
		player.removeAttached(ModAttachments.RETURN_POINT);
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false));
		player.sendMessage(Text.translatable("message.nullspace.returned").formatted(Formatting.GRAY), true);
	}

	/** Остановить все звуки у игрока (то же, что /stopsound). */
	public static void cutAllSounds(ServerPlayerEntity player) {
		player.networkHandler.sendPacket(new StopSoundS2CPacket(null, null));
	}

	/** Ищем две клетки воздуха над твёрдым блоком рядом с исходной точкой. */
	private static BlockPos findSafeSpot(ServerWorld world, BlockPos start) {
		world.getChunk(start); // прогрузить чанк
		BlockPos.Mutable pos = start.mutableCopy();
		for (int i = 0; i < 48 && pos.getY() < world.getTopY() - 2; i++) {
			if (isStandable(world, pos)) return pos.toImmutable();
			pos.move(0, 1, 0);
		}
		int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, start.getX(), start.getZ());
		return new BlockPos(start.getX(), top, start.getZ());
	}

	private static boolean isStandable(World world, BlockPos pos) {
		BlockState feet = world.getBlockState(pos);
		BlockState head = world.getBlockState(pos.up());
		BlockState below = world.getBlockState(pos.down());
		return feet.getCollisionShape(world, pos).isEmpty()
				&& head.getCollisionShape(world, pos.up()).isEmpty()
				&& !below.getCollisionShape(world, pos.down()).isEmpty()
				&& feet.getFluidState().isEmpty();
	}

	/**
	 * Часть вещей «не проходит» сквозь реальность и остаётся в сундуке на месте провала.
	 * Затрагивается только основной инвентарь (не хотбар, не броня).
	 */
	private static void leaveTrace(ServerPlayerEntity player) {
		double chance = NullspaceConfig.get().itemLossChance;
		if (chance <= 0) return;

		World world = player.getWorld();
		BlockPos chestPos = null;
		for (BlockPos candidate : new BlockPos[]{player.getBlockPos(), player.getBlockPos().up()}) {
			BlockState state = world.getBlockState(candidate);
			if (state.isAir() || state.isReplaceable()) {
				chestPos = candidate;
				break;
			}
		}
		if (chestPos == null) return; // некуда положить — вещи остаются при игроке

		PlayerInventory inventory = player.getInventory();
		List<ItemStack> lost = new ArrayList<>();
		for (int slot = PlayerInventory.getHotbarSize(); slot < inventory.main.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty() && player.getRandom().nextDouble() < chance) {
				lost.add(stack.copy());
				inventory.setStack(slot, ItemStack.EMPTY);
			}
		}
		if (lost.isEmpty()) return;

		world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
		BlockEntity be = world.getBlockEntity(chestPos);
		if (be instanceof ChestBlockEntity chest) {
			for (int i = 0; i < lost.size() && i < chest.size(); i++) {
				chest.setStack(i, lost.get(i));
			}
			chest.markDirty();
		}
	}

	private NullspaceTeleporter() {
	}
}
