package com.nullspace.mechanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Точка, куда вернуться из стадиона: прямо перед дверью, лицом от неё. */
public record PocketReturn(RegistryKey<World> dimension, Vec3d pos, float yaw) {
	public static final Codec<PocketReturn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			RegistryKey.createCodec(RegistryKeys.WORLD).fieldOf("dimension").forGetter(PocketReturn::dimension),
			Vec3d.CODEC.fieldOf("pos").forGetter(PocketReturn::pos),
			Codec.FLOAT.fieldOf("yaw").forGetter(PocketReturn::yaw)
	).apply(instance, PocketReturn::new));
}
