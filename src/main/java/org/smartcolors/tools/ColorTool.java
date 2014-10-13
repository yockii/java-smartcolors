package org.smartcolors.tools;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.DownloadListener;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.store.WalletProtobufSerializer;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.WalletTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartcolors.ColorDefinition;
import org.smartcolors.ColorKeyChain;
import org.smartcolors.ColorKeyChainFactory;
import org.smartcolors.ColorProof;
import org.smartcolors.ColorScanner;
import org.smartcolors.GenesisPoint;
import org.smartcolors.TxOutGenesisPoint;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.logging.LogManager;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class ColorTool {
	private static final Logger log = LoggerFactory.getLogger(ColorTool.class);

	private static OptionSet options;
	private static OptionParser parser;
	private static NetworkParameters params;

	private static BlockStore store;
	private static MyBlockChain chain;
	private static PeerGroup peers;
	private static Wallet wallet;
	private static File chainFile;
	private static File walletFile;
	private static ColorScanner scanner;
	private static File checkpointFile;
	private static ColorKeyChain colorChain;

	public static void main(String[] args) throws IOException {
		parser = new OptionParser();
		parser.accepts("prod", "use prodnet (default is testnet)");
		parser.accepts("regtest", "use regtest mode (default is testnet)");
		parser.accepts("debug");
		OptionSpec<String> walletFileName = parser.accepts("wallet").withRequiredArg();
		parser.nonOptions("COMMAND: one of - help, scan\n");

		options = parser.parse(args);

		if (options.has("debug")) {
			BriefLogFormatter.init();
			log.info("Starting up ...");
		} else {
			// Disable logspam unless there is a flag.
			LogManager.getLogManager().getLogger("").setLevel(Level.FINE);
		}

		List<?> cmds = options.nonOptionArguments();
		if (cmds.isEmpty())
			usage();
		String cmd = (String)cmds.get(0);
		List<?> cmdArgs = cmds.subList(1, cmds.size());

		String net;
		if (options.has("prod")) {
			net = "prodnet";
			params = NetworkParameters.fromID(NetworkParameters.ID_MAINNET);
		} else if (options.has("regtest")) {
			net = "regtest";
			params = NetworkParameters.fromID(NetworkParameters.ID_REGTEST);
		} else {
			net = "testnet";
			params = NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
		}

		chainFile = new File(net + ".chain");
		checkpointFile = new File(net + "-checkpoints.txt");

		String walletName = walletFileName.value(options);
		if (walletName == null)
			walletName = net + ".wallet";
		walletFile = new File(walletName);
		if (!walletFile.exists()) {
			createWallet(options, params, walletFile);
		}

		BufferedInputStream walletInputStream = null;
		try {
			WalletProtobufSerializer loader = new WalletProtobufSerializer();
			loader.setKeyChainFactory(new ColorKeyChainFactory());
			if (options.has("ignore-mandatory-extensions"))
				loader.setRequireMandatoryExtensions(false);
			walletInputStream = new BufferedInputStream(new FileInputStream(walletFile));
			wallet = loader.readWallet(walletInputStream);
			if (!wallet.getParams().equals(params)) {
				System.err.println("Wallet does not match requested network parameters: " +
						wallet.getParams().getId() + " vs " + params.getId());
				return;
			}
			wallet.clearTransactions(0);
		} catch (Exception e) {
			System.err.println("Failed to load wallet '" + walletFile + "': " + e.getMessage());
			e.printStackTrace();
			return;
		} finally {
			if (walletInputStream != null) {
				walletInputStream.close();
			}
		}

		if (cmd.equals("help")) {
			usage();
		} else if (cmd.equals("scan")) {
			scan(cmdArgs);
		} else {
			usage();
		}
	}

	// Sets up all objects needed for network communication but does not bring up the peers.
	private static void setup() throws BlockStoreException, IOException {
		if (store != null) return;  // Already done.
		if (chainFile.exists())
			chainFile.delete();
		reset();
		store = new SPVBlockStore(params, chainFile);
		if (checkpointFile.exists()) {
			CheckpointManager.checkpoint(params, new FileInputStream(checkpointFile), store, scanner.getEarliestKeyCreationTime());
		}
		chain = new MyBlockChain(params, wallet, store);
		chain.addListener(scanner, Threading.SAME_THREAD);

		if (peers == null) {
			peers = new PeerGroup(params, chain);
		}
		peers.setUserAgent("ColorTool", "1.0");
		peers.addPeerFilterProvider(scanner);
		//peers.addWallet(wallet);
		if (options.has("peers")) {
			String peersFlag = (String) options.valueOf("peers");
			String[] peerAddrs = peersFlag.split(",");
			for (String peer : peerAddrs) {
				try {
					peers.addAddress(new PeerAddress(InetAddress.getByName(peer), params.getPort()));
				} catch (UnknownHostException e) {
					System.err.println("Could not understand peer domain name/IP address: " + peer + ": " + e.getMessage());
					System.exit(1);
				}
			}
		} else {
			peers.addAddress(PeerAddress.localhost(params));
		}
	}

	private static void reset() {
		wallet.clearTransactions(0);
		saveWallet(walletFile);
	}

	private static void saveWallet(File walletFile) {
		try {
			// This will save the new state of the wallet to a temp file then rename, in case anything goes wrong.
			wallet.saveToFile(walletFile);
		} catch (IOException e) {
			System.err.println("Failed to save wallet! Old wallet should be left untouched.");
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void syncChain() {
		try {
			setup();
			peers.addEventListener(scanner.getPeerEventListener());
			int startTransactions = wallet.getTransactions(true).size();
			DownloadListener listener = new DownloadListener();
			peers.startAsync();
			peers.awaitRunning();
			peers.startBlockChainDownload(listener);
			try {
				listener.await();
			} catch (InterruptedException e) {
				System.err.println("Chain download interrupted, quitting ...");
				System.exit(1);
			}
			int endTransactions = wallet.getTransactions(true).size();
			if (endTransactions > startTransactions) {
				System.out.println("Synced " + (endTransactions - startTransactions) + " transactions.");
			}
		} catch (BlockStoreException e) {
			System.err.println("Error reading block chain file " + chainFile + ": " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("Error : " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void createWallet(OptionSet options, NetworkParameters params, File walletFile) throws IOException {
		if (walletFile.exists() && !options.has("force")) {
			System.err.println("Wallet creation requested but " + walletFile + " already exists, use --force");
			return;
		}
		wallet = new Wallet(params);
		try {
			colorChain =
					ColorKeyChain.builder()
							.seed(new DeterministicSeed("correct battery horse staple bogum", null, "", getEpoch()))
							.build();
			DeterministicKeyChain chain =
					DeterministicKeyChain.builder()
							.seed(new DeterministicSeed("correct battery horse staple bogum", null, "", getEpoch()))
							.build();
			wallet.addAndActivateHDChain(colorChain);
			wallet.addAndActivateHDChain(chain);
		} catch (UnreadableWalletException e) {
			throw new RuntimeException(e);
		}
		wallet.saveToFile(walletFile);
	}

	private static void scan(List<?> cmdArgs) {
		ColorDefinition def = makeColorDefinition();
		ColorProof proof = new ColorProof(def);
		scanner = new ColorScanner();
		scanner.addProof(proof);
		syncChain();
		System.out.println(proof);
		//Utils.sleep(1000);
		System.out.println(wallet);
		System.out.println(wallet.currentReceiveAddress());
		for (Transaction tx: wallet.getTransactionPool(WalletTransaction.Pool.UNSPENT).values()) {
			Map<ColorDefinition, Long> values = scanner.getAssetValues(tx, wallet);
			System.out.println(tx.getHash());
			System.out.println(values);
		}
		System.exit(0);
	}

	private static ColorDefinition makeColorDefinition() {
		String ser = "000000005b0000000000000000000000000000000000000000000000000000000000000000000000010174b16bf3ce53c26c3bc7a42f06328b4776a616182478b7011fba181db0539fc500000000";
		HashMap<String, String> metadata = Maps.newHashMap();
		metadata.put("name", "widgets");
		ColorDefinition def = ColorDefinition.fromPayload(params, Utils.HEX.decode(ser), metadata);
		System.out.println(def);
		return def;
	}

	private static ColorDefinition makeColorDefinition1() {
		List<String> genesisStrings = Lists.newArrayList("a18ed2595af17c30f5968a1c93de2364ae8d5af9d547f2336aafda8ed529fb2e:0");
		SortedSet<GenesisPoint> genesisPoints = Sets.newTreeSet();
		for (String str: genesisStrings) {
			String[] sp = str.split(":", 2);
			genesisPoints.add(new TxOutGenesisPoint(params, new TransactionOutPoint(params, Long.parseLong(sp[1]), new Sha256Hash(sp[0]))));
		}
		HashMap<String, String> metadata = Maps.newHashMap();
		metadata.put("name", "widgets");
		return new ColorDefinition(genesisPoints, metadata);
	}

	private static void usage() throws IOException {
		System.err.println("Usage: OPTIONS COMMAND ARGS*");
		parser.printHelpOn(System.err);
		System.exit(1);
	}

	private static class MyBlockChain extends BlockChain {
		public MyBlockChain(NetworkParameters params, Wallet wallet, BlockStore store) throws BlockStoreException {
			super(params, wallet, store);
		}

		public void roll(int height) throws BlockStoreException {
			rollbackBlockStore(height);
		}
	}

	// seconds
	private static long getEpoch() {
		try {
			return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse("2014-09-24T00:00:00+0000").getTime() / 1000;
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
	}
}