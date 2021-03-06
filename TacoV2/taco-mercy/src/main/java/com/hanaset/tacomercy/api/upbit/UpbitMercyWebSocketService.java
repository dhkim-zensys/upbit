package com.hanaset.tacomercy.api.upbit;

import com.hanaset.tacocommon.api.upbit.model.body.UpbitWebSocketTicket;
import com.hanaset.tacocommon.api.upbit.model.body.UpbitWebSocketType;
import com.hanaset.tacomercy.service.UpbitMercyMarketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Service
public class UpbitMercyWebSocketService {

    private final UpbitMercyWebSocketClient upbitMercyWebSocketClient;
    private final UpbitMercyMarketService upbitMercyMarketService;


    public UpbitMercyWebSocketService(UpbitMercyWebSocketClient upbitMercyWebSocketClient,
                                      UpbitMercyMarketService upbitMercyMarketService) {
        this.upbitMercyWebSocketClient = upbitMercyWebSocketClient;
        this.upbitMercyMarketService = upbitMercyMarketService;
    }

    @PostConstruct
    public void orderbook_Connect() {

        List<String> pairs = upbitMercyMarketService.getPairs();
        if (pairs.isEmpty())
            pairs = upbitMercyMarketService.initPairs();

        System.out.println(pairs);

        log.info("<======================== WebSocket Connceting =======================>");

        UpbitWebSocketTicket upbitWebSocketTicket = UpbitWebSocketTicket.builder()
                .ticket("UPBIT_ORDERBOOK")
                .build();

        UpbitWebSocketType upbitWebSocketType = UpbitWebSocketType.builder()
                .type("orderbook")
                .codes(pairs)
                .build();

        upbitMercyWebSocketClient.connect(upbitWebSocketTicket, upbitWebSocketType);
    }
}
