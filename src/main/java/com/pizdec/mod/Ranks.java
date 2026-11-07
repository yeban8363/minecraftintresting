package com.pizdec.mod;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Звания. До Дворянина — покупаются. Барон, Граф, Герцог и Принц — только милостью короля (шанс крошечный).
 * Звание видно над головой, в табе и в чате.
 */
public final class Ranks {
	public static final String[] NAMES = {"Простолюдин", "Крестьянин", "Горожанин", "Купец", "Рыцарь", "Дворянин",
			"Барон", "Граф", "Герцог", "Принц"};
	private static final Formatting[] COLORS = {Formatting.GRAY, Formatting.GREEN, Formatting.AQUA, Formatting.YELLOW,
			Formatting.BLUE, Formatting.GOLD, Formatting.LIGHT_PURPLE, Formatting.DARK_PURPLE, Formatting.RED,
			Formatting.DARK_RED};
	private static final Item[] ICONS = {Items.WHEAT_SEEDS, Items.WHEAT, Items.BRICK, Items.EMERALD, Items.IRON_SWORD,
			Items.GOLDEN_HELMET, Items.GOLD_BLOCK, Items.DIAMOND, Items.NETHER_STAR, Items.DRAGON_HEAD};
	/** Цена покупки звания. 0 — не продаётся. */
	public static final int[] PRICE = {0, 100, 300, 800, 2000, 5000, 0, 0, 0, 0};
	public static final int NOBLE = 5;
	public static final int MAX = NAMES.length - 1;

	private Ranks() {}

	public static int get(ServerPlayerEntity p) {
		return PizdecState.get(p.getServer()).ranks.getOrDefault(p.getUuid(), 0);
	}

	public static String name(ServerPlayerEntity p) {
		return NAMES[get(p)];
	}

	public static String name(int r) {
		return NAMES[Math.max(0, Math.min(MAX, r))];
	}

	public static boolean noble(ServerPlayerEntity p) {
		return get(p) >= NOBLE;
	}

	public static boolean atLeast(ServerPlayerEntity p, int r) {
		return get(p) >= r;
	}

	public static void set(ServerPlayerEntity p, int r) {
		r = Math.max(0, Math.min(MAX, r));
		PizdecState st = PizdecState.get(p.getServer());
		st.ranks.put(p.getUuid(), r);
		st.markDirty();
		applyTeam(p);
	}

	/** Скидка на налоги: чем выше звание, тем меньше берут. */
	public static double taxDiscount(ServerPlayerEntity p) {
		return Math.min(0.6, get(p) * 0.06);
	}

	/** Скидка в магазине: купцам и выше. */
	public static double shopDiscount(ServerPlayerEntity p) {
		int r = get(p);
		return r >= NOBLE ? 0.2 : r >= 3 ? 0.1 : 0;
	}

	public static void applyTeam(ServerPlayerEntity p) {
		int r = get(p);
		ServerScoreboard sb = p.getServer().getScoreboard();
		String tn = "pz_rank_" + r;
		Team t = sb.getTeam(tn);
		if (t == null) {
			t = sb.addTeam(tn);
			t.setPrefix(Text.literal("[" + NAMES[r] + "] ").formatted(COLORS[r]));
			t.setColor(r >= NOBLE ? COLORS[r] : Formatting.WHITE);
		}
		sb.addScoreHolderToTeam(p.getNameForScoreboard(), t);
	}

	/** Король может пожаловать звание выше дворянского. */
	public static boolean tryKingPromotion(ServerPlayerEntity p, double chance, String why) {
		int r = get(p);
		if (r < NOBLE || r >= MAX || U.R.nextDouble() >= chance) return false;
		set(p, r + 1);
		U.say(p.getServer(), U.t("👑 Король пожаловал " + p.getName().getString() + " титул «" + NAMES[r + 1] + "» (" + why
				+ ")! Завидуйте молча.", Formatting.GOLD, Formatting.BOLD));
		U.title(p, U.t("👑 " + NAMES[r + 1].toUpperCase(), COLORS[r + 1], Formatting.BOLD),
				U.t("Милостью Его Величества", Formatting.GOLD));
		U.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
		return true;
	}

	/** Каждый новый указ — крошечный шанс, что король кого-то возвысит. */
	public static void onDecree(MinecraftServer s) {
		for (ServerPlayerEntity p : U.free(s)) tryKingPromotion(p, 0.004, "просто так, у короля хорошее настроение");
	}

	// ------------------------------------------------------------------ магазин званий

	public static final class ShopGui extends Gui {
		private ShopGui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p) {
			super(syncId, pinv, inv, p);
			redraw();
		}

		public static void open(ServerPlayerEntity p) {
			Gui.open(p, "👑 Магазин званий", ShopGui::new);
		}

		private void redraw() {
			fill(Items.PURPLE_STAINED_GLASS_PANE);
			int cur = Ranks.get(player);
			set(4, icon(ICONS[cur], "§fТвоё звание: " + NAMES[cur], "§7Налог −" + (int) (Ranks.taxDiscount(player) * 100) + "%",
					"§7Скидка в магазине −" + (int) (Ranks.shopDiscount(player) * 100) + "%"));
			int[] slots = {19, 20, 21, 22, 23, 24, 25, 30, 31, 32};
			for (int r = 1; r <= MAX; r++) {
				int slot = slots[r - 1];
				String state;
				if (cur >= r) state = "§aУже есть";
				else if (PRICE[r] == 0) state = "§dТолько милостью короля (шанс очень мал)";
				else if (cur == r - 1) state = "§eЛКМ — купить за " + PRICE[r] + " бабла";
				else state = "§cСначала купи «" + NAMES[r - 1] + "»";
				set(slot, icon(ICONS[r], (cur >= r ? "§a" : "§f") + NAMES[r], state, perks(r)));
			}
			set(49, icon(Items.GOLD_NUGGET, "§6Твоё бабло: " + Money.get(player)));
			set(53, icon(Items.BARRIER, "§cЗакрыть"));
		}

		private static String perks(int r) {
			return switch (r) {
				case 1 -> "§7Можно работать фермером и шахтёром";
				case 2 -> "§7Охотник и курьер. Налог −12%";
				case 3 -> "§7Помощник налоговой, скидка в магазине 10%";
				case 4 -> "§7Можно служить стражником";
				case 5 -> "§7Броня и высота выше 90 разрешены всегда, банкир";
				default -> "§7Суд к тебе очень снисходителен";
			};
		}

		@Override
		protected void click(int slot, int button) {
			if (slot == 53) {
				close();
				return;
			}
			int[] slots = {19, 20, 21, 22, 23, 24, 25, 30, 31, 32};
			for (int r = 1; r <= MAX; r++) {
				if (slots[r - 1] != slot) continue;
				int cur = Ranks.get(player);
				if (cur >= r || PRICE[r] == 0 || cur != r - 1) return;
				if (r >= 3 && !Docs.require(player, "присвоения звания «" + NAMES[r] + "»")) return;
				if (Money.pay(player, PRICE[r], "звание «" + NAMES[r] + "»")) {
					Ranks.set(player, r);
					U.say(player.getServer(), U.t("👑 " + player.getName().getString() + " купил звание «" + NAMES[r]
							+ "». Деньги не пахнут.", Formatting.GOLD));
					U.sound(player, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
				}
				redraw();
				return;
			}
		}

		@Override
		protected void tick() {
			if (age % 20 == 0) redraw();
		}
	}
}
