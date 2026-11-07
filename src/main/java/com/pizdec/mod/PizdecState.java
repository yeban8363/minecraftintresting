package com.pizdec.mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** То, что должно пережить перезапуск: замок, тюрьма, указы и все, кто сидит. */
public class PizdecState extends PersistentState {
	public boolean castleBuilt;
	public int cx, cy, cz;
	public boolean prisonBuilt;
	public int px, py, pz;
	public int decrees;
	public int taxRaids;
	public int arrests;

	/** Заключённые: UUID → запись. */
	public final Map<UUID, Jail.Record> jail = new HashMap<>();

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
		st.prisonBuilt = nbt.getBoolean("prisonBuilt");
		st.px = nbt.getInt("px");
		st.py = nbt.getInt("py");
		st.pz = nbt.getInt("pz");
		st.decrees = nbt.getInt("decrees");
		st.taxRaids = nbt.getInt("taxRaids");
		st.arrests = nbt.getInt("arrests");
		NbtCompound j = nbt.getCompound("jail");
		for (String key : j.getKeys()) {
			try {
				NbtCompound r = j.getCompound(key);
				st.jail.put(UUID.fromString(key), new Jail.Record(r.getInt("t"), r.getString("crime"), r.getInt("cell")));
			} catch (IllegalArgumentException ignored) {
				// битая запись — считаем, что сбежал
			}
		}
		return st;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		nbt.putBoolean("castleBuilt", castleBuilt);
		nbt.putInt("cx", cx);
		nbt.putInt("cy", cy);
		nbt.putInt("cz", cz);
		nbt.putBoolean("prisonBuilt", prisonBuilt);
		nbt.putInt("px", px);
		nbt.putInt("py", py);
		nbt.putInt("pz", pz);
		nbt.putInt("decrees", decrees);
		nbt.putInt("taxRaids", taxRaids);
		nbt.putInt("arrests", arrests);
		NbtCompound j = new NbtCompound();
		for (Map.Entry<UUID, Jail.Record> e : jail.entrySet()) {
			NbtCompound r = new NbtCompound();
			r.putInt("t", e.getValue().ticks);
			r.putString("crime", e.getValue().crime);
			r.putInt("cell", e.getValue().cell);
			j.put(e.getKey().toString(), r);
		}
		nbt.put("jail", j);
		return nbt;
	}
}
