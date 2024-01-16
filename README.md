# Semestrální práce z předmětu DSVA
## Chat
Distribuovaná chatovací aplikace, využívající Java JMS, ActiveMQ a Raft Consensus Algorithm.

## Spuštění
Pro spuštění je nutné připravit dva brokery ActiveMQ. Program používá JDK 17, ActiveMQ 6.0.1 a Maven 3.9.6.

V konfiguračním souboru brokera /conf/activemq.xml musíme nastavit unikátní jméno pro každého brokera:
```brokerName="broker1"```

Dále v souboru s nastavením brokera musíme přidat <networkConnectors>, aby každý z brokerů znal IP adresu druhého brokera:
```agsl
<networkConnectors>
       <networkConnector name="bridge" uri="static:(tcp://<ip_adresa_jineho_brokeru>:61616)" duplex="false"/>
</networkConnectors>
```

Poté je nutné spustit brokery. V adresáři brokera otevřeme terminál a zadáme příkaz: ```./bin/activemq console```

V adresáři s projektem otevřeme terminál a spustíme uzel příkazem: ```mvn exec:java -Dexec.args="<unikatni nazev uzlu> <ip adresa prvniho brokeru> <ip adresa druheho brokeru>"```

Příkazy, které podporují jednotlivé uzly:

```-m:<zprava>``` -  funguje, pokud je uzel vůdcem; tento příkaz odešle novou AppendEntries zprávu, kterou ostatní uzly musí replikovat a následně provést commit.

```-logout``` - příkaz slouží k ukončení běhu uzlu.

## Raft consensus algorithm

### Volba vůdce
Když v průběhu běhu programu aktuální vůdce zemře, ostatní uzly nedostanou od vůdce Heartbeat zprávu. Pokud čas čekání na tuto zprávu přesáhne 5 sekund, aktivuje se electionTimeout. ElectionTimeout znamená náhodný čas mezi 150 a 300 ms, během kterého uzel musí vyčkat, než může změnit stav z FOLLOWER na CANDIDATE. Jakmile uzel nastaví se jako kandidát, začne posílat ostatním uzlům RequestVote zprávy, a uzly provádějí hlasování. Pokud kandidát získá většinu hlasů od ostatních uzlů, stane se novým vůdcem.
### Replikace logu
Když vůdce dostane od uživatele zprávu přes příkazovou řádku, nejprve ji zapíše do seznamu svých logů, kde jsou uvedene text zprávy, aktuální termín a čas vzniku logu. Poté začne posílat ostatním uzlům zprávu o replikaci nového logu. Uzly odpovídají vůdci, a pokud většina uzlů replikuje tento log, vůdce ho potvrdí jako commit. Následně pošle ostatním uzlům zprávu, že je nutné tento log potvrdit jako commit.

Pokud se do chatu přidá nový uzel ve stavu FOLLOWER, vůdce zopakuje pro něj všechny předchozí logy, aby byl systém stále v souladu.
### Logování
Logy jsou vypisovány do konzole a zároveň jsou zaznamenávány do souboru ```/logs/log.log```.