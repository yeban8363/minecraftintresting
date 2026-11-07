package com.pizdec.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
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

/** Законы Короля. Каждые 40 секунд — новый. Нарушил — карета, тюрьма. */
public final class Laws {
	public static final int PERIOD = 20 * 40;
	/** Сколько тиков даётся на ознакомление с новым указом. */
	public static final int GRACE = 20 * 5;

	public interface Tick {
		void tick(ServerPlayerEntity p, int t);
	}

	public static final class Law {
		final String id;
		final String title;
		final String detail;
		Consumer<MinecraftServer> start = s -> {};
		Tick tick = (p, t) -> {};
		BiConsumer<MinecraftServer, Integer> world = (s, t) -> {};
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

		Law world(BiConsumer<MinecraftServer, Integer> c) {
			world = c;
			return this;
		}

		Law end(Consumer<MinecraftServer> c) {
			end = c;
			return this;
		}

		/** Разовый эффект для каждого свободного игрока в момент объявления. */
		Law each(Consumer<ServerPlayerEntity> c) {
			start = s -> U.free(s).forEach(c);
			return this;
		}
	}

	public static final List<Law> ALL = new ArrayList<>();
	public static Law current;
	private static int lawTicks;

	private static final Map<UUID, Vec3d> lastPos = new HashMap<>();
	private static final Map<UUID, Vec3d> lastDelta = new HashMap<>();
	private static final Map<UUID, ItemStack> confiscated = new HashMap<>();
	private static final Map<UUID, Boolean> wasSneaking = new HashMap<>();
	private static final Map<UUID, Integer> lastSneak = new HashMap<>();
	/** Счётчики для «планов»: прыжки, блоки, убийства, сообщения, поклоны. */
	private static final Map<String, Map<UUID, Integer>> counters = new HashMap<>();

	private static final Identifier SCALE = Identifier.of("pizdec", "law_scale");
	private static final Identifier GRAVITY = Identifier.of("pizdec", "law_gravity");
	private static final Identifier JUMP = Identifier.of("pizdec", "law_jump");
	private static final Identifier FALL = Identifier.of("pizdec", "law_fall");
	private static final Identifier HEALTH = Identifier.of("pizdec", "law_health");
	private static final Identifier DAMAGE = Identifier.of("pizdec", "law_damage");

	private Laws() {}

	public static boolean is(String id) {
		return current != null && current.id.equals(id);
	}

	public static boolean graceOver() {
		return lawTicks > GRACE;
	}

	public static String currentTitle() {
		return current == null ? "без названия" : current.title;
	}

	public static String currentText() {
		return current == null ? "пока никаких, но это ненадолго" : current.title + " — " + current.detail;
	}

	/** Во сколько раз сейчас дороже платные штуки. */
	public static int priceMult() {
		return is("zhkh") ? 5 : 1;
	}

	/** Во сколько раз длиннее сроки. */
	public static int sentenceMult() {
		return is("double_sentence") ? 2 : 1;
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
			removeAttr(p, EntityAttributes.GENERIC_MAX_HEALTH, HEALTH);
			removeAttr(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, DAMAGE);
		}
	}

	private static void removeAttr(ServerPlayerEntity p, RegistryEntry<EntityAttribute> a, Identifier id) {
		EntityAttributeInstance i = p.getAttributeInstance(a);
		if (i != null) i.removeModifier(id);
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

	private static float moved(ServerPlayerEntity p) {
		Vec3d d = lastDelta.get(p.getUuid());
		return d == null ? 0f : (float) Math.sqrt(d.x * d.x + d.z * d.z);
	}

	private static boolean walking(ServerPlayerEntity p) {
		return moved(p) > 0.6f;
	}

	private static void v(ServerPlayerEntity p, String what) {
		Jail.violation(p, what);
	}

	private static int count(String key, ServerPlayerEntity p) {
		Map<UUID, Integer> m = counters.get(key);
		return m == null ? 0 : m.getOrDefault(p.getUuid(), 0);
	}

	private static void inc(String key, ServerPlayerEntity p) {
		counters.computeIfAbsent(key, k -> new HashMap<>()).merge(p.getUuid(), 1, Integer::sum);
	}

	/** План: кто к концу указа не набрал need — в тюрьму. */
	private static Consumer<MinecraftServer> quota(String key, int need, String what) {
		return s -> {
			for (ServerPlayerEntity p : U.free(s)) {
				int c = count(key, p);
				if (c < need) Jail.arrest(p, "невыполнение плана: " + what + " (" + c + " из " + need + ")", 40);
				else Money.give(p, 15, "план выполнен");
			}
		};
	}

	private static Tick holdItem(Item item, String name) {
		return (p, t) -> {
			if (t > GRACE && t % 60 == 0 && !p.getMainHandStack().isOf(item)) v(p, "в руке нет " + name);
		};
	}

	private static Tick rule(int every, java.util.function.Predicate<ServerPlayerEntity> bad, String what) {
		return (p, t) -> {
			if (t % every == 0 && bad.test(p)) v(p, what);
		};
	}

	private static BlockState stateUnder(ServerPlayerEntity p) {
		return p.getServerWorld().getBlockState(p.getBlockPos().down());
	}

	private static boolean hasArmor(ServerPlayerEntity p) {
		return !p.getEquippedStack(EquipmentSlot.HEAD).isEmpty() || !p.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
				|| !p.getEquippedStack(EquipmentSlot.LEGS).isEmpty() || !p.getEquippedStack(EquipmentSlot.FEET).isEmpty();
	}

	// ------------------------------------------------------------------ свод законов

	private static Law law(String id, String title, String detail) {
		Law l = new Law(id, title, detail);
		ALL.add(l);
		return l;
	}

	static {
		// ===== старые законы (нарушения теперь — тюрьма) =====

		law("no_jump", "Прыгать запрещено!", "каждый прыжок — тюрьма");

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

		law("snail", "Неделя улитки", "спешить запрещено (длится 40 секунд)")
				.tick((p, t) -> eff(p, t, StatusEffects.SLOWNESS, 2));

		law("air_tax", "Налог на воздух", "каждые 10 секунд −2 бабла за вдох")
				.tick((p, t) -> {
					if (t % 200 == 0) Money.fine(p, 2, "налог на воздух");
				});

		law("generous", "Король щедр!", "всем по 50 бабла (не привыкайте)")
				.each(p -> Money.give(p, 50, "подарок короля"));

		law("lottery", "Национальная лотерея", "один случайный счастливчик получает 200 бабла")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
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

		law("confiscate", "Временная конфискация", "всё, что в руке, — в казну на 40 секунд")
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

		law("only_sneak", "Ходить только гуськом", "кто идёт не приседая — в тюрьму")
				.tick(rule(20, p -> walking(p) && !p.isSneaking(), "ходил не гуськом"));

		law("no_sneak", "Приседать запрещено", "присел — сел")
				.tick(rule(20, p -> p.isSneaking(), "присел без разрешения"));

		law("freeze", "Стоять смирно!", "кто двигается — тот сидит")
				.tick(rule(20, Laws::walking, "шевелился"));

		law("floor_lava", "Пол — это лава!", "стоять на земле больно, прыгайте")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.JUMP_BOOST, 2);
					if (t % 30 == 0 && p.isOnGround()) {
						p.damage(p.getDamageSources().magic(), 1f);
						U.bar(p, U.t("Пол — лава!", Formatting.RED));
					}
				});

		law("chat_paid", "Свобода слова платная", "5 бабла за сообщение, нет денег — тюрьма");

		law("chat_reward", "Болтовня оплачивается государством", "+3 бабла за каждое сообщение в чате");

		law("no_dig", "Копать запрещено", "каждый блок — 3 бабла, нет денег — тюрьма")
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

		law("flower", "Обязательно держать цветок", "без цветка в руке — тюрьма")
				.tick((p, t) -> {
					if (t > GRACE && t % 100 == 0 && !p.getMainHandStack().isIn(ItemTags.SMALL_FLOWERS)) v(p, "где цветок?!");
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

		law("immortal", "Смерть временно запрещена", "40 секунд можно не умирать")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.RESISTANCE, 4);
					eff(p, t, StatusEffects.REGENERATION, 1);
				});

		law("swap", "Обмен телами", "все граждане меняются местами")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
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
					if (t % 20 == 0 && walking(p)) Money.fine(p, 1, "проезд по платной дороге");
				});

		law("salary", "Зарплата!", "каждые 10 секунд +3 бабла. Король заболел?")
				.tick((p, t) -> {
					if (t % 200 == 0) Money.give(p, 3, "зарплата");
				});

		law("kill_tax", "Мобы под защитой государства", "убил моба — сел");

		law("bounty", "Охота за головами", "за каждого убитого моба +10 бабла");

		// ===== новые законы: абсурд и тюрьма =====

		law("look_up", "Смотреть только в небо", "кто опустил глаза — тот в тюрьме")
				.tick(rule(20, p -> p.getPitch() > -25f, "смотрел не в небо"));

		law("look_down", "Смотреть под ноги", "поднял голову — сел")
				.tick(rule(20, p -> p.getPitch() < 25f, "поднял голову"));

		law("face_north", "Все смотрят на север", "там живёт любимая тёща короля")
				.tick(rule(20, p -> {
					float yaw = ((p.getYaw() % 360f) + 360f) % 360f;
					return Math.abs(yaw - 180f) > 50f;
				}, "отвернулся от тёщи короля"));

		law("must_sprint", "Ходить только бегом", "медленная ходьба — признак заговора")
				.tick(rule(20, p -> walking(p) && !p.isSprinting(), "шёл, а не бежал"));

		law("no_sprint", "Бег запрещён", "куда торопишься? В тюрьму?")
				.tick(rule(20, p -> p.isSprinting(), "бежал"));

		law("hold_stick", "Каждый обязан держать палку", "палка — символ власти")
				.tick(holdItem(Items.STICK, "палки"));

		law("hold_dirt", "Земля в руке — знак патриотизма", "держи землю или садись")
				.tick(holdItem(Items.DIRT, "земли"));

		law("hold_potato", "Всекоролевский день картошки", "картошку держать в руке, не есть")
				.tick(holdItem(Items.POTATO, "картошки"));

		law("no_armor", "Броня — это роскошь", "носить броню имеют право только дворяне")
				.tick(rule(40, Laws::hasArmor, "носил броню без дворянства"));

		law("pumpkin", "Тыква на голове обязательна", "снял тыкву — в тюрьму")
				.each(p -> {
					ItemStack old = p.getEquippedStack(EquipmentSlot.HEAD);
					if (!old.isOf(Items.CARVED_PUMPKIN)) {
						if (!old.isEmpty()) give(p, old.copy());
						p.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
					}
				})
				.tick(rule(40, p -> !p.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.CARVED_PUMPKIN), "снял тыкву"));

		law("no_eat", "Еда по талонам", "есть запрещено. Талонов нет")
				.tick((p, t) -> {
					if (t % 10 == 0 && p.isUsingItem() && p.getActiveItem().contains(DataComponentTypes.FOOD)) {
						p.stopUsingItem();
						v(p, "ел без талона");
					}
				});

		law("dance", "Обязательные танцы", "приседай хотя бы раз в 4 секунды")
				.tick((p, t) -> {
					if (t > GRACE && t % 20 == 0) {
						int last = lastSneak.getOrDefault(p.getUuid(), 0);
						if (lawTicks - last > 80) v(p, "не танцевал");
					}
				});

		law("silence", "Минута молчания", "40 секунд молчания. Написал в чат — сел");

		law("praise", "Каждое сообщение должно хвалить короля", "в сообщении должно быть слово «король»");

		law("caps", "Говорить только КАПСОМ", "маленькие буквы запрещены");

		law("no_o", "Буква «о» запрещена", "и русская, и английская. Король её не любит");

		law("whisper", "Говорить только шёпотом", "заглавные буквы запрещены");

		law("no_build", "Строить запрещено", "поставил блок — сел");

		law("no_doors", "Двери национализированы", "открыл дверь, люк или калитку — тюрьма");

		law("no_tools", "Копать только руками", "инструменты — привилегия знати");

		law("no_sun", "Загорать запрещено", "стоять под открытым небом днём — тюрьма")
				.tick(rule(60, p -> p.getServerWorld().isDay() && p.getServerWorld().isSkyVisible(p.getBlockPos().up()),
						"загорал"));

		law("must_sun", "Обязательный загар", "днём под крышей стоять нельзя")
				.tick(rule(60, p -> p.getServerWorld().isDay() && !p.getServerWorld().isSkyVisible(p.getBlockPos().up()),
						"прятался от солнца"));

		law("no_lawn", "По газонам не ходить!", "трава — собственность короля")
				.tick(rule(20, p -> p.isOnGround() && stateUnder(p).isOf(Blocks.GRASS_BLOCK), "топтал газон"));

		law("no_stone", "Камень — собственность короля", "стоять на камне запрещено")
				.tick(rule(20, p -> p.isOnGround() && stateUnder(p).isIn(BlockTags.BASE_STONE_OVERWORLD), "стоял на камне"));

		law("high_ban", "Выше 90 блоков — только для дворян", "простолюдинам там делать нечего")
				.tick(rule(20, p -> p.getY() > 90, "залез к дворянам"));

		law("animals_royal", "Животные — собственность короля", "ударил животное — сел");

		law("zombie_voters", "Зомби — избиратели", "бить электорат запрещено");

		law("pacifism", "Пацифизм", "бить нельзя вообще никого");

		law("backwards", "Ходить только задом наперёд", "так враги не поймут, куда ты идёшь")
				.tick(rule(20, p -> {
					Vec3d d = lastDelta.get(p.getUuid());
					if (d == null || moved(p) < 0.6f) return false;
					double yaw = Math.toRadians(p.getYaw());
					double fx = -Math.sin(yaw), fz = Math.cos(yaw);
					return (d.x * fx + d.z * fz) / moved(p) > 0.5;
				}, "шёл передом"));

		law("anthem", "Гимн королевства", "пока играет гимн — стоять смирно")
				.tick((p, t) -> {
					if (t % 8 == 0) {
						float[] notes = {0.7f, 0.8f, 0.9f, 1.0f, 1.2f, 1.0f, 0.9f, 1.4f};
						U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.8f, notes[(t / 8) % notes.length]);
					}
					if (t > GRACE && t % 20 == 0 && walking(p)) v(p, "двигался во время гимна");
				});

		law("cobble_ban", "Булыжник вне закона", "найдут булыжник в инвентаре — тюрьма")
				.tick(rule(100, p -> p.getInventory().count(Items.COBBLESTONE) > 0, "хранил булыжник"));

		law("no_water", "Вода только для дворян", "коснулся воды — сел")
				.tick(rule(20, p -> p.isTouchingWater(), "трогал королевскую воду"));

		law("quota_kill", "План по убийствам", "убей хотя бы одного моба за 40 секунд или в тюрьму")
				.end(quota("kills", 1, "убийства"));

		law("quota_mine", "План по добыче", "сломай 20 блоков за 40 секунд или в тюрьму")
				.end(quota("breaks", 20, "добыча"));

		law("quota_jump", "Норма прыжков", "15 прыжков за 40 секунд, иначе тюрьма")
				.end(quota("jumps", 15, "прыжки"));

		law("quota_chat", "Обязательно поздороваться", "напиши что-нибудь в чат, молчунов сажаем")
				.end(quota("chats", 1, "приветствие"));

		law("quota_bow", "Поклон королю", "присядь 3 раза в знак уважения, иначе тюрьма")
				.end(quota("bows", 3, "поклоны"));

		law("richest", "Подозрительное богатство", "самый богатый гражданин отправляется в тюрьму")
				.start(s -> U.free(s).stream().max(Comparator.comparingInt(Money::get))
						.ifPresent(p -> Jail.arrest(p, "подозрительное богатство", 60)));

		law("poor", "Бедность — преступление", "у кого меньше 30 бабла — в тюрьму")
				.each(p -> {
					if (Money.get(p) < 30) Jail.arrest(p, "бедность", 30);
				});

		law("prophylaxis", "Профилактический арест", "один случайный гражданин — для профилактики")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
					if (!ps.isEmpty()) Jail.arrest(U.pick(ps), "профилактика", 20);
				});

		law("all_jail", "Все в тюрьму!", "король так решил. Без объяснений")
				.each(p -> Jail.arrest(p, "указ «Все в тюрьму»", 20));

		law("jail_amnesty", "Тюремная амнистия", "все заключённые выходят на свободу")
				.start(s -> Jail.amnesty(s, "амнистия по случаю хорошего настроения короля"));

		law("double_sentence", "Сроки удвоены", "все новые сроки — ×2");

		law("freedom_tax", "Налог на свободу", "свободные граждане платят 1 бабло каждые 5 секунд")
				.tick((p, t) -> {
					if (t % 100 == 0) Money.fine(p, 1, "налог на свободу");
				});

		law("king_birthday", "День рождения короля", "каждый дарит королю 20 бабла. Нет денег — тюрьма")
				.each(p -> {
					if (!Money.pay(p, 20, "подарок королю")) Jail.arrest(p, "жадность в день рождения короля", 40);
				});

		law("fake_death", "Все объявлены мёртвыми", "по документам. Похороны платные")
				.each(p -> {
					U.title(p, U.t("ВЫ УМЕРЛИ", Formatting.DARK_RED, Formatting.BOLD), U.t("(по документам)", Formatting.GRAY));
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
					Money.fine(p, 5, "похороны по документам");
				});

		law("mirror", "Зеркальный день", "каждые 10 секунд всех разворачивает")
				.tick((p, t) -> {
					if (t % 200 == 0) p.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw() + 180f, p.getPitch());
				});

		law("time_lapse", "Ускоренное время", "сутки проходят за 40 секунд")
				.world((s, t) -> s.getOverworld().setTimeOfDay(s.getOverworld().getTimeOfDay() + 30));

		law("fish_rain", "Рыбный день", "с неба падает треска")
				.tick((p, t) -> {
					if (t % 40 == 0) {
						U.spawn(EntityType.COD, p.getServerWorld(), p.getX() + U.between(-4, 4), p.getY() + 10,
								p.getZ() + U.between(-4, 4));
					}
				});

		law("goat_anthem", "Козлиный гимн", "гимн королевства исполняют козлы")
				.tick((p, t) -> {
					if (t % 60 == 0) U.sound(p, SoundEvents.ENTITY_GOAT_SCREAMING_AMBIENT, 1f, 0.6f + U.R.nextFloat());
				});

		law("medicine_cut", "Медицина урезана", "здоровье — половина. Остальное платно")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_MAX_HEALTH, HEALTH, -0.5));

		law("strong", "Все — качки", "урон ×3. Король записал всех в спортзал")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, DAMAGE, 2.0));

		law("dirt_confiscation", "Земля конфискована", "вся земля из инвентарей — в пользу короля")
				.each(p -> {
					PlayerInventory inv = p.getInventory();
					int n = 0;
					for (int i = 0; i < inv.size(); i++) {
						if (inv.getStack(i).isOf(Items.DIRT)) {
							n += inv.getStack(i).getCount();
							inv.setStack(i, ItemStack.EMPTY);
						}
					}
					if (n > 0) U.msg(p, U.t("Изъято " + n + " земли. Королю нужнее.", Formatting.GRAY));
				});

		law("frog", "Все — лягушки", "прыгать высоко, ходить медленно, квакать про себя")
				.tick((p, t) -> {
					eff(p, t, StatusEffects.JUMP_BOOST, 4);
					eff(p, t, StatusEffects.SLOWNESS, 2);
					attr(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, FALL, 6.0);
				});

		law("effect_lottery", "Лотерея эффектов", "каждые 10 секунд — случайный эффект")
				.tick((p, t) -> {
					if (t % 200 == 1) {
						RegistryEntry<StatusEffect> e = U.pick(StatusEffects.SPEED, StatusEffects.SLOWNESS, StatusEffects.LEVITATION,
								StatusEffects.NAUSEA, StatusEffects.JUMP_BOOST, StatusEffects.INVISIBILITY, StatusEffects.GLOWING,
								StatusEffects.HASTE, StatusEffects.BLINDNESS, StatusEffects.REGENERATION);
						p.addStatusEffect(new StatusEffectInstance(e, 20 * 8, 1));
					}
				});

		law("swap_inventory", "Обмен имуществом", "инвентари граждан перемешиваются между собой")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
					if (ps.size() < 2) {
						ps.forEach(p -> U.msg(p, U.t("Меняться не с кем. Король расстроен.", Formatting.GRAY)));
						return;
					}
					List<List<ItemStack>> all = new ArrayList<>();
					for (ServerPlayerEntity p : ps) {
						List<ItemStack> l = new ArrayList<>();
						for (int i = 0; i < 36; i++) l.add(p.getInventory().getStack(i).copy());
						all.add(l);
					}
					for (int k = 0; k < ps.size(); k++) {
						List<ItemStack> l = all.get((k + 1) % ps.size());
						for (int i = 0; i < 36; i++) ps.get(k).getInventory().setStack(i, l.get(i));
					}
				});

		law("levitation", "Антигравитация по расписанию", "каждые 8 секунд — полёт за счёт казны")
				.tick((p, t) -> {
					if (t % 160 == 0) {
						p.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 40, 1));
						p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 160, 0));
					}
				});

		law("king_selfie", "Селфи с королём", "все граждане доставлены к трону")
				.each(p -> {
					PizdecState st = PizdecState.get(p.getServer());
					if (!st.castleBuilt) return;
					p.teleport(p.getServer().getOverworld(), st.cx + 16.5, st.cy, st.cz + 24.5, 0f, 0f);
				});
	}

	// ------------------------------------------------------------------ события от главного класса

	public static void onJump(ServerPlayerEntity p) {
		inc("jumps", p);
		if (is("no_jump")) v(p, "прыгнул без лицензии");
	}

	public static void onBroken(ServerPlayerEntity p) {
		inc("breaks", p);
	}

	/** false — блок ломать нельзя. */
	public static boolean allowBreak(ServerPlayerEntity p) {
		if (is("no_tools")) {
			ItemStack h = p.getMainHandStack();
			if (h.isIn(ItemTags.PICKAXES) || h.isIn(ItemTags.AXES) || h.isIn(ItemTags.SHOVELS) || h.isIn(ItemTags.HOES)) {
				v(p, "копал инструментом");
				return false;
			}
		}
		if (is("no_dig") && !Money.pay(p, 3, "лицензию на копание")) {
			v(p, "копал без лицензии");
			return false;
		}
		return true;
	}

	/** false — использование блока запрещено. */
	public static boolean allowUse(ServerPlayerEntity p, BlockState b, ItemStack inHand) {
		if (is("no_doors") && (b.isIn(BlockTags.DOORS) || b.isIn(BlockTags.TRAPDOORS) || b.isIn(BlockTags.FENCE_GATES))) {
			v(p, "открыл национализированную дверь");
			return false;
		}
		if (is("no_build") && inHand.getItem() instanceof BlockItem) {
			v(p, "пытался строить");
			return false;
		}
		return true;
	}

	/** false — бить нельзя. */
	public static boolean allowAttack(ServerPlayerEntity p, Entity target) {
		if (is("pacifism") && target instanceof LivingEntity) {
			v(p, "ударил живое существо");
			return false;
		}
		if (is("animals_royal") && target instanceof AnimalEntity) {
			v(p, "ударил королевское животное");
			return false;
		}
		if (is("zombie_voters") && target instanceof ZombieEntity) {
			v(p, "ударил избирателя");
			return false;
		}
		return true;
	}

	public static void onKill(ServerPlayerEntity p, LivingEntity killed) {
		inc("kills", p);
		if (is("kill_tax")) v(p, "убил моба под защитой государства");
	}

	/** Проверка сообщения в чате. false — сообщение не пропускаем. Аресты — через очередь сервера. */
	public static boolean allowChat(ServerPlayerEntity p, String text) {
		MinecraftServer s = p.getServer();
		inc("chats", p);
		String crime = null;
		if (is("silence")) crime = "нарушил минуту молчания";
		else if (is("praise") && !text.toLowerCase().contains("корол")) crime = "не похвалил короля";
		else if (is("caps") && !text.equals(text.toUpperCase())) crime = "говорил маленькими буквами";
		else if (is("whisper") && !text.equals(text.toLowerCase())) crime = "повысил голос";
		else if (is("no_o") && text.toLowerCase().matches(".*[оo].*")) crime = "использовал букву «о»";
		else if (is("chat_paid") && !Money.pay(p, 5, "свободу слова")) crime = "говорил бесплатно";

		if (crime != null) {
			String c = crime;
			s.execute(() -> Jail.violation(p, c));
			return false;
		}
		if (is("chat_reward")) Money.give(p, 3, "болтовня");
		return true;
	}

	// ------------------------------------------------------------------ движок

	public static void next(MinecraftServer s) {
		if (current != null) {
			current.end.accept(s);
			clearAttrs(s);
		}
		counters.clear();
		lastSneak.clear();
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
			UUID id = p.getUuid();
			if (lawTicks % 20 == 0) {
				Vec3d now = p.getPos();
				Vec3d was = lastPos.put(id, now);
				lastDelta.put(id, was == null ? Vec3d.ZERO : now.subtract(was));
			}
			boolean sn = p.isSneaking();
			Boolean before = wasSneaking.put(id, sn);
			if (sn && (before == null || !before)) {
				lastSneak.put(id, lawTicks);
				inc("bows", p);
			}
		}
		if (current == null) return;
		current.world.accept(s, lawTicks);
		for (ServerPlayerEntity p : U.free(s)) current.tick.tick(p, lawTicks);
	}

	public static void forget(UUID id) {
		lastPos.remove(id);
		lastDelta.remove(id);
		wasSneaking.remove(id);
	}
}
