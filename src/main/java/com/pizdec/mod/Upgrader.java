package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/**
 * Апгрейдер как в CS2, только в Майнкрафте. Кладёшь предмет из руки, выбираешь цель подороже,
 * стрелка крутится по полоске: попала в зелёное — получаешь цель, в красное — теряешь всё.
 */
public final class Upgrader extends Gui {
	private static final Map<Item, Integer> VALUE = new HashMap<>();
	private static final List<Item> TARGETS = new ArrayList<>();

	private static void v(Item i, int value) {
		VALUE.put(i, value);
	}

	private static void target(Item i, int value) {
		VALUE.put(i, value);
		TARGETS.add(i);
	}

	static {
		v(Items.DIRT, 1);
		v(Items.COBBLESTONE, 1);
		v(Items.STONE, 1);
		v(Items.SAND, 1);
		v(Items.GRAVEL, 1);
		v(Items.ROTTEN_FLESH, 1);
		v(Items.OAK_LOG, 2);
		v(Items.BIRCH_LOG, 2);
		v(Items.SPRUCE_LOG, 2);
		v(Items.STRING, 2);
		v(Items.BONE, 2);
		v(Items.ARROW, 2);
		v(Items.WHEAT, 2);
		v(Items.REDSTONE, 3);
		v(Items.QUARTZ, 3);
		v(Items.COPPER_INGOT, 3);
		v(Items.BREAD, 3);
		v(Items.LAPIS_LAZULI, 4);
		v(Items.GUNPOWDER, 4);
		v(Items.COAL, 5);
		v(Items.COOKED_BEEF, 5);
		v(Items.RAW_IRON, 12);
		v(Items.RAW_GOLD, 20);
		v(Items.ENDER_PEARL, 20);
		v(Items.BLAZE_ROD, 25);
		v(Items.SLIME_BALL, 6);
		v(Items.IRON_PICKAXE, 45);
		v(Items.IRON_SWORD, 30);
		v(Items.IRON_HELMET, 75);
		v(Items.IRON_CHESTPLATE, 120);
		v(Items.IRON_LEGGINGS, 105);
		v(Items.IRON_BOOTS, 60);
		v(Items.BOW, 10);
		v(Items.SHIELD, 20);

		target(Items.IRON_INGOT, 15);
		target(Items.GOLD_INGOT, 25);
		target(Items.EMERALD, 60);
		target(Items.DIAMOND, 100);
		target(Items.GOLDEN_APPLE, 150);
		target(Items.DIAMOND_SWORD, 250);
		target(Items.END_CRYSTAL, 300);
		target(Items.DIAMOND_PICKAXE, 350);
		target(Items.SHULKER_BOX, 450);
		target(Items.WITHER_SKELETON_SKULL, 600);
		target(Items.TOTEM_OF_UNDYING, 700);
		target(Items.NETHERITE_INGOT, 800);
		target(Items.DIAMOND_BLOCK, 900);
		target(Items.NETHERITE_SWORD, 1100);
		target(Items.HEART_OF_THE_SEA, 1200);
		target(Items.ELYTRA, 1500);
		target(Items.NETHERITE_CHESTPLATE, 1600);
		target(Items.NETHER_STAR, 1800);
		target(Items.ENCHANTED_GOLDEN_APPLE, 2000);
		target(Items.BEACON, 3000);
		target(Items.NETHERITE_BLOCK, 7200);
		target(Items.DRAGON_EGG, 10000);
	}

	public static int valueOf(ItemStack st) {
		if (st.isEmpty() || isSpecial(st, "fake_diamond")) return 0;
		if (isSpecial(st, "free_card")) return 300;
		return VALUE.getOrDefault(st.getItem(), 1) * st.getCount();
	}

	/** Указ «Принудительный апгрейд»: предмет из руки апгрейдится сам, без спроса. */
	public static void autoUpgrade(ServerPlayerEntity p) {
		ItemStack hand = p.getMainHandStack();
		int value = valueOf(hand);
		if (hand.isEmpty() || value <= 0) {
			U.bar(p, U.t("⬆ В руке пусто — апгрейдить нечего. Повезло.", Formatting.GRAY));
			return;
		}
		Item target = null;
		for (Item i : TARGETS) {
			if (VALUE.get(i) > value) {
				target = i;
				break;
			}
		}
		if (target == null) {
			U.bar(p, U.t("⬆ Твой предмет и так топ. Король завидует.", Formatting.GRAY));
			return;
		}
		double chance = Math.max(0.01, Math.min(0.8, (double) value / VALUE.get(target) * 0.88));
		String was = hand.getName().getString();
		p.getInventory().setStack(p.getInventory().selectedSlot, ItemStack.EMPTY);
		if (U.R.nextDouble() < chance) {
			p.getInventory().offerOrDrop(new ItemStack(target));
			U.msg(p, U.t("⬆ Принудительный апгрейд: " + was + " → " + new ItemStack(target).getName().getString()
					+ " (шанс " + String.format("%.0f%%", chance * 100) + ")", Formatting.GREEN));
		} else {
			U.msg(p, U.t("⬆ Принудительный апгрейд: " + was + " сгорел (шанс был " + String.format("%.0f%%", chance * 100)
					+ ")", Formatting.RED));
		}
	}

	private ItemStack input = ItemStack.EMPTY;
	private int inputValue;
	private final List<Item> options = new ArrayList<>();
	private Item selected;
	private double chance;

	// анимация
	private boolean spinning;
	private boolean success;
	private int needle;
	private int stepsLeft;
	private int nextStepAt;
	private boolean[] green = new boolean[9];
	private int doneAt = -1;

	private Upgrader(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
		super(syncId, pinv, inv, p);
		loadInput();
	}

	public static void open(ServerPlayerEntity p) {
		if (Jail.jailed(p)) {
			U.msg(p, U.t("⛓ Апгрейдер в тюрьме — это заточка ложки. Не положено.", Formatting.GRAY));
			return;
		}
		Gui.open(p, "⬆ Апгрейдер", Upgrader::new);
	}

	private void loadInput() {
		input = player.getMainHandStack().copy();
		inputValue = valueOf(input);
		options.clear();
		for (Item i : TARGETS) {
			if (VALUE.get(i) > inputValue && options.size() < 18) options.add(i);
		}
		selected = options.isEmpty() ? null : options.get(0);
		recompute();
		redraw();
	}

	private void recompute() {
		if (selected == null || inputValue <= 0) {
			chance = 0;
		} else {
			chance = Math.min(0.8, (double) inputValue / VALUE.get(selected) * 0.88);
			if (Laws.is("upgrader_luck")) chance = Math.min(0.95, chance + 0.2);
			chance = Math.max(0.01, chance);
		}
		int g = Math.max(1, (int) Math.round(chance * 9));
		int start = U.R.nextInt(9);
		green = new boolean[9];
		for (int i = 0; i < g; i++) green[(start + i) % 9] = true;
	}

	private String pct() {
		return String.format("%.1f%%", chance * 100);
	}

	private void redraw() {
		fill(Items.GRAY_STAINED_GLASS_PANE);
		for (int i = 0; i < 9; i++) {
			set(i, icon(i == needle && (spinning || doneAt >= 0) ? Items.END_ROD : Items.BLACK_STAINED_GLASS_PANE,
					i == needle ? "§f▼" : " "));
			set(9 + i, icon(green[i] ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE,
					green[i] ? "§aАПГРЕЙД" : "§cМИМО"));
		}
		if (input.isEmpty()) {
			set(19, icon(Items.BARRIER, "§cВозьми предмет в руку", "и открой апгрейдер заново"));
		} else if (inputValue == 0) {
			set(19, icon(input.copy(), "§cЭто подделка!", "Апгрейдер не принимает фальшивки."));
		} else {
			set(19, icon(input.copy(), "§fТвой предмет: " + input.getName().getString() + " ×" + input.getCount(),
					"§7Ценность: " + inputValue));
		}
		set(22, icon(Items.COMPASS, "§eШанс: " + pct(), "§7Чем дороже цель — тем меньше шанс"));
		if (selected != null) {
			set(25, icon(new ItemStack(selected), "§bЦель: " + new ItemStack(selected).getName().getString(),
					"§7Ценность: " + VALUE.get(selected)));
		} else {
			set(25, icon(Items.NETHER_STAR, "§7Нет целей дороже", "Твой предмет и так топ"));
		}
		for (int i = 0; i < 18; i++) {
			int slot = 27 + i;
			if (i < options.size()) {
				Item it = options.get(i);
				double c = Math.min(0.8, (double) inputValue / VALUE.get(it) * 0.88);
				boolean sel = it == selected;
				set(slot, icon(new ItemStack(it), (sel ? "§a▶ " : "§f") + new ItemStack(it).getName().getString(),
						"§7Ценность: " + VALUE.get(it), "§7Шанс: " + String.format("%.1f%%", Math.max(0.01, c) * 100),
						"§7Нажми, чтобы выбрать"));
			}
		}
		set(49, spinning ? icon(Items.CLOCK, "§eКрутится…")
				: icon(Items.EMERALD_BLOCK, "§a§lАПГРЕЙД!", "§7Предмет из руки сгорит при неудаче", "§7Шанс: " + pct()));
		set(45, icon(Items.HOPPER, "§7Обновить", "§7Взять новый предмет из руки"));
		set(53, icon(Items.BARRIER, "§cЗакрыть"));
	}

	@Override
	protected void click(int slot, int button) {
		if (slot == 53) {
			close();
			return;
		}
		if (spinning) return;
		if (slot == 45) {
			doneAt = -1;
			loadInput();
			return;
		}
		if (slot >= 27 && slot < 45) {
			int i = slot - 27;
			if (i < options.size()) {
				selected = options.get(i);
				doneAt = -1;
				recompute();
				redraw();
				U.sound(player, SoundEvents.UI_BUTTON_CLICK, 0.5f, 1.5f);
			}
			return;
		}
		if (slot == 49) start();
	}

	private void start() {
		if (selected == null || input.isEmpty() || inputValue <= 0) {
			U.bar(player, U.t("Нечего апгрейдить", Formatting.RED));
			return;
		}
		if (Laws.is("gambling_ban")) {
			close();
			Jail.violation(player, "азартные игры (апгрейдер)");
			return;
		}
		ItemStack hand = player.getMainHandStack();
		if (!ItemStack.areEqual(hand, input)) {
			close();
			Jail.arrest(player, "мошенничество в апгрейдере (подменил предмет)", 30);
			return;
		}
		// предмет уходит в апгрейдер
		player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
		success = U.R.nextDouble() < chance;
		List<Integer> pool = new ArrayList<>();
		for (int i = 0; i < 9; i++) if (green[i] == success) pool.add(i);
		int target = pool.isEmpty() ? 0 : U.pick(pool);
		needle = 0;
		stepsLeft = 27 + target; // три полных круга и на место
		nextStepAt = age + 1;
		spinning = true;
		doneAt = -1;
		redraw();
	}

	@Override
	protected void tick() {
		if (!spinning) return;
		if (age < nextStepAt) return;
		if (stepsLeft > 0) {
			needle = (needle + 1) % 9;
			stepsLeft--;
			// замедление в конце
			nextStepAt = age + (stepsLeft > 12 ? 1 : stepsLeft > 6 ? 2 : stepsLeft > 2 ? 4 : 7);
			U.sound(player, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f + (27 - Math.min(27, stepsLeft)) / 27f);
			redraw();
			return;
		}
		resolve();
	}

	@Override
	public void onClosed(net.minecraft.entity.player.PlayerEntity pl) {
		super.onClosed(pl);
		if (spinning) resolve(); // закрыл меню во время прокрутки — результат всё равно выдаём
	}

	private void resolve() {
		spinning = false;
		doneAt = age;
		String target = new ItemStack(selected).getName().getString();
		if (success) {
			player.getInventory().offerOrDrop(new ItemStack(selected));
			U.title(player, U.t("⬆ АПГРЕЙД!", Formatting.GREEN, Formatting.BOLD), U.t("Ты получил: " + target, Formatting.AQUA));
			U.sound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
			if (chance < 0.2) {
				U.say(player.getServer(), U.t("⬆ " + player.getName().getString() + " апгрейднул " + input.getName().getString()
						+ " в " + target + " с шансом " + pct() + "!", Formatting.AQUA));
			}
		} else {
			U.title(player, U.t("✖ МИМО", Formatting.RED, Formatting.BOLD),
					U.t(input.getName().getString() + " сгорел. Король доволен.", Formatting.GRAY));
			U.sound(player, SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.6f);
		}
		input = ItemStack.EMPTY;
		inputValue = 0;
		chance = 0;
		redraw();
	}
}
