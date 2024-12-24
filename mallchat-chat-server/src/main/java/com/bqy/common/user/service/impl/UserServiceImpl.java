package com.bqy.common.user.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.bqy.common.common.annotation.RedissonLock;
import com.bqy.common.common.event.UserBlackEvent;
import com.bqy.common.common.event.UserRegisterEvent;
import com.bqy.common.common.exception.BusinessException;
import com.bqy.common.common.utils.AssertUtil;
import com.bqy.common.user.dao.BlackDao;
import com.bqy.common.user.dao.ItemConfigDao;
import com.bqy.common.user.dao.UserBackpackDao;
import com.bqy.common.user.dao.UserDao;
import com.bqy.common.user.domain.entity.*;
import com.bqy.common.user.domain.enums.BlackTypeEnum;
import com.bqy.common.user.domain.enums.ItemEnum;
import com.bqy.common.user.domain.enums.ItemTypeEnum;
import com.bqy.common.user.domain.vo.req.BlackReq;
import com.bqy.common.user.domain.vo.req.WearBadgeReq;
import com.bqy.common.user.domain.vo.resp.BadgeResp;
import com.bqy.common.user.domain.vo.resp.UserInfoResp;
import com.bqy.common.user.service.UserService;
import com.bqy.common.user.service.adapter.UserAdapter;
import com.bqy.common.user.service.cache.ItemCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    @Resource
    private UserDao userDao;
    @Resource
    private UserBackpackDao userBackpackDao;
    @Resource
    private ItemCache itemCache;
    @Resource
    private ItemConfigDao itemConfigDao;
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;
    @Resource
    private BlackDao blackDao;
    @Override
    @Transactional
    public Long register(User user) {
        boolean result = userDao.save(user);
        applicationEventPublisher.publishEvent(new UserRegisterEvent(this,user));
        return user.getId();
    }

    @Override
    public UserInfoResp getUserInfo(Long uid) {
        User user = userDao.getById(uid);
        Integer modifyNameCount = userBackpackDao.getCountByValidItems(uid, ItemEnum.MODIFY_NAME_CARD.getId());
        return UserAdapter.buildUseInfoResp(user,modifyNameCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @RedissonLock(key = "#uid")
    public void modifyName(Long uid, String name) {
        User oldUser = userDao.getByName(name);
        AssertUtil.isEmpty(oldUser,"名字已经被抢占，请换一个");
        UserBackpack modifyNameCard = userBackpackDao.getFirstValidItem(uid, ItemEnum.MODIFY_NAME_CARD.getId());
        AssertUtil.isNotEmpty(modifyNameCard,"改名卡不足");
        boolean success = userBackpackDao.useItem(modifyNameCard);
        if(success){
            //改名
            userDao.modifyName(uid,name);
        }
    }

    @Override
    public List<BadgeResp> badges(Long uid) {
        List<ItemConfig> badges = itemCache.getByType(ItemTypeEnum.BADGE.getType());
        List<Long> badgeIds = badges.stream().map(ItemConfig::getId).collect(Collectors.toList());
        List<UserBackpack> userBackpacks = userBackpackDao.getOwnedBadge(uid, badgeIds);
        User user = userDao.getById(uid);
        return UserAdapter.buildBadgeResp(badges,userBackpacks,user);
    }

    @Override
    public void wearBadge(Long uid, Long itemId) {
        UserBackpack obtainedBadge = userBackpackDao.getFirstValidItem(uid, itemId);
        AssertUtil.isNotEmpty(obtainedBadge,"您还未拥有徽章，快去获得吧");
        ItemConfig item = itemConfigDao.getById(itemId);
        AssertUtil.equal(item.getType(),ItemTypeEnum.BADGE.getType(),"只有徽章才可以佩戴");
        userDao.wearBadge(uid,itemId);

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void blackUser(BlackReq blackReq) {
        Long uid = blackReq.getUid();
        Black blackUser = new Black();
        blackUser.setType(BlackTypeEnum.UID.getType());
        blackUser.setTarget(uid.toString());
        blackDao.save(blackUser);
        User userByIp = userDao.getById(uid);
        blackIpByUser(Optional.ofNullable(userByIp.getIpInfo()).map(IpInfo::getCreateIp).orElse(null));
        blackIpByUser(Optional.ofNullable(userByIp.getIpInfo()).map(IpInfo::getUpdateIp).orElse(null));
        applicationEventPublisher.publishEvent(new UserBlackEvent(this,userByIp));
    }

    private void blackIpByUser(String ip) {
        if(StringUtils.isBlank(ip)){
            return;
        }
        try{
            Black user = new Black();
            user.setType(BlackTypeEnum.IP.getType());
            user.setTarget(ip);
            blackDao.save(user);
        }catch (Exception e){
            log.error("拉黑失败");
        }

    }
}
