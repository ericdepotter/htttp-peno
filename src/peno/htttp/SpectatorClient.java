package peno.htttp;

import java.io.IOException;
import java.util.Map;

import peno.htttp.impl.Consumer;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * A client for spectating a game over the HTTTP protocol.
 */
public class SpectatorClient {

	/*
	 * Communication
	 */
	private final Connection connection;
	private Channel channel;
	private final SpectatorHandler handler;
	private Consumer consumer;

	/*
	 * Identifiers
	 */
	private final String gameID;

	/**
	 * Create a spectator client.
	 * 
	 * @param connection
	 *            The AMQP connection for communication.
	 * @param handler
	 *            The event handler which listens to this spectator.
	 * @param gameID
	 *            The game identifier.
	 * @throws IOException
	 */
	public SpectatorClient(Connection connection, SpectatorHandler handler, String gameID) throws IOException {
		this.connection = connection;
		this.handler = handler;
		this.gameID = gameID;
	}

	/**
	 * Get the game identifier.
	 */
	public String getGameID() {
		return gameID;
	}

	/**
	 * Start spectating.
	 * 
	 * @throws IOException
	 */
	public void start() throws IOException {
		// Create channel
		channel = connection.createChannel();
		// Declare exchange
		channel.exchangeDeclare(getGameID(), "topic");

		// Setup consumer
		consumer = new SpectatorConsumer(channel);
		consumer.bind(getGameID(), "*");
	}

	/**
	 * Stop spectating.
	 * 
	 * @throws IOException
	 */
	public void stop() throws IOException {
		// Shut down consumer
		if (consumer != null) {
			consumer.terminate();
		}
		consumer = null;

		// Shut down channel
		try {
			channel.close();
		} catch (IOException e) {
		} catch (ShutdownSignalException e) {
		} finally {
			channel = null;
		}
	}

	/**
	 * Handles spectator broadcasts.
	 */
	private class SpectatorConsumer extends Consumer {

		public SpectatorConsumer(Channel channel) throws IOException {
			super(channel);
		}

		@Override
		public void handleMessage(String topic, Map<String, Object> message, BasicProperties props) throws IOException {
			if (topic.equals("start")) {
				// Game started
				handler.gameStarted();
			} else if (topic.equals("stop")) {
				// Game stopped
				handler.gameStopped();
			} else if (topic.equals("pause")) {
				// Game paused
				handler.gamePaused();
			} else if (topic.equals("position")) {
				// Player updated their position
				String playerID = (String) message.get("playerID");
				int playerNumber = ((Number) message.get("playerNumber")).intValue();
				double x = ((Number) message.get("x")).doubleValue();
				double y = ((Number) message.get("y")).doubleValue();
				double angle = ((Number) message.get("angle")).doubleValue();
				handler.playerPosition(playerID, playerNumber, x, y, angle);
			} else if (topic.equals("found")) {
				// Player found their object
				String playerID = (String) message.get("playerID");
				int playerNumber = ((Number) message.get("playerNumber")).intValue();
				handler.playerFoundObject(playerID, playerNumber);
			}
		}

	}

}