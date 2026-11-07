package com.nullspace;

import com.nullspace.command.NullspaceCommand;
import com.nullspace.mechanic.AtmosphereDirector;
import com.nullspace.mechanic.FlashlightLightManager;
import com.nullspace.mechanic.NoclipManager;
import com.nullspace.mechanic.StabilityManager;
import com.nullspace.network.StabilityPayload;
import com.nullspace.registry.ModBlockEntities;
import com.nullspace.network.SilencePayload;
import com.nullspace.registry.ModAttachments;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModComponents;
import com.nullspace.registry.ModItems;
import com.nullspace.registry.ModSounds;
import com.nullspace.world.NullspaceDimensions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NullspaceMod implements ModInitializer {
	public static final String MOD_ID = "nullspace";
	public static final Logger LOGGER = LoggerFactory.getLogger("NULLSPACE");

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		NullspaceConfig.load();

		ModSounds.register();
		ModComponents.register();
		ModBlocks.register();
		ModItems.register();
		ModBlockEntities.register();
		ModAttachments.register();
		NullspaceDimensions.register();

		PayloadTypeRegistry.playS2C().register(SilencePayload.ID, SilencePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StabilityPayload.ID, StabilityPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				NullspaceCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			NoclipManager.tick(server);
			FlashlightLightManager.tick(server);
			AtmosphereDirector.tick(server);
			StabilityManager.tick(server);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> StabilityManager.sync(handler.player));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			FlashlightLightManager.clear(handler.player);
			NoclipManager.forget(handler.player);
			AtmosphereDirector.forget(handler.player);
		});

		LOGGER.info("Reality is stable. For now.");
	}
}
