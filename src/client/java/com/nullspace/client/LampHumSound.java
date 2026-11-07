package com.nullspace.client;

import com.nullspace.registry.ModSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.world.LightType;

/**
 * Непрерывный гул ламп. Громкость зависит от яркости вокруг:
 * под лампой гудит сильнее, в тёмной зоне почти не слышно.
 * Во время «тишины» плавно уходит в ноль.
 */
public class LampHumSound extends MovingSoundInstance {
	private final ClientPlayerEntity player;

	public LampHumSound(ClientPlayerEntity player) {
		super(ModSounds.LAMP_HUM, SoundCategory.AMBIENT, SoundInstance.createRandom());
		this.player = player;
		this.repeat = true;
		this.repeatDelay = 0;
		this.volume = 0.01f;
		this.relative = true;
		this.attenuationType = SoundInstance.AttenuationType.NONE;
		this.x = 0;
		this.y = 0;
		this.z = 0;
	}

	@Override
	public void tick() {
		if (player.isRemoved() || !ClientAtmosphere.inNullspace(MinecraftClient.getInstance())) {
			setDone();
			return;
		}
		float target;
		if (ClientAtmosphere.isSilent()) {
			target = 0.0f;
		} else {
			int light = player.getWorld().getLightLevel(LightType.BLOCK, player.getBlockPos().up());
			target = 0.08f + 0.55f * (light / 15.0f);
		}
		volume += (target - volume) * 0.05f;
		pitch = 1.0f + (float) Math.sin(player.age * 0.013) * 0.01f;
	}

	@Override
	public boolean shouldAlwaysPlay() {
		return true;
	}
}
