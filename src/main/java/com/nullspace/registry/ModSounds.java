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
	// 0.3 «Глубина»
	public static final SoundEvent POOL_AMBIENCE = register("ambient.pool");
	public static final SoundEvent WET_STEPS = register("ambient.wet_steps");
	public static final SoundEvent WATER_DISTANT = register("ambient.water_distant");
	public static final SoundEvent BLACKOUT = register("event.blackout");
	public static final SoundEvent POWER_ON = register("event.power_on");
	public static final SoundEvent DRAIN_ENGINE = register("block.drain_engine");
	// 0.4 PARKING
	public static final SoundEvent PARKING_AMBIENCE = register("ambient.parking");
	public static final SoundEvent CONCRETE_STEPS = register("ambient.concrete_steps");
	public static final SoundEvent PARKING_DISTANT = register("ambient.parking_distant");
	public static final SoundEvent CAR_ALARM = register("ambient.car_alarm");
	public static final SoundEvent ENGINE_IDLE = register("ambient.engine_idle");
	public static final SoundEvent HEADLIGHT_CLICK = register("ambient.headlight_click");
	public static final SoundEvent ELEVATOR_DING = register("block.elevator_ding");
	public static final SoundEvent MALL_MUSIC = register("ambient.mall_music");
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
