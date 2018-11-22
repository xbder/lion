/**
 * FileName: ConfigTools
 * Author:   Ren Xiaotian
 * Date:     2018/11/22 16:12
 */

package com.lion.vip.tools.config;


import com.lion.vip.tools.Utils;

public class ConfigTools {
    private ConfigTools() {
    }

    public static int getHeartbeat(int min, int max) {
        return Math.max(
                CC.lion.core.min_heartbeat,
                Math.min(max, CC.lion.core.max_heartbeat)
        );
    }

    /**
     * 获取内网IP地址
     *
     * @return 内网IP地址
     */
    public static String getLocalIp() {
        if (CC.lion.net.local_ip.length() > 0) {
            return CC.lion.net.local_ip;
        }
        return Utils.lookupLocalIP();
    }

    /**
     * 获取外网IP地址
     *
     * @return 外网IP地址
     */
    public static String getPublicIp() {

        if (CC.lion.net.public_ip.length() > 0) {
            return CC.lion.net.public_ip;
        }

        String localIp = getLocalIp();

        String remoteIp = CC.lion.net.public_ip_mapping.getString(localIp);

        if (remoteIp == null) {
            remoteIp = Utils.lookupExtranetIp();
        }

        return remoteIp == null ? localIp : remoteIp;
    }


    public static String getConnectServerRegisterIp() {
        if (CC.lion.net.connect_server_register_ip.length() > 0) {
            return CC.lion.net.connect_server_register_ip;
        }
        return getPublicIp();
    }

    public static String getGatewayServerRegisterIp() {
        if (CC.lion.net.gateway_server_register_ip.length() > 0) {
            return CC.lion.net.gateway_server_register_ip;
        }
        return getLocalIp();
    }
}