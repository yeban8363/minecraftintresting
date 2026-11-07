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
	private static final Map<UUID, Integer> lastJump = new HashMap<>();
	private static final Map<UUID, Float> lastYaw = new HashMap<>();
	private static final Map<UUID, Float> yawDelta = new HashMap<>();
	/** Счётчики для «планов»: прыжки, блоки, убийства, сообщения, поклоны. */
	private static final Map<String, Map<UUID, Integer>> counters = new HashMap<>();

	private static final Identifier SCALE = Identifier.of("pizdec", "law_scale");
	private static final Identifier GRAVITY = Identifier.of("pizdec", "law_gravity");
	private static final Identifier JUMP = Identifier.of("pizdec", "law_jump");
	private static final Identifier FALL = Identifier.of("pizdec", "law_fall");
	private static final Identifier HEALTH = Identifier.of("pizdec", "law_health");
	private static final Identifier DAMAGE = Identifier.of("pizdec", "law_damage");
	private static final Identifier REACH = Identifier.of("pizdec", "law_reach");
	private static final Identifier STEP = Identifier.of("pizdec", "law_step");
	private static final Identifier BREAK = Identifier.of("pizdec", "law_break");
	private static final Identifier MOVE = Identifier.of("pizdec", "law_move");
	private static long frozenTime = -1;

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
			removeAttr(p, EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, REACH);
			removeAttr(p, EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, REACH);
			removeAttr(p, EntityAttributes.GENERIC_STEP_HEIGHT, STEP);
			removeAttr(p, EntityAttributes.PLAYER_BLOCK_BREAK_SPEED, BREAK);
			removeAttr(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, MOVE);
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

	/** Есть ли рядом (в радиусе r) блок, подходящий под условие. */
	private static boolean near(ServerPlayerEntity p, int r, java.util.function.Predicate<BlockState> ok) {
		ServerWorld w = p.getServerWorld();
		net.minecraft.util.math.BlockPos c = p.getBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (ok.test(w.getBlockState(new net.minecraft.util.math.BlockPos(c.getX() + dx, c.getY() + dy, c.getZ() + dz)))) return true;
				}
			}
		}
		return false;
	}

	private static boolean hasSpecial(ServerPlayerEntity p, String tag) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) if (Gui.isSpecial(inv.getStack(i), tag)) return true;
		return false;
	}

	private static boolean removeSpecial(ServerPlayerEntity p, String tag) {
		PlayerInventory inv = p.getInventory();
		boolean found = false;
		for (int i = 0; i < inv.size(); i++) {
			if (Gui.isSpecial(inv.getStack(i), tag)) {
				inv.setStack(i, ItemStack.EMPTY);
				found = true;
			}
		}
		return found;
	}

	/** Магазин сообщает о покупке (для указа «Шопинг обязателен»). */
	public static void onBuy(ServerPlayerEntity p) {
		inc("buys", p);
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
				.tick(rule(40, p -> !Ranks.noble(p) && hasArmor(p), "носил броню без дворянства"));

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
				.tick(rule(20, p -> !Ranks.noble(p) && p.getY() > 90, "залез к дворянам"));

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
					Vec3d th = Castle.throneHall(st);
					p.teleport(p.getServer().getOverworld(), th.x, th.y, th.z, 0f, 0f);
				});

		// ===== версия 1.2: магазин, казино, апгрейдер и ещё больше абсурда =====

		law("gambling_ban", "Азартные игры запрещены", "казино и апгрейдер — прямая дорога в тюрьму");

		law("casino_x2", "Счастливый час в казино", "все выигрыши ×2. Король сегодня щедрый (нет)");

		law("shop_sale", "Королевская распродажа", "в магазине всё за полцены");

		law("shop_inflation", "Инфляция", "цены в магазине ×3. Король купил новую корону");

		law("upgrader_luck", "Апгрейдер подкручен", "шанс апгрейда +20%. Только сегодня");

		law("upgrade_everything", "Принудительный апгрейд", "всё, что в руке, — в апгрейдер. Без спроса")
				.each(Upgrader::autoUpgrade);

		law("casino_mandatory", "Обязательная ставка", "каждый ставит 20 бабла на монетку. Отказ не принимается")
				.each(p -> {
					if (!Money.pay(p, 20, "обязательную ставку")) {
						Jail.arrest(p, "уклонение от обязательной ставки", 20);
						return;
					}
					if (U.R.nextInt(100) < 48) Money.give(p, 40, "монетка выпала орлом");
					else U.bar(p, U.t("🪙 Решка. Казино благодарит.", Formatting.RED));
				});

		law("jail_tour", "Экскурсия в тюрьму", "всех граждан бесплатно катают в тюрьму на 5 секунд")
				.each(p -> Jail.arrest(p, "экскурсия (бесплатная)", 5));

		law("no_left", "Налево поворачивать запрещено", "только направо. Налево — только в тюрьму")
				.tick(rule(20, p -> yawDelta.getOrDefault(p.getUuid(), 0f) < -40f, "повернул налево"));

		law("pet_rock", "Каждому — домашний камень", "камень Валера должен быть с тобой до конца указа")
				.each(p -> give(p, Gui.petRock()))
				.end(s -> {
					for (ServerPlayerEntity p : U.free(s)) {
						if (!hasSpecial(p, "pet_rock")) Jail.arrest(p, "потерял домашний камень Валеру", 40);
						else Money.give(p, 10, "заботливый хозяин камня");
					}
				});

		law("hot_potato", "Горячая картошка", "у кого картошка в конце указа — тот сядет")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
					if (ps.isEmpty()) return;
					ServerPlayerEntity p = U.pick(ps);
					give(p, Gui.hotPotato());
					U.say(s, U.t("🥔 Горячая картошка у " + p.getName().getString() + "!", Formatting.RED));
				})
				.end(s -> {
					for (ServerPlayerEntity p : U.free(s)) {
						if (removeSpecial(p, "hot_potato")) Jail.arrest(p, "горячая картошка", 40);
					}
				});

		law("king_spies", "Король следит за вами", "к каждому приставлен шпион")
				.each(p -> {
					for (int i = 0; i < 3; i++) {
						net.minecraft.entity.passive.BatEntity bat = U.spawn(EntityType.BAT, p.getServerWorld(), p.getX(),
								p.getY() + 2, p.getZ());
						if (bat != null) {
							bat.setCustomName(U.t("Шпион короля", Formatting.DARK_GRAY));
							bat.setCustomNameVisible(true);
							bat.setGlowing(true);
						}
					}
				});

		law("happiness", "Обязательное счастье", "прыгай от радости хотя бы раз в 5 секунд")
				.tick((p, t) -> {
					if (t > GRACE && t % 20 == 0 && lawTicks - lastJump.getOrDefault(p.getUuid(), 0) > 100) {
						v(p, "недостаточно счастлив");
					}
				});

		law("beard_tax", "Налог на бороду", "у кого шлем — тот бородатый. 5 бабла каждые 10 секунд")
				.tick((p, t) -> {
					if (t % 200 == 0 && !p.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) Money.fine(p, 5, "налог на бороду");
				});

		law("toilet", "Обязательный поход в туалет", "постой у котла хотя бы раз, или в тюрьму")
				.tick((p, t) -> {
					if (t % 20 == 0 && near(p, 2, b -> b.isOf(Blocks.CAULDRON) || b.isOf(Blocks.WATER_CAULDRON))) inc("toilet", p);
				})
				.end(quota("toilet", 1, "поход в туалет"));

		law("touch_grass", "Потрогай траву", "постой на траве хотя бы раз за указ. Шахтёры — в тюрьму")
				.tick((p, t) -> {
					if (t % 20 == 0 && p.isOnGround() && stateUnder(p).isOf(Blocks.GRASS_BLOCK)) inc("grass", p);
				})
				.end(quota("grass", 1, "трогание травы"));

		law("hug_tree", "Обними дерево", "постой вплотную к дереву, король любит экологию")
				.tick((p, t) -> {
					if (t % 20 == 0 && near(p, 1, b -> b.isIn(BlockTags.LOGS))) inc("tree", p);
				})
				.end(quota("tree", 1, "объятия с деревом"));

		law("dinnerbone", "Мир вверх ногами", "все мобы вокруг переименованы в Dinnerbone")
				.each(p -> {
					for (net.minecraft.entity.mob.MobEntity m : p.getServerWorld().getEntitiesByClass(
							net.minecraft.entity.mob.MobEntity.class, p.getBoundingBox().expand(32),
							m -> !m.hasCustomName() && m.getCommandTags().isEmpty())) {
						m.setCustomName(U.t("Dinnerbone"));
					}
				});

		law("tax_return", "Возврат налогов", "всем +30 бабла. Налоговая плачет")
				.each(p -> Money.give(p, 30, "возврат налогов"));

		law("fake_diamonds", "Алмазный дождь", "с неба падают алмазы. Совершенно настоящие")
				.each(p -> {
					for (int i = 0; i < 5; i++) {
						p.getServerWorld().spawnEntity(new net.minecraft.entity.ItemEntity(p.getServerWorld(),
								p.getX() + U.between(-3, 3), p.getY() + 6, p.getZ() + U.between(-3, 3), Gui.fakeDiamond()));
					}
				});

		law("pay_click", "Платный ПКМ", "каждое нажатие по блоку — 1 бабло");

		// ===== версия 1.3: суд, работа, звания, прописка, война =====

		law("harsh_court", "Суд сегодня злой", "оправданий не будет, сроки чаще удваиваются");

		law("no_court", "Суд на каникулах", "сажают сразу, без суда и следствия");

		law("free_lawyer", "Бесплатная юридическая помощь", "всем обвиняемым — хороший адвокат за счёт казны");

		law("salary_freeze", "Зарплаты заморожены", "в ближайшую получку никто ничего не получит");

		law("double_salary", "Двойная зарплата", "ближайшая получка ×2. Король что-то перепутал");

		law("mobilization", "Частичная мобилизация", "один случайный гражданин отправляется на войну")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
					if (!ps.isEmpty()) War.exile(U.pick(ps), "мобилизация");
				});

		law("address_check", "Проверка прописки", "у кого нет адреса — в тюрьму или на войну")
				.each(p -> {
					if (Address.registered(p.getServer(), p.getUuid())) return;
					if (U.R.nextBoolean()) Jail.arrest(p, "проживание без прописки", 30);
					else War.exile(p, "проживание без прописки");
				});

		law("noble_day", "День дворянства", "дворянам +20 бабла, простолюдинам штраф 10 за простоту")
				.each(p -> {
					if (Ranks.noble(p)) Money.give(p, 20, "день дворянства");
					else Money.fine(p, 10, "недостаточно благородное происхождение");
				});

		law("peasant_revolt", "Крестьянское восстание", "простым +30 бабла, дворянам −30. Король в шоке")
				.each(p -> {
					if (Ranks.get(p) <= 1) Money.give(p, 30, "экспроприация");
					else if (Ranks.noble(p)) Money.fine(p, 30, "экспроприация экспроприаторов");
				});

		law("work_day", "Трудовая повинность", "безработные к концу указа сядут за тунеядство (/rabota)")
				.end(s -> {
					for (ServerPlayerEntity p : U.free(s)) {
						if (Jobs.jobOf(p) == null) Jail.arrest(p, "тунеядство", 30);
					}
				});

		law("royal_shopping", "Шопинг обязателен", "кто ничего не купит в магазине за 40 секунд — в тюрьму")
				.end(quota("buys", 1, "покупки в магазине"));

		law("vip_jail", "Платная тюрьма", "заключённые платят 2 бабла за каждую секунду отсидки");
	}


	// ===== версия 1.4: ещё 100 законов, ебанутых и не очень =====
	static {
		// --- эффекты (1-20)
		law("rabbit", "Все — кролики", "прыгаем и бегаем, морковку не дают")
				.tick((p, t) -> { eff(p, t, StatusEffects.JUMP_BOOST, 3); eff(p, t, StatusEffects.SPEED, 1); });
		law("drunk", "Королевский банкет", "все немного пьяны. За счёт короля")
				.tick((p, t) -> eff(p, t, StatusEffects.NAUSEA, 0));
		law("sloth", "День ленивца", "двигаться медленно, копать ещё медленнее")
				.tick((p, t) -> { eff(p, t, StatusEffects.SLOWNESS, 3); eff(p, t, StatusEffects.MINING_FATIGUE, 2); });
		law("flash", "Все — флэши", "скорость света. Почти")
				.tick((p, t) -> eff(p, t, StatusEffects.SPEED, 6));
		law("turtle", "Черепаший марафон", "медленно, но броня как у черепахи")
				.tick((p, t) -> { eff(p, t, StatusEffects.SLOWNESS, 4); eff(p, t, StatusEffects.RESISTANCE, 2); });
		law("ghosts", "Призраки королевства", "все невидимые, но светятся. Логика короля")
				.tick((p, t) -> { eff(p, t, StatusEffects.INVISIBILITY, 0); eff(p, t, StatusEffects.GLOWING, 0); });
		law("miners_joy", "Шахтёрский праздник", "спешка и ночное зрение для всех")
				.tick((p, t) -> { eff(p, t, StatusEffects.HASTE, 3); eff(p, t, StatusEffects.NIGHT_VISION, 0); });
		law("night_terror", "Ночной кошмар", "темнота и фантом в подарок")
				.each(p -> {
					net.minecraft.entity.mob.PhantomEntity ph = U.spawn(EntityType.PHANTOM, p.getServerWorld(), p.getX(), p.getY() + 12, p.getZ());
					if (ph != null) ph.setTarget(p);
				})
				.tick((p, t) -> eff(p, t, StatusEffects.DARKNESS, 0));
		law("sugar_rush", "Сахарная лихорадка", "быстро, высоко и очень голодно")
				.tick((p, t) -> { eff(p, t, StatusEffects.SPEED, 2); eff(p, t, StatusEffects.JUMP_BOOST, 2); eff(p, t, StatusEffects.HUNGER, 1); });
		law("zen", "День дзена", "медленно и спокойно, здоровье восстанавливается")
				.tick((p, t) -> { eff(p, t, StatusEffects.SLOWNESS, 1); eff(p, t, StatusEffects.REGENERATION, 1); });
		law("absorption", "Королевская броня", "всем по 8 золотых сердечек")
				.tick((p, t) -> { if (t == 1) p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, PERIOD, 3)); });
		law("hero", "Герой деревни", "жители дают скидки. Наконец-то")
				.tick((p, t) -> eff(p, t, StatusEffects.HERO_OF_THE_VILLAGE, 2));
		law("bad_omen", "Плохое предзнаменование", "зайдёшь в деревню — будет рейд")
				.each(p -> p.addStatusEffect(new StatusEffectInstance(StatusEffects.BAD_OMEN, 20 * 60, 0)));
		law("sea_power", "Сила моря", "под водой как дома")
				.tick((p, t) -> { eff(p, t, StatusEffects.CONDUIT_POWER, 0); eff(p, t, StatusEffects.DOLPHINS_GRACE, 0); });
		law("weak_day", "Хилый день", "все ослаблены. Драться бесполезно")
				.tick((p, t) -> eff(p, t, StatusEffects.WEAKNESS, 1));
		law("strength_day", "День силы", "все сильные. Драться опасно")
				.tick((p, t) -> eff(p, t, StatusEffects.STRENGTH, 1));
		law("springs", "Пружины в ботинках", "прыжок ×6 и падать не больно")
				.tick((p, t) -> { eff(p, t, StatusEffects.JUMP_BOOST, 5); attr(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, FALL, 10.0); });
		law("feathers", "Перья вместо костей", "все падают медленно")
				.tick((p, t) -> eff(p, t, StatusEffects.SLOW_FALLING, 0));
		law("royal_flu", "Королевская простуда", "каждые 10 секунд немного тошнит и травит")
				.tick((p, t) -> { if (t % 200 == 0) p.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 40, 0)); });
		law("glow_night", "Светящийся вечер", "все светятся, чтобы стража видела")
				.tick((p, t) -> eff(p, t, StatusEffects.GLOWING, 0));

		// --- размеры и атрибуты (21-28)
		law("size_mix", "Ассорти размеров", "каждому — случайный рост от крошки до великана")
				.each(p -> attr(p, EntityAttributes.GENERIC_SCALE, SCALE, -0.7 + U.R.nextDouble() * 2.2));
		law("long_arms", "Длинные руки закона", "достаёшь блоки и мобов вдвое дальше")
				.tick((p, t) -> { attr(p, EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, REACH, 1.0); attr(p, EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, REACH, 1.0); });
		law("short_arms", "Короткие руки", "дотянуться до чего-то — подвиг")
				.tick((p, t) -> attr(p, EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, REACH, -0.6));
		law("no_stairs", "Ступеньки отменены", "все сами забираются на блоки")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_STEP_HEIGHT, STEP, 1.0));
		law("fast_break", "Ускоренная добыча", "блоки ломаются втрое быстрее")
				.tick((p, t) -> attr(p, EntityAttributes.PLAYER_BLOCK_BREAK_SPEED, BREAK, 2.0));
		law("lead_boots", "Свинцовые ботинки", "тяжело и медленно")
				.tick((p, t) -> { attr(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, MOVE, -0.4); attr(p, EntityAttributes.GENERIC_GRAVITY, GRAVITY, 1.0); });
		law("double_hp", "Здоровье по акции", "максимум здоровья ×2")
				.tick((p, t) -> attr(p, EntityAttributes.GENERIC_MAX_HEALTH, HEALTH, 1.0));
		law("mini_fast", "Карлики-бегуны", "маленькие и быстрые")
				.tick((p, t) -> { attr(p, EntityAttributes.GENERIC_SCALE, SCALE, -0.6); eff(p, t, StatusEffects.SPEED, 2); });

		// --- деньги (29-44)
		law("rich_tax", "Налог на богатство", "у кого больше 500 — минус 20%")
				.each(p -> { int b = Money.get(p); if (b > 500) Money.fine(p, b / 5, "налог на богатство"); });
		law("poor_help", "Пособие для бедных", "у кого меньше 50 — плюс 40")
				.each(p -> { if (Money.get(p) < 50) Money.give(p, 40, "пособие"); });
		law("coin_rain", "Монетный дождь", "каждые 5 секунд всем по монетке")
				.tick((p, t) -> { if (t % 100 == 0) Money.give(p, 1, "монетный дождь"); });
		law("money_burn", "Бабло горит", "каждые 5 секунд сгорает 1 бабло с каждой сотни")
				.tick((p, t) -> { if (t % 100 == 0 && Money.get(p) >= 100) Money.fine(p, Money.get(p) / 100, "бабло горит"); });
		law("robin_hood", "Робин Гуд", "самый богатый отдаёт 20% самому бедному")
				.start(s -> {
					List<ServerPlayerEntity> ps = U.free(s);
					if (ps.size() < 2) return;
					ServerPlayerEntity rich = ps.stream().max(Comparator.comparingInt(Money::get)).get();
					ServerPlayerEntity poor = ps.stream().min(Comparator.comparingInt(Money::get)).get();
					int sum = Math.max(0, Money.get(rich)) / 5;
					if (sum <= 0 || rich == poor) return;
					Money.fine(rich, sum, "Робин Гуд");
					Money.give(poor, sum, "Робин Гуд");
				});
		law("anti_lottery", "Лотерея наоборот", "один случайный неудачник теряет 100")
				.start(s -> { List<ServerPlayerEntity> ps = U.free(s); if (!ps.isEmpty()) Money.fine(U.pick(ps), 100, "лотерея наоборот"); });
		law("interest", "Проценты по вкладам", "+5% к балансу (до 100)")
				.each(p -> { int b = Money.get(p); if (b > 0) Money.give(p, Math.min(100, Math.max(1, b / 20)), "проценты"); });
		law("toll_jump", "Платные прыжки", "каждый прыжок — 1 бабло");
		law("pay_sneak", "Платное приседание", "каждое приседание — 1 бабло");
		law("tip_king", "Чаевые королю", "каждое сообщение в чате — 2 бабла королю");
		law("fine_lottery", "Штрафная лотерея", "каждые 10 секунд случайный гражданин платит 10")
				.world((s, t) -> { if (t % 200 == 0) { List<ServerPlayerEntity> ps = U.free(s); if (!ps.isEmpty()) Money.fine(U.pick(ps), 10, "штрафная лотерея"); } });
		law("cashback", "Кэшбэк", "магазин возвращает 30% с каждой покупки");
		law("shop_closed", "Магазин закрыт на учёт", "ничего не продаём. Приходите завтра");
		law("casino_closed", "Казино опечатано налоговой", "играть нельзя. Налоговая сама играет");
		law("jackpot_day", "День джекпота", "выигрыши в казино ×3");
		law("mirror_money", "Перевёртыш", "у кого до 100 бабла — знак меняется. Долг стал деньгами и наоборот")
				.each(p -> { int b = Money.get(p); if (b >= -100 && b <= 100 && b != 0) { Money.set(p, -b); U.bar(p, U.t("Баланс перевёрнут: " + (-b), Formatting.YELLOW)); } });

		// --- предметы (45-58)
		law("free_lunch", "Бесплатный обед", "всем по 3 хлеба")
				.each(p -> give(p, new ItemStack(Items.BREAD, 3)));
		law("free_tools", "Инструменты от короля", "всем по железной кирке")
				.each(p -> give(p, new ItemStack(Items.IRON_PICKAXE)));
		law("free_xp", "Опыт в бутылках", "всем по 5 пузырьков опыта")
				.each(p -> give(p, new ItemStack(Items.EXPERIENCE_BOTTLE, 5)));
		law("torch_tax", "Налог на факелы", "половина факелов изымается")
				.each(p -> { PlayerInventory inv = p.getInventory(); for (int i = 0; i < inv.size(); i++) { ItemStack st = inv.getStack(i); if (st.isOf(Items.TORCH)) st.setCount(st.getCount() / 2); } });
		law("snowball_war", "Снежная война", "всем по 16 снежков. Огонь!")
				.each(p -> give(p, new ItemStack(Items.SNOWBALL, 16)));
		law("easter", "Пасха", "всем по 8 яиц. Курицы в шоке")
				.each(p -> give(p, new ItemStack(Items.EGG, 8)));
		law("flower_gift", "Цветы от короля", "каждому — цветочек")
				.each(p -> give(p, new ItemStack(U.pick(Items.POPPY, Items.DANDELION, Items.CORNFLOWER, Items.LILY_OF_THE_VALLEY, Items.WITHER_ROSE))));
		law("arrow_rain", "Стрелы с неба", "каждые 3 секунды — стрела. Не стойте под небом")
				.tick((p, t) -> {
					if (t % 60 != 0) return;
					net.minecraft.entity.projectile.ArrowEntity a = EntityType.ARROW.create(p.getServerWorld());
					if (a == null) return;
					a.refreshPositionAndAngles(p.getX() + U.between(-3, 3), p.getY() + 15, p.getZ() + U.between(-3, 3), 0f, 90f);
					a.setVelocity(0, -1.5, 0);
					p.getServerWorld().spawnEntity(a);
				});
		law("anvil_fall", "Наковальнепад", "каждые 8 секунд рядом падает наковальня")
				.tick((p, t) -> {
					if (t % 160 != 0) return;
					net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(p.getBlockX() + U.between(-4, 4), p.getBlockY() + 15, p.getBlockZ() + U.between(-4, 4));
					if (p.getServerWorld().getBlockState(pos).isAir()) net.minecraft.entity.FallingBlockEntity.spawnFromBlock(p.getServerWorld(), pos, Blocks.ANVIL.getDefaultState());
				});
		law("potato_rain", "Картофельный дождь", "с неба сыплется картошка")
				.tick((p, t) -> { if (t % 40 == 0) p.getServerWorld().spawnEntity(new net.minecraft.entity.ItemEntity(p.getServerWorld(), p.getX() + U.between(-3, 3), p.getY() + 8, p.getZ() + U.between(-3, 3), new ItemStack(Items.POTATO))); });
		law("inventory_tax", "Налог на инвентарь", "один случайный стак — в казну")
				.each(p -> { List<String> l = Tax.confiscate(p, 1); if (!l.isEmpty()) U.bar(p, U.t("Изъято: " + l.get(0), Formatting.RED)); });
		law("hands_swap", "Перепутанные руки", "каждые 10 секунд левая и правая рука меняются")
				.tick((p, t) -> {
					if (t % 200 != 0) return;
					PlayerInventory inv = p.getInventory();
					ItemStack main = inv.getStack(inv.selectedSlot), off = inv.getStack(40);
					inv.setStack(inv.selectedSlot, off);
					inv.setStack(40, main);
				});
		law("luxury_check", "Проверка на роскошь", "у кого больше 5 алмазов — лишние в казну")
				.each(p -> {
					PlayerInventory inv = p.getInventory();
					int keep = 5;
					for (int i = 0; i < inv.size(); i++) {
						ItemStack st = inv.getStack(i);
						if (!st.isOf(Items.DIAMOND) || Gui.isSpecial(st, "fake_diamond")) continue;
						int k = Math.min(keep, st.getCount());
						keep -= k;
						st.setCount(k);
					}
				});
		law("xp_rain", "Опытный дождь", "с неба падают шарики опыта")
				.tick((p, t) -> { if (t % 30 == 0) p.getServerWorld().spawnEntity(new net.minecraft.entity.ExperienceOrbEntity(p.getServerWorld(), p.getX() + U.between(-2, 2), p.getY() + 3, p.getZ() + U.between(-2, 2), 3)); });
		law("salute", "Королевский салют", "фейерверки в честь короля")
				.tick((p, t) -> { if (t % 40 == 0) p.getServerWorld().spawnEntity(new net.minecraft.entity.projectile.FireworkRocketEntity(p.getServerWorld(), p.getX() + U.between(-5, 5), p.getY(), p.getZ() + U.between(-5, 5), new ItemStack(Items.FIREWORK_ROCKET))); });

		// --- мобы (59-71)
		law("goats", "Козлиное нашествие", "к каждому пришли два козла")
				.each(p -> { for (int i = 0; i < 2; i++) U.spawnNear(EntityType.GOAT, p, 5); });
		law("llamas", "Ламы-дипломаты", "к каждому прибыла делегация лам")
				.each(p -> { for (int i = 0; i < 2; i++) U.spawnNear(EntityType.LLAMA, p, 5); });
		law("frog_rain", "Лягушачий дождь", "с неба падают лягушки")
				.tick((p, t) -> { if (t % 80 == 0) U.spawn(EntityType.FROG, p.getServerWorld(), p.getX() + U.between(-3, 3), p.getY() + 10, p.getZ() + U.between(-3, 3)); });
		law("slime_party", "Вечеринка слизней", "к каждому — три слизня")
				.each(p -> { for (int i = 0; i < 3; i++) U.spawnNear(EntityType.SLIME, p, 6); });
		law("wolves", "Волки на страже", "каждому по два волка. Приручайте")
				.each(p -> { for (int i = 0; i < 2; i++) U.spawnNear(EntityType.WOLF, p, 5); });
		law("zombie_parade", "Парад зомби", "по улицам маршируют зомби")
				.each(p -> { for (int i = 0; i < 3; i++) { ZombieEntity z = U.spawnNear(EntityType.ZOMBIE, p, 10); if (z != null) { z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); z.setCustomName(U.t("Участник парада", Formatting.GREEN)); } } });
		law("skeleton_band", "Скелеты-музыканты", "к каждому пришли барабанщики")
				.each(p -> { for (int i = 0; i < 2; i++) { net.minecraft.entity.mob.SkeletonEntity sk = U.spawnNear(EntityType.SKELETON, p, 10); if (sk != null) sk.setCustomName(U.t("Барабанщик", Formatting.WHITE)); } })
				.tick((p, t) -> { if (t % 10 == 0) U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 1f); });
		law("rally", "Митинг жителей", "к каждому пришли митингующие")
				.each(p -> { for (int i = 0; i < 4; i++) { VillagerEntity v = U.spawnNear(EntityType.VILLAGER, p, 6); if (v != null) { v.setCustomName(U.t(U.pick("За снижение налогов!", "Долой карету!", "Хотим шаурму!", "Король, ты кто?"), Formatting.YELLOW)); v.setCustomNameVisible(true); } } });
		law("cat_army", "Кошачья армия", "к каждому — четыре кота")
				.each(p -> { for (int i = 0; i < 4; i++) U.spawnNear(EntityType.CAT, p, 4); });
		law("bat_cloud", "Облако летучих мышей", "шпионы короля размножились")
				.each(p -> { for (int i = 0; i < 6; i++) U.spawn(EntityType.BAT, p.getServerWorld(), p.getX(), p.getY() + 2, p.getZ()); });
		law("hug_golem", "Голем-обнимашка", "к каждому приставлен железный голем")
				.each(p -> { net.minecraft.entity.passive.IronGolemEntity g = U.spawnNear(EntityType.IRON_GOLEM, p, 4); if (g != null) { g.setCustomName(U.t("Обнимашка", Formatting.LIGHT_PURPLE)); g.setCustomNameVisible(true); } });
		law("witch_week", "Неделя ведьмы", "к каждому пришла тёща короля")
				.each(p -> { net.minecraft.entity.mob.WitchEntity wt = U.spawnNear(EntityType.WITCH, p, 8); if (wt != null) { wt.setCustomName(U.t("Тёща короля", Formatting.DARK_PURPLE)); wt.setCustomNameVisible(true); } });
		law("patrol", "Патруль разбойников", "по округе гуляют разбойники")
				.each(p -> { for (int i = 0; i < 2; i++) U.spawnNear(EntityType.PILLAGER, p, 12); });

		// --- перемещения (72-79)
		law("step_aside", "Шаг вправо", "всех переносит на 10 блоков в случайную сторону")
				.each(p -> tpSurface(p, 10));
		law("to_square", "Все на площадь!", "все граждане доставлены во двор замка")
				.each(p -> { PizdecState st = PizdecState.get(p.getServer()); if (!st.castleBuilt) return; Vec3d c = Castle.courtyard(st); p.teleport(p.getServer().getOverworld(), c.x, c.y, c.z, 0f, 0f); });
		law("prison_tour", "Экскурсия по тюрьме", "всех привезли во двор тюрьмы. Посмотреть и ужаснуться")
				.each(p -> { PizdecState st = PizdecState.get(p.getServer()); if (!st.prisonBuilt) return; Vec3d c = Prison.yard(st); p.teleport(p.getServer().getOverworld(), c.x, c.y, c.z, 0f, 0f); });
		law("skyscraper", "Небоскрёб", "всех на 25 блоков вверх, перья прилагаются")
				.each(p -> { p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 20, 0)); p.teleport(p.getServerWorld(), p.getX(), p.getY() + 25, p.getZ(), p.getYaw(), p.getPitch()); });
		law("rocket", "Ракетный старт", "каждые 10 секунд — запуск в небо")
				.tick((p, t) -> { if (t % 200 == 0) { p.addVelocity(0, 1.3, 0); p.velocityModified = true; p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0)); } });
		law("carousel", "Карусель", "каждые 2 секунды всех разворачивает на 90°")
				.tick((p, t) -> { if (t % 40 == 0) p.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw() + 90f, p.getPitch()); });
		law("go_north", "Все на север!", "ветер дует строго на север")
				.tick((p, t) -> { if (t % 20 == 0) { p.addVelocity(0, 0.1, -0.5); p.velocityModified = true; } });
		law("freeze_time", "Время остановлено", "солнце замерло. Король так решил")
				.start(s -> frozenTime = s.getOverworld().getTimeOfDay())
				.world((s, t) -> { if (frozenTime >= 0) s.getOverworld().setTimeOfDay(frozenTime); })
				.end(s -> frozenTime = -1);

		// --- правила, за нарушение — тюрьма (80-100)
		law("no_numbers", "Цифры запрещены", "в чате — только буквы");
		law("short_msgs", "Краткость — сестра таланта", "сообщения длиннее 20 символов — тюрьма");
		law("long_msgs", "Многословие обязательно", "сообщения короче 15 символов — тюрьма");
		law("say_please", "Вежливость обязательна", "в каждом сообщении — «пожалуйста»");
		law("no_king_word", "Короля упоминать запрещено", "всуе — тюрьма");
		law("only_questions", "Только вопросы", "каждое сообщение должно заканчиваться «?»");
		law("hold_bread", "Держи хлеб", "хлеб в руке — признак благонадёжности")
				.tick(holdItem(Items.BREAD, "хлеба"));
		law("hold_torch", "Светоч знаний", "держи факел в руке")
				.tick(holdItem(Items.TORCH, "факела"));
		law("no_shield", "Щиты запрещены", "щит в левой руке — тюрьма")
				.tick(rule(40, p -> p.getOffHandStack().isOf(Items.SHIELD), "прятался за щитом"));
		law("no_bows", "Луки вне закона", "лук или арбалет в руке — тюрьма")
				.tick(rule(40, p -> p.getMainHandStack().isOf(Items.BOW) || p.getMainHandStack().isOf(Items.CROSSBOW), "держал лук"));
		law("no_flowers", "Цветы запрещены", "найдут цветок в инвентаре — тюрьма")
				.tick(rule(100, p -> p.getInventory().contains(ItemTags.SMALL_FLOWERS), "хранил цветы"));
		law("dungeons_closed", "Подземелья закрыты", "ниже 50 блоков спускаться нельзя")
				.tick(rule(40, p -> p.getServerWorld() == p.getServer().getOverworld() && p.getY() < 50, "залез в подземелье"));
		law("curfew", "Комендантский час", "ночью под открытым небом — тюрьма")
				.tick(rule(60, p -> !p.getServerWorld().isDay() && p.getServerWorld().isSkyVisible(p.getBlockPos().up()), "гулял ночью"));
		law("helmet_rule", "Каска обязательна", "без шлема — тюрьма")
				.tick(rule(60, p -> p.getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "ходил без каски"));
		law("no_sand", "Песок — собственность короля", "стоять на песке нельзя")
				.tick(rule(20, p -> p.isOnGround() && stateUnder(p).isIn(BlockTags.SAND), "топтал королевский песок"));
		law("no_crafting", "Крафт запрещён", "верстак опечатан");
		law("no_furnace", "Печи опечатаны", "жарить ничего нельзя");
		law("no_chests", "Сундуки опечатаны", "открыл — сел");
		law("no_beds", "Сон запрещён", "кто ляжет в кровать — проснётся в тюрьме");
		law("no_boats", "Лодки конфискованы", "сидеть в лодке — тюрьма")
				.tick(rule(40, p -> p.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity, "катался на лодке"));
		law("no_riding", "Верховая езда — для дворян", "простолюдину на лошади — тюрьма")
				.tick(rule(40, p -> !Ranks.noble(p) && p.getVehicle() instanceof net.minecraft.entity.passive.AbstractHorseEntity, "ездил верхом без дворянства"));

		// --- планы (101-106)
		law("quota_walk", "Норма шагов", "пройди 200 блоков за указ")
				.end(quota("walk", 200, "шаги"));
		law("quota_bow10", "Десять поклонов", "присядь 10 раз в честь короля")
				.end(quota("bows", 10, "поклоны"));
		law("stakhanov", "Стахановец", "сломай 50 блоков за указ")
				.end(quota("breaks", 50, "добыча"));
		law("three_heads", "Три головы", "убей трёх мобов за указ")
				.end(quota("kills", 3, "убийства"));
		law("chatterbox", "Три сообщения", "напиши в чат трижды")
				.end(quota("chats", 3, "болтовня"));
		law("jump30", "Тридцать прыжков", "напрыгай 30 раз")
				.end(quota("jumps", 30, "прыжки"));

		// --- звания, работа, отель, суд, война (107-118)
		law("rank_gift", "Король раздаёт звания", "один случайный гражданин повышен на ступень")
				.start(s -> { List<ServerPlayerEntity> ps = U.free(s); if (ps.isEmpty()) return; ServerPlayerEntity p = U.pick(ps); if (Ranks.get(p) < Ranks.NOBLE) { Ranks.set(p, Ranks.get(p) + 1); U.say(s, U.t("👑 " + p.getName().getString() + " повышен до «" + Ranks.name(p) + "»", Formatting.GOLD)); } });
		law("demotion", "Разжалование", "один случайный гражданин понижен на ступень")
				.start(s -> { List<ServerPlayerEntity> ps = U.free(s); if (ps.isEmpty()) return; ServerPlayerEntity p = U.pick(ps); if (Ranks.get(p) > 0) { Ranks.set(p, Ranks.get(p) - 1); U.say(s, U.t("👑 " + p.getName().getString() + " разжалован до «" + Ranks.name(p) + "»", Formatting.RED)); } });
		law("noble_tax", "Налог на дворянство", "дворяне платят 50 за благородство")
				.each(p -> { if (Ranks.noble(p)) Money.fine(p, 50, "налог на дворянство"); });
		law("job_bonus", "Премия работникам", "все трудоустроенные получают +40")
				.each(p -> { if (Jobs.jobOf(p) != null) Money.give(p, 40, "премия"); });
		law("mass_firing", "Массовые увольнения", "все уволены. Кризис")
				.each(p -> Jobs.quit(p));
		law("hotel_free", "Отель бесплатно", "номера в отеле сейчас бесплатные");
		law("hotel_sale", "Скидки в отеле", "номера за полцены");
		law("eviction", "Выселение", "все постояльцы отеля выселены")
				.start(Hotel::evictAll);
		law("court_lottery", "Судебная лотерея", "один случайный гражданин идёт под суд «для проверки»")
				.start(s -> { List<ServerPlayerEntity> ps = U.free(s); if (!ps.isEmpty()) Jail.arrest(U.pick(ps), "судебная лотерея", 15); });
		law("peace_talks", "Мирные переговоры", "все солдаты возвращаются с войны")
				.start(War::releaseAll);
		law("prison_break", "Побег из тюрьмы", "все заключённые сбежали! Штраф 20 за побег")
				.start(s -> { for (ServerPlayerEntity p : new ArrayList<>(s.getPlayerManager().getPlayerList())) if (Jail.jailed(p) && !Court.inTrial(p)) { Jail.release(p, "массовый побег"); Money.fine(p, 20, "побег"); } });
		law("housing_aid", "Субсидия на жильё", "у кого есть прописка — +25")
				.each(p -> { if (Address.registered(p.getServer(), p.getUuid())) Money.give(p, 25, "субсидия на жильё"); });

		// --- просто абсурд (119-128)
		law("rename_mobs", "День переименования", "все мобы вокруг получили новые имена")
				.each(p -> {
					for (net.minecraft.entity.mob.MobEntity m : p.getServerWorld().getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, p.getBoundingBox().expand(32), m -> !m.hasCustomName() && m.getCommandTags().isEmpty())) {
						m.setCustomName(U.t(U.pick("Гена", "Валера", "Зинаида", "Толик", "Аркадий", "Бабушка", "Шнырь", "Пельмень", "Сосед")));
					}
				});
		law("jeb", "Радужный день", "все овцы вокруг стали радужными")
				.each(p -> {
					for (SheepEntity sh : p.getServerWorld().getEntitiesByClass(SheepEntity.class, p.getBoundingBox().expand(32), x -> !x.hasCustomName())) sh.setCustomName(U.t("jeb_"));
				});
		law("radio", "Королевское радио", "играет королевская музыка")
				.tick((p, t) -> { if (t % 6 == 0) U.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_HARP, 0.5f, 0.5f + ((t / 6) % 8) * 0.2f); });
		law("bells", "Колокольный звон", "колокола звонят каждые 3 секунды")
				.tick((p, t) -> { if (t % 60 == 0) U.sound(p, SoundEvents.BLOCK_BELL_USE, 1f, 0.8f + U.R.nextFloat() * 0.4f); });
		law("propaganda", "Пропаганда", "король напоминает, какой он хороший")
				.tick((p, t) -> { if (t % 200 == 0) U.title(p, U.t(U.pick("КОРОЛЬ — ЛУЧШИЙ", "НАЛОГИ — ЭТО ЛЮБОВЬ", "КАРЕТА ЗАБОТИТСЯ О ТЕБЕ", "ТЮРЬМА — ЭТО ОТДЫХ", "ВОЙНА — ЭТО МИР"), Formatting.GOLD, Formatting.BOLD), U.t("— Министерство правды", Formatting.GRAY)); });
		law("fake_alarm", "Учебная тревога", "НАЛОГОВАЯ! (шутка)")
				.each(p -> { U.title(p, U.t("💸 НАЛОГОВАЯ 💸", Formatting.DARK_RED, Formatting.BOLD), U.t("…учебная тревога. Расслабьтесь", Formatting.GRAY)); U.sound(p, SoundEvents.EVENT_RAID_HORN, 2f, 1f); });
		law("forbes", "Список Forbes", "король оглашает самых богатых")
				.start(s -> { List<ServerPlayerEntity> ps = new ArrayList<>(U.players(s)); ps.sort(Comparator.<ServerPlayerEntity>comparingInt(Money::get).reversed()); for (int i = 0; i < Math.min(3, ps.size()); i++) U.say(s, U.t("💰 " + (i + 1) + ". " + ps.get(i).getName().getString() + " — " + Money.get(ps.get(i)) + " бабла", Formatting.GOLD)); });
		law("census", "Перепись населения", "король узнаёт, кто кем работает")
				.start(s -> { for (ServerPlayerEntity p : U.players(s)) { Jobs.Job j = Jobs.jobOf(p); U.say(s, U.t("📋 " + p.getName().getString() + ": " + Ranks.name(p) + ", " + (j == null ? "безработный" : j.name()), Formatting.GRAY)); } });
		law("sunset", "Вечный закат", "король любит закаты")
				.start(s -> s.getOverworld().setTimeOfDay(12500));
		// --- версия 1.5: документы, дроны, чипсы
		law("docs_check", "Проверка документов", "у кого нет действующего паспорта при себе — в тюрьму")
				.each(p -> {
					Docs.Check c = Docs.check(p);
					if (c == Docs.Check.OK) return;
					if (c == Docs.Check.FOREIGN) Jail.arrest(p, "использование чужого паспорта", 60);
					else if (c == Docs.Check.COPY) Jail.arrest(p, "подделка документов", 45);
					else Jail.arrest(p, "отсутствие документов (" + Docs.checkText(c) + ")", 30);
				});
		law("passport_regime", "Паспортный режим", "патрули каждые 10 секунд проверяют случайного гражданина")
				.world((s, t) -> {
					if (t % 200 != 0 || t < GRACE) return;
					List<ServerPlayerEntity> ps = U.free(s);
					if (ps.isEmpty()) return;
					ServerPlayerEntity p = U.pick(ps);
					Docs.Check c = Docs.check(p);
					U.msg(p, U.t("🛡 Патруль: «Документики!» — " + Docs.checkText(c), Formatting.YELLOW));
					if (c != Docs.Check.OK) Jail.arrest(p, "отсутствие документов при патрульной проверке", 30);
				});
		law("drone_strike", "Дроновая атака", "к случайным гражданам летят FPV-дроны. С сюрпризом")
				.world((s, t) -> {
					if (t % 260 != 0) return;
					List<ServerPlayerEntity> ps = U.free(s);
					if (!ps.isEmpty()) Fpv.launch(U.pick(ps), 2);
				});
		law("drone_delivery", "Доставка дронами", "каждому гражданину — посылка с неба")
				.each(p -> Fpv.launch(p, 0));
		law("chips_day", "День чипсов", "каждому по пачке «Хрустика» со вкусом лобстера")
				.each(p -> give(p, Fpv.chips(2)));
		law("property_census", "Перепись недвижимости", "владельцы домов платят 20 за перепись, бездомные — штраф 10")
				.each(p -> {
					if (PizdecState.get(p.getServer()).properties.containsKey(p.getUuid())) Money.fine(p, 20, "перепись недвижимости");
					else Money.fine(p, 10, "бездомность");
				});
		// --- версия 1.6: выборы, парламент, СМИ
		law("free_elections", "Свободные выборы", "ближайшие выборы пройдут максимально честно. Слово короля");
		law("agitation", "Агитация", "каждые 15 секунд гражданам напоминают, за кого голосовать")
				.tick((p, t) -> {
					if (t % 300 == 0) U.msg(p, U.t("📣 Голосуй за «" + Elections.PARTIES.get(0).name() + "»! " + News.headline(p.getServer()),
							Formatting.GOLD));
				});
		law("silence_day", "День тишины", "слово «выборы» в чате запрещено. Агитация — тюрьма");
		law("mandatory_vote", "Обязательное голосование", "кто не проголосовал на текущих выборах к концу указа — штраф 20")
				.end(s -> {
					PizdecState st = PizdecState.get(s);
					for (ServerPlayerEntity p : U.free(s)) if (!st.votes.containsKey(p.getUuid())) Money.fine(p, 20, "неявка на выборы");
				});
		law("free_press", "Свобода прессы", "каждому гражданину — свежая «Королевская правда» бесплатно")
				.each(p -> give(p, News.newspaper(p.getServer())));
		law("early_elections", "Досрочные выборы", "выборы — прямо сейчас! Неожиданно, правда?")
				.start(s -> {
					PizdecState.get(s).electionTicks = 0;
					Elections.hold(s);
				});
		law("dissolve_parliament", "Роспуск парламента", "все места отданы самой надёжной партии до следующих выборов")
				.start(s -> {
					PizdecState st = PizdecState.get(s);
					st.seats = new int[] {Castle.PARLIAMENT_SEATS, 0, 0, 0, 0, 0, 0};
					st.markDirty();
					Castle.paintSeats(s);
				});
		law("tv_mandatory", "Обязательный просмотр новостей", "всем показывают «Первый Королевский» прямо в голову")
				.tick((p, t) -> {
					if (t % 200 == 0) U.title(p, U.t("📺 ПЕРВЫЙ КОРОЛЕВСКИЙ", Formatting.AQUA, Formatting.BOLD),
							U.t(News.headline(p.getServer()), Formatting.WHITE));
				});
		law("weather_chaos", "Погодный хаос", "погода меняется каждые 10 секунд")
				.world((s, t) -> { if (t % 200 == 1) { int r = U.R.nextInt(3); s.getOverworld().setWeather(r == 0 ? 600 : 0, r == 0 ? 0 : 600, r != 0, r == 2); } });

		// ===== версия 1.7: тайная полиция, граница, ресторан, пропаганда =====
		law("vigilance_week", "Неделя бдительности", "тайная полиция подозревает всех вдвое сильнее, облавы опаснее");
		law("suspicion_amnesty", "Амнистия подозреваемых", "тайная полиция прощает всем половину подозрений")
				.start(s -> {
					PizdecState st = PizdecState.get(s);
					st.suspicion.replaceAll((k, v) -> v / 2);
					st.markDirty();
				});
		law("free_burger", "Бесплатный бургер", "всё меню «Вкусно и Точка» бесплатно, пока действует указ");
		law("border_locked", "Граница на замке", "выезд запрещён, разрешения не выдаются, КПП закрыты");
		law("tourist_year", "Год туризма", "иностранные туристы приезжают каждые 2 минуты");
		law("glory_hour", "Час славы", "Минута славы — каждую минуту. Не поклонился — под подозрением")
				.world((s, t) -> { if (t % (20 * 60) == 40) Propaganda.minuteOfGlory(s); });
		law("love_index_record", "Рекорд любви", "индекс любви к королю официально равен 146%")
				.start(s -> Propaganda.record = true).end(s -> Propaganda.record = false);
		law("portrait_bow", "Поклон портрету", "проходя мимо портрета короля — поклонись (присядь), иначе тюрьма")
				.tick((p, t) -> {
					if (t % 20 != 0 || p.isSneaking()) return;
					if (Portraits.nearPortrait(p, 3) && !Portraits.bowedRecently(p)) v(p, "не поклонился портрету короля");
				});
	}

	// ------------------------------------------------------------------ события от главного класса

	public static void onJump(ServerPlayerEntity p) {
		inc("jumps", p);
		lastJump.put(p.getUuid(), lawTicks);
		if (is("toll_jump")) Money.fine(p, 1, "платный прыжок");
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
		if (is("no_crafting") && b.isOf(Blocks.CRAFTING_TABLE)) {
			v(p, "крафтил без разрешения");
			return false;
		}
		if (is("no_furnace") && (b.isOf(Blocks.FURNACE) || b.isOf(Blocks.BLAST_FURNACE) || b.isOf(Blocks.SMOKER))) {
			v(p, "сорвал пломбу с печи");
			return false;
		}
		if (is("no_chests") && (b.isOf(Blocks.CHEST) || b.isOf(Blocks.BARREL) || b.isOf(Blocks.TRAPPED_CHEST))) {
			v(p, "вскрыл опечатанный сундук");
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
		else if (is("no_numbers") && text.matches(".*[0-9].*")) crime = "использовал цифры";
		else if (is("short_msgs") && text.length() > 20) crime = "болтал слишком длинно";
		else if (is("long_msgs") && text.length() < 15) crime = "говорил слишком коротко";
		else if (is("say_please") && !text.toLowerCase().contains("пожалуйста")) crime = "не сказал «пожалуйста»";
		else if (is("no_king_word") && text.toLowerCase().contains("корол")) crime = "упоминал короля всуе";
		else if (is("only_questions") && !text.trim().endsWith("?")) crime = "сказал что-то утвердительно";
		else if (is("silence_day") && text.toLowerCase().contains("выбор")) crime = "агитация в день тишины";
		if (crime == null && is("tip_king")) Money.fine(p, 2, "чаевые королю");

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
		Law l;
		do {
			l = U.pick(ALL);
		} while (l == current && ALL.size() > 1);
		enact(s, l, null);
	}

	/** Ввести конкретный закон (например, принятый парламентом). */
	public static void enact(MinecraftServer s, Law l, String how) {
		if (current != null) {
			current.end.accept(s);
			clearAttrs(s);
		}
		counters.clear();
		lastSneak.clear();
		lastJump.clear();
		current = l;
		PizdecMod.resetLawTimer();
		lawTicks = 0;

		PizdecState st = PizdecState.get(s);
		st.decrees++;
		st.markDirty();

		U.say(s, U.t("📜 Указ №" + st.decrees + " Его Величества " + Castle.KING_NAME + (how == null ? "" : " (" + how + ")") + ":",
				Formatting.GOLD));
		U.say(s, U.t("   «" + l.title + "» — " + l.detail, Formatting.YELLOW));
		U.titleAll(s, U.t("📜 " + l.title, Formatting.GOLD, Formatting.BOLD), U.t(l.detail, Formatting.YELLOW));
		U.soundAll(s, SoundEvents.BLOCK_BELL_USE, 1.5f, 1.2f);

		VillagerEntity king = Castle.findKing(s);
		if (king != null) {
			((ServerWorld) king.getWorld()).spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, king.getX(), king.getY() + 2,
					king.getZ(), 40, 0.5, 0.5, 0.5, 0.3);
		}

		l.start.accept(s);
		Ranks.onDecree(s);
	}

	public static void tick(MinecraftServer s) {
		lawTicks++;
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			UUID id = p.getUuid();
			if (lawTicks % 20 == 0) {
				Vec3d now = p.getPos();
				Vec3d was = lastPos.put(id, now);
				lastDelta.put(id, was == null ? Vec3d.ZERO : now.subtract(was));
				Float yWas = lastYaw.put(id, p.getYaw());
				float dy = yWas == null ? 0f : p.getYaw() - yWas;
				dy = ((dy % 360f) + 540f) % 360f - 180f;
				yawDelta.put(id, dy);
				Vec3d dl = lastDelta.get(id);
				if (dl != null) {
					int m = (int) Math.round(Math.sqrt(dl.x * dl.x + dl.z * dl.z));
					if (m > 0 && m < 12) counters.computeIfAbsent("walk", k -> new HashMap<>()).merge(id, m, Integer::sum);
				}
			}
			boolean sn = p.isSneaking();
			Boolean before = wasSneaking.put(id, sn);
			if (sn && (before == null || !before)) {
				lastSneak.put(id, lawTicks);
				inc("bows", p);
				if (is("pay_sneak") && !Jail.busy(p)) Money.fine(p, 1, "платное приседание");
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
		lastYaw.remove(id);
		yawDelta.remove(id);
	}
}
