package com.nullspace.client;

import com.nullspace.NullspaceMod;
import com.nullspace.item.FlashlightItem;
import com.nullspace.network.SilencePayload;
import com.nullspace.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.item.ModelPredicateProviderRegistry;

public class NullspaceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Модель фонарика: включён / выключен.
		ModelPredicateProviderRegistry.register(ModItems.FLASHLIGHT, NullspaceMod.id("on"),
				(stack, world, entity, seed) -> FlashlightItem.isOn(stack) ? 1.0f : 0.0f);

		ClientPlayNetworking.registerGlobalReceiver(SilencePayload.ID,
				(payload, context) -> ClientAtmosphere.silence(payload.ticks()));

		ClientTickEvents.END_CLIENT_TICK.register(ClientAtmosphere::tick);
	}
}
