package com.nullspace.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.world.ClientWorld;

/** Двойник: существует только на твоём клиенте и выглядит как ты. */
public class Doppelganger extends OtherClientPlayerEntity {
	public Doppelganger(ClientWorld world, GameProfile profile) {
		super(world, profile);
	}

	@Override
	public SkinTextures getSkinTextures() {
		ClientPlayerEntity me = MinecraftClient.getInstance().player;
		return me != null ? me.getSkinTextures() : super.getSkinTextures();
	}
}
