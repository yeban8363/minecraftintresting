package com.pizdec.mod;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

/** Королевское казино «Три Топора». Слоты, монетка, рулетка. Казино всегда выигрывает. */
public final class Casino extends Gui {
	private static final int[] BETS = {10, 50, 100, 500, 1000};
	private static final int[] BET_SLOTS = {28, 29, 30, 31, 32};

	// символы слотов: предмет, вес, множитель за три в ряд
	private static final Item[] SYM = {Items.ROTTEN_FLESH, Items.APPLE, Items.GOLD_INGOT, Items.EMERALD, Items.DIAMOND,
			Items.NETHER_STAR};
	private static final String[] SYM_NAME = {"Тухлятина", "Яблоко", "Золото", "Изумруд", "Алмаз", "Звезда Незера"};
	private static final int[] WEIGHT = {30, 25, 20, 13, 8, 4};
	private static final int[] TRIPLE = {0, 5, 8, 15, 30, 100};

	private static final int REEL_A = 11, REEL_B = 13, REEL_C = 15;

	private int bet = 10;
	private String game; // null — ничего не крутится
	private int t;
	private int[] reels = new int[3];
	private int rouletteNumber;
	private String rouletteBet;
	private boolean coinWin;

	private Casino(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
		super(syncId, pinv, inv, p);
		redraw();
		for (int i = 0; i < 3; i++) set(new int[] {REEL_A, REEL_B, REEL_C}[i], icon(Items.BLACK_STAINED_GLASS_PANE, "§8?"));
	}

	public static void open(ServerPlayerEntity p) {
		if (Jail.jailed(p)) {
			U.msg(p, U.t("⛓ В тюрьме играют только в «дурака», и то на сигареты.", Formatting.GRAY));
			return;
		}
		Gui.open(p, "🎰 Казино «Три Топора»", Casino::new);
	}

	private static int roll() {
		int r = U.R.nextInt(100);
		for (int i = 0; i < WEIGHT.length; i++) {
			r -= WEIGHT[i];
			if (r < 0) return i;
		}
		return 0;
	}

	private static ItemStack sym(int i) {
		return icon(SYM[i], "§f" + SYM_NAME[i]);
	}

	private void redraw() {
		for (int i = 0; i < SIZE; i++) {
			if (i == REEL_A || i == REEL_B || i == REEL_C) continue;
			set(i, icon(i < 27 ? Items.RED_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE, " "));
		}
		set(4, icon(Items.GOLD_BLOCK, "§6§lКАЗИНО «ТРИ ТОПОРА»", "Казино принадлежит королю.",
				"С выигрыша больше 100 — налог 10%."));
		for (int i = 0; i < BETS.length; i++) {
			boolean sel = bet == BETS[i];
			set(BET_SLOTS[i], icon(sel ? Items.GOLD_BLOCK : Items.GOLD_NUGGET, (sel ? "§a▶ " : "§e") + "Ставка " + BETS[i],
					"§7Нажми, чтобы выбрать"));
		}
		set(34, icon(Items.SUNFLOWER, "§6Текущая ставка: " + bet));
		set(37, icon(Items.CLOCK, "§d🎰 Крутить слоты", "Три одинаковых: яблоко ×5, золото ×8,", "изумруд ×15, алмаз ×30, звезда ×100",
				"Два одинаковых (не тухлятина): ×2"));
		set(39, icon(Items.GOLD_NUGGET, "§e🪙 Монетка ×2", "Шанс 48%. Остальные 2% — королю."));
		set(41, icon(Items.RED_WOOL, "§cРулетка: КРАСНОЕ ×2", "18 из 37"));
		set(42, icon(Items.BLACK_WOOL, "§8Рулетка: ЧЁРНОЕ ×2", "18 из 37"));
		set(43, icon(Items.LIME_WOOL, "§aРулетка: ЗЕРО ×14", "1 из 37"));
		set(49, icon(Items.EMERALD, "§aТвоё бабло: " + Money.get(player)));
		set(53, icon(Items.BARRIER, "§cУйти (пока есть штаны)"));
		if (Laws.is("casino_x2")) set(45, icon(Items.NETHER_STAR, "§a§lСЧАСТЛИВЫЙ ЧАС: выигрыши ×2"));
	}

	@Override
	protected void click(int slot, int button) {
		if (slot == 53) {
			close();
			return;
		}
		if (game != null) return; // ждём, пока докрутится
		for (int i = 0; i < BETS.length; i++) {
			if (slot == BET_SLOTS[i]) {
				bet = BETS[i];
				redraw();
				U.sound(player, SoundEvents.UI_BUTTON_CLICK, 0.5f, 1.5f);
				return;
			}
		}
		String g = switch (slot) {
			case 37 -> "slots";
			case 39 -> "coin";
			case 41 -> "red";
			case 42 -> "black";
			case 43 -> "green";
			default -> null;
		};
		if (g == null) return;
		if (Laws.is("gambling_ban")) {
			close();
			Jail.violation(player, "азартные игры");
			return;
		}
		if (Laws.is("casino_closed")) {
			U.bar(player, U.t("🎰 Казино опечатано налоговой. Указ короля.", Formatting.RED));
			return;
		}
		if (!Money.pay(player, bet, "ставку")) return;
		game = g;
		t = 0;
		if (g.equals("slots")) {
			reels = new int[] {roll(), roll(), roll()};
		} else if (g.equals("coin")) {
			coinWin = U.R.nextInt(100) < 48;
		} else {
			rouletteBet = g;
			rouletteNumber = U.R.nextInt(37);
		}
		redraw();
	}

	@Override
	protected void tick() {
		if (game == null) {
			if (age % 20 == 0) set(49, icon(Items.EMERALD, "§aТвоё бабло: " + Money.get(player)));
			return;
		}
		t++;
		switch (game) {
			case "slots" -> tickSlots();
			case "coin" -> tickCoin();
			default -> tickRoulette();
		}
	}

	@Override
	public void onClosed(net.minecraft.entity.player.PlayerEntity pl) {
		super.onClosed(pl);
		// ушёл посреди игры — докручиваем без него, ставка не сгорает просто так
		int guard = 0;
		while (game != null && guard++ < 200) {
			t++;
			switch (game) {
				case "slots" -> tickSlots();
				case "coin" -> tickCoin();
				default -> tickRoulette();
			}
		}
	}

	private void tickSlots() {
		int[] slots = {REEL_A, REEL_B, REEL_C};
		int[] stopAt = {20, 30, 40};
		for (int i = 0; i < 3; i++) {
			if (t < stopAt[i]) {
				if (t % 2 == 0) set(slots[i], sym(U.R.nextInt(SYM.length)));
			} else if (t == stopAt[i]) {
				set(slots[i], sym(reels[i]));
				U.sound(player, SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 1f);
			}
		}
		if (t % 2 == 0 && t < 40) U.sound(player, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.6f);
		if (t == 42) {
			int a = reels[0], b = reels[1], c = reels[2];
			int mult = 0;
			if (a == b && b == c) mult = TRIPLE[a];
			else if ((a == b && a != 0) || (b == c && b != 0) || (a == c && a != 0)) mult = 2;
			finish(bet * mult, mult >= 30 ? "ДЖЕКПОТ!" : null);
		}
	}

	private void tickCoin() {
		if (t < 30) {
			if (t % 3 == 0) {
				boolean f = (t / 3) % 2 == 0;
				set(REEL_B, icon(f ? Items.GOLD_NUGGET : Items.IRON_NUGGET, f ? "§6Орёл" : "§7Решка"));
				U.sound(player, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 0.5f, 2f);
			}
			return;
		}
		if (t == 30) {
			set(REEL_B, icon(coinWin ? Items.GOLD_NUGGET : Items.IRON_NUGGET, coinWin ? "§6Орёл — твой!" : "§7Решка — королю"));
			finish(coinWin ? bet * 2 : 0, null);
		}
	}

	private static boolean isRed(int n) {
		return n != 0 && n % 2 == 1;
	}

	private void tickRoulette() {
		// замедляющееся вращение
		int[] steps = {1, 2, 3, 4, 5, 7, 9, 11, 14, 17, 21, 25, 30, 36, 43, 51};
		for (int s : steps) {
			if (t == s) {
				int n = s == 51 ? rouletteNumber : U.R.nextInt(37);
				Item w = n == 0 ? Items.LIME_WOOL : isRed(n) ? Items.RED_WOOL : Items.BLACK_WOOL;
				set(REEL_B, icon(w, "§f" + n));
				set(REEL_A, icon(Items.BLACK_STAINED_GLASS_PANE, " "));
				set(REEL_C, icon(Items.BLACK_STAINED_GLASS_PANE, " "));
				U.sound(player, SoundEvents.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f);
			}
		}
		if (t == 53) {
			int n = rouletteNumber;
			boolean win = switch (rouletteBet) {
				case "red" -> isRed(n);
				case "black" -> n != 0 && !isRed(n);
				default -> n == 0;
			};
			int mult = rouletteBet.equals("green") ? 14 : 2;
			finish(win ? bet * mult : 0, win && mult == 14 ? "ЗЕРО!!!" : null);
		}
	}

	private void finish(int win, String big) {
		game = null;
		if (Laws.is("casino_x2")) win *= 2;
		if (Laws.is("jackpot_day")) win *= 3;
		if (win > 0) {
			int tax = win > 100 ? win / 10 : 0;
			Money.add(player, win - tax);
			U.msg(player, U.t("🎰 Выигрыш: " + win + (tax > 0 ? " (−" + tax + " налог королю)" : "") + ". Баланс: "
					+ Money.get(player), Formatting.GREEN));
			U.sound(player, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
			if (big != null) {
				U.title(player, U.t("🎰 " + big, Formatting.GOLD, Formatting.BOLD), U.t("+" + win + " бабла", Formatting.YELLOW));
				U.say(player.getServer(), U.t("🎰 " + player.getName().getString() + " сорвал " + big + " в казино: +" + win,
						Formatting.GOLD));
			}
		} else {
			U.bar(player, U.t("🎰 Мимо. Казино благодарит за пожертвование.", Formatting.RED));
			U.sound(player, SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.8f);
		}
		redraw();
	}
}
