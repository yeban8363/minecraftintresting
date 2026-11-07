package com.nullspace.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * Рендер «теней» — Жильца и Пловца. Человеческая фигура, почти чёрная,
 * без тени на полу, с тускло светящимися в темноте глазами.
 */
public class ShadowRenderer<T extends MobEntity> extends MobEntityRenderer<T, BipedEntityModel<T>> {
	private final Identifier texture;
	private final float scaleXZ, scaleY;
	private final boolean swimming;

	public ShadowRenderer(EntityRendererFactory.Context ctx, Identifier texture, Identifier eyes,
						  float scaleXZ, float scaleY, boolean swimming) {
		super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.ZOMBIE)), 0.0f);
		this.texture = texture;
		this.scaleXZ = scaleXZ;
		this.scaleY = scaleY;
		this.swimming = swimming;
		RenderLayer eyesLayer = RenderLayer.getEyes(eyes);
		addFeature(new EyesFeatureRenderer<T, BipedEntityModel<T>>(this) {
			@Override
			public RenderLayer getEyesTexture() {
				return eyesLayer;
			}
		});
	}

	@Override
	public Identifier getTexture(T entity) {
		return texture;
	}

	@Override
	protected void scale(T entity, MatrixStack matrices, float amount) {
		matrices.scale(scaleXZ, scaleY, scaleXZ);
	}

	@Override
	protected void setupTransforms(T entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta, float scale) {
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
		if (swimming) {
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90.0f - entity.getPitch()));
			matrices.translate(0.0f, -1.0f, 0.3f);
		}
	}
}
