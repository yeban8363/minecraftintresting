package com.nullspace.client;

import com.nullspace.NullspaceMod;
import com.nullspace.item.FlashlightItem;
import com.nullspace.item.NoteItem;
import com.nullspace.network.SilencePayload;
import com.nullspace.network.StabilityPayload;
import com.nullspace.registry.ModBlocks;
import com.nullspace.registry.ModEntities;
import com.nullspace.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.render.RenderLayer;

public class NullspaceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Модель фонарика: включён / выключен.
		ModelPredicateProviderRegistry.register(ModItems.FLASHLIGHT, NullspaceMod.id("on"),
				(stack, world, entity, seed) -> FlashlightItem.isOn(stack) ? 1.0f : 0.0f);

		BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getCutout(), ModBlocks.CHALK_MARK, ModBlocks.PAPER_PILE);

		NoteItem.opener = NoteScreens::open;

		ClientPlayNetworking.registerGlobalReceiver(SilencePayload.ID,
				(payload, context) -> ClientAtmosphere.silence(payload.ticks()));
		ClientPlayNetworking.registerGlobalReceiver(StabilityPayload.ID,
				(payload, context) -> ClientStability.set(payload.value()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientAtmosphere.tick(client);
			ClientStability.tick(client);
			Hallucinations.tick(client);
		});

		HudRenderCallback.EVENT.register(ClientStability::renderHud);

		EntityRendererRegistry.register(ModEntities.TENANT, ctx -> new ShadowRenderer<>(ctx,
				NullspaceMod.id("textures/entity/tenant.png"), NullspaceMod.id("textures/entity/tenant_eyes.png"),
				0.92f, 1.28f, false));
		EntityRendererRegistry.register(ModEntities.SWIMMER, ctx -> new ShadowRenderer<>(ctx,
				NullspaceMod.id("textures/entity/swimmer.png"), NullspaceMod.id("textures/entity/swimmer_eyes.png"),
				1.0f, 1.0f, true));
	}
}
