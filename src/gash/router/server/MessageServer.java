/**
 * Copyright 2016 Gash.
 *
 * This file and intellectual content is protected under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package gash.router.server;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;

import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gash.router.container.RoutingConf;
import gash.router.server.edges.EdgeMonitor;
import gash.router.server.tasks.NoOpBalancer;
import gash.router.server.tasks.TaskList;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import gash.router.server.election.Follower;

public class MessageServer {
	protected static Logger logger = LoggerFactory.getLogger("server");

	protected static HashMap<Integer, ServerBootstrap> bootstrap = new HashMap<Integer, ServerBootstrap>();

	protected boolean background = false;
	protected ServerState myState;
	protected Follower follower = null;
	protected ConfManager confManager;

	public MessageServer(File cfg) {
		confManager = new ConfManager(cfg);
		Thread cm = new Thread(confManager);
		cm.start();
	}

	public void release() {
	}

	public void startServer() {
		StartWorkCommunication comm = new StartWorkCommunication(confManager.getConf());
		logger.info("Work starting");
		this.myState = comm.getServerState();
		myState.getStatus().setTotalNodesDiscovered(myState.getConf().getTotalNodes());
		confManager.setState(myState);

		// We always start the worker in the background
		Thread cthread = new Thread(comm);
		cthread.start();

		if (!confManager.getConf().isInternalNode()) {
			StartCommandCommunication comm2 = new StartCommandCommunication(myState);
			logger.info("Command starting");

			if (background) {
				Thread cthread2 = new Thread(comm2);
				cthread2.start();
			} else
				comm2.run();
		}
	}

	/**
	 * static because we need to get a handle to the factory from the shutdown
	 * resource
	 */
	public void shutdown() {
		logger.info("Server shutdown");
		myState.keepWorking = false;
		System.exit(0);
	}

	private static class ConfManager implements Runnable {
		RoutingConf conf;
		private long timeStamp;
		private File file;
		protected ServerState myState;
		private boolean update = false;

		public ConfManager(File file) {
			this.file = file;
			this.timeStamp = 0;
			init(this.file);
		}

		public final void run() {

			while (true) {
				try {
					long timeStamp = file.lastModified();

					if (this.timeStamp != timeStamp) {
						this.timeStamp = timeStamp;
						init(file);
						if (update) {
							myState.updateConf();
						}
					}
					Thread.sleep(60000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		public void setState(ServerState myState) {
			this.myState = myState;
			this.update = true;
		}

		private void init(File cfg) {
			if (!cfg.exists())
				throw new RuntimeException(cfg.getAbsolutePath() + " not found");
			// resource initialization - how message are processed
			BufferedInputStream br = null;
			try {
				byte[] raw = new byte[(int) cfg.length()];
				br = new BufferedInputStream(new FileInputStream(cfg));
				br.read(raw);
				conf = JsonUtil.decode(new String(raw), RoutingConf.class);
				if (!verifyConf(conf))
					throw new RuntimeException("verification of configuration failed");
			} catch (Exception ex) {
				ex.printStackTrace();
			} finally {
				if (br != null) {
					try {
						br.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}

		private boolean verifyConf(RoutingConf conf) {
			return (conf != null);
		}

		public RoutingConf getConf() {
			return conf;
		}
	}

	private static class StartCommandCommunication implements Runnable {
		RoutingConf conf;
		ServerState state;

		public StartCommandCommunication(ServerState state) {
			this.conf = state.getConf();
			this.state = state;
			this.state.getEmon().setGlobalNeighbours();
			this.state.getEmon().FetchGlobalNeighbours();
		}

		public void run() {
			// construct boss and worker threads (num threads = number of cores)

			EventLoopGroup bossGroup = new NioEventLoopGroup();
			EventLoopGroup workerGroup = new NioEventLoopGroup();

			try {
				ServerBootstrap b = new ServerBootstrap();
				bootstrap.put(conf.getCommandPort(), b);

				b.group(bossGroup, workerGroup);
				b.channel(NioServerSocketChannel.class);
				b.option(ChannelOption.SO_BACKLOG, 100);
				b.option(ChannelOption.TCP_NODELAY, true);
				b.option(ChannelOption.SO_KEEPALIVE, true);
				// b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR);

				boolean compressComm = false;
				b.childHandler(new CommandInit(this.state, compressComm));

				// Start the server.
				logger.info("Starting command server (" + conf.getNodeId() + "), listening on port = "
						+ conf.getCommandPort());
				ChannelFuture f = b.bind(conf.getCommandPort()).syncUninterruptibly();

				logger.info(f.channel().localAddress() + " -> open: " + f.channel().isOpen() + ", write: "
						+ f.channel().isWritable() + ", act: " + f.channel().isActive());

				// block until the server socket is closed.
				f.channel().closeFuture().sync();

			} catch (Exception ex) {
				// on bind().sync()
				logger.error("Failed to setup handler.", ex);
			} finally {
				// Shut down all event loops to terminate all threads.
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();
			}
		}
	}

	/**
	 * initialize netty communication
	 * 
	 * @param port
	 *            The port to listen to
	 */
	private static class StartWorkCommunication implements Runnable {
		ServerState state;
		Follower follower = null;

		public StartWorkCommunication(RoutingConf conf) {
			if (conf == null)
				throw new RuntimeException("missing conf");

			state = new ServerState("./data");
			state.setConf(conf);

			TaskList tasks = new TaskList(new NoOpBalancer());
			state.setTasks(tasks);

			EdgeMonitor emon = new EdgeMonitor(state);
			Thread t = new Thread(emon);
			t.start();

			follower = new Follower(state);
			Thread th = new Thread(follower);
			th.start();

			logger.info("started the follower thread");
		}

		public ServerState getServerState() {
			return this.state;
		}

		public void run() {
			// construct boss and worker threads (num threads = number of cores)

			EventLoopGroup bossGroup = new NioEventLoopGroup();
			EventLoopGroup workerGroup = new NioEventLoopGroup();

			try {
				ServerBootstrap b = new ServerBootstrap();
				bootstrap.put(state.getConf().getWorkPort(), b);

				b.group(bossGroup, workerGroup);
				b.channel(NioServerSocketChannel.class);
				b.option(ChannelOption.SO_BACKLOG, 100);
				b.option(ChannelOption.TCP_NODELAY, true);
				b.option(ChannelOption.SO_KEEPALIVE, true);
				// b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR);

				boolean compressComm = false;
				b.childHandler(new WorkInit(state, compressComm));

				// Start the server.
				logger.info("Starting work server (" + state.getConf().getNodeId() + "), listening on port = "
						+ state.getConf().getWorkPort());
				ChannelFuture f = b.bind(state.getConf().getWorkPort()).syncUninterruptibly();

				logger.info(f.channel().localAddress() + " -> open: " + f.channel().isOpen() + ", write: "
						+ f.channel().isWritable() + ", act: " + f.channel().isActive());

				// block until the server socket is closed.
				f.channel().closeFuture().sync();

			} catch (Exception ex) {
				// on bind().sync()
				logger.error("Failed to setup handler.", ex);
			} finally {
				// Shut down all event loops to terminate all threads.
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();

				// shutdown monitor
				EdgeMonitor emon = state.getEmon();
				if (emon != null)
					emon.shutdown();
			}
		}
	}

	/**
	 * help with processing the configuration information
	 * 
	 * @author gash
	 *
	 */
	public static class JsonUtil {
		private static JsonUtil instance;

		public static void init(File cfg) {

		}

		public static JsonUtil getInstance() {
			if (instance == null)
				throw new RuntimeException("Server has not been initialized");

			return instance;
		}

		public static String encode(Object data) {
			try {
				ObjectMapper mapper = new ObjectMapper();
				return mapper.writeValueAsString(data);
			} catch (Exception ex) {
				return null;
			}
		}

		public static <T> T decode(String data, Class<T> theClass) {
			try {
				ObjectMapper mapper = new ObjectMapper();
				return mapper.readValue(data.getBytes(), theClass);
			} catch (Exception ex) {
				ex.printStackTrace();
				return null;
			}
		}
	}

}
