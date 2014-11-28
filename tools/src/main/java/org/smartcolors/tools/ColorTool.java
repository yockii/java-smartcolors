package org.smartcolors.tools;

import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.bitcoinj.core.AbstractWalletEventListener;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DownloadListener;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.store.WalletProtobufSerializer;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.WalletTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartcolors.AssetCoinSelector;
import org.smartcolors.BitcoinCoinSelector;
import org.smartcolors.ClientColorScanner;
import org.smartcolors.ColorKeyChain;
import org.smartcolors.ColorKeyChainFactory;
import org.smartcolors.ColorScanner;
import org.smartcolors.SPVColorScanner;
import org.smartcolors.SmartwalletExtension;
import org.smartcolors.core.ColorDefinition;
import org.smartcolors.core.GenesisOutPointsMerbinnerTree;
import org.smartcolors.core.GenesisScriptMerbinnerTree;
import org.smartcolors.core.SmartColors;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.LogManager;

import javax.annotation.Nullable;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class ColorTool {
	private static final boolean USE_SPV = false;
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
	private static OptionSpec<String> mnemonicSpec;
	private static SmartwalletExtension extension;

	public static void main(String[] args) throws IOException {
//		for (int i = 0; i < args.length; i++) {
//			System.out.println(args[i]);
//		}
		parser = new OptionParser();
		parser.accepts("prod", "use prodnet (default is testnet)");
		parser.accepts("regtest", "use regtest mode (default is testnet)");
		parser.accepts("force", "force creation of wallet from mnemonic");
		parser.accepts("debug");
		parser.accepts("verbose");
		mnemonicSpec = parser.accepts("mnemonic", "mnemonic phrase").withRequiredArg();
		OptionSpec<String> walletFileName = parser.accepts("wallet").withRequiredArg();
		parser.nonOptions("COMMAND: one of - help, scan, send\n");

		options = parser.parse(args);

		if (options.has("debug")) {
			BriefLogFormatter.init();
			log.info("Starting up ...");
		} else {
			// Disable logspam unless there is a flag.
			LogManager.getLogManager().getLogger("").setLevel(Level.WARNING);
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
		if (!walletFile.exists() || options.has(mnemonicSpec)) {
			createWallet(options, params, walletFile);
		}

		if (readWallet()) return;

		wallet.addEventListener(new AbstractWalletEventListener() {
			@Override
			public void onCoinsReceived(final Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
				ListenableFuture<Transaction> res = scanner.getTransactionWithKnownAssets(tx, wallet, colorChain);
				Futures.addCallback(res, new FutureCallback<Transaction>() {
					@Override
					public void onSuccess(@Nullable Transaction result) {
						Map<ColorDefinition, Long> change = scanner.getNetAssetChange(result, wallet, colorChain);
						System.out.println(change);
					}

					@Override
					public void onFailure(Throwable t) {
						log.error("*** failed");
					}
				});
			}
		});

		if (cmd.equals("help")) {
			usage();
		} else if (cmd.equals("scan")) {
			scan(cmdArgs);
		} else if (cmd.equals("send")) {
			send(cmdArgs);
		} else if (cmd.equals("dump")) {
			dump(cmdArgs);
		} else {
			usage();
		}
	}

	private static boolean readWallet() throws IOException {
		BufferedInputStream walletInputStream = null;
		try {
			makeScanner();
			colorChain = null;
			WalletProtobufSerializer loader = new WalletProtobufSerializer(new WalletProtobufSerializer.WalletFactory() {
				@Override
				public Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
					Wallet wallet = new Wallet(params, keyChainGroup);
					SmartwalletExtension extension = (SmartwalletExtension) wallet.addOrGetExistingExtension(new SmartwalletExtension(params));
					extension.setScanner(scanner);
					if (colorChain != null) {
						extension.setColorKeyChain(colorChain);
					}
					return wallet;
				}
			});
			loader.setKeyChainFactory(new ColorKeyChainFactory(new ColorKeyChainFactory.Callback() {
				@Override
				public void onRestore(ColorKeyChain chain) {
					colorChain = chain;
				}
			}));
			if (options.has("ignore-mandatory-extensions"))
				loader.setRequireMandatoryExtensions(false);
			walletInputStream = new BufferedInputStream(new FileInputStream(walletFile));
			wallet = loader.readWallet(walletInputStream);
			checkNotNull(colorChain);
			if (!wallet.getParams().equals(params)) {
				System.err.println("Wallet does not match requested network parameters: " +
						wallet.getParams().getId() + " vs " + params.getId());
				return true;
			}
			scanner.addAllPending(wallet, wallet.getPendingTransactions());
			if (wallet.getExtensions().get(SmartwalletExtension.IDENTIFIER) == null)
				throw new UnreadableWalletException("missing smartcolors extension");
			if (colorChain == null)
				throw new UnreadableWalletException("missing color keychain");
		} catch (Exception e) {
			System.err.println("Failed to load wallet '" + walletFile + "': " + e.getMessage());
			e.printStackTrace();
			return true;
		} finally {
			if (walletInputStream != null) {
				walletInputStream.close();
			}
		}
		return false;
	}

	// Sets up all objects needed for network communication but does not bring up the peers.
	private static void setup() throws BlockStoreException, IOException {
		if (store != null) return;  // Already done.
//		if (chainFile.exists())
//			chainFile.delete();
//		reset();
		boolean chainExisted = chainFile.exists();
		store = new SPVBlockStore(params, chainFile);
		if (checkpointFile.exists() && !chainExisted) {
			long creationTime = Long.MAX_VALUE;
			if (USE_SPV) {
				creationTime = ((SPVColorScanner)scanner).getEarliestKeyCreationTime();
			}
			creationTime = Math.min(creationTime, wallet.getEarliestKeyCreationTime());
			CheckpointManager.checkpoint(params, new FileInputStream(checkpointFile), store, creationTime);
		}
		chain = new MyBlockChain(params, wallet, store);

		if (peers == null) {
			peers = new PeerGroup(params, chain);
		}
		peers.setUserAgent("ColorTool", "1.0");

		if (USE_SPV) {
			SPVColorScanner spvScanner = (SPVColorScanner) scanner;
			chain.addListener(spvScanner, Threading.SAME_THREAD);
			peers.addPeerFilterProvider(spvScanner);
			peers.addEventListener(spvScanner.getPeerEventListener());
		} else {
			ClientColorScanner clientScanner = (ClientColorScanner) scanner;
			clientScanner.listenToWallet(wallet);
			clientScanner.setColorKeyChain(colorChain);
			clientScanner.start();
		}

		peers.addWallet(wallet);
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
			//peers.addPeerDiscovery(new DnsDiscovery(params));
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
			wallet.saveToFile(walletFile);
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
			System.exit(1);
		}
		if (chainFile.exists())
			chainFile.delete();

		try {
			String mnemonicCode = "correct battery horse staple bogum";
			if (options.has(mnemonicSpec))
				mnemonicCode = mnemonicSpec.value(options);
			System.out.println(mnemonicCode);
			DeterministicSeed seed = new DeterministicSeed(mnemonicCode, null, "", SmartColors.getSmartwalletEpoch());
			System.out.println(Utils.HEX.encode(seed.getSeedBytes()));
			colorChain =
					ColorKeyChain.builder()
							.seed(seed)
							.build();
			DeterministicKeyChain chain =
					DeterministicKeyChain.builder()
							.seed(seed)
							.build();
			KeyChainGroup group = new KeyChainGroup(params);
			group.addAndActivateHDChain(colorChain);
			group.addAndActivateHDChain(chain);
			group.setLookaheadSize(20);
			group.setLookaheadThreshold(10);
			wallet = new Wallet(params, group);
			extension = new SmartwalletExtension(params);
			//extension.setColorKeyChain(colorChain);
			wallet.addOrGetExistingExtension(extension);
			makeScanner();
			addBuiltins();
			extension.setScanner(scanner);
		} catch (UnreadableWalletException e) {
			throw new RuntimeException(e);
		}
		wallet.saveToFile(walletFile);
	}

	private static void makeScanner() {
		if (USE_SPV)
			scanner = new SPVColorScanner(params);
		else {
			URI baseUri = null;
			try {
				baseUri = new URI("http://localhost:8888/");
			} catch (URISyntaxException e) {
				Throwables.propagate(e);
			}
			scanner = new ClientColorScanner(params, baseUri);
		}
	}

	private static void addBuiltins() {
		try {
			scanner.addDefinition(loadDefinition("gold.json"));
			scanner.addDefinition(loadDefinition("oil.json"));
		} catch (SPVColorScanner.ColorDefinitionException colorDefinitionExists) {
			Throwables.propagate(colorDefinitionExists);
		}
	}

	private static ColorDefinition loadDefinition(String path) {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> values = Maps.newHashMap();
		values.put(ColorDefinition.NETWORK_ID_INJECTABLE, NetworkParameters.ID_TESTNET);
		mapper.setInjectableValues(new InjectableValues.Std(values));
		ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		InputStream is = classloader.getResourceAsStream(path);
		try {
			return mapper.readValue(is, ColorDefinition.TYPE_REFERENCE);
		} catch (IOException e) {
			throw Throwables.propagate(e);
		}
	}

	private static void dump(List<?> cmdArgs) {
		syncChain();
		dumpState();
		System.out.println(wallet.currentReceiveAddress());
		Address assetBitcoinAddress = colorChain.freshOutputScript(KeyChain.KeyPurpose.RECEIVE_FUNDS).getToAddress(params);
		System.out.println(assetBitcoinAddress);
		System.out.println(SmartColors.toAssetAddress(assetBitcoinAddress, !isTestNet()));
		System.exit(0);
	}

	private static void send(List<?> cmdArgs) {
		syncChain();
		String color = (String)cmdArgs.get(0);
		String dest = (String)cmdArgs.get(1);
		String amountString = (String)cmdArgs.get(2);
		ColorDefinition def = null;
		for (ColorDefinition definition : scanner.getDefinitions()) {
			if (definition.getName().equalsIgnoreCase(color)) {
				def = definition;
				break;
			}
		}
		if (def == null) {
			System.err.println("unknown color");
			System.exit(1);
		}
		AssetCoinSelector assetSelector = new AssetCoinSelector(colorChain, scanner.getColorTrackByDefinition(def));
		BigDecimal divisibilityDivider = getDivider(def);

		long amount = new BigDecimal(amountString).multiply(divisibilityDivider).intValue();
		Wallet.SendRequest req = null;
		try {
			req = makeAssetSendRequest(dest, amount);
			assetSelector.completeTx(wallet, req, amount);
		} catch (AddressFormatException e) {
			Throwables.propagate(e);
		} catch (InsufficientMoneyException e) {
			Throwables.propagate(e);
		}
		wallet.commitTx(req.tx);
		try {
			peers.broadcastTransaction(req.tx).get();
		} catch (InterruptedException e) {
			Throwables.propagate(e);
		} catch (ExecutionException e) {
			Throwables.propagate(e);
		}
		System.out.println(req.tx);
		Utils.sleep(2000);
		System.exit(0);

	}

	private static BigDecimal getDivider(ColorDefinition def) {
		String div = def.getMetadata().get("divisibility");
		int divisibilityDivider = 1;
		if (div != null) {
			int divisibility = Integer.parseInt(div);
			divisibilityDivider = BigInteger.TEN.pow(divisibility).intValue();
		}
		return new BigDecimal(divisibilityDivider);
	}

	private static Wallet.SendRequest makeAssetSendRequest(String dest, long amount) throws AddressFormatException {
		Transaction tx = new Transaction(wallet.getParams());
		Address to = new Address(SmartColors.getAssetParameters(!isTestNet()), dest);
		Script outputScript = ScriptBuilder.createOutputScript(to);
		AssetCoinSelector.addAssetOutput(tx, outputScript, amount);
		Wallet.SendRequest request = Wallet.SendRequest.forTx(tx);
		request.shuffleOutputs = false;
		request.coinSelector = new BitcoinCoinSelector(colorChain);
		return request;
	}


	private static void scan(List<?> cmdArgs) {
		syncChain();
		checkState(wallet.isConsistent());
		Utils.sleep(5*1000);
		if (options.has("verbose")) {
			dumpState();
		}
		Utils.sleep(6000*1000);
		System.exit(0);
	}

	private static void dumpState() {
		System.out.println(scanner);
		System.out.println(wallet);
		System.out.println("************** Transactions:");
		for (Transaction tx: wallet.getTransactionPool(WalletTransaction.Pool.UNSPENT).values()) {
			Map<ColorDefinition, Long> values = scanner.getNetAssetChange(tx, wallet, colorChain);
			System.out.println(tx.getHash());
			System.out.println(values);
		}
		System.out.println("\n************** Balances:");
		Map<ColorDefinition, Long> balances = scanner.getBalances(wallet, colorChain);
		for (Map.Entry<ColorDefinition, Long> entry : balances.entrySet()) {
			BigDecimal divisibilityDivider = getDivider(entry.getKey());
			long amount = BigDecimal.valueOf(entry.getValue()).divide(divisibilityDivider).intValue();
			System.out.println(entry.getKey().getName() + " : " + amount);
		}
	}

	private static boolean isTestNet() {
		return params.getId().equals(NetworkParameters.ID_TESTNET);
	}

	private static ColorDefinition makeColorDefinition() {
		String ser;
		if (params.getId().equals(NetworkParameters.ID_REGTEST)) {
			ser = "000000005b0000000000000000000000000000000000000000000000000000000000000000000000010174b16bf3ce53c26c3bc7a42f06328b4776a616182478b7011fba181db0539fc500000000";
		} else if (isTestNet()) {
			ser = "000000002e970400000000000000000000000000000000000000000000000000000000000000000001019fe1cdae009a55d0877550aabdc7a1dc187f1dabcea8cf167827d6401f912db100000000";
		} else {
			throw new IllegalArgumentException();
		}
		HashMap<String, String> metadata = Maps.newHashMap();
		metadata.put("name", "widgets");
		ColorDefinition def = null; //ColorDefinition.fromPayload(params, Utils.HEX.decode(ser), metadata);
		System.out.println(def);
		return def;
	}

	private static ColorDefinition makeColorDefinition1() {
		List<String> genesisStrings = Lists.newArrayList("a18ed2595af17c30f5968a1c93de2364ae8d5af9d547f2336aafda8ed529fb2e:0:10000");
		Map<TransactionOutPoint, Long> points = Maps.newHashMap();
		for (String str: genesisStrings) {
			String[] sp = str.split(":", 3);
			points.put(new TransactionOutPoint(params, Long.parseLong(sp[1]), new Sha256Hash(sp[0])), Long.parseLong(sp[2]));
		}
		GenesisOutPointsMerbinnerTree outPoints = new GenesisOutPointsMerbinnerTree(params, points);
		GenesisScriptMerbinnerTree scripts = new GenesisScriptMerbinnerTree();
		HashMap<String, String> metadata = Maps.newHashMap();
		metadata.put("name", "widgets");
		return new ColorDefinition(params, outPoints, scripts, metadata);
	}

	private static void usage() throws IOException {
		System.err.println("Version: " + SmartColors.getVersion());
		System.err.println("Usage: OPTIONS COMMAND ARGS*\n" +
				"scan\n" +
				"send COLOR DEST AMOUNT\n");
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
