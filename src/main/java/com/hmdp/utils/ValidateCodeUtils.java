package com.hmdp.utils;

import java.util.Random;

/**
 * 随机生成验证码工具类
 */
public class ValidateCodeUtils {
    public static Integer generateValidateCode(int length) {
        Integer code;
        if (length == 4) {
            //生成随机数，最大为9999
            code = new Random().nextInt(9999);
            if (code < 1000) {
                code += 1000;
            }
        } else if (length == 6) {
            code = new Random().nextInt(999999);
            if (code < 100000) {
                code += 100000;
            }
        } else {
            throw new RuntimeException("只能生成4和6为数字验证码");
        }
        return code;
    }
    /**
     * 随机生成指定长度字符串验证码
     * @param length 长度
     * @return
     */
    public static String generateValidateCode4String(int length){
        Random rdm = new Random();
        String hash1 = Integer.toHexString(rdm.nextInt());
        String capstr = hash1.substring(0, length);
        return capstr;
    }
}
