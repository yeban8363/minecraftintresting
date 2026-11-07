package com.nullspace.mechanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Где игрок был до провала: туда ведёт дверь EXIT. */
public record ReturnPoint(RegistryKey<World> dimension, BlockPos pos) {
	public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			RegistryKey.createCodec(RegistryKeys.WORLD).fieldOf("dimension").forGetter(ReturnPoint::dimension),
			BlockPos.CODEC.fieldOf("pos").forGetter(ReturnPoint::pos)
	).apply(instance, ReturnPoint::new));
}
