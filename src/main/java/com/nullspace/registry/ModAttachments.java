package com.nullspace.registry;

import com.mojang.serialization.Codec;
import com.nullspace.NullspaceMod;
import com.nullspace.mechanic.PocketReturn;
import com.nullspace.mechanic.ReturnPoint;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

@SuppressWarnings("UnstableApiUsage")
public final class ModAttachments {
	/** Точка возврата в обычный мир. Сохраняется в данных игрока и переживает смерть. */
	public static final AttachmentType<ReturnPoint> RETURN_POINT = AttachmentRegistry.<ReturnPoint>builder()
			.persistent(ReturnPoint.CODEC)
			.copyOnDeath()
			.buildAndRegister(NullspaceMod.id("return_point"));

	/** Стабильность — связь с реальностью, 0..100. После смерти сбрасывается. */
	public static final AttachmentType<Float> STABILITY = AttachmentRegistry.<Float>builder()
			.persistent(Codec.FLOAT)
			.buildAndRegister(NullspaceMod.id("stability"));

	/** Откуда игрок вошёл в аномальную дверь (стадион). */
	public static final AttachmentType<PocketReturn> POCKET_RETURN = AttachmentRegistry.<PocketReturn>builder()
			.persistent(PocketReturn.CODEC)
			.copyOnDeath()
			.buildAndRegister(NullspaceMod.id("pocket_return"));

	public static void register() {
	}

	private ModAttachments() {
	}
}
