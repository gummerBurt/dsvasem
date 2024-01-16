package raft;

import lombok.Getter;
import lombok.Setter;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.jms.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Node {
    private static final Logger log = LogManager.getLogger(Node.class);

    public static Node thisNode = null;

    private final String broker1IpAddress;

    private final String broker2IpAddress;
    @Getter
    @Setter
    private String nodeNickname = "Unknown";
    @Getter
    @Setter
    private NodeState nodeState = NodeState.FOLLOWER;
    @Getter
    @Setter
    private int term = 0;
    private ConnectionFactory connectionFactory;
    private Connection connection;
    private Session session;
    private Topic leaderTopic;
    private Topic candidateTopic;
    private HashMap<String, MessageProducer> topicProducerMap = new HashMap<>();
    private HashMap<String, MessageConsumer> topicConsumerMap = new HashMap<>();
    @Getter
    @Setter
    private Instant lastHeartbeatReceivedTime;
    private ScheduledExecutorService heartbeatMonitorExecutor;
    private ScheduledExecutorService heartbeatExecutor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> electionTimeout;
    @Getter
    @Setter
    private boolean hasVoted = false;
    @Getter
    @Setter
    private HashMap<String, Integer> nodes = new HashMap<>();
    @Getter
    @Setter
    private boolean hasLeader;
    @Getter
    @Setter
    private int nodesCount = 1;
    @Getter
    @Setter
    private List<Log> nodeLogs = new ArrayList<>();
    @Getter
    @Setter
    private Integer commitIndex = 0;


    public Node(String[] args) {
        if(args.length!=3 && args.length!=4){
            log.error("Configuration must have 3 parameters: <nodeNickname> <firstBrokerIpAddress> <secondBrokerIpAddress>");
            System.exit(1);
        }
        nodeNickname = args[0];
        broker1IpAddress = args[1];
        broker2IpAddress = args[2];
        initialize();
        lastHeartbeatReceivedTime = Instant.now();
    }

    public static void main(String[] args) {
        thisNode = new Node(args);
        Thread consoleListenerThread = new Thread(Node::handleInputCommand);
        consoleListenerThread.start();
    }

    private static void handleInputCommand() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (!scanner.hasNextLine()) {
                    continue;
                }
                String input = scanner.nextLine();
                if (input.startsWith("-m")) {
                    if(thisNode.nodeState==NodeState.LEADER) {
                        thisNode.handleClientMessage(input.split(":")[1]);
                    }else{
                        log.error("This node is not leader. It can not send messages to chat");
                    }
                }else if(input.startsWith("-logout")){
                    thisNode.logout();
                }
            }
        }
    }

    public void initialize(){
        try{
            connectionFactory = new ActiveMQConnectionFactory("failover:(tcp://"+broker1IpAddress+":61616,tcp://"+broker2IpAddress+":61616)");
            connection = connectionFactory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            leaderTopic = session.createTopic(Constants.LEADER_TOPIC);
            candidateTopic = session.createTopic(Constants.CANDIDATE_TOPIC);

            topicProducerMap.put(Constants.LEADER_TOPIC, session.createProducer(leaderTopic));
            topicProducerMap.put(Constants.CANDIDATE_TOPIC, session.createProducer(candidateTopic));

            MessageConsumer appendEntriesConsumer = session.createConsumer(leaderTopic);
            topicConsumerMap.put(Constants.LEADER_TOPIC, appendEntriesConsumer);
            AppendEntriesListener appendEntriesListener = new AppendEntriesListener(this);
            appendEntriesConsumer.setMessageListener(appendEntriesListener);

            MessageConsumer requestVoteConsumer = session.createConsumer(candidateTopic);
            topicConsumerMap.put(Constants.CANDIDATE_TOPIC, requestVoteConsumer);
            RequestVoteListener requestVoteListener = new RequestVoteListener(this);
            requestVoteConsumer.setMessageListener(requestVoteListener);

            setupHeartbeatMonitoring();
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    void setupHeartbeatMonitoring() {
//        System.out.println("heartbeat monitor setup");
        heartbeatMonitorExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatMonitorExecutor.scheduleAtFixedRate(this::checkHeartbeats, 0, 1, TimeUnit.SECONDS);
    }

    void stopHeartbeatMonitoring() {
//        System.out.println("heartbeat monitor stopped");
        if (heartbeatMonitorExecutor != null) {
            heartbeatMonitorExecutor.shutdown();
        }
    }

    private void checkHeartbeats() {
        if(nodeState!=NodeState.LEADER && nodeState!=NodeState.CANDIDATE) {
            if (Duration.between(lastHeartbeatReceivedTime, Instant.now()).getSeconds() > 5) {
                log.info("Leader do not respond. Starting election timeout");
                nodesCount--;
                if(nodesCount==1){
                    stopHeartbeatMonitoring();
                    term++;
                    hasLeader = false;
                    hasVoted = false;
                    becomeLeader();
                    return;
                }
                hasLeader = false;
                hasVoted = false;
                startElectionTimeout();
            }
        }
    }

    public void startElectionTimeout(){
        stopHeartbeatMonitoring();
        if (electionTimeout != null && !electionTimeout.isDone()) {
            electionTimeout.cancel(true);
        }

        Random random = new Random();
        int electionTimeoutDuration = 150 + random.nextInt(300 - 150 + 1);

        electionTimeout = scheduler.schedule(this::startElection, electionTimeoutDuration, TimeUnit.MILLISECONDS);

    }

    public void stopElectionTimeout() {
        if (electionTimeout != null && !electionTimeout.isDone()) {
            electionTimeout.cancel(true);
        }
    }

    private void startElection() {
        if(nodeState!=NodeState.LEADER) {
            hasLeader = false;
            term++;
            nodeState = NodeState.CANDIDATE;
            log.info("Election timeout. Starting election process... Term is " + term);
            if (term == 1) {
                if (nodes.isEmpty()) {
                    nodesCount=1;
                    becomeLeader();
                    return;
                }
            } else if (term!=1) {
                if(nodesCount==1){
                    becomeLeader();
                    return;
                }
            }
            hasVoted = true;
            startElectionTimeout();
            sendRequestVoteMessage();
        }
    }

    public void sendRequestVoteMessage(){
        try {
            TextMessage message = session.createTextMessage("REQUEST;" + nodeNickname+";"+term);
            topicProducerMap.get(Constants.CANDIDATE_TOPIC).send(message);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void sendRequestVoteAnswer(String name){
        boolean answer = false;
        hasLeader = false;
        if(!hasVoted){
            hasVoted = true;
            answer = true;
        }
//        System.out.println("has voted " + hasVoted + ", answer: " + answer);
        try {
            TextMessage message = session.createTextMessage("ANSWER;" + nodeNickname + ";"+answer+";"+name);
            topicProducerMap.get(Constants.CANDIDATE_TOPIC).send(message);
            startElectionTimeout();
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void becomeLeader(){
        log.info(nodeNickname+" becomes leader");
        nodeState = NodeState.LEADER;
        hasVoted = false;
        nodes.clear();
        stopElectionTimeout();
        stopHeartbeatMonitoring();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(this::sendAppendEntryMessage, 0, 5, TimeUnit.SECONDS);
        hasLeader = true;
    }

    //Heartbeat
    public void sendAppendEntryMessage(){
        try{
            List<String> lostNodes = new ArrayList<>();
            nodes.entrySet().forEach((entry)->{
                if(entry.getValue()>=3){
                    lostNodes.add(entry.getKey());
                    nodesCount--;
                    log.info("Node " + entry.getKey() + " didn't respond for 3 times, updating nodes count");
                }
                entry.setValue(entry.getValue()+1);
            });
            boolean lost = false;
            for(String s: lostNodes){
                nodes.remove(s);
                lost = true;
            }
            TextMessage message = session.createTextMessage("HEARTBEAT;"+nodeNickname+";"+term+";"+nodesCount);
//            System.out.println("here");
            topicProducerMap.get(Constants.LEADER_TOPIC).send(message);
            if(lost && commitIndex<nodeLogs.size()){
                sendLogEntryMessage(nodeLogs.get(nodeLogs.size()-1), "REPEAT");
            }
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void confirmNewLeader(String name){
        log.info(nodeNickname+ " confirms new leader");
        stopElectionTimeout();
        nodeState = NodeState.FOLLOWER;
        hasLeader = true;
        lastHeartbeatReceivedTime = Instant.now();
        setupHeartbeatMonitoring();
        hasVoted = false;
        sendAppendEntryAnswer("true", name);
    }

    public void sendAppendEntryAnswer(String text, String name){
        try{
            TextMessage message = session.createTextMessage("ANSWER;"+nodeNickname+";"+text+";"+name);
            topicProducerMap.get(Constants.LEADER_TOPIC).send(message);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void handleClientMessage(String text){
        Log l = new Log(term, text, LocalDateTime.now().toString());
        nodeLogs.add(l);
        if(nodesCount>1) {
            sendLogEntryMessage(l, "LOGENTRY");
        }else{
            log.info(nodeNickname + " log is replicated, committing log");
            System.out.println("Message: "+l.getAction());
            log.info("LOG "+nodeNickname+". DATE: " + nodeLogs.get(commitIndex).getDate() + "; TERM: " + nodeLogs.get(commitIndex).getTerm() + "; MESSAGE: " + nodeLogs.get(commitIndex).getAction());
            commitIndex++;
            log.info("log is committed, commit index = " + commitIndex);
        }
    }

    public void sendLogEntryMessage(Log l, String action){
        try{
            TextMessage message = session.createTextMessage(action+";"+nodeNickname+";"+l.getTerm()+";"+l.getAction()+";"+l.getDate());
            topicProducerMap.get(Constants.LEADER_TOPIC).send(message);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void gotLogFromLeader(String messageText){
        if(nodeState==NodeState.FOLLOWER) {
            Log l = new Log(Integer.parseInt(messageText.split(";")[2]), messageText.split(";")[3], messageText.split(";")[4]);
            nodeLogs.add(l);
            sendLogEntryAnswer("REPLICATED", "true");
        }
    }

    public void sendLogEntryAnswer(String action, String value){
        try{
            TextMessage message = session.createTextMessage(action+";"+nodeNickname+";"+value);
            topicProducerMap.get(Constants.LEADER_TOPIC).send(message);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void sendLogEntryCommit(){
        try{
            TextMessage message = session.createTextMessage("LOGCOMMIT;"+nodeNickname+";"+commitIndex);
            topicProducerMap.get(Constants.LEADER_TOPIC).send(message);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }

    public void logout(){
        try {
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdown();
            }
            stopHeartbeatMonitoring();
            stopElectionTimeout();
            topicConsumerMap.entrySet().forEach((entry) ->
            {
                try {
                    entry.getValue().close();
                } catch (JMSException e) {
                    e.printStackTrace();
                }
            });
            topicProducerMap.entrySet().forEach((entry) ->
            {
                try {
                    entry.getValue().close();
                } catch (JMSException e) {
                    e.printStackTrace();
                }
            });
            session.close();
            connection.close();
            System.exit(0);
        }catch (JMSException e){
            e.printStackTrace();
        }
    }
}
