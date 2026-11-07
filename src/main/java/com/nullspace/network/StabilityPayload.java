package com.nullspace.network;

import com.nullspace.NullspaceMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Текущая Стабильность игрока (0..100) для клиентских эффектов. */
public record StabilityPayload(float value) implements CustomPayload {
	public static final CustomPayload.Id<StabilityPayload> ID = new CustomPayload.Id<>(NullspaceMod.id("stability"));
	public static final PacketCodec<RegistryByteBuf, StabilityPayload> CODEC =
			PacketCodec.tuple(PacketCodecs.FLOAT, StabilityPayload::value, StabilityPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
