package com.hanaset.tacoreaper.service.okex;

import com.hanaset.tacocommon.api.okex.OkexApiRestClient;
import com.hanaset.tacocommon.api.okex.model.OkexAccount;
import com.hanaset.tacocommon.api.okex.model.OkexOrderDetail;
import com.hanaset.tacocommon.api.okex.model.OkexOrderRequest;
import com.hanaset.tacocommon.api.okex.model.OkexOrderResponse;
import com.hanaset.tacocommon.api.probit.model.ProbitOrderCancelRequest;
import com.hanaset.tacocommon.api.probit.model.ProbitOrderResponse;
import com.hanaset.tacocommon.api.upbit.model.UpbitOrderbookItem;
import com.hanaset.tacocommon.entity.reaper.ReaperAssetEntity;
import com.hanaset.tacocommon.entity.reaper.ReaperTransactionHistoryEntity;
import com.hanaset.tacocommon.exception.TacoResponseException;
import com.hanaset.tacocommon.model.TacoErrorCode;
import com.hanaset.tacocommon.repository.reaper.ReaperAssetRepository;
import com.hanaset.tacocommon.repository.reaper.ReaperTransactionHistoryRepository;
import com.hanaset.tacocommon.utils.PairUtils;
import com.hanaset.tacoreaper.cached.ReaperTradeCached;
import com.hanaset.tacoreaper.model.ReaperPair;
import com.hanaset.tacoreaper.model.ReaperTradeCondition;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@SuppressWarnings("Duplicates")
public class ReaperOkexTradeService {

    private final OkexApiRestClient okexApiRestClient;
    private final ReaperAssetRepository reaperAssetRepository;
    private final ReaperTransactionHistoryRepository reaperTransactionHistoryRepository;


    public ReaperOkexTradeService(OkexApiRestClient okexApiRestClient,
                                  ReaperAssetRepository reaperAssetRepository,
                                  ReaperTransactionHistoryRepository reaperTransactionHistoryRepository) {
        this.okexApiRestClient = okexApiRestClient;
        this.reaperAssetRepository = reaperAssetRepository;
        this.reaperTransactionHistoryRepository = reaperTransactionHistoryRepository;
    }

    @Async
    @Synchronized
    public void updateUpbitData(UpbitOrderbookItem orderbookItem, String asset) {

        String pair = PairUtils.getPair(asset);

        ReaperTradeCached.ASK_PAIR_VALUE.put(pair, ReaperPair.builder()
                .side("sell")
                .price(BigDecimal.valueOf(orderbookItem.getAsk_price()))
                .build());

        ReaperTradeCached.BID_PAIR_VALUE.put(pair, ReaperPair.builder()
                .side("buy")
                .price(BigDecimal.valueOf(orderbookItem.getBid_price()))
                .build());
    }

    public void tradeFlashingProbit(String pair) {

        List<OkexAccount> balances = okexApiRestClient.getSpotAccount();
        Map<String, BigDecimal> balanceMap = balances.stream().filter(balance -> balance.getCurrency().equals(pair) || balance.getCurrency().equals("KRW"))
                .collect(Collectors.toMap(OkexAccount::getCurrency, OkexAccount::getAvailable));

        ReaperPair askPair = ReaperTradeCached.ASK_PAIR_VALUE.get(pair);
        ReaperPair bidPair = ReaperTradeCached.BID_PAIR_VALUE.get(pair);

        if (askPair == null || bidPair == null) {
            return;
        }

        ReaperTradeCondition condition = ReaperTradeCached.TRADE_CONDITION;
        OkexOrderRequest request;

        if (!balanceMap.containsKey(pair)) { // ?????? ????????? ?????? ???????????? ?????? ?????? ??????
            request = createOrderASK(pair);
        } else { // ?????? ????????? ???????????? ????????? ?????? ??????
            if (balanceMap.get(pair).multiply(bidPair.getPrice()).compareTo(condition.getOrderMinVolume()) <= 0) { // ?????? ???????????? Pair ???????????? ?????? ??????
                request = createOrderASK(pair);
            } else {
                request = createOrderBID(pair, balanceMap);
            }
        }

        OkexOrderResponse response = okexApiRestClient.order(request);

        try {
            Thread.sleep(1000 * condition.getInterval());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        okexApiRestClient.cancelOrder(response.getOrderId(), request);
    }

    public void tradeProbit(String pair) {

        List<OkexAccount> balances = okexApiRestClient.getSpotAccount();
        Map<String, BigDecimal> balanceMap = balances.stream().filter(balance -> balance.getCurrency().equals(pair) || balance.getCurrency().equals("KRW"))
                .collect(Collectors.toMap(OkexAccount::getCurrency, OkexAccount::getAvailable));

        ReaperPair askPair = ReaperTradeCached.ASK_PAIR_VALUE.get(pair);
        ReaperPair bidPair = ReaperTradeCached.BID_PAIR_VALUE.get(pair);

        if (askPair == null || bidPair == null) {
            return;
        }

        ReaperTradeCondition condition = ReaperTradeCached.TRADE_CONDITION;

        if (!ReaperTradeCached.OKEX_RESPONSE.containsKey(pair)) { // ?????? ????????? ?????? ?????? ??????
            OkexOrderRequest request;

            if (!balanceMap.containsKey(pair)) { // ?????? ????????? ?????? ???????????? ?????? ?????? ??????
                request = createOrderASK(pair);
            } else { // ?????? ????????? ???????????? ????????? ?????? ??????
                if (balanceMap.get(pair).multiply(bidPair.getPrice()).compareTo(condition.getOrderMinVolume()) <= 0) { // ?????? ???????????? Pair ???????????? ?????? ??????
                    request = createOrderASK(pair);
                } else {
                    request = createOrderBID(pair, balanceMap);
                }
            }

            OkexOrderResponse response = okexApiRestClient.order(request);

            if(response.getResult())
                ReaperTradeCached.OKEX_RESPONSE.put(pair, response);
            else {
                System.out.println(response);
            }

        } else { // ??????????????? ?????? ??????

            OkexOrderResponse response = ReaperTradeCached.OKEX_RESPONSE.get(pair);

            OkexOrderRequest request = OkexOrderRequest.builder().instrumentId(pair + "-KRW").build();

            OkexOrderDetail orderInfoResponse = okexApiRestClient.orderDetail(response.getOrderId(), request.getInstrumentId());

            if (orderInfoResponse.getFilledNotional().compareTo(BigDecimal.ZERO) == 0) { // ?????? ????????? ?????? ???????????? ???????????? ?????? ??????

                BigDecimal limitPrice = orderInfoResponse.getPrice();

                if ((orderInfoResponse.getSide().equals("sell") && limitPrice.compareTo(askPair.getPrice()) != 0) ||
                        (orderInfoResponse.getSide().equals("buy") && limitPrice.compareTo(bidPair.getPrice()) != 0)) {

                    ReaperTradeCached.OKEX_RESPONSE.remove(pair);
                    okexApiRestClient.cancelOrder(response.getOrderId(), request);
                }
            } else { // ????????? ?????? ????????? ??????
                // ???????????? ????????? ?????? ?????? ??????

                ReaperTransactionHistoryEntity entity = ReaperTransactionHistoryEntity.builder()
                        .asset(pair)
                        .exchange("Okex")
                        .baseCurrency("KRW")
                        .side(orderInfoResponse.getSide())
                        .price(orderInfoResponse.getPrice())
                        .volume(orderInfoResponse.getFilledSize())
                        .totalPrice(orderInfoResponse.getFilledNotional())
                        .fee(orderInfoResponse.getFilledNotional().divide(BigDecimal.valueOf(1000)))
                        .build();

                reaperTransactionHistoryRepository.save(entity);
                ReaperTradeCached.OKEX_RESPONSE.remove(pair);
                okexApiRestClient.cancelOrder(response.getOrderId(), request);
            }
        }

    }

    public void init(String pair) {
        initProbitCondition(pair);
    }

    private void initProbitCondition(String pair) {

        ReaperAssetEntity entity = reaperAssetRepository.findByAsset(pair)
                .orElseThrow(() -> new TacoResponseException(TacoErrorCode.ASSET_ERROR, "DB??? ?????? ASSET??? ???????????? ????????????."));

        ReaperTradeCached.TRADE_CONDITION = ReaperTradeCondition.builder()
                .pair(entity.getAsset())
                .interval(entity.getInterval())
                .orderMaxVolume(entity.getOrderMaxVolume())
                .orderMinVolume(entity.getOrderMinVolume())
                .baseAsset(entity.getBaseAsset())
                .precision(entity.getPrecision())
                .unit(entity.getUnit())
                .tradingVolume(entity.getTradingVolume())
                .build();
    }

    private OkexOrderRequest createOrderASK(String pair) {

        ReaperPair bidProbitPair = ReaperTradeCached.BID_PAIR_VALUE.get(pair);

        ReaperTradeCondition condition = ReaperTradeCached.TRADE_CONDITION;

        return OkexOrderRequest.builder()
                .instrumentId(pair + "-KRW") // PROBIT??? ?????? ID??? XRP-KRW ????????????.
                .side(bidProbitPair.getSide())
                .type("limit")
                .price(bidProbitPair.getPrice().toPlainString())
                .size(condition.getTradingVolume().divide(bidProbitPair.getPrice(), condition.getPrecision(), RoundingMode.DOWN).toPlainString())
                .build();
    }

    private OkexOrderRequest createOrderBID(String pair, Map<String, BigDecimal> balanceMap) {

        ReaperPair askProbitPair = ReaperTradeCached.ASK_PAIR_VALUE.get(pair);

        ReaperTradeCondition condition = ReaperTradeCached.TRADE_CONDITION;

        return OkexOrderRequest.builder()
                .instrumentId(pair + "-KRW") // PROBIT??? ?????? ID??? XRP-KRW ????????????.
                .side(askProbitPair.getSide())
                .type("limit")
                .price(askProbitPair.getPrice().toPlainString())
                .size(balanceMap.get(pair).toPlainString())
                .build();

    }

}
