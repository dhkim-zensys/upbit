package com.hanaset.taco.service.upbit;

import com.hanaset.taco.api.upbit.UpbitApiRestClient;
import com.hanaset.taco.api.upbit.model.*;
import com.hanaset.taco.cache.OrderbookCached;
import com.hanaset.taco.cache.UpbitTransactionCached;
import com.hanaset.taco.utils.SleepHelper;
import com.hanaset.taco.utils.Taco2CurrencyConvert;
import com.hanaset.taco.utils.TacoPercentChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;

@Service
@SuppressWarnings("Duplicates")
public class UpbitTransactionService {

    private Logger log = LoggerFactory.getLogger("upbit_askbid");

    private final UpbitApiRestClient upbitApiRestClient;
    private final UpbitBalanceService upbitBalanceService;
    private final Double profit = 0.4;

    public UpbitTransactionService(UpbitApiRestClient upbitApiRestClient,
                                   UpbitBalanceService upbitBalanceService) {
        this.upbitApiRestClient = upbitApiRestClient;
        this.upbitBalanceService = upbitBalanceService;
    }

    @Async
    public void checkProfit(String pair) {

        if (UpbitTransactionCached.LOCK) {
            return;
        }

        try {
            UpbitOrderbookItem btcItem = OrderbookCached.UPBIT.getOrDefault("BTC-" + pair, null);
            UpbitOrderbookItem krwItem = OrderbookCached.UPBIT.getOrDefault("KRW-" + pair, null);

            if (btcItem == null || krwItem == null)
                return;

            if (TacoPercentChecker.profitCheck(Taco2CurrencyConvert.convertBidBTC2KRW(btcItem.getBid_price()), krwItem.getAsk_price(), profit)) {

                Double base_amount = btcItem.getBid_size() > krwItem.getAsk_size() ? krwItem.getAsk_size() : btcItem.getBid_size();
                Double amount = base_amount / 10.f;

                if (amount * btcItem.getBid_price() <= 0.0005 || amount * krwItem.getAsk_price() <= 10000) {
                    return;
                }

                UpbitTransactionCached.LOCK = true;

                log.info("==================================================================");

                log.info("[{}] [BTC Bid : {}({})/{}] [KRW Ask : {}/{}] [profit : {}] [percent : {}]",
                        pair,
                        Taco2CurrencyConvert.convertBidBTC2KRW(btcItem.getBid_price()), BigDecimal.valueOf(btcItem.getBid_price()).toPlainString(), btcItem.getBid_size(),
                        krwItem.getAsk_price(), krwItem.getAsk_size(),
                        Taco2CurrencyConvert.convertBidBTC2KRW(btcItem.getBid_price()) - krwItem.getAsk_price(),
                        (Taco2CurrencyConvert.convertBidBTC2KRW(btcItem.getBid_price()) - krwItem.getAsk_price()) / krwItem.getAsk_price() * 100);

                Response<UpbitOrderResponse> bidResponse = biding(krwItem, BigDecimal.valueOf(amount), "KRW-" + pair);

                if (bidResponse.isSuccessful()) {
                    log.info("??????:{}", bidResponse.body().toString());

                    UpbitTicket ticket = UpbitTicket.builder()
                            .uuid(bidResponse.body().getUuid())
                            .market(pair)
                            .bid_market(bidResponse.body().getMarket())
                            .ask_market("BTC-" + pair)
                            .bidOrderbookItem(krwItem)
                            .askOrderbookItem(btcItem)
                            .amount(BigDecimal.valueOf(amount))
                            .ask_amount(BigDecimal.valueOf(krwItem.getAsk_size()))
                            .bid_amount(BigDecimal.valueOf(btcItem.getBid_size()))
                            .build();

                    UpbitTransactionCached.TICKET = ticket;

                } else {
                    log.error("?????? ??????:{}", bidResponse.errorBody().byteString().toString());
                    UpbitTransactionCached.LOCK = false;
                }

            } else if (TacoPercentChecker.profitCheck(krwItem.getBid_price(), Taco2CurrencyConvert.convertAskBTC2KRW(btcItem.getAsk_price()), profit)) {

                Double base_amount = krwItem.getBid_size() > btcItem.getAsk_size() ? btcItem.getAsk_size() : krwItem.getBid_size();
                Double amount = base_amount / 10.f;

                if (amount * btcItem.getAsk_price() <= 0.0005 || amount * krwItem.getBid_price() <= 10000) {
                    return;
                }

                UpbitTransactionCached.LOCK = true;

                log.info("==================================================================");

                log.info("[{}] [KRW Bid : {}/{}] [BTC Ask : {}({})/{}] [profit : {}] [percent : {}]",
                        pair,
                        krwItem.getBid_price(), krwItem.getBid_size(),
                        Taco2CurrencyConvert.convertAskBTC2KRW(btcItem.getAsk_price()), BigDecimal.valueOf(btcItem.getAsk_price()).toPlainString(), btcItem.getAsk_size(),
                        krwItem.getBid_price() - Taco2CurrencyConvert.convertAskBTC2KRW(btcItem.getAsk_price()),
                        (krwItem.getBid_price() - Taco2CurrencyConvert.convertAskBTC2KRW(btcItem.getAsk_price())) / Taco2CurrencyConvert.convertAskBTC2KRW(btcItem.getAsk_price()) * 100);


                Response<UpbitOrderResponse> bidResponse = biding(btcItem, BigDecimal.valueOf(amount), "BTC-" + pair);


                if (bidResponse.isSuccessful()) {
                    log.info("??????:{}", bidResponse.body().toString());

                    UpbitTicket ticket = UpbitTicket.builder()
                            .uuid(bidResponse.body().getUuid())
                            .market(pair)
                            .bid_market(bidResponse.body().getMarket())
                            .ask_market("KRW-" + pair)
                            .bidOrderbookItem(btcItem)
                            .askOrderbookItem(krwItem)
                            .amount(BigDecimal.valueOf(amount))
                            .ask_amount(BigDecimal.valueOf(btcItem.getAsk_size()))
                            .bid_amount(BigDecimal.valueOf(krwItem.getBid_size()))
                            .build();

                    UpbitTransactionCached.TICKET = ticket;

                } else {
                    log.error("?????? ??????:{}", bidResponse.errorBody().byteString().toString());
                    UpbitTransactionCached.LOCK = false;
                }
            }

        } catch (Exception e) {
            log.error("[{}] Upbit Data error -> {}", pair, e.getMessage());
            UpbitTransactionCached.LOCK = false;
        }
    }

    public void orderProfit(UpbitTrade upbitTrade) {

        if (UpbitTransactionCached.TICKET == null || upbitTrade == null)
            return;

        if(UpbitTransactionCached.LOCK == false) { // ?????? ?????? ??? ?????? ???????????? ??????
            return;
        }

        if (UpbitTransactionCached.TICKET.getBid_market().equals(upbitTrade.getCode()) &&
                upbitTrade.getAsk_bid().equals("BID")) {
            bidProfit(upbitTrade);
        } else if (UpbitTransactionCached.TICKET.getAsk_market().equals(upbitTrade.getCode()) &&
                upbitTrade.getAsk_bid().equals("ASK")) {
            askProfit(upbitTrade);
        }

    }

    @Async
    public void bidProfit(UpbitTrade upbitTrade) {

        UpbitTicket ticket = UpbitTransactionCached.TICKET;

        if (upbitTrade.getTrade_price().compareTo(BigDecimal.valueOf(ticket.getBidOrderbookItem().getAsk_price())) != 0)
            return;

        System.out.println(upbitTrade);

        BigDecimal myBalance = upbitBalanceService.getUpbitMarketAccount(ticket.getMarket());

        try {
            Response<UpbitOrderResponse> askResponse = asking(ticket.getAskOrderbookItem(), myBalance, ticket.getAsk_market());

            if (askResponse.isSuccessful()) {
                log.info("??????:{}", askResponse.body());

                SleepHelper.Sleep(3000);
                UpbitTransactionCached.reset();

            } else {
                log.error("?????? ??????: {}", askResponse.errorBody().byteString().toString());
                UpbitTransactionCached.COUNT++;
            }
        } catch (IOException e) {
            log.error("IOException: {}", e.getMessage());
        }

        if (myBalance.compareTo(BigDecimal.ZERO) == 0) { // ??? ????????? ???????????? ??????

            System.out.println("?????? ??????");
            try {
                    Response<UpbitOrderResponse> deleteResponse = orderDeleting(ticket.getUuid());

                    if (deleteResponse.isSuccessful()) {
                        log.info("?????? ??????: {}", deleteResponse.body().toString());
                    } else {
                        log.error("?????? ?????? ?????? :{}", deleteResponse.errorBody().byteString().toString());
                    }
                } catch (IOException e) {
                    log.error("?????? ?????? IOException: {}", e.getMessage());
                }
                UpbitTransactionCached.reset();

//            if (enableAmount.compareTo(ticket.getAmount()) < 0) { // ?????? ??????
//                // ?????? ?????? ?????? - ????????? ????????? ?????? >= ?????? ????????? ?????? (?????? ??????)
//                // ?????? ?????? ?????? - ????????? ????????? ?????? < ?????? ????????? ?????? (??????) -> ?????? ??????
//
//                try {
//                    Response<UpbitOrderResponse> deleteResponse = orderDeleting(ticket.getUuid());
//
//                    if (deleteResponse.isSuccessful()) {
//                        log.info("?????? ??????: {}", deleteResponse.body().toString());
//                    } else {
//                        log.error("?????? ?????? ?????? :{}", deleteResponse.errorBody().byteString().toString());
//                    }
//                } catch (IOException e) {
//                    log.error("?????? ?????? IOException: {}", e.getMessage());
//                }
//                UpbitTransactionCached.reset();
//            } else {
//                ticket.setAsk_amount(enableAmount);
//                // ?????? ????????? ????????? ?????? ????????? ?????? ????????? ?????? ?????? ??? ??? ????????? ??????
//            }
////            return;
        }
    }

    public void exchangeProfit() {

        BigDecimal myBalance = upbitBalanceService.getUpbitMarketAccount("BTC");

        UpbitOrderbookItem converItem = new UpbitOrderbookItem();
        converItem.setAsk_price(OrderbookCached.UPBIT_BTC.get("ask").doubleValue());
        converItem.setBid_price(OrderbookCached.UPBIT_BTC.get("bid").doubleValue());

        if (myBalance.compareTo(BigDecimal.valueOf(0.01)) == 1) {

            try {
                Response<UpbitOrderResponse> exchangeResponse = asking(converItem, myBalance.subtract(BigDecimal.valueOf(0.01)), "KRW-BTC");

                if (exchangeResponse.isSuccessful()) {
                    log.info("??????:{}", exchangeResponse.body().toString());
                } else {
                    log.error("?????? ??????:{}", exchangeResponse.errorBody().byteString().toString());
                }

            } catch (IOException e) {
                log.error("?????? ??????:{}", e.getMessage());
            }
        } else if (myBalance.compareTo(BigDecimal.valueOf(0.01)) == -1) {

            try {
                Response<UpbitOrderResponse> exchangeResponse = biding(converItem, BigDecimal.valueOf(0.01).subtract(myBalance), "KRW-BTC");

                if (exchangeResponse.isSuccessful()) {
                    log.info("??????:{}", exchangeResponse.body().toString());
                } else {
                    log.error("?????? ??????:{}", exchangeResponse.errorBody().byteString().toString());
                }
            } catch (IOException e) {
                log.error("?????? ??????:{}", e.getMessage());
            }
        }

    }

    @Async
    public void askProfit(UpbitTrade upbitTrade) {

        UpbitTicket ticket = UpbitTransactionCached.TICKET;

        if (upbitTrade.getTrade_price().compareTo(BigDecimal.valueOf(ticket.getAskOrderbookItem().getBid_price())) != 0)
            return;

        System.out.println(upbitTrade);

        BigDecimal myBalance = upbitBalanceService.getUpbitMarketAccount(ticket.getMarket());
        BigDecimal enableAmount = BigDecimal.valueOf(ticket.getAskOrderbookItem().getBid_size()).subtract(ticket.getAmount());

        if (myBalance.compareTo(BigDecimal.ZERO) != 0) { // ??? ????????? ???????????? ??????

            if (enableAmount.compareTo(ticket.getAmount()) < 0) { // ?????? ??????
                // ?????? ?????? ?????? - ????????? ????????? ?????? >= ?????? ????????? ?????? (?????? ??????)
                // ?????? ?????? ?????? - ????????? ????????? ?????? < ?????? ????????? ?????? (??????) -> ?????? ??????

                try {
                    Response<UpbitOrderResponse> deleteResponse = orderDeleting(ticket.getUuid());

                    if (deleteResponse.isSuccessful()) {
                        log.info("?????? ??????: {}", deleteResponse.body().toString());
                    } else {
                        log.error("?????? ?????? ?????? :{}", deleteResponse.errorBody().byteString().toString());
                    }
                } catch (IOException e) {
                    log.error("?????? ?????? ??????: {}", e.getMessage());
                }
                UpbitTransactionCached.reset();

            } else {
                // ?????? ?????? ????????? ?????? ?????? ?????? ?????? ?????? ?????? ??? ??? ????????? ??????
                ticket.setBid_amount(enableAmount);
            }

            return;

        } else { // ??? ????????? ????????? ???????? ????????? ?????? ???????????? ??????

            if (enableAmount.compareTo(ticket.getAmount()) < 0) {
                // ?????? ?????? ?????? - ????????? ????????? ?????? < ?????? ????????? ?????? (??????) -> ?????? ?????? // ????????? ?????? ??????????????? ???????????? ??????
                try {
                    Response<UpbitOrderResponse> deleteResponse = orderDeleting(ticket.getUuid());

                    if (deleteResponse.isSuccessful()) {
                        log.info("?????? ??????: {}", deleteResponse.body().toString());
                    } else {
                        log.error("?????? ?????? ?????? :{}", deleteResponse.errorBody().byteString().toString());
                    }
                } catch (IOException e) {
                    log.error("?????? ?????? ??????: {}", e.getMessage());
                }


                UpbitTransactionCached.reset();
            }

            try {
                Response<UpbitOrderResponse> askResponse = asking(ticket.getAskOrderbookItem(), myBalance, ticket.getAsk_market());

                if (askResponse.isSuccessful()) {
                    log.info("??????:{}", askResponse.body());
                    SleepHelper.Sleep(3000);
                    UpbitTransactionCached.reset();

                } else {
                    log.error("?????? ??????: {}", askResponse.errorBody().byteString().toString());
                    UpbitTransactionCached.COUNT++;
                }
            } catch (IOException e) {
                log.error("IOException: {}", e.getMessage());
            }
        }

    }


    private Response<UpbitOrderResponse> biding(UpbitOrderbookItem askitem, BigDecimal amount, String pair) throws IOException {

        // ??????
        UpbitOrderRequest request = UpbitOrderRequest.builder()
                .market(pair)
                .side("bid")
                .price(BigDecimal.valueOf(askitem.getAsk_price()).toPlainString())
                .volume(amount.toPlainString())
                .ord_type("limit")
                .build();

        return upbitApiRestClient.createOrder(request).execute();

    }

    private Response<UpbitOrderResponse> asking(UpbitOrderbookItem biditem, BigDecimal amount, String pair) throws IOException {

        // ??????
        UpbitOrderRequest request = UpbitOrderRequest.builder()
                .market(pair)
                .side("ask")
                .price(BigDecimal.valueOf(biditem.getBid_price()).toPlainString())
                .volume(amount.toPlainString())
                .ord_type("limit")
                .build();

        return upbitApiRestClient.createOrder(request).execute();
    }

    public Response<UpbitOrderResponse> orderDeleting(String uuid) throws IOException {

        return upbitApiRestClient.deleteOrder(uuid).execute();
    }

    private Response<UpbitOrderResponse> bidingMarket(UpbitOrderbookItem askitem, BigDecimal amount, String pair) throws IOException {

        // ??????
        UpbitOrderRequest request = UpbitOrderRequest.builder()
                .market(pair)
                .side("bid")
                .price(amount.toPlainString())
                .volume(null)
                .ord_type("price")
                .build();

        return upbitApiRestClient.bidOrder(request).execute();
    }

    private Response<UpbitOrderResponse> askingMarket(UpbitOrderbookItem biditem, BigDecimal amount, String pair) throws IOException {

        // ??????
        UpbitOrderRequest request = UpbitOrderRequest.builder()
                .market(pair)
                .side("ask")
                .price(null)
                .volume(amount.divide(BigDecimal.valueOf(biditem.getAsk_price())).toPlainString())
                .ord_type("market")
                .build();

        return upbitApiRestClient.askOrder(request).execute();
    }

}
