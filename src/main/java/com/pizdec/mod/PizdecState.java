package com.pizdec.mod;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** То, что должно пережить перезапуск: где стоит замок и сколько указов уже издано. */
public class PizdecState extends PersistentState {
	public boolean castleBuilt;
	public int cx, cy, cz;
	public int decrees;
	public int taxRaids;

	private static final Type<PizdecState> TYPE = new Type<>(PizdecState::new, PizdecState::fromNbt, null);

	public static PizdecState get(MinecraftServer s) {
		return s.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "pizdec");
	}

	private static PizdecState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		PizdecState st = new PizdecState();
		st.castleBuilt = nbt.getBoolean("castleBuilt");
		st.cx = nbt.getInt("cx");
		st.cy = nbt.getInt("cy");
		st.cz = nbt.getInt("cz");
		st.decrees = nbt.getInt("decrees");
		st.taxRaids = nbt.getInt("taxRaids");
		return st;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		nbt.putBoolean("castleBuilt", castleBuilt);
		nbt.putInt("cx", cx);
		nbt.putInt("cy", cy);
		nbt.putInt("cz", cz);
		nbt.putInt("decrees", decrees);
		nbt.putInt("taxRaids", taxRaids);
		return nbt;
	}
}
