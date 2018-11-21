/**
 * FileName: ServiceListener
 * Author:   Ren Xiaotian
 * Date:     2018/11/21 16:38
 */

package com.lion.vip.api.srd;

import com.lion.vip.api.event.Event;

public interface ServiceListener extends Event {

    void onServiceAdded(String path, ServiceNode node);

    void onServiceUpdated(String path, ServiceNode node);

    void onServiceRemoved(String path, ServiceNode node);

}
