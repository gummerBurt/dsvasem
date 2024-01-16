package raft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.time.Instant;

public class AppendEntriesListener implements MessageListener {
    private static final Logger log = LogManager.getLogger(AppendEntriesListener.class);
    private final Node node;
    private int votes = 1;
    private int count = 0;

    public AppendEntriesListener(Node node) {
        this.node = node;
    }

    @Override
    public void onMessage(Message message) {
        if (message instanceof TextMessage) {
            try {
                String messageText = ((TextMessage) message).getText();

                if(messageText.startsWith("HEARTBEAT")) {
                    log.info("Append entries message: "+messageText);
                    if(Integer.parseInt(messageText.split(";")[3])!=node.getNodesCount()){
                        node.setNodesCount(Integer.parseInt(messageText.split(";")[3]));
                    }
                    if (!node.isHasLeader()) {
                        node.setTerm(Integer.parseInt(messageText.split(";")[2]));
                        node.confirmNewLeader(messageText.split(";")[1]);
                        node.stopElectionTimeout();
                    }else{
                        node.setLastHeartbeatReceivedTime(Instant.now());
                        node.sendAppendEntryAnswer("true", messageText.split(";")[1]);
                        node.stopElectionTimeout();
                    }
                }else if(messageText.startsWith("ANSWER") && messageText.split(";")[3].equals(node.getNodeNickname()) && !messageText.split(";")[1].equals(node.getNodeNickname())){
//                    log.info("Append entries message: "+messageText);
                    if (!node.getNodes().containsKey(messageText.split(";")[1])) {
                        node.getNodes().put(messageText.split(";")[1], 0);
                        count = 0;
                        if(!node.getNodeLogs().isEmpty()){
                            node.sendLogEntryMessage(node.getNodeLogs().get(count), "REPEAT");
                            count++;
                        }
                    }else{
                        node.getNodes().replace(messageText.split(";")[1], 0);
                        count=node.getNodeLogs().size();
                    }
                    if(node.getNodesCount()+1!=node.getNodes().size()){
                        node.setNodesCount(node.getNodes().size()+1);
                    }

                } else if (messageText.startsWith("LOGENTRY") && node.getNodeState()!=NodeState.LEADER) {
                    log.info("Append entries message: "+messageText);
                    node.gotLogFromLeader(messageText);
                } else if (messageText.startsWith("REPLICATED") && node.getNodeState()==NodeState.LEADER && !messageText.split(";")[1].equals(node.getNodeNickname())) {
//                    System.out.println("count = " + count + ", logs size = " + node.getNodeLogs().size());
                    if(node.getNodeLogs().size()!=node.getCommitIndex()) {
                        log.info("Append entries message: "+messageText);
                        if (messageText.split(";")[2].equals("true")) {
                            votes++;
                        }
                        if (votes > (node.getNodesCount() / 2)) {
                            log.info(node.getNodeNickname() + " log is replicated, committing log");
                            votes = 1;
                            System.out.println("Message: " + node.getNodeLogs().get(node.getCommitIndex()).getAction());
                            log.info("LOG "+node.getNodeNickname()+". DATE: " + node.getNodeLogs().get(node.getCommitIndex()).getDate() + "; TERM: " + node.getNodeLogs().get(node.getCommitIndex()).getTerm() + "; MESSAGE: " + node.getNodeLogs().get(node.getCommitIndex()).getAction());
                            node.setCommitIndex(node.getCommitIndex() + 1);
                            log.info("log is committed, commit index = " + node.getCommitIndex());
                            node.sendLogEntryCommit();
                        }
                    }else{
                        if(count!=node.getNodeLogs().size()){
                            node.sendLogEntryMessage(node.getNodeLogs().get(count), "REPEAT");
//                            System.out.println("here repeat 1");
                            count++;
                        }
                    }
                } else if (messageText.startsWith("REPEAT")) {
                    if(node.getNodeState()!=NodeState.LEADER) {

                        boolean contains = false;
                        for (Log l : node.getNodeLogs()) {
                            if (l.getDate().equals(messageText.split(";")[4])) {
                                contains = true;
                            }
                        }
                        if (!contains) {
//                            System.out.println("R: " + messageText);
                            log.info("Append entries message: "+messageText);
                            node.gotLogFromLeader(messageText);
                            node.setCommitIndex(node.getNodeLogs().size());
                            log.info("Logs count = " + node.getNodeLogs().size() + " commit index = " + node.getCommitIndex());

                        }else{
                            node.sendLogEntryAnswer("REPLICATED", "true");
                        }
                    }else{
                        votes=1;
                    }
                } else if (messageText.startsWith("LOGCOMMIT") && node.getNodeState()!=NodeState.LEADER) {
                    log.info("Append entries message: "+messageText);
                    System.out.println("Message: "+node.getNodeLogs().get(node.getCommitIndex()).getAction());
                    log.info("LOG "+node.getNodeNickname()+". DATE: " + node.getNodeLogs().get(node.getCommitIndex()).getDate() + "; TERM: " + node.getNodeLogs().get(node.getCommitIndex()).getTerm() + "; MESSAGE: " + node.getNodeLogs().get(node.getCommitIndex()).getAction());
                    node.setCommitIndex(Integer.parseInt(messageText.split(";")[2]));
                    log.info("log is committed, commit index = " + node.getCommitIndex());
                    node.sendLogEntryAnswer("COMMITED", "true");
                }
            } catch (JMSException e) {
                e.printStackTrace();
            }
        }
    }
}
