package udp;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import javax.mail.MessagingException;

import com.util.ProtocolConstants;
import com.util.UCIUtil;

public class DatagramServer implements Runnable {

	private static Logger LOG = Logger.getLogger(DatagramServer.class.getName());

	public static final int DEFAULT_PORT = 11000;
	private DatagramSocket datagramSocket = null;
	private static Properties config;
	public Boolean exit = false;
	private String enginePath;
	private boolean started = false;
	private int cores = -1;

	private static List<DatagramServer> servers = new ArrayList<>();
	private byte[] password;

	public DatagramServer(String enginePath, int port) throws FileNotFoundException, IOException, MessagingException {
		
		
		InetAddress.getByName(config.getProperty("client_ip"));
		datagramSocket = new DatagramSocket(port);
		// datagramSocket.setReuseAddress(true);
		// datagramSocket.bind(new InetSocketAddress("192.168.1.2", 11000));
		System.out.println(datagramSocket.getLocalSocketAddress());
		System.out.println(datagramSocket.getLocalPort());
		this.enginePath = enginePath;
		password = config.getProperty("secretKey").getBytes();
		cores = Runtime.getRuntime().availableProcessors();
		LOG.info("available cores: "+cores);
		if(cores > 1)
			cores = cores-1;
		LOG.info("usable cores: "+cores);
		System.out.println("cores: "+cores);
		// LOG.info("Port number: "+DEFAULT_PORT+" "+datagramSocket.getLocalSocketAddress());
	}

	public DatagramServer(int port) throws FileNotFoundException, IOException, MessagingException {
		if (!config.containsKey(String.valueOf(port))) {
			System.err.println("cannot find the mapping for corresponding port...");
			throw new IOException("cannot find the mapping for corresponding port...");
		}
		enginePath = config.getProperty(String.valueOf(port));
		datagramSocket = new DatagramSocket(port);		
		System.out.println("======="+datagramSocket.getLocalAddress());
		password = config.getProperty("secretKey").getBytes();
		System.out.println("listening on port: " + datagramSocket.getLocalPort() + "\tengine: " + enginePath);
		
		cores = Runtime.getRuntime().availableProcessors();
		LOG.info("available cores: "+cores);
		if(cores > 1)
			cores = cores-1;
		LOG.info("usable cores: "+cores);
		System.out.println("cores: "+cores);
	}

	public void listen() {
		byte[] buffer = new byte[ProtocolConstants.BUFFER_SIZE];
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		Process p = null;
		boolean init = false;
		String sentMsg = null;
		String receivedText = null;

		try {
			while (!exit) {
				if (init) {
					if (!started) {
						p = Runtime.getRuntime().exec(enginePath);
						//p.getOutputStream().write(("setoption name Max CPUs value "+cores+"\nsetoption name CPU Usage value 100\n").getBytes());
						p.getOutputStream().write(("setoption name Threads value "+cores+"\nsetoption name CPU Usage value 100\n").getBytes());
						started = true;
						LOG.fine("Connection received from " + packet.getAddress().getHostName());
					}

					Thread reader = new Thread(new AsyncDatagramReader(p.getOutputStream(), datagramSocket, packet, exit, password));
					Thread writer = new Thread(new AsyncDatagramWriter(p.getInputStream(), datagramSocket, packet.getAddress(), packet.getPort(),
							exit, password));
					// writer.setDaemon(true);
					reader.start();
					writer.start();

					if (reader.isAlive())
						reader.join();
					if (writer.isAlive())
						writer.join();

					LOG.info("Closing Engine");
					/*
					 * newPacket = new DatagramPacket("exit".getBytes(), 4,
					 * packet.getAddress(), packet.getPort());
					 * datagramSocket.send(newPacket);
					 */
					UCIUtil.sendPacket("exit".getBytes(UCIUtil.CHARSET), password, datagramSocket, packet.getAddress(), packet.getPort());
					LOG.info("Closed Engine");
					init = false;
					p.destroy();
					started = false;
				} else {
					packet = new DatagramPacket(buffer, buffer.length);
					LOG.info("waiting for packet... on port " + datagramSocket.getLocalPort());
					datagramSocket.receive(packet);
					receivedText = UCIUtil.readPacket(packet, password);
					LOG.info("packet recvd " + receivedText + " from " + packet.getSocketAddress());
					if (receivedText.equalsIgnoreCase("helloserver")) {
						sentMsg = "connected";
						init = true;
					} else if ("exit".equals(receivedText)) {
						exit = true;
					} else if ("close_engine".equals(receivedText)) {
						p.destroy();
						sentMsg = "engine closed";
					} else {
						sentMsg = "unknown command";
					}
					UCIUtil.sendPacket(sentMsg.getBytes(UCIUtil.CHARSET), password, datagramSocket, packet.getAddress(), packet.getPort());
				}
			}
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		} finally {
			p.destroy();
		}
	}

	public static void main(String[] args) throws FileNotFoundException, IOException, MessagingException {
		System.getProperties().put("Djava.util.logging.config.file", "logging.properties");
		LOG.info("server started");
		config = new Properties();
		config.load(new FileInputStream("config.ini"));
		
		try {
			System.out.println(UCIUtil.getExternalIP());
			UCIUtil.mailExternalIP(UCIUtil.getExternalIP()+":admin_port="+UCIUtil.getAdminPort(), config.getProperty("fromMail"), config.getProperty("mailPass"), config.getProperty("toMail"));
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		
		int count = 0;
		for (int port = DEFAULT_PORT; port <= 11080; port++) {
			if (config.containsKey(String.valueOf(port))) {
				servers.add(new DatagramServer(port));				
				new Thread(servers.get(count)).start();
				count++;
			}
		}
		
		new Thread(new Runnable() {
			
			@Override
			public void run() {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				String line = null;
				try {
					while ((line = reader.readLine()) != null) {
						if(line.contains(":q")) {
							for(DatagramServer s : servers) {
								s.exit = true;
							}
							break;
						}
							
					}
				} catch (IOException e) {
					e.printStackTrace();
				}				
			}
		}).start();
	}

	public DatagramSocket getDatagramSocket() {
		return datagramSocket;
	}
	
	@Override
	public void run() {
		listen();
	}
}
