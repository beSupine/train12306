package com.fanko.train.business.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.fanko.train.business.domain.*;
import com.fanko.train.business.enums.ConfirmOrderStatusEnum;
import com.fanko.train.business.enums.SeatColEnum;
import com.fanko.train.business.enums.SeatTypeEnum;
import com.fanko.train.business.feign.MemberFeign;
import com.fanko.train.business.mapper.ConfirmOrderMapper;
import com.fanko.train.business.mapper.DailyTrainSeatMapper;
import com.fanko.train.business.mapper.customer.DailyTrainTicketMapperCust;
import com.fanko.train.business.req.ConfirmOrderDoReq;
import com.fanko.train.business.req.ConfirmOrderQueryReq;
import com.fanko.train.business.req.ConfirmOrderTicketReq;
import com.fanko.train.business.resp.ConfirmOrderQueryResp;
import com.fanko.train.common.context.LoginMemberContext;
import com.fanko.train.common.exception.BusinessException;
import com.fanko.train.common.exception.BusinessExceptionEnum;
import com.fanko.train.common.req.MemberTicketReq;
import com.fanko.train.common.resp.CommonResp;
import com.fanko.train.common.resp.PageResp;
import com.fanko.train.common.util.SnowUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class AfterConfirmOrderService {
    private static final Logger LOG = LoggerFactory.getLogger(AfterConfirmOrderService.class);
    @Resource
    private DailyTrainSeatMapper dailyTrainSeatMapper;
    @Resource
    private DailyTrainTicketMapperCust dailyTrainTicketMapperCust;
    @Resource
    private MemberFeign memberFeign;
    @Resource
    private ConfirmOrderMapper confirmOrderMapper;

    /*
      选中座位后事务处理：
      座位表修改售卖情况sell；
      余票详情表修改余票；
      为会员增加购票记录
      更新确认订单为成功
    * */
//    @Transactional
    @GlobalTransactional
    public void afterDoConfirm(DailyTrainTicket dailyTrainTicket,
                               List<DailyTrainSeat> finalSeatList,
                               List<ConfirmOrderTicketReq> tickets,
                               ConfirmOrder confirmOrder) throws Exception {
        LOG.info("seata全局事务ID：{}", RootContext.getXID());
        for (int j = 0; j < finalSeatList.size(); j++) {
            DailyTrainSeat dailyTrainSeat = finalSeatList.get(j);
            DailyTrainSeat seatForUpdate = new DailyTrainSeat();
            seatForUpdate.setId(dailyTrainSeat.getId());
            seatForUpdate.setSell(dailyTrainSeat.getSell());
            seatForUpdate.setUpdateTime(new Date());
            dailyTrainSeatMapper.updateByPrimaryKeySelective(seatForUpdate);

            // 计算这个站卖出去后，影响了哪些站的余票库存
            // 参照2~3节 如何保证不超卖、不少卖，还要承受极高的并发 10：30左右
            // 影响的库存：本次选座之前 没卖过票的，和本次够买的区间有交集的区间
            // 假设10个站，本次买4~7站
            // 原售：001000001
            // 购买：000011100
            // 新售：001011101
            // 影响：XXX11111X
//            Integer startIndex = 4;
//            Integer endIndex = 7;
//            Integer minStartIndex = startIndex - 往前碰到的最后一个0;
//            Integer maxStartIndex = endIndex - 1;
//            Integer minEndIndex = startIndex + 1;
//            Integer maxEndIndex = endIndex + 往后碰到的第一个0;
            Integer startIndex = dailyTrainTicket.getStartIndex();
            Integer endIndex = dailyTrainTicket.getEndIndex();
            char[] chars = seatForUpdate.getSell().toCharArray();
            Integer maxStartIndex = endIndex - 1;
            Integer minEndIndex = startIndex + 1;
            Integer minStartIndex = 0;
            for (int i = startIndex - 1; i >= 0; i--) {
                char aChar = chars[i];
                if (aChar == '1') {
                    minStartIndex = i + 1;
                    break;
                }
            }
            LOG.info("影响出发站区间:" + minStartIndex + "-" + maxStartIndex);

            Integer maxEndIndex = seatForUpdate.getSell().length();
            for (int i = endIndex; i < seatForUpdate.getSell().length(); i++) {
                char aChar = chars[i];
                if (aChar == '1') {
                    maxEndIndex = i;
                    break;
                }
            }
            LOG.info("影响到达站区间:" + minEndIndex + "-" + maxEndIndex);
            dailyTrainTicketMapperCust.updateCountBySell(
                    dailyTrainSeat.getDate(),
                    dailyTrainSeat.getTrainCode(),
                    dailyTrainSeat.getSeatType(),
                    minStartIndex,
                    maxStartIndex,
                    minEndIndex,
                    maxEndIndex
            );
            // 调用会员服务接口，为会员增加一张车票
            DateTime now = DateTime.now();

            MemberTicketReq memberTicketReq = new MemberTicketReq();
            memberTicketReq.setMemberId(LoginMemberContext.getId());
            memberTicketReq.setPassengerId(tickets.get(j).getPassengerId());
            memberTicketReq.setPassengerName(tickets.get(j).getPassengerName());
            memberTicketReq.setTrainDate(dailyTrainTicket.getDate());
            memberTicketReq.setTrainCode(dailyTrainTicket.getTrainCode());
            memberTicketReq.setCarriageIndex(dailyTrainSeat.getCarriageIndex());
            memberTicketReq.setSeatRow(dailyTrainSeat.getRow());
            memberTicketReq.setSeatCol(dailyTrainSeat.getCol());
            memberTicketReq.setStartStation(dailyTrainTicket.getStart());
            memberTicketReq.setStartTime(dailyTrainTicket.getStartTime());
            memberTicketReq.setEndStation(dailyTrainTicket.getEnd());
            memberTicketReq.setEndTime(dailyTrainTicket.getEndTime());
            memberTicketReq.setSeatType(dailyTrainSeat.getSeatType());
            memberTicketReq.setCreateTime(now);
            memberTicketReq.setUpdateTime(now);
            CommonResp<Object> save = memberFeign.save(memberTicketReq);
            LOG.info("通用member接口，返回：{}",save);


            // 更新订单状态为成功
            ConfirmOrder confirmOrderForUpdate  = new ConfirmOrder();
            confirmOrderForUpdate.setId(confirmOrder.getId());
            confirmOrderForUpdate.setUpdateTime(new Date());
            confirmOrderForUpdate.setStatus(ConfirmOrderStatusEnum.SUCCESS.getCode());
            confirmOrderMapper.updateByPrimaryKeySelective(confirmOrderForUpdate);
//
//            if (1==1){
//                throw new Exception("测试异常");
//            }

        }
    }

}
