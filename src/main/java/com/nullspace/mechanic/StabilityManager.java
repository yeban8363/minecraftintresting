package com.nullspace.mechanic;

import com.nullspace.block.GeneratorBlock;
import com.nullspace.network.StabilityPayload;
import com.nullspace.registry.ModAttachments;
import com.nullspace.registry.ModBlocks;
import com.nullspace.world.NullspaceDimensions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

/**
 * Стабильность — связь игрока с реальностью (0..100).
 *
 * В NULLSPACE каждую секунду:
 *   базово            −0.15
 *   темно (свет < 5)   −0.45
 *   светло (свет ≥ 12) +0.25
 *   во время тишины    −0.30
 *   рядом генератор    +1.60
 * В обычном мире быстро восстанавливается.
 * На нуле — «распад»: пространство выплёвывает тебя в другом месте.
 */
public final class StabilityManager {
	public static final float MAX = 100f;
	private static final int[] THRESHOLDS = {70, 45, 20};

	public static float get(ServerPlayerEntity player) {
		Float v = player.getAttached(ModAttachments.STABILITY);
		return v == null ? MAX : v;
	}

	public static void set(ServerPlayerEntity player, float value) {
		player.setAttached(ModAttachments.STABILITY, Math.max(0f, Math.min(MAX, value)));
		sync(player);
	}

	public static void add(ServerPlayerEntity player, float delta) {
		set(player, get(player) + delta);
	}

	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			boolean inside = NullspaceDimensions.isNullspace(player.getWorld().getRegistryKey());
			float before = get(player);
			float now = before;

			if (inside && !player.isCreative() && !player.isSpectator()) {
				ServerWorld world = player.getServerWorld();
				BlockPos head = player.getBlockPos().up();
				int light = world.getLightLevel(LightType.BLOCK, head);

				float delta = -0.15f;
				if (light < 5) delta -= 0.45f;
				else if (light >= 12) delta += 0.25f;
				if (AtmosphereDirector.isSilent(player)) delta -= 0.30f;
				if (player.isSubmergedInWater()) delta -= 0.40f;
				if (nearRunningGenerator(world, player.getBlockPos())) delta += 1.60f;
				now = before + delta;
			} else if (!inside) {
				now = before + 2.0f;
			}

			now = Math.max(0f, Math.min(MAX, now));
			if (now != before) {
				player.setAttached(ModAttachments.STABILITY, now);
			}

			if (inside) {
				for (int t : THRESHOLDS) {
					if (before >= t && now < t) {
						player.sendMessage(Text.translatable("message.nullspace.stability_" + t)
								.formatted(Formatting.DARK_GRAY, Formatting.ITALIC), true);
					}
				}
				if (now <= 0f) {
					collapse(player);
					continue;
				}
			}
			if (inside || now != before) sync(player);
		}
	}

	/** Распад: Стабильность кончилась, пространство перебрасывает игрока. */
	private static void collapse(ServerPlayerEntity player) {
		player.setAttached(ModAttachments.STABILITY, 35f);
		NullspaceTeleporter.sendToNullspace(player);
		player.sendMessage(Text.translatable("message.nullspace.collapse").formatted(Formatting.DARK_RED), false);
		sync(player);
	}

	public static boolean nearRunningGenerator(ServerWorld world, BlockPos center) {
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = -6; dz <= 6; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockState state = world.getBlockState(p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz));
					if (state.isOf(ModBlocks.GENERATOR) && state.get(GeneratorBlock.LIT)) return true;
				}
			}
		}
		return false;
	}

	public static void sync(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, StabilityPayload.ID)) {
			ServerPlayNetworking.send(player, new StabilityPayload(get(player)));
		}
	}

	private StabilityManager() {
	}
}
