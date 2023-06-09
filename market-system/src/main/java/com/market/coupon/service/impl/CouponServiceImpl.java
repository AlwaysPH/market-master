package com.market.coupon.service.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.market.activity.mapper.ActivityCouponMapper;
import com.market.activity.mapper.ActivityMapper;
import com.market.activity.mapper.MerchantMapper;
import com.market.activity.model.ActivityCouponInfo;
import com.market.activity.model.ActivityInfo;
import com.market.activity.model.MerchantInfo;
import com.market.common.enums.*;
import com.market.common.exception.ServiceException;
import com.market.common.utils.CommonUtils;
import com.market.common.utils.DateUtils;
import com.market.common.utils.RegexCommon;
import com.market.common.utils.SecurityUtils;
import com.market.common.utils.uuid.IdUtils;
import com.market.coupon.mapper.CouponMapper;
import com.market.coupon.mapper.CouponThresholdMapper;
import com.market.coupon.model.*;
import com.market.coupon.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;


import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ph
 * @version 1.0
 * @date 2023-04-16 21:23
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class CouponServiceImpl implements CouponService {

    private static final BigDecimal MIN = new BigDecimal("0.01");

    private static final BigDecimal NO_MAX = new BigDecimal("5");

    private static final BigDecimal FIX_MAX = new BigDecimal("100");

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private CouponThresholdMapper thresholdMapper;

    @Autowired
    private ActivityCouponMapper activityCouponMapper;

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private MerchantMapper merchantMapper;

    /**
     * 查询优惠券信息
     *
     * @param id 优惠券信息主键
     * @return 优惠券信息
     */
    @Override
    public CouponParam selectCouponInfoById(String id) {
        CouponInfo info = couponMapper.selectCouponInfoById(id);
        if(Objects.isNull(info)){
            throw new ServiceException("优惠券信息不存在");
        }
        CouponParam param = new CouponParam();
        BeanUtils.copyProperties(info, param);
        List<CouponThreshold> list = thresholdMapper.getDataByCouponId(info.getId(), null);
        if(CollectionUtils.isNotEmpty(list)){
            if(StringUtils.equalsIgnoreCase(info.getCouponType(), CouponTypeEnum.FULL.getCode())){
                Map<String, List<CouponThreshold>> map = list.stream().collect(Collectors.groupingBy(CouponThreshold::getRestrictionType));
                map.forEach((key, tList) -> {
                    param.setRestrictionType(key);
                    CouponThreshold threshold = tList.get(0);
                    if(StringUtils.equalsIgnoreCase(key, RestrictionTypeEnum.NO.getCode())){
                        param.setDisAmount(threshold.getDisAmount());

                    }else if(StringUtils.equalsIgnoreCase(key, RestrictionTypeEnum.FIX.getCode())){
                        param.setFullFixSubCondition(threshold.getFullFixSubCondition());
                        param.setDisFixAmount(threshold.getDisFixAmount());
                    }else if(StringUtils.equalsIgnoreCase(key, RestrictionTypeEnum.LADDER.getCode())){
                        param.setConditionList(tList);
                    }
                });
            }else if(StringUtils.equalsIgnoreCase(info.getCouponType(), CouponTypeEnum.SALE.getCode())){
                CouponThreshold threshold = list.get(0);
                param.setFullRestriction(threshold.getFullRestriction());
                param.setSaleNum(threshold.getSaleNum());
                param.setUpperLimitFlag(threshold.getUpperLimitFlag());
                param.setUpperLimitAmount(threshold.getUpperLimitAmount());
            }else if(StringUtils.equalsIgnoreCase(info.getCouponType(), CouponTypeEnum.VOUCHER.getCode())){
                CouponThreshold threshold = list.get(0);
                param.setFaceMoney(threshold.getFaceMoney());
                param.setOrderAmount(threshold.getOrderAmount());
            }
        }
        return param;
    }

    /**
     * 查询优惠券信息列表
     *
     * @param info 优惠券信息
     * @return 优惠券信息
     */
    @Override
    public List<ActivityCouponEntity> selectCouponInfoList(CouponInfo info) {
        return couponMapper.selectInfoList(info);
    }

    /**
     * 新增优惠券信息
     *
     * @param param 优惠券信息
     * @return 结果
     */
    @Override
    public int insertCouponInfo(CouponParam param) {
        CouponInfo info = new CouponInfo();
        BeanUtils.copyProperties(param, info);
        info.setId(IdUtils.simpleUUID());
        info.setCreateTime(DateUtils.getNowDate());
        info.setDelFlag("0");
        info.setCreateBy(SecurityUtils.getUserId());
        info.setCouponCode(CommonUtils.getRandom(16));
        int i = couponMapper.insertCouponInfo(info);
        List<CouponThreshold> list = getCouponThreshold(param, info);
        //优惠券使用门槛入库
        if(CollectionUtils.isNotEmpty(list)){
            i += thresholdMapper.batchInsertData(list);
        }
        return i;
    }

    private CouponThreshold buildThreshold(CouponParam param, CouponInfo info) {
        CouponThreshold threshold = new CouponThreshold();
        BeanUtils.copyProperties(param, threshold);
        threshold.setId(IdUtils.simpleUUID());
        threshold.setCouponId(info.getId());
        threshold.setCouponType(info.getCouponType());
        threshold.setCreateTime(DateUtils.getNowDate());
        threshold.setDelFlag("0");
        threshold.setCreateBy(SecurityUtils.getUserId());
        return threshold;
    }

    /**
     * 修改优惠券信息
     *
     * @param param 优惠券信息
     * @return 结果
     */
    @Override
    public int updateCouponInfo(CouponParam param) {
        CouponInfo info = couponMapper.selectCouponInfoById(param.getId());
        if(Objects.isNull(info)){
            throw new ServiceException("操作失败");
        }
        //判断当前优惠券是否关联活动
        int count = couponMapper.getActivityNum(param.getId());
        if(count > 0){
            throw new ServiceException("当前优惠券关联的活动已审核通过，不允许修改");
        }
        param.setUpdateBy(SecurityUtils.getUserId());
        param.setUpdateTime(DateUtils.getNowDate());
        //修改优惠券信息
        int i = couponMapper.updateCouponInfo(param);
        //若修改了使用门槛类型，删除原先的使用门槛数据
        i += thresholdMapper.deleteByCouponId(info.getId());
        List<CouponThreshold> list = getCouponThreshold(param, info);
        //优惠券使用门槛入库
        if(CollectionUtils.isNotEmpty(list)){
            i += thresholdMapper.batchInsertData(list);
        }
        return i;
    }

    /**
     * 获取优惠券使用门槛数据
     * @param param
     * @param info
     * @return
     */
    private List<CouponThreshold> getCouponThreshold(CouponParam param, CouponInfo info) {
        List<CouponThreshold> list = Lists.newArrayList();
        if(StringUtils.equalsIgnoreCase(param.getCouponType(), CouponTypeEnum.FULL.getCode())){
            //满减券
            if (StringUtils.equalsIgnoreCase(param.getRestrictionType(), RestrictionTypeEnum.NO.getCode())) {
                //无限制
                if(Objects.isNull(param.getDisAmount())){
                    throw new ServiceException("面额不能为空");
                }
                //判断是否是符合规范的数据
                if(!RegexCommon.judgeNum(param.getDisAmount(), RegexCommon.TWO_REGEX) || param.getDisAmount().compareTo(MIN) < 0
                        || param.getDisAmount().compareTo(NO_MAX) > 0){
                    throw new ServiceException("输入的数据不符合规范");
                }
                //构造使用门槛数据
                CouponThreshold data = buildThreshold(param, info);
                list.add(data);
            }else if(StringUtils.equalsIgnoreCase(param.getRestrictionType(), RestrictionTypeEnum.FIX.getCode())){
                //固定满减
                if(Objects.isNull(param.getFullFixSubCondition())){
                    throw new ServiceException("满减条件不能为空");
                }
                if(!RegexCommon.judgeNum(param.getFullFixSubCondition(), RegexCommon.TWO_REGEX)){
                    throw new ServiceException("输入的满减条件数据不符合规范");
                }
                if(Objects.isNull(param.getDisFixAmount())){
                    throw new ServiceException("优惠金额不能为空");
                }
                //判断是否是符合规范的数据
                if(!RegexCommon.judgeNum(param.getDisFixAmount(), RegexCommon.TWO_REGEX) || param.getDisFixAmount().compareTo(MIN) < 0
                        || param.getDisFixAmount().compareTo(FIX_MAX) > 0){
                    throw new ServiceException("输入的优惠金额数据不符合规范");
                }
                //构造使用门槛数据
                CouponThreshold data = buildThreshold(param, info);
                list.add(data);
            }else if(StringUtils.equalsIgnoreCase(param.getRestrictionType(), RestrictionTypeEnum.LADDER.getCode())){
                //阶梯满减
                List<CouponThreshold> thresholdList = param.getConditionList();
                if(CollectionUtils.isEmpty(thresholdList)){
                    throw new ServiceException("优惠券使用门槛不能为空");
                }
                thresholdList.forEach(e -> {
                    if(Objects.isNull(e.getFullSubCondition())){
                        throw new ServiceException("满减条件不能为空");
                    }
                    if(!RegexCommon.judgeNum(e.getFullSubCondition(), RegexCommon.TWO_REGEX)){
                        throw new ServiceException("输入的满减条件数据不符合规范");
                    }
                    if(Objects.isNull(e.getFullSubAmount())){
                        throw new ServiceException("优惠金额不能为空");
                    }
                    if(!RegexCommon.judgeNum(e.getFullSubAmount(), RegexCommon.TWO_REGEX)){
                        throw new ServiceException("输入的优惠金额数据不符合规范");
                    }
                    e.setId(IdUtils.simpleUUID());
                    e.setCouponId(info.getId());
                    e.setCouponType(info.getCouponType());
                    e.setRestrictionType(param.getRestrictionType());
                    e.setCreateTime(DateUtils.getNowDate());
                    e.setDelFlag("0");
                    e.setCreateBy(SecurityUtils.getUserId());
                    list.add(e);
                });
            }
        }else if(StringUtils.equalsIgnoreCase(param.getCouponType(), CouponTypeEnum.SALE.getCode())){
            if(Objects.isNull(param.getFullRestriction())){
                throw new ServiceException("满额限制不能为空");
            }
            if(!RegexCommon.judgeNum(param.getFullRestriction(), RegexCommon.TWO_REGEX)){
                throw new ServiceException("输入的满额限制数据不符合规范");
            }
            if(Objects.isNull(param.getSaleNum())){
                throw new ServiceException("折扣比例不能为空");
            }
            if(!RegexCommon.judgeNum(param.getSaleNum(), RegexCommon.TWO_REGEX)  || param.getSaleNum().compareTo(MIN) < 0
                    || param.getSaleNum().compareTo(FIX_MAX) > 0){
                throw new ServiceException("输入的折扣比例数据不符合规范");
            }
            if (StringUtils.equalsIgnoreCase(param.getUpperLimitFlag(), UpperLimitTypeEnum.YES.getCode())) {
                if(Objects.isNull(param.getUpperLimitAmount())){
                    throw new ServiceException("上限满额金额不能为空");
                }
                if(!RegexCommon.judgeNum(param.getUpperLimitAmount(), RegexCommon.TWO_REGEX)){
                    throw new ServiceException("输入的上限满额金额不符合规范");
                }
            }else {
                param.setUpperLimitAmount(null);
            }
            CouponThreshold data = buildThreshold(param, info);
            list.add(data);
        }else if(StringUtils.equalsIgnoreCase(param.getCouponType(), CouponTypeEnum.VOUCHER.getCode())){
            if(Objects.isNull(param.getFaceMoney())){
                throw new ServiceException("面额不能为空");
            }
            if(!RegexCommon.judgeNum(param.getFaceMoney(), RegexCommon.TWO_REGEX)){
                throw new ServiceException("输入的面额不符合规范");
            }
            if(Objects.isNull(param.getOrderAmount())){
                throw new ServiceException("订单需满金额不能为空");
            }
            if(!RegexCommon.judgeNum(param.getOrderAmount(), RegexCommon.TWO_REGEX)){
                throw new ServiceException("输入的订单需满金额不符合规范");
            }
            CouponThreshold data = buildThreshold(param, info);
            list.add(data);
        }
        return list;
    }

    /**
     * 批量删除优惠券信息
     *
     * @param ids 需要删除的优惠券信息主键
     * @return 结果
     */
    @Override
    public int deleteCouponInfoByIds(String[] ids) {
        if(ids.length == 0){
            throw new ServiceException("请选择需要删除的数据！");
        }
        List<String> list = Arrays.asList(ids);
        List<CouponInfo> data = couponMapper.getListData(list);
        if(CollectionUtils.isEmpty(data)){
            throw new ServiceException("未找到对应数据");
        }
        String couponId = list.get(0);
        List<ActivityCouponInfo> dataList = activityCouponMapper.getDataList(null, couponId, null);
        if(CollectionUtils.isNotEmpty(dataList)){
            throw new ServiceException("当前优惠券有关联活动数据，不允许删除！");
        }
        int i = couponMapper.deleteCouponInfoByIds(list, SecurityUtils.getUserId());
        i += thresholdMapper.updateByCouponId(list, SecurityUtils.getUserId());
        return i;
    }

    /**
     * 获取新增关联的优惠券列表
     * @param info
     * @return
     */
    @Override
    public List<CouponEntity> getCouponDetailList(CouponInfo info) {
        List<CouponEntity> result = Lists.newArrayList();
        if(StringUtils.isEmpty(info.getCouponType())){
            throw new ServiceException("卡券类型不允许为空");
        }
        if(StringUtils.isEmpty(info.getActivityId())){
            throw new ServiceException("活动id不允许为空");
        }
        ActivityInfo activityInfo = activityMapper.selectActivityInfoById(info.getActivityId());
        if(Objects.isNull(activityInfo)){
            throw new ServiceException("活动信息不存在");
        }
        //获取优惠券列表
        List<CouponInfo> infoList = couponMapper.selectCouponInfoList(info);
        if(CollectionUtils.isEmpty(infoList)){
            log.info("无当前卡券类型的优惠券");
            return result;
        }
        List<String> idList = infoList.stream().map(CouponInfo::getId).collect(Collectors.toList());
        //获取优惠券优惠数据
        List<CouponThreshold> thresholdList = thresholdMapper.getDataByCouponId(null, idList);
        Map<String, List<CouponThreshold>> thresholdMap = Maps.newHashMap();
        if(CollectionUtils.isNotEmpty(thresholdList)){
            thresholdMap = thresholdList.stream().collect(Collectors.groupingBy(CouponThreshold::getCouponId));
        }
        //获取优惠券领取数据
        List<UserCouponInfo> receiveList = couponMapper.getReceiveList(idList, info.getActivityId());
        Map<String, List<UserCouponInfo>> receiveMap = Maps.newHashMap();
        if(CollectionUtils.isNotEmpty(receiveList)){
            receiveMap = receiveList.stream().collect(Collectors.groupingBy(UserCouponInfo::getCouponId));
        }
        //获取优惠券关联活动数据
//        List<ActivityCoupon> activityCouponList = couponMapper.getActivityCouponList(idList);
//        Map<String, List<ActivityCoupon>> activityMap = Maps.newHashMap();
//        if(CollectionUtils.isNotEmpty(activityCouponList)){
//            activityMap = activityCouponList.stream().collect(Collectors.groupingBy(ActivityCoupon::getCouponId));
//        }
        Map<String, List<UserCouponInfo>> finalReceiveMap = receiveMap;
//        Map<String, List<ActivityCoupon>> finalActivityMap = activityMap;
        Map<String, List<CouponThreshold>> finalThresholdMap = thresholdMap;
        infoList.forEach(e -> {
            CouponEntity entity = new CouponEntity();
            BeanUtils.copyProperties(e, entity);
            entity.setGrantNum(0);
            entity.setCouponAmount(BigDecimal.ZERO);
            List<UserCouponInfo> receiveData = finalReceiveMap.get(e.getId());
            if(CollectionUtils.isNotEmpty(receiveData)){
                entity.setGrantNum(receiveData.get(0).getReceiveNum());
            }
//            List<ActivityCoupon> activityCoupons = finalActivityMap.get(e.getId());
//            if(CollectionUtils.isNotEmpty(activityCoupons)){
//                entity.setInventoryNum(activityCoupons.get(0).getInventoryNum());
//            }
            List<CouponThreshold> thresholds = finalThresholdMap.get(e.getId());
            if(CollectionUtils.isNotEmpty(thresholds)){
                buildCouponAmount(entity, thresholds, true);
            }
            result.add(entity);
        });
        return result;
    }

    /**
     * 获取活动关联的优惠券列表
     * @param info
     * @return
     */
    @Override
    public List<ActivityCouponEntity> getActivityCouponList(CouponInfo info) {
        if(StringUtils.isEmpty(info.getActivityId())){
            throw new ServiceException("活动id不能为空");
        }
        return activityCouponMapper.getDataListByCoupon(info);
    }

    /**
     * 获取我的优惠券列表
     * @return
     * @param params
     */
    @Override
    public List<CouponThreshold> getUserCouponList(AppParams params) {
        if(StringUtils.isEmpty(params.getPhoneNumber())){
            throw new ServiceException("参数不能为空");
        }
        List<CouponThreshold> list = couponMapper.getUserCouponList(params);
        if(CollectionUtils.isEmpty(list)){
            return Lists.newArrayList();
        }
        List<CouponThreshold> result = getCouponList(list);
        return result;
    }

    private List<CouponThreshold> getCouponList(List<CouponThreshold> list) {
        List<CouponThreshold> result = Lists.newArrayList();
        //计算配置日期的优惠券失效时间
        list.forEach(e -> {
            if(StringUtils.equalsIgnoreCase(e.getEffectType(), CouponEffectTypeEnum.ACC.getCode())){
                int num = e.getTakeDateNum() + e.getEffectDateNum();
                Date effectEndTime = DateUtils.daysAgoOrAfterToDate(e.getReceiveTime(), num);
                e.setEffectEndTime(effectEndTime);
            }
        });
        //阶梯满减券使用门槛合并
        if(CollectionUtils.isNotEmpty(list)){
            //阶梯满减券
            List<CouponThreshold> collect = list.stream().filter(e -> StringUtils.equalsIgnoreCase(e.getRestrictionType(), RestrictionTypeEnum.LADDER.getCode())).collect(Collectors.toList());
            List<CouponThresholdEntity> ladderList = buildEntity(collect);
            //非阶梯满减券
            List<CouponThreshold> otherList = list.stream().filter(e -> !StringUtils.equalsIgnoreCase(e.getRestrictionType(), RestrictionTypeEnum.LADDER.getCode())).collect(Collectors.toList());
            result.addAll(otherList);
            if(CollectionUtils.isNotEmpty(collect)){
                CouponThreshold threshold = collect.get(0);
                threshold.setLadderList(ladderList);
                result.add(threshold);
            }
        }
        return result;
    }

    private List<CouponThresholdEntity> buildEntity(List<CouponThreshold> collect) {
        List<CouponThresholdEntity> data = Lists.newArrayList();
        if(CollectionUtils.isEmpty(collect)){
            return data;
        }
        collect.forEach(e -> {
            CouponThresholdEntity entity = new CouponThresholdEntity();
            BeanUtils.copyProperties(e, entity);
            data.add(entity);
        });
        return data;
    }

    /**
     * 更新优惠券使用状态
     * @param params
     * @return
     */
    @Override
    public int useCoupon(UseCouponParam params) {
        CouponInfo couponInfo = couponMapper.selectCouponInfoById(params.getCouponId());
        if(Objects.isNull(couponInfo)){
            throw new ServiceException("当前优惠券不存在");
        }
        List<CouponThreshold> data = thresholdMapper.getDataByCouponId(params.getCouponId(), null);
        if(CollectionUtils.isEmpty(data)){
            throw new ServiceException("当前优惠券优惠金额不存在");
        }
        CouponThreshold threshold = data.get(0);
        if(!judgePayAmount(threshold, params.getDisAmount())){
            throw new ServiceException("当前订单优惠金额不正确");
        }
        UserCouponInfo info = couponMapper.getUserCouponData(params);
        if(Objects.isNull(info)){
            throw new ServiceException("当前用户不存在该优惠券");
        }
        info.setStatus(params.getStatus());
        info.setUseTime(new Date());
        info.setWriteOffCode(params.getWriteOffCode());
        //更新用户使用优惠券状态
        int i = couponMapper.updateUserCoupon(info);
        //记录优惠券使用详情信息
        if(StringUtils.equalsIgnoreCase(params.getStatus(), CouponUseStatusEnum.USED.getCode())){
            CouponUseEntity entity = new CouponUseEntity();
            BeanUtils.copyProperties(params, entity);
            entity.setId(IdUtils.simpleUUID());
            entity.setTransTime(DateUtils.transLongToDate(params.getTime()));
            entity.setCreateTime(new Date());
            entity.setChannelType(couponInfo.getChannelType());
            i += couponMapper.insertCouponUseDetail(entity);
        }
        return i;
    }

    private boolean judgePayAmount(CouponThreshold threshold, BigDecimal disAmount) {
        if(StringUtils.equalsIgnoreCase(threshold.getCouponType(), CouponTypeEnum.FULL.getCode())){
            /**满减券**/
            if(StringUtils.equalsIgnoreCase(threshold.getRestrictionType(), RestrictionTypeEnum.NO.getCode())){
                //满减无限制
                if(disAmount.compareTo(threshold.getDisAmount()) != 0){
                    return false;
                }
            }else if(StringUtils.equalsIgnoreCase(threshold.getRestrictionType(), RestrictionTypeEnum.FIX.getCode())){
                //满减固定
                if(disAmount.compareTo(threshold.getDisFixAmount()) != 0){
                    return false;
                }
            }else if(StringUtils.equalsIgnoreCase(threshold.getRestrictionType(), RestrictionTypeEnum.LADDER.getCode())){
                //满减固定
                if(disAmount.compareTo(threshold.getFullSubAmount()) != 0){
                    return false;
                }
            }
        }else if(StringUtils.equalsIgnoreCase(threshold.getCouponType(), CouponTypeEnum.SALE.getCode())){
            /**折扣券**/
            if(StringUtils.equalsIgnoreCase(threshold.getUpperLimitFlag(), UpperLimitTypeEnum.YES.getCode())){
                if(disAmount.compareTo(threshold.getUpperLimitAmount()) != 0){
                    return false;
                }
            }else {
                BigDecimal fullRestriction = Objects.isNull(threshold.getFullRestriction()) ? BigDecimal.ZERO : threshold.getFullRestriction();
                BigDecimal saleNum = Objects.isNull(threshold.getSaleNum()) ? BigDecimal.ZERO : threshold.getSaleNum();
                BigDecimal divide = fullRestriction.multiply(saleNum).divide(new BigDecimal(100), 2, BigDecimal.ROUND_HALF_UP);
                if(disAmount.compareTo(divide) != 0){
                    return false;
                }
            }
        }else if(StringUtils.equalsIgnoreCase(threshold.getCouponType(), CouponTypeEnum.VOUCHER.getCode())){
            /**代金券**/
            if(disAmount.compareTo(threshold.getFaceMoney()) != 0){
                return false;
            }
        }
        return true;
    }

    /**
     * 获取最优支付的优惠券
     * @param params
     * @return
     */
    @Override
    public UserCouponEntity getOptimalCoupon(AppParams params) {
        if(StringUtils.isEmpty(params.getPhoneNumber())){
            throw new ServiceException("用户手机号不能为空");
        }
        if(StringUtils.isEmpty(params.getMerchantNo())){
            throw new ServiceException("商户号不能为空");
        }
        if(Objects.isNull(params.getOrderAmount())){
            throw new ServiceException("订单原总金额不能为空");
        }
        List<CouponThreshold> list = thresholdMapper.getUserCouponList(params);
        if(CollectionUtils.isEmpty(list)){
            return new UserCouponEntity();
        }
        Map<String, List<CouponThreshold>> map = list.stream().collect(Collectors.groupingBy(CouponThreshold::getCouponType));
        List<UserCouponEntity> result = Lists.newArrayList();
        map.forEach((key, tList) -> {
            UserCouponEntity entity = new UserCouponEntity();
            if(StringUtils.equalsIgnoreCase(CouponTypeEnum.FULL.getCode(), key)){
                /**满减券**/
                Map<String, List<CouponThreshold>> collect = tList.stream().collect(Collectors.groupingBy(CouponThreshold::getRestrictionType));
                collect.forEach((type, data) -> {
                    if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.LADDER.getCode(), type)){
                        //阶梯满减
                        //判断符合满减金额条件的数据
                        List<CouponThreshold> collect1 = data.stream().filter(e -> e.getFullSubCondition().compareTo(params.getOrderAmount()) <= 0).collect(Collectors.toList());
                        if(CollectionUtils.isNotEmpty(collect1)){
                            //获取满足条件中满减金额最大的数据
                            CouponThreshold threshold = collect1.stream().max(Comparator.comparing(CouponThreshold::getFullSubAmount)).get();
                            entity.setUserCouponId(threshold.getUserCouponId());
                            entity.setOptimalAmount(threshold.getFullSubAmount());
                            result.add(entity);
                        }
                    }else if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.NO.getCode(), type)){
                        //无限制
                        CouponThreshold threshold = data.get(0);
                        entity.setUserCouponId(threshold.getUserCouponId());
                        entity.setOptimalAmount(threshold.getDisAmount());
                        result.add(entity);
                    }else if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.FIX.getCode(), type)){
                        //固定满减
                        CouponThreshold threshold = data.get(0);
                        if(threshold.getFullFixSubCondition().compareTo(params.getOrderAmount()) <= 0){
                            entity.setUserCouponId(threshold.getUserCouponId());
                            entity.setOptimalAmount(threshold.getDisFixAmount());
                            result.add(entity);
                        }
                    }
                });
            }else if(StringUtils.equalsIgnoreCase(CouponTypeEnum.SALE.getCode(), key)){
                /**折扣券**/
                CouponThreshold threshold = tList.get(0);
                BigDecimal divide = threshold.getFullRestriction().multiply(threshold.getSaleNum()).divide(new BigDecimal(100), 2, BigDecimal.ROUND_HALF_UP);
                if(StringUtils.equalsIgnoreCase(threshold.getUpperLimitFlag(), UpperLimitTypeEnum.YES.getCode())){
                    if(divide.compareTo(threshold.getUpperLimitAmount()) > 0){
                        divide = threshold.getUpperLimitAmount();
                    }
                }
                entity.setUserCouponId(threshold.getUserCouponId());
                entity.setOptimalAmount(divide);
                result.add(entity);
            }else if(StringUtils.equalsIgnoreCase(CouponTypeEnum.VOUCHER.getCode(), key)){
                /**代金券**/
                CouponThreshold threshold = tList.get(0);
                if(threshold.getOrderAmount().compareTo(params.getOrderAmount()) <= 0){
                    entity.setUserCouponId(threshold.getUserCouponId());
                    entity.setOptimalAmount(threshold.getFaceMoney());
                    result.add(entity);
                }
            }
        });
        if(CollectionUtils.isEmpty(result)){
            return new UserCouponEntity();
        }
        UserCouponEntity entity = result.stream().max(Comparator.comparing(UserCouponEntity::getOptimalAmount)).get();
        return entity;
    }

    /**
     * 获取优惠券详细信息
     * @param params
     * @return
     */
    @Override
    public CouponThreshold getCouponDetail(AppParams params) {
        if(StringUtils.isEmpty(params.getUserCouponId())){
            throw new ServiceException("用户关联优惠券id不能为空");
        }
        if(StringUtils.isEmpty(params.getCouponId())){
            throw new ServiceException("优惠券id不能为空");
        }
        CouponInfo info = couponMapper.selectCouponInfoById(params.getCouponId());
        if(Objects.isNull(info)){
            throw new ServiceException("当前优惠券不存在");
        }
        List<CouponThreshold> list = couponMapper.getUserCouponList(params);
        if(CollectionUtils.isEmpty(list)){
            throw new ServiceException("当前用户未领取该优惠券");
        }
        CouponThreshold threshold = list.get(0);
        //获取活动适用商户
        List<MerchantInfo> merchantList = merchantMapper.getMerchantList(threshold.getActivityId(), null);
        String merchantStr = Strings.EMPTY;
        if(CollectionUtils.isNotEmpty(merchantList)){
            merchantStr = merchantList.stream().map(MerchantInfo::getMerchantName).collect(Collectors.joining(","));
        }
        threshold.setMerchantNames(merchantStr);
        return threshold;
    }

    /**
     * 获取可支付的优惠券
     * @param params
     * @return
     */
    @Override
    public List<CouponThreshold> getPayCouponList(AppParams params) {
        if(StringUtils.isEmpty(params.getPhoneNumber())){
            throw new ServiceException("用户手机号不能为空");
        }
        if(StringUtils.isEmpty(params.getMerchantNo())){
            throw new ServiceException("商户号不能为空");
        }
        List<CouponThreshold> list = thresholdMapper.getUserCouponList(params);
        return getCouponList(list);
    }

    /**
     * 根据优惠券类型获取优惠金额总数
     * @param entity
     * @param thresholds
     */
    private void buildCouponAmount(CouponEntity entity, List<CouponThreshold> thresholds, Boolean jumpFlag) {
        //根据优惠券类型分组
        Map<String, List<CouponThreshold>> map = thresholds.stream().collect(Collectors.groupingBy(CouponThreshold::getCouponType));
        map.forEach((key, tList) -> {
            if(StringUtils.equalsIgnoreCase(CouponTypeEnum.FULL.getCode(), key)){
                /**满减券**/
                Map<String, List<CouponThreshold>> collect = tList.stream().collect(Collectors.groupingBy(CouponThreshold::getRestrictionType));
                collect.forEach((type, list) -> {
                    if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.LADDER.getCode(), type)){
                        //阶梯满减
                        if(!jumpFlag){
                            BigDecimal reduce = list.stream().map(CouponThreshold::getFullSubAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                            entity.setCouponAmount(reduce);
                        }
                    }else if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.NO.getCode(), type)){
                        //无限制
                        BigDecimal reduce = list.stream().map(CouponThreshold::getDisAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                        entity.setCouponAmount(reduce);
                    }else if(StringUtils.equalsIgnoreCase(RestrictionTypeEnum.FIX.getCode(), type)){
                        //固定满减
                        BigDecimal reduce = list.stream().map(CouponThreshold::getDisFixAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                        entity.setCouponAmount(reduce);
                    }
                });
            }else if(StringUtils.equalsIgnoreCase(CouponTypeEnum.SALE.getCode(), key)){
                /**折扣券**/
                CouponThreshold threshold = tList.get(0);
                BigDecimal divide = threshold.getFullRestriction().subtract(threshold.getFullRestriction().multiply(threshold.getSaleNum()).divide(new BigDecimal(100), 2, BigDecimal.ROUND_HALF_UP));
                if(StringUtils.equalsIgnoreCase(threshold.getUpperLimitFlag(), UpperLimitTypeEnum.YES.getCode())){
                    if(divide.compareTo(threshold.getUpperLimitAmount()) > 0){
                        entity.setCouponAmount(threshold.getUpperLimitAmount());
                    }
                }
                entity.setCouponAmount(divide);
            }else if(StringUtils.equalsIgnoreCase(CouponTypeEnum.VOUCHER.getCode(), key)){
                /**代金券**/
                CouponThreshold threshold = tList.get(0);
                entity.setCouponAmount(threshold.getFaceMoney());
            }
        });

    }

}
