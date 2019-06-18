package org.apache.servicecomb.saga.omega.transaction.accidentplatform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.servicecomb.saga.common.ConfigCenterType;
import org.apache.servicecomb.saga.omega.transaction.MessageSender;
import org.springframework.beans.factory.annotation.Autowired;

public class ClientAccidentHandlingService {

    @Autowired
    private MessageSender sender;

    public boolean reportMsgToAccidentPlatform(String jsonParams) {
        if (sender.readConfigFromServer(ConfigCenterType.AccidentReport.toInteger()).getStatus()) {// 差错平台上报支持配置降级功能，未降级场景才进行上报
            JsonObject jsonObject = new JsonParser().parse(jsonParams).getAsJsonObject();
            String serviceName = "", instanceId = "", globalTxId = "", localTxId = "",bizinfo = "";
            int type = 1;
            JsonElement jsonElement = jsonObject.get("servicename");
            if (jsonElement != null) {
                serviceName = jsonElement.getAsString();
            }
            jsonElement = jsonObject.get("instanceid");
            if (jsonElement != null) {
                instanceId = jsonElement.getAsString();
            }
            jsonElement = jsonObject.get("globaltxid");
            if (jsonElement != null) {
                globalTxId = jsonElement.getAsString();
            }
            jsonElement = jsonObject.get("localtxid");
            if (jsonElement != null) {
                localTxId = jsonElement.getAsString();
            }
            jsonElement = jsonObject.get("type");
            if (jsonElement != null) {
                type = jsonElement.getAsInt();
            }
            jsonElement = jsonObject.get("bizinfo");
            if (jsonElement != null) {
                bizinfo = jsonElement.getAsString();
            }
            AccidentHandling accident = new AccidentHandling(serviceName, instanceId, globalTxId, localTxId, type, bizinfo);
            return "true".equals(sender.reportAccidentToServer(accident));
        }
        return false;
    }

}