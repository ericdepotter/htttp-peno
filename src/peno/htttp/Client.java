package peno.htttp;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import peno.htttp.impl.ForwardingFailureCallback;
import peno.htttp.impl.PlayerInfo;
import peno.htttp.impl.PlayerState;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.tools.json.JSONReader;
import com.rabbitmq.tools.json.JSONWriter;

/**
 * A client for communicating over the HTTTP protocol.
 */
public class Client {

	/*
	 * Constants
	 */
	public static final int nbPlayers = 4;
	public static final int joinExpiration = 2000;
	public static final int heartbeatFrequency = 2000;
	public static final int heartbeatExpiration = 5000;

	/*
	 * Communication
	 */
	private final Channel channel;
	private final Handler handler;
	private String publicQueue;
	private String teamQueue;

	/*
	 * Identifiers
	 */
	private final String gameID;
	private final String playerID;

	/*
	 * Game state
	 */
	private GameState gameState = GameState.DISCONNECTED;
	private final Map<String, PlayerInfo> players = new HashMap<String, PlayerInfo>();

	public Client(Connection connection, Handler handler, String gameID,
			String playerID) throws IOException {
		this.handler = handler;
		this.gameID = gameID;
		this.playerID = playerID;

		this.channel = connection.createChannel();
		setup();
	}

	public String getGameID() {
		return gameID;
	}

	public String getPlayerID() {
		return playerID;
	}

	public GameState getGameState() {
		return gameState;
	}

	protected void setGameState(GameState gameState) {
		this.gameState = gameState;
	}

	public boolean isConnected() {
		return getGameState() != GameState.DISCONNECTED;
	}

	public boolean isPaused() {
		return getGameState() == GameState.PAUSED;
	}

	/*
	 * Player tracking
	 */

	private boolean hasPlayer(String playerID) {
		return players.containsKey(playerID);
	}

	private PlayerInfo getPlayer(String playerID) {
		synchronized (players) {
			return players.get(playerID);
		}
	}

	private PlayerInfo getLocalPlayer() {
		String playerID = getPlayerID();
		if (!hasPlayer(playerID)) {
			setPlayer(playerID, PlayerState.NOT_READY);
		}
		return getPlayer(playerID);
	}

	private int getNbPlayers() {
		synchronized (players) {
			return players.size();
		}
	}

	public boolean isFull() {
		return getNbPlayers() >= nbPlayers;
	}

	private void setPlayer(String playerID, PlayerState state) {
		synchronized (players) {
			if (hasPlayer(playerID)) {
				players.get(playerID).setState(state);
			} else {
				players.put(playerID, new PlayerInfo(playerID, state));
			}
		}
	}

	private void setPlayer(String playerID, boolean isReady) {
		setPlayer(playerID, isReady ? PlayerState.READY : PlayerState.NOT_READY);
	}

	private void removePlayer(String playerID) {
		synchronized (players) {
			players.remove(playerID);
		}
	}

	/*
	 * Joining/leaving
	 */

	public void join(final Callback<Void> callback)
			throws IllegalStateException, IOException {
		if (isConnected()) {
			throw new IllegalStateException("Already connected to game.");
		}

		// Reset
		resetGame();

		// Request to join
		new JoinRequester(callback).request(joinExpiration);
	}

	protected void joined() throws IOException {
		// Setup public queue
		setupPublic();
	}

	protected boolean canJoin(String playerID) {
		switch (getGameState()) {
		case PLAYING:
			// Nobody can join while playing
			return false;
		case PAUSED:
			// Only missing players can join
			return hasPlayer(playerID);
		case WAITING:
			// Reject duplicate players
			if (hasPlayer(playerID))
				return false;
			// Reject when full
			if (isFull())
				return false;
			return true;
		default:
		}
		return false;
	}

	public void leave() throws IOException {
		if (isConnected()) {
			// Notify leaving
			publish("leave", null);
			// Shut down
			shutdownPublic();
			shutdownTeam();
		}

		// Reset game
		resetGame();
	}

	protected void playerLeft(String playerID) {
		switch (getGameState()) {
		case WAITING:
			// Simply remove player
			removePlayer(playerID);
			break;
		case PLAYING:
		case PAUSED:
			if (hasPlayer(playerID)) {
				// Player went missing
				getPlayer(playerID).setState(PlayerState.DISCONNECTED);
				// Pause
				try {
					pause();
				} catch (IllegalStateException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			break;
		default:
			break;
		}
	}

	/*
	 * Starting/stopping
	 */

	public boolean isReady() {
		return getLocalPlayer().isReady();
	}

	public void setReady(boolean isReady) throws IOException {
		PlayerInfo playerInfo = getLocalPlayer();
		PlayerState newState = isReady ? PlayerState.READY
				: PlayerState.NOT_READY;

		if (newState != playerInfo.getState()) {
			// Update state
			playerInfo.setState(newState);

			// Publish updated state
			Map<String, Object> message = new HashMap<String, Object>();
			message.put("isReady", playerInfo.isReady());
			publish("ready", message);
		}
	}

	protected void start(Callback<Void> callback) {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		// TODO Check if can start
	}

	public void stop(Callback<Void> callback) {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		if (getGameState() == GameState.WAITING) {
			// Game already stopped
			callback.onSuccess(null);
		}
	}

	protected void pause() throws IOException, IllegalStateException {
		if (!isConnected()) {
			throw new IllegalStateException("Not connected to game.");
		}

		// Already paused
		if (isPaused())
			return;

		publish("pause", null);
		onPaused();
	}

	protected void onPaused() {
		// Update game state
		setGameState(GameState.PAUSED);
		// Call handler
		handler.gamePaused();
	}

	public void updatePosition(double x, double y, double angle)
			throws IOException {
		Map<String, Object> message = new HashMap<String, Object>();
		message.put("x", x);
		message.put("y", y);
		message.put("angle", angle);
		publish("position", message);
		// TODO Wait for result
	}

	/*
	 * Setup/shutdown
	 */

	private void setup() throws IOException {
		// Reset game
		resetGame();
		// Declare exchange
		channel.exchangeDeclare(getGameID(), "topic");
	}

	private void setupPublic() throws IOException {
		// Declare and bind
		publicQueue = channel.queueDeclare().getQueue();
		channel.queueBind(publicQueue, getGameID(), "*");

		// Attach consumers
		channel.basicConsume(publicQueue, new JoinLeaveHandler(channel));
	}

	private void shutdownPublic() throws IOException {
		// Delete queue (also cancels attached consumers)
		channel.queueDelete(publicQueue);
	}

	private void setupTeam(int teamId) throws IOException {
		// Declare and bind
		teamQueue = channel.queueDeclare().getQueue();
		channel.queueBind(teamQueue, getGameID(), "team." + teamId + ".*");

		// Attach consumers
	}

	private void shutdownTeam() throws IOException {
		// Delete queue (also cancels attached consumers)
		channel.queueDelete(teamQueue);
	}

	private void shutdown() {
		try {
			// Close channel
			this.channel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void resetGame() {
		// Reset game state
		setGameState(GameState.DISCONNECTED);

		// Only retain local player
		synchronized (players) {
			PlayerInfo localPlayer = getLocalPlayer();
			players.clear();
			players.put(getPlayerID(), localPlayer);
		}
	}

	/*
	 * Helpers
	 */

	private void publish(String routingKey, Map<String, Object> message,
			BasicProperties props) throws IOException {
		// Default to empty message
		if (message == null) {
			message = new HashMap<String, Object>();
		}

		// Add player ID to message
		message.put("playerID", getPlayerID());

		// Serialize map as JSON object
		String jsonMessage = new JSONWriter().write(message);

		// Publish message
		channel.basicPublish(getGameID(), routingKey, props,
				jsonMessage.getBytes());
	}

	private void publish(String routingKey, Map<String, Object> message)
			throws IOException {
		publish(routingKey, message, defaultProps().build());
	}

	private AMQP.BasicProperties.Builder defaultProps() {
		return new AMQP.BasicProperties.Builder().timestamp(new Date())
				.contentType("text/plain").deliveryMode(1);
	}

	private void consume(String queue, Consumer consumer, boolean autoAck)
			throws IOException {
		channel.basicConsume(queue, autoAck, "", true, false, null, consumer);
	}

	private void consume(String queue, Consumer consumer) throws IOException {
		consume(queue, consumer, true);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseMessage(byte[] body) {
		return (Map<String, Object>) new JSONReader().read(new String(body));
	}

	/**
	 * Requests a join and handles the responses.
	 */
	private class JoinRequester extends DefaultConsumer {

		private final Callback<Void> callback;
		private String replyQueue;
		private volatile boolean isDone = false;

		public JoinRequester(Callback<Void> callback) {
			super(channel);
			this.callback = callback;
		}

		public void request(int timeout) throws IOException {
			// Declare reply consumer
			replyQueue = channel.queueDeclare().getQueue();
			consume(replyQueue, this);

			// Publish "join" with local player info
			PlayerInfo playerInfo = getLocalPlayer();
			Map<String, Object> message = new HashMap<String, Object>();
			message.put("isReady", playerInfo.isReady());

			AMQP.BasicProperties props = defaultProps()
					.expiration(timeout + "").replyTo(replyQueue).build();

			publish("join", message, props);

			// Report success after timeout
			Executors.newSingleThreadScheduledExecutor().schedule(
					new Runnable() {
						@Override
						public void run() {
							if (!isDone) {
								success();
							}
						}
					}, timeout, TimeUnit.MILLISECONDS);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope,
				BasicProperties props, byte[] body) throws IOException {
			String topic = envelope.getRoutingKey();
			Map<String, Object> message = parseMessage(body);

			if (!isDone) {
				if (topic.equals("accept")) {
					// Accepted by peer
					String playerID = (String) message.get("playerID");
					Boolean isReady = (Boolean) message.get("isReady");
					GameState gameState = GameState.valueOf((String) message
							.get("gameState"));
					// Store state
					setPlayer(playerID, isReady);
					setGameState(gameState);
					if (isFull()) {
						// All players registered
						success();
					}
				} else if (topic.equals("reject")) {
					// Rejected by peer
					failure();
				}
			}
		}

		private void success() {
			try {
				// Mark as done
				isDone = true;
				// Setup
				joined();
				// Report success
				callback.onSuccess(null);
			} catch (IOException e) {
				callback.onFailure(e);
			}
		}

		private void failure() {
			try {
				// Mark as done
				isDone = true;
				// Stop listening
				channel.queueDelete(replyQueue);
				// Report failure
				callback.onFailure(new Exception("Request to join rejected"));
				// Leave
				leave();
			} catch (IOException e) {
				callback.onFailure(e);
			}
		}

	}

	/**
	 * Handles join requests
	 */
	private class JoinLeaveHandler extends DefaultConsumer {

		public JoinLeaveHandler(Channel channel) {
			super(channel);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope,
				BasicProperties props, byte[] body) throws IOException {
			String topic = envelope.getRoutingKey();
			Map<String, Object> message = parseMessage(body);

			if (topic.equals("join")) {
				// Read player info
				String playerID = (String) message.get("playerID");
				Boolean isReady = (Boolean) message.get("isReady");

				// Prepare reply
				String replyTopic;
				PlayerInfo playerInfo = getLocalPlayer();
				Map<String, Object> reply = new HashMap<String, Object>();
				reply.put("playerID", playerInfo.getPlayerID());

				AMQP.BasicProperties replyProps = defaultProps().replyTo(
						props.getReplyTo()).build();

				if (canJoin(playerID)) {
					// Accept
					replyTopic = "accept";
					setPlayer(playerID, isReady);
					// Report state
					reply.put("isReady", playerInfo.isReady());
					reply.put("gameState", getGameState().name());
				} else {
					// Reject
					replyTopic = "reject";
				}

				// Send reply
				publish(replyTopic, reply, replyProps);
			} else if (topic.equals("leave")) {
				// Read player info
				String playerID = (String) message.get("playerID");
				// Handle player left
				playerLeft(playerID);
			}
		}

	}

}
