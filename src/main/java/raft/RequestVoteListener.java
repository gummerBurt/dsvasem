package raft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

public class RequestVoteListener implements MessageListener {
    private static final Logger log = LogManager.getLogger(RequestVoteListener.class);

    private final Node node;
    private int votes = 1;
    private int againstVotes = 0;
    private int term = 0;

    public RequestVoteListener(Node node) {
        this.node = node;
    }

    @Override
    public void onMessage(Message message) {
        if (message instanceof TextMessage) {
            try {
                String messageText = ((TextMessage) message).getText();
                if(messageText.startsWith("ANSWER") && messageText.split(";")[3].equals(node.getNodeNickname()) && !messageText.split(";")[1].equals(node.getNodeNickname())) {
                    log.info("Request vote message: "+messageText);
                        node.startElectionTimeout();
                        if (term != node.getTerm()) {
                            votes = 1;
                            againstVotes = 0;
                            term = node.getTerm();
                        }
                        if (messageText.split(";")[2].equals("true")) {
                            votes++;
                        } else if (messageText.split(";")[2].equals("false")) {
                            againstVotes++;
                        }
//                    System.out.println("Nodes "+node.getNodesCount() + ", votes: " + votes);
                        if(node.getNodeState()!=NodeState.LEADER) {
                            if (votes > (node.getNodesCount() / 2)) {
//                                System.out.println(node.getNodeNickname() + " become leader in listener");
                                node.becomeLeader();
                                votes = 1;
                                term = node.getTerm();
                            } else if (againstVotes + votes == node.getNodesCount()) {
                                votes = 1;
                                againstVotes = 0;
                                node.setNodeState(NodeState.FOLLOWER);
                                node.setHasVoted(false);
                                node.setHasLeader(false);
                            }
                        }
                } else if (messageText.startsWith("REQUEST")) {
                    if(!messageText.split(";")[1].equals(node.getNodeNickname())) {
                        log.info("Request vote message: "+messageText);
                        node.startElectionTimeout();
                        node.sendRequestVoteAnswer(messageText.split(";")[1]);
                        node.setTerm(Integer.parseInt(messageText.split(";")[2]));
                    }
                }
            } catch (JMSException e) {
                e.printStackTrace();
            }

        }
    }
}
