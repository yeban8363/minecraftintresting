package com.nullspace.registry;

import com.nullspace.NullspaceMod;
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

	public static void register() {
	}

	private ModAttachments() {
	}
}
