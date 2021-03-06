/**
 * Copyright 2020 OPSLI 快速开发平台 https://www.opsli.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opsli.core.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.opsli.api.base.result.ResultVo;
import org.opsli.api.web.system.menu.MenuApi;
import org.opsli.api.web.system.options.OptionsApi;
import org.opsli.api.wrapper.system.menu.MenuModel;
import org.opsli.api.wrapper.system.options.OptionsModel;
import org.opsli.core.cache.local.CacheUtil;
import org.opsli.core.cache.pushsub.msgs.MenuMsgFactory;
import org.opsli.core.cache.pushsub.msgs.OptionMsgFactory;
import org.opsli.core.msg.CoreMsg;
import org.opsli.plugins.redis.RedisPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static org.opsli.common.constants.OrderConstants.UTIL_ORDER;

/**
 * @BelongsProject: opsli-boot
 * @BelongsPackage: org.opsli.core.utils
 * @Author: Parker
 * @CreateTime: 2020-09-19 20:03
 * @Description: 参数工具类
 */
@Slf4j
@Order(UTIL_ORDER)
@Component
@Lazy(false)
public class OptionsUtil {

    /** 前缀 */
    public static final String PREFIX_CODE = "options:code:";


    /** Redis插件 */
    private static RedisPlugin redisPlugin;

    /** 参数 Api */
    private static OptionsApi optionsApi;


    /**
     * 根据 optionCode 获得参数
     * @param optionCode
     * @return
     */
    public static OptionsModel getOptionByCode(String optionCode){
        // 缓存Key
        String cacheKey = PREFIX_CODE + optionCode;

        // 先从缓存里拿
        OptionsModel model = CacheUtil.getTimed(OptionsModel.class, cacheKey);
        if (model != null){
            return model;
        }

        // 拿不到 --------
        // 防止缓存穿透判断
        boolean hasNilFlag = CacheUtil.hasNilFlag(cacheKey);
        if(hasNilFlag){
            return null;
        }

        try {
            // 分布式加锁
            if(!DistributedLockUtil.lock(cacheKey)){
                // 无法申领分布式锁
                log.error(CoreMsg.REDIS_EXCEPTION_LOCK.getMessage());
                return null;
            }

            // 如果获得锁 则 再次检查缓存里有没有， 如果有则直接退出， 没有的话才发起数据库请求
            model = CacheUtil.getTimed(OptionsModel.class, cacheKey);
            if (model != null){
                return model;
            }

            // 查询数据库
            ResultVo<OptionsModel> resultVo = optionsApi.getByCode(optionCode);
            if(resultVo.isSuccess()){
                model = resultVo.getData();
                // 存入缓存
                CacheUtil.put(cacheKey, model);
            }
        }catch (Exception e){
            log.error(e.getMessage(),e);
        }finally {
            // 释放锁
            DistributedLockUtil.unlock(cacheKey);
        }

        if(model == null){
            // 设置空变量 用于防止穿透判断
            CacheUtil.putNilFlag(cacheKey);
            return null;
        }

        return model;
    }


    // ============== 刷新缓存 ==============

    /**
     * 刷新参数 - 删就完了
     * @param option
     * @return
     */
    public static boolean refreshOption(OptionsModel option){
        if(option == null || StringUtils.isEmpty(option.getOptionCode())){
            return true;
        }

        // 计数器
        int count = 0;

        OptionsModel model = CacheUtil.getTimed(OptionsModel.class, PREFIX_CODE + option.getOptionCode());
        boolean hasNilFlag = CacheUtil.hasNilFlag(PREFIX_CODE + option.getOptionCode());

        // 只要不为空 则执行刷新
        if (hasNilFlag){
            count++;
            // 清除空拦截
            boolean tmp = CacheUtil.delNilFlag(PREFIX_CODE + option.getOptionCode());
            if(tmp){
                count--;
            }
        }

        if(model != null){
            count++;
            // 先删除
            boolean tmp = CacheUtil.del(PREFIX_CODE + option.getOptionCode());
            if(tmp){
                count--;
            }

            // 发送通知消息
            redisPlugin.sendMessage(
                    OptionMsgFactory.createOptionMsg(option)
            );
        }

        return count == 0;
    }




    // =====================================

    @Autowired
    public  void setRedisPlugin(RedisPlugin redisPlugin) {
        OptionsUtil.redisPlugin = redisPlugin;
    }

    @Autowired
    public void setOptionsApi(OptionsApi optionsApi) {
        OptionsUtil.optionsApi = optionsApi;
    }
}
