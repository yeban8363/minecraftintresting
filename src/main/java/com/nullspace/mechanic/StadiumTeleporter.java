package com.nullspace.mechanic;

import com.nullspace.NullspaceMod;
import com.nullspace.registry.ModAttachments;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import com.nullspace.world.StadiumLayout;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * Аномальная дверь: маленькая дверь, за которой целый стадион.
 * Все такие двери ведут в один и тот же стадион.
 */
public final class StadiumTeleporter {

	public static void use(ServerPlayerEntity player) {
		if (player.getWorld().getRegistryKey() == NullspaceDimensions.STADIUM) {
			leave(player);
		} else {
			enter(player);
		}
	}

	private static void enter(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerWorld stadium = server.getWorld(NullspaceDimensions.STADIUM);
		if (stadium == null) {
			NullspaceMod.LOGGER.error("Измерение nullspace:stadium не загружено");
			return;
		}

		// Вернём игрока на шаг назад от двери, лицом в противоположную сторону.
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d back = player.getPos().subtract(look.x * 0.6, 0, look.z * 0.6);
		player.setAttached(ModAttachments.POCKET_RETURN,
				new PocketReturn(player.getWorld().getRegistryKey(), back, player.getYaw() + 180f));

		NullspaceTeleporter.cutAllSounds(player);
		player.teleport(stadium, StadiumLayout.SPAWN_X + 0.5, StadiumLayout.FLOOR_Y + 1, 0.5, -90f, 0f);
		player.playSoundToPlayer(ModSounds.STADIUM_ENTER, SoundCategory.AMBIENT, 1.0f, 1.0f);
		player.sendMessage(Text.translatable("message.nullspace.stadium").formatted(Formatting.GRAY, Formatting.ITALIC), true);
	}

	private static void leave(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;

		PocketReturn back = player.getAttached(ModAttachments.POCKET_RETURN);
		ServerWorld world = back != null ? server.getWorld(back.dimension()) : null;
		if (world == null) {
			// Не знаем, откуда пришёл — значит, обратно на Уровень 0.
			player.removeAttached(ModAttachments.POCKET_RETURN);
			NullspaceTeleporter.sendToNullspace(player);
			return;
		}
		NullspaceTeleporter.cutAllSounds(player);
		player.teleport(world, back.pos().x, back.pos().y, back.pos().z, back.yaw(), 0f);
		player.removeAttached(ModAttachments.POCKET_RETURN);
		player.sendMessage(Text.translatable("message.nullspace.stadium_leave").formatted(Formatting.GRAY, Formatting.ITALIC), true);
	}

	private StadiumTeleporter() {
	}
}
