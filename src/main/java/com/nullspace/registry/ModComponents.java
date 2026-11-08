package com.nullspace.registry;

import com.mojang.serialization.Codec;
import com.nullspace.NullspaceMod;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModComponents {
	/** Включён ли фонарик. */
	public static final ComponentType<Boolean> FLASHLIGHT_ON = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			NullspaceMod.id("flashlight_on"),
			ComponentType.<Boolean>builder().codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL).build());

	/** Номер записки исследователя. */
	public static final ComponentType<Integer> NOTE_ID = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			NullspaceMod.id("note_id"),
			ComponentType.<Integer>builder().codec(Codec.INT).packetCodec(PacketCodecs.VAR_INT).build());

	/** Радио включено. */
	public static final ComponentType<Boolean> RADIO_ON = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			NullspaceMod.id("radio_on"),
			ComponentType.<Boolean>builder().codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL).build());

	/** Номер частоты радио. */
	public static final ComponentType<Integer> RADIO_FREQ = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			NullspaceMod.id("radio_freq"),
			ComponentType.<Integer>builder().codec(Codec.INT).packetCodec(PacketCodecs.VAR_INT).build());

	public static void register() {
	}

	private ModComponents() {
	}
}
