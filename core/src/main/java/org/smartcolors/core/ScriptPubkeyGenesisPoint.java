package org.smartcolors.core;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.VarInt;
import org.bitcoinj.script.Script;

import java.io.IOException;
import java.io.OutputStream;

public class ScriptPubkeyGenesisPoint extends GenesisPoint {
	public static final byte POINT_TYPE = 0x02;
	private Script scriptPubkey;

	public ScriptPubkeyGenesisPoint(NetworkParameters params, Script scriptPubkey) {
		this.params = params;
		this.scriptPubkey = scriptPubkey;
	}

	public ScriptPubkeyGenesisPoint() {
	}

	@Override
	public byte getType() {
		return POINT_TYPE;
	}

	@Override
	protected void parse() {
		int cursor = 1;
		VarInt varInt = new VarInt(payload, cursor);
		if (varInt.value < 0 || varInt.value > Integer.MAX_VALUE)
			throw new ProtocolException("script length");
		cursor += varInt.getOriginalSizeInBytes();
		byte[] scriptBytes = new byte[(int) varInt.value];
		System.arraycopy(payload, cursor, scriptBytes, 0, scriptBytes.length);
		scriptPubkey = new Script(scriptBytes);
		cursor += scriptBytes.length;
	}

	@Override
	public void bitcoinSerializeToStream(OutputStream stream) throws IOException {
		super.bitcoinSerializeToStream(stream);
		stream.write(new VarInt(scriptPubkey.getProgram().length).encode());
		stream.write(scriptPubkey.getProgram());
	}

	public Script getScriptPubkey() {
		return scriptPubkey;
	}

	@Override
	public int compareTo(GenesisPoint o) {
		if (!(o instanceof ScriptPubkeyGenesisPoint)) {
			if (getType() > o.getType())
				return 1;
			else if (getType() < o.getType())
				return -1;
			return 0;
		}
		ScriptPubkeyGenesisPoint p = (ScriptPubkeyGenesisPoint) o;
		byte[] bytes = scriptPubkey.getProgram();
		byte[] obytes = p.scriptPubkey.getProgram();
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] > obytes[i])
				return 1;
			else if (bytes[i] < obytes[i])
				return -1;
		}
		return 0;
	}
}