package com.nullspace.network;

import com.nullspace.NullspaceMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Сервер говорит клиенту: «замолчи» на указанное число тиков. */
public record SilencePayload(int ticks) implements CustomPayload {
	public static final CustomPayload.Id<SilencePayload> ID = new CustomPayload.Id<>(NullspaceMod.id("silence"));
	public static final PacketCodec<RegistryByteBuf, SilencePayload> CODEC =
			PacketCodec.tuple(PacketCodecs.VAR_INT, SilencePayload::ticks, SilencePayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
