package com.nullspace.mechanic;

import com.nullspace.NullspaceConfig;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * «Провал сквозь реальность»: следит за условиями, запускает предвестники
 * и в конце отправляет игрока в NULLSPACE.
 */
public final class NoclipManager {
	private record Pending(int total, int left) {
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, Integer> DAMP_SECONDS = new HashMap<>();

	public static void tick(MinecraftServer server) {
		boolean secondTick = server.getTicks() % 20 == 0;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			UUID id = player.getUuid();

			Pending pending = PENDING.get(id);
			if (pending != null) {
				tickPrecursor(player, pending);
				continue;
			}
			if (!secondTick || player.isSpectator()) continue;

			if (NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey())) {
				checkDampCarpet(player);
			} else if (!player.isCreative()) {
				rollFall(player);
			}
		}
	}

	/** Запустить провал с предвестниками (используется и командой). */
	public static void begin(ServerPlayerEntity player) {
		NullspaceConfig cfg = NullspaceConfig.get();
		int min = Math.max(1, cfg.precursorMinTicks);
		int max = Math.max(min, cfg.precursorMaxTicks);
		int total = min + player.getRandom().nextInt(max - min + 1);
		PENDING.put(player.getUuid(), new Pending(total, total));
	}

	public static void forget(ServerPlayerEntity player) {
		PENDING.remove(player.getUuid());
		DAMP_SECONDS.remove(player.getUuid());
	}

	private static void rollFall(ServerPlayerEntity player) {
		NullspaceConfig cfg = NullspaceConfig.get();
		ServerWorld world = player.getServerWorld();
		BlockPos pos = player.getBlockPos();
		double roll = player.getRandom().nextDouble();

		if (player.isInsideWall() && roll < cfg.chanceInsideWall) {
			begin(player);
			return;
		}

		boolean deepDark = pos.getY() < cfg.darknessMaxY
				&& !world.isSkyVisible(pos)
				&& world.getLightLevel(pos) == 0;
		if (deepDark && roll < cfg.chanceDarkness) {
			begin(player);
		}
	}

	/** Мокрое пятно: присесть на нём 3 секунды — и пол поддаётся. */
	private static void checkDampCarpet(ServerPlayerEntity player) {
		UUID id = player.getUuid();
		boolean onDamp = player.isOnGround()
				&& player.getWorld().getBlockState(player.getBlockPos().down()).isOf(ModBlocks.DAMP_CARPET);
		if (!onDamp || !player.isSneaking()) {
			DAMP_SECONDS.remove(id);
			return;
		}
		int seconds = DAMP_SECONDS.merge(id, 1, Integer::sum);
		if (seconds == 1) {
			player.sendMessage(Text.translatable("message.nullspace.damp_soft").formatted(Formatting.GRAY), true);
		}
		if (seconds >= 3) {
			DAMP_SECONDS.remove(id);
			player.sendMessage(Text.translatable("message.nullspace.damp_gives").formatted(Formatting.GRAY), true);
			PENDING.put(id, new Pending(30, 30));
		}
	}

	/**
	 * Предвестники: на долю секунды пропадает звук, в чате мелькает пустое
	 * сообщение без автора, перед самым провалом сгущается темнота.
	 */
	private static void tickPrecursor(ServerPlayerEntity player, Pending pending) {
		int left = pending.left() - 1;
		int elapsed = pending.total() - left;

		if (elapsed == 1) {
			NullspaceTeleporter.cutAllSounds(player);
		}
		if (elapsed == pending.total() / 2) {
			player.sendMessage(Text.literal(" "), false);
			player.playSoundToPlayer(ModSounds.DISTANT, SoundCategory.AMBIENT, 0.25f, 0.5f);
		}
		if (left == 25) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false));
		}

		if (left <= 0) {
			PENDING.remove(player.getUuid());
			NullspaceTeleporter.sendToNullspace(player);
		} else {
			PENDING.put(player.getUuid(), new Pending(pending.total(), left));
		}
	}

	private NoclipManager() {
	}
}
