package com.pizdec.mod;

import static com.pizdec.mod.B.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * Закрытая граница королевства: квадрат ±1000 блоков от замка, 4 КПП (север, юг, запад, восток),
 * пограничники, таможня, разрешения на выезд, контрабанда, туристы и почти невозможный побег.
 */
public final class Border {
	public static final int R = 1000;
	public static final String OFFICER_TAG = "pizdec_border";
	public static final String CUSTOMS_TAG = "pizdec_customs";
	public static final String DUTYFREE_TAG = "pizdec_dutyfree";
	public static final String GUARD_TAG = "pizdec_borderguard";
	public static final String TOURIST_TAG = "pizdec_tourist";
	public static final String GUIDE_TAG = "pizdec_guide";
	public static final double ESCAPE = 0.005, ESCAPE_UNDERGROUND = 0.01;
	public static final long PERMIT = 20L * 60 * 20, TRIP = 20L * 60 * 10;

	public static final String[] SIDES = {"Северный", "Южный", "Западный", "Восточный"};
	private static final int[][] DIRS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

	/** Разрешения на выезд: UUID → до какого мирового тика. */
	private static final Map<UUID, Long> permits = new HashMap<>();
	private static final Set<UUID> returnedOnce = new HashSet<>();
	private static final Map<UUID, Integer> lastWarn = new HashMap<>();

	private Border() {}

	// ================================================================== геометрия

	public static int bx(PizdecState st) {
		return st.cx + Castle.SX / 2;
	}

	public static int bz(PizdecState st) {
		return st.cz + Castle.SZ / 2;
	}

	/** Насколько далеко за линией границы (>0 — снаружи). */
	public static double outside(PizdecState st, Vec3d p) {
		return Math.max(Math.abs(p.x - bx(st) - 0.5), Math.abs(p.z - bz(st) - 0.5)) - R;
	}

	/** Мировые координаты точки КПП k: u — от линии наружу, v — поперёк дороги. */
	private static int[] at(PizdecState st, int k, int u, int v) {
		int dx = DIRS[k][0], dz = DIRS[k][1];
		int ax = -dz, az = dx;
		int x = bx(st) + dx * R + dx * u + ax * v;
		int z = bz(st) + dz * R + dz * u + az * v;
		return new int[] {x, z};
	}

	private static Vec3d atV(PizdecState st, int k, double u, double v) {
		int dx = DIRS[k][0], dz = DIRS[k][1];
		double ax = -dz, az = dx;
		return new Vec3d(bx(st) + dx * R + dx * u + ax * v + 0.5, st.borderY[k], bz(st) + dz * R + dz * u + az * v + 0.5);
	}

	private static Direction out(int k) {
		return switch (k) {
			case 0 -> Direction.NORTH;
			case 1 -> Direction.SOUTH;
			case 2 -> Direction.WEST;
			default -> Direction.EAST;
		};
	}

	/** Ближайший КПП, если игрок в пределах dist блоков от него. */
	public static int nearCheckpoint(PizdecState st, Vec3d p, double dist) {
		if (!st.borderBuilt) return -1;
		for (int k = 0; k < 4; k++) {
			Vec3d c = atV(st, k, 0, 0);
			if (Math.abs(p.x - c.x) <= dist && Math.abs(p.z - c.z) <= dist) return k;
		}
		return -1;
	}

	public static boolean inside(PizdecState st, BlockPos p) {
		if (!st.borderBuilt) return false;
		for (int k = 0; k < 4; k++) {
			int[] c = at(st, k, 0, 0);
			if (Math.abs(p.getX() - c[0]) <= 13 && Math.abs(p.getZ() - c[1]) <= 13 && Math.abs(p.getY() - st.borderY[k]) <= 16) {
				return true;
			}
		}
		return false;
	}

	public static Vec3d checkpointPos(PizdecState st, int k) {
		return atV(st, k, -3, 0);
	}

	// ================================================================== стройка КПП

	public static void ensureBuilt(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (st.borderBuilt || !st.castleBuilt) return;
		ServerWorld world = s.getOverworld();
		w = world;
		for (int k = 0; k < 4; k++) {
			int[] c = at(st, k, 0, 0);
			loadChunks(c[0] - 16, c[1] - 16, c[0] + 16, c[1] + 16);
			int y = ground(c[0], c[1]);
			st.borderY[k] = y;
			buildCheckpoint(st, k, y);
			spawnCheckpointNpcs(world, st, k);
		}
		st.borderBuilt = true;
		st.markDirty();
		PizdecMod.LOG.info("Граница королевства обустроена: 4 КПП в {} блоках от замка", R);
	}

	private static void put(PizdecState st, int k, int u, int y, int v, Block b) {
		int[] c = at(st, k, u, v);
		set(c[0], y, c[1], b);
	}

	private static void put(PizdecState st, int k, int u, int y, int v, BlockState b) {
		int[] c = at(st, k, u, v);
		set(c[0], y, c[1], b);
	}

	private static void box(PizdecState st, int k, int u0, int y0, int v0, int u1, int y1, int v1, Block b) {
		for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
			for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) {
				for (int y = y0; y <= y1; y++) put(st, k, u, y, v, b);
			}
		}
	}

	private static void post(PizdecState st, int k, int u, int y, int v, int h) {
		for (int i = 0; i < h; i++) put(st, k, u, y + i, v, i % 2 == 0 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE);
	}

	private static void buildCheckpoint(PizdecState st, int k, int y) {
		int[] a = at(st, k, -14, -12), b = at(st, k, 14, 12);
		site(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), y, 20, Blocks.GRASS_BLOCK,
				Blocks.DIRT);
		Direction o = out(k), in = o.getOpposite();
		// дорога и линия границы
		for (int u = -14; u <= 14; u++) for (int v = -2; v <= 2; v++) put(st, k, u, y - 1, v, Blocks.POLISHED_ANDESITE);
		for (int v = -12; v <= 12; v++) put(st, k, 0, y - 1, v, Blocks.RED_CONCRETE);
		for (int u = -14; u <= 14; u += 4) put(st, k, u, y - 1, 0, Blocks.YELLOW_CONCRETE);
		// столбы по линии
		for (int v : new int[] {-12, -8, 8, 12}) post(st, k, 0, y, v, 3);
		// шлагбаумы (закрыты всегда — открывает только пограничник)
		for (int u : new int[] {-1, 1}) {
			post(st, k, u, y, -3, 2);
			post(st, k, u, y, 3, 2);
			for (int v = -2; v <= 2; v++) put(st, k, u, y + 1, v, (v + 2) % 2 == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
		}
		// будка паспортного контроля (наша сторона) u -6..-3, v 4..7
		box(st, k, -6, y, 4, -3, y + 3, 7, Blocks.WHITE_CONCRETE);
		box(st, k, -5, y, 5, -4, y + 2, 6, Blocks.AIR);
		box(st, k, -6, y + 4, 4, -3, y + 4, 7, Blocks.GREEN_CONCRETE);
		put(st, k, -5, y + 1, 4, Blocks.AIR); // окошко к дороге
		put(st, k, -4, y + 1, 4, Blocks.AIR);
		put(st, k, -5, y, 7, Blocks.AIR); // дверь сзади
		put(st, k, -5, y + 1, 7, Blocks.AIR);
		put(st, k, -4, y - 1, 5, Blocks.SPRUCE_PLANKS);
		put(st, k, -4, y + 3, 5, B.hangingLantern());
		// таможня (чужая сторона) u 3..6, v -7..-4
		box(st, k, 3, y, -7, 6, y + 3, -4, Blocks.LIGHT_GRAY_CONCRETE);
		box(st, k, 4, y, -6, 5, y + 2, -5, Blocks.AIR);
		box(st, k, 3, y + 4, -7, 6, y + 4, -4, Blocks.BLUE_CONCRETE);
		put(st, k, 4, y + 1, -4, Blocks.AIR);
		put(st, k, 5, y + 1, -4, Blocks.AIR);
		put(st, k, 4, y, -7, Blocks.AIR);
		put(st, k, 4, y + 1, -7, Blocks.AIR);
		put(st, k, 5, y + 3, -5, B.hangingLantern());
		// магазин Duty Free (чужая сторона) u 8..11, v 4..7
		box(st, k, 8, y, 4, 11, y + 3, 7, Blocks.YELLOW_CONCRETE);
		box(st, k, 9, y, 5, 10, y + 2, 6, Blocks.AIR);
		box(st, k, 8, y + 4, 4, 11, y + 4, 7, Blocks.ORANGE_CONCRETE);
		put(st, k, 9, y + 1, 4, Blocks.AIR);
		put(st, k, 10, y + 1, 4, Blocks.AIR);
		put(st, k, 9, y + 3, 6, B.hangingLantern());
		// вышка u -9..-7, v -8..-6
		box(st, k, -9, y, -8, -7, y + 8, -6, Blocks.STONE_BRICKS);
		box(st, k, -8, y, -7, -8, y + 8, -7, Blocks.AIR);
		for (int yy = y; yy <= y + 8; yy++) put(st, k, -8, yy, -7, Blocks.SCAFFOLDING);
		box(st, k, -10, y + 9, -9, -6, y + 9, -5, Blocks.SPRUCE_PLANKS);
		for (int u = -10; u <= -6; u++) {
			for (int v = -9; v <= -5; v++) {
				if (u == -10 || u == -6 || v == -9 || v == -5) put(st, k, u, y + 10, v, Blocks.SPRUCE_FENCE);
			}
		}
		put(st, k, -8, y + 9, -7, Blocks.SCAFFOLDING);
		put(st, k, -8, y + 11, -7, Blocks.REDSTONE_LAMP);
		put(st, k, -8, y + 12, -7, Blocks.REDSTONE_BLOCK);
		// флагшток королевства
		for (int yy = y; yy <= y + 9; yy++) put(st, k, -8, yy, 9, Blocks.OAK_FENCE);
		for (int i = 1; i <= 3; i++) {
			put(st, k, -8, y + 9, 9 + i, Blocks.RED_WOOL);
			put(st, k, -8, y + 8, 9 + i, i == 2 ? Blocks.YELLOW_WOOL : Blocks.RED_WOOL);
		}
		// фонари на дороге
		for (int u : new int[] {-10, 10}) {
			int[] c = at(st, k, u, 3);
			lampPost(c[0], y, c[1]);
			c = at(st, k, u, -3);
			lampPost(c[0], y, c[1]);
		}
		// таблички
		int[] t1 = at(st, k, -2, -3);
		Restaurant.sign(t1[0], y + 1, t1[1], in, "§4ГОСУДАРСТВЕННАЯ", "§4ГРАНИЦА", "Выезд: /vyezd", "по разрешению МФЦ");
		int[] t2 = at(st, k, 2, 3);
		Restaurant.sign(t2[0], y + 1, t2[1], o, "Королевство", "§lПиздец", "Добро пожаловать!", "Налоги — на въезде");
		int[] t3 = at(st, k, 7, -4);
		Restaurant.sign(t3[0], y + 2, t3[1], o, "ТАМОЖНЯ", "Контрабанда —", "конфискация", "и тюрьма");
		int[] t4 = at(st, k, 10, 3);
		Restaurant.sign(t4[0], y + 3, t4[1], side(k, -1), "§6DUTY FREE", "Импорт без", "пошлин*", "§8*пошлина на въезде");
		int[] t5 = at(st, k, -5, 3);
		Restaurant.sign(t5[0], y + 3, t5[1], side(k, -1), "Паспортный", "контроль", SIDES[k] + " КПП", "");
		// громкоговоритель на вышке
		put(st, k, -10, y + 11, -9, Blocks.NOTE_BLOCK);
	}

	private static VillagerEntity npc(ServerWorld world, PizdecState st, int k, double u, double v, Direction face, String tag,
			String name, Formatting f, VillagerProfession prof) {
		Vec3d p = atV(st, k, u, v);
		return Castle.npc(world, p.x, p.y, p.z, face.asRotation(), tag, U.t(name, f), prof);
	}

	private static Direction side(int k, int sign) {
		// направление «поперёк дороги» в сторону +v (sign=1) или −v (sign=-1)
		int dx = DIRS[k][0], dz = DIRS[k][1];
		int ax = -dz * sign, az = dx * sign;
		return Direction.fromVector(ax, 0, az);
	}

	private static void spawnCheckpointNpcs(ServerWorld world, PizdecState st, int k) {
		npc(world, st, k, -4, 5, side(k, -1), OFFICER_TAG, "🛂 Пограничник ефрейтор Бдительный (" + SIDES[k] + " КПП)",
				Formatting.DARK_GREEN, VillagerProfession.WEAPONSMITH);
		npc(world, st, k, 4, -5, side(k, 1), CUSTOMS_TAG, "🧳 Таможенник Досмотров", Formatting.BLUE,
				VillagerProfession.LIBRARIAN);
		npc(world, st, k, 9, 5, side(k, -1), DUTYFREE_TAG, "🛍 Duty Free «Заграница»", Formatting.GOLD,
				VillagerProfession.FARMER);
		for (double v : new double[] {-3, 3}) {
			IronGolemEntity g = EntityType.IRON_GOLEM.create(world);
			if (g == null) continue;
			Vec3d p = atV(st, k, -2, v);
			float yaw = out(k).asRotation();
			g.refreshPositionAndAngles(p.x, p.y, p.z, yaw, 0f);
			g.setHeadYaw(yaw);
			g.setBodyYaw(yaw);
			g.setAiDisabled(true);
			g.setInvulnerable(true);
			g.setPersistent();
			g.setCustomName(U.t("Пограничник с собакой Налогом", Formatting.DARK_GREEN));
			g.setCustomNameVisible(true);
			g.addCommandTag(GUARD_TAG);
			g.addCommandTag(Castle.VERSION_TAG);
			world.spawnEntity(g);
		}
	}

	// ================================================================== контрабанда и импорт

	public static ItemStack foreign(String id) {
		return switch (id) {
			case "gum" -> Gui.special(Items.PINK_DYE, "foreign_gum", "§dЖвачка «Бабл-Ням» (импорт)",
					"§7Вкладыш с машинкой внутри", "§4Контрабанда");
			case "jeans" -> {
				ItemStack st = Gui.special(Items.LEATHER_LEGGINGS, "foreign_jeans", "§9Джинсы (заграничные)",
						"§7Настоящий деним", "§4Контрабанда. Носить — вызывающе.");
				st.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x2E4A8B, true));
				yield st;
			}
			case "cheese" -> Gui.special(Items.YELLOW_DYE, "foreign_cheese", "§eСыр «Пармезан» (санкционный)",
					"§7Пахнет свободой", "§4Контрабанда");
			case "cola" -> Gui.special(Items.GLASS_BOTTLE, "foreign_cola", "§cГазировка «Заморская»",
					"§7Шипит по-иностранному", "§4Контрабанда");
			default -> Gui.special(Items.BOOK, "foreign_magazine", "§fИностранный журнал",
					"§7Там пишут, что у них нет короля", "§4Контрабанда. Особо опасно.");
		};
	}

	public static final String[] FOREIGN = {"gum", "cheese", "cola", "jeans", "magazine"};
	public static final int[] FOREIGN_PRICE = {20, 40, 25, 60, 30};

	/** Базовая цена импортного товара (0 — не импорт). */
	public static int foreignPrice(ItemStack st) {
		for (int i = 0; i < FOREIGN.length; i++) if (Gui.isSpecial(st, "foreign_" + FOREIGN[i])) return FOREIGN_PRICE[i];
		return 0;
	}

	public static boolean isContraband(ItemStack st) {
		if (st.isEmpty()) return false;
		return st.isOf(Items.TNT) || st.isOf(Items.END_CRYSTAL) || st.isOf(Items.TNT_MINECART) || Gui.isSpecial(st, "fake_diamond")
				|| Gui.isSpecial(st, "leaflet") || foreignPrice(st) > 0;
	}

	public static String label(ItemStack st) {
		return st.getCount() + "× " + st.getName().getString();
	}

	/** Изымает всю контрабанду из инвентаря, возвращает список изъятого. */
	public static List<String> confiscate(ServerPlayerEntity p) {
		List<String> out = new ArrayList<>();
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack st = inv.getStack(i);
			if (isContraband(st)) {
				out.add(label(st));
				inv.setStack(i, ItemStack.EMPTY);
			}
		}
		return out;
	}

	private static boolean hasContraband(ServerPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) if (isContraband(inv.getStack(i))) return true;
		return false;
	}

	/** Таможенный досмотр. true — можно ехать дальше. */
	private static boolean customs(ServerPlayerEntity p, String dir) {
		if (!hasContraband(p)) {
			U.msg(p, U.t("🧳 Таможня: «Ничего запрещённого. Скучно.»", Formatting.BLUE));
			return true;
		}
		if (U.chance(0.2)) {
			U.msg(p, U.t("🧳 Таможенник зевнул и не заметил контрабанду. Повезло. " + dir, Formatting.GREEN));
			return true;
		}
		List<String> got = confiscate(p);
		U.msg(p, U.t("🧳 Таможня: изъято " + String.join(", ", got) + ".", Formatting.RED));
		SecretPolice.add(p, 20, "контрабанда на границе: " + String.join(", ", got));
		Jail.arrest(p, "контрабанда (" + String.join(", ", got) + ")", 60);
		return false;
	}

	// ================================================================== разрешение на выезд (МФЦ, окно №4)

	public static boolean hasPermit(ServerPlayerEntity p) {
		Long until = permits.get(p.getUuid());
		return until != null && until > p.getServer().getOverworld().getTime();
	}

	public static void issuePermit(ServerPlayerEntity p) {
		if (!Docs.require(p, "разрешения на выезд")) return;
		if (Laws.is("border_locked")) {
			U.msg(p, U.t("📝 Нотариус: «Граница на замке по указу короля. Разрешений нет и не будет.»", Formatting.RED));
			return;
		}
		if (hasPermit(p)) {
			U.msg(p, U.t("📝 У вас уже есть действующее разрешение на выезд.", Formatting.GRAY));
			return;
		}
		if (!Money.pay(p, 300, "госпошлину за разрешение на выезд")) return;
		int sus = SecretPolice.get(p);
		if (sus >= 40) {
			U.msg(p, U.t("📝 В выдаче разрешения отказано. Причина: вам виднее. Госпошлина не возвращается.", Formatting.RED));
			SecretPolice.add(p, 5, "запрашивал разрешение на выезд");
			return;
		}
		if (U.chance(0.15)) {
			U.msg(p, U.t("📝 Отказано: вы — носитель государственной тайны (какой — тоже тайна). Госпошлина не возвращается.",
					Formatting.RED));
			return;
		}
		long until = p.getServer().getOverworld().getTime() + PERMIT;
		permits.put(p.getUuid(), until);
		SecretPolice.add(p, 3, "получил разрешение на выезд");
		p.getInventory().offerOrDrop(Gui.special(Items.PAPER, "exit_permit", "§aРазрешение на выезд",
				"§7Действительно 20 минут", "§7Предъявить на любом КПП: /vyezd", "§7Пребывание за границей — до 10 минут",
				"§8Выдано окном №4 МФЦ «Мои документы»"));
		U.msg(p, U.t("📝 Разрешение на выезд выдано. Действует 20 минут. КПП: " + checkpointsText(PizdecState.get(p.getServer())),
				Formatting.GREEN));
	}

	public static String checkpointsText(PizdecState st) {
		if (!st.borderBuilt) return "не построены";
		List<String> l = new ArrayList<>();
		for (int k = 0; k < 4; k++) {
			Vec3d c = checkpointPos(st, k);
			l.add(SIDES[k] + " " + (int) c.x + " " + (int) c.z);
		}
		return String.join(", ", l);
	}

	// ================================================================== выезд

	public static void exit(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		PizdecState st = PizdecState.get(s);
		int k = nearCheckpoint(st, p.getPos(), 12);
		if (k < 0 || outside(st, p.getPos()) > 0) {
			U.msg(p, U.t("🛂 Выезд только через КПП (стоя у будки пограничника). КПП: " + checkpointsText(st), Formatting.GRAY));
			return;
		}
		if (Jail.busy(p)) return;
		String who = "🛂 Ефрейтор Бдительный: ";
		if (Laws.is("border_locked")) {
			U.msg(p, U.t(who + "«Граница закрыта указом. Разворачивайтесь.»", Formatting.RED));
			return;
		}
		Docs.Check c = Docs.check(p);
		if (c != Docs.Check.OK) {
			U.msg(p, U.t(who + "«Паспорт: " + Docs.checkText(c) + ". Без паспорта граница — только в одну сторону. В тюрьму.»",
					Formatting.RED));
			SecretPolice.add(p, 5, "пытался выехать без паспорта");
			return;
		}
		if (!hasPermit(p)) {
			U.msg(p, U.t(who + "«Где разрешение на выезд? МФЦ, окно №4. Без него — никак.»", Formatting.RED));
			SecretPolice.add(p, 3, "пытался выехать без разрешения");
			return;
		}
		if (SecretPolice.get(p) >= 40) {
			U.msg(p, U.t(who + "«Вы в базе. Выезд ограничен. Причину не скажу — сам не знаю.»", Formatting.RED));
			SecretPolice.add(p, 5, "попытка выезда при ограничении");
			return;
		}
		if (Money.get(p) < 0) {
			U.msg(p, U.t(who + "«У вас долг " + (-Money.get(p)) + ". Должникам выезд запрещён.»", Formatting.RED));
			return;
		}
		if (!customs(p, "")) return;
		if (!Money.pay(p, 20, "пограничный сбор")) return;
		permits.remove(p.getUuid());
		long until = s.getOverworld().getTime() + TRIP;
		st.abroad.put(p.getUuid(), until);
		st.markDirty();
		SecretPolice.note(s, p.getUuid(), "легально выехал через " + SIDES[k] + " КПП");
		Vec3d to = atV(st, k, 7, 0);
		p.stopRiding();
		p.teleport(s.getOverworld(), to.x, to.y, to.z, out(k).asRotation(), 0f);
		U.title(p, U.t("🛂 ВЫЕЗД РАЗРЕШЁН", Formatting.GREEN, Formatting.BOLD),
				U.t("10 минут за границей. Указы тут не действуют.", Formatting.GRAY));
		U.sound(p, SoundEvents.BLOCK_FENCE_GATE_OPEN, 1f, 0.8f);
		U.msg(p, U.t(who + "«Счастливого пути. Возвращайтесь — у нас вас ждут. В прямом смысле.» "
				+ "За границей: Duty Free у КПП. Вернуться — до истечения 10 минут, иначе станешь невозвращенцем.",
				Formatting.DARK_GREEN));
	}

	/** Легально за границей (указы, налоги и тайная полиция не достают). */
	public static boolean abroad(ServerPlayerEntity p) {
		Long v = PizdecState.get(p.getServer()).abroad.get(p.getUuid());
		return v != null;
	}

	// ================================================================== охрана границы

	public static void tick(MinecraftServer s) {
		PizdecState st = PizdecState.get(s);
		if (!st.borderBuilt) return;
		int now = s.getTicks();
		tickTourists(s, st);
		if (now % 10 != 0) return;
		ServerWorld world = s.getOverworld();
		long time = world.getTime();
		for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
			if (p.getServerWorld() != world || p.isSpectator()) continue;
			if (Jail.jailed(p) || War.exiled(p) || SecretPolice.interrogating(p)) continue;
			double o = outside(st, p.getPos());
			Long status = st.abroad.get(p.getUuid());
			if (Math.abs(o) < 48 && now % 20 == 0) borderVisuals(world, st, p);
			if (p.isCreative()) continue;
			if (o > 0) {
				if (status == null) {
					crossing(p, st);
				} else if (status > 0 && time > status) {
					st.abroad.put(p.getUuid(), -1L);
					st.markDirty();
					SecretPolice.note(s, p.getUuid(), "не вернулся из-за границы (невозвращенец)");
					SecretPolice.setLevel(s, p.getUuid(), SecretPolice.get(p) + 30);
					U.say(s, U.t("🚨 Гражданин " + p.getName().getString() + " не вернулся из-за границы. Объявлен невозвращенцем. "
							+ "По возвращении — тюрьма.", Formatting.DARK_RED));
					News.breaking(s, "Предатель " + p.getName().getString() + " остался за границей. Народ негодует.");
				} else if (status > 0 && now % 200 == 0) {
					U.bar(p, U.t("🌍 За границей: осталось " + (status - time) / 20 + " сек. Вернуться — через КПП.",
							Formatting.AQUA));
				} else if (status < 0 && now % 1200 == 0) {
					U.msg(p, U.t("🌍 " + U.pick("Тут нет налогов. И указов. И бургеров тоже.",
							"Свобода пахнет травой. Обычной травой.", "Ты в розыске на родине. Зато тут тихо.",
							"Говорят, где-то тут есть другие королевства. Пока не нашёл."), Formatting.AQUA));
				}
				continue;
			}
			// внутри
			if (status != null) {
				st.abroad.remove(p.getUuid());
				st.markDirty();
				if (status < 0) {
					SecretPolice.add(p, 30, "вернулся после незаконного выезда");
					U.say(s, U.t("🚨 Беглец " + p.getName().getString() + " вернулся на родину. Родина встретила его каретой.",
							Formatting.DARK_RED));
					Jail.arrest(p, "незаконное пересечение границы и возвращение беглеца", 180, true);
				} else if (nearCheckpoint(st, p.getPos(), 20) >= 0) {
					U.msg(p, U.t("🛂 С возвращением на родину! Таможенный досмотр…", Formatting.DARK_GREEN));
					if (customs(p, "Добро пожаловать домой.")) {
						U.msg(p, U.t("🛂 Въездной сбор: 10 бабла. Налоги — ждут.", Formatting.DARK_GREEN));
						Money.add(p, -10);
					}
				} else {
					Money.fine(p, 50, "въезд в королевство вне КПП");
					SecretPolice.add(p, 10, "въехал вне КПП");
				}
				continue;
			}
			if (o > -30) {
				int lw = lastWarn.getOrDefault(p.getUuid(), -999);
				if (now - lw >= 40) {
					lastWarn.put(p.getUuid(), now);
					U.bar(p, U.t("⚠ Государственная граница в " + (int) Math.ceil(-o) + " блоках. Без разрешения — тюрьма. "
							+ "Выезд — только через КПП (/vyezd).", Formatting.RED));
				}
			}
		}
	}

	private static void crossing(ServerPlayerEntity p, PizdecState st) {
		MinecraftServer s = p.getServer();
		double chance = SecretPolice.member(p) ? ESCAPE_UNDERGROUND : ESCAPE;
		if (U.chance(chance)) {
			st.abroad.put(p.getUuid(), -1L);
			st.markDirty();
			SecretPolice.note(s, p.getUuid(), "НЕЗАКОННО ПОКИНУЛ КОРОЛЕВСТВО");
			U.title(p, U.t("🌍 ТЫ СБЕЖАЛ", Formatting.AQUA, Formatting.BOLD),
					U.t("Шанс был " + (chance * 100) + "%. Назад — только в тюрьму.", Formatting.GRAY));
			U.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
			U.say(s, U.t("🚨 ЧП на границе! Гражданин " + p.getName().getString() + " незаконно покинул королевство. "
					+ "Объявлен в розыск. Пограничники наказаны (лишены премии).", Formatting.DARK_RED));
			News.breaking(s, "Пограничники отразили атаку на границу. Один гражданин случайно оказался по ту сторону.");
			return;
		}
		// поймали: возвращаем на 6 блоков внутрь
		Vec3d pos = p.getPos();
		double cx = bx(st) + 0.5, cz = bz(st) + 0.5;
		double x = Math.max(cx - R + 6, Math.min(cx + R - 6, pos.x));
		double z = Math.max(cz - R + 6, Math.min(cz + R - 6, pos.z));
		ServerWorld world = s.getOverworld();
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		p.stopRiding();
		p.teleport(world, x, y, z, p.getYaw(), p.getPitch());
		U.title(p, U.t("🛂 СТОЯТЬ! ГРАНИЦА!", Formatting.DARK_RED, Formatting.BOLD),
				U.t("Ефрейтор Бдительный и пёс Налог задержали нарушителя", Formatting.RED));
		U.sound(p, SoundEvents.ENTITY_WOLF_GROWL, 2f, 0.8f);
		SecretPolice.add(p, 40, "попытка незаконного пересечения границы");
		Jail.arrest(p, "незаконное пересечение государственной границы", 120);
	}

	/** Красная линия из частиц и пограничные столбы рядом с игроком. */
	private static void borderVisuals(ServerWorld world, PizdecState st, ServerPlayerEntity p) {
		double cx = bx(st) + 0.5, cz = bz(st) + 0.5;
		DustParticleEffect dust = new DustParticleEffect(new Vector3f(1f, 0.1f, 0.1f), 1.6f);
		double px = p.getX(), pz = p.getZ(), py = p.getY() + 1;
		double[][] lines = {{cx - R, 0}, {cx + R, 0}, {cz - R, 1}, {cz + R, 1}};
		for (double[] ln : lines) {
			boolean xLine = ln[1] == 0;
			double dist = Math.abs((xLine ? px : pz) - ln[0]);
			if (dist > 48) continue;
			for (int i = -16; i <= 16; i += 2) {
				double ax = xLine ? ln[0] : Math.floor(px) + i, az = xLine ? Math.floor(pz) + i : ln[0];
				if (Math.abs(ax - cx) > R + 0.01 || Math.abs(az - cz) > R + 0.01) continue;
				world.spawnParticles(p, dust, true, ax, py, az, 2, 0, 0.6, 0, 0);
			}
			// столбы каждые 25 блоков
			double along = xLine ? pz : px;
			for (int j = -2; j <= 2; j++) {
				int m = (int) (Math.round(along / 25.0) * 25) + j * 25;
				int x = (int) Math.floor(xLine ? ln[0] : m), z = (int) Math.floor(xLine ? m : ln[0]);
				if (Math.abs(x - cx) > R + 1 || Math.abs(z - cz) > R + 1) continue;
				if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
				int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockState under = world.getBlockState(new BlockPos(x, y - 1, z));
				if (under.isOf(Blocks.RED_CONCRETE) || under.isOf(Blocks.WHITE_CONCRETE) || !under.getFluidState().isEmpty()) continue;
				for (int i = 0; i < 3; i++) {
					world.setBlockState(new BlockPos(x, y + i, z),
							(i % 2 == 0 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE).getDefaultState(), B.FLAGS);
				}
			}
		}
	}

	public static void onJoin(ServerPlayerEntity p) {
		PizdecState st = PizdecState.get(p.getServer());
		if (!st.borderBuilt || p.getServerWorld() != p.getServer().getOverworld()) return;
		if (outside(st, p.getPos()) > 0 && !st.abroad.containsKey(p.getUuid()) && returnedOnce.add(p.getUuid())) {
			Vec3d c = Castle.gateOutside(st);
			p.teleport(p.getServer().getOverworld(), c.x, c.y, c.z, 180f, 0f);
			U.msg(p, U.t("🛂 Ты оказался за границей королевства. Пограничники вежливо вернули тебя к замку. "
					+ "В следующий раз — тюрьма.", Formatting.YELLOW));
		}
	}

	// ================================================================== Duty Free и разговоры

	public static void talk(ServerPlayerEntity p, Entity e) {
		PizdecState st = PizdecState.get(p.getServer());
		var tags = e.getCommandTags();
		if (tags.contains(OFFICER_TAG)) {
			U.msg(p, U.t("🛂 Ефрейтор Бдительный: «Выезд — /vyezd. Нужно: паспорт, разрешение (МФЦ, окно №4), "
					+ "чистая совесть (по нашей базе) и отсутствие долгов. Пешком через поле — тюрьма. "
					+ "Последний, кто ушёл, ушёл с шансом 0,5%.»", Formatting.DARK_GREEN));
		} else if (tags.contains(CUSTOMS_TAG)) {
			U.msg(p, U.t("🧳 Таможенник Досмотров: «Запрещено: динамит, кристаллы Края, фальшивые алмазы, листовки, "
					+ "любой импорт. Разрешено: любовь к королю (беспошлинно).»", Formatting.BLUE));
		} else if (tags.contains(DUTYFREE_TAG)) {
			if (outside(st, p.getPos()) <= 0 || !abroad(p)) {
				U.msg(p, U.t("🛍 Duty Free: «Только для выехавших. Покажите штамп о выезде.»", Formatting.GOLD));
				return;
			}
			ShopGui.open(p, "🛍 Duty Free «Заграница»", 1);
		} else if (tags.contains(TOURIST_TAG)) {
			ShopGui.open(p, "🎒 " + e.getName().getString() + " шепчет: «Купить?»", 2);
		} else if (tags.contains(GUIDE_TAG)) {
			U.msg(p, U.t("🎤 Гид: «Уважаемые гости, не разговаривайте с местными. Местные, не разговаривайте с гостями.»",
					Formatting.YELLOW));
		}
	}

	/** Магазин импорта: mode 1 — Duty Free (дёшево), 2 — турист из-под полы (дороже, тайная полиция видит). */
	public static final class ShopGui extends Gui {
		private final int mode;

		private ShopGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p, int mode) {
			super(syncId, pinv, inv, p);
			this.mode = mode;
			redraw();
		}

		public static void open(ServerPlayerEntity p, String title, int mode) {
			Gui.open(p, title, (id, pinv, inv, pl) -> new ShopGui(id, pinv, inv, pl, mode));
		}

		private int price(int i) {
			return mode == 1 ? FOREIGN_PRICE[i] / 2 : FOREIGN_PRICE[i];
		}

		private void redraw() {
			fill(mode == 1 ? Items.YELLOW_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE);
			for (int i = 0; i < FOREIGN.length; i++) {
				ItemStack st = foreign(FOREIGN[i]);
				set(11 + i, icon(st, st.getName().getString() + " §7— §e" + price(i) + " бабла",
						"§7Подполье купит за " + FOREIGN_PRICE[i] * 5 / 2 + " (/podpolie prodat)",
						mode == 1 ? "§7На въезде таможня найдёт (80%)" : "§8Гид-«переводчик» всё видит",
						"§eЛКМ — купить"));
			}
			set(31, icon(Items.WRITTEN_BOOK, "§fЗачем?", "§7Импорт продаётся подполью в 2,5 раза дороже.",
					"§7Но это контрабанда: обыск, таможня, тюрьма."));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			int i = slot - 11;
			if (i < 0 || i >= FOREIGN.length) return;
			if (!Money.pay(player, price(i), "импортный товар")) return;
			player.getInventory().offerOrDrop(foreign(FOREIGN[i]));
			if (mode == 2) SecretPolice.add(player, 8, "покупал товары у иностранца");
			U.sound(player, SoundEvents.ENTITY_VILLAGER_TRADE, 1f, 1f);
		}
	}

	// ================================================================== туристы

	private static final String[] TOURISTS = {"Турист Ганс", "Туристка Мэри", "Турист Пьер", "Турист Хуан", "Туристка Сакура",
			"Турист Олаф", "Туристка Джулия"};
	private static final String[] CHATTER = {"О, какой большой король!", "Я хотеть бургер «Вкусно и Точка»!",
			"Почему все молчат? Это традиция?", "Где здесь свобода? В меню нет.", "Фотографировать можно? Нельзя? Почему?",
			"У нас дома нет налоговой каждые 5 минут…", "Этот человек в сером идёт за нами уже час.", "Карета! Как мило! Ой, она за кем-то.",
			"Скажите, а выборы у вас интересные?", "Я купить сувенир — портрет короля. Обязательно?"};

	private static final class Group {
		final List<VillagerEntity> members = new ArrayList<>();
		final List<Vec3d> path = new ArrayList<>();
		final Map<Integer, Integer> pauses = new HashMap<>();
		final Map<Integer, String> speeches = new HashMap<>();
		double s;
		int pause, nextPoint = 1;
		double total;
	}

	private static Group group;
	private static boolean spawningTourists;
	private static int tourTimer = 20 * 60 * 7;

	private static void tickTourists(MinecraftServer s, PizdecState st) {
		if (group != null) {
			moveGroup(s, st);
			return;
		}
		int period = Laws.is("tourist_year") ? 20 * 120 : 20 * 600;
		if (++tourTimer < period) return;
		tourTimer = 0;
		Vec3d c = Castle.courtyard(st);
		boolean someone = false;
		for (ServerPlayerEntity p : U.players(s)) {
			if (p.getServerWorld() == s.getOverworld() && p.squaredDistanceTo(c) < 160 * 160) someone = true;
		}
		if (someone) spawnTourists(s, st);
	}

	private static Vec3d rel(PizdecState st, double x, double z) {
		return new Vec3d(st.cx + x, st.cy, st.cz + z);
	}

	private static void spawnTourists(MinecraftServer s, PizdecState st) {
		ServerWorld world = s.getOverworld();
		Group g = new Group();
		double[][] pts = {{28.5, -14}, {28.5, -5.5}, {28.5, 10.5}, {40.5, 10.5}, {40.5, 48.5}, {28.5, 48.5}, {40.5, 48.5},
				{51.5, 49.0}, {40.5, 48.5}, {40.5, 10.5}, {28.5, 10.5}, {28.5, -5.5}, {28.5, -14}};
		for (double[] q : pts) g.path.add(rel(st, q[0], q[1]));
		g.pauses.put(2, 20 * 9);
		g.speeches.put(2, "Перед вами — статуя Его Величества Короля Пиздец III. Высота — 25 блоков, а любовь народа — 146%. "
				+ "Пожалуйста, поклонитесь (присядьте). Это не просьба.");
		g.pauses.put(5, 20 * 7);
		g.speeches.put(5, "Парламент королевства: 210 мест, 7 партий и одна верная победа. Самые честные выборы в мире — "
				+ "проверено самим королём.");
		g.pauses.put(7, 20 * 7);
		g.speeches.put(7, "Ресторан «Вкусно и Точка» — гордость королевской кухни. Шаурма Его Величества — обязательна к дегустации.");
		for (int i = 1; i < g.path.size(); i++) g.total += g.path.get(i).distanceTo(g.path.get(i - 1));
		int n = U.between(3, 5);
		List<String> names = new ArrayList<>(List.of(TOURISTS));
		java.util.Collections.shuffle(names, U.R);
		spawningTourists = true;
		for (int i = 0; i <= n; i++) {
			VillagerEntity v = EntityType.VILLAGER.create(world);
			if (v == null) continue;
			Vec3d p0 = g.path.get(0);
			v.refreshPositionAndAngles(p0.x, p0.y, p0.z - i, 0f, 0f);
			v.setAiDisabled(true);
			v.setInvulnerable(true);
			if (i == 0) {
				v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.CARTOGRAPHER).withLevel(5));
				v.setCustomName(U.t("🎤 Гид Людмила («Королевтурист»)", Formatting.YELLOW));
				v.addCommandTag(GUIDE_TAG);
			} else {
				v.setCustomName(U.t("🎒 " + names.get(i - 1), Formatting.AQUA));
				v.addCommandTag(TOURIST_TAG);
			}
			v.setCustomNameVisible(true);
			world.spawnEntity(v);
			g.members.add(v);
		}
		spawningTourists = false;
		group = g;
		U.say(s, U.t("🚌 К воротам замка прибыла группа иностранных туристов. Граждане! Не вступайте в контакт. "
				+ "Улыбайтесь. Тайная полиция рядом.", Formatting.YELLOW));
	}

	private static Vec3d along(Group g, double s) {
		double left = Math.max(0, s);
		for (int i = 1; i < g.path.size(); i++) {
			Vec3d a = g.path.get(i - 1), b = g.path.get(i);
			double d = a.distanceTo(b);
			if (left <= d) return a.add(b.subtract(a).multiply(d < 1e-6 ? 0 : left / d));
			left -= d;
		}
		return g.path.get(g.path.size() - 1);
	}

	private static double dist(Group g, int point) {
		double sum = 0;
		for (int i = 1; i <= point; i++) sum += g.path.get(i).distanceTo(g.path.get(i - 1));
		return sum;
	}

	private static void moveGroup(MinecraftServer s, PizdecState st) {
		Group g = group;
		g.members.removeIf(Entity::isRemoved);
		if (g.members.isEmpty()) {
			group = null;
			return;
		}
		if (g.pause > 0) {
			g.pause--;
		} else {
			g.s += 0.11;
			while (g.nextPoint < g.path.size() && g.s >= dist(g, g.nextPoint)) {
				Integer ps = g.pauses.get(g.nextPoint);
				if (ps != null) {
					g.s = dist(g, g.nextPoint);
					g.pause = ps;
					String speech = g.speeches.get(g.nextPoint);
					for (ServerPlayerEntity p : U.players(s)) {
						if (p.squaredDistanceTo(g.members.get(0)) < 20 * 20) {
							U.msg(p, U.t("🎤 Гид Людмила: «" + speech + "»", Formatting.YELLOW));
						}
					}
				}
				g.nextPoint++;
			}
		}
		if (g.s >= g.total) {
			for (VillagerEntity v : g.members) v.discard();
			group = null;
			U.say(s, U.t("🚌 Туристы уехали. Впечатления: «очень… интересно». Пограничники проверили их чемоданы трижды.",
					Formatting.GRAY));
			return;
		}
		for (int i = 0; i < g.members.size(); i++) {
			VillagerEntity v = g.members.get(i);
			double si = g.s - 1.7 * i;
			Vec3d a = along(g, si), b = along(g, si + 0.5);
			Vec3d dir = b.subtract(a);
			float yaw = dir.lengthSquared() < 1e-6 ? v.getYaw() : (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
			double side = (i % 2 == 0 ? 0.6 : -0.6) * (i == 0 ? 0 : 1);
			Vec3d n = dir.lengthSquared() < 1e-6 ? Vec3d.ZERO : new Vec3d(-dir.z, 0, dir.x).normalize().multiply(side);
			Vec3d pos = a.add(n);
			if (g.pause > 0 && i > 0) {
				// на остановке туристы смотрят на гида
				Vec3d gd = g.members.get(0).getPos().subtract(pos);
				yaw = (float) (Math.toDegrees(Math.atan2(gd.z, gd.x)) - 90.0);
			}
			v.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f);
			v.setHeadYaw(yaw);
			v.setBodyYaw(yaw);
		}
		if (s.getTicks() % 100 == 0 && g.members.size() > 1) {
			VillagerEntity t = g.members.get(1 + U.R.nextInt(g.members.size() - 1));
			for (ServerPlayerEntity p : U.players(s)) {
				if (p.squaredDistanceTo(t) < 10 * 10) {
					U.msg(p, U.t("💬 " + t.getName().getString().replace("🎒 ", "") + ": «" + U.pick(CHATTER) + "»", Formatting.AQUA));
				}
			}
		}
	}

	public static void onEntityLoad(Entity e) {
		if (spawningTourists) return;
		var tags = e.getCommandTags();
		if (tags.contains(TOURIST_TAG) || tags.contains(GUIDE_TAG)) {
			if (group == null || !group.members.contains(e)) e.discard();
		}
	}
}
