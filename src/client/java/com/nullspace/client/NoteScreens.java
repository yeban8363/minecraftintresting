package com.nullspace.client;

import com.nullspace.item.NoteItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Открывает записку как книгу. Ник читающего и дата «из будущего» подставляются в текст. */
public final class NoteScreens {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	public static void open(ItemStack stack, PlayerEntity player) {
		int id = NoteItem.id(stack);
		String name = player.getGameProfile().getName();
		String date = LocalDate.now().plusDays(3L + id * 11L).format(DATE);

		List<Text> pages = new ArrayList<>();
		for (int p = 1; p <= NoteItem.PAGES[id]; p++) {
			pages.add(Text.translatable("note.nullspace." + id + ".p" + p, name, date));
		}

		BookScreen.Contents contents = new BookScreen.Contents(pages);
		MinecraftClient.getInstance().setScreen(new BookScreen(contents));
	}

	private NoteScreens() {
	}
}
