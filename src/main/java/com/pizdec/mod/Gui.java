package com.pizdec.mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Серверное «меню» в виде сундука на 6 рядов. Работает без мода на клиенте:
 * все клики перехватываются, предметы из меню забрать нельзя.
 */
public abstract class Gui extends GenericContainerScreenHandler {
	public static final int ROWS = 6;
	public static final int SIZE = ROWS * 9;
	private static final Set<Gui> OPEN = new HashSet<>();

	protected final SimpleInventory inv;
	protected final ServerPlayerEntity player;
	protected int age;

	protected Gui(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity player) {
		super(ScreenHandlerType.GENERIC_9X6, syncId, pinv, inv, ROWS);
		this.inv = inv;
		this.player = player;
		OPEN.add(this);
	}

	public interface Maker {
		Gui make(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity p);
	}

	public static void open(ServerPlayerEntity p, String title, Maker maker) {
		p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, pinv, pl) -> maker.make(syncId, pinv, new SimpleInventory(SIZE), p),
				Text.literal(title)));
	}

	/** Клик по слоту меню (0..53). button: 0 — ЛКМ, 1 — ПКМ. */
	protected abstract void click(int slot, int button);

	/** Вызывается каждый тик, пока меню открыто. */
	protected void tick() {}

	@Override
	public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity pl) {
		if (slot >= 0 && slot < SIZE && action == SlotActionType.PICKUP) {
			try {
				click(slot, button);
			} catch (Exception e) {
				PizdecMod.LOG.error("Ошибка в меню", e);
			}
		}
		syncState(); // откатываем всё, что клиент успел «нарисовать»
	}

	@Override
	public ItemStack quickMove(PlayerEntity pl, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canUse(PlayerEntity pl) {
		return pl.isAlive();
	}

	@Override
	public void onClosed(PlayerEntity pl) {
		super.onClosed(pl);
		OPEN.remove(this);
	}

	public static void tickAll(MinecraftServer s) {
		for (Gui g : new ArrayList<>(OPEN)) {
			if (g.player.isRemoved() || g.player.currentScreenHandler != g) {
				OPEN.remove(g);
				continue;
			}
			g.age++;
			try {
				g.tick();
			} catch (Exception e) {
				PizdecMod.LOG.error("Ошибка в меню", e);
			}
		}
	}

	protected void close() {
		player.closeHandledScreen();
	}

	protected void set(int slot, ItemStack st) {
		inv.setStack(slot, st);
	}

	protected void fill(Item pane) {
		for (int i = 0; i < SIZE; i++) set(i, icon(pane, " "));
	}

	// ------------------------------------------------------------------ предметы с названиями

	public static ItemStack icon(Item item, String name, String... lore) {
		return icon(new ItemStack(item), name, lore);
	}

	public static ItemStack icon(ItemStack st, String name, String... lore) {
		st.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(s -> s.withItalic(false)));
		if (lore.length > 0) {
			List<Text> l = new ArrayList<>();
			for (String line : lore) l.add(Text.literal(line).formatted(Formatting.GRAY).styled(s -> s.withItalic(false)));
			st.set(DataComponentTypes.LORE, new LoreComponent(l));
		}
		return st;
	}

	/** Особый предмет мода (справка, фальшивый алмаз…). */
	public static ItemStack special(Item item, String tag, String name, String... lore) {
		ItemStack st = icon(item, name, lore);
		NbtCompound nbt = new NbtCompound();
		nbt.putString("pizdec", tag);
		st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
		return st;
	}

	public static boolean isSpecial(ItemStack st, String tag) {
		if (st.isEmpty()) return false;
		NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
		return c != null && tag.equals(c.copyNbt().getString("pizdec"));
	}

	public static ItemStack freeCard() {
		return special(Items.PAPER, "free_card", "§aСправка об освобождении",
				"Одноразовая. Отменяет следующий арест.", "Подписано: Король Пиздец III");
	}

	public static ItemStack fakeCard() {
		return special(Items.PAPER, "fake_card", "§eСправка об освобождении (почти настоящая)",
				"Сработает в 50% случаев.", "В остальных — срок ×2 за подделку.");
	}

	public static ItemStack fakeDiamond() {
		return special(Items.DIAMOND, "fake_diamond", "§bАлмаз",
				"Самый настоящий. Честно.", "Не продавать налоговой.");
	}

	public static ItemStack petRock() {
		return special(Items.COBBLESTONE, "pet_rock", "§7Камень Валера", "Твой домашний камень.", "Потеряешь — сядешь.");
	}

	public static ItemStack hotPotato() {
		return special(Items.BAKED_POTATO, "hot_potato", "§cГорячая картошка",
				"У кого она в конце указа — тот в тюрьме.", "Выбрось (Q) или подкинь другу.");
	}
}
