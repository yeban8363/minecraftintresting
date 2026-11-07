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

	public static void register() {
	}

	private ModComponents() {
	}
}
