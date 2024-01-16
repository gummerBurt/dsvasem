package raft;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

public class Log {
    @Getter
    @Setter
    private Integer term;
    @Getter
    @Setter
    private String action;
    @Getter
    @Setter
    private String date;

    public Log(Integer term, String action, String date) {
        this.term = term;
        this.action = action;
        this.date = date;
    }
}
