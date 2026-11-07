package com.pizdec.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/** Законы Короля. Каждую минуту — новый. Действует, пока не придёт следующий. */
public final class Laws {
	public static final int PERIOD = 20 * 60;

	public interface Tick {
		void tick(ServerPlayerEntity p, int t);
	}

	public static final class Law {
		final String id;
		final String title;
		final String detail;
		Consumer<MinecraftServer> start = s -> {};
		Tick tick = (p, t) -> {};
		Consumer<MinecraftServer> end = s -> {};

		Law(String id, String title, String detail) {
			this.id = id;
			this.title = title;
			this.detail = detail;
		}

		Law start(Consumer<MinecraftServer> c) {
			start = c;
			return this;
		}

		Law tick(Tick c) {
			tick = c;
			return this;
		}

		Law end(Consumer<MinecraftServer> c) {
			end = c;
			return this;
		}

		/** Разовый эффект для каждого игрока в момент объявления. */
		Law each(Consumer<ServerPlayerEntity> c) {
			start = s -> U.players(s).forEach(c);
			return this;
		}
	}

	public static final List<Law> ALL = new ArrayList<>();
	public static Law current;
	private static int lawTicks;

	private static final Map<UUID, Vec3d> lastPos = new HashMap<>();
	private static final Map<UUID, Float> moved = new HashMap<>();
	private static final Map<UUID, ItemStack> confiscated = new HashMap<>();

	private static final Identifier SCALE = Identifier.of("pizdec", "law_scale");
	private static final Identifier GRAVITY = Identifier.of("pizdec", "law_gravity");
	private static final Identifier JUMP = Identifier.of("pizdec", "law_jump");
	private static final Identifier FALL = Identifier.of("pizdec", "law_fall");

	private Laws() {}

	public static boolean is(String id) {
		return current != null && current.id.equals(id);
	}

	public static String currentText() {
		return current == null ? "пока никаких, но это ненадолго" : current.title + " — " + current.detail;
	}

	/** Во сколько раз сейчас дороже платные штуки. */
	public static int priceMult() {
		return is("zhkh") ? 5 : 1;
	}

	// ------------------------------------------------------------------ помогалки для законов

	private static void eff(ServerPlayerEntity p, int t, RegistryEntry<StatusEffect> e, int amp) {
		if (t % 20 == 1) p.addStatusEffect(new StatusEffectInstance(e, 60, amp, false, false, true));
	}

	private static void attr(ServerPlayerEntity p, RegistryEntry<EntityAttribute> a, Identifier id, double v) {
		EntityAttributeInstance i = p.getAttributeInstance(a);
		if (i != null && !i.hasModifier(id)) {
			i.addTemporaryModifier(new EntityAttributeModifier(id, v, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	private static void clearAttrs(MinecraftServer s) {
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			removeAttr(p, EntityAttributes.GENERIC_SCALE, SCALE);
			removeAttr(p, EntityAttributes.GENERIC_GRAVITY, GRAVITY);
			removeAttr(p, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP);
			removeAttr(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, FALL);
		}
	}

	private static void removeAttr(ServerPlayerEntity p, RegistryEntry<EntityAttribute> a, Identifier id) {
		EntityAttributeInstance i = p.getAttributeInstance(a);
		if (i != null) i.removeModifier(id);
	}

	private static void hurt(ServerPlayerEntity p, float dmg, String why) {
		p.damage(p.getDamageSources().magic(), dmg);
		U.bar(p, U.t("⚖ " + why, Formatting.RED));
	}

	private static void tpSurface(ServerPlayerEntity p, int radius) {
		ServerWorld w = p.getServerWorld();
		int x = p.getBlockX() + U.between(-radius, radius);
		int z = p.getBlockZ() + U.between(-radius, radius);
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		p.teleport(w, x + 0.5, y, z + 0.5, p.getYaw(), p.getPitch());
	}

	private static void lightning(ServerPlayerEntity p, int radius) {
		ServerWorld w = p.getServerWorld();
		LightningEntity l = EntityType.LIGHTNING_BOLT.create(w);
		if (l == null) return;
		int x = p.getBlockX() + U.between(-radius, radius);
		int z = p.getBlockZ() + U.between(-radius, radius);
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
		l.refreshPositionAndAngles(x + 0.5, y, z + 0.5, 0f, 0f);
		l.setCosmetic(true);
		w.spawnEntity(l);
	}

	private static void give(ServerPlayerEntity p, ItemStack st) {
		p.getInventory().offerOrDrop(st);
	}

	private static void dropHand(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		ItemStack st = inv.getMainHandStack();
		if (st.isEmpty()) return;
		p.dropItem(st.copy(), true, false);
		inv.setStack(inv.selectedSlot, ItemStack.EMPTY);
	}

	private static float movedLastSecond(ServerPlayerEntity p) {
		return moved.getOrDefault(p.getUuid(), 0f);
	}

	// ------------------------------------------------------------------ сам свод законов

	private static Law law(String id, String title, String detail) {
		Law l = new Law(id, title, detail);
		ALL.add(l);
		return l;
	}

	static {
		law("no_jump", "Прыгать запрещено!", "каждый прыжок — штраф 5 бабла");

		law("must_jump", "Обязательные прыжки", "государство само подбрасывает граждан")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.JUMP_BOOST, 1);
					if (t % 60 == 0 && p.isOnGround()) {
						p.addVelocity(0, 0.7, 0);
						p.velocityModified = true;
					}
				});

		law("low_grav", "Гравитация отменена", "указом короля всё падает медленно")
				.tick((p, t) -> {
					attr(p, EntityAttributes.GENERIC_GRAVITY, GRAVITY, -0.85);
					attr(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, FALL, 5.0);
				});

		law("high_grav", "Гравитация ×3", "король уронил на планету гирю")
				.tick((p, t) -> {
					attr(p, EntityAttributes.GENERIC_GRAVITY, GRAVITY, 2.0);
					attr(p, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP, -0.4);
				});

		law("tiny", "Все граждане теперь карлики", "налог на рост отменён вместе с ростом")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_SCALE, SCALE, -0.7));

		law("giant", "Все граждане теперь великаны", "двери не наши проблемы")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_SCALE, SCALE, 1.5));

		law("speed", "День бега", "кто медленно — тот налоговый инспектор")
				.tick((p, t) -> eff(p, t, StatusEffects.SPEED, 3));

		law("snail", "Неделя улитки", "спешить запрещено (длится минуту)")
				.tick((p, t) -> eff(p, t, StatusEffects.SLOWNESS, 2));

		law("air_tax", "Налог на воздух", "каждые 10 секунд −2 бабла за вдох")
				.tick((p, t) -> {
					if (t % 200 == 0) Money.fine(p, 2, "налог на воздух");
				});

		law("generous", "Король щедр!", "всем по 50 бабла (не привыкайте)")
				.each(p -> Money.give(p, 50, "подарок короля"));

		law("lottery", "Национальная лотерея", "один случайный счастливчик получает 200 бабла")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.players(s);
					if (ps.isEmpty()) return;
					ServerPlayerEntity win = U.pick(ps);
					Money.give(win, 200, "выигрыш в лотерею");
					U.say(s, U.t("🎰 Выиграл " + win.getName().getString() + "! Остальные — лохи.", Formatting.GOLD));
				});

		law("denominate", "Деноминация", "у всех минус половина бабла. Экономика, ничего личного")
				.each(p -> {
					int b = Money.get(p);
					if (b > 0) Money.fine(p, b / 2, "деноминация");
				});

		law("confiscate", "Временная конфискация", "всё, что в руке, — в казну на минуту")
				.each(p -> {
					PlayerInventory inv = p.getInventory();
					ItemStack st = inv.getMainHandStack();
					if (st.isEmpty()) return;
					confiscated.put(p.getUuid(), st.copy());
					inv.setStack(inv.selectedSlot, ItemStack.EMPTY);
					U.bar(p, U.t("Изъято: " + st.getName().getString(), Formatting.RED));
				})
				.end(s -> {
					for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
						ItemStack st = confiscated.remove(p.getUuid());
						if (st != null) {
							give(p, st);
							U.msg(p, U.t("Король вернул твоё имущество. Почти не пользовался.", Formatting.GRAY));
						}
					}
					confiscated.clear();
				});

		law("night", "Объявлена ночь", "солнце уволено")
				.start(s -> s.getOverworld().setTimeOfDay(18000));

		law("day", "Солнце обязано светить", "дождь запрещён законом")
				.start(s -> {
					s.getOverworld().setTimeOfDay(1000);
					s.getOverworld().setWeather(6000, 0, false, false);
				});

		law("storm", "Гроза — государственная тайна", "никому не рассказывайте")
				.start(s -> s.getOverworld().setWeather(0, 1200, true, true));

		law("zeus", "Король тренируется в молниях", "держитесь подальше от короля и от себя")
				.tick((p, t) -> {
					if (t % 140 == 0) lightning(p, 6);
				});

		law("sheep", "Овечья мобилизация", "каждому гражданину — пять разноцветных овец")
				.each(p -> {
					for (int i = 0; i < 5; i++) {
						SheepEntity sh = U.spawnNear(EntityType.SHEEP, p, 4);
						if (sh != null) sh.setColor(U.pick(DyeColor.values()));
					}
				});

		law("chicken_rain", "Куриный дождь", "небо обязано выдавать по курице каждые 3 секунды")
				.tick((p, t) -> {
					if (t % 60 == 0) {
						U.spawn(EntityType.CHICKEN, p.getServerWorld(), p.getX() + U.between(-3, 3), p.getY() + 12,
								p.getZ() + U.between(-3, 3));
					}
				});

		law("only_sneak", "Ходить только гуськом", "кто идёт не приседая — получает по жопе")
				.tick((p, t) -> {
					if (t % 20 == 0 && movedLastSecond(p) > 0.6f && !p.isSneaking()) hurt(p, 1f, "Гуськом, сказали!");
				});

		law("no_sneak", "Приседать запрещено", "−2 бабла за каждую секунду на корточках")
				.tick((p, t) -> {
					if (t % 20 == 0 && p.isSneaking()) Money.fine(p, 2, "ты присел. Зачем?");
				});

		law("freeze", "Стоять смирно!", "двигаешься — получаешь урон")
				.tick((p, t) -> {
					if (t % 20 == 0 && movedLastSecond(p) > 0.6f) hurt(p, 1f, "Смирно, сказал король!");
				});

		law("floor_lava", "Пол — это лава!", "стоять на земле больно, прыгайте")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.JUMP_BOOST, 2);
					if (t % 30 == 0 && p.isOnGround()) hurt(p, 1f, "Пол — лава!");
				});

		law("chat_paid", "Свобода слова платная", "каждое сообщение в чате — 5 бабла");

		law("chat_reward", "Болтовня оплачивается государством", "+3 бабла за каждое сообщение в чате");

		law("no_dig", "Копать запрещено", "каждый сломанный блок — 3 бабла, и руки как у ленивца")
				.tick((p, t) -> eff(p, t, StatusEffects.MINING_FATIGUE, 1));

		law("build_reward", "Стройка века", "+2 бабла за каждый сломанный блок");

		law("money_trees", "Деньги растут на деревьях", "за каждое бревно +5 бабла");

		law("zhkh", "Тарифы ЖКХ ×5", "кровать, сундуки и верстаки теперь в 5 раз дороже");

		law("darkness", "Режим экономии электричества", "в королевстве отключили свет")
				.tick((p, t) -> eff(p, t, StatusEffects.DARKNESS, 0));

		law("night_vision", "Бесплатное ночное зрение", "король купил всем фонарики в глаза")
				.tick((p, t) -> eff(p, t, StatusEffects.NIGHT_VISION, 0));

		law("butter_fingers", "Скользкие руки", "каждые 10 секунд всё из руки падает")
				.tick((p, t) -> {
					if (t % 200 == 0) dropHand(p);
				});

		law("shuffle", "Реформа инвентаря", "все вещи разложены по-новому. Так удобнее (королю)")
				.each(p -> {
					PlayerInventory inv = p.getInventory();
					List<ItemStack> l = new ArrayList<>();
					for (int i = 0; i < 36; i++) l.add(inv.getStack(i));
					Collections.shuffle(l, U.R);
					for (int i = 0; i < 36; i++) inv.setStack(i, l.get(i));
				});

		law("fire_party", "Огненная вечеринка", "все горят, но не умирают. Наверное")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.FIRE_RESISTANCE, 0);
					if (t % 100 == 0) p.setOnFireFor(3f);
				});

		law("double_tax", "Налоги удвоены", "следующая проверка налоговой — ×2")
				.start(s -> Tax.multiplier = 2.0);

		law("triple_tax", "Налоги утроены", "король проиграл казну в карты")
				.start(s -> Tax.multiplier = 3.0);

		law("amnesty", "Налоговая амнистия", "следующую проверку налоговая пропустит")
				.start(s -> Tax.amnesty = true);

		law("diamond", "Всем по алмазу", "король сегодня добрый, не спрашивайте почему")
				.each(p -> give(p, new ItemStack(Items.DIAMOND)));

		law("cake", "Пусть едят торт", "каждому гражданину по торту")
				.each(p -> give(p, new ItemStack(Items.CAKE)));

		law("diet", "Обязательная диета", "король считает, что вы зажрались")
				.tick((p, t) -> eff(p, t, StatusEffects.HUNGER, 2));

		law("feast", "Пир на весь мир", "сытость и регенерация за счёт казны")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.SATURATION, 0);
					eff(p, t, StatusEffects.REGENERATION, 0);
				});

		law("creeper_citizens", "Криперы — тоже граждане", "к каждому подселён гражданин Крипер")
				.each(p -> {
					CreeperEntity c = U.spawnNear(EntityType.CREEPER, p, 8);
					if (c != null) {
						c.setCustomName(U.t("Гражданин Крипер", Formatting.GREEN));
						c.setCustomNameVisible(true);
					}
				});

		law("teleport_day", "День телепортации", "каждые 15 секунд — переезд за счёт государства")
				.tick((p, t) -> {
					if (t % 300 == 0) tpSurface(p, 30);
				});

		law("pigs", "Свиньи назначены министрами", "у каждого свой министр")
				.each(p -> {
					PigEntity pig = U.spawnNear(EntityType.PIG, p, 3);
					if (pig != null) {
						pig.setCustomName(U.t(U.pick("Министр финансов", "Министр налогов", "Министр хрюканья",
								"Министр по делам картошки"), Formatting.LIGHT_PURPLE));
						pig.setCustomNameVisible(true);
					}
				});

		law("wind", "Ветер перемен", "государство толкает граждан в светлое будущее")
				.tick((p, t) -> {
					if (t % 40 == 0) {
						double a = U.R.nextDouble() * Math.PI * 2;
						p.addVelocity(Math.cos(a) * 0.9, 0.35, Math.sin(a) * 0.9);
						p.velocityModified = true;
					}
				});

		law("fish", "Все граждане — рыбы", "вне воды −1 бабло в 2 секунды")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.WATER_BREATHING, 0);
					eff(p, t, StatusEffects.DOLPHINS_GRACE, 0);
					if (t % 40 == 0 && !p.isTouchingWaterOrRain()) Money.fine(p, 1, "рыба на суше");
				});

		law("bees", "Король любит пчёл", "пчёлы любят вас. Пока что")
				.each(p -> {
					for (int i = 0; i < 4; i++) U.spawnNear(EntityType.BEE, p, 4);
				});

		law("flower", "Обязательно держать цветок", "без цветка в руке −3 бабла каждые 5 секунд")
				.tick((p, t) -> {
					if (t % 100 == 0 && !p.getMainHandStack().isIn(ItemTags.SMALL_FLOWERS)) Money.fine(p, 3, "где цветок?!");
				});

		law("empty_hands", "Руки должны быть пустыми", "всё, что в руке через 5 секунд, падает на землю")
				.tick((p, t) -> {
					if (t % 100 == 0) dropHand(p);
				});

		law("xp_gift", "Образование бесплатно", "всем по 10 уровней опыта")
				.each(p -> p.addExperienceLevels(10));

		law("xp_tax", "Налог на знания", "минус 5 уровней. Умные никому не нужны")
				.each(p -> p.addExperienceLevels(-5));

		law("disco", "Королевская дискотека", "все светятся, играет музыка")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.GLOWING, 0);
					if (t % 10 == 0) {
						U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.5f + U.R.nextFloat() * 1.5f);
						p.getServerWorld().spawnParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2.2, p.getZ(), 1, 0.3,
								0.2, 0.3, 1.0);
					}
				});

		law("spies", "Все — шпионы", "невидимость для всех. Никто никого не видел")
				.tick((p, t) -> eff(p, t, StatusEffects.INVISIBILITY, 0));

		law("immortal", "Смерть временно запрещена", "минуту можно не умирать")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.RESISTANCE, 4);
					eff(p, t, StatusEffects.REGENERATION, 1);
				});

		law("swap", "Обмен телами", "все граждане меняются местами")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.players(s);
					if (ps.size() < 2) {
						for (ServerPlayerEntity p : ps) {
							p.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw() + 180f, p.getPitch());
							U.msg(p, U.t("Ты один, поэтому поменялся местами сам с собой. Развернись.", Formatting.GRAY));
						}
						return;
					}
					List<Vec3d> pos = new ArrayList<>();
					List<ServerWorld> worlds = new ArrayList<>();
					for (ServerPlayerEntity p : ps) {
						pos.add(p.getPos());
						worlds.add(p.getServerWorld());
					}
					for (int i = 0; i < ps.size(); i++) {
						int j = (i + 1) % ps.size();
						Vec3d v = pos.get(j);
						ps.get(i).teleport(worlds.get(j), v.x, v.y, v.z, ps.get(i).getYaw(), ps.get(i).getPitch());
					}
				});

		law("skydive", "Национальный день парашютиста", "всех на 40 блоков вверх, медленное падение прилагается")
				.each(p -> {
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 30, 0));
					p.teleport(p.getServerWorld(), p.getX(), p.getY() + 40, p.getZ(), p.getYaw(), p.getPitch());
				});

		law("pay_to_walk", "Платные дороги", "−1 бабло за каждую секунду ходьбы")
				.tick((p, t) -> {
					if (t % 20 == 0 && movedLastSecond(p) > 0.6f) Money.fine(p, 1, "проезд по платной дороге");
				});

		law("salary", "Зарплата!", "каждые 10 секунд +3 бабла. Король заболел?")
				.tick((p, t) -> {
					if (t % 200 == 0) Money.give(p, 3, "зарплата");
				});

		law("kill_tax", "Мобы под защитой государства", "за убийство моба штраф 5 бабла");

		law("bounty", "Охота за головами", "за каждого убитого моба +10 бабла");
	}

	// ------------------------------------------------------------------ движок

	public static void next(MinecraftServer s) {
		if (current != null) {
			current.end.accept(s);
			clearAttrs(s);
		}
		Law l;
		do {
			l = U.pick(ALL);
		} while (l == current && ALL.size() > 1);
		current = l;
		lawTicks = 0;

		PizdecState st = PizdecState.get(s);
		st.decrees++;
		st.markDirty();

		U.say(s, U.t("📜 Указ №" + st.decrees + " Его Величества " + Castle.KING_NAME + ":", Formatting.GOLD));
		U.say(s, U.t("   «" + l.title + "» — " + l.detail, Formatting.YELLOW));
		U.titleAll(s, U.t("📜 " + l.title, Formatting.GOLD, Formatting.BOLD), U.t(l.detail, Formatting.YELLOW));
		U.soundAll(s, SoundEvents.BLOCK_BELL_USE, 1.5f, 1.2f);

		VillagerEntity king = Castle.findKing(s);
		if (king != null) {
			((ServerWorld) king.getWorld()).spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, king.getX(), king.getY() + 2,
					king.getZ(), 40, 0.5, 0.5, 0.5, 0.3);
		}

		l.start.accept(s);
	}

	public static void tick(MinecraftServer s) {
		lawTicks++;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (lawTicks % 20 == 0) {
				Vec3d now = p.getPos();
				Vec3d was = lastPos.put(p.getUuid(), now);
				float d = was == null ? 0f : (float) Math.sqrt(Math.pow(now.x - was.x, 2) + Math.pow(now.z - was.z, 2));
				moved.put(p.getUuid(), d);
			}
		}
		if (current == null) return;
		for (ServerPlayerEntity p : U.players(s)) current.tick.tick(p, lawTicks);
	}

	/** Вызывается из главного класса, когда игрок прыгнул. */
	public static void onJump(ServerPlayerEntity p) {
		if (is("no_jump")) Money.fine(p, 5, "прыжок без лицензии");
	}

	public static void forget(UUID id) {
		lastPos.remove(id);
		moved.remove(id);
	}
}
