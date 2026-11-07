package com.nullspace.registry;

import com.nullspace.NullspaceMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
	public static final SoundEvent LAMP_HUM = register("ambient.lamp_hum");
	public static final SoundEvent LAMP_BUZZ = register("block.lamp_buzz");
	public static final SoundEvent STEPS_BEHIND_WALL = register("ambient.steps_behind_wall");
	public static final SoundEvent KNOCK = register("ambient.knock");
	public static final SoundEvent DISTANT = register("ambient.distant");
	public static final SoundEvent BREATH = register("ambient.breath");
	public static final SoundEvent NOCLIP_FALL = register("event.noclip_fall");
	public static final SoundEvent FLASHLIGHT_CLICK = register("item.flashlight_click");
	public static final SoundEvent GENERATOR_HUM = register("block.generator_hum");
	public static final SoundEvent GENERATOR_START = register("block.generator_start");
	public static final SoundEvent GENERATOR_STOP = register("block.generator_stop");
	public static final SoundEvent HEARTBEAT = register("ambient.heartbeat");
	public static final SoundEvent WHISPER = register("ambient.whisper");
	public static final SoundEvent CHALK = register("item.chalk");
	public static final SoundEvent STADIUM_ENTER = register("event.stadium_enter");
	/** «Музыка» биома — тишина. Нужна, чтобы в NULLSPACE не играла обычная музыка. */
	public static final SoundEvent MUSIC_SILENCE = register("music.silence");

	private static SoundEvent register(String name) {
		Identifier id = NullspaceMod.id(name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	public static RegistryEntry<SoundEvent> entry(SoundEvent sound) {
		return Registries.SOUND_EVENT.getEntry(sound);
	}

	public static void register() {
	}

	private ModSounds() {
	}
}
