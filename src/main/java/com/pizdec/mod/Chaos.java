package com.pizdec.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Случайные пиздецы. Прилетают раз в 1.5–3 минуты одному случайному игроку. */
public final class Chaos {
	private record Event(String text, Consumer<ServerPlayerEntity> act) {}

	private static final List<Event> EVENTS = new ArrayList<>();

	private static void ev(String text, Consumer<ServerPlayerEntity> act) {
		EVENTS.add(new Event(text, act));
	}

	private Chaos() {}

	static {
		ev("на %s упала наковальня. Кто её там оставил?", p -> {
			ServerWorld w = p.getServerWorld();
			BlockPos pos = p.getBlockPos().up(14);
			if (w.getBlockState(pos).isAir()) FallingBlockEntity.spawnFromBlock(w, pos, Blocks.ANVIL.getDefaultState());
		});

		ev("на %s пошёл дождь из хлама", p -> {
			Item[] junk = {Items.ROTTEN_FLESH, Items.DIRT, Items.STICK, Items.POISONOUS_POTATO, Items.DEAD_BUSH,
					Items.BONE, Items.STRING, Items.COBBLESTONE, Items.WHEAT_SEEDS, Items.FEATHER};
			ServerWorld w = p.getServerWorld();
			for (int i = 0; i < 14; i++) {
				Item it = i == 0 && U.chance(0.3) ? Items.DIAMOND : U.pick(junk);
				ItemEntity e = new ItemEntity(w, p.getX() + U.between(-3, 3), p.getY() + 8, p.getZ() + U.between(-3, 3),
						new ItemStack(it, U.between(1, 4)));
				w.spawnEntity(e);
			}
		});

		ev("у %s взорвался газовый баллон", p -> p.getServerWorld().createExplosion(null, p.getX() + 1, p.getY(),
				p.getZ() + 1, 1.5f, World.ExplosionSourceType.NONE));

		ev("к %s пришли крысы из подвала", p -> {
			for (int i = 0; i < 4; i++) U.spawnNear(EntityType.SILVERFISH, p, 3);
		});

		ev("к %s пришёл кот-налоговик", p -> {
			CatEntity c = U.spawnNear(EntityType.CAT, p, 2);
			if (c != null) {
				c.setCustomName(U.t("Кот-налоговик Барсик", Formatting.GOLD));
				c.setCustomNameVisible(true);
			}
		});

		ev("%s нашёл заначку под кроватью: +40 бабла", p -> Money.give(p, 40, "заначка"));

		ev("хакеры украли у %s 30 бабла", p -> Money.fine(p, 30, "кибератака"));

		ev("%s подбросило батутом", p -> {
			p.addVelocity(0, 1.6, 0);
			p.velocityModified = true;
		});

		ev("у %s завелись фантомы среди бела дня", p -> {
			for (int i = 0; i < 2; i++) {
				PhantomEntity ph = U.spawn(EntityType.PHANTOM, p.getServerWorld(), p.getX() + U.between(-6, 6),
						p.getY() + 12, p.getZ() + U.between(-6, 6));
				if (ph != null) ph.setTarget(p);
			}
		});

		ev("%s получил гуманитарку: золотое яблоко", p -> p.getInventory().offerOrDrop(new ItemStack(Items.GOLDEN_APPLE)));

		ev("у %s Minecraft.exe не отвечает", p -> {
			U.title(p, U.t("Minecraft.exe не отвечает", Formatting.WHITE, Formatting.BOLD),
					U.t("Подождите… или не ждите", Formatting.GRAY));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 6));
		});

		ev("%s уронил хотбар и собрал его как попало", p -> {
			PlayerInventory inv = p.getInventory();
			for (int i = 0; i < 9; i++) {
				ItemStack a = inv.getStack(i);
				inv.setStack(i, inv.getStack(i + 27));
				inv.setStack(i + 27, a);
			}
		});

		ev("%s внезапно переехал", p -> {
			ServerWorld w = p.getServerWorld();
			int x = p.getBlockX() + U.between(-60, 60);
			int z = p.getBlockZ() + U.between(-60, 60);
			int y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			p.teleport(w, x + 0.5, y, z + 0.5, p.getYaw(), p.getPitch());
		});

		ev("к %s приехал пчелиный рой", p -> {
			for (int i = 0; i < 6; i++) U.spawnNear(EntityType.BEE, p, 3);
		});

		ev("%s отравился шаурмой", p -> {
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200, 0));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 0));
		});

		ev("%s выпил энергетик", p -> {
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 400, 4));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 400, 2));
		});

		ev("у %s налоговая нашла долг из прошлой жизни: −25", p -> Money.fine(p, 25, "долг из прошлой жизни"));

		ev("к %s внепланово выехал коллектор", Tax::sendCollector);

		ev("%s выиграл в казино: +77 бабла", p -> Money.give(p, 77, "казино"));
	}

	public static void random(MinecraftServer s) {
		List<ServerPlayerEntity> ps = U.players(s);
		if (ps.isEmpty()) return;
		ServerPlayerEntity p = U.pick(ps);
		Event e = U.pick(EVENTS);
		U.say(s, U.t("⚡ ПИЗДЕЦ: " + String.format(e.text, p.getName().getString()), Formatting.LIGHT_PURPLE));
		U.sound(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.7f, 1.2f);
		e.act.accept(p);
	}
}
